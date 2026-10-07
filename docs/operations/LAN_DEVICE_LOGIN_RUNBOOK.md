# 真机真实微信登录（测试号 + 局域网）

更新日期：2026-10-07。适用对象：使用**微信小程序测试号**、希望在**真实手机**上完成真实 `wx.login` 登录，且暂时不想投入 HTTPS 证书、ICP 备案域名或微信云托管。

## 结论

这条路径成立，依据是微信官方[申请测试号](https://developers.weixin.qq.com/miniprogram/dev/devtools/sandbox)：

- 测试号是官方分配的**真实**小程序账号，具备 AppID 与 AppSecret，可在开发者工具创建项目，并**支持真机预览体验**。
- 在真机上如需**跳过网络请求域名的校验**，官方做法是：预览后在手机右上角「…」选择**打开调试**。
- 在开发者工具内则可勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」。

也就是说，真机真实微信登录只剩一个前提：**手机能访问到后端地址**。测试号阶段不需要公网、域名或证书。

需要保留的边界：测试号不能上传发布（上传按钮置灰）、不能使用支付等敏感接口；它只解决"开发期真机真实登录"，**不能替代正式上线验收**。

## 原先的阻塞点

本机后端容器一直**只发布在回环地址**（`127.0.0.1:18080`）。手机里的 `127.0.0.1` 指向手机自己，所以开发者工具能登录、手机一定连不上。这与[网络环境说明](MINIAPP_NETWORK_ENVIRONMENTS.md)中记录的"当前后端回环监听尚不能供手机访问"一致。

## 本次已完成的改动

| 项 | 变化 |
| --- | --- |
| 本机后端容器 | `vehicle-auth-local-backend` 由 `127.0.0.1:18080→8080` 改为 `0.0.0.0:18080→8080`（同一隔离测试库与配置，未改数据卷）；镜像后随 AI 管家切换为 `vehicle-auth/backend:ai-chat` |
| 小程序构建配置 | `apps/miniapp/.env.local` 的 `VITE_API_BASE_URL` 指向本机局域网地址，并已重新构建 `mp-weixin` 产物 |
| 回滚容器 | 旧容器改名为 `vehicle-auth-local-backend-before-lan` 保留，未删除；切换 AI 镜像时另保留 `vehicle-auth-local-backend-before-ai-chat` |
| 构建助手 | 新增 `scripts/miniapp_lan_helper.cjs`，负责探测地址、写配置、构建、还原与可达性检查 |

实测结果：`http://127.0.0.1:18080/actuator/health` 与 `http://<局域网地址>:18080/actuator/health` 均返回 `{"status":"UP"}`。本机可达不等于手机可达，手机侧仍需按下文验收。

2026-10-07 本轮电脑侧准备结果：容器 `vehicle-auth-local-backend`（镜像 `vehicle-auth/backend:ai-chat`）已发布在
`0.0.0.0:18080`；局域网地址 `http://10.66.1.251:18080` 健康检查返回 200/`UP`；`mp-weixin` 产物已按该地址重建；
AI 链路在运行容器上重跑 `scripts/verify_ai_chat_local.py` **19 项全部通过**。
**手机侧 L0–L8 仍未执行，图片链路（MinIO 回环自签）另属独立问题。**

> **镜像标签必须带 AI 管家**：真机验收要顺带验 AI 对话，重建容器时镜像应为 `vehicle-auth/backend:ai-chat`。
> 若误用 `vehicle-auth/backend:merchant-orders` 等旧标签，`/api/ai/chat` 会直接 404（旧镜像里没有这段代码），
> 而登录仍能成功，极易被误判成"AI 有问题"。用 `docker inspect <容器> --format '{{.Config.Image}}'` 核对。

## 一键操作

```bash
node scripts/miniapp_lan_helper.cjs                 # 探测推荐地址（不改动任何文件）
node scripts/miniapp_lan_helper.cjs --check         # 额外验证 http://<地址>:18080/actuator/health
node scripts/miniapp_lan_helper.cjs --apply --build # 写入 .env.local、重新构建、并验证后端可达性
node scripts/miniapp_lan_helper.cjs --restore       # 还原为 http://127.0.0.1:18080 并重新构建
```

`--apply` 结束时会用「本机能否经局域网地址访问后端」给出后端是否就绪的结论——这比读 Docker 端口映射更可靠
（Windows 上 Node 直接 spawn `docker` 会 `EBUSY`，读不到端口信息）。它只报告状态，不会改动容器。

脚本只改写 `.env.local` 里的 `VITE_API_BASE_URL`，其余行（例如云托管变量）原样保留；可用 `--ip`、`--port` 覆盖。多网卡时它优先选择物理网卡（WLAN/以太网），把 WSL、Hyper-V、VirtualBox 等虚拟网卡排在后面。

离线自检（不联网、不改文件）：

```bash
node --test scripts/test_miniapp_lan_helper.cjs
```

## 手工步骤

1. **确认后端发布在局域网**：`docker ps` 中该容器的端口应为 `0.0.0.0:18080->8080/tcp`。若仍是 `127.0.0.1:18080->8080/tcp`，按下面「重建容器」执行。
2. **拿到手机可达的地址**：运行脚本的探测模式，或手工查看 `ipconfig` 中 WLAN/以太网 的 IPv4 地址。
3. **写入并构建**：`node scripts/miniapp_lan_helper.cjs --apply --build`。
4. **开发者工具**：导入 `apps/miniapp/dist/build/mp-weixin`，确认「详情 → 本地设置」勾选了不校验合法域名。
5. **真机预览**：点「预览」，用测试号管理员微信扫码；进入后点右上角「…」→「打开调试」。
6. **验证登录**：从车主入口进入，应完成真实 `wx.login` 换码并进入首页。
7. **收尾**：测试结束后执行 `--restore`，并把容器改回回环发布（见下）。

### 重建容器（放在局域网 / 还原回环）

```bash
# 放到局域网（手机可达）
docker stop vehicle-auth-local-backend
docker rename vehicle-auth-local-backend vehicle-auth-local-backend-before-lan
docker run -d --name vehicle-auth-local-backend \
  --env-file .env.auth-backend.local \
  --env-file .env.upload-app.local \
  --env-file .env.payment-test.local \
  --network vehicle-auth-local_default \
  -e MYSQL_HOST=vehicle-auth-local-mysql-1 \
  -p 0.0.0.0:18080:8080 \
  vehicle-auth/backend:ai-chat

# 还原为只监听回环
docker rm -f vehicle-auth-local-backend
docker rename vehicle-auth-local-backend-before-lan vehicle-auth-local-backend
docker start vehicle-auth-local-backend
```

> 上面两段会占用 `-before-lan` 这个名字，重复执行会因重名失败。当前机器上该名字**已被占用**
> （还有 `-before-ai-chat`），所以通常**不需要**再跑这两段：`docker ps` 若已显示
> `0.0.0.0:18080->8080/tcp` 且镜像为 `:ai-chat`，直接做手机侧验收即可。
> 确需重做时，先把旧容器改名成别的名字（如 `-before-lan-2`）。

## 怎么让手机和电脑"在同一张网"

| 方案 | 说明 | 风险/限制 |
| --- | --- | --- |
| 同一 WiFi（当前环境为校园网） | 直接使用 WLAN 地址 | **校园网/公共 WiFi 常开启客户端隔离（AP isolation）**，设备之间互相不可达，此时手机一定连不上；且后端会暴露在整个校园网段 |
| 电脑「移动热点」（推荐） | Windows 设置 → 网络和 Internet → 移动热点，手机连该热点；热点地址通常是 `192.168.137.1`，脚本会自动识别 | 只对连上该热点的设备可见，比校园网安全得多 |
| 手机 USB 共享网络 | 部分机型可让电脑反向共享手机网络 | 地址与路由随机型变化，需要手工确认 |
| 微信云托管 | 免配通讯域名，走 `wx.callContainer` | 需要云托管环境与有状态服务外迁，见[云托管清单](CLOUDRUN_LOGIN_RUNBOOK.md) |

网络类别会影响 Windows 防火墙生效的规则集。当前机器上 Docker Desktop 已为「公用」配置登记了入站 TCP 放行规则，因此一般无需额外加规则。若手机仍连不上，可在**管理员**终端执行：

```bash
netsh advfirewall firewall add rule name="autocare-lan-18080" dir=in action=allow protocol=TCP localport=18080
# 不再需要时删除
netsh advfirewall firewall delete rule name="autocare-lan-18080"
```

## 验收清单

| 编号 | 操作 | 期望 | 记录 |
| --- | --- | --- | --- |
| L0 | 手 机浏览器打开 `http://<局域网地址>:18080/actuator/health` | 返回 `UP`；若失败，先解决网络与防火墙，不要继续 | 待执行 |
| L1 | 开发者工具导入当前产物 | 页面可打开，后端地址为局域网地址，不再是 `127.0.0.1` | 待执行 |
| L2 | 点「预览」，测试号管理员扫码，手机「…」→「打开调试」 | 真机可打开小程序，调试面板可用 | 待执行 |
| L3 | 车主入口点击微信登录 | 真实 `wx.login` 换码成功，进入首页，后端签发会话 | 待执行 |
| L4 | 完全退出小程序后重新进入 | 登录态从本地恢复，无需再次点击 | 待执行 |
| L5 | 会话过期后继续操作 | `/api/auth/refresh` 轮换成功，业务请求自动续期 | 待执行 |
| L6 | 执行退出 | 后端撤销会话，本地登录态清除 | 待执行 |
| L7 | 技师入口输入员工码 | 绑定成功并可重复登录 | 待执行 |
| L8 | 记录设备型号、系统版本、基础库版本、脱敏证据编号 | 失败时记录阶段、HTTP/业务码与追踪 ID，不记录令牌与请求体 | 待执行 |

## 失败排查

| 现象 | 先查什么 |
| --- | --- |
| 手机打不开 `/actuator/health` | 是否同一网络（校园网可能客户端隔离，换移动热点）；容器端口是否 `0.0.0.0`；防火墙入站规则 |
| 提示"登录服务尚未配置" | 产物是否为本轮重新构建的、`.env.local` 是否写对；开发者工具是否导入了正确的 `dist/build/mp-weixin` |
| 请求被拦截、提示域名不合法 | 手机是否已「打开调试」；开发者工具是否勾选了不校验合法域名 |
| 首页图片不显示 | 图片走 `MINIO_PUBLIC_ENDPOINT`（当前为回环 HTTPS 自签），手机不可达；这属于图片链路独立问题，不影响登录判定 |
| 换网络后突然连不上 | DHCP 地址变了；重新运行脚本探测并 `--apply --build`，或执行 `--restore` 回到回环 |

## 安全边界

- 改为 `0.0.0.0` 发布后，**同一网段内的其它设备可以访问本机后端**。当前后端连接的是隔离测试库与合成数据；`/api/dev/token` 因 `SPRING_PROFILES_ACTIVE=local-payment-test` 未激活；本地测试支付回调需 HMAC 密钥。即便如此，也只应在受控测试网络中使用，用完立即回滚。
- **不要**为了绕过真机网络问题去关闭证书校验或跳过 HTTPS 验证；测试号 + 打开调试只跳过域名白名单，不降低其它安全要求。
- 局域网调试**不能**计作正式真机验收。常规真机与发布仍需可访问的 HTTPS、有效证书与备案域名，或改用微信云托管通道。
