新增github-*报告对应合并前两项P2修复后的源码；其余阶段报告保留审查前原始来源和失败记录。Git提交保留JSON原始字节，不转换行尾。详见[GitHub补验](../../GITHUB_MERGE_VALIDATION_2026_10_30.md)。

# 1/3/4/5本轮证据

只包含已审核的结构化JSON、输入/JAR哈希和合成页面截图；不包含.env、JWT、SQL种子、业务正文或私有日志。manifest记录发布文件SHA；原始记录及父运行哈希保留.cache，不得整体外发。

backend-full/core-coverage为本轮完整255套2641项直接采集；local-regression为最终12组。recognition-http是同次Java原始HTTP记录严格复核，不算再次执行真实供应商。security完成true但漏洞门禁false，不能解释为安全全通过。linux-ocr为真实离线模型与合成图片。

ui-current为管理员修复后的最后源码，113游客页和车辆503重试/唯一新增等；ui-feature-before-admin-fix仅覆盖同轮较早三分类/目录对比/合作方取消/角色菜单，管理员故障随后修复，不能说该报告验证了最后源码。比较图来自该较早运行，新车移动/桌面图来自最终运行。全部显示合成身份标签，非正式微信/真机/真实合作方。

performance-linux-steady保留原门槛500并发稳态通过；另外三份failed报告是保留的反例，必须与通过结果一起阅读。写P95不满足原文12.1统一300ms；Windows/短预热不通过；不等于生产长稳通过。

current-api-original-aggregation-failed保留本轮29业务执行通过、末尾来源汇总失败的原报告；current-api是修复工具后的严格原始证据重审，37项通过/327个有效操作四类齐全，明确不是新HTTP执行。完整结论见[测试报告](../../SCOPE_1345_ACCEPTANCE_2026_10_30.md)。

ui-readback-recovery补充同源码新库下保存→档案列表→选择新车→空档案回读及目录503恢复。local-regression/security已更新为汇总工具修复后的最终冻结版本，Python623项；原始较早报告仍留.cache。
