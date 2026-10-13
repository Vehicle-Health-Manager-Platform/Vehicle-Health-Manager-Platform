# R4 findings

- Previous R4b2a/b/c1–c3 functionality uploaded with code and docs (#142–#148). #148 backend717/frontend475, all six CI passed.
- Remaining R4: exact-model six-metric applicability/validity/comparable trends/source-based reference score; source-based MUST suggestions; full vehicle/driver document, violations/faults/maintenance fields; five categories fault/maintenance/insurance/inspection/violation with advance reminders and dashboard integration.
- Original mock health87 and advance15/30 days are UI examples, not production rules. User selected configurable policies with unknown defaults.
- Existing raw health reads/history in HealthObservationService, structure in ArchiveStructuredService/Input, three arrival-only categories in ArchiveReminderService.
- User changed upload cadence: finish ALL R4 before push. Update delivery docs to make this current rule explicit.
- User explicitly selected configurable source rules/UNKNOWN. Build full configurable engine/admin source publication and owner evidence confirmation; no seeded production thresholds.
- Original five reminders are FAULT / MAINTENANCE / INSURANCE / INSPECTION / VIOLATION. Existing three reminder targets are per-vehicle (types1,3,7), owner-entered, source validity checked.
- Raw archive currently types1..7 only. Need additive8 registration document,9 driver licence,10 fault record; update all validators/filter/labels/report compatibility, preserve private fields out of shared reports.
- History API supports50 rows/page, corrected markers and invalid-source hiding. Trend should use recent consecutive comparable uncorrected/source-valid observations, cut at clear/method/name/unit/provenance changes, no fake historical percentages.
- Chosen additive archive8/9/10 path, with owner report validators extended and new private types excluded from public share projection. Structured JSON existing table extended, owner confirmation explicit inside fields; source fields remain private.
- New governance/evidence-confirmation/advance-preference tables V037 =>80 tables; existing three reminder API remains unchanged, new dashboard computes five categories and early status.
- ShareService now uses ArchiveReportService.loadShared with type<=7 visibility for both records/undated counters; private owner report accepts1..10 and PDF labels extend. Defense-in-depth share projection also omits>7.
- Existing AI chat context queried raw archives; explicitly limited outbound context to archive_type<=7 so new identity document/fault raw titles/notes cannot be sent. Structured private fields were never queried for AI and remain excluded.
- Verified installed uni-h5 runtime createCanvasContext accepts componentInstance; native chart components pass Vue instance proxy for scoped canvas. Score ring now proportional to actual reference score and gray for unknown.
- Five reminder records bounded to200 per category, latest invalid source suppresses older same-code/case records; dashboard provides limited marker, homepage shows first5 with full-archive link.
- Full F06 re-audit found rating sorting missing (quote flow price/distance only; service detail stale "no public ratings" copy) and symptom search handoff missing (catalog category only). Add source-qualified rating quote feed and symptom→explicit AI purpose flow before declaring R4 complete. Reuse exact PublicRatings.SOURCE/VALID qualification.

Local DockerHub metadata failure is environmental; cached MinIO and mc exactly match pinned digests. Cached upload-init script differed from tracked current source and was replaced before full test, verified F1339DDC1E7D84B3CAE08E9866E6A33B5CA80ACCC013BD509A27BDC96768762A. Production rules remain absent by design; UNKNOWN is user-approved.
