#!/bin/bash

# Verifies that a client disconnect is forwarded to the backend: the proxy must
# tear down its upstream connection, which the mock backend records as a
# disconnect via its /control/disconnects endpoint.

MOCK_URL="http://127.0.0.1:9000"

cancellation_warm_up() {
    # A normal request starts the (lazy) local-mock backend so its control
    # endpoints become reachable.
    local payload='{"model":"local-mock","messages":[{"role":"user","content":"warmup"}]}'
    http_post "http://$HOST:$PORT/v1/chat/completions" "$payload" > /dev/null 2>&1

    local elapsed=0
    while [ $elapsed -lt 15 ]; do
        if curl -s "$MOCK_URL/control/disconnects" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

cancellation_get_count() {
    local kind=$1
    curl -s "$MOCK_URL/control/disconnects" 2>/dev/null | jq -r ".${kind} // 0"
}

cancellation_wait_for_count() {
    local kind=$1
    local timeout=${2:-8}
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local c
        c=$(cancellation_get_count "$kind")
        if [ "${c:-0}" -gt 0 ] 2>/dev/null; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

test_streaming_cancellation_propagates() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    curl -s -X POST "$MOCK_URL/control/reset" > /dev/null 2>&1

    local payload='{"model":"local-mock","stream":true,"messages":[{"role":"user","content":"[SLOW] stream please"}]}'
    # Abort the client mid-stream; --max-time closes the connection.
    curl -s -N --max-time 3 -X POST -H "Content-Type: application/json" \
        -d "$payload" "http://$HOST:$PORT/v1/chat/completions" > /dev/null 2>&1 || true

    if cancellation_wait_for_count "streaming" 8; then
        echo "  ✓ Streaming: client cancellation tears down the upstream connection"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Streaming: client cancellation tears down the upstream connection"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_nonstreaming_cancellation_propagates() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    curl -s -X POST "$MOCK_URL/control/reset" > /dev/null 2>&1

    local payload='{"model":"local-mock","stream":false,"messages":[{"role":"user","content":"[SLOW] non stream please"}]}'
    # Abort the client mid-body; the proxy streams the upstream body through, so
    # the disconnect propagates to the backend.
    curl -s --max-time 2 -X POST -H "Content-Type: application/json" \
        -d "$payload" "http://$HOST:$PORT/v1/chat/completions" > /dev/null 2>&1 || true

    if cancellation_wait_for_count "nonstreaming" 8; then
        echo "  ✓ Non-streaming: client cancellation tears down the upstream connection"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Non-streaming: client cancellation tears down the upstream connection"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_nonstreaming_no_regression() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Hello"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    local http_code
    http_code=$(echo "$response" | tail -n1)
    local body
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ] && echo "$body" | jq -e '.choices[0].message.content' > /dev/null 2>&1; then
        echo "  ✓ Non-streaming: normal request still returns 200 with valid JSON"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Non-streaming: normal request still returns 200 with valid JSON (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Cancellation Tests..."
if cancellation_warm_up; then
    test_streaming_cancellation_propagates
    test_nonstreaming_cancellation_propagates
    test_nonstreaming_no_regression
else
    echo "  ! Skipping cancellation tests: mock control endpoint unreachable"
fi
