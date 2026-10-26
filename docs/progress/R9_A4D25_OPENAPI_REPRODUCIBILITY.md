# A4d25 OpenAPI生成源与CI漂移修复

2026-10-23，实际基线为PR215（`codex/r9-a4d24-delivery-merchant-view`，head `1d52e4c`），非本聊天旧记录的PR209。入驻附件验收、契约结案、取车对比后端/车主页面/商家入口均已完成，本轮不重复实施。当前分支 `codex/r9-a4d25-openapi-reproducibility`。

## 根因与修复

最新PR215共8项检查，7成功，`schema-and-ocr`失败。失败在第83步生成后的`git diff --exit-code`，不是OCR服务错误或环境抖动。A4d21/22只修改交付的`docs/api/openapi.json`，未同步生成源；重新生成把3处实现标记及取车对比的请求/响应/权限/无写键语义覆盖回旧版。

本地定向输出到`.cache`复现：352操作中恰4项漂移，components相同。新完整生成回归在修复前确实失败，修复后通过。

- 新增`r9_contract_updates_openapi.py`生成层，编码现行AI chat、受控材料上传/访问实现标记及取车对比完整契约；不把交付JSON读回作为输入。
- 原入口提取无写入`build_document()`，默认生成方式兼容原命令。
- 增加`python scripts/generate_openapi.py --check`，比较规范文本，漂移/缺失时非零退出且不修复或创建文件；LF/CRLF按文本读取统一，默认连续生成字节稳定。
- CI在任何资产生成之前跑生成回归与`--check`；保留末尾生成与git diff检查。
- 9项新回归验证完整文本复现、检查模式不写/不创建、漂移/非法JSON/空文件拒绝、连续生成、输出文件不是输入、调用间无共享状态，以及只读正文/无幂等键/权限/错误码和三处实现标记保留。

生产Java、数据库、接口路径、现行契约规范内容、历史覆盖和逐项结论均未改变。

## 验证

完整重放`schema-and-ocr`的85条run命令，85/85通过，耗时118.313秒，其中451项Python测试通过。checkout/setup动作由本地既有环境承担，`pypdf==6.18.1`精确版本已安装，未重复联网安装。再运行CI未包含的认证就绪单测6项，合计46套/457项Python测试通过，无失败。

重放包括所有Postman业务集合生成、覆盖核对、契约结论门禁、追踪表生成、初始化SQL生成、OpenAPI生成、Compose迁移核对和OCR编译。原失败的生成后差异检查通过；Postman、追踪表、初始化SQL与OpenAPI均无Git规范内容变化。

[执行报告](../testing/reports/R9_A4D25_OPENAPI_REPRODUCIBILITY_2026_10_23.json)。报告包含失败CI定位、修复前后摘要、新测试修复前失败证明、85条命令结果及本地环境边界，不含凭证或私有执行数据。

接口总数仍352，所选历史四类证据仍316，未有完整历史证据36项，全部仍有逐项结论；全API验收保持false。本轮未重新发起后端业务HTTP、性能或真机验证，不合并既有阶段PR。

## 下一功能阶段

财务对账仍未实现，保持原not-implemented结论。下一阶段先确定并实现本地支付/退款记录的按日按渠道查询：北京时间半开区间、CNY十进制、正式与LOCAL_TEST分开、异常来源可见；缺少真实渠道账单时，渠道金额及差额必须未知，不能把本地支付减退款宣称为渠道已对平。独立权限、只读无副作用及真实账单接入边界随规格一起确认。当前阶段不计财务功能交付。


阶段已上传[草稿PR216](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/216)，实现提交 `e037489`，base=`codex/r9-a4d24-delivery-merchant-view`，依赖PR215；远程CI已触发，结果以PR实际检查为准，未合并。
