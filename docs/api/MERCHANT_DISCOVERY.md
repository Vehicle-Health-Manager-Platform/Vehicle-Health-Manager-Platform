# R4b1 车主门店详情与区域筛选

所有读取要求有效正式user/OWNER会话、账号启用未删除；响应no-store。

## 区域报价

GET `/api/service/project/{id}/merchants?sort=price_asc&page=1&page_size=20&region_code=440305`。

新增可选region_code必须六位数字，不接受空串/空格或其他格式；省略保留全部报价与旧响应字段。精确匹配merchant.region_code；仅status=1且未删除门店、有效标准目录、在售未删除报价及最新版本。未知但格式正确的区域返回空页，不假称已覆盖；旧空区域门店仅全范围显示。只支持原价格排序。微信picker提供中文地区名称与code，不能从地址猜区划。支持参考：[微信官方picker](https://developers.weixin.qq.com/miniprogram/dev/component/picker.html)、[DCloud picker](https://uniapp.dcloud.net.cn/component/picker.html)；region仅原生支持，H5仅全范围兼容。

## 门店详情

GET `/api/service/merchant/{id}?project_id=11&page=1&page_size=20`。

id、project_id为1..9007199254740991，project_id可省略表示本店所有有效在售项目；page1..1000000，page_size1..100。指定有效标准项目但本店无在售报价返回空页；指定停用/删除项目或停用/删除店404。旧已上线门店无需重新拥有入驻申请。

data恰好merchant和offers：

- merchant恰好merchant_id、name、address、merchant_type（既有1..6映射）、region_code（有效六位字符串，旧空/非法区域null）。没有电话、坐标、营业时间、评级、佣金、资质、人员或客户字段。
- offers恰好items、total、page、page_size；items每项merchant_project_id、standard_project_id、project_name、category（1..6）、price（最新版本两位小数字符串）、version_id、version。稳定按标准项目ID/报价ID排序，最新版本只出现一次。

读事务同一门店/报价快照；不推导距离、公开评分、健康状态。分页刷新复核停用和下架，空态无预约按钮；预约只跳现有`/pages/order/book?id={merchant_project_id}`，该页重新取quote_version_id/时段，提交以服务端版本与容量检查为准，不能用详情价格直接下单。

400参数错误；401会话/账号失效；403非正式车主；404门店/指定项目不可用；503数据库未配置或失败。数据库错误不回传SQL/私人数据。

微信区域只存当前页面内存；取消不改筛选，缺少合法区级code不请求；切换区域/排序/账号或隐藏清旧行并忽略迟到响应。详情分页失败保留已加载页及重试页码；401/403/404立即清已加载详情，不保留预约入口。
