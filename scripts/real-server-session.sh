#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Prepares the real Ostrom server ostrom-server's app-journey workflow started, through its own routes, for the app's
# journey: two requests of a push to main, pending, that the phone's person answers in the app (one yes, one no).
# - The phone's person signs in with GitHub (the demo stack's fake GitHub, ostrom-helm compose/demo/fake-github.mjs):
#   once in the browser's way, to own the organization "acme-e2e", invite the asker and write the workflow that names
#   them approver of pushes to main; once in the app's way (PKCE S256 to `ostrom://auth/callback`, then
#   `POST /auth/mobile/token`), for the phone's session and its unlock key. The app's own browser hop is not scripted:
#   the E2E build is given that session.
# - The asker signs in by e-mail (the link read from the directory the server writes its mails to), accepts the
#   invitation, and asks the two gates with a token of their own (`gates:ask`), which the job also reads them with.
# Masks the secrets in the job's log, and writes `token`, `unlock_key`, `login`, `yes_approval`, `no_approval`,
# `yes_gate`, `no_gate`, `asker_token` and `repo` to $GITHUB_OUTPUT (standard output outside Actions).
# Usage: scripts/real-server-session.sh <server origin> <mail directory> <fake GitHub origin>
# Linux only (bash 4.4 or later, GNU coreutils), as on the CI runner. A refused call prints the server's answer.
set -euo pipefail
# A failure inside `$(…)` stops the script too (bash resets -e there otherwise).
shopt -s inherit_errexit

server="$1"
mail_dir="$2"
github="$3"
run="$(date +%s)$RANDOM"
repo="acme/app-e2e-$run"
login="app-owner-$run"
asker_email="app-asker-$run@a$run.e2e.ostrom.test"
redirect_uri="ostrom://auth/callback"
json=(-H "content-type: application/json" -H "accept: application/json")

# On standard error: a failure inside `$(…)` must reach the log, not the captured value.
fail() {
    echo "::error::$1" >&2
    exit 1
}

# A call to the server: its body, or the call's address, status and the server's answer on standard error (never the
# headers, which carry the credentials).
api() {
    local out code url arg
    for arg in "$@"; do case "$arg" in http*) url="$arg" ;; esac; done
    out="$(curl -sS -w '\n%{http_code}' "$@")"
    code="${out##*$'\n'}"
    out="${out%$'\n'*}"
    if [ "$code" -ge 400 ]; then
        echo "::error::${url%%\?*} → $code $out" >&2
        exit 1
    fi
    printf '%s' "$out"
}

# The response headers of a request, without following a redirect (curl fails only on a transport error).
headers() {
    curl -sS -D - -o /dev/null "$@" | tr -d '\r'
}
location() {
    printf '%s\n' "$1" | grep -i '^location: ' | head -n 1 | cut -d' ' -f2- || true
}
status() {
    printf '%s\n' "$1" | head -n 1 | cut -d' ' -f2
}
# Every cookie a response sets, as a `cookie` header value (`name=value; …`): read from the headers and sent back by
# hand, as `__Host-` cookies on plain HTTP would be dropped by a cookie jar.
cookies() {
    printf '%s\n' "$1" | grep -i '^set-cookie: ' | sed -E 's/^[^:]+: *([^;]*).*/\1/' | paste -sd ';' - | sed 's/;/; /g' || true
}
cookie_value() {
    printf '%s\n' "$1" | grep -i "^set-cookie: \(__Host-\)\?$2=" | head -n 1 | sed -E 's/^[^:]+: *[^=]+=([^;]*).*/\1/' || true
}

# GitHub's consent through the fake: start on the server (query $1), pick the login on the authorize page, come back
# to the server's callback with the start's cookies. Prints the callback's response headers.
github_sign_in() {
    local start authorize back callback
    start="$(headers "$server/api/v1/auth/github/start?$1")"
    authorize="$(location "$start")"
    case "$authorize" in "$github"/login/oauth/authorize\?*) ;; *) fail "GitHub sign-in start → $(status "$start") ${authorize:-no redirect}" ;; esac
    back="$(headers -X POST "$github/login/oauth/authorize" --data "${authorize#*\?}&login=$login")"
    callback="$(location "$back")"
    case "$callback" in "$server"/*) ;; *) fail "fake GitHub authorize → $(status "$back") ${callback:-no redirect}" ;; esac
    headers -H "cookie: $(cookies "$start")" "$callback"
}

# The newest token of a link `<page>#token=` mailed to $1 (the server writes `To: …`, `Subject: …`, a blank line, the
# text), waiting for the mail.
mailed_token() {
    local mail token
    for _ in $(seq 1 30); do
        for mail in $(grep -rlix "to: $1" "$mail_dir" 2> /dev/null | xargs -r ls -t || true); do
            token="$(grep -oE "$2#token=[A-Za-z0-9._~%-]+" "$mail" | head -n 1 | cut -d= -f2- || true)"
            if [ -n "$token" ]; then
                printf '%b' "${token//%/\\x}"
                return
            fi
        done
        sleep 1
    done
    fail "No mail with a $2 link for $1 in $mail_dir"
}

# A browser session from a response that set it: the session and CSRF cookies, the CSRF header, the console's origin.
browser_of() {
    local session csrf
    session="$(cookie_value "$1" ostrom_session)"
    csrf="$(cookie_value "$1" ostrom_csrf)"
    [ -n "$session" ] && [ -n "$csrf" ] || fail "$2 gave no session cookie"
    printf '%s\n' "cookie: $(printf '%s\n' "$1" | grep -iE '^set-cookie: (__Host-)?ostrom_(session|csrf)=' | sed -E 's/^[^:]+: *([^;]*).*/\1/' | paste -sd ';' - | sed 's/;/; /g')" "x-csrf-token: $csrf" "origin: $server"
}
# The `-H` arguments of the header lines in $1.
header_args() {
    local line
    while IFS= read -r line; do printf '%s\0%s\0' -H "$line"; done <<< "$1"
}

# Each step is assigned before it is used: `set -e` stops on a failed `$(…)` only in an assignment.
# The phone's person, in the browser: owner of the organization.
signed="$(github_sign_in "redirect=/")"
lines="$(browser_of "$signed" "The GitHub sign-in in the browser")"
mapfile -d '' -t owner < <(header_args "$lines")
org="$(api "${json[@]}" "${owner[@]}" -X POST "$server/api/v1/orgs" -d '{"name":"acme-e2e"}' | jq -er '.id')"
api "${json[@]}" "${owner[@]}" -X POST "$server/api/v1/orgs/$org/members/invites" -d "{\"invites\":[{\"email\":\"$asker_email\",\"role\":\"member\"}]}" > /dev/null

# The asker: e-mail sign-in, the invitation accepted, a token that asks and reads gates and reads receipts.
api "${json[@]}" -X POST "$server/api/v1/auth/email" -d "{\"email\":\"$asker_email\"}" > /dev/null
link="$(mailed_token "$asker_email" "/auth/email")"
signed="$(headers "${json[@]}" -X POST "$server/api/v1/auth/email/verify" -d "{\"token\":\"$link\"}")"
lines="$(browser_of "$signed" "The e-mail sign-in")"
mapfile -d '' -t asker < <(header_args "$lines")
invitation="$(mailed_token "$asker_email" "/invite")"
joined="$(api "${json[@]}" "${asker[@]}" -X POST "$server/api/v1/invitations/accept" -d "{\"token\":\"$invitation\"}" | jq -r '.id')"
[ "$joined" = "$org" ] || fail "The asker joined $joined, not $org"
asker_token="$(api "${json[@]}" "${asker[@]}" -X POST "$server/api/v1/tokens" \
    -d '{"name":"app e2e asker","expiresInDays":1,"scopes":["gates:ask","gates:read","receipts:read"]}' | jq -er '.secret')"
echo "::add-mask::$asker_token"

# The workflow: a push to main of the repository waits for the phone's person.
workflow="$(jq -nc --arg id "app-e2e-$run" --arg login "$login" --arg repo "$repo" --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{id: $id, name: "App journey", domain: "delivery", status: "accepted", foundAt: $at, edges: [],
      nodes: [{id: "push-main", kind: "gate", label: "Push to main", approvers: [$login],
               actions: [{class: "push-main", label: "Push to the main branch", patterns: [], repos: [$repo]}]}]}')"
api "${json[@]}" "${owner[@]}" -X POST "$server/api/v1/workflows" -d "$workflow" > /dev/null

# Two pushes to main, asked by the asker: one for the yes, one for the no.
ask() {
    local sha
    sha="$(printf '%s' "$run-$1" | sha1sum | cut -d' ' -f1)"
    api "${json[@]}" -H "authorization: Bearer $asker_token" -X POST "$server/api/v1/gates" \
        -d "{\"repo\":\"$repo\",\"class\":\"git.push\",\"tool\":\"Bash\",\"command\":\"git push origin main\",\"push\":{\"remote\":\"origin\",\"refs\":[{\"ref\":\"refs/heads/main\",\"sha\":\"$sha\"}]}}" |
        jq -er '.gate.id'
}
yes_gate="$(ask yes)"
no_gate="$(ask no)"
pending="$(api "${json[@]}" "${owner[@]}" "$server/api/v1/approvals?mine=true&status=pending&limit=50")"
approval_of() {
    printf '%s' "$pending" | jq -er --arg gate "$1" '[.items[] | select(.group == $gate)][0].id'
}
yes_approval="$(approval_of "$yes_gate")" || fail "No pending approval of the phone's person on gate $yes_gate: $pending"
no_approval="$(approval_of "$no_gate")" || fail "No pending approval of the phone's person on gate $no_gate: $pending"

# The phone's session: the app's sign-in (PKCE S256 to its deep link), the code exchanged for the token and unlock key.
verifier="$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n')"
challenge="$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n')"
state="state$run"
signed="$(github_sign_in "redirect_uri=ostrom%3A%2F%2Fauth%2Fcallback&state=$state&code_challenge=$challenge&code_challenge_method=S256")"
back="$(location "$signed")"
case "$back" in "$redirect_uri?"*"state=$state"*) ;; *) fail "The app's sign-in came back to ${back:-nothing}" ;; esac
code="$(printf '%s' "${back#*\?}" | tr '&' '\n' | grep '^code=' | cut -d= -f2- || true)"
[ -n "$code" ] || fail "The app's sign-in came back without a code"
mobile="$(api "${json[@]}" -X POST "$server/api/v1/auth/mobile/token" \
    -d "$(jq -nc --arg code "$code" --arg verifier "$verifier" --arg uri "$redirect_uri" '{code: $code, codeVerifier: $verifier, redirectUri: $uri}')")"
token="$(printf '%s' "$mobile" | jq -er '.token')"
unlock_key="$(printf '%s' "$mobile" | jq -er '.unlockKey')"
signed_in="$(printf '%s' "$mobile" | jq -er '.user.login')"
[ "$signed_in" = "$login" ] || fail "The phone signed in as $signed_in, not $login"
echo "::add-mask::$token"
echo "::add-mask::$unlock_key"

{
    echo "token=$token"
    echo "unlock_key=$unlock_key"
    echo "login=$login"
    echo "repo=$repo"
    echo "yes_approval=$yes_approval"
    echo "no_approval=$no_approval"
    echo "yes_gate=$yes_gate"
    echo "no_gate=$no_gate"
    echo "asker_token=$asker_token"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
