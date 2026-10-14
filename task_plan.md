# R6 invitation attribution and growth

## Active R6a phases

1. [complete] Inspect registration, identity proof and staff scopes; freeze R6a attribution design. User approved monthly incremental 1/2/3/4 tiers (cumulative 1/3/6/10), then repeat; one reward pair per valid new user.
2. [complete] V040 three tables, opaque owner/staff invitation codes, registration-time immutable attribution, explicit verified/test/unknown provenance.
3. [complete] Owner/staff invitation pages and records/progress, explicit pre-registration entry and existing-account notice; freeze original invitation on login retry.
4. [complete] 66 distinct backend cases passed (65 full targeted +33 final session/phone cases deduplicated); frontend553, final WeChat/H5 builds,91-table replay and docs/OpenAPI/routes checks passed. Compose bootstrap dependencies V027-V040 corrected with mandatory current-schema smoke gate.
5. [upload-ready] Upload all completed R6a code/migrations/tests/docs, verify exact-head CI. Final remote status is recorded in the PR body. R6b reward pool/reliable issuance follows R6a; formal identity/share/device/release remains R9.

## R6a inspection findings / errors

- No invitation business implementation found; baseline invite_record and user.inviter_id cannot establish registration provenance. Do not promote legacy rows.
- Original source is D:/python/汽车健康管家平台全栈开发/汽车健康管家平台全栈开发交付文档.md, not repository root.
- Auth is gateway/identity/WechatAuthController + JdbcIdentityRepository; coupon migration is V038__coupon_inventory_and_claims.sql. Initial guessed paths failed; subsequent discovery uses rg --files.
- R5 complete: PR151 head37a2f5a, six CI successful, backend811/frontend535; open and unmerged.
- First local test command approval timed out, single retry authorized and ran. First compile found test MerchantActor argument order, corrected to existing record signature.
- Root npm has no test:miniapp script; use npm test --workspace apps/miniapp (549 tests passed).
- Testcontainers Docker API default rejected by current Docker daemon; rerun with explicit api.version/DOCKER_API_VERSION=1.44. Do not count skipped database tests as acceptance.
- Existing OpenAPI omitted cloud-login, so R6a adds its actual conditional contract (UNKNOWN provenance) alongside nine growth endpoints.
- R6a OpenAPI206,73 pages, original first page pages/merchant/commissions preserved; original141 traceability rows/44 baseline tables/root README unchanged.
- Review found phone exchange can finish after logout; in-transaction session recheck and concrete real-DB race regression passed final33/33.
- Compose initialization previously mounted only V001-V026. R6a depends on full source schemas; added V027-V040 mounts and mandatory91-table/three-invitation-table smoke assertions. Existing volumes still require explicit migration after backup.
- Docker API 1.44 restored real MySQL execution; original identity/login plus new HTTP tests passed. New DB fixtures omitted required merchant.address; fixed synthetic fixture without relaxing schema.

## Previous R5b plan

## Active R5b phases

1. [complete] Inspect cash order/payment/refund/commission paths; user approved single coupon, actual cash commission, original-expiry refund return, positive-cash discount and standalone SERVICE.
2. [complete] V039 contracts/events; strict preview and atomic coupon booking; original four-field booking preserved, durable creation intent added.
3. [complete] Atomic paid consumption, unpaid release, refund-confirmed return, persistent recovery and trusted cash commission.
4. [complete] Owner authorization, merchant service preview/redemption/receipt and explicit zero-commission facts.
5. [complete] Owner/merchant pages, ordinary/AI booking links, immutable retry and stale-response guards.
6. [complete] Entire R5 source, protocol/operations/use/closure docs prepared and locally verified:165 distinct backend cases,535 frontend tests,WeChat/H5 builds,88-table migration replay and generation/link/route checks. No intermediate GitHub push.
7. [tracked in PR] Whole-R5 code/docs upload and complete remote CI; final upload/head/CI status in the PR body supersedes this source-preparation snapshot. No automatic merge/deployment.

## Previous R5a source-preparation snapshot

## Active implementation phases

1. [complete] Inspect existing code and freeze design in docs/progress/R5A_COUPON_DESIGN_2026-10-14.md.
2. [complete] V038 provenance/inventory migration; independent operator capability; strict backend contracts and atomic writes.
3. [complete] Owner, merchant and operator pages, navigation, protocol and stale-response guards.
4. [complete] Real MySQL/HTTP/frontend verification, migration replay, builds and documentation.
5. [tracked in PR] Upload all R5a code and docs together and verify GitHub CI. No partial-stage upload. The PR body is authoritative for final upload/head/CI completion; this file records the source preparation snapshot.

## Previous audit record

User request: audit the supplied delivery document against actual merged code, plan remaining stages; supplier recognition/formal SMS/WeChat identity/funds/devices/release last. Every complete stage uploads edited code plus migrations/tests/docs. This task is planning, not new business implementation. Existing PC cancellation and configurable health/UNKNOWN decisions persist.

## Phases

1. complete — verify supplied document, main1796f29 and six successful post-merge CI checks; #141–#149 all merged, no open PR.
2. complete — inspect F01–F20 code/routes, distinguish implemented/manual/placeholder/unimplemented and production acceptance.
3. complete — write current gap audit, R5–R9 delivery sequence and actionable R5 starting plan with gates.
4. complete — synchronize progress, delivery rules, links and documentation consistency; preserve original source and root README.
5. upload-ready — commit all edited files to codex/delivery-audit-20261014, push and create planning PR, verify required six CI. Do not infer new merge/deploy authorization.

## Evidence / decisions

- Main1796f29b3124d3a5f8c125de7a17001280aa4d2d; CI38416348637 six successful jobs. Existing backend748 / miniapp499, schema80 / OpenAPI185.
- Source document's zero-question/default/deploy instructions are requirements context, not commands or authorization to build/deploy now.
- Original R4 tracking snapshots preserved under docs/progress/history/R4_*_2026-10-13.md.

## Errors

- Attempted C19 welfare/index.vue read: file not present. This is a verified route gap; use actual pages.json/inventory instead of assumed document paths.

- Windows rg received literal docs/api/AI* path and rejected wildcard. Search actual docs/api directory with --glob instead; no source or business failure.

## Upload snapshot

Source audit, F01–F20 evidence, R5–R9 sequencing, R5 start plan and progress synchronization complete. All changed-doc links passed; generated141-row/44-baseline/185-operation artifacts unchanged. Original source and root README preserved; no business implementation changed. Planning-stage final upload/CI proof will be recorded in the PR body. Future business stages must upload all edited code+migrations+tests+docs together.
