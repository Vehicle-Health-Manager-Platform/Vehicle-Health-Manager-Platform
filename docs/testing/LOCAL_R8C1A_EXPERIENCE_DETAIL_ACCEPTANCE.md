# R8c1a 受控同款经验详情本地验收

2026-10-17，本阶段本地完成。无新迁移，仍 V056/116 表。整 R8 完成前全部代码和文档保留本地。

## 后端

`.cache/r8c1a-backend.log` 一次完整54/54通过，无失败、错误或跳过：

- JdbcExperienceDetailTest 44：新7、继承原归档/经验37。
- ExperienceModerationHttpTest 6：新2、原4。
- ExperienceCardsNoDatabaseTest 1：原用例新增详情无库503/非法入口400断言，不重复计为新方法。
- VehiclePlanEvidenceHttpTest 3：原方案证据兼容。

合计54不同用例（新9/原45）。真实MySQL从当前全部迁移初始化，覆盖原经验授权/审核/撤回/归档及本次精确最小投影、列表详情一致、读取无副作用、旧UUID撤回和重新批准后不复活、跨车主车辆/车型/随机ID、车型/车系/品牌停用或删除、源施工变化/文件不安全/摘要篡改、原车主停用或车辆转移、当前会话/账号失效与关闭开关。HTTP匿名/跨角色、重复/未知/缺少选择器、非法UUID/不安全编号、no-store、404/503及依赖细节隐藏通过。

使用既有Java17/Maven容器、真实MySQL Testcontainers和本机API1.44/host.docker.internal设置；本机Ryuk设置沿前阶段，由测试生命周期清理，不改变CI。未运行整R8所有后端，也未将继承重复或构建计成新业务用例。

## 前端及最终产物

`.cache/r8c1a-miniapp-final.log` 796/796通过，新增11。覆盖当前车辆/车型/公开UUID及精确最小响应、日期规范、只含公开编号的入口/严格路由、只读请求/账号、错误响应不当正常、原生与云托管运输、切号/换车/车型/引用/隐藏迟到清理、404清旧结果、实际同款/AI页装配和精确车主登录返回。

初次795及增加登录返回后的796均通过；最后补列表错误同步清详情与联合刷新后完整796再通过，不重复累计。实际Vue页面最终微信构建/全局原生样式/无测试身份桥通过（`.cache/r8c1a-mp-final.log`），最终H5通过（`.cache/r8c1a-h5-final.log`）。未宣称浏览器视觉、真机或官方分享验收。

## 静态及范围

301 API操作/1048引用全部解析，原13字段运营登录保持；99唯一路由及实际页面文件、原首路由、56唯一有序SQL挂载和原README/141追踪MD+CSV/44表init.sql基线保持通过。无新DDL，完整V056/116表重放沿[R8b3c1记录](LOCAL_R8B3C1_PRICE_SOURCE_ACCEPTANCE.md)，Compose config沿R8b3c2既有证据，本步不冒称重新执行完整迁移或Compose烟测。最终92份变更/新增Markdown的707个相对链接及git diff --check通过；Git仅提示既有自动CRLF转换，不是空白检查失败。

没有新增热度事件、公开图片、问答处理或正式分享渠道；当前是应用内受控引用基础。最初两处猜测路径读取失败已改rg定位，提前读取未启动H5日志不算构建失败或通过；最终以完成日志为准。

见[执行](../progress/R8C1A_EXPERIENCE_DETAIL_EXECUTION.md)、[API](../api/EXPERIENCE_DETAIL.md)、[操作](../operations/EXPERIENCE_DETAIL.md)及[下一步](../progress/R8C1B_COMMUNITY_NEXT_PLAN.md)。HEAD仍d4c7763，无push/PR更新/合并/发布；整个R8完成后才同批上传源码、迁移、测试与文档。
