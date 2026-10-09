# 运营认证与审核账号

日期2026-10-09，V019先迁移。本阶段运营入口在微信小程序，账号密码+短信二次校验、独立短会话；不是车主/商家身份。默认无账号与审核授权，无HTTP注册/提权。

## 离线管理

在可信服务器受控环境提供数据库与JWT配置，使用 `operator-admin` profile且 `spring.main.web-application-type=none` 启动后端JAR。命令只输出账号ID，凭据不得放在命令参数、仓库、聊天或日志。

- 新建：受控环境 `OPERATOR_ACCOUNT`、`OPERATOR_PASSWORD`（12字符以上且UTF-8≤72字节）、`OPERATOR_PHONE`、`OPERATOR_CAN_REVIEW=true/false`；启动参数 `--spring.profiles.active=operator-admin --spring.main.web-application-type=none --operator.action=create`。
- 禁用：相同启动profile与无HTTP参数，`--operator.action=disable --operator.id=...`。
- 审核权限变更：受控环境显式 `OPERATOR_CAN_REVIEW=true/false`，参数 `--operator.action=review-permission --operator.id=...`。

禁止对生产自动创建账号；由有授权的运维人员运行。管理变更与审计原子提交，禁用/权限变更撤销全部该运营会话。不保存明文密码，不输出手机号、验证码或token。

## API与短信

POST `/api/auth/operator/code`：严格 `{account,password}`。POST `/api/auth/operator/login`：另加6位字符串sms_code。POST `/api/auth/operator/logout`：运营Bearer，严格 `{}`。未知/重复/尾随字段与query均拒绝，全部no-store。账号ASCII 3–64字符，大小写统一，密码不截断。

验证码bcrypt保存、5分钟有效、一次消费、用途绑定运营ID。发码60秒1次/每天5次，登录IP30次/15分钟、账号10次/15分钟。登录事务复核账号、消费验证码、创建15分钟JWT/数据库会话及最小审计；不发refresh，过期需再次两步认证。can_review只供界面提示，服务端每次复核数据库权限。退出即时撤销。

需配置正式 `OperatorSmsSender` 供应商适配器。当前未配置会返回503，不返回验证码，不使用固定代码或绕过；供应商失败回滚已发验证码状态。隔离测试可用合成供应商/验证码夹具，不能代替正式短信验收。车主/商家/技师接口不能使用运营身份。
