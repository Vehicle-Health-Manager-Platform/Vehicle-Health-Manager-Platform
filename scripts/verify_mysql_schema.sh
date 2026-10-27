#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

container=s0-schema-mysql
password=s0-ci-only-password
database=vehicle_health

docker run --detach --rm --name "$container" \
  --env "MYSQL_ROOT_PASSWORD=$password" \
  --env "MYSQL_DATABASE=$database" \
  mysql:8.0 >/dev/null
trap 'docker stop "$container" >/dev/null 2>&1 || true' EXIT

ready=false
for attempt in $(seq 1 60); do
  if docker exec --env "MYSQL_PWD=$password" "$container" \
      mysql --protocol=tcp --host=127.0.0.1 --user=root \
      --database="$database" --execute 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  docker logs "$container"
  echo "MySQL did not become ready" >&2
  exit 1
fi

run_sql_file() {
  docker exec --interactive --env "MYSQL_PWD=$password" "$container" \
    mysql --default-character-set=utf8mb4 --user=root "$database" < "$1"
}

query() {
  docker exec --env "MYSQL_PWD=$password" "$container" \
    mysql --user=root --batch --skip-column-names "$database" --execute "$1"
}

run_sql_file docs/sql/init.sql
run_sql_file docs/sql/migrations/V001__baseline.sql
run_sql_file docs/sql/migrations/V002__staff_wechat_identity.sql
run_sql_file docs/sql/migrations/V002__staff_wechat_identity.sql
run_sql_file docs/sql/migrations/V003__auth_lifecycle.sql
run_sql_file docs/sql/migrations/V003__auth_lifecycle.sql
run_sql_file docs/sql/migrations/V004__upload_http.sql
run_sql_file docs/sql/migrations/V004__upload_http.sql
run_sql_file docs/sql/seed_test.sql
run_sql_file docs/sql/seed_test.sql
run_sql_file docs/sql/migrations/V005__vehicle_archive_files.sql
run_sql_file docs/sql/migrations/V006__merchant_project_versions.sql
run_sql_file docs/sql/migrations/V006__merchant_project_versions.sql
run_sql_file docs/sql/migrations/V007__reservation_orders.sql
run_sql_file docs/sql/migrations/V007__reservation_orders.sql

run_sql_file docs/sql/migrations/V008__payment_foundation.sql
run_sql_file docs/sql/migrations/V008__payment_foundation.sql

run_sql_file docs/sql/migrations/V009__order_fulfillment_states.sql
run_sql_file docs/sql/migrations/V009__order_fulfillment_states.sql
run_sql_file docs/sql/migrations/V010__pickup_inspection.sql
run_sql_file docs/sql/migrations/V010__pickup_inspection.sql
run_sql_file docs/sql/migrations/V011__pickup_owner_decision.sql
run_sql_file docs/sql/migrations/V011__pickup_owner_decision.sql
run_sql_file docs/sql/migrations/V012__technician_dispatch.sql
run_sql_file docs/sql/migrations/V012__technician_dispatch.sql
run_sql_file docs/sql/migrations/V013__dispute_resolution.sql
run_sql_file docs/sql/migrations/V013__dispute_resolution.sql

run_sql_file docs/sql/migrations/V014__service_work.sql
run_sql_file docs/sql/migrations/V014__service_work.sql

run_sql_file docs/sql/migrations/V015__order_redemption.sql
run_sql_file docs/sql/migrations/V015__order_redemption.sql

run_sql_file docs/sql/migrations/V016__order_reviews.sql
run_sql_file docs/sql/migrations/V016__order_reviews.sql
run_sql_file docs/sql/migrations/V017__service_archive_jobs.sql
run_sql_file docs/sql/migrations/V017__service_archive_jobs.sql
run_sql_file docs/sql/migrations/V018__experience_cards.sql
run_sql_file docs/sql/migrations/V018__experience_cards.sql
run_sql_file docs/sql/migrations/V019__experience_moderation.sql
run_sql_file docs/sql/migrations/V019__experience_moderation.sql
run_sql_file docs/sql/migrations/V020__merchant_onboarding.sql
run_sql_file docs/sql/migrations/V020__merchant_onboarding.sql
run_sql_file docs/sql/migrations/V021__merchant_staff_store.sql
run_sql_file docs/sql/migrations/V021__merchant_staff_store.sql

run_sql_file docs/sql/migrations/V022__catalog_governance.sql
run_sql_file docs/sql/migrations/V022__catalog_governance.sql
run_sql_file docs/sql/migrations/V023__completion_archive_jobs.sql
run_sql_file docs/sql/migrations/V023__completion_archive_jobs.sql
run_sql_file docs/sql/migrations/V024__payment_query_recovery.sql
run_sql_file docs/sql/migrations/V024__payment_query_recovery.sql
run_sql_file docs/sql/migrations/V025__order_refunds.sql
run_sql_file docs/sql/migrations/V025__order_refunds.sql
run_sql_file docs/sql/migrations/V026__commission_ledger.sql
run_sql_file docs/sql/migrations/V026__commission_ledger.sql
run_sql_file docs/sql/migrations/V027__archive_report_shares.sql
run_sql_file docs/sql/migrations/V027__archive_report_shares.sql
run_sql_file docs/sql/migrations/V028__archive_reminders.sql
run_sql_file docs/sql/migrations/V028__archive_reminders.sql
run_sql_file docs/sql/migrations/V029__archive_structured_details.sql
run_sql_file docs/sql/migrations/V029__archive_structured_details.sql
run_sql_file docs/sql/migrations/V030__vehicle_health_observations.sql
run_sql_file docs/sql/migrations/V030__vehicle_health_observations.sql
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='refund' AND column_name IN ('refund_no','revision','owner_consented_at','merchant_consented_at','dispatch_token','next_attempt_at','controlled_order_id')")" == 7 ]] || { echo 'V025 refund fields missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='refund' AND non_unique=0 AND index_name IN ('uk_managed_refund_no','uk_managed_refund_order')")" == 2 ]] || { echo 'V025 refund unique guards missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='payment' AND column_name IN ('query_token','next_query_at','query_attempts','last_query_at','last_query_error')")" == 5 ]] || { echo 'V024 payment query fields missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='payment' AND column_name='query_attempts' AND column_default='0'")" == 1 ]] || { echo 'V024 attempts default missing' >&2; exit 1; }
[[ "$(query "SELECT is_nullable FROM information_schema.columns WHERE table_schema='$database' AND table_name='service_archive_job' AND column_name='review_id'")" == YES ]] || { echo 'V023 review_id must allow null' >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='service_archive_job' AND column_name='card_requested' AND column_default='0'")" == 1 ]] || { echo 'V023 card_requested missing' >&2; exit 1; }

[[ "$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_name IN ('order_commission_snapshot','merchant_commission_entry')")" == 2 ]] || { echo 'V026 commission tables missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='merchant_commission_policy' AND column_name IN ('rule_version','source_application_id')")" == 2 ]] || { echo 'V026 policy provenance missing' >&2; exit 1; }
run_sql_file docs/sql/migrations/V031__vehicle_health_observation_history.sql
run_sql_file docs/sql/migrations/V031__vehicle_health_observation_history.sql
run_sql_file docs/sql/migrations/V032__merchant_location.sql
run_sql_file docs/sql/migrations/V032__merchant_location.sql
run_sql_file docs/sql/migrations/V033__review_publication.sql
run_sql_file docs/sql/migrations/V033__review_publication.sql
run_sql_file docs/sql/migrations/V034__public_review_text.sql
run_sql_file docs/sql/migrations/V034__public_review_text.sql
run_sql_file docs/sql/migrations/V035__review_summary_consent.sql
run_sql_file docs/sql/migrations/V035__review_summary_consent.sql
run_sql_file docs/sql/migrations/V036__project_model_applicability.sql
run_sql_file docs/sql/migrations/V036__project_model_applicability.sql
run_sql_file docs/sql/migrations/V037__health_policies_and_confirmations.sql
run_sql_file docs/sql/migrations/V037__health_policies_and_confirmations.sql
run_sql_file docs/sql/migrations/V038__coupon_inventory_and_claims.sql
run_sql_file docs/sql/migrations/V038__coupon_inventory_and_claims.sql
run_sql_file docs/sql/migrations/V039__coupon_transactions.sql
run_sql_file docs/sql/migrations/V039__coupon_transactions.sql
run_sql_file docs/sql/migrations/V040__invitation_attribution.sql
run_sql_file docs/sql/migrations/V040__invitation_attribution.sql
[[ "$(query "SELECT COUNT(*) FROM review_summary_consent")" == 0 ]] || { echo "AI consent was inferred" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM public_review_text")" == 0 ]] || { echo "Private review texts were backfilled" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM review_publication")" == 0 ]] || { echo "Legacy reviews were published" >&2; exit 1; }

run_sql_file docs/sql/migrations/V041__invitation_reward_policies.sql
run_sql_file docs/sql/migrations/V041__invitation_reward_policies.sql
run_sql_file docs/sql/migrations/V042__invitation_reward_delivery.sql
run_sql_file docs/sql/migrations/V042__invitation_reward_delivery.sql
run_sql_file docs/sql/migrations/V043__invitation_diagnosis_unlock.sql
run_sql_file docs/sql/migrations/V043__invitation_diagnosis_unlock.sql
run_sql_file docs/sql/migrations/V044__order_archive_grants.sql
run_sql_file docs/sql/migrations/V044__order_archive_grants.sql
run_sql_file docs/sql/migrations/V045__service_notification_outbox.sql
run_sql_file docs/sql/migrations/V045__service_notification_outbox.sql
run_sql_file docs/sql/migrations/V046__async_diagnosis.sql
run_sql_file docs/sql/migrations/V046__async_diagnosis.sql
run_sql_file docs/sql/migrations/V047__order_notification_sources.sql
run_sql_file docs/sql/migrations/V047__order_notification_sources.sql
run_sql_file docs/sql/migrations/V048__diagnosis_notifications.sql
run_sql_file docs/sql/migrations/V048__diagnosis_notifications.sql
run_sql_file docs/sql/migrations/V049__point_task_policies.sql
run_sql_file docs/sql/migrations/V049__point_task_policies.sql
run_sql_file docs/sql/migrations/V050__verified_points_ledger.sql
run_sql_file docs/sql/migrations/V050__verified_points_ledger.sql
run_sql_file docs/sql/migrations/V051__point_exchange_catalog.sql
run_sql_file docs/sql/migrations/V051__point_exchange_catalog.sql
[[ "$(query "SELECT COUNT(*) FROM point_exchange_catalog")" == 0 ]] || { echo "V051 must not seed exchange offers" >&2; exit 1; }
# Synthetic pre-upgrade evidence verifies that signed-source DDL preserves old rows.
query "INSERT INTO point_verified_entry(user_id,event_id,delta,balance_after,created_at) VALUES(9000001,9000001,7,7,'2026-10-16 00:00:00')" >/dev/null
query "INSERT INTO coupon_issue(user_coupon_id,coupon_id,user_id,publication_revision,issued_at,origin,reward_item_id) VALUES(9000001,9000001,9000001,2,'2026-10-16 00:00:00','PUBLIC',NULL),(9000002,9000001,9000001,2,'2026-10-16 00:00:00','INVITE',9000001)" >/dev/null
run_sql_file docs/sql/migrations/V052__atomic_point_exchange.sql
run_sql_file docs/sql/migrations/V052__atomic_point_exchange.sql
[[ "$(query "SELECT COUNT(*) FROM point_verified_entry WHERE user_id=9000001 AND event_id=9000001 AND delta=7 AND balance_after=7 AND exchange_id IS NULL")" == 1 ]] || { echo "V052 changed pre-upgrade point evidence" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM coupon_issue WHERE user_coupon_id IN (9000001,9000002) AND points_exchange_id IS NULL AND ((origin='PUBLIC' AND reward_item_id IS NULL AND public_user_id=9000001) OR (origin='INVITE' AND reward_item_id=9000001 AND public_user_id IS NULL))")" == 2 ]] || { echo "V052 changed PUBLIC/INVITE provenance" >&2; exit 1; }
query "DELETE FROM point_verified_entry WHERE user_id=9000001" >/dev/null
query "DELETE FROM coupon_issue WHERE user_coupon_id IN (9000001,9000002)" >/dev/null
[[ "$(query "SELECT COUNT(*) FROM point_exchange_record")" == 0 ]] || { echo "V052 must not seed point exchanges" >&2; exit 1; }
run_sql_file docs/sql/migrations/V053__assessment_policies.sql
run_sql_file docs/sql/migrations/V053__assessment_policies.sql
run_sql_file docs/sql/migrations/V054__assessment_monthly_snapshots.sql
run_sql_file docs/sql/migrations/V054__assessment_monthly_snapshots.sql
run_sql_file docs/sql/migrations/V055__business_dashboard_permission.sql
run_sql_file docs/sql/migrations/V055__business_dashboard_permission.sql
run_sql_file docs/sql/migrations/V056__price_monitor_policies.sql
run_sql_file docs/sql/migrations/V056__price_monitor_policies.sql
run_sql_file docs/sql/migrations/V057__curated_experiences.sql
run_sql_file docs/sql/migrations/V058__private_community_questions.sql
run_sql_file docs/sql/migrations/V059__public_community_questions.sql
run_sql_file docs/sql/migrations/V057__curated_experiences.sql
run_sql_file docs/sql/migrations/V058__private_community_questions.sql
run_sql_file docs/sql/migrations/V059__public_community_questions.sql
run_sql_file docs/sql/migrations/V060__public_image_copies.sql
run_sql_file docs/sql/migrations/V060__public_image_copies.sql
run_sql_file docs/sql/migrations/V061__licensed_knowledge.sql
run_sql_file docs/sql/migrations/V061__licensed_knowledge.sql
[[ "$(query "SELECT COUNT(*) FROM public_image_application")" == 0 ]] || { echo "V060 must not seed public image consent" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM knowledge_document")" == 0 ]] || { echo "V061 must not seed unlicensed knowledge" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM operator_account WHERE can_knowledge<>0")" == 0 ]] || { echo "Knowledge permission must default off" >&2; exit 1; }

[[ "$(query "SELECT COUNT(*) FROM assessment_policy")" == 0 ]] || { echo "V053 must not seed assessment rules" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM operator_account WHERE can_assessment<>0")" == 0 ]] || { echo "Assessment permission must default off" >&2; exit 1; }
run_sql_file docs/sql/migrations/V062__input_material.sql
run_sql_file docs/sql/migrations/V062__input_material.sql
[[ "$(query "SELECT COUNT(*) FROM input_material")" == 0 ]] || { echo "V062 must not seed private input materials" >&2; exit 1; }
run_sql_file docs/sql/migrations/V063__recognition_job.sql
run_sql_file docs/sql/migrations/V063__recognition_job.sql
[[ "$(query "SELECT COUNT(*) FROM recognition_job")" == 0 ]] || { echo "V063 must not seed recognition jobs or transcripts" >&2; exit 1; }

tables=$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$database' AND table_type = 'BASE TABLE'")
[[ "$tables" == 132 ]] || { echo "Expected 132 tables after V063, got $tables" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM merchant_location")" == 0 ]] || { echo 'V032 must not infer legacy coordinates' >&2; exit 1; }
catalog_columns=$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '$database' AND ((table_name IN ('brand','series','model','standard_project') AND column_name IN ('sort_order','source_note','revision')) OR (table_name IN ('brand','series','model') AND column_name='status') OR (table_name='operator_account' AND column_name='can_catalog'))")
[[ "$catalog_columns" == 16 ]] || { echo "V022 catalogue fields incomplete: $catalog_columns/16" >&2; exit 1; }

for column in check_in_completed_at owner_confirmed_at assigned_at service_report_ready_at; do
  found=$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '$database' AND table_name = 'order' AND column_name = '$column'")
  [[ "$found" == 1 ]] || { echo "Repeated V009 left order.$column missing" >&2; exit 1; }
done
transitions=$(query "SELECT COUNT(DISTINCT column_name) FROM information_schema.columns WHERE table_schema = '$database' AND table_name = 'order_status_transition' AND column_name IN ('order_id','from_status','to_status','action','actor_type','actor_id','occurred_at')")
[[ "$transitions" == 7 ]] || { echo "order_status_transition is missing audit columns, got $transitions" >&2; exit 1; }
for column in assigned_by accepted_at; do
  found=$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '$database' AND table_name = 'technician_assignment' AND column_name = '$column' AND is_nullable = 'YES'")
  [[ "$found" == 1 ]] || { echo "Repeated V012 left technician_assignment.$column missing or non-nullable" >&2; exit 1; }
done
assignment_unique=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = 'technician_assignment' AND index_name = 'uk_order' AND column_name = 'order_id' AND non_unique = 0")
[[ "$assignment_unique" == 1 ]] || { echo "V012 did not preserve the unique assignment per order" >&2; exit 1; }
for entry in pickup_check:dispute_reason order_status_transition:note; do
  table="${entry%%:*}"
  column="${entry##*:}"
  length=$(query "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = '$database' AND table_name = '$table' AND column_name = '$column'")
  [[ "$length" == 500 ]] || { echo "Repeated V011 left $table.$column with length $length instead of 500" >&2; exit 1; }
done
dispute_unique=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = 'order_dispute' AND index_name = 'uk_order' AND column_name = 'order_id' AND non_unique = 0")
[[ "$dispute_unique" == 1 ]] || { echo "V013 did not preserve one dispute per order" >&2; exit 1; }
dispute_records=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = 'order_dispute_record' AND index_name = 'idx_dispute' AND non_unique = 1")
[[ "$dispute_records" == 1 ]] || { echo "Repeated V013 left order_dispute_record without its timeline index" >&2; exit 1; }
owner_confirm_comment=$(query "SELECT column_comment FROM information_schema.columns WHERE table_schema = '$database' AND table_name = 'pickup_check' AND column_name = 'owner_confirm'")
[[ "$owner_confirm_comment" == *3* ]] || { echo "Repeated V013 left pickup_check.owner_confirm without the resolved state" >&2; exit 1; }

for entry in experience_card:uk_card_order experience_card:uk_card_archive service_archive_job:uk_archive_order service_archive_job:uk_archive_review service_archive_job:uk_archive_output order_review:uk_review_order order_review_file:uk_review_file order_redemption:uk_redemption_order service_evidence_file:uk_file service_report_submission:uk_order service_report_submission:uk_report; do
  table="${entry%%:*}"
  index="${entry##*:}"
  found=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = '$table' AND index_name = '$index' AND non_unique = 0")
  [[ "$found" == 1 ]] || { echo "Repeated V014 missing $table.$index" >&2; exit 1; }
done

versions=$(query "SELECT COUNT(*) FROM merchant_project_version WHERE merchant_project_id=900001 AND version=1")
[[ "$versions" == 1 ]] || { echo "Repeated V006 did not preserve one initial quote version" >&2; exit 1; }

for index in uk_active_app_openid uk_active_app_staff; do
  found=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = 'staff_wechat_identity' AND index_name = '$index' AND non_unique = 0")
  [[ "$found" == 1 ]] || { echo "Missing unique index $index" >&2; exit 1; }
done

query "INSERT INTO staff_account (id, role, account) VALUES (900001, 'TECHNICIAN', 'schema-tech-1'), (900002, 'TECHNICIAN', 'schema-tech-2')" >/dev/null
query "INSERT INTO staff_wechat_identity (app_id, openid, staff_account_id) VALUES ('test-app', 'wx-one', 900001)" >/dev/null
if query "INSERT INTO staff_wechat_identity (app_id, openid, staff_account_id) VALUES ('test-app', 'wx-one', 900002)" >/dev/null 2>&1; then
  echo "Active openid could be bound to two staff accounts" >&2
  exit 1
fi
if query "INSERT INTO staff_wechat_identity (app_id, openid, staff_account_id) VALUES ('test-app', 'wx-two', 900001)" >/dev/null 2>&1; then
  echo "One staff account could have two active openids" >&2
  exit 1
fi
query "UPDATE staff_wechat_identity SET status = 'REVOKED', unbound_at = UTC_TIMESTAMP() WHERE app_id = 'test-app' AND openid = 'wx-one'" >/dev/null
query "INSERT INTO staff_wechat_identity (app_id, openid, staff_account_id) VALUES ('test-app', 'wx-one', 900002)" >/dev/null
bindings=$(query "SELECT COUNT(*) FROM staff_wechat_identity WHERE app_id = 'test-app' AND openid = 'wx-one'")
[[ "$bindings" == 2 ]] || { echo "Revoked binding history was not retained" >&2; exit 1; }

for table in brand series model standard_project merchant merchant_project; do
  rows=$(query "SELECT COUNT(*) FROM \`$table\` WHERE id = 900001")
  [[ "$rows" == 1 ]] || { echo "Expected one synthetic row in $table, got $rows" >&2; exit 1; }
done

for entry in operator_account:uk_operator_account experience_card_moderation:uk_card_revision experience_card_moderation:uk_experience_public merchant_application_review:uk_application_revision merchant_region_category_quota:uk_region_category; do
  table="${entry%%:*}"
  index="${entry##*:}"
  found=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = '$table' AND index_name = '$index' AND non_unique = 0")
  [[ "$found" == 1 ]] || { echo "Missing V019/V020 unique index $table.$index" >&2; exit 1; }
done

for entry in merchant_application:revision merchant_application:merchant_id merchant_application:last_reason_code merchant:region_code operator_account:can_onboard; do
  table="${entry%%:*}"
  column="${entry##*:}"
  found=$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '$database' AND table_name = '$table' AND column_name = '$column'")
  [[ "$found" == 1 ]] || { echo "Repeated V020 left $table.$column missing" >&2; exit 1; }
done
for entry in merchant_application:idx_applicant_status merchant_application:idx_merchant_link merchant:idx_region_type_status; do
  table="${entry%%:*}"
  index="${entry##*:}"
  found=$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = '$database' AND table_name = '$table' AND index_name = '$index' AND non_unique = 1")
  [[ "$found" == 1 ]] || { echo "Repeated V020 left $table.$index missing" >&2; exit 1; }
done
for entry in staff_account:display_name staff_account:created_by; do
  table="${entry%%:*}"
  column="${entry##*:}"
  found=$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '$database' AND table_name = '$table' AND column_name = '$column'")
  [[ "$found" == 1 ]] || { echo "Repeated V021 left $table.$column missing" >&2; exit 1; }
done
[[ "$(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='archive_report_share' AND column_name IN ('token_hash','public_id','summary','revoked_at','expires_at','owner_id','vehicle_id')")" == 7 ]] || { echo 'V027 share fields missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='archive_report_share' AND index_name IN ('uk_archive_share_public','uk_archive_share_hash') AND non_unique=0")" == 2 ]] || { echo 'V027 share unique indexes missing' >&2; exit 1; }
[[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='archive_reminder' AND index_name='uk_archive_reminder_owner_vehicle_type' AND non_unique=0")" == 1 ]] || { echo 'V028 reminder uniqueness missing' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM project_model_applicability')" == 0 ]] || { echo 'Applicability must not seed technical rules' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM model_health_policy')" == 0 ]] || { echo 'Must not seed health thresholds' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM health_evidence_confirmation')" == 0 ]] || { echo 'Must not confirm historical observations' >&2; exit 1; }
for table in coupon_source coupon_publication coupon_issue coupon_stock_event; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'Coupons must not promote legacy data or seed production issuance' >&2; exit 1; }
done
[[ "$(query "SELECT COUNT(*) FROM operator_account WHERE can_coupon<>0")" == 0 ]] || { echo 'Coupon permission must not be granted implicitly' >&2; exit 1; }
for entry in coupon_issue:uk_coupon_issue_owner coupon_stock_event:uk_coupon_stock_issue; do
  table="${entry%%:*}"; index="${entry##*:}"
  [[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='$table' AND index_name='$index' AND non_unique=0")" == 1 ]] || { echo 'Coupon uniqueness missing' >&2; exit 1; }
done
for table in order_coupon_allocation coupon_transaction_event coupon_service_authorization coupon_service_redemption; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'V039 must not promote legacy coupon transactions' >&2; exit 1; }
done
for entry in order_coupon_allocation:uk_coupon_active_allocation order_coupon_allocation:uk_coupon_creation_intent coupon_transaction_event:uk_coupon_transaction_event coupon_service_redemption:uk_coupon_service_redemption; do
  table="${entry%%:*}"; index="${entry##*:}"
  [[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='$table' AND index_name='$index' AND non_unique=0")" == 1 ]] || { echo 'V039 coupon uniqueness missing' >&2; exit 1; }
done
for table in invite_code invite_registration invite_phone_evidence; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'V040 must not promote legacy invitations or seed production eligibility' >&2; exit 1; }
done
for entry in invite_code:uk_invite_code invite_code:uk_invite_actor invite_registration:PRIMARY invite_registration:uk_invite_record_id invite_phone_evidence:PRIMARY; do
  table="${entry%%:*}"; index="${entry##*:}"
  [[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='$table' AND index_name='$index' AND non_unique=0")" == 1 ]] || { echo 'V040 invitation uniqueness missing' >&2; exit 1; }
done
echo "MySQL 8.0 schema: 132 tables after V063; repeat migration and synthetic seed passed"

[[ "$(query "SELECT COUNT(*) FROM invite_reward_policy")" == 0 ]] || { echo "Reward policies must not be seeded" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM operator_account WHERE can_invite<>0")" == 0 ]] || { echo "Invitation permission must default off" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM invite_diagnosis_policy")" == 0 ]] || { echo "Diagnosis unlock must not seed thresholds or grant legacy eligibility" >&2; exit 1; }
[[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='invite_diagnosis_policy' AND index_name='uk_invite_diagnosis_active' AND non_unique=0")" == 1 ]] || { echo "Diagnosis active-policy uniqueness missing" >&2; exit 1; }
for table in invite_reward_batch invite_reward_item; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'V042 must not manufacture reward issuance' >&2; exit 1; }
done
[[ "$(query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='coupon_issue' AND index_name='uk_coupon_issue_owner' AND column_name='public_user_id' AND non_unique=0")" == 1 ]] || { echo 'V042 public-only uniqueness missing' >&2; exit 1; }
for entry in coupon_issue:uk_coupon_issue_reward invite_reward_batch:uk_invite_reward_event invite_reward_batch:uk_invite_reward_new_user invite_reward_item:uk_invite_reward_role invite_reward_item:uk_invite_reward_coupon; do
  table="${entry%%:*}"; index="${entry##*:}"
  [[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='$table' AND index_name='$index' AND non_unique=0")" == 1 ]] || { echo 'V042 reward uniqueness missing' >&2; exit 1; }
done

[[ "$(query "SELECT COUNT(*) FROM order_archive_grant")" == 0 ]] || { echo "Must not seed service archive consent" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='order_archive_grant' AND index_name='uk_order_archive_grant' AND non_unique=0")" == 2 ]] || { echo "Per-order record consent uniqueness missing" >&2; exit 1; }

[[ "$(query "SELECT COUNT(*) FROM service_notification_outbox")" == 0 ]] || { echo "Must not manufacture historical notification events" >&2; exit 1; }

[[ "$(query "SELECT COUNT(*) FROM diagnosis_service_policy")" == 0 ]] || { echo "Must not seed diagnosis service policy" >&2; exit 1; }
[[ "$(query "SELECT COUNT(*) FROM operator_account WHERE can_diagnose<>0 OR can_diagnose_manage<>0")" == 0 ]] || { echo "Diagnosis privileges must default off" >&2; exit 1; }

[[ "$(query "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='service_notification_outbox' AND index_name='uk_service_notification_source' AND non_unique=0")" == 3 ]] || { echo "V047 event source uniqueness missing" >&2; exit 1; }

for table in diagnosis_notification_outbox diagnosis_notification_target; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo "Must not backfill historical diagnosis notices" >&2; exit 1; }
done

[[ "$(query 'SELECT COUNT(*) FROM point_task_policy')" == 0 ]] || { echo 'Must not seed point task policies' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM operator_account WHERE can_points<>0')" == 0 ]] || { echo 'Points permission must default off' >&2; exit 1; }
for index in uk_point_policy_submission uk_point_policy_published; do
  [[ "$(query "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='point_task_policy' AND index_name='$index' AND non_unique=0")" == 1 ]] || { echo 'Point policy uniqueness missing' >&2; exit 1; }
done

for table in point_task_event point_verified_account point_verified_entry point_checkin_request; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'Must not manufacture historical point rewards' >&2; exit 1; }
done
[[ "$(query "SELECT COUNT(*) FROM auth_session WHERE point_identity_source<>'UNKNOWN'")" == 0 ]] || { echo 'Must not promote legacy identity sources' >&2; exit 1; }

for table in assessment_month assessment_snapshot assessment_snapshot_fact; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo "Must not backfill historical assessment snapshots" >&2; exit 1; }
done

[[ "$(query 'SELECT COUNT(*) FROM operator_account WHERE can_analytics<>0')" == 0 ]] || { echo 'Analytics permission must default off' >&2; exit 1; }

[[ "$(query 'SELECT COUNT(*) FROM price_monitor_policy')" == 0 ]] || { echo 'Must not seed price ranges' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM operator_account WHERE can_price_monitor<>0')" == 0 ]] || { echo 'Price monitor permission must default off' >&2; exit 1; }

[[ "$(query 'SELECT COUNT(*) FROM experience_curation')" == 0 ]] || { echo 'Must not seed curation' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM operator_account WHERE can_community_curate<>0')" == 0 ]] || { echo 'Curation permission must default off' >&2; exit 1; }

for table in community_qa_service_policy community_qa_case community_qa_message community_qa_case_event; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo 'Must not seed private community content or admission' >&2; exit 1; }
done
[[ "$(query 'SELECT COUNT(*) FROM operator_account WHERE can_community_answer<>0 OR can_community_manage<>0')" == 0 ]] || { echo 'Community privileges must default off' >&2; exit 1; }
[[ "$(query 'SELECT COUNT(*) FROM community_qa_control WHERE id=1')" == 1 ]] || { echo 'Community serialization control missing' >&2; exit 1; }

for table in community_qa_publication community_qa_publication_event; do
  [[ "$(query "SELECT COUNT(*) FROM $table")" == 0 ]] || { echo "Must not seed public QA or consent" >&2; exit 1; }
done
