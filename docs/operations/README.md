# 部署与运维文档

按交付文档 §10，记录环境变量、Compose/Nginx、监控告警、发布与回滚步骤。仓库已有 Compose 与 Nginx 配置并通过 CI 冒烟；这只验证临时容器能够启动，真实 HTTPS 环境、已有数据库 V002/V003 迁移、对象存储、告警以及发布/回滚演练尚未验收。当前外部条件见[依赖清单](../progress/S0_DEPENDENCIES.md)，M0 验收顺序见[下一步规划](../progress/NEXT_STEPS.md)。

本机 Docker 登录环境和新建 V001–V005 测试卷已实际联调；已有外部库仍须单独核对迁移。开发者工具可用本机 HTTP，正式自建后端须配置备案 HTTPS 通讯域名，详见[小程序开发与上线网络说明](MINIAPP_NETWORK_ENVIRONMENTS.md)。

真实登录环境可按[联调预检与验收清单](AUTH_INTEGRATION_RUNBOOK.md)检查入口和逐角色流程；清单中的未执行项仍须在测试部署中完成。

网络通道与真机登录：

- [小程序开发与上线网络说明](MINIAPP_NETWORK_ENVIRONMENTS.md)：模拟器、手机调试与常规真机的网络边界。
- [测试号真机登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)：用官方测试号 + 电脑局域网地址完成真机真实微信登录，无需域名/证书/云托管（L0–L8）。
- [微信云托管登录验收清单](CLOUDRUN_LOGIN_RUNBOOK.md)：`callContainer` 免配通讯域名路径的部署与验收（C0–C10），含"必须关闭公网访问"这条安全前提。
- [AI 管家接入清单](AI_CHAT_RUNBOOK.md)：DeepSeek 密钥配置、安全边界与 A0–A8 验收。

2026-10-06 已核对本机环境与微信官方网络要求，形成[可信 HTTPS 部署与真机验收方案](../superpowers/specs/2026-10-06-https-device-design.md)。推荐复用现有接口部署自建 HTTPS；服务器、备案域名、证书和设备尚未确认，方案待复核，不代表已开通公网入口。
