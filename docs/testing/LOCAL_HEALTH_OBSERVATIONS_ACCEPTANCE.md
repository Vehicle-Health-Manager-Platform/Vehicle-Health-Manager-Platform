# R4a3 本人当前读数验收

日期2026-10-12。

| 层次 | 本地结果 | 范围 |
| --- | --- | --- |
| Java输入 | 2/2 | 单位/数值精度/百分比格式、有效/未来日期、指标及字段白名单、显式清空 |
| 真实MySQL | 4/4 | V030重复、六序/两类电池隔离、读数持久化/重放/清空、源删除/车辆转移、换来源确认、版本竞争、审计回滚及旧content不变 |
| Spring安全HTTP | 2/2 | 三端点匿名与演示/非OWNER拒绝、no-store；非生产身份 |
| 微信离线 | 371/371 | 本轮协议5/状态流5，旧361项回归；单位/日期/来源、六序/跨车响应、最小回执、重试/冲突/确认/隐藏及账号车辆变化 |
| 构建与产物 | 通过 | mp-weixin/H5、共享原生样式、普通产物无测试身份桥、health/health-observation注册与WXML |
| 生成 | 通过 | init/baseline43表、OpenAPI154操作，完整链70表由GitHub MySQL实际验证 |

本地命令：Maven/Java17容器 `mvn -B -o -Dtest=HealthObservationInputTest,JdbcHealthObservationTest,HealthObservationAccessTest test`；`npm test --workspace @autocare/miniapp`、`npm run build:miniapp`、`npm run build:h5 --workspace @autocare/miniapp`；两个生成器及`git diff --check`。首次fixture迁移名引用旧版本，改为V030后全部通过；Docker兼容和测试触发器设置沿用前阶段，不是生产设置。

## 口径

六类是本人当前原始读数，名称、日期、方法和来源明确，仍未核验；适用性/合格阈值/有效期不推断。百分比不是平台健康分；动力电池与低压电瓶独立。不把NOT_SET或SOURCE_UNAVAILABLE当健康，来源失效隐藏旧ID/值。

新車主不继承前车主个人读数；每次读写/重放复核会话/归属/来源。写入/审计/回执同事务，审计与写回执不含私有值或方法。修正/清空不改档案content、结构化资料、登记里程、提醒、PDF/分享权限。

## 尚未完成/验收

原生指标/日期/单位/来源选择器与确认实点、完整“源档案→填写→列表→修正→清空”流程和iOS/Android真机。历史趋势、可信工单测量/供应商、健康评分、完整证件/官方信息、提前/订阅和正式身份/资金仍欠。

后端#131、微信#132、收口文档为堆叠PR；完整CI数量以各PR最新head正文为准。本阶段所有README保持不动，原用户交付文档未上传。不自动合并部署。
