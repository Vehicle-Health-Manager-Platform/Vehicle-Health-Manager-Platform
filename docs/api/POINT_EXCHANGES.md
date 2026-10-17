# 本人积分原子兑换（R8a3b）

仅 OWNER 当前有效账号/会话，响应全部 no-store。车主目录选择、确认与原请求恢复已在[R8a3c](OWNER_POINT_EXCHANGES.md)接入；本文为事务接口及收支明细协议。真实身份/资金/真机仍 R9，`POINTS_EXCHANGE_ENABLED=false` 默认关闭。

## 提交

`POST /api/point/exchange`，必须 UUID `Idempotency-Key`，精确正文：

```json
{"catalog_id":1,"catalog_revision":2}
```

编号/修订须为正安全整数（小于9007199254740991），不允许查询参数、重复字段、额外字段、尾随JSON、客户端价格/用户/余额/身份来源。目录须已发布且修订精确匹配；发布冻结的价、限额、条款、来源和窗口取服务器版本。

提交锁当前本人→真实券来源门店/项目/券→目录→本人已核验账户；所有等待后统一时点重验：服务器已核验同app会话、独立兑换开关、目录及券窗口、发行/精选池版本和精选状态、来源仍可用、实际库存、可信余额和同模板全部成功兑换累计次数。获分开关不决定已到账余额是否可兑换；兑换资格/开关独立校验。

只有 `point_verified_account` 可用，旧 `user.point_balance`/`point_flow` 不导入；PENDING 任务也不可支付。每次成功同事务写余额及版本、负数流水、本人券、POINTS 来源凭证、库存事件、永久兑换事实及审计。无外部资金/支付调用，失败全部回滚，不留待扣分/待补券状态。

## 成功结果与历史

成功仍使用一层 ApiResponse，data 精确字段：`exchange_id`、`user_coupon_id`、`catalog_id`、`catalog_revision`、`coupon_id`、`publication_revision`、`points_spent`、`balance_after`、`exchanged_at`、`status=SUCCESS`、`rule_version=R8A_EXCHANGE_V1`、`catalog`（[完整冻结目录](POINT_EXCHANGE_CATALOG.md)）。`balance_after` 是该次成功后的余额快照；当前余额读取 overview。

`GET /api/point/exchanges?page=1&page_size=20` 仅本人成功记录，默认第一页，page=1..1000000、page_size固定20。返回 `{items,total,page,page_size}`，items 为原成功data，按兑换id倒序，不暴露会话、提交键/哈希。停止或过期不删除成功事实。

## 永久回执

本人＋规范化UUID永久保存正文哈希和首次成功结果。通用24小时回执失效、服务重建、目录停止、来源到期或兑换开关关闭后，同键同正文仍返回同一成功事实，不再扣分、发券或审计；回执前必须重验当前账号/会话。换正文400，401重新登录后先核对，不自动换键重做。网络/503未知时保留原键原正文。

成功兑换没有自助撤销返分接口。金额券按R5保留正现金实付/实际现金佣金，服务券独立核销；全额订单退款返原券，不再返积分，不延长原券期限。

## 统一收支账本

`GET /api/point/flow` 收入旧结构保持：`id,delta,balance_after,created_at,task_type,period_key,policy_id,policy_revision`。支出采用精确结构：`id,delta,balance_after,created_at,exchange_id,catalog_id,catalog_revision,coupon_id`；delta 为 -1000000..-1。两类使用同一自增id倒序、同一20条分页，避免收入/支出分开后排序或余额不一致。数据库约束禁止0分、无来源、双来源和方向与来源不匹配。

400参数/同键正文变化，401失效，403身份角色，404目录/来源不可用，409资格/目录/窗口/版本/余额/限额/库存变化，503数据库/审计异常。均不把错误当成功，内部数据库详情不返回。

见[运维](../operations/POINT_EXCHANGES.md)、[设计](../progress/R8A3B_ATOMIC_EXCHANGE_DESIGN.md)、[OpenAPI](openapi.json)。
