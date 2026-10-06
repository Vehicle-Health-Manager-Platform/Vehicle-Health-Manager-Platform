# 本机标准服务项目验收

日期：2026-10-06。实际结果以[执行记录](../progress/S0_EXECUTION_LOG.md)为准。

## 环境与步骤

固定隔离项目 `vehicle-auth-local`、MySQL 容器 `vehicle-auth-local-mysql-1`，完成 V001–V005；新服务模块无迁移。后端微信/JWT/数据库配置保存在私有环境，只监听 `127.0.0.1:18080`；小程序 `.env.local` 指向这个本机 API。

```powershell
node scripts/prepare_local_service_catalog.cjs --allow-local-test-writes
npm test --workspace @autocare/miniapp
npm run build:miniapp
npm run build:h5 --workspace @autocare/miniapp
```

准备器无显式参数即拒绝。检查固定 Compose 项目及 ID 9101101–9101126；已有 ID/唯一名称/分类/内容/价格/状态须完全匹配，冲突即停止，不覆盖现有记录。准备 24 条启用项目、1 条停用、1 条软删除，前 22 条保养、后 4 条轮胎，明确标为本地合成测试；保留在隔离库供复验，不作为生产参考价。

用 Java 17/Maven 或仓库 Dockerfile 构建新后端，在原隔离网络复用私有配置/MySQL，保留旧镜像/容器供回滚，不删除数据卷。核对健康 UP 和匿名项目接口 401/40100。

```powershell
& '<开发者工具安装目录>\cli.bat' auto --project "$PWD/apps/miniapp/dist/build/mp-weixin" --port 11927 --auto-port 9420 --trust-project
node scripts/local_business_harness.cjs --allow-local-test-writes
```

当前微信开发者工具须可取得真实 `wx.login` code。既有联调服务还会准备本地合成车辆种子，提供同源 API 与本机取码桥接，只监听回环 4317。

## 实际 H5 页面

使用 gstack `/browse`，每次换查询标识完整重载，避免复用旧内存会话：

```powershell
$env:BROWSE_PARENT_PID='0'
$env:GSTACK_AGENT_WATCHDOG_TICK_MS='3600000'
& '<gstack browse 路径>' goto 'http://127.0.0.1:4317/?service-catalog=唯一标识#/pages/owner/index'
& '<gstack browse 路径>' eval apps/miniapp/test/local-service-catalog-browser-flow.js
```

检查真实会话、精确参考价、分类、分页、停用/删除隐藏、空分类、断网重试、详情/缺省质量标准、停用详情、非法参数、匿名拒绝与退出撤销。业务响应来自真实后端/MySQL；断网用例故意中断一次前端请求，明确记为故障注入。

脚本只输出检查名称/数量和失败阶段，code/令牌仅保存在本次内存。最后撤销会话并恢复 uni 接口；复验需要新微信 code。完成后关闭联调服务，保留隔离 Docker 数据。

## 回归与证据边界

后端 HTTP 测试覆盖角色、参数、价格、故障及无数据库；MySQL 集成测试覆盖分类/分页/计数、停用/删除、金额、会话撤销/过期和禁用用户。CI 使用独立 Testcontainers 库，不对本机共享库执行清库测试。

小程序回归覆盖响应校验、分类竞态、分页失败原页重试、完整列表刷新失败重试第一页、隐藏页和身份变化。微信/H5 构建分别记录。本机 H5 + 真实微信会话通过不等于真机验收、正式价格来源或完整 F06 交付。
