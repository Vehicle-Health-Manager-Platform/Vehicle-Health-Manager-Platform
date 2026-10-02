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

echo "Compose smoke: four web entries, anonymous 401, owner 200, cross-owner 40300 passed"
