#!/bin/bash

wait_for_server() {
    local host=$1
    local port=$2
    local timeout=${3:-30}
    local endpoint=${4:-/health}

    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if curl -s "http://$host:$port$endpoint" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    return 1
}

http_get() {
    local url=$1
    curl -s -w "\n%{http_code}" "$url"
}

http_post() {
    local url=$1
    local data=$2
    curl -s -w "\n%{http_code}" -X POST -H "Content-Type: application/json" -d "$data" "$url"
}

http_post_stream() {
    local url=$1
    local data=$2
    curl -s -N -X POST -H "Content-Type: application/json" -d "$data" "$url"
}
