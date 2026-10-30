# 本地授权VIN规则与精确车型候选

新增来源默认关闭，不包含真实国内车型数据库，也不提供其他车主的车牌反查。NHTSA模式仍可选且仅有限覆盖美国登记数据。两种模式不能自动互相回退，避免未经同意向境外发送VIN。

本地模式配置 `VIN_DECODE_ENABLED=true`、`VIN_DECODER_PROVIDER=LOCAL_LICENSED`、`VIN_LOCAL_DATA_PATH`（服务端可读文件绝对路径）、`VIN_LOCAL_DATA_SHA256`（该文件原始字节SHA256）。前端构建设 `VITE_VIN_PROVIDER=LOCAL_LICENSED`，重建后用户同意版本为 `VIN_LOCAL_V1`。后端与前端来源不一致会拒绝请求，不能默默变更用途。默认NHTSA前端使用 `VIN_VPIC_V1`。

文件格式见 [合成夹具](../testing/fixtures/vin-local-synthetic.json)：schema_version=1、revision、license_reference、synthetic、valid_from、valid_until、rules。每条规则含6–11位VIN前缀、make/model/model_year/trim四个精确目录名称。文件最大1MiB、1000条；拒绝重复JSON键、异常字段、无效日期/年款/文本。前缀可能对应多个配置时返回NO_MATCH，不能把第一条当成精确车型。规则重复但四字段相同可合并。

每次查询重新核对SHA、授权日期和文件可读性。授权撤销时移除文件或修改SHA；请求失败后仍复核会话。规则匹配后，后端按品牌/车系/年款/配置逐字精确匹配，三层目录均需启用且未删除；只有唯一结果才返回catalog_candidates。没有、多个或停用结果返回空候选。客户端显示来源和版本，用户确认后重新读取最新目录，再选入表单，最终保存再次走原有权限/目录校验及幂等事务。

本阶段仅合成夹具验证：`VIN_LOCAL_ALLOW_SYNTHETIC=true`为明确测试开关，默认false。夹具全链路标识LOCAL_SYNTHETIC和“合成测试规则（非真实识别）”。真实授权文件应设synthetic=false，核实合同允许平台解析、保存目录映射及使用范围。把文件的synthetic改为false并不能证明拥有数据授权。

PowerShell取得SHA：`(Get-FileHash -Algorithm SHA256 -LiteralPath '绝对文件路径').Hash.ToLowerInvariant()`。Docker运行需以只读bind额外挂载规则文件（不要提交真实授权文件）；VIN_LOCAL_DATA_PATH使用容器内路径。现有Compose默认没有挂载任意主机文件，请用私有override声明 `volumes: ['主机文件:/run/vin/rules.json:ro']`。本地JAR可直接使用主机路径。

正式接入资料与验收：数据授权合同/使用与转授权范围、来源修订/有效期、车型覆盖及精度说明、供应商脱敏已知样本和内部目录；逐项核对唯一匹配、歧义、无匹配、停用、过期、撤销、篡改、账户撤销、限流及确认后目录变化。须用获授权的真实样本单独测准确率和覆盖率。本地夹具不代表真实国内VIN识别通过。新车车牌→车型仍需有资质且经车主同意的数据来源，不得扩大现有本人记录查询权限。
