# B1a 微信云开发开通与部署准备

2026-10-26。用户选择微信云开发、短信后续再做，并确认云环境尚未开通。承接A4d46 / PR239最终head2591df5，未自动合并前置PR。范围是微信小程序及现有共享后端的云接入准备，没有开通账号、购买资源或执行云部署。

## 本阶段交付

[开通与部署计划](../superpowers/plans/2026-10-26-b1a-cloud-deployment.md)明确采用容器承载既有Java后端，保留外部MySQL事务/迁移，复用现有callContainer和云登录入口。创建CloudBase环境不自动证明微信网关身份可用，必须核对选定产品的调用兼容性及公网隔离；私有文件直传/扫描另做适配，不把云存储开通当作现有上传流程已迁移。

扩展既有身份检查器的`--cloud-deployment`模式，比较显式后端env、小程序env、固定schema部署声明及真实源码manifest AppID。仅输出固定检查项/状态，拒绝缺项、环境/服务/AppID不匹配、本地/Compose数据库地址、公开/未知网关声明，读取失败不回显路径或异常。无网络与写入行为，不继承其他进程凭据补齐显式文件。

配置齐备时也始终cloudDeployed=false、formalIdentityAccepted=false，并保留实际云服务/数据库/身份/存储/手机待验。云登录可不带AppSecret，但当前手机号客户端仍需要服务端AppSecret与微信手机号权限，分别报告两者。商家/受限运营短信标为deferred-no-bypass，不新增固定验证码、合成登录或生产开关绕过。

## 实际验证

- 新10项云预检回归及原9项身份检查回归通过；全量小程序1088/1088，无失败/跳过。
- 普通微信构建及原生样式、无测试身份桥门禁通过。
- 使用明确离线占位EnvId/服务名构建独立云通道微信包，实际编译配置cloudRunEnabled=true、逻辑原点正确、AppID与源码一致；278个JS文件扫描无测试身份桥，原生共享样式通过。没有发送真实云请求。
- 模板云预检实际退出2，configurationReady/cloudDeployed/formalIdentityAccepted均false，符合环境尚未开通的事实。
- 8项交付索引回归、源绑定--check及契约/覆盖/结论检查通过；353契约/339受保护/317历史四类/36结论保持，全API仍false。

脱敏结果见[报告](../testing/reports/B1A_CLOUD_DEPLOYMENT_READINESS_2026_10_26.json)。未改变业务接口、数据库结构、生产角色权限或现有短信拒绝行为；新增声明模板为空值，不包含真实EnvId/密钥/身份/手机号。

## 下一步与依赖

先由小程序管理账号按官方步骤开通隔离测试环境并核对资源/预算；之后提供EnvId、地域及已建服务名即可定位环境，服务端密钥只留在私有配置。选定产品确认支持既有访问链路、MySQL和最小权限后进入B1b真实云端车主/技师身份及B1c手机号；商家/运营短信正式成功仍暂缓。正式支付退款、授权数据/AI、500并发延迟、iOS/Android手机与发布验收均未因此完成。

官方资料经gstack /browse于2026-10-26读取：[创建环境](https://docs.cloudbase.net/quick-start/create-env)、[云托管概述](https://docs.cloudbase.net/run/introduction)。本地检查不证明账号权限、平台当前套餐、地域可用性或费用。
