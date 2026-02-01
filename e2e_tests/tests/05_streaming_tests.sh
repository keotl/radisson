#!/bin/bash

test_local_backend_streaming() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Hello"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    if echo "$response" | grep -q "^data:" && echo "$response" | grep -q "data:\[DONE\]"; then
        echo "  ✓ Local backend: Streaming returns SSE format with [DONE]"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Local backend: Streaming returns SSE format with [DONE]"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_local_backend_streaming_json() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Hello"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    local all_valid=true
    while IFS= read -r line; do
        if [[ "$line" =~ ^data:(.+)$ ]]; then
            local json_part="${BASH_REMATCH[1]}"
            if [ "$json_part" != "[DONE]" ]; then
                if ! echo "$json_part" | jq empty 2>/dev/null; then
                    all_valid=false
                    break
                fi
            fi
        fi
    done <<< "$response"

    if [ "$all_valid" = true ]; then
        echo "  ✓ Local backend: Each streaming chunk is valid JSON"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Local backend: Each streaming chunk is valid JSON"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_remote_backend_streaming() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"remote-mock","messages":[{"role":"user","content":"Hello"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    if echo "$response" | grep -q "^data:" && echo "$response" | grep -q "data:\[DONE\]"; then
        echo "  ✓ Remote backend: Streaming returns SSE format with [DONE]"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Remote backend: Streaming returns SSE format with [DONE]"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_remote_backend_streaming_json() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"remote-mock","messages":[{"role":"user","content":"Hello"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    local all_valid=true
    while IFS= read -r line; do
        if [[ "$line" =~ ^data:(.+)$ ]]; then
            local json_part="${BASH_REMATCH[1]}"
            if [ "$json_part" != "[DONE]" ]; then
                if ! echo "$json_part" | jq empty 2>/dev/null; then
                    all_valid=false
                    break
                fi
            fi
        fi
    done <<< "$response"

    if [ "$all_valid" = true ]; then
        echo "  ✓ Remote backend: Each streaming chunk is valid JSON"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Remote backend: Each streaming chunk is valid JSON"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Streaming Completion Tests..."
test_local_backend_streaming
test_local_backend_streaming_json
test_remote_backend_streaming
test_remote_backend_streaming_json
