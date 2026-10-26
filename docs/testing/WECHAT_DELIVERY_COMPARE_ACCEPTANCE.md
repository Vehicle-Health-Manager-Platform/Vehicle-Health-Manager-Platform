# 微信原生取车对比验收

范围：车主取车对比页及商家核销前真实按钮导航，合成SQL数据、隔离HTTP及微信开发者工具。只做读取，不提交核销，不预览真实照片，不代表手机触摸、扫码、正式登录或真实资金验收。

## 复现

先按[已有隔离微信构建与工具启动步骤](WECHAT_LOCAL_FINANCE_ACCEPTANCE.md)构建 `.cache/r0-native/mp-weixin`，使用回环后端18080、RPC9420；不要运行其中finance套件。构建完成后再用CLI打开项目；若工具在构建前已打开，先仅关闭该隔离项目再重新auto，避免旧页面缓存。工具加载当前小程序首页、18080空闲后，在仓库根目录运行：

```powershell
$env:AUTOCARE_DELIVERY_NATIVE_QA='1'
try {
  python scripts/run_api_acceptance.py --delivery-compare-cases
} finally {
  Remove-Item Env:AUTOCARE_DELIVERY_NATIVE_QA -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json' -ErrorAction SilentlyContinue
}
```

执行器重新构建JAR、新建专用数据库/网络/后端，用既有delivery_compare夹具运行338匿名请求和16业务请求；随后以短期私有会话运行原生脚本。正常、证据不齐、完成后回看、缺接车基线及跨归属错误均不应泄露旧结果。商家用核销页真实“取车对比（核销前核对证据）”按钮进入商家视角。

显式开启时，连接、断言或身份清理失败即本轮失败；默认不开启时只运行HTTP，并明确报告原生未执行。同路由重新进入需同时等待新pageId，不能只比较路径；脚本已内置此等待。元素读取遇到工具RPC错误时最多重试两次，重取句柄前核对路径与query相同；点击和业务操作不重试，计数单列。脚本错误不打印令牌，私有原生会话文件在finally删除。先核实源码稳定、数据库完整内容不变、delivery_compare无新增记录及三类资源清理成功，才可登记通过；原始Newman和凭据不上传。

不要使用已有生产库或将测试会话桥带入正常微信包。普通 `npm run build:miniapp` 必须通过不含身份桥门禁。页面源码变化后，还需 `python scripts/miniapp_handover.py` 重生成交付摘要并核对变动。
