# A7.2a 本人订单评价实施计划

> 按 executing-plans 在当前隔离工作树执行；用户已授权规划与实施，按阶段提交、推送及创建 PR，完成后报告。

**目标：** 可信核销后，车主本人保存一份带评分、文字与可选私有图片的评价。

**架构：** 使用现有 ReservationStore 与 WriteIntegrityService，实现 OrderReviews 资格/投影/事务、OrderReviewInput 严格正文、OrderReviewsController 权限与错误契约。小程序复用本人私有图片上传和签名接口，独立评价 API 与页面状态流。

**技术：** Java17/Spring Boot/JDBC/MySQL8/Testcontainers；Vue3/uni-app/Node tests；gstack browse。

**规格：** [A7.2a 规格](../specs/2026-10-09-a7-owner-review-design.md)。

## 全局约束

OWNER 本人；COMPLETED 且核销/付款一致、无未解决争议；1–5 整数评分、首尾去空白后 1–500 Unicode 码点、0–3 本人安全 JPEG/PNG；仅本人查询、不可修改、不公开。UUID 幂等、订单唯一、审计同事务、test_mode 服务端继承。无自动回填、无生产部署、无合并。

## 阶段 1：规格与计划

- [x] 对照来源与旧实现，选择本人唯一评价并写明实施假设。
- [x] 写清资格、契约、错误、图片、重放、并发、页面生命周期与后续范围，扫描占位符/冲突。
- [ ] 提交 `codex/a7-review-spec`，推送，创建依赖 `codex/a7-redeem-e2e` 的规格 PR；CI 仅证明旧代码回归。

## 阶段 2：后端、契约与数据库

文件：新建 `backend/src/main/java/com/autocare/platform/order/OrderReviewInput.java`、`OrderReviews.java`、`OrderReviewsController.java`；修改 `ReservationConfiguration.java`；新建 `docs/sql/migrations/V016__order_reviews.sql`、`docs/api/ORDER_REVIEWS.md`；修改 `scripts/verify_mysql_schema.sh`、`scripts/generate_openapi.py`、API 索引；新增 JdbcOrderReviewsTest、OrderReviewsHttpTest、OrderReviewsNoDatabaseTest。

接口：`OrderReviewInput.parse(String): JsonNode` 与 `normalize(JsonNode): JsonNode`；`OrderReviews.submit(VehicleOwner,String,JsonNode): JsonNode`、`detail(VehicleOwner,long): Map<String,Object>`。

- [ ] 新建真实 MySQL 测试，使用已有迁移和合成订单/付款/核销，实际运行：
  ```java
  var a=reviews.submit(owner,key,body).path("data");
  var b=reviews.submit(owner,key,body).path("data");
  assertEquals(a,b);assertEquals(1,count("order_review"));
  ```
- [ ] 实现严格整数、码点长度、图片唯一和 JSON 重复键/尾随拒绝；服务端资格及最小投影。
- [ ] 实现锁顺序和事务，加入三种失败触发器分别使评价、审计、幂等缓存回滚；真实并发同/异内容断言唯一评价及唯一审计。
- [ ] 运行 `mvn -B -Dtest=JdbcOrderReviewsTest,OrderReviewsHttpTest,OrderReviewsNoDatabaseTest,JdbcOrderRedemptionTest test`，零失败/错误/跳过；执行 OpenAPI 生成及 V016 重放验证。
- [ ] 更新中文接口、阶段记录；提交/推送 `codex/a7-review-backend`，创建后端 PR。

## 阶段 3：小程序评价

文件：新建 `apps/miniapp/src/services/order-reviews.js`、`src/pages/order/review.vue`、`test/order-reviews.test.js`；修改订单详情与 pages.json；新增页面执行记录。

接口：`createOrderReviewsApi({baseUrl,runtime})` 提供 `detail(token,order)`、`submit(token,body,key)`；`reviewBody(order,rating,content,files)`；`createOrderReviewFlow({state,token,body,api,newKey,confirm,onConflict})` 提供 `resume/reset/submit`。

- [ ] 测试缺资格、最小字段、错误响应不展示服务端文本、输入上下界；拒绝错误 order_id、额外身份字段及不一致 test_mode。
- [ ] 状态流用可延迟 Promise 验证确认取消不提交、同载荷网络失败原键重试、不同内容换键、隐藏/账号切换丢弃旧响应、成功不重复提交：
  ```js
  await flow.submit();await flow.submit();
  assert.equal(requests[0].key,requests[1].key)
  ```
- [ ] 评价页接真实 API：不默认选择评分；文字/图片、二次确认、已评展示、原图上传重试、图片失败提示、测试标签；离页/换号全部清空。
- [ ] 运行 `npm test --workspace @autocare/miniapp`、`npm run build:miniapp`、`npm run build:h5 --workspace @autocare/miniapp`；更新文档，提交/推送 `codex/a7-review-ui` 并创建页面 PR。

## 阶段 4：联调与收口

文件：`scripts/local_order_reviews_e2e.cjs`、`docs/testing/LOCAL_ORDER_REVIEWS_ACCEPTANCE.md`、`docs/progress/A7_OWNER_REVIEWS_EXECUTION.md`；进度/下一步/角色缺口。

- [ ] 打包最终 JAR，与原隔离环境备份后创建评价测试镜像，仅回环端口；在隔离 MySQL 应用 V016 两次。
- [ ] 可复现真实 HTTP：合成登录只桥接身份，真实核销记录作为前置；真实私有上传→本人评价→原键重试→本人读取与唯一审计；覆盖角色、他人、状态、缺核销、支付异常、图片和撤销会话。检查运行 JAR 摘要及精确镜像/网络/表数；凭据不打印、不提交，结束撤销。
- [ ] gstack 验证实际填写/取消确认/提交/本人结果、离页清空；仅 CORS 与合成身份桥接，业务请求真实转发。真机和直连预览留独立验收。
- [ ] 更新所有中文证据和 A7.2b 规划，提交/推送 `codex/a7-review-e2e`，创建收口 PR，最终 head 六项 CI 全绿后标记可审阅。

## 上传与合并关系

评价四 PR 连在现有 #45→#48 后；待用户另行授权合并时，按依赖顺序逐支把下一支 base 改回 main，复核差异与当前 CI 再合并。阶段上传授权不等于合并或部署授权。
