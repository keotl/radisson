#!/bin/bash

test_empty_messages() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 400 ] && echo "$body" | grep -q "messages cannot be empty"; then
        echo "  ✓ Empty messages array returns 400 with appropriate error"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Empty messages array returns 400 with appropriate error (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_empty_model() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"","messages":[{"role":"user","content":"test"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 400 ] && echo "$body" | grep -q "model cannot be empty"; then
        echo "  ✓ Empty model string returns 400 with appropriate error"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Empty model string returns 400 with appropriate error (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_unknown_model() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"unknown-model","messages":[{"role":"user","content":"test"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 400 ] && echo "$body" | grep -qi "model.*not found\|unknown.*model"; then
        echo "  ✓ Unknown model returns 400 with model not found error"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Unknown model returns 400 with model not found error (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Validation Tests..."
test_empty_messages
test_empty_model
test_unknown_model
