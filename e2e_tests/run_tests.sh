#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

source "$SCRIPT_DIR/lib/common.sh"

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --host HOST    Host to test against (default: 127.0.0.1)"
    echo "  --port PORT    Port to test against (default: 8081)"
    echo "  -h, --help     Show this help message"
    exit 0
}

HOST="127.0.0.1"
PORT="8081"

while [[ $# -gt 0 ]]; do
    case $1 in
        --host)
            HOST="$2"
            shift 2
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo "========================================="
echo "radisson E2E Test Suite"
echo "========================================="
echo ""

echo "Checking radisson is running on port $PORT..."
if ! wait_for_server "$HOST" "$PORT" 5 "/health"; then
    echo "ERROR: radisson is not accessible on $HOST:$PORT"
    exit 1
fi
echo "radisson is ready"
echo ""

for test_file in "$SCRIPT_DIR/tests"/*.sh; do
    source "$test_file"
done

echo ""
echo "========================================="
echo "Test Summary"
echo "========================================="
echo "Total:  $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $FAILED_TESTS"
echo "========================================="

if [ "$FAILED_TESTS" -gt 0 ]; then
    exit 1
fi

exit 0
