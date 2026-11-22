#!/bin/sh
# Puts the API key (from the KONG_API_KEY env var, a Secret Manager secret) into the
# declarative config, then starts Kong.
set -e
if [ -z "$KONG_API_KEY" ]; then
  echo "KONG_API_KEY is not set" >&2
  exit 1
fi
sed "s|\${KONG_API_KEY}|$KONG_API_KEY|" /kong/kong.yml > /tmp/kong.yml
exec /docker-entrypoint.sh kong docker-start
