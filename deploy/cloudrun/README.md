# 微信云托管部署

本目录提供把 Spring Boot 后端部署到**微信云托管**的最小产物，目的是让小程序在**无需配置通讯域名、无需 ICP 备案域名**的前提下完成真实微信登录。

## 为什么能免域名

小程序/公众号通过 `wx.cloud.callContainer` 走微信私有协议访问云托管服务，官方明确**无需在 mp 后台配置服务器域名**。网关会在容器收到的请求中注入调用者身份头，后端据此识别用户，不必再走 `wx.login` + `code2session`。

代价是云托管的硬限制，先看清再决定是否采用：

| 限制 | 对本项目的影响 |
| --- | --- |
| 不支持部署数据库/Redis 等有状态服务 | MySQL/Redis/RabbitMQ 必须使用容器外实例（云数据库或已有服务器） |
| 不支持 Docker Compose | 现有 `deploy/compose` 不能整体上云，只能上这一个服务 |
| 不支持多端口 | 后端只监听一个端口（默认 80） |
| 容器无持久化存储 | MinIO 不能跑在容器里，图片需改用对象存储 |
| `callContainer` 超时 ≤ 15s、请求体 ≤ 100K | 图片不能经容器中转上传，必须对象存储直传 |
| 默认公网域名仅供接口测试 | 生产不能依赖默认公网域名 |

**本次交付只覆盖登录与请求通道**；数据库/对象存储的外迁见 `docs/superpowers/specs/2026-10-07-cloudrun-login-design.md` 第 7 节。

## 部署步骤

1. **准备容器外依赖**：可用 MySQL 实例（先按 `docs/sql/migrations` 顺序应用迁移）、对象存储。
2. 在微信云托管控制台**新建环境**，环境需与小程序同主体，并授权该小程序调用。
3. **新建服务**，代码来源选择本仓库与目标分支；Dockerfile 路径填 `deploy/cloudrun/Dockerfile`，**构建上下文为仓库根目录**，监听端口填 `80`（或与 `SERVER_PORT` 一致）。
4. 在「服务设置 → 环境变量」按 `deploy/cloudrun/env.example` 配置；`WECHAT_APP_SECRET`、`JWT_SECRET`、数据库口令只填在这里。
5. **关闭公网访问**。这是安全前提：公网可直连时任何人都能伪造 `X-WX-OPENID` 冒充任意用户，此时 `/api/auth/cloud-login` 等同无鉴权。
6. 部署完成后确认服务健康检查通过（容器内 `/actuator/health` 返回 `UP`）。
7. 小程序侧按 `apps/miniapp/.env.example` 配置 `VITE_WECHAT_CLOUD_ENV_ID` 与 `VITE_WECHAT_CLOUD_SERVICE`，重新构建 `mp-weixin` 产物并导入开发者工具。
8. 在小程序后台「设置 → 功能设置 → 基础库最低版本设置」设为 **≥ 2.23.0**，否则 `callContainer` 不可用。
9. 后端环境变量 `WECHAT_CLOUD_RUN_ENABLED=true`，使 `/api/auth/cloud-login` 生效。

## 验证

按 `docs/operations/CLOUDRUN_LOGIN_RUNBOOK.md` 逐项执行并记录脱敏证据。

## 回退

- 服务端：把 `WECHAT_CLOUD_RUN_ENABLED` 设为 `false`，`/api/auth/cloud-login` 立即不存在；`/api/auth/wx-login` 不受影响。
- 小程序：清空 `VITE_WECHAT_CLOUD_ENV_ID` 与 `VITE_WECHAT_CLOUD_SERVICE`，重新构建即回到 `uni.request` + `code2session` 通道。
