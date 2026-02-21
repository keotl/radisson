#!/bin/bash

test_first_available_with_remote() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"first-available-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        echo "  ✓ First-available: Routes to remote backend and returns 200"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ First-available: Routes to remote backend and returns 200 (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_first_available_response_structure() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"first-available-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    body=$(echo "$response" | head -n-1)

    if echo "$body" | jq -e '.id' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.choices[0].message.content' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.usage' > /dev/null 2>&1; then
        echo "  ✓ First-available: Response contains required fields"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ First-available: Response contains required fields (missing fields)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_first_available_local_backend() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"first-available-local-only","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 503 ]; then
        echo "  ✓ First-available (local): Request accepted (got $http_code)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ First-available (local): Request accepted (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_first_available_streaming() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"first-available-mock","messages":[{"role":"user","content":"Hello"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    if echo "$response" | grep -q "data:"; then
        echo "  ✓ First-available: Streaming returns SSE events"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ First-available: Streaming returns SSE events"
        echo "    Response: $response"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_first_available_in_models_list() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/v1/models")
    body=$(echo "$response" | head -n-1)

    if echo "$body" | jq -e '.data[] | select(.id == "first-available-mock")' > /dev/null 2>&1; then
        echo "  ✓ First-available: Appears in models list"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ First-available: Appears in models list"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running First-Available Backend Tests..."
test_first_available_with_remote
test_first_available_response_structure
test_first_available_local_backend
test_first_available_streaming
test_first_available_in_models_list
