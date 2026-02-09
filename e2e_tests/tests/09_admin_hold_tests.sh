#!/bin/bash

test_acquire_hold_local_stub() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"ttl_seconds": 300}'
    response=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        backend_id=$(echo "$body" | jq -r '.backend_id')
        backend_type=$(echo "$body" | jq -r '.backend_type')
        status=$(echo "$body" | jq -r '.status')
        port=$(echo "$body" | jq -r '.port')
        endpoint=$(echo "$body" | jq -r '.endpoint')
        acquired_at=$(echo "$body" | jq -r '.hold_acquired_at')
        expires_at=$(echo "$body" | jq -r '.hold_expires_at')

        if [ "$backend_id" == "stub-warmup" ] && \
           [ "$backend_type" == "local-stub" ] && \
           [ "$status" == "running" ] && \
           [ "$endpoint" != "null" ] && \
           [ "$acquired_at" -gt 0 ] && \
           [ "$expires_at" -gt "$acquired_at" ]; then
            echo "  ✓ Acquire hold on local-stub backend works correctly"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Acquire hold response missing expected fields"
            echo "    Response: $body"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ Acquire hold failed (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_acquire_hold_with_custom_ttl() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"ttl_seconds": 600}'
    response=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        acquired_at=$(echo "$body" | jq -r '.hold_acquired_at')
        expires_at=$(echo "$body" | jq -r '.hold_expires_at')
        ttl=$((expires_at - acquired_at))

        if [ "$ttl" -ge 590 ] && [ "$ttl" -le 610 ]; then
            echo "  ✓ Custom TTL (600s) applied correctly"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Custom TTL incorrect (expected ~600s, got ${ttl}s)"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ Acquire hold with custom TTL failed (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_release_hold() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local acquire_payload='{"ttl_seconds": 300}'
    http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$acquire_payload" > /dev/null 2>&1

    sleep 1

    response=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/release" "")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        backend_id=$(echo "$body" | jq -r '.backend_id')
        status=$(echo "$body" | jq -r '.status')
        released_at=$(echo "$body" | jq -r '.released_at')

        if [ "$backend_id" == "stub-warmup" ] && \
           [ "$status" == "released" ] && \
           [ "$released_at" -gt 0 ]; then
            echo "  ✓ Release hold works correctly"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Release hold response missing expected fields"
            echo "    Response: $body"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ Release hold failed (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_acquire_idempotent() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"ttl_seconds": 300}'
    response1=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$payload")
    body1=$(echo "$response1" | head -n-1)
    acquired_at1=$(echo "$body1" | jq -r '.hold_acquired_at')
    expires_at1=$(echo "$body1" | jq -r '.hold_expires_at')

    sleep 2

    response2=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$payload")
    http_code=$(echo "$response2" | tail -n1)
    body2=$(echo "$response2" | head -n-1)
    acquired_at2=$(echo "$body2" | jq -r '.hold_acquired_at')
    expires_at2=$(echo "$body2" | jq -r '.hold_expires_at')

    if [ "$http_code" -eq 200 ] && \
       [ "$acquired_at2" -ge "$acquired_at1" ] && \
       [ "$expires_at2" -gt "$expires_at1" ]; then
        echo "  ✓ Acquire hold is idempotent and refreshes expiry"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Acquire hold idempotency failed"
        echo "    First: acquired=$acquired_at1, expires=$expires_at1"
        echo "    Second: acquired=$acquired_at2, expires=$expires_at2"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_release_idempotent() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_post "http://$HOST:$PORT/admin/backend/nonexistent/release" "")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        status=$(echo "$body" | jq -r '.status')
        if [ "$status" == "released" ]; then
            echo "  ✓ Release hold is idempotent (non-existent hold succeeds)"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Release hold idempotency failed (wrong status)"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ Release hold idempotency failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_local_stub_not_in_models() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/v1/models")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        model_ids=$(echo "$body" | jq -r '.data[].id')

        if echo "$model_ids" | grep -q "stub-warmup"; then
            echo "  ✗ local-stub backend incorrectly appears in model list"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        else
            echo "  ✓ local-stub backend correctly excluded from model list"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        fi
    else
        echo "  ✗ Failed to fetch model list (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_local_stub_cannot_serve_requests() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"stub-warmup","messages":[{"role":"user","content":"test"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 400 ] || [ "$http_code" -eq 404 ]; then
        error_message=$(echo "$body" | jq -r '.error.message')
        if echo "$error_message" | grep -qi "not found"; then
            echo "  ✓ local-stub backend correctly cannot serve requests"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Wrong error message for local-stub request"
            echo "    Expected: 'not found', got: $error_message"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ local-stub backend request should return 400/404 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_ttl_validation() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"ttl_seconds": 7200}'
    response=$(http_post "http://$HOST:$PORT/admin/backend/stub-warmup/acquire" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 400 ]; then
        error_message=$(echo "$body" | jq -r '.error.message')
        if echo "$error_message" | grep -qi "cannot exceed"; then
            echo "  ✓ TTL validation rejects excessive values"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Wrong error message for TTL validation"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ TTL validation should return 400 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_backend_not_found() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"ttl_seconds": 300}'
    response=$(http_post "http://$HOST:$PORT/admin/backend/nonexistent/acquire" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 404 ]; then
        error_message=$(echo "$body" | jq -r '.error.message')
        if echo "$error_message" | grep -qi "not found"; then
            echo "  ✓ Acquire hold on non-existent backend returns 404"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Wrong error message for non-existent backend"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ Non-existent backend should return 404 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Admin Hold Tests..."
test_acquire_hold_local_stub
test_acquire_hold_with_custom_ttl
test_release_hold
test_acquire_idempotent
test_release_idempotent
test_local_stub_not_in_models
test_local_stub_cannot_serve_requests
test_ttl_validation
test_backend_not_found
