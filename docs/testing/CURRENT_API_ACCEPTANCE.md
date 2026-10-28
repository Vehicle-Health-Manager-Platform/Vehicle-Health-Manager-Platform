# 当前版API统一运行器

入口：scripts/run_business_api_acceptance.py。完整业务范围仍以[统一验收清单](../delivery/FINAL_ACCEPTANCE_2026_10_28.md)为准；此工具的PASS只表示所选套件及证据通过，不代表全部362操作、正式身份、真实资金、真机、模型领域效果或生产验收。

## 启动

准备Python3.11、Docker Linux Engine、Node/npm，执行npm ci，并按scripts/requirements-export-qa.txt准备PDF验证依赖。首次构建需要获取固定测试镜像和Maven依赖。业务后端在Docker中的Maven/Java17构建；七套文件旅程同样使用原运行器的--docker，不要求宿主Maven。

完整新执行29业务加七套真实文件/扫描/图片/奖励旅程：

```powershell
python scripts/run_business_api_acceptance.py
```

每套新建数据库及自有资源，可能持续数小时。不要与任何共享backend/target的手工Maven或其他验收任务并行。互斥锁只保护本运行器重入，不承诺检测外部Maven。

选定业务（名称使用下划线，--help列出白名单）：

```powershell
python scripts/run_business_api_acceptance.py --suites community owner --journeys none
```

如果刚完成当前源码的完整后端，七套原始JUnit/响应证据仍在target，可明确只复核它们，同时新跑所选业务：

```powershell
python scripts/run_business_api_acceptance.py --suites community --journeys audit
```

run模式重新执行，audit模式标为AUDIT_EXISTING并检查当前源码绑定/原始HTTP清单/数据库和清理，不宣称再发送原请求；none不计七套旅程。默认run，未传CI复用jar参数，也不伪造CI变量。原生辅助QA和材料原生桥在子进程明确关闭，不修改宿主环境。

## 报告与失败处理

每次生成独立.cache/business-api-acceptance-随机值/report.json，记录所选范围、每套结果、源码/契约/清单摘要、去重覆盖、缺口分类、实际新执行和已有复核的请求数、清理与锁状态。失败后继续独立套件，最后非零退出；源码变化、缺报告、多份歧义报告、原始请求/返回不符、断言/数据库检查或清理失败均不能PASS。失败套件不计覆盖。

业务报告由唯一新增的api-acceptance目录取得，重新审计匿名和业务Newman原始结果；原私有环境仅在内存用于身份/正文绑定验证，不复制到汇总。旅程run前将三个旧输出备份到本次previous-套件目录再移除，零退出但没生成新报告不能借旧证据通过。audit不移除已有证据。

子进程和审计诊断留在本次*.private.log、*.audit.private.log及原运行器私有日志中。汇总只输出安全摘要，不包含令牌、完整请求/资料正文或原始异常文本。发布前另按交接流程审阅，不上传整个.cache目录。

操作覆盖按operation_id和normal/boundary/error/unauthorized去重。重复29次匿名探测不会变成更多接口；未选四类只表示本次范围未覆盖。缺口附带CONTRACT_VERDICTS中的独立结论，具体解释查该文件，不能把45项或局部缺口都当未开发功能。两个正式支付/退款回调依赖商户证书，仍属外部后置事项。

## 中断与互斥

遇到已有.cache/business-api-acceptance.lock即拒绝启动，不能自动按PID猜测删除。中断时等待本次子进程最多30秒完成其清理；若仍未结束，报告保留child_pid并保留锁，返回130，不计完成。确认子进程与本次自有Docker资源已结束、没有共享target的Maven后，再人工处理该锁；不删除其他项目的容器/卷。正常结束或已确认子进程退出才释放本次所有权匹配的锁，被他人替换的锁不删除。

## 验证范围

本批的运行器故障测试、实际选定新库执行及七套复核、默认回归结果见[阶段记录](../progress/R6A_CURRENT_API_ACCEPTANCE.md)。R5c2的完整249套/2568项及36套317项当前四类证据见[独立报告](../progress/RECOGNITION_R5C2_PUBLIC_VECTOR.md)，不冒称在每次工具测试中重新执行。下一批仍需10项已实现识别接口的完整四类HTTP归档、材料保留/删除、有限重试等。

## 可选识别补证（R6b3）

默认--recognition none保持原36套范围。显式新跑全部十项识别（VIN、行驶证、ASR/图文创建查询取消、车主/接车里程）：

```powershell
python scripts/run_business_api_acceptance.py --suites community --journeys none --recognition run
```

--recognition audit只复核已有当前源码的十项记录，不声称新请求或新清理。适配器重审每份JUnit/现场证据及源码/清理，把规范方法和路径匹配到现行OpenAPI operation_id，未知或重复映射失败。局部报告只汇总该次实际选择，不把旧317历史数相加冒充本轮全库覆盖。

识别的六组证据来自五个测试类，里程两组共享同一JUnit/数据库；执行项和数据库数量去重统计。识别模型、图片元数据/材料和存储为明确替身，不能当成真实供应商或仪表效果。结果见[R6b3报告](../progress/R6B3_MILEAGE_HTTP.md)。
