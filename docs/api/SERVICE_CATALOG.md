# 标准服务项目查询

对应 F06 第一项交付。车主服务 Tab 提供分类、分页和详情；商家报价、排序、定位、评价总结和预约另行实现。

仅有效 `user/OWNER` 会话可调用。匿名、令牌无效/撤销为 401/40100；其他角色与绑定凭证为 403/40300。读取时重新核对会话和用户有效状态；认证失败使用统一 JSON，响应不缓存。

| 接口 | 参数 | data |
| --- | --- | --- |
| `GET /api/service/projects` | `category` 可选 1–6；`page` 默认 1，上限 1000000；`page_size` 默认 20，上限 100 | `items, total, page, page_size` |
| `GET /api/service/project/{id}` | 正整数 ID，最大 9007199254740991 | 项目详情 |

分类：1 保养、2 轮胎、3 维修、4 美容、5 服务、6 用品；不传 category 查询全部。按 ID 升序；列表和计数在同一只读 REPEATABLE READ 事务中查询，条件一致：`status=1 AND is_deleted=0`。

列表字段：`id, project_name, category, base_price_low, base_price_high`；详情增加 `service_content, quality_standard`。价格为两位小数字符串，例如 `"0.10"`；缺失质量标准返回 null，项目文字以纯文本渲染。

非法分类、分页或编号为 400/40001；不存在、停用或软删除统一 404/40400；数据库未配置/失败为 503/50300，不泄漏内部连接和 SQL。成功和服务模块业务错误使用 `Cache-Control: no-store`。

复用现有 `standard_project` 表，无结构迁移和写接口。参考价属于运营数据；合成价格只用于本机验证，不作为生产价格来源，无项目时返回真实空列表。分页失败保留已加载项目、重试原页；重新加载失败重试第一页；身份改变/隐藏页后旧响应不得覆盖当前状态。

结构见[OpenAPI](openapi.json)，生成源 `scripts/generate_openapi.py`；本机步骤见[复现说明](../testing/LOCAL_SERVICE_CATALOG_ACCEPTANCE.md)。
