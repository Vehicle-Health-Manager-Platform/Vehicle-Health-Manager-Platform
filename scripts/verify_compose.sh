#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

compose=(docker compose --env-file .env.example -f deploy/compose/compose.yml -p s0smoke)
cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" config --quiet
startup_log=$(mktemp)
if ! "${compose[@]}" up --detach --build mysql redis rabbitmq minio ocr backend nginx >"$startup_log" 2>&1; then
  tail -n 80 "$startup_log"
  summary=$(tail -n 8 "$startup_log" | tr '\n' ' ' | cut -c 1-1800)
  echo "::error title=Compose startup::$summary"
  "${compose[@]}" ps --all
  "${compose[@]}" logs --tail=80
  exit 1
fi
rm "$startup_log"

for port in 8081 8082 8083 8084; do
  if ! response=$(curl --fail --silent --show-error "http://127.0.0.1:$port/"); then
    echo "::error title=Web entry::Port $port did not return HTTP 200"
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

echo "Compose smoke: four web entries returned HTML; protected API returned 401"
