#!/usr/bin/env bash
#
# Integration test script for ProperBackup Subscription module
# Tests all subscription endpoints against a running backend + Stripe sandbox.
#
# Prerequisites:
#   - Backend running at localhost:7100 with PostgreSQL
#   - STRIPE_TEST_SECRET_KEY & STRIPE_PUBLIC_KEY configured
#   - Test user: test@properbackup.dev / Test1234!@
#
# Usage: ./test-subscription.sh [--verbose]
#

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:7100}"
EMAIL="${TEST_EMAIL:-test@properbackup.dev}"
PASSWORD="${TEST_PASSWORD:-Test1234!@}"
VERBOSE="${1:-}"

PASS=0
FAIL=0
TOTAL=0
FAILURES=()

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { echo -e "${BLUE}[TEST]${NC} $*"; }
pass() { echo -e "${GREEN}  ✓ PASS${NC}: $1"; PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); }
fail() { echo -e "${RED}  ✗ FAIL${NC}: $1 — $2"; FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); FAILURES+=("$1: $2"); }

json_get() {
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1',''))" 2>/dev/null
}

json_get_nested() {
    python3 -c "
import sys,json
d=json.load(sys.stdin)
keys='$1'.split('.')
for k in keys:
    if isinstance(d, dict):
        d = d.get(k, '')
    else:
        d = ''
        break
print(d)
" 2>/dev/null
}

json_get_bool() {
    python3 -c "import sys,json; d=json.load(sys.stdin); v=d.get('$1'); print('true' if v else 'false')" 2>/dev/null
}

# ===========================================================================
# 0. Health check
# ===========================================================================
log "0. Health check"
HEALTH=$(curl -s --max-time 5 "$BASE_URL/health" 2>/dev/null || echo "FAIL")
if [ "$HEALTH" = "ok" ]; then
    pass "Backend healthy"
else
    fail "Backend health check" "Got: $HEALTH"
    echo -e "${RED}Backend not running — aborting tests.${NC}"
    exit 1
fi

# ===========================================================================
# 1. Authentication
# ===========================================================================
log "1. Authentication"
LOGIN_RESP=$(curl -s "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESP" | json_get token)

if [ -n "$TOKEN" ] && [ ${#TOKEN} -gt 20 ]; then
    pass "Login successful (token ${#TOKEN} chars)"
else
    fail "Login" "No token received. Response: $LOGIN_RESP"
    echo -e "${RED}Cannot authenticate — aborting tests.${NC}"
    exit 1
fi

AUTH="Authorization: Bearer $TOKEN"

# ===========================================================================
# 2. Get subscription info
# ===========================================================================
log "2. Subscription info endpoint"
SUB_RESP=$(curl -s "$BASE_URL/account/subscription" -H "$AUTH")

SUB_STATUS=$(echo "$SUB_RESP" | json_get status)
SUB_PLAN=$(echo "$SUB_RESP" | json_get plan)
DAYS_REMAINING=$(echo "$SUB_RESP" | json_get daysRemaining)
VAT_RATE=$(echo "$SUB_RESP" | json_get vatRate)
STRIPE_MODE=$(echo "$SUB_RESP" | json_get stripeMode)
STORAGE_LIMIT=$(echo "$SUB_RESP" | json_get storageLimitBytes)
PRICING_MONTHLY_BASE=$(echo "$SUB_RESP" | json_get_nested pricing.monthly.basePriceGrosze)
PRICING_ANNUAL_BASE=$(echo "$SUB_RESP" | json_get_nested pricing.annual.basePriceGrosze)
PRORATED_MONTHLY=$(echo "$SUB_RESP" | json_get_nested pricing.monthly.proratedDiscountGrosze)
PRORATED_ANNUAL=$(echo "$SUB_RESP" | json_get_nested pricing.annual.proratedDiscountGrosze)

# Test: status must be present
if [ -n "$SUB_STATUS" ]; then
    pass "Subscription status: $SUB_STATUS"
else
    fail "Subscription status" "Empty status in response"
fi

# Test: VAT rate must be 0.23
if [ "$VAT_RATE" = "0.23" ]; then
    pass "VAT rate = 0.23 (from config table)"
else
    fail "VAT rate" "Expected 0.23, got: $VAT_RATE"
fi

# Test: Stripe mode must be 'test'
if [ "$STRIPE_MODE" = "test" ]; then
    pass "Stripe mode = test"
else
    fail "Stripe mode" "Expected 'test', got: $STRIPE_MODE"
fi

# Test: monthly base price = 1900 (19.00 PLN)
if [ "$PRICING_MONTHLY_BASE" = "1900" ]; then
    pass "Monthly base price = 1900 groszy (19.00 PLN)"
else
    fail "Monthly base price" "Expected 1900, got: $PRICING_MONTHLY_BASE"
fi

# Test: annual base price = 19000 (190.00 PLN)
if [ "$PRICING_ANNUAL_BASE" = "19000" ]; then
    pass "Annual base price = 19000 groszy (190.00 PLN)"
else
    fail "Annual base price" "Expected 19000, got: $PRICING_ANNUAL_BASE"
fi

# Test: storage limit = 2TB
if [ "$STORAGE_LIMIT" = "2199023255552" ]; then
    pass "Storage limit = 2 TB"
else
    fail "Storage limit" "Expected 2199023255552, got: $STORAGE_LIMIT"
fi

# Test: prorated discount >= 0
if python3 -c "assert int('${PRORATED_MONTHLY:-0}') >= 0"; then
    pass "Monthly prorated discount = ${PRORATED_MONTHLY} groszy (≥0)"
else
    fail "Monthly prorated discount" "Negative: $PRORATED_MONTHLY"
fi

# Test: prorated discount cannot exceed base price
if python3 -c "assert int('${PRORATED_MONTHLY:-0}') <= int('${PRICING_MONTHLY_BASE:-1900}')"; then
    pass "Monthly prorated discount ≤ base price (capped)"
else
    fail "Monthly prorated discount cap" "$PRORATED_MONTHLY > $PRICING_MONTHLY_BASE"
fi

if python3 -c "assert int('${PRORATED_ANNUAL:-0}') <= int('${PRICING_ANNUAL_BASE:-19000}')"; then
    pass "Annual prorated discount ≤ base price (capped)"
else
    fail "Annual prorated discount cap" "$PRORATED_ANNUAL > $PRICING_ANNUAL_BASE"
fi

# Test: prorated math verification
# prorated = min(basePrice, basePrice * daysRemaining / totalDays)
if [ -n "$DAYS_REMAINING" ] && [ "$DAYS_REMAINING" != "0" ]; then
    EXPECTED_PRORATED_ANNUAL=$(python3 -c "print(min(19000, 19000 * $DAYS_REMAINING // 365))")
    if [ "$PRORATED_ANNUAL" = "$EXPECTED_PRORATED_ANNUAL" ]; then
        pass "Prorated annual math correct: $PRORATED_ANNUAL = min(19000, 19000×$DAYS_REMAINING/365)"
    else
        fail "Prorated annual math" "Expected $EXPECTED_PRORATED_ANNUAL, got $PRORATED_ANNUAL (days=$DAYS_REMAINING)"
    fi
fi

# ===========================================================================
# 3. Stripe public key endpoint
# ===========================================================================
log "3. Stripe public key"
PK_RESP=$(curl -s "$BASE_URL/payment/stripe/public-key" -H "$AUTH")
PK=$(echo "$PK_RESP" | json_get publicKey)

if [ -n "$PK" ] && [[ "$PK" == pk_test_* ]]; then
    pass "Stripe public key starts with pk_test_"
elif [ -n "$PK" ] && [[ "$PK" == pk_live_* ]]; then
    fail "Stripe public key" "Got LIVE key ($PK) — should be test!"
else
    fail "Stripe public key" "Missing or invalid: $PK"
fi

# ===========================================================================
# 4. Card info endpoint
# ===========================================================================
log "4. Card info"
CARD_RESP=$(curl -s "$BASE_URL/payment/stripe/card-info" -H "$AUTH")
HAS_CARD=$(echo "$CARD_RESP" | json_get_bool hasCard)
CARD_BRAND=$(echo "$CARD_RESP" | json_get brand)
CARD_LAST4=$(echo "$CARD_RESP" | json_get last4)
CARD_EXP_MONTH=$(echo "$CARD_RESP" | json_get expMonth)
CARD_EXP_YEAR=$(echo "$CARD_RESP" | json_get expYear)

if [ "$HAS_CARD" = "true" ]; then
    pass "Card attached: hasCard=true"

    # Verify card details are present and valid
    if [ -n "$CARD_BRAND" ] && [ "$CARD_BRAND" != "" ]; then
        pass "Card brand returned: $CARD_BRAND"
    else
        fail "Card brand" "Missing brand"
    fi

    if [ -n "$CARD_LAST4" ] && [ ${#CARD_LAST4} -eq 4 ]; then
        pass "Card last4 returned: $CARD_LAST4"
    else
        fail "Card last4" "Invalid: '$CARD_LAST4'"
    fi

    if [ -n "$CARD_EXP_MONTH" ] && [ -n "$CARD_EXP_YEAR" ]; then
        pass "Card expiry returned: $CARD_EXP_MONTH/$CARD_EXP_YEAR"
    else
        fail "Card expiry" "Missing: month=$CARD_EXP_MONTH year=$CARD_EXP_YEAR"
    fi
else
    pass "No card attached (hasCard=false) — expected if no prior payment"
fi

# ===========================================================================
# 5. Payment status endpoint
# ===========================================================================
log "5. Payment status"
PAY_RESP=$(curl -s "$BASE_URL/payment/stripe/status" -H "$AUTH")
PAY_HAS_METHOD=$(echo "$PAY_RESP" | json_get_bool hasPaymentMethod)
PAY_SUB_ID=$(echo "$PAY_RESP" | json_get stripeSubscriptionId)

if [ -n "$PAY_RESP" ]; then
    pass "Payment status endpoint returns data"
else
    fail "Payment status" "Empty response"
fi

if [ -n "$PAY_SUB_ID" ] && [[ "$PAY_SUB_ID" == sub_* ]]; then
    pass "Stripe subscription ID present: ${PAY_SUB_ID:0:20}..."
else
    log "  (No active Stripe subscription — skipping sub ID check)"
fi

# ===========================================================================
# 6. Promo code validation
# ===========================================================================
log "6. Promo code validation"

# 6a. Valid code
PROMO_VALID_RESP=$(curl -s "$BASE_URL/payment/validate-promo" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"code":"PROMO50"}')
PROMO_VALID=$(echo "$PROMO_VALID_RESP" | json_get valid)
PROMO_TYPE=$(echo "$PROMO_VALID_RESP" | json_get type)
PROMO_DISCOUNT=$(echo "$PROMO_VALID_RESP" | json_get discountPercent)

if [ "$PROMO_VALID" = "True" ] || [ "$PROMO_VALID" = "true" ]; then
    pass "PROMO50 validated successfully"
    if [ "$PROMO_DISCOUNT" = "50" ]; then
        pass "PROMO50 discount = 50%"
    else
        fail "PROMO50 discount" "Expected 50, got: $PROMO_DISCOUNT"
    fi
else
    fail "PROMO50 validation" "Expected valid=true. Response: $PROMO_VALID_RESP"
fi

# 6b. Invalid code
PROMO_INVALID_RESP=$(curl -s "$BASE_URL/payment/validate-promo" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"code":"BADCODE999"}')
PROMO_INVALID=$(echo "$PROMO_INVALID_RESP" | json_get valid)
PROMO_REASON=$(echo "$PROMO_INVALID_RESP" | json_get reason)

if [ "$PROMO_INVALID" = "False" ] || [ "$PROMO_INVALID" = "false" ]; then
    pass "BADCODE999 rejected correctly"
    if [ "$PROMO_REASON" = "not_found_or_expired" ]; then
        pass "Rejection reason: not_found_or_expired"
    else
        fail "Rejection reason" "Expected 'not_found_or_expired', got: $PROMO_REASON"
    fi
else
    fail "Invalid code rejection" "Expected valid=false. Response: $PROMO_INVALID_RESP"
fi

# 6c. Empty code
PROMO_EMPTY_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/validate-promo" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"code":""}')
PROMO_EMPTY_STATUS=$(echo "$PROMO_EMPTY_RESP" | tail -1)

if [ "$PROMO_EMPTY_STATUS" = "400" ]; then
    pass "Empty code returns 400"
else
    fail "Empty code status" "Expected 400, got: $PROMO_EMPTY_STATUS"
fi

# 6d. Case insensitivity (API should uppercase)
PROMO_LOWER_RESP=$(curl -s "$BASE_URL/payment/validate-promo" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"code":"promo50"}')
PROMO_LOWER_VALID=$(echo "$PROMO_LOWER_RESP" | json_get valid)

if [ "$PROMO_LOWER_VALID" = "True" ] || [ "$PROMO_LOWER_VALID" = "true" ]; then
    pass "Lowercase 'promo50' accepted (API uppercases)"
else
    fail "Case insensitivity" "Lowercase code rejected. Response: $PROMO_LOWER_RESP"
fi

# ===========================================================================
# 7. Stripe Checkout session creation
# ===========================================================================
log "7. Checkout session creation"

# 7a. Monthly plan
CHECKOUT_RESP=$(curl -s "$BASE_URL/payment/stripe/checkout" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"plan":"monthly"}')
CHECKOUT_URL=$(echo "$CHECKOUT_RESP" | json_get checkoutUrl)
CHECKOUT_MODE=$(echo "$CHECKOUT_RESP" | json_get mode)
CHECKOUT_SID=$(echo "$CHECKOUT_RESP" | json_get sessionId)

if [ -n "$CHECKOUT_URL" ] && [[ "$CHECKOUT_URL" == https://checkout.stripe.com/* ]]; then
    pass "Monthly checkout URL created: ${CHECKOUT_URL:0:60}..."
else
    fail "Monthly checkout URL" "Invalid URL: $CHECKOUT_URL. Full response: $CHECKOUT_RESP"
fi

if [ "$CHECKOUT_MODE" = "subscription" ]; then
    pass "Checkout mode = subscription (recurring)"
else
    fail "Checkout mode" "Expected 'subscription', got: $CHECKOUT_MODE"
fi

if [ -n "$CHECKOUT_SID" ] && [[ "$CHECKOUT_SID" == cs_test_* ]]; then
    pass "Session ID starts with cs_test_"
else
    fail "Session ID" "Expected cs_test_*, got: $CHECKOUT_SID"
fi

# 7b. Annual plan
CHECKOUT_ANNUAL_RESP=$(curl -s "$BASE_URL/payment/stripe/checkout" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"plan":"annual"}')
CHECKOUT_ANNUAL_URL=$(echo "$CHECKOUT_ANNUAL_RESP" | json_get checkoutUrl)

if [ -n "$CHECKOUT_ANNUAL_URL" ] && [[ "$CHECKOUT_ANNUAL_URL" == https://checkout.stripe.com/* ]]; then
    pass "Annual checkout URL created"
else
    fail "Annual checkout URL" "Invalid: $CHECKOUT_ANNUAL_URL"
fi

# 7c. Checkout with promo code
CHECKOUT_PROMO_RESP=$(curl -s "$BASE_URL/payment/stripe/checkout" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"plan":"monthly","promoCode":"PROMO50"}')
CHECKOUT_PROMO_URL=$(echo "$CHECKOUT_PROMO_RESP" | json_get checkoutUrl)

if [ -n "$CHECKOUT_PROMO_URL" ] && [[ "$CHECKOUT_PROMO_URL" == https://checkout.stripe.com/* ]]; then
    pass "Checkout with PROMO50 creates session"
else
    fail "Checkout with promo" "Invalid URL: $CHECKOUT_PROMO_URL. Response: $CHECKOUT_PROMO_RESP"
fi

# ===========================================================================
# 8. Cancel subscription
# ===========================================================================
log "8. Cancel subscription"

# Check if user has active subscription
CANCEL_RESP=$(curl -s "$BASE_URL/payment/stripe/cancel-subscription" \
    -X POST -H "$AUTH" -H 'Content-Type: application/json')
CANCEL_STATUS=$(echo "$CANCEL_RESP" | json_get status)
CANCEL_ERROR=$(echo "$CANCEL_RESP" | json_get error)

if [ "$CANCEL_STATUS" = "cancelled" ]; then
    pass "Cancel subscription: status=cancelled"

    # Verify subscription state
    SUB_AFTER_CANCEL=$(curl -s "$BASE_URL/account/subscription" -H "$AUTH")
    CANCEL_FLAG=$(echo "$SUB_AFTER_CANCEL" | json_get_bool cancelAtPeriodEnd)

    if [ "$CANCEL_FLAG" = "true" ]; then
        pass "cancelAtPeriodEnd = true after cancel"
    else
        fail "cancelAtPeriodEnd after cancel" "Expected true, got: $CANCEL_FLAG"
    fi
elif [ "$CANCEL_ERROR" = "no_active_subscription" ]; then
    pass "Cancel returns 'no_active_subscription' (expected if no sub)"
else
    fail "Cancel subscription" "Unexpected response: $CANCEL_RESP"
fi

# ===========================================================================
# 9. Reactivate subscription
# ===========================================================================
log "9. Reactivate subscription"
REACT_RESP=$(curl -s "$BASE_URL/payment/stripe/reactivate" \
    -X POST -H "$AUTH" -H 'Content-Type: application/json')
REACT_STATUS=$(echo "$REACT_RESP" | json_get status)
REACT_ERROR=$(echo "$REACT_RESP" | json_get error)

if [ "$REACT_STATUS" = "reactivated" ]; then
    pass "Reactivate: status=reactivated"

    # Verify subscription state
    SUB_AFTER_REACT=$(curl -s "$BASE_URL/account/subscription" -H "$AUTH")
    REACT_FLAG=$(echo "$SUB_AFTER_REACT" | json_get_bool cancelAtPeriodEnd)

    if [ "$REACT_FLAG" = "false" ]; then
        pass "cancelAtPeriodEnd = false after reactivation"
    else
        fail "cancelAtPeriodEnd after reactivation" "Expected false, got: $REACT_FLAG"
    fi
elif [ "$REACT_ERROR" = "no_active_subscription" ]; then
    pass "Reactivate returns 'no_active_subscription' (expected if no sub)"
else
    fail "Reactivate" "Unexpected response: $REACT_RESP"
fi

# ===========================================================================
# 10. Unauthorized access (no token)
# ===========================================================================
log "10. Auth guards"

NOAUTH_SUB=$(curl -s -w "\n%{http_code}" "$BASE_URL/account/subscription")
NOAUTH_STATUS=$(echo "$NOAUTH_SUB" | tail -1)
if [ "$NOAUTH_STATUS" = "401" ]; then
    pass "Subscription endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: subscription" "Expected 401, got: $NOAUTH_STATUS"
fi

NOAUTH_CHECKOUT=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/stripe/checkout" \
    -H 'Content-Type: application/json' -d '{"plan":"monthly"}')
NOAUTH_CHK_STATUS=$(echo "$NOAUTH_CHECKOUT" | tail -1)
if [ "$NOAUTH_CHK_STATUS" = "401" ]; then
    pass "Checkout endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: checkout" "Expected 401, got: $NOAUTH_CHK_STATUS"
fi

NOAUTH_CARD=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/stripe/card-info")
NOAUTH_CARD_STATUS=$(echo "$NOAUTH_CARD" | tail -1)
if [ "$NOAUTH_CARD_STATUS" = "401" ]; then
    pass "Card info endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: card-info" "Expected 401, got: $NOAUTH_CARD_STATUS"
fi

NOAUTH_CANCEL=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/stripe/cancel-subscription" -X POST)
NOAUTH_CANCEL_STATUS=$(echo "$NOAUTH_CANCEL" | tail -1)
if [ "$NOAUTH_CANCEL_STATUS" = "401" ]; then
    pass "Cancel endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: cancel" "Expected 401, got: $NOAUTH_CANCEL_STATUS"
fi

NOAUTH_REACT=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/stripe/reactivate" -X POST)
NOAUTH_REACT_STATUS=$(echo "$NOAUTH_REACT" | tail -1)
if [ "$NOAUTH_REACT_STATUS" = "401" ]; then
    pass "Reactivate endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: reactivate" "Expected 401, got: $NOAUTH_REACT_STATUS"
fi

NOAUTH_PROMO=$(curl -s -w "\n%{http_code}" "$BASE_URL/payment/validate-promo" \
    -H 'Content-Type: application/json' -d '{"code":"PROMO50"}')
NOAUTH_PROMO_STATUS=$(echo "$NOAUTH_PROMO" | tail -1)
if [ "$NOAUTH_PROMO_STATUS" = "401" ]; then
    pass "Promo validation endpoint rejects unauthenticated (401)"
else
    fail "Auth guard: validate-promo" "Expected 401, got: $NOAUTH_PROMO_STATUS"
fi

# ===========================================================================
# 11. Webhook endpoint exists (POST only)
# ===========================================================================
log "11. Webhook endpoint"
WEBHOOK_RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/webhooks/stripe" \
    -X POST -H 'Content-Type: application/json' -d '{}')
WEBHOOK_STATUS=$(echo "$WEBHOOK_RESP" | tail -1)

# Should return 400 (bad payload/missing signature), not 404
if [ "$WEBHOOK_STATUS" = "400" ]; then
    pass "Webhook endpoint exists and validates requests (400 for invalid payload)"
elif [ "$WEBHOOK_STATUS" = "404" ]; then
    fail "Webhook endpoint" "Returns 404 — endpoint not registered!"
else
    fail "Webhook endpoint" "Expected 400, got: $WEBHOOK_STATUS"
fi

# ===========================================================================
# 12. Verify audit log has entries (DB check via subscription endpoint)
# ===========================================================================
log "12. Audit logging verification"
# We triggered cancel + reactivate above, let's check DB directly
AUDIT_COUNT=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
    "SELECT COUNT(*) FROM subscription_audit_log WHERE user_id = (SELECT id FROM users WHERE email = '$EMAIL' LIMIT 1)" 2>/dev/null | tr -d ' ')

if [ -n "$AUDIT_COUNT" ] && [ "$AUDIT_COUNT" -gt 0 ]; then
    pass "Audit log has $AUDIT_COUNT entries"
else
    fail "Audit logging" "No audit entries found (count: $AUDIT_COUNT)"
fi

# Check specific actions logged
for ACTION in "cancel_requested" "subscription_reactivated"; do
    ACTION_COUNT=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
        "SELECT COUNT(*) FROM subscription_audit_log WHERE action = '$ACTION'" 2>/dev/null | tr -d ' ')
    if [ -n "$ACTION_COUNT" ] && [ "$ACTION_COUNT" -gt 0 ]; then
        pass "Audit: '$ACTION' logged ($ACTION_COUNT entries)"
    else
        fail "Audit: '$ACTION'" "No entries found"
    fi
done

# ===========================================================================
# 13. Stripe event deduplication (DB check)
# ===========================================================================
log "13. Stripe event table"
EVENT_COUNT=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
    "SELECT COUNT(*) FROM stripe_event" 2>/dev/null | tr -d ' ')

if [ -n "$EVENT_COUNT" ] && [ "$EVENT_COUNT" -ge 0 ]; then
    pass "Stripe event table accessible ($EVENT_COUNT events)"
else
    fail "Stripe event table" "Cannot query stripe_event"
fi

# ===========================================================================
# 14. Subscription config (VAT from DB)
# ===========================================================================
log "14. Config table"
DB_VAT=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
    "SELECT value FROM subscription_config WHERE key = 'vat_rate'" 2>/dev/null | tr -d ' ')

if [ "$DB_VAT" = "0.23" ]; then
    pass "DB config vat_rate = 0.23"
else
    fail "DB config vat_rate" "Expected 0.23, got: '$DB_VAT'"
fi

# ===========================================================================
# 15. Stripe price config persisted
# ===========================================================================
log "15. Stripe price config"
PRICE_COUNT=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
    "SELECT COUNT(*) FROM stripe_price_config WHERE plan_key IN ('monthly', 'annual')" 2>/dev/null | tr -d ' ')

if [ "$PRICE_COUNT" = "2" ]; then
    pass "Both monthly and annual Stripe prices persisted in DB"
else
    fail "Stripe price config" "Expected 2 entries, got: $PRICE_COUNT"
fi

# Verify price IDs start with price_
for PLAN in "monthly" "annual"; do
    PRICE_ID=$(PGPASSWORD=dev-pg-pass psql -h localhost -U properbackup -d properbackup -t -c \
        "SELECT stripe_price_id FROM stripe_price_config WHERE plan_key = '$PLAN'" 2>/dev/null | tr -d ' ')
    if [[ "$PRICE_ID" == price_* ]]; then
        pass "Stripe price for $PLAN: ${PRICE_ID:0:20}..."
    else
        fail "Stripe price for $PLAN" "Expected price_*, got: '$PRICE_ID'"
    fi
done

# ===========================================================================
# Summary
# ===========================================================================
echo ""
echo -e "${BLUE}════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  RESULTS: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}, $TOTAL total"
echo -e "${BLUE}════════════════════════════════════════════════${NC}"

if [ $FAIL -gt 0 ]; then
    echo ""
    echo -e "${RED}FAILURES:${NC}"
    for f in "${FAILURES[@]}"; do
        echo -e "  ${RED}✗${NC} $f"
    done
    echo ""
    exit 1
else
    echo ""
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
fi
