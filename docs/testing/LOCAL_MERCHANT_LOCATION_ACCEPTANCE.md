# R4b2a 本地门店位置与距离验收

日期：2026-10-13。契约见 [门店位置](../api/MERCHANT_LOCATION.md)。真机定位、正式账号资质与发布保持 R9 独立验收。

## 可复现验证

1. 隔离 MySQL 8.0 应用 V001/V003/V006/V019/V020/V032；旧 merchant.lng/lat 有值时新位置表仍为空，返回距离未知。再次执行 V032 不改已有数据。
2. 店长用 UUID 幂等键写入确认的 GCJ02 坐标；同键重放只产生一次版本和审计。清除后列表/详情距离为空；清除保留版本；已撤销会话重放返回401。店员只读，本店门店停用返回404，伪造其他店归属返回401。
3. 本人车主请求附近列表，验证同点0米、纬度相差1度约111195米、相同距离按报价ID稳定分页、未知最后、区域过滤和价格排序；详情和列表同公式。验证对跖点约20015114米、±180度同点有限不溢出。
4. 拒绝插入审计的触发器使写操作503；位置、幂等和审计都回滚。读取距离不产生位置数据或业务审计。并发两次写仍只有一个门店位置记录、版本2和两次审计。
5. HTTP 边界测试使用替换业务服务，覆盖匿名/错误角色、非法数值、错误坐标系、多余/重复字段、尾随JSON、查询参数、分页边界和单层信封/no-store。HTTP替身结果与真实数据库测试分别记录。
6. 小程序401项测试验证定位拒绝/超时/不支持、隐藏/账号/排序后迟到回调不再调用接口；附近POST的坐标只在正文，白名单拒绝私人字段/不合法距离；位置保存确认、幂等重试和权限失效清除。

后端命令：

```text
mvn -B -Dtest=JdbcMerchantLocationTest,MerchantLocationHttpTest,JdbcMerchantDiscoveryTest,MerchantDiscoveryHttpTest test
```

本地 Maven/JDK17 在 Docker 内运行，挂载仓库和 Docker socket。Docker 29 使用 JAVA_TOOL_OPTIONS=-Dapi.version=1.52、TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal；本机 Ryuk 镜像无法创建，关闭 Ryuk 后由 JUnit 生命周期关闭每个真实 MySQL 容器，未跳过数据库测试。CI 使用标准 Linux Testcontainers 配置。

```powershell
npm test --workspace @autocare/miniapp
npm run build:miniapp
npm run build:h5 --workspace @autocare/miniapp
python scripts/generate_traceability.py
python scripts/build_init_sql.py
python scripts/generate_openapi.py
```

完整 schema 重放：`bash scripts/verify_mysql_schema.sh`，隔离库从44表到V032后72表，重放及合成种子数据需通过。微信产物含门店位置页面、scope.userLocation 和 requiredPrivateInfos=getLocation；构建产物不上传 Git。

## 验收边界

自动化定位使用替身，本阶段没有实际点击微信授权弹窗，也没有 iOS/Android 真机定位精度和后台 API 开通证据。店长确认的坐标不等于平台现场核实。最终计数和CI状态以 [阶段执行记录](../progress/R4B2A_LOCATION_DISTANCE_EXECUTION.md)及阶段 PR 为准；生产接入和真机待验项保持公开记录。
