# S1 Archive Photo Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner capture a photo and save a real photo-source vehicle archive, then see it in archive and home summaries.

**Architecture:** Extend the existing archive contract with `input_type=1` while preserving omitted `input_type` as manual `3`. Reuse the same form, private image upload, ownership checks, idempotent write and paginated read. Only the image picker source changes for photo mode.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, MySQL; uni-app 3, Vue 3, Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-05-archive-photo-design.md`

## Global Constraints

- Only input types 1 (photo) and 3 (manual) may be created; omitted means 3. Type 1 requires 1–5 image IDs, type 3 allows 0–5.
- Keep owner session, vehicle ownership, CLEAN image, transaction, idempotency and audit guarantees; no schema migration.
- Do not infer content from photos or claim OCR/voice support. Do not store signed URLs or sensitive form data persistently.

---

### Task 1: Archive API and database read/write

**Files:** Modify `backend/src/main/java/com/autocare/platform/vehicle/ArchiveInput.java`, `ArchiveService.java`; test `backend/src/test/java/com/autocare/platform/JdbcArchiveTest.java`.

**Interfaces:** `ArchiveInput` gains `int inputType` and parses optional `input_type`; `canonical()` includes it. `ArchiveService.list()` returns `input_type` for types 1 and 3.

- [ ] Add failing parser and MySQL tests: omitted type is 3; type 1 without files and type 2 fail 400; type 1 with one CLEAN owned file saves `input_type=1`, returns it in list and audit, and changes under the same idempotency key fail 400. Assert mixed type pagination and total, foreign/unclean image refusal and rollback.
- [ ] Run `mvn -B -Dtest=JdbcArchiveTest test` from `backend` where Java/Docker are available; expect the new tests to fail before code change.
- [ ] Implement strict field parsing with `int inputType = body.has("input_type") ? ... : 3`; require `(inputType == 1 || inputType == 3)`, and `inputType == 1 && files.isEmpty()` is invalid. Include `input_type` in canonical map, SQL insert parameter and safe audit map. Query `a.input_type IN (1,3)` in both rows and count, select and return `input_type` in each row.
- [ ] Run the backend test again and commit the backend/API change after pass; if local Java/Docker are unavailable, record this and use CI for execution.

### Task 2: Miniapp contract and camera selection

**Files:** Modify `apps/miniapp/src/services/archives.js`, `archive-flow.js`, `private-images.js`; test `apps/miniapp/test/archives.test.js`, `archive-flow.test.js`, `images.test.js`.

**Interfaces:** `state.inputType` defaults to 3; `archiveBody(fields)` emits `input_type` only for type 1, rejects photo without images; `imageApi.choose(token, source='mixed')` requests `['camera']` for `source='camera'` and existing `['album','camera']` otherwise.

- [ ] Add failing tests for photo body, manual compatibility, type 2 rejection, camera-only `sourceType`, cancel/permission failure, and mixed archive list response with `input_type`.
- [ ] Run focused Node tests from `apps/miniapp` using `node --test --test-isolation=none test/archives.test.js test/archive-flow.test.js test/images.test.js`; expect the new assertions to fail.
- [ ] Implement the above interfaces. Continue using `ArchiveError` and `ImageError` safe messages; ensure a cancelled camera selection returns `null` without replacing previously uploaded file IDs.
- [ ] Run focused tests and commit the client contract changes after pass.

### Task 3: Archive and home pages

**Files:** Modify `apps/miniapp/src/pages/archive/index.vue`, `record-add.vue`, `home/index.vue`; create `apps/miniapp/src/services/archive-entry-mode.js` and `apps/miniapp/test/archive-entry-mode.test.js`; modify `apps/miniapp/test/home-summary.test.js`.

**Interfaces:** `archiveEntryUrl(vehicleId, mode)` builds the two routes, `archiveInputType(query)` returns 1 only for exact `mode=photo` and 3 otherwise, `archiveInputTypeName(value)` returns source labels. `record-add?vehicle_id=N&mode=photo` sets `state.inputType=1`; existing manual route omits `mode`.

- [ ] Add pure helper tests asserting the exact photo/manual routes and source labels; add a home summary fixture whose latest archive has `input_type=1`. Photo image requirement and camera-only `sourceType` are covered by Task 2 client tests.
- [ ] Run focused tests and observe failures.
- [ ] Update archive actions to offer manual and photo routes for the selected vehicle. In `record-add.vue`, parse exact `mode=photo`, preserve mode across form resets, call camera-only selection in photo mode, show distinct source guidance and disable save when photo has no uploaded images. Render source labels in archive list and latest home record. Keep existing generation fencing, previews and upload retry key behavior.
- [ ] Run full miniapp Node suite plus `npm --prefix apps/miniapp run build:mp-weixin` and `npm --prefix apps/miniapp run build:h5`; commit page changes after pass.

### Task 4: Contract documentation and final verification

**Files:** Modify `docs/api/ARCHIVE_MANUAL.md`, `scripts/generate_openapi.py`, generated `docs/api/openapi.json`, `README.md`, `docs/progress/CURRENT_STATUS.md`, `NEXT_STEPS.md`, `S0_EXECUTION_LOG.md`.

**Interfaces:** Document request `input_type`, mixed-list response, legacy omitted default, photo image requirement, and actual validation evidence.

- [ ] Update the written contract and `scripts/generate_openapi.py`, run `python scripts/generate_openapi.py`, then `git diff --check` and `python scripts/generate_openapi.py` again to verify stable generated output.
- [ ] Update progress and execution record with what passed locally, what CI later proves, and the remaining real camera/private environment limitations.
- [ ] Review the diff for accidental broader scope; push an independent branch and create a PR against `main` using a body file. Verify all six CI checks, then report PR and evidence. Do not merge without a separate user request.
