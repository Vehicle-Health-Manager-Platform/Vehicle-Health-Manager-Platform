#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

compose=(docker compose --env-file .env.example -f deploy/compose/compose.yml \
  -f deploy/compose/ci.override.yml -p s0smoke)
cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" config --quiet
startup_log=$(mktemp)
if ! "${compose[@]}" up --detach --build mysql redis rabbitmq minio ocr backend nginx >"$startup_log" 2>&1; then
  tail -n 80 "$startup_log"
  summary=$(grep -Ei 'curl:|sha256sum:|failed|error:|denied|temporary failure|unable to select' "$startup_log" \
    | head -n 6 | tr '\n' ' ' | cut -c 1-1800 || true)
  [[ -n "$summary" ]] || summary=$(tail -n 8 "$startup_log" | tr '\n' ' ' | cut -c 1-1800)
  echo "::error title=Compose startup::$summary"
  "${compose[@]}" ps --all
  "${compose[@]}" logs --tail=80
  exit 1
fi
rm "$startup_log"

# A green startup must include the current business schema, not just a live MySQL process.
schema_count=$("${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute "$1"' sh "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='payment' AND column_name IN ('query_token','next_query_at','query_attempts','last_query_at','last_query_error')) OR (table_name='service_archive_job' AND column_name='review_id' AND is_nullable='YES') OR (table_name='service_archive_job' AND column_name='card_requested') OR (table_name='operator_account' AND column_name='can_catalog') OR (table_name='refund' AND column_name IN ('refund_no','controlled_order_id','dispatch_token')) OR (table_name='refund_event' AND column_name='event_hash') OR (table_name='merchant_commission_policy' AND column_name='rule_version') OR (table_name='order_commission_snapshot' AND column_name='commission_amount') OR (table_name='merchant_commission_entry' AND column_name='event_key'))")
[[ "$schema_count" == 15 ]] || { echo "::error title=Business schema::Compose initialization missed V022-V026 fields"; exit 1; }
table_count=$("${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute "$1"' sh "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")
[[ "$table_count" == 95 ]] || { echo "::error title=Business schema::Expected V001-V043 95 tables, got $table_count"; exit 1; }
invite_count=$("${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute "$1"' sh "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('invite_code','invite_registration','invite_phone_evidence')")
[[ "$invite_count" == 3 ]] || { echo "::error title=Invitation schema::Compose initialization missed V040 invitation evidence"; exit 1; }
reward_count=$("${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute "$1"' sh "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('invite_reward_policy','invite_reward_batch','invite_reward_item','invite_diagnosis_policy')")
[[ "$reward_count" == 4 ]] || { echo "::error title=Invitation reward schema::Compose initialization missed V041-V043 reward/eligibility tables"; exit 1; }

for port in 8081 8082 8083 8084; do
  response=''
  ready=false
  for attempt in {1..15}; do
    if response=$(curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:$port/" 2>/dev/null); then
      ready=true
      break
    fi
    sleep 2
  done
  if [[ "$ready" != true ]]; then
    echo "::error title=Web entry::Port $port did not return HTTP 200 after 15 attempts"
    "${compose[@]}" ps --all
    "${compose[@]}" logs --tail=40 nginx backend
    exit 1
  fi
  if [[ "$response" != *'<html'* ]]; then
    echo "::error title=Web entry::Port $port did not return HTML"
    echo "Expected an HTML page on port $port" >&2
    exit 1
  fi
done

status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8081/api/demo/vehicles/1001)
[[ "$status" == 401 ]] || {
  echo "::error title=API protection::Expected HTTP 401, got $status"
  exit 1
}

login=$(curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data '{"user_id":"1001"}' \
  http://127.0.0.1:8081/api/dev/token)
token=$(printf '%s' "$login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["data"]["access_token"])')

own=$(curl --fail --silent --show-error \
  --header "Authorization: Bearer $token" \
  http://127.0.0.1:8081/api/demo/vehicles/1001)
printf '%s' "$own" | python3 -c \
  'import json,sys; value=json.load(sys.stdin); assert value["code"] == 0 and value["data"]["owner_id"] == 1001'

denied_body=$(mktemp)
denied_status=$(curl --silent --output "$denied_body" --write-out '%{http_code}' \
  --header "Authorization: Bearer $token" \
  http://127.0.0.1:8081/api/demo/vehicles/2001)
[[ "$denied_status" == 403 ]] || {
  echo "::error title=API ownership::Expected HTTP 403, got $denied_status"
  exit 1
}
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["code"] == 40300' "$denied_body"
rm "$denied_body"

echo "Compose smoke: current V001-V043 95-table schema including invitation evidence, four compatibility web entries, anonymous 401, owner 200, cross-owner 40300 passed"
