# A4d-5 接口验收覆盖核对

日期：2026-10-21。独立分支 `codex/r9-a4d5-api-coverage` 从 `origin/main`（b41c3b1）创建。PR195的退款页仍在原分支，不重复提交或修改；两阶段独立交付。

## 计划与实现

本阶段实现 `scripts/summarize_api_coverage.py`，对当前OpenAPI、16套Postman清单/集合/历史执行报告及3套专用HTTP旅程进行自动核对，生成确定性的[逐项JSON](../testing/api-coverage.json)和[缺口清单](../testing/API_COVERAGE.md)。设计与校验规则见[阶段设计](../superpowers/specs/2026-10-21-api-coverage-design.md)。

工具严格区分用例定义、匹配的历史执行记录和当前版本执行。缺少报告仅保留定义；失败、重复、缺失、跳过、哈希/方法/路径/分类/统计不匹配的已选记录拒绝核对。专用旅程检查完整案例、固定检查项和五类资源清理。合并统计按接口去重，保留每套证据路径和SHA256，不拷贝私有正文。

CI的schema-and-ocr增加测试与 `--check` 门禁；契约、用例或证据变化后须重新生成核对报告，不能默默使用旧完成数。

## 实际核对结果

- 当前契约352项，其中受保护接口338项。
- 所选19套记录中，216项接口有完整四类定义和匹配的历史执行证据（按接口去重）。
- 136项尚未在这些记录中取得完整四类覆盖。其他单元/HTTP测试未全部适配入表，不能把136等同于未开发功能或完全没有测试。
- 本次新发起业务HTTP为0；`current_acceptance=NOT_RUN`，`full_api_acceptance_passed=false`。历史输入源码稳定只代表原执行期间，不能证明当前后端已重跑。
- 16套Postman定义单独合并为198项，专用HTTP旅程补充后为216项；简单累加各套接口数会重复统计。

## 下一补测阶段

优先支付/退款恢复和佣金六接口，再是施工/通知、经营政策与月结；最后按终端范围处理身份/模型/官方渠道及旧契约入口。每批独立定义四类用例、执行真实本地HTTP/数据库验证并上传，不等待全部136项才形成交付。

首批六接口：

- GET `/api/payments/{id}`
- POST `/api/payments/{id}/reconcile`
- GET `/api/merchant/orders/{id}/refund`
- POST `/api/merchant/orders/{id}/refund/reconcile`
- POST `/api/order/{id}/refund/withdraw`
- GET `/api/merchant/commissions`

沿用LOCAL_TEST，验证当前身份/归属、状态变化、原请求与退款流水一致、失败恢复以及佣金冲销。是否已有可复用Java/HTTP用例先查证，再补证据适配或实际缺失用例，不重复造轮子。正式微信/真实资金继续后置。

## 验证与上传状态

本次本地验证完成：新增核对工具19项、既有API证据9项、16套业务用例脚本148项、私有上传9项、档案授权10项、材料/工作台10项，共205项测试通过。输出重现/漂移检查、Python语法与diff检查通过。

补齐跨平台测试：当前Windows文件实际为CRLF，Git存储为LF；输入指纹规范化换行、历史集合哈希严格匹配LF或CRLF表示。模拟两种文件树输出一致，修改集合内容仍被拒绝，避免仅本地通过而Linux CI失败。

此次不修改小程序或后端，不把前阶段1030项测试算作本阶段实测，也不把汇总工具通过描述为接口验收通过。阶段提交和PR在上传后回填；远程CI、合并与上线独立记录。
