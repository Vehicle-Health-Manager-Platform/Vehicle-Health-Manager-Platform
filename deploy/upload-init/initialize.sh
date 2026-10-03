#!/bin/sh
set -eu
umask 077
# Local administrative operations must not wait indefinitely on a broken endpoint.
mc() { timeout -k 2 10 /usr/local/bin/mc "$@"; }
for value in "${MINIO_ENDPOINT:-}" "${MINIO_ROOT_USER:-}" "${MINIO_ROOT_PASSWORD:-}" \
  "${UPLOAD_MINIO_BUCKET:-}" "${UPLOAD_MINIO_ACCESS_KEY:-}" "${UPLOAD_MINIO_SECRET_KEY:-}"; do
  case "$value" in ''|change-me*|unconfigured) echo 'Incomplete private upload initialization configuration' >&2; exit 1;; esac
done
case "$UPLOAD_MINIO_BUCKET" in *[!a-z0-9-]*|'') echo 'Invalid upload bucket' >&2; exit 1;; esac
case "$UPLOAD_MINIO_ACCESS_KEY" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid upload account name' >&2; exit 1;; esac
if [ "$UPLOAD_MINIO_ACCESS_KEY" = "$MINIO_ROOT_USER" ]; then echo 'Upload account must be separate from root' >&2; exit 1; fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export MC_CONFIG_DIR="$work/client"
mc alias set admin "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
# Read-only preflight. Existing identities/policies require explicit operator review, never overwrite.
mc admin user list --json admin >"$work/users" 2>/dev/null
mc admin policy list --json admin >"$work/policies" 2>/dev/null
policy="upload-$UPLOAD_MINIO_BUCKET"
if ! jq -se --arg value "$UPLOAD_MINIO_ACCESS_KEY" '[.[] | .. | strings | select(. == $value)] | length == 0' "$work/users" >/dev/null \
  || ! jq -se --arg value "$policy" '[.[] | .. | strings | select(. == $value)] | length == 0' "$work/policies" >/dev/null; then
  echo 'Upload account or policy already exists; review existing provisioning manually' >&2; exit 1
fi
mc mb --ignore-existing "admin/$UPLOAD_MINIO_BUCKET" >/dev/null 2>&1
if ! mc anonymous get "admin/$UPLOAD_MINIO_BUCKET" 2>/dev/null | grep -q 'is `private`'; then
  echo 'Upload bucket is not private; refusing to change its policy' >&2; exit 1
fi
cat >"$work/policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
{"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket","s3:GetBucketPolicy"],"Resource":["arn:aws:s3:::$UPLOAD_MINIO_BUCKET"]},
{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::$UPLOAD_MINIO_BUCKET/uploads/*"]}]}
EOF
mc admin policy create admin "$policy" "$work/policy.json" >/dev/null 2>&1
mc admin user add admin "$UPLOAD_MINIO_ACCESS_KEY" "$UPLOAD_MINIO_SECRET_KEY" >/dev/null 2>&1
mc admin policy attach admin "$policy" --user "$UPLOAD_MINIO_ACCESS_KEY" >/dev/null 2>&1
echo 'Private upload bucket and dedicated account initialized'
