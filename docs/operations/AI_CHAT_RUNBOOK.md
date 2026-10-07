# 车主端 AI 管家接入与验收清单

更新日期：2026-10-07。适用于车主端「AI」Tab 的对话能力，覆盖 `POST /api/ai/chat`、DeepSeek 接入、
档案上下文注入与降级行为。接口契约以 [Spec §4.4](../SPEC.md)（F05）与本文为准。

## 1. 能力范围

| 已实现 | 说明 |
| --- | --- |
| 车主对话 | `POST /api/ai/chat`，仅 `OWNER` 会话可访问（商家/技师/微信绑定态返回 `40300`）。 |
| 档案感知 | 传入 `vehicle_id` 时，后端校验车辆属于本人，把车型、里程、动力类型与最近 5 条档案拼成上下文；未传车辆时按通用知识回答。 |
| 降级 | 未配置密钥、上游失败返回 `503` + `50301`；限流返回 `429` + `42900`；档案库读不到时降级为通用回答而不阻断对话。 |
| 无状态 | 服务端不保存会话，历史由客户端携带（最多 8 轮），因此不需要新增对话表，也没有跨设备恢复。 |

| 未实现 | 说明 |
| --- | --- |
| 流式输出 | 当前为非流式整段返回。云托管 `callContainer` 单次请求上限 15s，若后续要流式需另做通道评估。 |
| 方案/商家推荐落库 | `POST /api/ai/plan`、`ai_plan_rule` 与商家/同款经验联动未实现。 |
| 转人工 | Spec §5.5 的“转人工在线确认”三态未实现。 |
| 对话持久化与用量统计 | 未落库、未做配额与计费统计。 |

## 2. 配置项

密钥只进入后端环境，绝不写入小程序、仓库或构建产物。

> ⚠️ **易错点（2026-10-07 实际踩过）**：`.gitignore` 里有 `!.env.example` 例外，所以 **`.env.example` 是被 Git 跟踪的**。
> 把真实密钥填进 `.env.example` 既不会生效（后端读的是进程环境变量，不是这个文件），又会在下次提交时泄露到版本库。
> 真实密钥只能放在 `.env.auth-backend.local`（匹配 `*.local`，已被忽略）或云托管控制台的环境变量里。
> 模板文件的 `DEEPSEEK_API_KEY` 必须保持 `your-deepseek-api-key` 占位值。

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `DEEPSEEK_API_KEY` | 是 | 空 | 真实密钥。留空或 `unconfigured` 时接口返回 `50301`。 |
| `DEEPSEEK_BASE_URL` | 否 | `https://api.deepseek.com` | OpenAI 兼容入口，尾部斜杠会自动去掉。 |
| `DEEPSEEK_MODEL` | 否 | `deepseek-flash` | `deepseek-flash` 对应 DeepSeek-V4.1-Flash（非思考）；`deepseek-v4-pro` 质量更高、单价约 3 倍。 |
| `DEEPSEEK_THINKING` | 否 | `disabled` | `enabled` 开启思考模式，延迟与费用更高。 |
| `AI_PROVIDER` | 否 | `deepseek` | 仅作记录，代码按 `DEEPSEEK_*` 判断是否可用。 |

## 3. 启用步骤

### 本机后端

1. 在私有 `.env.auth-backend.local` 把 `DEEPSEEK_API_KEY=unconfigured` 改成真实密钥（该文件已被 `.gitignore` 的 `*.local` 忽略）。
   **不要改 `.env.example`**，见第 2 节的易错点。
2. 用同一个 `--env-file .env.auth-backend.local` 重建后端容器（保持镜像、数据卷、网络与端口不变）。
3. 等 `/actuator/health` 返回 `UP` 后执行第 5 节验收。

> 环境变量只在容器**创建时**读取。改完 `.env` 文件后必须重新 `docker run` 一次，
> 只 `docker restart` 不会加载新值。可以用
> `docker exec <容器> printenv DEEPSEEK_API_KEY` 确认容器内是否已经拿到密钥（只核对长度/前缀，不要打印全文）。

### 微信云托管

1. 在云托管服务的环境变量里配置同一组 `DEEPSEEK_*`，密钥不要在聊天、工单或仓库里粘贴。
2. 重新部署一次服务使环境变量生效。
3. 注意：`callContainer` 单次请求上限 15s，而 AI 回答通常更慢；云托管模式下 AI 对话可能超时，需另行评估（缩短 `max_tokens`、换成流式或让 AI 走独立通讯域名）。

## 4. 安全与隐私边界

- 密钥只在服务端使用，接口不回显密钥，错误信息不含上游原文。
- 注入模型的档案上下文**只含**车型、里程、动力类型、档案类型/标题/备注/里程；**车牌与 VIN 不进入上下文**，备注单条截断 200 字、整体上限 1800 字。
- 上传到第三方模型的内容包含车主填写的档案备注，属于既定的功能取舍；若后续要收紧，需在录入页明确告知或改为只发送结构化字段。
- `vehicle_id` 必须属于当前车主，否则返回 `404`；接口不信任前端传入的其他资源 ID。
- 对话接口只读，不写业务表，因此不需要幂等键。

## 5. 验收清单

状态列以 2026-10-07 本机真实链路验证为准（证据见第 6 节）。

| 项 | 操作 | 期望 | 状态 |
| --- | --- | --- | --- |
| A0 | 未配置 `DEEPSEEK_API_KEY` 时用车主会话提问 | HTTP `503`、`code=50301`、`Cache-Control: no-store`；其余功能不受影响 | 未配置期间离线测试覆盖 |
| A1 | 匿名、商家、技师会话调用 `/api/ai/chat` | `401`+`40100` 或 `403`+`40300`，不产生上游调用 | 匿名/绑定态已实测；商家/技师由离线测试覆盖 |
| A2 | 车主会话，不传 `vehicle_id` 提问 | `200`，`data.grounded=false`、`data.vehicle_id=null` | 已通过 |
| A3 | 车主会话，传本人 `vehicle_id` 提问 | `200`，`data.grounded=true`，回答与档案记录一致 | 已通过（回答复述了档案中的车型与里程） |
| A4 | 传他人 `vehicle_id` | `404`+`40400` | 已通过 |
| A5 | 消息为空、超 2000 字、含未知字段、`history` 超 8 轮 | `400`+`40001` | 已通过（含 `vehicle_id` 非法） |
| A6 | 连续快速提问 | 上游限流时 `429`+`42900`，页面出现"稍后重试"并可重试 | 未触发（需实际限流） |
| A7 | 小程序：发送、失败后重试、换车后开新对话 | 问题不重复入列、失败保留原提问可重试、换车清空旧对话 | 离线测试覆盖；未在真机复验 |
| A8 | 真机（测试号局域网路径，见[真机登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)） | 真实登录后可在手机完成一次提问与回答 | 待验收 |

隐私断言（不属于 A0–A8，但每次真实验收都应复核）：用本人车辆提问"我的车牌号是什么"，
回答不得出现车牌号。已实测：模型回答"车辆档案里没有车牌号信息"，未泄露。

## 6. 本机真实验证记录（2026-10-07）

真实 `DEEPSEEK_API_KEY` 配置完成后，在本机 Docker 后端验证了完整链路。

**上游直连**（绕过本平台，单独确认密钥与模型名有效）：

| 项 | 结果 |
| --- | --- |
| 端点 | `POST https://api.deepseek.com/chat/completions` |
| 结果 | HTTP `200`，约 `1.1s` |
| 返回 `model` | `deepseek-flash`（与 `DEEPSEEK_MODEL` 一致） |
| 用量 | `prompt_tokens=22`、`completion_tokens=39` |

**平台完整链路**：用 `scripts/verify_ai_chat_local.py` 对 `http://127.0.0.1:18080/api/ai/chat`
执行 **19 项检查，全部通过**。关键结果：

- A2 通用提问返回 `grounded=false`、`vehicle_id=null`、`model=deepseek-flash`，响应 `Cache-Control: no-store`；
- A3 传入本人车辆 `9100699` 后 `grounded=true`，且**回答确实复述了档案内容**——"车型为本地合成测试品牌…当前里程 5290367 km，近期养护记录有 5 条，均为 2026-10-06 的保养"，
  与库中该车的 `current_mileage`、5 条 `input_type IN (1,3)` 档案一致，证明档案上下文端到端注入成功；
- 隐私断言通过：追问"我的车牌号是什么"，模型回答"车辆档案里没有车牌号信息"，**车牌 `京B90366` 未出现在回复中**；
- A4 传他人车辆 `9100690` 返回 `404`+`40400`；A5 六类非法参数均返回 `400`+`40001`；匿名 `401`+`40100`、微信绑定态令牌 `403`+`40300`。

复现命令（需先用一条活跃的 OWNER 会话 ID 作为 `--jti`，脚本头部注释给出查询 SQL）：

```
python scripts/verify_ai_chat_local.py --jti <活跃车主会话ID> \
    --owner-vehicle-id 9100699 --foreign-vehicle-id 9100690 --plate 京B90366
```

该脚本用 `JWT_SECRET` 自签令牌，**仅限本机验证，不是可用的鉴权途径**。A6（上游限流）未触发，
A7/A8（小程序交互与真机）未在真机复验，A0（未配置降级）在配置密钥后已无法在现场复现，均由离线测试覆盖。

## 7. 失败排查

| 现象 | 排查 |
| --- | --- |
| `50301 / AI 管家尚未配置` | `DEEPSEEK_API_KEY` 未生效：确认容器/云托管服务已重建并读到了环境变量。 |
| `50301 / AI 管家密钥无效` | 密钥错误或已失效（上游返回 401/403）。 |
| `50301 / AI 管家暂时不可用` | 上游 5xx、返回体异常或超时；看后端日志的 `AiUpstreamException`。 |
| `42900` | 上游限流；降低并发或稍后重试。 |
| 回答没有结合档案 | 页面未选车，或该车确实没有档案记录；确认请求体带了 `vehicle_id`。 |
| 小程序提示 `AI 管家尚未接入` | 构建产物缺少后端地址，或处于云托管模式但未部署服务。 |
| 云托管下等待后超时 | `callContainer` 15s 上限，见第 3 节。 |
| 所有请求都返回 `401`+`40100` | 密钥已改但仍 401：注意这是**鉴权**失败而非 AI 失败。除了验签，`JwtDecoder` 还要求令牌 `jti` 在 `auth_session` 表中未撤销未过期，需要重新登录取得新会话。 |

## 8. 相关文件

- 后端：`backend/src/main/java/com/autocare/platform/ai/`（`DeepSeekClient`、`AiContextProvider`、`AiChatController`）
- 接口契约：[车主端 AI 管家接口](../api/AI_CHAT.md)
- 小程序：`apps/miniapp/src/services/ai-chat.js`、`ai-chat-flow.js`、`apps/miniapp/src/pages/ai/index.vue`
- 测试：`AiChatHttpTest`、`DeepSeekClientTest`、`AiContextProviderTest`、`apps/miniapp/test/ai-chat.test.js`
- 本机真实验证脚本：`scripts/verify_ai_chat_local.py`
