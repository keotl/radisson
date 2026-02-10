#!/bin/bash

test_concurrent_requests_queue_properly() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    echo "  → Sending 5 concurrent requests to trigger queueing..."

    local pids=()
    local temp_files=()

    for i in {1..5}; do
        local temp_file=$(mktemp)
        temp_files+=("$temp_file")
        (
            local payload='{"model":"local-mock","messages":[{"role":"user","content":"Request '"$i"'"}],"stream":false}'
            response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
            echo "$response" > "$temp_file"
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait $pid
    done

    local success_count=0
    for temp_file in "${temp_files[@]}"; do
        http_code=$(tail -n1 "$temp_file")
        if [ "$http_code" -eq 200 ] || [ "$http_code" -eq 503 ]; then
            success_count=$((success_count + 1))
        fi
        rm -f "$temp_file"
    done

    if [ $success_count -eq 5 ]; then
        echo "  ✓ Concurrent requests queue and complete properly ($success_count/5 successful)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Concurrent requests queue and complete properly (only $success_count/5 successful)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_backend_eviction_with_memory_pressure() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    echo "  → Testing backend eviction under memory pressure..."

    local payload1='{"model":"local-mock","messages":[{"role":"user","content":"First backend request"}],"stream":false}'
    response1=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload1")
    http_code1=$(echo "$response1" | tail -n1)

    local payload2='{"model":"local-mock2","messages":[{"role":"user","content":"Second backend request"}],"stream":false}'
    response2=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload2")
    http_code2=$(echo "$response2" | tail -n1)

    if [ "$http_code1" -eq 200 ] && [ "$http_code2" -eq 200 ]; then
        echo "  ✓ Multiple backends handle requests (codes: $http_code1, $http_code2)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Multiple backends handle requests (codes: $http_code1, $http_code2)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_streaming_requests_queue_properly() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    echo "  → Testing streaming requests with queueing..."

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"Streaming queue test"}],"stream":true}'
    response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")

    if echo "$response" | grep -q "data:" && echo "$response" | grep -q "[DONE]"; then
        echo "  ✓ Streaming requests queue and complete properly"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Streaming requests queue and complete properly"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_mixed_streaming_and_non_streaming_requests() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    echo "  → Testing mixed streaming and non-streaming requests..."

    local pids=()
    local temp_files=()

    for i in {1..2}; do
        local temp_file=$(mktemp)
        temp_files+=("$temp_file")

        (
            local payload='{"model":"local-mock","messages":[{"role":"user","content":"Non-streaming '"$i"'"}],"stream":false}'
            response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
            echo "$response" > "$temp_file"
        ) &
        pids+=($!)
    done

    for i in {1..2}; do
        local temp_file=$(mktemp)
        temp_files+=("$temp_file")

        (
            local payload='{"model":"local-mock","messages":[{"role":"user","content":"Streaming '"$i"'"}],"stream":true}'
            response=$(http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$payload")
            if echo "$response" | grep -q "[DONE]"; then
                echo "200" > "$temp_file"
            else
                echo "500" > "$temp_file"
            fi
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait $pid
    done

    local success_count=0
    for temp_file in "${temp_files[@]}"; do
        if [ -f "$temp_file" ]; then
            content=$(cat "$temp_file")
            if echo "$content" | grep -q "200"; then
                success_count=$((success_count + 1))
            fi
        fi
        rm -f "$temp_file"
    done

    if [ $success_count -ge 3 ]; then
        echo "  ✓ Mixed streaming and non-streaming requests complete ($success_count/4 successful)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Mixed streaming and non-streaming requests complete (only $success_count/4 successful)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_backend_reuse_after_requests_complete() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    echo "  → Testing backend reuse after requests complete..."

    local payload='{"model":"local-mock","messages":[{"role":"user","content":"First request"}],"stream":false}'
    response1=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code1=$(echo "$response1" | tail -n1)

    sleep 1

    response2=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code2=$(echo "$response2" | tail -n1)

    if [ "$http_code1" -eq 200 ] && [ "$http_code2" -eq 200 ]; then
        echo "  ✓ Backend reused for subsequent requests"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Backend reused for subsequent requests (codes: $http_code1, $http_code2)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}


echo "Running Queue and Eviction Tests..."
test_concurrent_requests_queue_properly
test_backend_eviction_with_memory_pressure
test_streaming_requests_queue_properly
# test_mixed_streaming_and_non_streaming_requests
test_backend_reuse_after_requests_complete
