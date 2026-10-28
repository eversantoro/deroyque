#!/usr/bin/env bash
# Gera um JWT HS256 para o perfil "local" (desenvolvimento). Uso:
#   scripts/dev-token.sh [client_id] ["escopos separados por espaco"]
# Exemplo: TOKEN=$(scripts/dev-token.sh sistema-a "requests:read requests:write")
set -euo pipefail

client_id="${1:-sistema-a}"
scopes="${2:-requests:read requests:write}"
secret="${JWT_LOCAL_SECRET:-local-development-secret-change-me-0123456789}"
issuer="${JWT_ISSUER:-http://localhost/local-issuer}"
audience="${JWT_AUDIENCE:-activation-service}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"%s","aud":["%s"],"sub":"%s","client_id":"%s","scope":"%s","iat":%s,"exp":%s}' \
  "$issuer" "$audience" "$client_id" "$client_id" "$scopes" "$now" "$((now + 3600))" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
