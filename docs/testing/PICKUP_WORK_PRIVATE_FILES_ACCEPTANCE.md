# 接车与施工私有附件访问验收

日期：2026-10-21。执行器：`scripts/run_pickup_work_files_acceptance.py`；旅程测试：`backend/src/test/java/com/autocare/platform/RealPickupWorkFileAccessJourneyTest.java`；脱敏报告：[R9_A4D9_PICKUP_WORK_FILES_2026_10_21.json](reports/R9_A4D9_PICKUP_WORK_FILES_2026_10_21.json)。

## 实际结果

- 本地 PASS，282.656 秒；39 个业务请求、4 个接口各覆盖 normal/boundary/error/unauthorized 四类，0 失败。
- 19 个真实上传经 ClamAV 判为 CLEAN；14 条接车附件关联（两单各 7 张）、5 条施工证据关联（PROTECTION/PROCESS/FAULT/FINISH/SIGNATURE 各 1）。
- 签名下载逐字节等于上传字节、`Content-Type=image/png`、`X-Amz-Expires=5`、`expires_at` 在 10 秒内；去签名、篡改 `X-Amz-Signature`、等待 6.1 秒后重放均返回 403。
- 自有 MySQL/MinIO/ClamAV/初始化容器与网络全部实际移除；源码哈希执行前后一致。
- 覆盖核对：352 项契约中 238 项具备所选历史四类证据，缺口 114 项；全 API 验收仍为 false。

## 用例矩阵

| 接口 | normal | boundary | error | unauthorized |
| --- | --- | --- | --- | --- |
| `GET /api/check/pickup/{order}/files/{file}/access` | 车主本单、本店商家、车主第二单 | 跨订单文件 404、施工文件 404 | query 400、文件 id 0 → 400 | 匿名 401、技师 403、他人车主 404、他店 404 |
| `GET /api/merchant/orders/{id}/work/files/{file}/access` | 防护照、过程照 | 接车图片 404、订单不存在 404 | query 400、订单 0 → 400 | 匿名 401、车主 403、他店 404 |
| `GET /api/tech/orders/{id}/work/files/{file}/access` | 防护照、完工照 | 接车图片 404、订单不存在 404 | query 400、订单 0 → 400 | 匿名 401、车主 403、同店未派工技师 404 |
| `GET /api/order/{id}/work/files/{file}/access` | 防护照、过程照、签字 PNG | 他单文件 404、订单不存在 404 | query 400、文件 0 → 400 | 匿名 401、商家 403、他人车主 404 |

## 复现

```bash
# 需要 Docker（镜像 autocare-upload-minio-ci / autocare-upload-init-ci 由脚本自动构建）
python scripts/run_pickup_work_files_acceptance.py --docker

# 只校验已归档证据与源码哈希是否仍一致（CI 使用）
python scripts/run_pickup_work_files_acceptance.py --check-only

# 执行器自身的证据校验逻辑
python -m unittest discover -s scripts -p test_pickup_work_files_acceptance.py -v
```

`--check-only` 要求 `backend/target/pickup-work-files-evidence.json` 与源码同源；证据中的方法/路径/角色/状态、9 项检查与 5 个清理角色任一不符即失败。

## 本次修复

接车附件访问原本忽略未知/重复 query（同一路由对不同调用方有两套语义），现与其余三个附件接口一致返回 `40001`。除此之外生产逻辑未改。

## 边界

- 身份、订单、支付、派工为 SQL 合成夹具；接车提交、派工接单、防护、报工、签字与全部下载均为真实 HTTP。
- 扫描引擎为真实 ClamAV，签名库为合成；生产鲜度与限流策略未改。
- 签名租期取隔离配置 5 秒，只验证一次自然过期；未做并发压测、真机、正式微信身份、真实资金与外部云存储验收。
- 本文件不主张全量接口验收通过；该结论见 [API_COVERAGE.md](API_COVERAGE.md)。
