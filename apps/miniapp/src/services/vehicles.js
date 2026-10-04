export class VehicleError extends Error {
  constructor(kind, message) { super(message); this.kind = kind }
}
const id = value => Number.isSafeInteger(value) && value > 0
const text = value => typeof value === 'string'
const messages = {
  400: ['invalid', '车辆信息无效，请检查后重新提交'],
  401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '当前身份无权操作，请重新登录车主账号'],
  404: ['missing', '车型已不可用，请重新选择'],
  409: ['conflict', '该车牌或VIN已登记，请返回查看本人车辆'],
  429: ['limited', '操作过于频繁，请稍后重试'],
  503: ['unavailable', '车辆服务暂不可用，请稍后重试'],
}
export const vehicleFailure = error => error instanceof VehicleError ? error : new VehicleError('network', '无法连接车辆服务，请检查网络后重试')
export function vehicleBody(fields) {
  const plate = fields.plate.trim().toUpperCase(), vin = fields.vin.trim().toUpperCase()
  if (!id(fields.modelId) || !/^\d{1,10}$/.test(fields.mileage) || Number(fields.mileage) > 2147483647
    || (plate && !/^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5,6}$/.test(plate))
    || (vin && !/^[A-HJ-NPR-Z0-9]{17}$/.test(vin))) throw new VehicleError('invalid', '请选择车型，填写非负整数里程，并检查车牌或17位VIN')
  return { add_type: 4, model_id: fields.modelId, current_mileage: Number(fields.mileage), plate_no: plate, vin }
}
export function createVehicleApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new VehicleError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new VehicleError('unconfigured', '车辆服务尚未配置，请联系管理员')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) { reject(new VehicleError('protocol', '车辆服务响应异常，请重试')); return }
            if (statusCode < 200 || statusCode >= 300) {
              const [kind, message] = messages[statusCode] || ['server', '车辆请求未成功，请稍后重试']
              reject(new VehicleError(kind, message)); return
            }
            if (data?.code !== 0 || !validate(data.data)) { reject(new VehicleError('protocol', '车辆服务响应异常，请重试')); return }
            resolve(data.data)
          },
          fail: () => reject(new VehicleError('network', '无法连接车辆服务，请检查网络后重试')),
        })
      } catch { reject(new VehicleError('network', '无法连接车辆服务，请稍后重试')) }
    })
  }
  const paged = (result, page, size, row) => Array.isArray(result?.list) && result.list.length <= size
    && Number.isSafeInteger(result.total) && result.total >= result.list.length && result.total >= 0
    && result.page === page && result.page_size === size && result.list.every(row)
  return {
    list(token, page = 1) {
      return request(token, `/api/vehicle/list?page=${page}&page_size=20`, 'GET', undefined, undefined,
        result => paged(result, page, 20, item => id(item.vehicle_id) && Number.isSafeInteger(item.current_mileage)
          && item.current_mileage >= 0 && text(item.model_name) && text(item.plate_no_masked) && text(item.vin_masked)))
    },
    catalog(token, kind, parent = 0, page = 1) {
      if (!['brand', 'series', 'model'].includes(kind) || (kind !== 'brand' && !id(parent))) throw new VehicleError('invalid', '请先选择品牌和车系')
      const query = kind === 'brand' ? '' : `&${kind === 'series' ? 'brand_id' : 'series_id'}=${parent}`
      return request(token, `/api/${kind}/list?page=${page}&page_size=100${query}`, 'GET', undefined, undefined,
        result => paged(result, page, 100, item => id(item.id) && (kind === 'model' ? text(item.year) && text(item.config_name) : text(item.name))))
    },
    add(token, body, key) {
      if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new VehicleError('invalid', '保存信息无效，请重新尝试')
      return request(token, '/api/vehicle/add', 'POST', body, key,
        result => id(result?.vehicle_id) && text(result.model_name) && result.need_archive === true)
    },
  }
}
export const vehicleApi = createVehicleApi({ baseUrl: import.meta.env?.VITE_API_BASE_URL, runtime: () => uni })
