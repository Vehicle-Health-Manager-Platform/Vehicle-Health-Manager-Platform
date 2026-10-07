import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'

// 与后端 AiChatController 的契约保持一致：问题 1–1000 字、历史最多 8 轮。
export const MAX_QUESTION_CHARS = 1000
export const MAX_HISTORY_TURNS = 8
export const MAX_REPLY_CHARS = 8000

const isText = value => typeof value === 'string'
const safeVehicle = value => value === null || (Number.isSafeInteger(value) && value > 0)
const answer = value => isText(value?.reply) && value.reply.trim().length > 0 && value.reply.length <= MAX_REPLY_CHARS
  && isText(value.model) && value.model.length > 0 && value.model.length <= 64
  && typeof value.grounded === 'boolean' && safeVehicle(value.vehicle_id)
  && value.grounded === (value.vehicle_id !== null)

const failures = {
  400: ['invalid', '问题内容无效，请修改后重试'],
  401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '请使用车主账号与 AI 管家对话'],
  404: ['missing', '所选车辆的档案不可用，请重新选择车辆'],
  429: ['limited', '提问过于频繁，请稍后重试'],
  503: ['unavailable', 'AI 管家暂时不可用，可先查看档案或稍后重试'],
}

export function createAiChatApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, payload, validate) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', 'AI 管家尚未接入，请联系管理员')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({
          url: `${endpoint}/api/ai/chat`, method: 'POST', data: payload, timeout: 60000,
          header: { Authorization: `Bearer ${token}`, 'content-type': 'application/json' },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new ServiceError('protocol', 'AI 管家响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              const [kind, message] = failures[statusCode] || ['server', 'AI 管家请求未成功，请稍后重试']
              return reject(new ServiceError(kind, message))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', 'AI 管家响应异常，请重试'))
            resolve(data.data)
          },
          fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    chat(token, { message, vehicleId = null, history = [] } = {}) {
      const question = isText(message) ? message.trim() : ''
      if (!question || question.length > MAX_QUESTION_CHARS) throw new ServiceError('invalid', `请输入 1–${MAX_QUESTION_CHARS} 字的用车问题`)
      if (vehicleId !== null && (!Number.isSafeInteger(vehicleId) || vehicleId <= 0)) throw new ServiceError('invalid', '车辆编号无效')
      if (!Array.isArray(history) || history.length > MAX_HISTORY_TURNS) throw new ServiceError('invalid', '历史对话过长，请开始新的对话')
      const turns = history.map(item => ({ role: item?.role, content: isText(item?.content) ? item.content : '' }))
      if (turns.some(item => !['user', 'assistant'].includes(item.role) || !item.content || item.content.length > MAX_QUESTION_CHARS))
        throw new ServiceError('invalid', '历史对话格式无效')
      const payload = vehicleId === null ? { message: question, history: turns } : { message: question, vehicle_id: vehicleId, history: turns }
      return request(token, payload, answer)
    },
  }
}
export const aiChatApi = createAiChatApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
