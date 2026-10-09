#!/bin/bash

# Verifies that radisson notices a backend whose server died while the launched
# process stayed alive (e.g. an attached `docker run`), and restarts it on the
# next request instead of routing to a dead endpoint forever.

CRASHY_URL="http://127.0.0.1:9004"

crash_warm_up() {
    local payload='{"model":"crashy-mock","messages":[{"role":"user","content":"warmup"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    [ "$(echo "$response" | tail -n1)" = "200" ]
}

test_crashed_backend_is_restarted() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local crash_payload='{"model":"crashy-mock","stream":true,"messages":[{"role":"user","content":"[CRASH] please"}]}'
    http_post_stream "http://$HOST:$PORT/v1/chat/completions" "$crash_payload" > /dev/null 2>&1 || true

    if curl -s --max-time 2 "$CRASHY_URL/health" > /dev/null 2>&1; then
        echo "  ✗ Backend crash: mock backend is still serving after [CRASH]"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return
    fi

    local payload='{"model":"crashy-mock","messages":[{"role":"user","content":"Hello again"}]}'
    local elapsed=0
    local http_code=""
    while [ $elapsed -lt 40 ]; do
        response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
        http_code=$(echo "$response" | tail -n1)
        if [ "$http_code" = "200" ]; then
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    if [ "$http_code" = "200" ]; then
        echo "  ✓ Backend crash: radisson detects the dead backend and restarts it"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Backend crash: radisson detects the dead backend and restarts it (last status $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Backend Crash Tests..."
if crash_warm_up; then
    test_crashed_backend_is_restarted
else
    echo "  ! Skipping backend crash tests: crashy-mock did not start"
fi
