# 微信资金查询验收复现

实际结果见[A4d27](../progress/R9_A4D27_LOCAL_FINANCE_ACCEPTANCE.md)和[脱敏报告](reports/R9_A4D27_LOCAL_FINANCE_API_2026_10_23.json)。全部业务数据为合成夹具；WECHAT只代表库内历史记录字段，不连接正式支付渠道。

## HTTP/MySQL（本地及CI）

需要本机Docker引擎、Python、Node/npm。仓库根目录执行：

```powershell
python scripts/local_finance_api_cases.py
python -m unittest discover -s scripts -p test_local_finance_api_cases.py -v
python scripts/run_api_acceptance.py --local-finance-cases
```

执行器重建当前JAR、新建带所有权标记的专用网络/MySQL/后端、应用迁移、准备合成数据、运行Newman、审计请求及源表内容、清理专用资源。不得复用正式库；与其他业务套件参数互斥。CI的 `--ci-built-jar` 仅接受新checkout中先记录来源再构建的JAR，不能用于绕过本地重新构建。

本地未设置原生开关时，报告明确 `local_finance_ui.executed=false`，不能宣称原生检查通过。集合默认禁用且实际零请求；私有令牌、Newman完整报告和构建产物只在忽略的.cache内，不应上传。只使用执行器生成的脱敏report.json作为历史证据，核实passed、sources_unchanged及三项cleanup后再登记。

## 可选微信开发者工具

先在 `apps/miniapp` 构建独立测试产物：

```powershell
$env:R0_NATIVE_TEST_BUILD='1'
$env:UNI_OUTPUT_DIR=Join-Path (Resolve-Path '../..').Path '.cache/r0-native/mp-weixin'
$env:VITE_API_BASE_URL='http://127.0.0.1:18080'
$env:VITE_WECHAT_CLOUD_ENV_ID=''
$env:VITE_WECHAT_CLOUD_SERVICE=''
& '../../node_modules/.bin/uni.cmd' build -p mp-weixin
```

仓库根目录另一终端（不要把测试构建开关带入普通微信构建）：

```powershell
'{"setting":{"urlCheck":false}}' | Set-Content -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json' -Encoding utf8
& '<工具安装目录>/cli.bat' auto --project "$PWD/.cache/r0-native/mp-weixin" --port 11927 --auto-port 9420 --trust-project
$env:AUTOCARE_FINANCE_NATIVE_QA='1'
try {
  python scripts/run_api_acceptance.py --local-finance-cases
} finally {
  Remove-Item Env:AUTOCARE_FINANCE_NATIVE_QA -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json' -ErrorAction SilentlyContinue
}
npm run build:miniapp
```

先确认9420已返回本项目页面，18080未被其他服务占用。开启原生开关后连接/断言/身份清理失败会使本轮失败，不会降级成成功；关闭开关时只验HTTP。测试脚本由执行器传入短期私有会话，清理内存身份并删除原生私有会话文件；库和容器由执行器收口。普通微信构建扫描确认没有身份桥。

原生检查使用实际页面、picker元素、按钮及真实HTTP；日期/渠道的change事件由RPC辅助。手机选择器交互、正式登录、真机、正式资金及渠道账单仍待独立验收，不能由本脚本代替。
