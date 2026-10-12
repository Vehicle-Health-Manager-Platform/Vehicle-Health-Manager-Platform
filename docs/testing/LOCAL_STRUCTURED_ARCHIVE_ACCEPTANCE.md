# R4a2 结构化资料验收

日期：2026-10-12。

| 证据层次 | 结果 | 范围 |
| --- | --- | --- |
| 后端输入 | 2/2 | 类型字段、枚举/控制字符/长度、日期有效性/顺序、整数及显式空对象 |
| 真实MySQL | 4/4 | 迁移重复、持久化与清空/重放、源删除/车辆转移、无前车主字段继承、并发版本、审计失败回滚、旧content与里程不变 |
| Spring安全HTTP | 2/2 | 两接口匿名与演示/非车主角色拒绝、no-store；非生产身份 |
| 微信离线 | 361/361 | 本轮协议5、状态流5，旧351项回归；跨车响应/字段白名单/最小回执/错误/重试/确认/隐藏及账号变化 |
| 构建与产物 | 通过 | mp-weixin与兼容H5，普通产物无测试身份桥/原生共享样式；新structured路由和WXML存在 |
| 生成文件 | 通过 | init/baseline42表、OpenAPI151操作；MySQL完整迁移期望69表，由CI实际执行验证 |

本地命令：`mvn -B -o -Dtest=ArchiveStructuredInputTest,JdbcArchiveStructuredTest,ArchiveStructuredAccessTest test`（Maven/Java17容器）、`npm test --workspace @autocare/miniapp`、`npm run build:miniapp`、`npm run build:h5 --workspace @autocare/miniapp`、两个Python生成器及`git diff --check`。首次测试迁移名误写V028，修正后8项通过；本地Docker兼容/测试触发器选项沿用R4a1，不修改生产设置。

## 正确性与权限口径

原content、施工快照、PDF和分享摘要维持原字段，无历史自由备注回填。结构化资料按账号和档案隔离，来源/车辆/会话有效性逐次复核；保单等字段不出现在审计或幂等写回执。PUT全量替换，{}清空后版本保留；清空不删除原档案/照片/目标。本人里程只是该记录资料，不覆盖当前登记里程或R4a1目标。

## 尚未验收/未完成

开发者工具完整“档案→填写→刷新→修正→清空”流程，原生日期/枚举选择器和确认弹窗实点，以及iOS/Android真机。正式微信/SMS/官方查询/OCR/VIN、健康测量/六指标评分、驾驶证/违章结构化服务、自动提醒/订阅仍欠。本人填写的保险/年检资料不是官方核验结果。

GitHub分段#128后端、#129微信、收口文档；全量数量和六项CI按最新head记录在PR正文。本轮上传不自动合并部署。
