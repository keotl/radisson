#!/bin/bash

PORT=${1:-9000}

echo "Starting mock backend on port $PORT..."

handle_request() {
    local request_line method path body content_length

    read -r request_line
    method=$(echo "$request_line" | awk '{print $1}')
    path=$(echo "$request_line" | awk '{print $2}')

    content_length=0
    while IFS= read -r line; do
        line=$(echo "$line" | tr -d '\r')
        [ -z "$line" ] && break

        if echo "$line" | grep -iq "^Content-Length:"; then
            content_length=$(echo "$line" | awk '{print $2}' | tr -d '\r')
        fi
    done

    body=""
    if [ "$content_length" -gt 0 ]; then
        body=$(head -c "$content_length")
    fi

    if [ "$method" = "GET" ] && [ "$path" = "/health" ]; then
        echo -ne "HTTP/1.1 200 OK\r\n"
        echo -ne "Content-Type: text/plain\r\n"
        echo -ne "Content-Length: 2\r\n"
        echo -ne "\r\n"
        echo -ne "OK"

    elif [ "$method" = "POST" ] && [ "$path" = "/v1/chat/completions" ]; then

        if echo "$body" | grep -q '"stream"[[:space:]]*:[[:space:]]*true'; then
            local stream_data='data: {"id":"chatcmpl-mock-123","object":"chat.completion.chunk","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-123","object":"chat.completion.chunk","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"delta":{"content":"Mock"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-123","object":"chat.completion.chunk","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"delta":{"content":" streaming"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-123","object":"chat.completion.chunk","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"delta":{"content":" response"},"finish_reason":null}]}

data: {"id":"chatcmpl-mock-123","object":"chat.completion.chunk","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

'
            echo -ne "HTTP/1.1 200 OK\r\n"
            echo -ne "Content-Type: text/event-stream\r\n"
            echo -ne "Cache-Control: no-cache\r\n"
            echo -ne "Connection: keep-alive\r\n"
            echo -ne "\r\n"
            echo -n "$stream_data"
        else
            local response_body='{"id":"chatcmpl-mock-123","object":"chat.completion","created":'$(date +%s)',"model":"mock-model","choices":[{"index":0,"message":{"role":"assistant","content":"Mock response"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}'

            echo -ne "HTTP/1.1 200 OK\r\n"
            echo -ne "Content-Type: application/json\r\n"
            echo -ne "Content-Length: ${#response_body}\r\n"
            echo -ne "\r\n"
            echo -n "$response_body"
        fi
    else
        echo -ne "HTTP/1.1 404 Not Found\r\n"
        echo -ne "Content-Length: 0\r\n"
        echo -ne "\r\n"
    fi
}

while true; do
    handle_request | nc -l -p "$PORT" -q 1
done
