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
