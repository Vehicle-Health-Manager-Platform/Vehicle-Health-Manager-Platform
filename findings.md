# 2026-10-14 findings

## R5b final findings

User approved one coupon/issuing store/project, cash-paid commission with existing waiver, full-refund original-expiry return, >=0.01 cash DISCOUNT and standalone noncash SERVICE. V039 adds four tables and permanent creation intent: generic24-hour receipts cannot recreate closed coupon orders. Payment/close/refund convert coupons atomically; persistent recovery rejects contradictory paid/closed facts and defers bad rows. Service code stores digest only, previews actual project, uses current merchant/staff, unique noncash receipt/0 commission, no fake payment or work archive. R5 is a+b; invitation/points rewards are laterR6/R8. Local165 distinct backend/535 frontend cases, builds and88-table replay passed; upload only after wholeR5 closure.

R4 is merged and final main CI is green. Do not repeat historical health/ratings/AI gaps as unimplemented. Coupon/invite/points/assessment/notification base tables exist, but current business Java contains no corresponding complete services. ReservationInput.Booking accepts exactly quote/version/vehicle/slot; no coupon flow. CommissionLedger leaves coupon orders WAIT_COUPON, not implemented coupon exemption. Mine page explicitly postpones welfare/points/community entries; no welfare/index.vue exists. Owner order detail exposes status/pickup/refund/redemption, not full construction evidence or before/after comparison.

Supplied document text equals tracked DELIVERY_V1 after newline/BOM normalization; raw SHA differs only encoding/newlines.63 actual registered pages, no welfare/coupon/invite/point/assessment/notification routes. Existing private order review accepts up to3 photos; only public photo projection is missing. Professional knowledge retrieval/vector/source corpus not present in inspected business code; current AI uses limited SQL context/catalog, so evidence cannot imply complete external RAG. Original cash-discount coupon commission behavior unspecified; plan separates explicit no-fee coupon redemption from cash-order commission policy to freeze beforeR5b.
# R5a implementation findings — 2026-10-14

Existing coupon/user_coupon rows had no trusted issuance source. V038 adds four evidence tables and independent can_coupon (default0); no legacy promotion, policy seed or implicit grant. Published rights frozen, single issuing merchant/project, lifetime one claim per template. Merchant/owner/operator mutations follow current auth, ordered source locks, canonical WriteIntegrityService, atomic audit and receipt. Network ambiguity preserves exact payload/UUID; successful write followed by failed GET closes merchant draft to prevent accidental recreation. UI selects/displays Beijing validity; UTC only on wire. 62 distinct backend targeted tests and 514 frontend tests passed; no R5b trading behavior added.
# R6a findings (2026-10-14)

## R6b 2026-10-15

- Completed local R6b1 configuration, global shop/project lock ordering for multi-coupon publication and generated active-scope uniqueness. Reward issuance remains R6b2; public claim UNIQUE(coupon_id,user_id) unchanged.
- Docker engine initially stopped; desktop CLI started local engine29.6.2, real MySQL tests completed, no skipped database acceptance. Final evidence36 distinct backend/561 frontend/92 tables/74 pages/212 actual/draft OpenAPI operations.

- R6a PR152 exacthead1b2161e, remote six CI successful, backend842/frontend553. User now forbids intermediate upload until all R6 complete.
- R5 coupon_issue has UNIQUE(coupon_id,user_id), so repeat reward issuance requires a separately constrained issuance origin in R6b2; do not silently remove public-claim uniqueness in R6b1.
- User approved owner two-sided reward per valid invite and staff invitee-only reward. Reward config references current published, operator-selected coupon sources; no production seed or fabricated staff beneficiary.

- Remote first full run isolated stale coupon @BeforeAll total-schema assertion; updated88 to91 and35/35 passed. Public Mine invitation action now renders outside private signed-in content gate, preserving first-registration attribution entry. Local total101 backend cases,553 frontend, final builds passed.

- Implemented V040 (91 total tables), nine growth routes, opaque registration record UUIDs and private source-scoped reads; strict login/phone payloads and source flags cannot be client supplied.
- Official evidence gate defaults false, cloud source UNKNOWN; server official endpoint plus same-app verified phone required for formal qualification. Source/phone/month/audit facts atomic, earlier unknown/test evidence never promoted.
- R6b staff rewards need real owner beneficiary provenance; never treat staff_id as user_id. No automatic human-diagnosis unlock without configured source/rule.
- Final focused session gate33/33 passed;66 distinct backend cases in total,553 frontend. Compose bootstrap missing V027-V039 dependencies was identified and corrected alongside V040; remote smoke verifies91 actual tables.

- R6 next after uploaded R5. R6a must reject existing/self/duplicate attribution and freeze owner or staff source at first registration; R6b uses R5 coupon issuance with recoverable reward events.
- User approved each Beijing calendar month incremental 1/2/3/4 targets, cumulative 1/3/6/10, repeated after 10; each qualified new user produces one reward pair rather than tier payouts.
- Current createOwnerOrRead inserts or reads owner by unique openid but records neither new-registration evidence nor invitation context. Legacy inviter_id/invite_record are not reliable proof.
- Actual formal WeChat identity and native share remain R9. Test identities must be explicit and excluded from formal progress/rewards; never infer verification from client flags or timestamps.


- R6b2 V042 public_user_id keeps PUBLIC unique per template/user; INVITE uses unique reward_item_id and batch/role. Qualification snapshots published policy inside original phone transaction; legacy/no-policy event remains UNKNOWN_POLICY. GET is read-only and other-party coupon IDs are null.
- R6b2 fixed published quotas have no top-up API: WAIT_STOCK persists without a partial pair; no automatic replacement/expiry extension. Original issuer/account can recover through durable retries. Formal reward scheduler defaults off.

- R6b2 lock-expiry test failed because its fixed observation budget included worker startup/pre-lock reads; failure snapshots showed both actual LOCK WAIT and not-yet-at-lock states. Added an observed JdbcTemplate checkpoint and reused monitoring connection, keeping the actual database wait assertion. R6b3 accepted monthly eligibility excludes currently disabled invitees and never exposes an operational message endpoint.

- Final review: qualification month and qualified_at previously used separate clock reads and could disagree across Beijing month boundary; one snapshot plus moving-clock DB regression added. Native preview could hide/unmount and unlink its image; use in-page preview, retire in-use album files until completion. Operator forms retained stale CAS revision after confirmed writes; reset on confirmed receipt, including retry and refresh failure.
- Local whole-R6 integration environment: only Maven/MySQL/ClamAV images available after interruption; Docker Hub auth resolution/connect fails for alpine:3.21. Explicit local exclusion of RealUploadAdaptersTest and failed Compose startup are documented; repository six-job CI remains unmodified and mandatory on final uploaded head.
