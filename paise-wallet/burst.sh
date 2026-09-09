#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?Usage: $0 <BASE_URL>}"
C=50

json() { python3 -c "import sys,json; print(json.load(sys.stdin)[\"$1\"])"; }

new_user() {
  printf 'user_%s' "$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
}

tok() { curl -sf "${BASE_URL}/dev/token?user=$1" | json token; }

bal() { curl -sf "${BASE_URL}/accounts/me" -H "Authorization: Bearer $1" | json balance_paise; }

say() { printf '%s\n' "$*"; }

# -------------------------------------------------------------------------
say "=== Paise Wallet Burst Test ==="
say "BASE_URL : $BASE_URL"
say "CONCURRENCY: ${C}"
say ""

A=$(new_user); B=$(new_user)
say "USER_A = $A"
say "USER_B = $B"

TA=$(tok "$A"); TB=$(tok "$B")
say "[ok] obtained JWT for A and B"
pushd=$(mktemp -d)

# Create wallets
curl -sf -X POST "$BASE_URL/accounts" -H "Authorization: Bearer $TA" >/dev/null
curl -sf -X POST "$BASE_URL/accounts" -H "Authorization: Bearer $TB" >/dev/null
say "[ok] created wallets A, B"

# Fund A with 1_000_000 paise
curl -sf -X POST "$BASE_URL/dev/fund" \
  -H "Authorization: Bearer $TA" \
  -H "Content-Type: application/json" \
  -d "{\"user_id\":\"$A\",\"amount_paise\":1000000}" | json balance_paise >/dev/null
say "[ok] funded A with 1,000,000 paise (Rs 10,000)"

FAIL=0
report_fail() { say "FAIL: $1"; FAIL=1; }

# --- 1) 50 concurrent FIRST-transfers (distinct keys), A->B of 100 paise -----
say ""
say "=== Test 1: ${C} concurrent first-transfers (distinct keys) ==="
for i in $(seq 1 $C); do
  curl -sf -o "$pushd/resp_$i.json" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TA" -H "Content-Type: application/json" \
    -d "{\"to_user\":\"$B\",\"amount_paise\":100,\"idempotency_key\":\"first_${i}\"}" > "$pushd/code_$i" &
done
wait

NON200=0
for i in $(seq 1 $C); do
  [ "$(cat "$pushd/code_$i")" = "200" ] || NON200=$((NON200+1))
done
say "non-200 responses: $NON200"
[ "$NON200" -eq 0 ] && say "PASS: all ${C} first-transfers returned 200" || report_fail "$NON200 first-transfers not 200"

# --- 2) 50 concurrent RETRIES of one transfer (SAME key) --------------------
say ""
say "=== Test 2: ${C} concurrent retries, SAME idempotency key ==="
KEY="retry_key_$RANDOM"
for i in $(seq 1 $C); do
  curl -sf -o "$pushd/retry_$i.json" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TA" -H "Content-Type: application/json" \
    -d "{\"to_user\":\"$B\",\"amount_paise\":100,\"idempotency_key\":\"$KEY\"}" > "$pushd/rcode_$i" &
done
wait

NON200=0
for i in $(seq 1 $C); do
  [ "$(cat "$pushd/rcode_$i")" = "200" ] || NON200=$((NON200+1))
done
say "non-200 responses: $NON200"
[ "$NON200" -eq 0 ] && say "PASS: all ${C} retries returned 200" || report_fail "$NON200 retries not 200"

# --- 3) Same key + DIFFERENT body -> 409 -----------------------------------
say ""
say "=== Test 3: same key different body -> 409 ==="
CONF=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $TA" -H "Content-Type: application/json" \
  -d "{\"to_user\":\"$B\",\"amount_paise\":9999,\"idempotency_key\":\"$KEY\"}")
say "conflict response: $CONF"
[ "$CONF" = "409" ] && say "PASS: conflict correctly 409" || report_fail "expected 409 got $CONF"

# --- 4) Conservation --------------------------------------------------------
say ""
say "=== Test 4: conservation ==="
BAL_A=$(bal "$TA"); BAL_B=$(bal "$TB")
TOTAL=$((BAL_A + BAL_B))
say "A = $BAL_A, B = $BAL_B, total = $TOTAL (expected 1,000,000)"
[ "$TOTAL" -eq 1000000 ] && say "PASS: total conserved ($TOTAL)" || report_fail "expected total 1000000 got $TOTAL"

# B should have exactly: Test1 (50*100=5000) + Test2 (100) = 5100
EXPECTED_B=$((50*100 + 100))
[ "$BAL_B" -eq "$EXPECTED_B" ] && say "PASS: B received exactly $EXPECTED_B (single apply per key)" \
  || report_fail "B expected $EXPECTED_B got $BAL_B"

# --- 5) Negatives / overspend ----------------------------------------------
BAL_A=$(bal "$TA"); BAL_B=$(bal "$TB")
{ [ "$BAL_A" -ge 0 ] && [ "$BAL_B" -ge 0 ]; } && say "PASS: no negative balances" || report_fail "negative balance detected"

# --- 6) Health & auth ----------------------------------------------------------
say ""
say "=== Test 5: health / auth ==="
H=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/healthz")
R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/readyz")
NA=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/accounts/me")
say "healthz=$H readyz=$R no-auth=$NA"
{ [ "$H" = "200" ] && [ "$R" = "200" ]; } || report_fail "health/ready not 200"
[ "$NA" = "401" ] || report_fail "missing token should be 401"

rm -rf "$pushd"

say ""
say "=========================================="
if [ "$FAIL" -eq 0 ]; then
  say "BURST: PASS"
  exit 0
else
  say "BURST: FAIL"
  exit 1
fi
