# A4d30 微信原生扫码接车

2026-10-23，基线b1a9031，分支codex/r9-a4d30-native-pickup-scan，依赖PR220。范围为商家扫码接车的原生按钮、协议与真实后端归属/状态验证，不提交接车单或资金操作。

## 实现与验证口径

- 商家订单页原提示“扫车主核销码”与实际 `VHMP:PICKUP:1:订单ID` 协议不符，修正为扫描车主订单详情的接车二维码。六位核销码仍用于核销，协议和业务权限未改变。
- 复用现有delivery_compare隔离栈与只读内容核对，新增互斥可选 `AUTOCARE_PICKUP_SCAN_NATIVE_QA=1` 模式。只在扫码模式准备额外PAID订单304，普通CI/对比模式不生成此夹具。
- 原生真实“扫码接车”按钮分别接收错误协议、非PAID订单301、外门店订单304及本店PAID订单304的测试扫码结果。归属和状态由真实HTTP处理，成功必须进入正确接车页且读取表单，错误留在订单页；返回订单列表时扫码API替身应恢复。
- 测试只辅助系统scanCode返回，不替换请求响应。相机物理识别、扫码解码、手机授权/取消交互仍需真机；取消、迟到结果与换身份已有离线流程回归，不宣称本轮原生取消已测。
- 系统API替身恢复、身份清空及私有会话文件删除均为成功条件；13项数据库只读检查及后端/MySQL/网络清理沿执行器完成。没有新增接口、渠道支付或数据库迁移。

## 本地检查

相关Python34项通过（套件11、执行器/构建来源15、交付索引8）；小程序1035项通过。页面源码改变后已更新交付摘要，页面总数仍110，历史接口覆盖仍317/352、36套来源及35项结论，全API验收false。实际原生及HTTP结果在本阶段收口时补记。

## 复现

先按[隔离微信构建说明](../testing/WECHAT_LOCAL_FINANCE_ACCEPTANCE.md)构建固定 `.cache/r0-native/mp-weixin`、回环后端18080且无云托管变量的测试包。**构建后**再打开开发者工具项目并确认RPC9420；已经打开时仅关闭并重开该隔离项目，避免旧包加载。

在仓库根目录执行：

```powershell
$env:AUTOCARE_PICKUP_SCAN_NATIVE_QA='1'
$env:npm_config_offline='true' # 仅在固定Newman已缓存时使用；首次执行可不设
try {
  python scripts/run_api_acceptance.py --delivery-compare-cases
} finally {
  Remove-Item Env:AUTOCARE_PICKUP_SCAN_NATIVE_QA -ErrorAction SilentlyContinue
  Remove-Item Env:npm_config_offline -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json' -ErrorAction SilentlyContinue
}
```

不能同时启用AUTOCARE_DELIVERY_NATIVE_QA。默认不启用原生，CI只跑既有HTTP及模式/清理测试；原始私有Newman与令牌不上传。正常微信包仍需 `npm run build:miniapp` 检查不含身份桥。正式身份、物理相机、文件/拍照上传及真机旅程另行验收。

## 最终实测与交付

90242a68e006整轮通过：原生6项，复用取车对比集合16业务请求/60断言、338匿名请求/1014断言，两默认禁用集合各0请求；13项数据库检查通过，来源稳定、系统API恢复、身份清空及三类资源清理成功。私有原生会话文件已删除，正常微信包无测试身份桥。[脱敏报告](../testing/reports/R9_A4D30_NATIVE_PICKUP_SCAN_2026_10_23.json)。扫码上下文的额外真实HTTP随原生脚本执行，不把它计为新增四类接口覆盖。

代码、测试、页面摘要、计划与实际报告同批上传，覆盖保持317/352。下一阶段优先迁移并验证受控文件/图片选取与上传的原生链路；存储、扫描及文件清理也要真实取证。正式身份、物理相机与手机旅程仍待验。本轮未重跑全量Java、性能、安全或正式供应商；前置PR220开始时5项CI成功，backend/performance仍运行，状态OPEN。
