import { computed, ref } from 'vue'
import { AuthError, authApi } from './wechat-auth.js'
import { ownerSession, clearOwnerSession, setOwnerSession } from './owner-session.js'

export function useRoleIdentity(role, api = authApi) {
  const operation = ref('')
  const phase = ref('idle')
  const message = ref(role === 'merchant' ? '使用商家账号、密码和短信验证码登录' : '请使用微信验证身份')
  const failureKind = ref('')
  const roleToken = ref('')
  const accessToken = computed({
    get: () => role === 'owner' ? ownerSession.accessToken : roleToken.value,
    set: (value) => { if (role === 'owner') ownerSession.accessToken = value; else roleToken.value = value },
  })
  const phoneBound = computed(() => role === 'owner' && ownerSession.phoneBound)
  const requiresLogin = ref(false)
  if (accessToken.value) message.value = phoneBound.value ? '车主已登录，手机号已绑定' : '车主已登录，可继续授权绑定手机号'
  const bindingToken = ref('')
  const employeeCode = ref('')
  const merchantAccount = ref('')
  const merchantPassword = ref('')
  const smsCode = ref('')
  const busy = computed(() => Boolean(operation.value))
  let retryAction = null
  const canRetry = ref(false)

  function session(result) {
    requiresLogin.value = false
    accessToken.value = result.access_token
    if (role === 'owner') setOwnerSession(result)
  }

  function invalid(text) {
    phase.value = 'error'
    failureKind.value = 'invalid'
    message.value = text
    canRetry.value = false
  }

  async function run(name, pending, action, retry) {
    if (busy.value) return
    operation.value = name
    phase.value = 'loading'
    message.value = pending
    failureKind.value = ''
    canRetry.value = false
    retryAction = null
    try {
      await action()
      phase.value = 'success'
    } catch (error) {
      phase.value = 'error'
      failureKind.value = error instanceof AuthError ? error.kind : 'server'
      message.value = error instanceof AuthError ? error.message : '操作失败，请稍后重试'
      requiresLogin.value = Boolean(accessToken.value && ['unauthorized', 'forbidden'].includes(failureKind.value))
      if (name === 'bind' && ['unauthorized', 'forbidden'].includes(failureKind.value)) {
        bindingToken.value = ''
        employeeCode.value = ''
        message.value = '绑定身份无效或无权限，请重新微信登录'
      }
      canRetry.value = Boolean(retry && ['network', 'timeout', 'server', 'unavailable', 'wechat', 'protocol'].includes(failureKind.value))
      retryAction = canRetry.value ? retry : null
    } finally {
      operation.value = ''
    }
  }

  function tryLogin() {
    return run('login', '正在验证微信身份…', async () => {
      const result = await api.requestWechatLogin(role)
      if (result.status === 'BIND_REQUIRED') {
        bindingToken.value = result.binding_token
        message.value = '微信身份已验证，请输入商家发放的员工码'
      } else {
        session(result)
        bindingToken.value = ''
        message.value = role === 'owner' && !result.user.phone_bound ? '车主登录成功，可继续授权绑定手机号' : '身份验证成功'
      }
    }, tryLogin)
  }

  function tryBind() {
    if (busy.value) return
    if (!employeeCode.value.trim()) return invalid('请输入商家发放的员工码')
    return run('bind', '正在绑定技师身份…', async () => {
      session(await api.bindTechnician(bindingToken.value, employeeCode.value))
      bindingToken.value = ''
      employeeCode.value = ''
      message.value = '技师身份绑定成功'
    }, tryBind)
  }

  function tryBindPhone(event) {
    if (busy.value) return
    const code = event?.detail?.code
    if (!code) return invalid('未获得手机号授权，可再次点击授权按钮')
    // A phone code is short lived and single use. Retry requires fresh authorization.
    return run('phone', '正在绑定手机号…', async () => {
      await api.bindOwnerPhone(accessToken.value, code)
      setOwnerSession({ access_token: accessToken.value, user: { phone_bound: true } })
      message.value = '手机号已验证并绑定'
    })
  }

  function tryLogout() {
    return run('logout', '正在退出登录…', async () => {
      await api.logoutWechat(accessToken.value)
      accessToken.value = ''
      if (role === 'owner') clearOwnerSession()
      requiresLogin.value = false
      bindingToken.value = ''
      employeeCode.value = ''
      merchantPassword.value = ''
      smsCode.value = ''
      message.value = '已安全退出登录'
    }, tryLogout)
  }

  function validMerchant() {
    if (!merchantAccount.value.trim() || merchantAccount.value.trim().length > 64 ||
        !merchantPassword.value.trim() || merchantPassword.value.length > 256) {
      invalid('请输入有效商家账号和密码')
      return false
    }
    return true
  }

  function tryMerchantCode() {
    if (busy.value || !validMerchant()) return
    return run('code', '正在发送短信验证码…', async () => {
      await api.requestMerchantCode(merchantAccount.value.trim(), merchantPassword.value)
      smsCode.value = ''
      message.value = '验证码已发送至账号绑定的手机号，5 分钟内有效'
    }, tryMerchantCode)
  }

  function tryMerchantLogin() {
    if (busy.value || !validMerchant()) return
    if (!/^\d{6}$/.test(smsCode.value.trim())) return invalid('请输入六位数字短信验证码')
    return run('merchant-login', '正在验证商家身份…', async () => {
      session(await api.requestMerchantLogin(merchantAccount.value.trim(), merchantPassword.value, smsCode.value.trim()))
      merchantPassword.value = ''
      smsCode.value = ''
      message.value = '商家登录成功'
    }, tryMerchantLogin)
  }

  function retry() { if (!busy.value && canRetry.value) return retryAction?.() }

  function restartLogin() {
    if (busy.value) return
    // Explicit recovery after rejection. This clears local state, not a remote session.
    accessToken.value = ''
    if (role === 'owner') clearOwnerSession()
    requiresLogin.value = false
    phase.value = 'idle'
    failureKind.value = ''
    canRetry.value = false
    retryAction = null
    message.value = '请重新验证身份'
  }

  return { operation, phase, message, failureKind, accessToken, phoneBound, requiresLogin, bindingToken, employeeCode,
    merchantAccount, merchantPassword, smsCode, busy, canRetry, tryLogin, tryBind, tryBindPhone,
    tryLogout, tryMerchantCode, tryMerchantLogin, retry, restartLogin }
}
