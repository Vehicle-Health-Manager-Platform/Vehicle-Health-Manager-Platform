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

tables=$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$database' AND table_type = 'BASE TABLE'")
[[ "$tables" == 59 ]] || { echo "Expected 59 tables after V018, got $tables" >&2; exit 1; }

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

echo "MySQL 8.0 schema: 59 tables after V018; repeat migration and synthetic seed passed"
