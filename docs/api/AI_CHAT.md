# 车主端 AI 管家接口

更新日期：2026-10-07。对应 Spec F05 的对话部分。运维与验收步骤见 [AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md)。

## 1. 范围与权限

| 项 | 说明 |
| --- | --- |
| 路径 | `POST /api/ai/chat` |
| 会话要求 | 仅正式 `OWNER` 会话。未登录 `401`+`40100`，商家/技师/微信绑定态 `403`+`40300` |
| 幂等 | 只读接口，不写业务表，无需幂等键 |
| 缓存 | 成功与降级响应均带 `Cache-Control: no-store` |
| 会话存储 | 服务端不落库。历史完全由客户端携带，因此没有跨设备恢复，也没有对话表 |

## 2. 请求

```json
{
  "message": "刹车有异响，可能是什么原因？",
  "vehicle_id": 7,
  "history": [
    { "role": "user", "content": "上次保养做了什么？" },
    { "role": "assistant", "content": "2026-09-20 更换了机油机滤。" }
  ]
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `message` | 是 | 字符串，去首尾空格后长度 1–2000 |
| `vehicle_id` | 否 | 整数，`> 0` 且不超过 JS 安全整数上限。必须属于当前车主 |
| `history` | 否 | 数组，最多 8 项；每项**只能**含 `role` 与 `content` 两个字段；`role` ∈ {`user`, `assistant`}；`content` 非空且 ≤2000 字 |

**未知字段一律拒绝**：请求体顶层出现 `message`/`vehicle_id`/`history` 之外的字段即 `400`+`40001`。
`history` 中的多余字段同样判为格式无效。这是刻意的严格白名单，避免前端误传字段被静默忽略。

## 3. 响应

成功（HTTP `200`）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "reply": "刹车异响常见于刹车片磨损到极限、刹车盘生锈或有异物…",
    "model": "deepseek-flash",
    "grounded": true,
    "vehicle_id": 7
  },
  "request_id": "…"
}
```

| 字段 | 说明 |
| --- | --- |
| `reply` | 模型回答正文（简体中文，服务端已限制约 400 字以内） |
| `model` | 实际使用的模型标识，便于核对是否误用高价模型 |
| `grounded` | 是否注入了车辆档案上下文。`true` 表示回答基于车主本人的车辆与档案 |
| `vehicle_id` | 生效的车辆 ID；未传或档案库不可用时为 `null` |

## 4. 错误码

| HTTP | `code` | 触发条件 |
| --- | --- | --- |
| `400` | `40001` | 未知字段、`message` 缺失/为空/超 2000 字、`vehicle_id` 非正整数、`history` 格式错误或超过 8 项 |
| `401` | `40100` | 未登录或令牌已失效 |
| `403` | `40300` | 非车主会话 |
| `404` | `40400` | `vehicle_id` 不属于当前车主，或车辆不存在 |
| `429` | `42900` | 上游限流 |
| `503` | `50301` | 未配置密钥、密钥无效，或上游 5xx/超时/返回体异常 |

`50301` 与 `42900` 由 `ApiExceptionHandler` 中专门针对 `AiUpstreamException` 的分支产生。

## 5. 档案上下文与隐私边界

传入 `vehicle_id` 时，后端先校验车辆归属，再把下列内容拼进 system 提示词：

- 车型名称、当前里程、动力类型
- 最近 5 条档案的类型、标题、备注、里程、记录日期

**以下内容绝不进入上下文**：车牌号、VIN。备注单条截断 200 字，上下文整体上限 1800 字。
原因是这些内容会离开平台、发送给第三方模型；若后续要放宽，需先在录入页向车主明确告知。

档案库读取失败（`DataAccessException`）时**不阻断对话**：降级为不带档案的通用回答，仍返回 `200`，
此时 `grounded=false`、`vehicle_id=null`。

## 6. 配置

服务端按进程环境变量判断可用性，配置项见 [AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md) 第 2 节。
密钥只存在于服务端，接口不回显密钥，错误信息也不含上游原文。

## 7. 未实现

- **流式输出**：当前为非流式整段返回，用户需等待数秒。云托管 `callContainer` 单次请求上限 15s，慢回答会超时。
- **对话持久化与用量配额**：不落库、无计费统计。
- **`POST /api/ai/plan`**：方案生成、`ai_plan_rule` 与商家/同款经验联动未实现。

## 8. 测试

- 后端：`AiChatHttpTest`（角色、参数、降级、越权）、`DeepSeekClientTest`（URL/鉴权头/payload/错误码映射，mock `HttpClient`）、`AiContextProviderTest`（上下文格式、脱敏、坏 JSON 容错、截断）
- 小程序：`apps/miniapp/test/ai-chat.test.js`
- 这些测试**不访问真实上游**；真实链路的验证记录见 [AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md) 第 6 节。
