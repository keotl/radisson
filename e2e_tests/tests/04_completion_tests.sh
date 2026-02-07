#!/bin/bash

test_local_backend_completion() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        echo "  ✓ Local backend: Valid request returns 200"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Local backend: Valid request returns 200 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo $response
    fi
}

test_local_backend_response_structure() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    body=$(echo "$response" | head -n-1)

    if echo "$body" | jq -e '.id' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.object' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.created' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.model' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.choices' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.usage' > /dev/null 2>&1; then
        echo "  ✓ Local backend: Response contains required fields"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Local backend: Response contains required fields (missing fields)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_remote_backend_completion() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"remote-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        echo "  ✓ Remote backend: Valid request returns 200"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Remote backend: Valid request returns 200 (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_remote_backend_response_structure() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"remote-mock","messages":[{"role":"user","content":"Hello"}],"stream":false}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    body=$(echo "$response" | head -n-1)

    if echo "$body" | jq -e '.id' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.choices[0].message.role' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.choices[0].message.content' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.usage.prompt_tokens' > /dev/null 2>&1; then
        echo "  ✓ Remote backend: Response structure valid"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Remote backend: Response structure valid (missing fields)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Non-Streaming Completion Tests..."
test_local_backend_completion
test_local_backend_response_structure
test_remote_backend_completion
test_remote_backend_response_structure
