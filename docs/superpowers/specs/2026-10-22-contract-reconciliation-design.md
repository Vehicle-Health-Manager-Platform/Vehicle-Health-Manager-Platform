# 契约逐项结论（缺口销项）设计

## 问题

覆盖核对器一直报告"352 项契约 / 315 项有四类历史证据 / 缺口 37 项"。这个"缺口"把四件完全不同的事混成一个数字：

1. 契约里还留着早期设计占位，功能早已由另一个具名接口承担（例如 `/api/home/dashboard` → `/api/vehicle/{vehicle}/health-dashboard`）；
2. 功能有，但必须接真实微信/短信/大模型/OCR 上游，本地无法验收；
3. 功能压根没做（例如行驶证 OCR）；
4. 功能做了却没有成功路径（通用商家动作接口四个动作全部被封堵）。

结果是每轮"补证据"都要重新人工排查一遍，且名单看起来永远清不完。**缺口的真正问题是缺结论，不是缺证据。**

## 方案

引入一个**结论文件 + 门禁**，而不是改覆盖口径：

- `docs/api/CONTRACT_VERDICTS.json`：人工维护的结论表，每条形如 `{operation, verdict, replacement?, reason}`；
- `scripts/reconcile_contract.py`：把契约、覆盖结果与结论表三方对齐，生成 `docs/api/CONTRACT_RECONCILIATION.md`；`--check` 供 CI 用；
- 覆盖核对器**数字不变**（仍然是 315 有证据 / 37 无证据），只是在报告里链到结论表。

不把"有结论"算成"有证据"，也不把结论项从契约里删掉——删条目会掩盖"当初设计过、后来被替代"的历史。

## 结论类型

| 结论 | 含义 | 额外要求 |
| --- | --- | --- |
| `superseded` | 已被另一个具名实现替代 | 必须给 `replacement`，且该替代在契约中存在、带实现标记 |
| `internal-only` | 语义由服务内部事务完成，没有 HTTP 入口 | 不给替代 |
| `blocked-external` | 实现存在，但依赖未接入的外部上游 | 不给替代 |
| `not-implemented` | 契约有、代码无，需要先立项 | 不给替代 |
| `no-success-path` | 实现存在但没有 200 路径 | 不给替代 |
| `dev-only` | 仅本地演示/开发端点，不构成生产契约 | 不给替代 |

## 门禁规则（fail-closed）

1. 结论必须覆盖**全部**"未同时满足实现标记与四类证据"的操作，一个不能少（缺一条即失败，报出具体操作）。
2. 结论不得出现**已实现且有四类证据**的操作上——防止用一句"已替代"把真实证据缺口抹掉。
3. `superseded` 的替代必须是契约内、带实现标记、且不等于自身；`not-implemented` 之类不得携带 `replacement`。
4. 方法名必须大写，`操作` 必须是 `METHOD /path` 且唯一；`reason` 至少 10 字符（避免"以后再补"这种空话）。
5. 契约操作总数必须等于 `352`、有证据数不得低于 `315`——任何人放开这两个数字都得显式改代码并过评审。
6. 生成文件与提交文件必须逐字节一致（`--check`）。

## 顺带修正的三处契约元数据

排查时发现三个**已实现但契约没标**的接口，把它们补上实现标记（不改路径、不改行为，因此探针数 338 与证据数 315 都不变）：

| 操作 | 新标记 |
| --- | --- |
| `POST /api/ai/chat` | `ai-chat-implemented-requires-provider` |
| `POST /api/input-materials/{purpose}/{vehicleId}/upload` | `controlled-input-material-upload-implemented` |
| `GET /api/input-materials/{purpose}/{vehicleId}/{id}/access` | `controlled-input-material-access-implemented` |

前者的新标记同时说明了它为什么没有证据：需要供应商。

## 边界

- 结论是**判断**，不是验收：`superseded` 只说明"这一项不该再要证据"，替代接口自己的证据仍由覆盖清单给出。
- 结论不改变任何生产行为；本阶段只改契约元数据与工具。
- 结论会过期：替代接口下线、外部上游接入、功能立项都必须同步改 `CONTRACT_VERDICTS.json`，否则门禁会报"未解析操作"（这正好是想要的效果）。
- 全 API 验收仍为 false。
