#!/bin/bash

test_backend_timeout() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"timeout-model","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 502 ] || [ "$http_code" -eq 504 ]; then
        echo "  ✓ Backend timeout returns 502 or 504"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Backend timeout returns 502 or 504 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Error Handling Tests..."
test_backend_timeout
