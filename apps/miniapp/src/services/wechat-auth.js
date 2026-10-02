const endpoint = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')

export function requestWechatLogin(role) {
  if (!['owner', 'technician'].includes(role)) {
    return Promise.reject(new Error('当前角色不使用微信授权登录'))
  }
  if (!endpoint) {
    return Promise.reject(new Error('尚未配置服务端地址，微信登录接口无法联调'))
  }

  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success: ({ code }) => {
        if (!code) {
          reject(new Error('微信未返回临时登录凭证'))
          return
        }
        uni.request({
          url: `${endpoint}/api/auth/wx-login`,
          method: 'POST',
          data: { code, role },
          success: ({ statusCode, data }) => {
            if (statusCode >= 200 && statusCode < 300 && data?.code === 0 &&
                (data?.data?.access_token || (role === 'technician' && data?.data?.status === 'BIND_REQUIRED' && data?.data?.binding_token))) {
              resolve(data.data)
            } else {
              reject(new Error(data?.message || '服务端尚未实现微信登录'))
            }
          },
          fail: () => reject(new Error('无法连接服务端，请检查合法域名与网络')),
        })
      },
      fail: () => reject(new Error('微信授权未完成，请重试')),
    })
  })
}

export function bindTechnician(bindingToken, employeeCode) {
  if (!endpoint || !bindingToken || !employeeCode) {
    return Promise.reject(new Error('请输入有效员工码并重新登录'))
  }
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${endpoint}/api/auth/technician/bind`,
      method: 'POST',
      header: { Authorization: `Bearer ${bindingToken}` },
      data: { employee_code: employeeCode },
      success: ({ statusCode, data }) => {
        if (statusCode >= 200 && statusCode < 300 && data?.code === 0 && data?.data?.access_token) {
          resolve(data.data)
        } else {
          reject(new Error(data?.message || '员工码绑定失败'))
        }
      },
      fail: () => reject(new Error('无法连接服务端，请检查网络')),
    })
  })
}

export function bindOwnerPhone(accessToken, phoneCode) {
  if (!endpoint || !accessToken || !phoneCode) {
    return Promise.reject(new Error('请先登录并同意微信手机号授权'))
  }
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${endpoint}/api/auth/phone/bind`,
      method: 'POST',
      header: { Authorization: `Bearer ${accessToken}` },
      data: { code: phoneCode },
      success: ({ statusCode, data }) => {
        if (statusCode >= 200 && statusCode < 300 && data?.code === 0 && data?.data?.phone_bound) {
          resolve(data.data)
        } else {
          reject(new Error(data?.message || '手机号绑定失败'))
        }
      },
      fail: () => reject(new Error('无法连接服务端，请检查网络')),
    })
  })
}

export function logoutWechat(accessToken) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${endpoint}/api/auth/logout`,
      method: 'POST',
      header: { Authorization: `Bearer ${accessToken}` },
      success: ({ statusCode, data }) => {
        if (statusCode >= 200 && statusCode < 300 && data?.code === 0) resolve()
        else reject(new Error(data?.message || '退出失败'))
      },
      fail: () => reject(new Error('无法连接服务端，请检查网络')),
    })
  })
}

export function requestMerchantCode(account, password) {
  return merchantRequest('/api/auth/merchant/code', { account, password })
}

export function requestMerchantLogin(account, password, smsCode) {
  return merchantRequest('/api/auth/merchant/login', { account, password, sms_code: smsCode })
}

function merchantRequest(path, data) {
  if (!endpoint) return Promise.reject(new Error('尚未配置服务端地址，商家登录接口无法联调'))
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${endpoint}${path}`,
      method: 'POST',
      data,
      success: ({ statusCode, data: response }) => {
        if (statusCode >= 200 && statusCode < 300 && response?.code === 0) resolve(response.data)
        else reject(new Error(response?.message || '商家身份验证失败'))
      },
      fail: () => reject(new Error('无法连接服务端，请检查网络')),
    })
  })
}
