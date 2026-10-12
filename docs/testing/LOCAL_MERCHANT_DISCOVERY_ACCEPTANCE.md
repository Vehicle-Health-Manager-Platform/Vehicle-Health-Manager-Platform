# R4b1 商家详情与区域筛选验收

2026-10-12。隔离合成门店/项目/会话，无真实身份、资金或个人信息截图；仅微信小程序与必要后端，所有README不改。

## 已执行与证据

| 检查 | 结果 |
| --- | --- |
| 新MySQL集成 | 5/5，最小5字段门店/7字段报价、最新版本、稳定分页、有效项目过滤、区域/旧门店、停用/下架/删除、撤销会话与账号停用 |
| 新HTTP契约 | 3/3，匿名/门店/技师/运营拒绝，ID/页码/区域参数拒绝，owner范围、no-store、SQL错误不外泄 |
| 原报价回归 | JDBC7/7、HTTP4/4；合计定向19/19通过，旧5参数接口和JSON响应保持兼容 |
| 小程序全套 | 388/388，新增11项区域/取消/缺失code/范围/旧排序迟到错误/分页/权限/隐藏/账号/返回目标测试 |
| 微信原生构建 | 成功；新pages/service/merchant.wxml/js/json存在；项目详情含mode=region及bindtap/bindchange/bindcancel；全局样式及无测试身份桥检查通过 |
| H5 | 仅兼容构建成功，原生地区picker被条件编译排除，无新增PC产品 |
| 数据结构/API | 无新迁移，基线44/全链71表；OpenAPI156操作；最新六项CI见对应PR最终正文 |

初次新测试夹具漏载V019运营表，V020执行报operator_account缺失；已补真实迁移顺序V001/V003/V006/V019/V020/V022后定向19/19通过，无改动业务迁移来迎合测试。

## 复现

Java17/Maven3.9及Docker：backend内`mvn -B -Dtest=JdbcMerchantDiscoveryTest,MerchantDiscoveryHttpTest,JdbcMerchantQuotesTest,MerchantQuotesHttpTest test`。本机Docker29通过JAVA_TOOL_OPTIONS=-Dapi.version=1.44、TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal运行，坏Ryuk缓存仅本机禁用、JUnit负责容器清理，CI保持正常策略。

`npm test --workspace @autocare/miniapp`；`npm run build:miniapp`；`npm run build:h5 --workspace @autocare/miniapp`。最终GitHubCI按最新head记录，不以旧提交绿灯代替。

## 未验收/未实现

微信开发者工具和手机实际打开地区选择弹窗、取消/切换账号/弱网/预约跳转仍待设备交互验收；没有声称自动化单测或WXML构建代替真实交互。距离/评分/评价公开/营业时间与电话授权、真实身份/资金和发布均未在本阶段补齐。区域中文来自微信选择器，代码只用于与真实门店区域匹配，不从地址推断覆盖。
