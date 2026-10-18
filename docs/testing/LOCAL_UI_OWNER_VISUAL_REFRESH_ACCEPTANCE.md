# 车主端视觉刷新本机验收

2026-10-18。范围：车主端 9 个页面/组件的视觉改动（设计令牌、全局设计系统、共享样式层升级）。无后端、无迁移、无 API 变更。

## 复现命令

在仓库根目录执行：

```bash
# 1. 前端全量测试（含请求方法/路径/信封与页面装配断言）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（注意：若 dist/build/h5 有上次产物，先把它改名移走）
npm run build:h5 --workspace @autocare/miniapp

# 4. 模拟器冒烟（需微信开发者工具已打开 dist/build/mp-weixin 且就绪）
node apps/miniapp/test/simulator-smoke.cjs
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 916 / # pass 916 / # fail 0 / # skipped 0`，4625ms |
| 2 | exit 0，`DONE Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE Build complete.` |
| 4 | **INCOMPLETE**：`complete build and source route inventory match`、`native WebSocket handshake and RPC connection` 两项 PASS，随后 `Devtools RPC timeout: App.callWxMethod` 中断 |

第 4 步未完成的原因是本机开发者工具未就绪，不是构建或源码问题；本轮**不**据此声明 UI 验收通过。

## 第 3 步为什么要把旧产物移走

`uni build` 产出前会清空 `dist/build/h5/assets` 下上次构建的文件。当前开发工具环境对批量删除有拦截，会直接判定构建失败，日志形如：

```
[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":262,"threshold":50,
 "targets":["...dist\\build\\h5\\assets"]}
Build failed with errors.
```

这不是源码错误，也不是偶发：只要 dist 里有上次产物就会复现。在普通终端里直接跑同一条命令不受影响。

## 验收口径

- 测试全绿只证明"请求契约与页面装配没被改坏"，不等同真实联调。
- 构建通过只证明可打包、共享样式注入未丢失、普通包内不含测试身份桥。
- 界面是否"变好"属于设计判断，本文件不提供像素级对照；真机与设计稿比对留到 C 段。
