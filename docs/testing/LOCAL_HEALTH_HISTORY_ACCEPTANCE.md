# R4a4 本人读数历史验收

2026-10-12。隔离测试数据，无真实手机号、资金或生产身份。所有README按用户要求不修改。

## 已执行

| 项目 | 结果与证据 |
| --- | --- |
| 输入/安全/JDBC 定向 | 12/12通过，JdbcHealthObservationTest、HealthObservationInputTest、HealthObservationAccessTest |
| 修正链 | 新测量→两次修正→清空，原值12.50保留；supersedes关系与corrected标记正确；不允许修正已清空/跨来源/过期版本 |
| 幂等/并发/事务 | 重放修正不重复历史；同版本竞争只追加一行；审计拒绝时当前值和历史同时回滚；旧客户端指纹不变 |
| 迁移 | 当前revision9仅生成一次MIGRATED_SNAPSHOT，重放不重复、不造1..8履历；随后修正保存revision10 |
| 权限/来源 | 失效来源隐藏值与ID/日期；档案移至他车隐藏值；转移后旧车主404、新车主无旧私人历史；会话撤销401 |
| 分页 | 倒序/游标/最后一页null；before=0、limit=51拒绝；边界页空且无下一页 |
| 小程序全套 | 本地377/377；新增6项历史/修正契约与状态机测试 |
| 原生微信构建 | mp-weixin成功，新health-history页面wxml/js/json产物存在；全局样式和无测试身份桥检查通过 |
| H5 | 仅兼容构建成功，不作为PC功能交付 |
| 后端全量CI | #134最终run38228614263：625/625，六项全绿；其余最终head/CI写在对应PR正文 |

## 可复现命令

小程序：`npm test --workspace @autocare/miniapp`；微信：`npm run build:miniapp`；兼容：`npm run build:h5 --workspace @autocare/miniapp`。

MySQL集成：在Java17/Maven3.9及Docker可用环境执行 `mvn -B -Dtest=JdbcHealthObservationTest,HealthObservationInputTest,HealthObservationAccessTest test`。本机Docker29容器测试需JAVA_TOOL_OPTIONS=-Dapi.version=1.44及TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal；禁用本机损坏缓存的Ryuk，由JUnit容器生命周期清理，不改变CI策略。

全链MySQL迁移和六项CI以最新PR Checks核对，不能用旧head结果代表新提交。

## 尚待验收与范围

微信开发者工具实际点击、手机真机历史翻页/确认/弱网/账号切换尚未执行，本阶段没有声称通过。历史永久删除、曲线/健康分、可信门店测量、车型与有效期规则、外部服务和生产发布均不在已实现范围。清空当前明确保留私有历史。
