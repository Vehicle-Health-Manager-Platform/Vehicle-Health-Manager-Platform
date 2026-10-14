# R4 completion plan

User authorization: finish all remaining R4 locally, then upload ALL updated code and documents together to GitHub. No intermediate push/PR. Formal recognition/SMS/WeChat identity/funds/devices/release remain R9. User chose configurable source rules; missing rules/evidence must remain UNKNOWN.

## Phases

1. complete — freeze complete R4 scope/design/contracts and local branch; audit existing archive/reminder/health APIs.
2. complete — R4d1 exact-model source policies, confirmations, expiry/comparability trends, reproducible reference scoring and source-based required project suggestions.
3. complete — R4d2 complete manually confirmed structured vehicle/driver documents, violation/fault and maintenance metadata with corrections.
4. complete — R4d3 five source-based/advance reminder categories, dashboard/health/AI/booking integrations and operator pages.
5. complete — remaining R4b public-rating quote sorting and symptom→AI handoff, full R4 local backend/MySQL/HTTP/frontend/build/schema/generated docs validation; close remaining requirements accurately.
6. upload-ready — only after phases 1–5 complete commit/push ALL code+docs together, create PR based on #148, complete six CI checks.

## Decisions

- Base: codex/r4c3-plan-booking / 283b28f, PR #148 six CI passed, unmerged.
- Implement configurable engine/admin source confirmation; no invented production medical/automotive thresholds or seeded technical rules.
- Existing data not retroactively confirmed; previous API contracts remain compatible.
- Local incremental commits are allowed; remote uploads deferred until entire R4 implementation complete.

## Errors

- First health test compile lacked ObjectNode import; production compile passed. Added explicit test import; rerun targeted database/HTTP suite.
- Full backend command: PowerShell split unquoted dotted Maven property; quote -Dupload.test.images.prebuilt=true. Local upload CI image tags absent, build both test images before retry.

- DockerHub alpine metadata returned text/html. Verified cached MinIO/mc SHA256 against pinned Dockerfiles; rebuilt upload-init from cached image with current tracked initialize.sh, verified script SHA256, running full backend with quoted Maven property. No repository image/test relaxation.

## Final local snapshot before unified upload

All R4 functions/docs complete. Full backend first run742/741 passed, one class initialization error from local ClamAV descriptor; same official1.4.3 cached image restored. Public HTTP endpoint localhost policy retained; isolated Maven runner maps logical localhost to same Docker Desktop host published ports. Recovered real upload7/7 and fresh health export1/1 passed; complete unique backend coverage748. Miniapp499, finalWX/H5 builds, schema80, generated185 operations/141 rows/unchanged44 baseline, docs links and real JSON-schema response passed. Phase6 final upload/remote verification proof is recorded in the unified PR body after this snapshot; no intermediate push.
