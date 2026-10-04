# S1 Owner Archive Manual Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner create and query manual records for their own vehicles and attach up to five owned, clean images.

**Architecture:** A new archive module reuses `VehicleOwner`, `WriteIntegrityService`, and the private upload metadata. V005 records ordered file references in a join table. The miniapp adds a record form and a per-vehicle list; image preview continues through the existing short-lived access API.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JdbcTemplate, MySQL 8, Vue 3/uni-app, Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-04-archive-manual-design.md`

## Global Constraints

- Build on `ff51e5e` and preserve the PR #12 → #13 → #14 dependency order.
- Only formal OWNER user sessions may read or write records; derive owner ID from JWT.
- `archive_type` is 1–7 and `input_type=3`; file IDs must belong to the owner and be `CLEAN`.
- Keep URLs, object keys, titles, and notes out of audit metadata.
- Keep CI, simulator, and real WeChat/private service verification distinct.

---

### Task 1: Persist ordered archive file references

**Files:**
- Create: `docs/sql/migrations/V005__vehicle_archive_files.sql`
- Modify: `docs/sql/SCHEMA.md`, `deploy/README.md`
- Test: `backend/src/test/java/com/autocare/platform/JdbcArchiveTest.java`

**Interfaces:**
- Consumes: V001 `vehicle_archive` and `file_object` tables.
- Produces: `vehicle_archive_file(archive_id BIGINT UNSIGNED,file_id BIGINT UNSIGNED,position TINYINT UNSIGNED)` with primary key `(archive_id,position)` and unique `(archive_id,file_id)`.

- [ ] **Step 1: Write a migration test** that runs V001–V005 twice in MySQL and asserts an archive accepts two ordered rows but rejects duplicate position and duplicate file ID.
- [ ] **Step 2: Run the focused test** with `mvn -f backend/pom.xml -Dtest=JdbcArchiveTest test`; expect missing V005 to fail.
- [ ] **Step 3: Add V005** with `CREATE TABLE IF NOT EXISTS vehicle_archive_file`, the keys above, `KEY idx_file_id(file_id)`, and no URL column; document V005 in schema/deploy guidance.
- [ ] **Step 4: Re-run the focused test** and confirm it passes, then commit `feat: add ordered archive file references`.

### Task 2: Add protected archive APIs and atomic writes

**Files:**
- Create: `backend/src/main/java/com/autocare/platform/vehicle/ArchiveInput.java`, `ArchiveService.java`, `ArchiveController.java`, `ArchiveConfiguration.java`
- Modify: `backend/src/test/java/com/autocare/platform/JdbcArchiveTest.java`
- Test: `backend/src/test/java/com/autocare/platform/ArchiveAccessTest.java`

**Interfaces:**
- Consumes: `VehicleOwner.from(Jwt)`, `WriteIntegrityService.execute(...)`, `file_object` metadata, V005.
- Produces: `ArchiveInput.parse(JsonNode)` and `canonical()`; `ArchiveService.add(VehicleOwner,String,ArchiveInput): JsonNode`; `ArchiveService.list(VehicleOwner,long,int,int): Map<String,Object>`; `POST /api/archive/add`, `GET /api/archive/list`.

- [ ] **Step 1: Add failing tests** for valid no-image and two-image records; same-key replay; changed body; owner A accessing B's vehicle/file; non-CLEAN/deleted file; pagination and order; expired or revoked session; audit rollback.
- [ ] **Step 2: Run** `mvn -f backend/pom.xml -Dtest=JdbcArchiveTest,ArchiveAccessTest test`; expect API/module failures.
- [ ] **Step 3: Implement input parsing** with exact accepted keys `vehicle_id,archive_type,recorded_date,mileage,title,notes,file_ids`; normalize title/notes; enforce 1–7, ISO date, nonnegative int mileage, title 1–80, notes ≤1000, unique ordered 0–5 positive file IDs.
- [ ] **Step 4: Implement service**: recheck active OWNER session/user; verify `vehicle.user_id` and `is_deleted=0`; validate file rows with owner, `CLEAN`, and `is_deleted=0`; use `WriteIntegrityService` to atomically insert `vehicle_archive`, ordered links, response, and redacted success audit. List only records for the selected owned vehicle, ordered `recorded_at DESC,id DESC`, with a bounded page and ordered file IDs.
- [ ] **Step 5: Implement controller/configuration** following `VehicleController`'s 503 database boundary and `Cache-Control: no-store`; return only IDs and dates. Run focused tests, then all backend tests. Commit `feat: add owner archive APIs`.

### Task 3: Wire miniapp archive flow and image association

**Files:**
- Create: `apps/miniapp/src/services/archives.js`, `apps/miniapp/src/services/archive-flow.js`, `apps/miniapp/src/pages/archive/record-add.vue`
- Modify: `apps/miniapp/src/pages/archive/index.vue`, `apps/miniapp/src/pages.json`, `apps/miniapp/src/components/VehicleList.vue`
- Test: `apps/miniapp/test/archive-flow.test.js`, `apps/miniapp/test/archives.test.js`

**Interfaces:**
- Consumes: `owner-session.js`, `private-images.js`, `image-flow.js`, vehicle selection; API response `{archive_id,vehicle_id}` and list `{list,total,page,page_size}`.
- Produces: `createArchiveApi({baseUrl,runtime})` with `list(token,vehicleId,page)` and `add(token,body,key)`; a form that submits only uploaded `file_id` values.

- [ ] **Step 1: Add failing Node tests** for token/header and response validation; same key/body retry after network failure; changed form key; vehicle switch discarding stale list; image ID carried into request; no-image save.
- [ ] **Step 2: Run** `npm --prefix apps/miniapp test`; expect new tests to fail.
- [ ] **Step 3: Add API client and flow** patterned after `vehicles.js` and `vehicle-flow.js`: fixed user-safe errors, no token or URL in logs, abort/epoch fencing on identity or vehicle change, and retry preservation of the original body/key.
- [ ] **Step 4: Add page and route**: choose existing vehicle, fill common fields, upload/remove up to five images, submit, return to vehicle list; list per vehicle with empty/loading/error/retry/pagination and short-lived preview via `private-images.js`. Clear sensitive state on hide/logout.
- [ ] **Step 5: Run** miniapp tests and `npm --prefix apps/miniapp run build:mp-weixin`; verify H5 interaction and simulator routes if available. Commit `feat: add owner archive miniapp flow`.

### Task 4: Publish contract and verification evidence

**Files:**
- Modify: `docs/api/openapi.json`, `docs/api/README.md`, `README.md`, `docs/progress/CURRENT_STATUS.md`, `docs/progress/NEXT_STEPS.md`, `docs/progress/S0_EXECUTION_LOG.md`

**Interfaces:**
- Consumes: verified task 1–3 behavior.
- Produces: documented API request/response, V005 operation steps, test results and remaining private-environment limitations.

- [ ] **Step 1: Update OpenAPI** with exact field bounds, bearer security, idempotency header, pagination, file IDs, and 400/401/403/404/503 responses; validate JSON parsing.
- [ ] **Step 2: Update progress and README** with actual test counts and clearly state which real environment checks remain unverified.
- [ ] **Step 3: Run** `git diff --check`, backend tests where Java/Docker are available, miniapp tests/build, and repository CI gates; record only observed results.
- [ ] **Step 4: Commit** `docs: record archive contract and verification`; push the branch and create a PR after local review, keeping merge order behind #14.
