# 契约逐项结论

本文件由 `python scripts/reconcile_contract.py` 生成，请勿手工编辑；结论数据在 [CONTRACT_VERDICTS.json](CONTRACT_VERDICTS.json)。

- 契约操作：**353** 项；具备四类历史证据：**317** 项；有明确结论但**不计证据**：**36** 项。
- 未解释的操作：**0** 项（脚本 `--check` 会在出现未结案条目时失败）。

结论只回答"这一项为什么没有四类证据"，**不等于**该功能已验收、已上线或已可用。真实执行证据见 [API_COVERAGE.md](../testing/API_COVERAGE.md)，全 API 验收仍为 `false`。

## 结论分布

| 结论 | 含义 | 项数 |
| --- | --- | --- |
| `superseded` | 已被具名实现替代 | 15 |
| `internal-only` | 由服务内部事务完成 | 1 |
| `implemented-local` | 本地软件已实现，完整历史HTTP证据待补 | 1 |
| `blocked-external` | 依赖未接入的外部上游 | 11 |
| `not-implemented` | 尚未实现(需先立项) | 4 |
| `no-success-path` | 实现存在但无成功路径 | 1 |
| `dev-only` | 仅本地演示或开发端点 | 3 |

## 明细

### 已被具名实现替代（15 项）

| 操作 | 替代实现 | 说明 |
| --- | --- | --- |
| `GET /api/admin/assessment` | `GET /api/admin/assessment/snapshots` | 早期"考核列表"占位。现行读取是月结快照列表(含 policy_snapshot 与 UNKNOWN 摘要),政策与事实明细各有独立入口。 |
| `GET /api/admin/dashboard` | `GET /api/admin/business/dashboard` | 运营看板已由 R8b3a 的经营看板接口承担,含授权运营口径与空月份处理。 |
| `POST /api/admin/merchant/audit` | `POST /api/admin/merchant-applications/{id}/moderate` | 商家审核已收敛到入驻申请审核:带 revision 乐观锁、固定驳回码、单事务开店。早期"audit"占位不含任何入参定义。 |
| `GET /api/admin/standard-project` | `GET /api/admin/catalog/{kind}` | 标准项目读写并入目录治理(kind=standard-project),新增只写草稿、修订带 revision。 |
| `POST /api/ai/plan` | `POST /api/ai/service-plan` | AI 方案改为基于目录与车辆证据的保守方案接口;该占位无请求/响应定义。 |
| `POST /api/auth/staff-login` | `POST /api/auth/merchant/login` | 店员/技师不再有独立账号密码登录:店主走 merchant/login,技师走工号绑定(technician/bind)。 |
| `POST /api/coupon/reserve` | `POST /api/order/coupon-create` | 券与订单的冻结改为"订单/时段/单券原子冻结创建",在创建订单时一次完成,不再有先 reserve 再下单的两步路径。 |
| `GET /api/home/dashboard` | `GET /api/vehicle/{vehicle}/health-dashboard` | 车主首页数据由健康驾驶舱按车辆提供;早期"首页驾驶舱"无字段定义,且与车辆维度冲突。 |
| `GET /api/home/reminders` | `GET /api/vehicle/reminders` | 强提醒流由车辆提醒接口承担,偏好另有 reminder-preference;单条手写提醒在 /api/archive/{id}/reminder。 |
| `POST /api/merchant/apply` | `POST /api/merchant-applications` | 入驻申请改为独立资源:严格六字段、资质文件快照、一个申请人一行并可重提。 |
| `GET /api/merchant/{id}` | `GET /api/service/merchant/{id}` | 公开商家详情走 service 前缀,并区分距离与附近商家;该占位没有身份与字段口径。 |
| `GET /api/merchant/{id}/reviews/summary` | `POST /api/service/merchant/{id}/review-summary` | 评价摘要改为独立资源并以车主授权快照为前提;另有 ratings 提供星级报价聚合。 |
| `GET /api/notifications` | `GET /api/owner/notifications` | 通知按收件角色拆分(owner/merchant/tech 与 diagnosis 两类),避免单入口混合三类投影与已读状态。 |
| `POST /api/order/verify` | `POST /api/merchant/orders/{id}/redeem` | 核销改为本店商家对订单的显式核销,带幂等键与免佣事实;早期 verify 只有 order_id/verify_code 两个字段。 |
| `POST /api/refunds/create` | `POST /api/order/{id}/refund` | 退款申请改为订单维度资源,含撤销、商家决策与与支付对账,不再是独立 create。 |

### 由服务内部事务完成（1 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `POST /api/coupon/release` | `未标记` | 订单取消/退款时的券释放由事务内的 CouponRecovery 完成,不存在独立 HTTP 入口;单独暴露会被滥用为手动改券状态。 |

### 本地软件已实现，完整历史HTTP证据待补（1 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `POST /api/vehicle/mileage-recognition` | `mileage-recognition-gateway-implemented-requires-model` | 默认关闭的里程识别网关已实现；真实OCR模型、小程序接线及完整四类真实HTTP执行证据待补。 |

### 依赖未接入的外部上游（11 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `POST /api/ai/chat` | `ai-chat-implemented-requires-provider` | 实现存在(AiChatController,含车辆档案上下文与积分钩子),但必须配置 DeepSeek 供应商才能返回 200;本地不可验收,也不允许用假响应替代。 |
| `POST /api/auth/cloud-login` | `未标记` | CloudRunAuthController已有条件启用的cloud-login控制器和测试；需可信微信云托管网关及关闭公网直连的正式环境验证，不能将现有实现误写为仅permitAll占位。 |
| `POST /api/auth/merchant/code` | `core-implemented` | 商家登录验证码需要生产短信适配器;本地只能用已登录会话夹具,不计真实短信。 |
| `POST /api/auth/merchant/login` | `core-implemented` | 依赖 merchant/code 下发的验证码,因此同受生产短信适配器阻塞。 |
| `POST /api/auth/operator/code` | `operator-auth-implemented` | 运营验证码同需生产短信适配器。 |
| `POST /api/auth/operator/login` | `operator-auth-implemented` | 依赖 operator/code 下发的验证码,同受生产短信适配器阻塞。 |
| `POST /api/auth/phone/bind` | `core-implemented` | 手机号绑定需要真实短信验证码;本地用已登录会话夹具验证绑定后的投影。 |
| `POST /api/auth/sms-code` | `未标记` | 早期短信验证码占位,语义已被 merchant/code 与 operator/code 覆盖,且同样需要生产短信适配器。 |
| `POST /api/auth/technician/bind` | `core-implemented` | 技师绑定需要真实微信上游下发绑定凭证。 |
| `POST /api/auth/wx-login` | `core-implemented` | 微信登录需要真实 code2session 上游;本地用自签会话夹具,不计真实微信登录。 |
| `POST /api/knowledge/answer` | `r8-remaining-implemented` | 实现存在(LicensedKnowledge.answer),未配置 AI 即 503,配置后需真实上游生成并复核来源未变更。 |

### 尚未实现(需先立项)（4 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `POST /api/payments/callback/{channel}` | `未标记` | 真实渠道回调未接入;当前只有 LOCAL_TEST 渠道回调用于隔离验收。接入需渠道证书、验签密钥与生产商户号。 |
| `POST /api/refunds/callback/{channel}` | `未标记` | 同上:退款回调只有 LOCAL_TEST,真实渠道回调未接入。 |
| `GET /api/vehicle/ocr-license` | `未标记` | 行驶证 OCR 未实现,需要外部 OCR 供应商与合规存储方案;当前车辆信息由车主手填。 |
| `GET /api/vehicle/vin-decode` | `未标记` | VIN 解码未实现,需要外部 VIN 库供应商;车型由品牌/车系/配置枚举录入。 |

### 实现存在但无成功路径（1 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `POST /api/merchant/orders/{id}/actions` | `order-fulfillment-state-machine-implemented` | 四个动作分别被拦截为 43001/43004/43005/43006,未知动作 40001,没有 200 路径;已在 docs/api/MERCHANT_ORDERS.md 逐动作登记正确入口。 |

### 仅本地演示或开发端点（3 项）

| 操作 | 当前实现标记 | 说明 |
| --- | --- | --- |
| `GET /api/demo/vehicles/{id}` | `未标记` | 本地 profile 的车辆归属校验样例,用于演示与联调,不是生产接口。 |
| `POST /api/demo/vehicles/{id}/mileage` | `local-example` | 本地 profile 的里程写入样例(local-example),生产里程由接车检查写入。 |
| `POST /api/dev/token` | `未标记` | 仅本地 profile 的演示令牌入口(SecurityConfig permitAll),不构成生产契约。 |

## 复核

```bash
# 重新生成并校验(CI 使用 --check)
python scripts/reconcile_contract.py
python scripts/reconcile_contract.py --check
python -m unittest discover -s scripts -p test_reconcile_contract.py -v
```
