#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Prepares a person on the real Ostrom server ostrom-server's app-journey workflow started, as the console would: an e-mail sign-in
# (the link read from the directory the server writes its mails to), an organization of their own, and a personal
# token the E2E build signs in with (no GitHub on the runner). Masks the token in the job's log, and writes `token=` and
# `login=` to $GITHUB_OUTPUT (standard output outside Actions): workflow commands only work on the step's own output.
# Usage: scripts/real-server-session.sh <server origin> <mail directory>
set -euo pipefail

server="$1"
mail_dir="$2"
email="app-e2e-$(date +%s)-$RANDOM@e2e.ostrom.test"
json=(-H "content-type: application/json" -H "accept: application/json")

curl -fsS "${json[@]}" -X POST "$server/api/v1/auth/email" -d "{\"email\":\"$email\"}" > /dev/null

# The server writes `To: …`, `Subject: …`, a blank line, the text with the link `…#token=<token>`.
link_token=""
for _ in $(seq 1 30); do
    mail="$(grep -rlix "to: $email" "$mail_dir" 2> /dev/null | head -n 1 || true)"
    if [ -n "$mail" ]; then
        link_token="$(grep -oE '#token=[A-Za-z0-9._~%-]+' "$mail" | head -n 1 | cut -d= -f2- || true)"
        [ -n "$link_token" ] && break
    fi
    sleep 1
done
if [ -z "$link_token" ]; then
    echo "::error::No sign-in mail for $email in $mail_dir"
    exit 1
fi
link_token="$(printf '%b' "${link_token//%/\\x}")"

# The session is in the cookies of the answer (`__Host-` cookies: read from the headers, sent back by hand).
headers="$(curl -fsS "${json[@]}" -D - -o /dev/null -X POST "$server/api/v1/auth/email/verify" -d "{\"token\":\"$link_token\"}")"
cookie_value() {
    printf '%s\n' "$headers" | tr -d '\r' | grep -i "^set-cookie: \(__Host-\)\?$1=" | head -n 1 | sed -E "s/^[^:]+: *([^=]+)=([^;]*).*/\\1=\\2/" || true
}
session="$(cookie_value ostrom_session)"
csrf_cookie="$(cookie_value ostrom_csrf)"
if [ -z "$session" ] || [ -z "$csrf_cookie" ]; then
    echo "::error::The e-mail sign-in gave no session cookie"
    exit 1
fi
browser=(-H "cookie: $session; $csrf_cookie" -H "x-csrf-token: ${csrf_cookie#*=}")

curl -fsS "${json[@]}" "${browser[@]}" -X POST "$server/api/v1/orgs" -d '{"name":"acme-e2e"}' > /dev/null
created="$(curl -fsS "${json[@]}" "${browser[@]}" -X POST "$server/api/v1/tokens" \
    -d '{"name":"ostrom-app e2e","expiresInDays":1,"scopes":["profile:read","orgs:read","gates:read","receipts:read","usage:read"]}')"
token="$(printf '%s' "$created" | jq -er '.secret')"
login="$(curl -fsS -H "accept: application/json" -H "authorization: Bearer $token" "$server/api/v1/auth/me" | jq -er '.login')"

echo "::add-mask::$token"
printf 'token=%s\nlogin=%s\n' "$token" "$login" >> "${GITHUB_OUTPUT:-/dev/stdout}"
