#!/bin/bash

test_basic_embedding_request() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-embeddings-mock","input":"Hello world"}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 200 ]; then
        echo "  ✓ Basic embedding request returns 200"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Basic embedding request failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_embedding_response_structure() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-embeddings-mock","input":"Test text"}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ] && \
       echo "$body" | jq -e '.object == "list"' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data[0].object == "embedding"' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data[0].embedding' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data[0].index == 0' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.usage.prompt_tokens' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.usage.total_tokens' > /dev/null 2>&1; then
        echo "  ✓ Embedding response has correct structure"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Embedding response structure validation failed"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_multiple_inputs() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-embeddings-mock","input":["First text","Second text","Third text"]}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    local embedding_count=$(echo "$body" | jq -r '.data | length')

    if [ "$http_code" -eq 200 ] && [ "$embedding_count" -eq 3 ]; then
        echo "  ✓ Multiple inputs return correct number of embeddings"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Multiple inputs test failed (got $http_code, count: $embedding_count)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_invalid_model() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"nonexistent-model","input":"Test"}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 400 ]; then
        echo "  ✓ Invalid model returns 400"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Invalid model test failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_chat_model_rejected_for_embeddings() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-mock","input":"Test text"}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 400 ]; then
        echo "  ✓ Chat model rejected for embeddings (400)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Chat model should be rejected for embeddings (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_embedding_model_rejected_for_chat() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"local-embeddings-mock","messages":[{"role":"user","content":"Test"}]}'
    response=$(http_post "http://$HOST:$PORT/v1/chat/completions" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 400 ]; then
        echo "  ✓ Embedding model rejected for chat (400)"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Embedding model should be rejected for chat (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_empty_model() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    local payload='{"model":"","input":"Test"}'
    response=$(http_post "http://$HOST:$PORT/v1/embeddings" "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" -eq 400 ]; then
        echo "  ✓ Empty model returns 400"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ Empty model test failed (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Embedding Tests..."
test_basic_embedding_request
test_embedding_response_structure
test_multiple_inputs
test_invalid_model
test_chat_model_rejected_for_embeddings
test_embedding_model_rejected_for_chat
test_empty_model
