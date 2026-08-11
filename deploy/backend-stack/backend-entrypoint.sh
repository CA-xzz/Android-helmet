#!/bin/sh

set -eu
umask 077

HELMET_DATA_DIR=${HELMET_DATA_DIR:-/var/lib/helmet}
HELMET_AUTH_SECRET_FILE=${HELMET_AUTH_SECRET_FILE:-/run/secrets/helmet_auth_config}
HELMET_POSTGRES_PASSWORD_FILE=${HELMET_POSTGRES_PASSWORD_FILE:-/run/secrets/postgres_password}
HELMET_S3_ACCESS_KEY_FILE=${HELMET_S3_ACCESS_KEY_FILE:-/run/secrets/s3_access_key}
HELMET_S3_SECRET_KEY_FILE=${HELMET_S3_SECRET_KEY_FILE:-/run/secrets/s3_secret_key}
HELMET_TURN_SECRET_FILE=${HELMET_TURN_SECRET_FILE:-/run/secrets/turn_shared_secret}
export HELMET_POSTGRES_PASSWORD_FILE HELMET_S3_ACCESS_KEY_FILE \
    HELMET_S3_SECRET_KEY_FILE HELMET_TURN_SECRET_FILE

install -d -m 0700 "$HELMET_DATA_DIR/runtime"
if ! python3 -m backend.private_file materialize \
    "$HELMET_AUTH_SECRET_FILE" \
    "$HELMET_DATA_DIR/runtime/auth-config.json" \
    --maximum-bytes 65536; then
    echo "invalid deployment secret: $HELMET_AUTH_SECRET_FILE" >&2
    exit 78
fi

exec python3 -m backend.media_service \
    --production \
    --host 0.0.0.0 \
    --port 18080 \
    --data-dir "$HELMET_DATA_DIR" \
    --auth-config "$HELMET_DATA_DIR/runtime/auth-config.json" \
    --object-store s3
