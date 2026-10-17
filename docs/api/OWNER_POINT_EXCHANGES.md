# 本人兑换目录与操作（R8a3c）

承接[原子兑换及成功记录](POINT_EXCHANGES.md)。新增一个只读接口，未新增表或迁移，所有响应no-store，仅当前有效OWNER。独立兑换开关仍默认false，获分开关不决定兑换资格。

## GET /api/point/exchange-catalog

只接受 `page=1..1000000`、`page_size=20`，默认第一页；拒绝任意用户选择器、未知/重复查询参数。

返回精确字段 `items,total,page,page_size,balance,legacy_state,exchange_available,read_at`。余额仅来自可信账户，不含历史旧积分和PENDING；历史值存在时 `legacy_state=REVIEW_REQUIRED`，其余NONE。`exchange_available` 是独立开关及同app服务器已核验会话，不等于每张券均可兑。`read_at` 是UTC业务时点。

items只含PUBLISHED目录（含尚未开始/过期），停止和草稿不列入；无配置为空列表、价格未知，无默认兑换价。单条精确字段：

- `catalog`：完整冻结[兑换目录](POINT_EXCHANGE_CATALOG.md)。
- `merchant`：`{id,name,address}`，或不可核对时null。
- `project`：`{id,name,service_content}`，或不可核对时null。门店与项目只有在当前有效并与原发行/精选版本和冻结条款匹配时展示。
- `stock_left`：实际库存或缺来源时null。
- `exchanged_count`：本人在同一券模板全部成功兑换数，跨目录版本累计。
- `remaining_limit`：`max(0,本目录per_owner_limit-exchanged_count)`。
- `eligibility`：下述状态，只有ELIGIBLE可以发起新确认。

状态依次检查：目录窗口NOT_STARTED/EXPIRED，发行缺失SOURCE_UNKNOWN，券/门店/项目失效SOURCE_UNAVAILABLE，发行或池修订/条款/精选变化SOURCE_CHANGED，原券窗口，OUT_OF_STOCK，LIMIT_REACHED，INSUFFICIENT_POINTS，EXCHANGE_UNAVAILABLE，全部通过ELIGIBLE。同一条目可能有多个限制，返回当前优先原因；顶部独立资格仍显示。普通读取不锁业务、不记账、不发券，不是提交授权，POST按R8a3b重验。

## 客户端与恢复

车主“我的积分”→“积分兑换券与成功记录”。目录和历史分别分页。新兑换固定复制服务器报价，确认积分价、券种金额/门槛、门店、项目、模板终身次数、来源和两类窗口；仅传目录ID/修订。不提供改价、自动换目录或成功自助撤销。

成功显示首次结果和券入口；当次余额为快照，当前余额刷新目录。历史使用完整冻结结果，即使目录停止或原券过期仍可核对，实际可用/核销/退款状态由现有“我的券与有效期”读取。

本机 `autocare:point-exchange:v1:<ownerId>` 仅存 `{version:1,pending:{action:'exchange',id:0,body:{catalog_id,catalog_revision},key}}`，不存token或档案，与签到/运营目录独立。页面重建/登录后先读本人目录与成功记录，之后原键原正文重试；当前目录已消失/开关关闭也不换新价，而是恢复原成功事实或接受服务器明确拒绝。

未知网络/503/协议错误保留；401保留原账号请求并重新登录；400/404/409等明确拒绝结束旧请求后，新操作须再次读取并确认。存储失败阻写，成功但本机清理失败仍保留原键；多页面标记比较防止误清他页请求。切号/隐藏清除页面数据和迟到响应，不擦除原账号持久请求。登录回跳只接受不带查询的静态兑换页。

见[运维](../operations/OWNER_POINT_EXCHANGES.md)、[OpenAPI](openapi.json)和[设计](../progress/R8A3C_OWNER_EXCHANGE_DESIGN.md)。
