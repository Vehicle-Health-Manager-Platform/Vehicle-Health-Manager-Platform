# R4 完整收口验收

日期：2026-10-13。代码、迁移、API、页面、测试、规格及此文档在全部 R4 完成后统一上传。范围与来源边界见[契约](../api/R4_COMPLETION.md)。

## 已执行检查

- 新健康数据库与HTTP初轮16/16；与档案/分享联动42/42；星级报价、字面搜索、AI私有证件隔离及健康回归37/37。各轮有重叠，不相加为新测试数。
- 小程序全量499/499，通过健康来源/分数复算、未知状态、趋势、权限/异步陈旧响应、确认重试、提前偏好、完整档案、搜索/症状、星级报价及预约排序测试。
- 微信构建、原生样式校验、测试身份隔离检查通过；H5兼容构建通过。微信组件曲线使用uni原生canvas，并使用当前组件作用域；日期间隔和参考分环形长度取实际数据。
- 真实MySQL完整迁移重复重放通过，80表；规则与确认均无种子回填。真实健康数据库响应经严格前端投影校验通过（总分、来源版本与MUST证据）。
- 本地完整后端748项覆盖通过（全量命令中的741项 + 环境修复后真实上传7/7）；健康证据目录复核1/1通过且不重复计数。初轮全量命令742项、Errors=1，失败为整组容器初始化，不将该命令标成BUILD SUCCESS。日志`r4-upload-host-alias.log`记录修复后7/7、BUILD SUCCESS。GitHub最终单次完整运行和六项CI结果在[执行记录](../progress/R4_COMPLETION_EXECUTION.md)及统一PR正文更新。

本地完整日志保存于忽略目录`.cache/r4-health-tests-2.log`、`r4-integrated-tests.log`、`r4-completion-remaining-tests.log`、`r4-miniapp-final.log`、`r4-wechat-final.log`、`r4-h5-final.log`、`r4-schema-final.log`、`r4-backend-full-2.log`。日志不提交令牌/本机配置；可提交证据以测试代码和最终CI为准。

## 关键用例

1. 未配置规则/车型未知/规则过期/读数缺失或过期均UNKNOWN；不能把部分均分标为整车分；全不适用仍UNKNOWN。
2. 来源名称/单位/方法/口径匹配与年龄边界；无本人确认无分数；改车型/改规则/改读数需重确认；停用车型仍能撤回。
3. 多权重BigDecimal计算与HALF_UP；分段边界含等号；前端拒绝伪造总分、MUST来源不一致和遗漏触发证据。
4. 修正链跳过旧值；清空、源删除/变更/归属变化、过期和口径改变断开趋势；实际日期间距，不造历史分。
5. 同规则版本双运营写入仅一方成功；越权/非法字段/单位/日期/金额拒绝；原回执不能复活已撤回确认。
6. 技术MUST只在当前确认分数低于规则且项目精确适用时出现；项目停用/适用性冲突/撤回/未配置不触发。
7. 默认提前0，用户范围边界和当前里程正确；故障/违章完整确认及处理状态；最新来源失效不复活同案旧警报；扫描限额明示。
8. 完整手动证件字段/日期顺序/有限与长期有效期/确认必填；本人PDF保留、分享与AI自动上下文排除8/9/10，含未分类记录计数隔离。
9. 真实公开星级授权撤回/资格失效动态改变排序；测试单隔离、零样本null、显示平均值与排序一致；区域/分页校验。
10. 字面SQL搜索不把%/_作通配；症状一次内存、账号绑定、过期/隐藏清除；未经AI用途确认不发送模型；排序与预约衔接不保留陈旧坐标。

## 复现命令与环境事实

小程序沿用`apps/miniapp/package.json`的test/build:mp-weixin/build:h5命令。MySQL重放沿用`scripts/verify_mysql_schema.sh`，OpenAPI沿用`scripts/generate_openapi.py`。后端使用Java17/Maven3.9与Testcontainers真实MySQL/MinIO，不把MockMvc授权测试宣称为真实HTTP端到端。

本机全量命令的首轮在测试前失败：PowerShell拆分未加引号的Maven点号属性，随后DockerHub的alpine元数据返回text/html。已给Maven属性加引号；本机使用缓存MinIO和mc，其SHA256与仓库Dockerfile锁定值逐字一致（MinIO eef6581f6509f43ece007a6f2eb4c5e3ce41498c8956e919a7ac7b4b170fa431；mc 01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891），并覆盖当前initialize.sh（SHA256 f1339ddc1e7d84b3cae08e9866e6a33b5ca80accc013bd509a27bdc96768762a）后执行完整测试；未修改仓库镜像定义或测试断言。随后ClamAV镜像清单异常阻止真实上传组初始化；验证官方直连缓存`registry-1.docker.io/clamav/clamav:1.4.3_base`（digest sha256:629a3050df6a706aedb31859fbb8139e9aaf8f56b0ffefbf251b8358a7c9e76c，实际clamd --version=1.4.3）后恢复本机同版本标签。Maven桥接容器的host.docker.internal不符合HTTP公开端点回环限制，Desktop共享主机网络也不可用；最终仅在一次性Maven容器内将localhost测试别名映射同一宿主机发布端口，七项真实扫描/匿名拒绝/签名过期/持久HTTP/初始化权限测试全部通过。宿主机hosts、项目生产地址校验及测试断言均未改变。

## 验收范围

这是R4可配置引擎和人工来源流程验收。实际生产车型手册、阈值、权重与有效期尚未配置，系统显示未知；没有官方可信测量/供应商识别/正式短信身份资金/真实模型效果/物理真机或发布验收。公开照片与完整社区属于R8，券/邀请提示随R5/R6依赖；不以本轮代码或CI替代这些验收。
