# 下一步规划

> 更新日期：2026-10-03｜目标：完成 S0 并取得 M0“骨架就绪”的可复现证据。每项按独立 PR 验证，不把 CI 构建等同于真机或业务验收。

## 近期执行顺序

S0-7.1f-3 已完成现有身份接口的页面状态处理：16 项离线测试、H5 22 项交互断言、小程序/H5 构建和微信模拟器 9 条路由验证通过。业务读接口尚未接入，列表加载与空状态随对应接口逐项完成，不以待接入页充当空列表验收。

S0 幂等与成功审计核心已在 [PR #8](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/8) 完成：后端 45 项测试无失败、无跳过，本步 12 项 MySQL 测试实际执行，六项 CI 通过。首次接入为 local 里程示例，正式业务写请求仍需逐项接入；完整安全审计尚未完成。

私有上传核心已在 [PR #9](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/9) 实现并验证，后端增至 62 项测试无失败、无跳过。本步仅为内部服务与可离线验证的存储/扫描接口；真实 MinIO/ClamAV、HTTP 上传及签名访问仍未完成，见[接入说明](../api/PRIVATE_UPLOAD_CORE.md)。

| 顺序 | 工作项 | 完成判定 | 依赖 |
| --- | --- | --- | --- |
| 1 | **S0-7.1f-4：真实身份联调** | 使用轮换后的私有 AppSecret 和已迁移数据库验证车主首次/重复登录、技师绑定、手机号授权、刷新及退出；记录 HTTP 结果与脱敏证据。商家短信通道就绪后验证验证码送达、一次性消费和失效。 | 私有凭据、V002/V003、HTTPS 后端、合法请求域名、具备手机号能力的测试账号；商家还需短信服务商。 |
| 2 | **S0 私有上传服务接入，然后健康监控** | 下一步设计并实现真实 MinIO/ClamAV 适配器与可丢弃 CI 服务验证：证明未签名读取被拒绝、病毒/扫描故障不放行、权限复核后短时签名访问。随后设计 HTTP 上传的幂等、并发/限流、总请求体限制与对象清理/状态核对，再接入业务附件；之后补健康监控。正式业务写请求逐项复用幂等与成功审计服务，完整安全审计随对应功能接入。 | 用户确认真实存储与扫描尚未配置；可先用可丢弃 CI 服务验证适配器，私有环境与外部签名域名到位后再验收真实部署。健康监控需要运行环境。 |
| 3 | **M0 验收** | 记录 iOS/Android 真机、车主 H5、数据库迁移、后端及协作团队运营 PC 的验收结果。业务列表空状态随读接口验证，未满足项保持未通过。 | 前两项和[S0 依赖清单](S0_DEPENDENCIES.md)。 |

## 仓库合并顺序

截至 2026-10-03：[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 已合入 [PR #2](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/2) 的分支，**尚未进入 `main`**。先复核并合入 PR #2；随后将以旧 PR #3 分支为基线的 [PR #4](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/4) 调整到已合入的基线并合入；最后处理以 PR #4 为基线的 [PR #5](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/5)。每次变更基线后重新确认差异和 CI。此处是合并计划，不表示 PR 已合并。

后续按 PR #6（文档）→ PR #7（三角色页面状态）→ PR #8（幂等与审计）→ PR #9（私有上传核心）的顺序处理堆叠基线；PR #8 当前以 PR #7 分支为基线，PR #9 以 PR #8 为基线。各项合并前重新确认差异和 CI。

## 外部条件与范围

- **用户/环境提供**：确认轮换后的 AppSecret 只在私有环境中配置；提供已应用 V002/V003 的测试数据库、可从小程序访问的 HTTPS 后端和测试设备。不要把密钥、手机号或验证码写进文档、聊天、Git 或构建产物。
- **待决策**：商家短信服务商、签名与模板尚未确定；当前 [商家身份核心](../api/MERCHANT_AUTH.md)没有生产发送器，请求验证码会返回 503。正式小程序主体、各角色 AppID 与支付资质仍待确认。
- **范围边界**：`apps/miniapp` 当前是可编译的导航和身份骨架，不能计入车辆、交易、AI 或完整商家/技师业务交付；旧网页目录仍是过渡原型，运营 PC 后台由协作团队负责。需求与验收仍以[原始交付文档](../../汽车健康管家平台全栈开发交付文档.md)和[Spec](../SPEC.md)为准。
