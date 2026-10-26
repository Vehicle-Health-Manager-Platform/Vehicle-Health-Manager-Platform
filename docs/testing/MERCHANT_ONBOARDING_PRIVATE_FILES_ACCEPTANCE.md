# 商家入驻资质附件授权访问验收

日期：2026-10-22。执行器：`scripts/run_merchant_onboarding_files_acceptance.py`；旅程测试：`backend/src/test/java/com/autocare/platform/RealMerchantOnboardingFileAccessJourneyTest.java`；脱敏报告：[R9_A4D19_MERCHANT_ONBOARDING_FILES_2026_10_22.json](reports/R9_A4D19_MERCHANT_ONBOARDING_FILES_2026_10_22.json)。

## 实际结果

- 本地 PASS，307.395 秒；12 个业务请求、1 个接口覆盖 normal/boundary/error/unauthorized 四类，0 失败。
- 3 次真实 multipart 上传经 ClamAV 判为 CLEAN，全部归属两位申请人本人，没有第三方归属行。
- 2 份入驻申请均为 `PENDING_REVIEW`、`revision=1`；快照精确为 `[1,2]` 与 `[3]`，跨申请引用为 0；`merchant` 仍只有夹具那 1 家，`merchant_id`/`reviewed_at` 全为 NULL。
- 签名下载逐字节等于上传字节、`Content-Type=image/png`、`X-Amz-Expires=5`、`expires_at` 在 10 秒内；去签名、篡改 `X-Amz-Signature`、等待 6.1 秒后重放均返回 403。
- 自有 MySQL/ClamAV/MinIO/初始化容器与网络全部实际移除；源码哈希执行前后一致。
- 覆盖核对：352 项契约中 314 项具备所选历史四类证据，缺口 38 项；全 API 验收仍为 false。

## 用例矩阵

| 接口 | normal | boundary | error | unauthorized |
| --- | --- | --- | --- | --- |
| `GET /api/admin/merchant-applications/{id}/files/{file}/access` | 申请 1 的首张与第二张、申请 2 的附件 | 跨申请文件 404、申请不存在 404 | query 400、申请 0 → 400、文件 0 → 400 | 匿名 401、仅 `can_review` 的运营 403、车主 403、商家 403 |

## 复现

```bash
# 需要 Docker（镜像 autocare-upload-minio-ci / autocare-upload-init-ci 由脚本自动构建）
python scripts/run_merchant_onboarding_files_acceptance.py --docker

# 只校验已归档证据与源码哈希是否仍一致（CI 使用）
python scripts/run_merchant_onboarding_files_acceptance.py --check-only

# 执行器自身的证据校验逻辑
python -m unittest discover -s scripts -p test_merchant_onboarding_files_acceptance.py -v
```

`--check-only` 要求 `backend/target/merchant-onboarding-files-evidence.json` 与源码同源；证据中的方法/路径/角色/状态、11 项检查与 5 个清理角色任一不符即失败。

## 回归

同一次 maven 调用内 `MerchantOnboardingHttpTest`(6)、`MerchantOnboardingMySqlTest`(9) 全部通过；本阶段新增单测 11 项。

## 边界

- 身份、门店、运营账号与"门店已开业"为 SQL 合成夹具；上传、入驻申请提交与全部下载均为真实 HTTP。
- 扫描引擎为真实 ClamAV，签名库为合成；生产鲜度与限流策略未改。
- 签名租期取隔离配置 5 秒，只验证一次自然过期；未做并发压测、真机、正式微信身份、审核决策与建店验收。
- 本文件不主张全量接口验收通过；该结论见 [API_COVERAGE.md](API_COVERAGE.md)。
