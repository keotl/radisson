#!/bin/bash

test_admin_status_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/admin/status")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        has_total=$(echo "$body" | jq -e '.total_memory_bytes' 2>/dev/null)
        has_used=$(echo "$body" | jq -e '.used_memory_bytes' 2>/dev/null)
        has_available=$(echo "$body" | jq -e '.available_memory_bytes' 2>/dev/null)
        has_backends=$(echo "$body" | jq -e '.backends' 2>/dev/null)
        has_pending=$(echo "$body" | jq -e '.pending_starts' 2>/dev/null)

        if [ $? -eq 0 ]; then
            backend_count=$(echo "$body" | jq '.backends | length')
            if [ "$backend_count" -gt 0 ]; then
                echo "  ✓ /admin/status returns valid status with $backend_count backends"
                PASSED_TESTS=$((PASSED_TESTS + 1))
            else
                echo "  ✗ /admin/status returned 0 backends"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            fi
        else
            echo "  ✗ /admin/status response missing expected fields"
            echo "    Response: $body"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/status failed (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_admin_status_backend_fields() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/admin/status")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        first_backend=$(echo "$body" | jq '.backends[0]')
        has_id=$(echo "$first_backend" | jq -e '.id' 2>/dev/null)
        has_type=$(echo "$first_backend" | jq -e '.backend_type' 2>/dev/null)
        has_state=$(echo "$first_backend" | jq -e '.state' 2>/dev/null)

        if [ $? -eq 0 ]; then
            echo "  ✓ /admin/status backend entries have expected fields"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ /admin/status backend entries missing fields"
            echo "    First backend: $first_backend"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/status failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_admin_requests_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    # Make a completion request first to generate a trace
    local payload='{"model":"local-mock","messages":[{"role":"user","content":"test"}]}'
    http_post "http://$HOST:$PORT/v1/chat/completions" "$payload" > /dev/null 2>&1

    sleep 1

    response=$(http_get "http://$HOST:$PORT/admin/requests")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        has_traces=$(echo "$body" | jq -e '.traces' 2>/dev/null)
        has_total=$(echo "$body" | jq -e '.total_captured' 2>/dev/null)

        if [ $? -eq 0 ]; then
            trace_count=$(echo "$body" | jq '.traces | length')
            total_captured=$(echo "$body" | jq '.total_captured')
            if [ "$trace_count" -gt 0 ] && [ "$total_captured" -gt 0 ]; then
                echo "  ✓ /admin/requests returns traces (count: $trace_count, total: $total_captured)"
                PASSED_TESTS=$((PASSED_TESTS + 1))
            else
                echo "  ✗ /admin/requests has no traces after making a request"
                echo "    Response: $body"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            fi
        else
            echo "  ✗ /admin/requests response missing expected fields"
            echo "    Response: $body"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/requests failed (got $http_code)"
        echo "    Response: $body"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_admin_requests_trace_fields() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/admin/requests")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        trace_count=$(echo "$body" | jq '.traces | length')
        if [ "$trace_count" -gt 0 ]; then
            first_trace=$(echo "$body" | jq '.traces[0]')
            has_request_id=$(echo "$first_trace" | jq -e '.request_id' 2>/dev/null)
            has_backend_id=$(echo "$first_trace" | jq -e '.backend_id' 2>/dev/null)
            has_model=$(echo "$first_trace" | jq -e '.model' 2>/dev/null)
            has_request_type=$(echo "$first_trace" | jq -e '.request_type' 2>/dev/null)
            has_status=$(echo "$first_trace" | jq -e '.status' 2>/dev/null)
            has_duration=$(echo "$first_trace" | jq -e '.duration_ms' 2>/dev/null)
            has_started=$(echo "$first_trace" | jq -e '.started_at' 2>/dev/null)
            has_completed=$(echo "$first_trace" | jq -e '.completed_at' 2>/dev/null)

            if [ $? -eq 0 ]; then
                echo "  ✓ Trace entries have expected fields"
                PASSED_TESTS=$((PASSED_TESTS + 1))
            else
                echo "  ✗ Trace entries missing fields"
                echo "    First trace: $first_trace"
                FAILED_TESTS=$((FAILED_TESTS + 1))
            fi
        else
            echo "  ✗ No traces available to check fields"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/requests failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_admin_dashboard_html() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/admin/")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        if echo "$body" | grep -q "radisson dashboard"; then
            echo "  ✓ /admin/ returns HTML dashboard"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ /admin/ does not contain expected HTML content"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/ failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_admin_status_memory_consistency() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/admin/status")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ]; then
        total=$(echo "$body" | jq '.total_memory_bytes')
        used=$(echo "$body" | jq '.used_memory_bytes')
        available=$(echo "$body" | jq '.available_memory_bytes')
        expected_available=$((total - used))

        if [ "$available" -eq "$expected_available" ]; then
            echo "  ✓ Memory values are consistent (total - used = available)"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo "  ✗ Memory values inconsistent: $total - $used != $available"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo "  ✗ /admin/status failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Admin Dashboard Tests..."
test_admin_status_endpoint
test_admin_status_backend_fields
test_admin_requests_endpoint
test_admin_requests_trace_fields
test_admin_dashboard_html
test_admin_status_memory_consistency
