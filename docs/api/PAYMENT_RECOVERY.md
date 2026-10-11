# 支付结果核对（R2b1）

`POST /api/payments/{id}/reconcile`：车主 JWT，本人支付记录，JSON必须为`{}`。不接收金额、成功状态、渠道或订单号，不需要客户端幂等键，结果通过支付流水/事件唯一约束去重。200返回既有 PaymentDetail，`Cache-Control: no-store`。同一记录60秒内复用已持久化租约，返回当前状态，200不代表已付款。SUCCEEDED直接返回；PENDING/CREATED、FAILED/CLOSED可查询，但未知/超时不标失败。

401会话无效（供应商调用后仍再次检查）；403身份不符；404非本人/缺失；400额外字段/无效ID；409历史缺少可信支付号；503查询能力或数据库未配置。查询错误保留原业务状态，后台分类`QUERY_FAILED/INVALID_RESULT/APPLY_FAILED`并重试，无法认领的历史记录分类`CLAIM_FAILED`延后300秒，防止阻塞队列；前端只能按返回状态显示结果。接口不会执行退款。

## 测试渠道与恢复配置

必须已有`local-payment-test` profile、`PAYMENT_LOCAL_TEST_ENABLED=true`、至少32字节测试密钥；prod/production与测试profile组合拒绝启动。

- `PAYMENT_LOCAL_TEST_QUERY_URL`：只允许本机HTTP源（127.0.0.1/localhost，可端口，不含凭据/路径/查询/片段）。容器部署时供应商夹具必须在同一网络命名空间；禁止改成外部地址来绕过限制。
- `PAYMENT_QUERY_RECOVERY_ENABLED=false`默认。启用时须具备测试渠道查询能力；数据库配置不存在时接口503，没有恢复worker。
- `PAYMENT_QUERY_RECOVERY_INTERVAL_MS=60000`。每轮20条、租约60秒。未确定/失败60秒后重试，CLOSED/FAILED300秒；成功记录退出扫描。无历史订单补款、无自动退款、无任务丢弃窗口。

供应商 `GET /payments/{payment_no}`：202/404为未知；200须返回既有 LOCAL_TEST PaymentNotice JSON 和`X-Test-Timestamp/Nonce/Signature`，独立HMAC签名、5分钟时间窗、原始响应≤16KiB，总等待≤3秒，禁止重定向。支付ID/冻结订单号/金额/币种必须匹配，签名与响应不入日志。查询不读取本地支付状态来构造成功证据。

V024只扩展payment的持久租约字段，不扩张公开摘要；原回调和查询共用同一入账逻辑。迟到账保留 payment_exception，不恢复取消/过期订单与名额。**该协议是隔离测试，不是正式微信支付验签/对账。** R9必须另验官方协议、证书轮换、商户/app归属、金额与真实资金。
