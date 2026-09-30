#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

compose=(docker compose --env-file .env.example -f deploy/compose/compose.yml -p s0smoke)
cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" config --quiet
if ! "${compose[@]}" up --detach --build mysql redis rabbitmq minio ocr backend nginx; then
  "${compose[@]}" ps --all
  "${compose[@]}" logs --tail=80
  exit 1
fi

for port in 8081 8082 8083 8084; do
  response=$(curl --fail --silent --show-error "http://127.0.0.1:$port/")
  if [[ "$response" != *'<html'* ]]; then
    echo "Expected an HTML page on port $port" >&2
    exit 1
  fi
done

status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8081/api/demo/vehicles/1001)
[[ "$status" == 401 ]] || { echo "Expected protected API to return 401, got $status" >&2; exit 1; }

echo "Compose smoke: four web entries returned HTML; protected API returned 401"
