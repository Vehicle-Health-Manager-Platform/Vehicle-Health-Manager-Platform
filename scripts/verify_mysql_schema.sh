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
run_sql_file docs/sql/seed_test.sql
run_sql_file docs/sql/seed_test.sql

tables=$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$database' AND table_type = 'BASE TABLE'")
[[ "$tables" == 39 ]] || { echo "Expected 39 tables, got $tables" >&2; exit 1; }

for table in brand series model standard_project merchant merchant_project; do
  rows=$(query "SELECT COUNT(*) FROM \`$table\` WHERE id = 900001")
  [[ "$rows" == 1 ]] || { echo "Expected one synthetic row in $table, got $rows" >&2; exit 1; }
done

echo "MySQL 8.0 schema: 39 tables; repeat migration and synthetic seed passed"
