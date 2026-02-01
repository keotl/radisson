#!/bin/bash

test_openai_models_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/v1/models")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ] && echo "$body" | jq -e '.data' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data[] | select(.id == "local-mock")' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.data[] | select(.id == "remote-mock")' > /dev/null 2>&1; then
        echo "  ✓ GET /v1/models returns OpenAI-style model list with local-mock and remote-mock"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ GET /v1/models returns OpenAI-style model list with local-mock and remote-mock (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

test_ollama_models_endpoint() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    response=$(http_get "http://$HOST:$PORT/api/tags")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" -eq 200 ] && echo "$body" | jq -e '.models' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.models[] | select(.name | startswith("local-mock"))' > /dev/null 2>&1 && \
       echo "$body" | jq -e '.models[] | select(.name | startswith("remote-mock"))' > /dev/null 2>&1; then
        echo "  ✓ GET /api/tags returns Ollama-style model list with local-mock and remote-mock"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo "  ✗ GET /api/tags returns Ollama-style model list with local-mock and remote-mock (got $http_code)"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

echo "Running Model Listing Tests..."
test_openai_models_endpoint
test_ollama_models_endpoint
