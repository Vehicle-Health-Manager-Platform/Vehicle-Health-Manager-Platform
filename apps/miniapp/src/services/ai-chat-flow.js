import { ServiceError, serviceFailure } from './service-catalog.js'
import { MAX_HISTORY_TURNS, MAX_QUESTION_CHARS } from './ai-chat.js'

export const initialAiChatState = () => ({ messages: [], busy: false, loaded: false, message: '', failureKind: '', retryable: false })

// 对话流：保持消息顺序、丢弃过期响应、失败后保留提问并允许原样重试。
export function createAiChatFlow({ state, api, token, vehicleId }) {
  let generation = 0
  let retryTurn = null
  const suspend = () => { generation++; state.busy = false }
  const reset = () => { suspend(); retryTurn = null; Object.assign(state, initialAiChatState()) }
  const historyOf = messages => messages
    .filter(item => item.role === 'user' || item.role === 'assistant')
    .map(({ role, content }) => ({ role, content }))
    .slice(-MAX_HISTORY_TURNS)

  async function deliver(prompt, history) {
    const owner = token(), current = ++generation
    state.busy = true; state.message = ''; state.failureKind = ''; state.retryable = false
    try {
      if (!owner) throw new ServiceError('unauthorized', '请先登录车主账号')
      const result = await api.chat(owner, { message: prompt, vehicleId: vehicleId?.() ?? null, history })
      if (current !== generation || token() !== owner) return
      state.messages = [...state.messages, { role: 'assistant', content: result.reply, grounded: result.grounded }]
      state.loaded = true; retryTurn = null
    } catch (error) {
      if (current !== generation || token() !== owner) return
      const safe = serviceFailure(error)
      state.message = safe.message; state.failureKind = safe.kind
      state.retryable = safe.kind !== 'invalid' && safe.kind !== 'unauthorized' && safe.kind !== 'forbidden'
      retryTurn = state.retryable ? { prompt, history } : null
    } finally { if (current === generation) state.busy = false }
  }

  function send(input) {
    if (state.busy) return Promise.resolve()
    const prompt = typeof input === 'string' ? input.trim() : ''
    if (!prompt || prompt.length > MAX_QUESTION_CHARS) {
      state.message = `请输入 1–${MAX_QUESTION_CHARS} 字的用车问题`
      state.failureKind = 'invalid'; state.retryable = false; retryTurn = null
      return Promise.resolve()
    }
    const history = historyOf(state.messages)
    state.messages = [...state.messages, { role: 'user', content: prompt }]
    return deliver(prompt, history)
  }

  function retry() {
    if (!retryTurn || state.busy) return Promise.resolve()
    return deliver(retryTurn.prompt, retryTurn.history)
  }

  return { send, retry, suspend, reset }
}
