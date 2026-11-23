#!/bin/sh
# Puts the consumer API keys (env vars from Secret Manager secrets) into the
# declarative config, then starts Kong. To add a consumer, add it to kong.yml,
# add its env var name to KEY_VARS, and pass its secret with --set-secrets.
set -e
KEY_VARS="KONG_API_KEY KONG_API_KEY_WEB KONG_API_KEY_DESKTOP"

cp /kong/kong.yml /tmp/kong.yml
for var in $KEY_VARS; do
  value=$(printenv "$var" || true)
  if [ -z "$value" ]; then
    echo "$var is not set" >&2
    exit 1
  fi
  sed -i "s|\${$var}|$value|" /tmp/kong.yml
done
exec /docker-entrypoint.sh kong docker-start
