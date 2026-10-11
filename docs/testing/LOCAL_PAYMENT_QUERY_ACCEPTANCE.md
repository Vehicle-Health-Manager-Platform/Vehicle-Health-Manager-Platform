# R2b1 支付查询恢复验收

日期2026-10-11；LOCAL_TEST、合成账户/订单/金额，仅隔离测试。正式微信支付和真实收退款未验收。

## 已执行

- Docker Maven / Testcontainers 真实 MySQL：`JdbcPaymentsTest` **34/34**，包含旧支付19项和新增恢复15项。V024执行两次。独立本机 HTTP 供应商返回 HMAC 签名事实，实际查询后真实MySQL写入支付/订单/事件/审计；未直接把本地状态作为支付证据。
- `LocalPaymentQueryTest` **5/5**：真实本机HTTP、独立签名、支付ID/订单/金额绑定、202/404未知、未签名/302/503拒绝、16KiB限额、响应头之后延迟正文也在3秒截止。`LocalTestPaymentChannelTest` **4/4**：原验签、时间窗、重复JSON和配置边界回归。
- `PaymentHttpTest` **5/5**（MockMvc，业务服务mock）：未登录/商家拒绝、空JSON、客户端金额/成功状态/渠道拒绝、no-store和既有回调重试；`PaymentNoDatabaseTest` **1/1**：数据库未配置路径。HTTP角色断言不是完整真实微信身份端到端。
- 微信离线 **286/286**，新增核对空POST、请求ID绑定、未知结果/测试资金提示、网络重试、身份切换/离开页面丢弃旧响应。mp-weixin和兼容H5构建通过；正常包不含测试身份桥。

首轮后端47/47；最终增加独立供应商联动、损坏历史记录防阻塞两项后合计49项，两轮有重叠，不相加。原生/真机入口未操作验收。

## 恢复和安全事实

漏通知查询支付一次且成功后停止；未知/网络错误不标失败；新worker实例从持久next_query_at继续；丢失租约60秒后可重新认领；旧token不能重写新调度；两个实例一次查询。测试供应商断言没有活动数据库事务。

取消/过期/原失败后到账分别留待核对，原订单和名额不恢复；回调可在查询期间先入账，事件/成功审计仍一次；跨支付ID与共用流水不能入账；审计拒写全部回滚，下一次恢复成功。非本人/已撤销会话不触发查询；查询期间撤销会话后可信资金事实可入账，但接口不向失效会话披露结果。历史损坏记录延期，健康记录继续处理。

## 复现和限制

命令参考仓库既有Docker Maven方式：`mvn -B -Dtest=JdbcPaymentsTest,LocalPaymentQueryTest,LocalTestPaymentChannelTest,PaymentHttpTest,PaymentNoDatabaseTest -Dapi.version=1.44 package`。Docker Desktop须传`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`；测试容器和合成数据随本次suite结束清理，不修改原本机业务栈。

本次本机受限沙箱内微信构建报告Terser不存在；解除路径读取限制后同一依赖与代码构建成功，未改依赖。该构建不是原生页面与真机验收。

CI最终六项与全量计数以GitHub收口PR的实际检查记录为准，不将未完成CI提前写成通过。正式渠道尚未注册；后台查询只覆盖LOCAL_TEST。支付查询关闭/异常不执行退款；R2b2仍需取消审批和退款金额规则，R2c仍缺佣金台账。
