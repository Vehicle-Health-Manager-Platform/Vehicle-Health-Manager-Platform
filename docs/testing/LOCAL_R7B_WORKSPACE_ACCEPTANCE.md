# R7b 本地验收（2026-10-15）

范围：[设计](../progress/R7B_WORKSPACE_DESIGN.md)、[执行](../progress/R7B_WORKSPACE_EXECUTION.md)。以下为本地验证，不代表整R7或正式渠道/真机验收完成。

## 实际结果

- 最终后端47/47，Failures/Errors/Skipped均0，BUILD SUCCESS；日志.cache/r7b-backend-final.log，结束2026-10-15T11:51:16Z。
- JdbcServiceWorkspaceTest32：原施工13+车主证据8+工作台/授权11；ServiceWorkspaceHttpTest5、OwnerServiceEvidenceHttpTest3、ServiceWorkHttpTest7。不重复累计继承用例。
- 首轮另跑JdbcCouponTransactionsTest37/37，验证新增V044后的完整96表结构及原券事务兼容；首轮工作台29/29及HTTP5/5同时通过，日志.cache/r7b-backend.log。最终新增并发/结构化/自动归档测试后数据库32/32。
- npm test --workspace apps/miniapp：606/606，0失败/跳过；新增工作台/授权11项，原595项均通过。日志.cache/r7b-miniapp.log。
- 微信小程序、H5构建通过，微信全局原生样式和无测试身份桥检查通过；日志.cache/r7b-build-mp.log、.cache/r7b-build-h5.log。
- scripts/verify_mysql_schema.sh通过：全部V001–V044及重复执行、96表、无默认授权、(order_id,archive_id)唯一索引；日志.cache/r7b-schema.log。
- OpenAPI重新生成230操作；页面79路由、引用、变更文档链接与差异检查在本阶段收口复核。Compose挂载和结构门禁已更新V044，整栈Compose运行留整R7统一门禁，未以单表测试冒充整栈运行。

## 覆盖的实际风险

八分类真实事实与分页、本店/跨店/同店其他技师隔离，未接单拒绝；无默认共享、无全车里程和身份字段。逐条授权/撤销/重授权、源变更重确认、CAS并发仅一个成功、审计失败事务回滚、撤销会话不能重放写入。

图片逐次复核安全扫描/删除/归属；自动档案需DONE任务、原订单同车主、原签字报工与原技师照片，破坏任一来源拒绝新签名。源结构化字段变化/删除使旧授权失效，服务投影去除provider_name等非必要字段。签名存储调用后再次复核的撤销竞态HTTP测试通过。

客户端拒绝额外身份字段、错误接收范围、错单回执及不安全/过期/过长URL；切号/换角色/订单/隐藏后迟到响应不回填，确认期间切号不写，预览不授权，旧到期回调不清除新图片，未知写保留原正文/版本/幂等键，明确拒绝清空资料和重试。

## 限制与后续

R7b未决写仅内存保存，卸载后重读实际授权；持久恢复在R7c。已经返回URL最多300秒残余访问无法即时撤回。未跑真实微信身份、官方分享、真机、正式资金或生产供应商；继续R9。当前没有R7上传或远程CI，整个R7完成后同批上传所有编辑代码、迁移、测试和文档。
