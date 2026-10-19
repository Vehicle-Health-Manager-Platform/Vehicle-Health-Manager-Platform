# 全API验收资产与匿名门禁（A4d-1）

## 已有资产与未完成范围

`docs/api/openapi.json` 是本轮来源。生成入口 `python scripts/generate_api_acceptance.py`，三个文件位于 [postman目录](postman/)：

- `anonymous.postman_collection.json`：338项受保护契约，每项实际发送一次无令牌请求，检查HTTP401、JSON code40100、Cache-Control含no-store。
- `prepared.postman_collection.json`：352项请求准备模板，默认全部禁发；需显式开启、配置具体业务案例才能发送。
- `acceptance_inventory.json`：逐API四类案例清单。正常/边界/错误均为NOT_RUN；338项未授权初始也是NOT_RUN（生成文件不是执行证据）；14项公开接口标为另审。真实匿名结果另存实际报告，不篡改清单为“所有案例通过”。

**匿名401不证明该控制器已实现**：全局安全过滤也会拒绝未实现路径。它验证匿名访问边界，不验证跨角色/跨用户/跨店授权，也不验证公开登录、回调验签、文件上传、正常保存或业务边界。352份模板不等于352项完整验收。原文Postman/Newman四类全量验收仍未完成。

## 隔离复跑

要求Docker Engine、Python3.11+、Node/npm。项目根目录：

```powershell
python scripts/run_api_acceptance.py
```

入口构建当前后端jar，复用性能阶段已经验证的容器归属检查封装，新建唯一标签的网络/MySQL/后端，执行62个迁移、确认131表；不使用已有库、业务凭据或真实用户，不接受远程base_url参数。匿名检查不灌入正向业务夹具。实例发布到随机本机环回端口，所有业务和外部开关沿用未配置默认。

Newman固定6.2.1，通过npm exec安装在被忽略的.cache/npm-newman缓存，不修改项目依赖或全局安装。首次需能访问镜像/Maven/npm源。端点采用契约方法，路径参数用合成值1；无JWT时应在业务参数绑定前拒绝。不是用错误参数的400代替认证401。

脚本验证实际请求方法与路径、338个唯一案例完整执行、每请求3条断言未失败/跳过；额外实际运行默认禁发模板，要求发送请求数为0。前后核对131表行数未变化；结束检查实现输入稳定性并回收本轮容器/网络。日志及Newman原始JSON只在本轮.cache目录；公开报告仅记录操作ID、计数、哈希、检查和清理结果，不含令牌或响应业务行。

退出码0、最新本次report.json的passed=true才表示本轮匿名范围通过，full_api_acceptance_passed始终false。不能用历史成功报告、请求发送成功、案例数量或模板跳过来充当验收。

CI：schema-and-ocr检查生成一致性及必要证据测试；backend在clean verify后运行同一Newman入口（CI专用--ci-built-jar，核对jar不早于来源文件），上传api-acceptance-evidence报告。完整后端CI仍单独决定整套回归结果。

## 使用业务模板

在Postman导入prepared集合，初始base_url为不可用本机端口1，enable_prepared_requests=false。先复制到个人私有工作区，只选择本次案例，确认隔离环境和数据，再设enable_prepared_requests=true。

每项请求需要配置：status_操作ID（明确预期HTTP状态）、路径/查询/请求头变量（含幂等编号）、受保护请求的access_token、JSON正文body_操作ID或multipart文件file_操作ID。所需变量未配置会跳过并报错，不能当成业务通过。可选查询项不用时请在个人副本中移除，不通过填错值伪造边界案例。不同角色需分别使用相应身份和归属数据，不能用同一令牌假装全部角色。

模板只准备请求结构与明确状态断言，业务正文不会凭空生成“有效”样例；文件、签名回调、流式响应、PDF及特定用途请求还须按现有API专项文档补齐内容类型与专门断言。真实密钥、令牌、私有图片和环境文件不得提交仓库。

## 下一阶段

A4d-2先核定源草案中历史占位的适用性，以已确认软件范围为准，不恢复已取消的PC后台或正式H5。再按业务域补可复跑状态夹具及正常/边界/错误、跨角色/跨归属案例，优先车主车辆/档案→预约履约/券/资金本地协议→商家入驻与员工→运营治理/社区/知识，复用已有JDBC/HTTP测试证据但不冒充Newman执行。14项公开端点分别检查实现状态、未配置回退与验证协议；真实身份/正式资金/识别仍留B。性能延迟未达标事实继续保留，A4d-1不销掉该项。
