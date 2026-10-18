# A4a覆盖率基线执行记录

日期2026-10-18，基线2576060，分支codex/r9-a4a-core-coverage，依赖PR159。用户授权按下一步规划后实施并逐阶段上传；本阶段只完成A4a，不将A4b/A4c写成完成。

## 实施

- 新增固定JaCoCo 0.8.12 Maven profile，单fork采集、独立数据/报告目录。
- 固定六组33个业务根类及全部内部类，逐组与总体LINE/METHOD均≥70%，披露BRANCH；不得降低阈值、隐藏内部类或用总覆盖率掩盖低组。
- 新增真实测试运行器、失败关闭的XML/JUnit门禁与自身异常测试，记录精确命令/源摘要/环境及结果。
- CI全量backend复用本次插桩，门禁与报告artifact同步接入，不重复运行两遍全量。

## 实际进度

门禁及运行器自身14项测试通过；真实19套件431项执行全部通过，失败/错误/跳过均0。Maven clean verify通过，用时1355.36秒，测试期间backend/src摘要一致。最终总体LINE **93.43%（1137/1217）**，METHOD **89.91%（481/535）**，六组两项均≥70%；BRANCH **68.13%（1712/2513）**仅披露，不计本次门槛。范围与阈值未为结果缩减；核对原文后在已有LINE70上增加METHOD70。

- coupons：LINE 90.43%，METHOD 86.79%，BRANCH 64.64%。
- invitations：LINE 91.24%，METHOD 79.46%，BRANCH 69.02%。
- assessment：LINE 97.08%，METHOD 95.8%，BRANCH 70.66%。
- mileage_and_vehicle：LINE 92.91%，METHOD 96.88%，BRANCH 65.83%。
- order_states：LINE 94.18%，METHOD 92.67%，BRANCH 67.91%。
- write_integrity：LINE 92.96%，METHOD 100.0%，BRANCH 70.37%。

[实际逐类报告与来源摘要](../testing/reports/R9_A4A_CORE_COVERAGE_2026_10_18.json)。原始HTML/XML/CSV在忽略的backend/target/site/core-coverage，本机原命令与环境在core-coverage-run.json；CI全量另产生artifact。报告不是整个平台覆盖率。

定向基线中CouponOrderInput、InvitationInput、InvitePolicyInput、ReservationInput四个输入类的单类指标低于70%，已完整保留在JSON中；组门槛通过不表示每类达到70%。全量CI包含已有HTTP解析测试，A4b再核对接口/输入异常覆盖。

环境：Windows宿主、Java17/Maven缓存容器、Testcontainers临时MySQL8；只用本地合成身份/业务数据，不调用正式接口。首次Python测试临时目录遇宿主沙箱权限，改为仓库.cache内的独立临时目录并验证绝对边界后清理，9项重跑通过。

无业务API/SQL/前端改动，352操作/131表/62挂载保持。测试覆盖率与功能正确性不同，不将测试数量或隔离资金当生产效果。

## 文档与后续

[范围与复跑](../testing/CORE_COVERAGE.md) · [设计](../superpowers/specs/2026-10-18-core-coverage-design.md) · [计划](../superpowers/plans/2026-10-18-core-coverage.md)。代码/profile/门禁/测试/CI/报告与文档已上传[PR160](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/160)，代码提交 `a410638`，base为 `codex/r9-a3-candidate-confirmation`。远程全量CI待完成，保持草稿，结果以PR当前head为准；不合并部署。前序PR158六项CI已全部成功，PR159后端尚在运行。下一A4b核心业务端到端，再A4c性能与备份恢复，随后A5与B段真实接入。
