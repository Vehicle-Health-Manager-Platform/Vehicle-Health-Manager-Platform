# A2 订单履约状态机执行记录

日期：2026-10-07。范围和顺序见[中文计划](../superpowers/plans/2026-10-07-order-state-machine.md)。

## 代码与审阅

- 规格 PR #32 已合并，合并提交 `e0e44f7`。
- A2 初始实现为 `724cdff`；审阅修复提交 `c5feab6`。
- 修正已生效动作以新键重放时多写业务审计，明确无变更可缓存响应而不再写成功审计；声明不同前后状态却要求跳过审计时事务回滚。
- 修正前端可选备注误传空字符串导致操作未发出；修正新建 Compose 数据卷漏装 V009。
- 修正真库测试计数，把时段发布/下单的审计和幂等记录排除在履约断言之外。

## 验证证据

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| [CI 37644016017](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37644016017) | `c5feab6` 六项成功；后端 251 项，失败/错误/跳过均 0 | 含 MySQL、状态矩阵、并发、审计/缓存回滚及 Compose |
| 本机 Node | 114 项通过 | 服务与交互状态流；真实短信不是本步验收 |
| 本机 Maven | OrderStatusTest 7 项、MerchantOrdersHttpTest 5 项通过 | Docker Maven；完整 Testcontainers 在 CI 执行 |
| 本机构建 | H5、mp-weixin 成功 | 使用 esbuild 压缩；CI 同时验证默认构建 |
| 本机数据库 | 先备份隔离库，再执行 V009 | 保留既有数据卷；备份未入 Git |
| 本机后端 | 镜像 `vehicle-auth/backend:order-fulfillment`，健康 UP | 私有变量原样保留，旧容器保留；端口仍为 `0.0.0.0:18080` |
| gstack browse H5 | 商家订单详情 200；点击“确认接车”并确认，POST 返回 HTTP 409 / 43001；页面显示缺接车单原因并刷新 | 商家身份使用仅本机隔离会话；订单与业务接口均真实后端/MySQL |
| 数据库核对 | 测试订单仍 PAID，check_in_completed_at=NULL；迁移审计和接车成功审计均 0 | 未给运行库伪造接车证据；测试支付没有真实扣款 |

## 交付与下一步

[PR #33](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/33) 收口 A2；文档更新后的最终提交检查和合并状态以该 PR 为准。

A2 是履约地基，目前订单仍不能从 PAID 完成真实接车。A3 的商家上传、预约验码与完整接车单尚未编码。用户已确认手填里程、固定七张照片及一次提交方案；书面规格复核后实施。A4 车主确认与异议、A5 派工、A6 报工、核销和退款继续分别交付。
