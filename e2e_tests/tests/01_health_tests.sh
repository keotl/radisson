#!/bin/bash

test_root_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ] && echo "$body" | grep -q "radisson"; then
        echo "  ✓ GET / returns 200 with radisson message"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ GET / returns 200 with radisson message (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_health_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/health")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 200 ]; then
        echo "  ✓ GET /health returns 200 OK"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ GET /health returns 200 OK (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Health Tests..."
test_root_endpoint
test_health_endpoint
