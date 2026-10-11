# R1c 标准库治理接口

日期2026-10-11；V022先迁移。`CATALOG_GOVERNANCE_ENABLED=false` 默认拒绝50300，运营独立 `can_catalog=1`，每次含幂等重放实时查会话与权限。车主/门店/技师不可维护运营目录。

| 方法 | 路径 | 请求与响应 |
| --- | --- | --- |
| GET | `/api/admin/catalog/{kind}` | page默认1/page_size默认20、最多50；单值参数，未知拒绝。data为items/total/page/page_size |
| POST | `/api/admin/catalog/{kind}` | UUID Idempotency-Key；新建严格字段，不接受status/revision，默认未发布；data为目录行 |
| PUT | `/api/admin/catalog/{kind}/{id}` | UUID Idempotency-Key；全量字段+revision/status；data为更新行、revision递增 |

kind固定projects/brands/series/models。字段见[规格](../superpowers/specs/2026-10-11-r1c-catalog-design.md)与生成OpenAPI。创建和更新只返回一层ApiResponse信封，禁止data.data重复包装。所有响应no-store；未知/重复/类型错误/无效页码/缺键40001；身份40100/40300；不存在40400；版本、重复、上级未发布、上级更换或引用冻结40900；开关/依赖50300。发生网络或503时相同正文复用原幂等键，正文修改生成新键。

行包含id、该kind身份字段、status（0未发布/停用、1已发布）、sort_order、source_note、revision。项目价格为两位小数字符串；其余字段不返回身份资料。遗留source_note可能空，编辑时须补来源；质检标准/车型可选历史空值在投影中归一为空字符串，不把空来源当合法新建。

车型上级创建后不可变；父级停用隐藏新录车的整条链，既有车辆继续回显。已引用身份字段不可改写。项目停用隐藏新选择、新报价启用与新预约；已有门店报价允许下架，历史订单快照、付款和施工档案保留。

客户端运营登录新增可选布尔`user.can_catalog`，新服务固定返回此字段；微信客户端兼容旧服务未返回时权限为false，不允许未知权限字段。CLI授权：profile operator-admin、web-application-type=none、operator.action=catalog-permission、operator.id与OPERATOR_CAN_CATALOG=true/false；操作会撤销原会话，重新登录生效。不得把短信/密码/令牌放到文档或命令输出。
