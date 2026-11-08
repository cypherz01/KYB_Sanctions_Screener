#!/usr/bin/env bash
#
# The keyless demo: starts the service with synthetic data and stubbed upstreams, then walks through
# what it does. Needs Docker, curl and bash (on Windows, run it from Git Bash).
#
#   scripts/demo.sh              start the demo stack, then run the walkthrough
#   scripts/demo.sh --no-start   run the walkthrough against a service already running, at
#                                KYB_DEMO_URL with key KYB_DEMO_API_KEY
#
# Deliberately needs no jq: a newcomer should not have to install anything to see this work. The
# values read here come first in, or are unique to, the service's compact JSON.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE_FILE=docker-compose.demo.yml
URL="${KYB_DEMO_URL:-http://localhost:${KYB_DEMO_PORT:-8090}}"
KEY="${KYB_DEMO_API_KEY:-demo-key}"

api_get() {
    curl -sS --fail-with-body -H "X-Api-Key: $KEY" "$URL$1"
}

# Through stdin, so non-Latin names reach curl as UTF-8 whatever the platform does to arguments.
api_post() {
    printf '%s' "$2" | curl -sS --fail-with-body -X POST -H "X-Api-Key: $KEY" \
        -H 'Content-Type: application/json' --data-binary @- "$URL$1"
}

# First value of a string field; sed rather than head, so nothing upstream dies of SIGPIPE.
text_field() {
    grep -o "\"$1\":\"[^\"]*\"" | sed -n 1p | cut -d'"' -f4
}

number_field() {
    grep -o "\"$1\":[0-9.]*" | sed -n 1p | cut -d: -f2
}

all_values() {
    grep -o "\"$1\":\"[^\"]*\"" | cut -d'"' -f4 | paste -sd' ' -
}

heading() {
    printf '\n== %s\n' "$1"
}

# One company: shows the decision and why, and stops if it is not the decision the data should give.
check() {
    local number="$1" expected="$2" story="$3" body decision
    heading "$story"
    body="$(api_post /api/v1/checks "{\"companyNumber\":\"$number\"}")"
    decision="$(printf '%s' "$body" | text_field decision)"
    printf '%s (%s)\n' "$(printf '%s' "$body" | text_field companyName)" "$number"
    printf 'decision: %s, score: %s\n' "$decision" "$(printf '%s' "$body" | number_field score)"
    # Only the factors: the same field names recur under subjects and regulatory.
    printf 'factors:  %s\n' "$(printf '%s' "$body" | sed 's/"notes":.*//' | all_values code)"
    if printf '%s' "$body" | grep -q '"autoFail":true'; then
        echo "automatic: a strong sanctions match fails a company whatever its score"
    fi
    if [ "$decision" != "$expected" ]; then
        printf 'Expected %s. The demo data and the rules disagree.\n' "$expected" >&2
        exit 1
    fi
    LAST_BODY="$body"
}

if [ "${1:-}" != "--no-start" ]; then
    echo "Starting the demo stack. The first run builds the image, which takes a few minutes..."
    docker compose -f "$COMPOSE_FILE" up -d --build --wait
fi

echo
echo "All the data in this demo is synthetic: invented companies and an invented sanctions list."
echo "Nothing here says anything about any real person or company."

# Health is UP while the first import runs, and a check without sanctions data is refused.
for _ in $(seq 1 60); do
    if api_get /api/v1/admin/sanctions/status | grep -q '"active":{'; then
        break
    fi
    sleep 2
done
api_get /api/v1/admin/sanctions/status | grep -q '"active":{' || {
    echo "The sanctions data did not load. Check: docker compose -f $COMPOSE_FILE logs app" >&2
    exit 1
}

check 07654321 PASS "A clean company passes"

check 12345678 REVIEW "A matching clone-firm warning on the FCA Register sends a company to a person"

check 01234567 FAIL "A sanctioned director fails a company automatically"
SANCTIONED_ID="$(printf '%s' "$LAST_BODY" | text_field checkId)"
echo "why the match was made:"
printf '%s' "$LAST_BODY" \
    | grep -o '"evidence":\[[^]]*\]' | sed -n 1p \
    | sed 's/^"evidence":\["//; s/"\]$//' \
    | awk -F'","' '{ for (i = 1; i <= NF; i++) print "  - " $i }'

heading "The same person in Cyrillic is found too, through transliteration"
NAME="Иван Петров"
SCREEN="$(api_post /api/v1/screen/name "{\"name\":\"$NAME\",\"type\":\"PERSON\",\"birthDate\":\"1971-03\",\"country\":\"RU\"}")"
printf '%s matches %s: %s, similarity %s\n' "$NAME" \
    "$(printf '%s' "$SCREEN" | text_field caption)" \
    "$(printf '%s' "$SCREEN" | text_field band)" \
    "$(printf '%s' "$SCREEN" | number_field similarity)"

heading "Any past decision can be reproduced from what it stored, with no external call"
REPLAY="$(api_post "/api/v1/checks/$SANCTIONED_ID/replay" '')"
if printf '%s' "$REPLAY" | grep -q '"identical":true'; then
    echo "replay of the failed check: identical: true"
else
    echo "The replay differed from the stored result: $REPLAY" >&2
    exit 1
fi

heading "And everything that happened to it is on record"
printf 'events: %s\n' "$(api_get "/api/v1/checks/$SANCTIONED_ID/audit" | all_values eventType)"

echo
echo "Explore the API at $URL/swagger-ui.html (authorise with the key: $KEY)"
if [ "${1:-}" != "--no-start" ]; then
    echo "Stop the demo with: docker compose -f $COMPOSE_FILE down"
fi
