# R5a coupon inventory and claiming implementation

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
