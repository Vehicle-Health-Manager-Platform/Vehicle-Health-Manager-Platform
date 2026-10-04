# S1 Home Archive Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show real owner vehicle and archive summary data on the miniapp home tab, with one shared vehicle selection across home and archive.

**Architecture:** Keep selected vehicle in a token-bound in-memory service. Reuse the existing protected vehicle list and archive list APIs; a small home summary flow fences stale responses and reads the archive list total and newest row. No backend or migration changes.

**Tech Stack:** Vue 3, uni-app 3, Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-04-home-archive-summary-design.md`

## Global Constraints

- Base branch is PR #15 commit `4b0aec1`; do not merge stacked PRs.
- Show only server-provided owner data and masked identifiers; no invented score or reminder.
- Clear selection on `ownerSession.accessToken` changes and discard stale async results.
- Use `/browse` for any webpage interaction; builds and offline tests are distinct from browser or real WeChat checks.

---

### Task 1: Share owner vehicle selection

**Files:** Create `apps/miniapp/src/services/owner-vehicle-selection.js`; modify `apps/miniapp/src/components/VehicleList.vue`, `apps/miniapp/src/pages/archive/index.vue`; test `apps/miniapp/test/home-summary.test.js`.

**Interfaces:** `selectedOwnerVehicle` is a Vue ref; `selectOwnerVehicle(row)` accepts a validated vehicle list row and returns the selected ID; `clearOwnerVehicle()` clears it. `VehicleList` emits a matching selected row after refresh and defaults to first row only when `selectedId=0`.

- [ ] Add a test that selects a vehicle from page two, refreshes page one, and retains that ID; simulate token change and assert selection clears.
- [ ] Implement token-bound selection and update the vehicle list auto-selection condition.
- [ ] Wire archive Tab to the shared selection and confirm switching there updates the home selection.
- [ ] Run focused Node tests and commit `feat: share selected owner vehicle`.

### Task 2: Load real archive summary for the selected vehicle

**Files:** Create `apps/miniapp/src/services/home-summary-flow.js`; modify `apps/miniapp/src/pages/home/index.vue`; test `apps/miniapp/test/home-summary.test.js`.

**Interfaces:** `createHomeSummaryFlow({state,api,token})` exposes `select(row)`, `load()`, `suspend()`, and `reset()`. State contains `vehicle`, `total`, `latest`, `busy`, `loaded`, `message`, `failureKind`.

- [ ] Add failing tests for total/latest from `api.list(token,vehicleId,1)`, empty records, retry after failure, vehicle switch during a pending request, and logout during a pending request.
- [ ] Implement the flow with generation fencing and safe errors from `archiveFailure`.
- [ ] Render a real home vehicle section and summary; link to archive Tab, add vehicle, and retry/login actions.
- [ ] Run the focused and full Node suites, then miniapp and H5 builds; commit `feat: show owner archive summary on home`.

### Task 3: Record evidence and publish the stacked PR

**Files:** Modify `README.md`, `docs/progress/CURRENT_STATUS.md`, `docs/progress/NEXT_STEPS.md`, `docs/progress/S0_EXECUTION_LOG.md`.

**Interfaces:** No code interface; document observed verification and remaining real-environment checks.

- [ ] Update progress based on actual tests and builds; do not claim browser, simulator or real services unless executed.
- [ ] Run `git diff --check`, commit documentation, push the branch and open a PR based on `codex/s1-archive-manual`.
- [ ] Wait for all CI jobs, fix failures, and record the final run without merging the PR.
