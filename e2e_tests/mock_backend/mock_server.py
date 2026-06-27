#!/usr/bin/env python3

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9000

# Marker a request can include in its message content to make the backend
# respond slowly, giving an e2e test time to abort the client mid-response.
SLOW_MARKER = "[SLOW]"

# Counts of upstream connections the proxy dropped mid-response (i.e. the proxy
# forwarded a client cancellation by tearing down the connection to us). Guarded
# by a lock because ThreadingHTTPServer handles requests on multiple threads.
_disconnect_lock = threading.Lock()
DISCONNECTS = {"streaming": 0, "nonstreaming": 0}


def _record_disconnect(kind):
    with _disconnect_lock:
        DISCONNECTS[kind] += 1


def _request_is_slow(request_data):
    try:
        for msg in request_data.get("messages", []):
            content = msg.get("content", "")
            if isinstance(content, str) and SLOW_MARKER in content:
                return True
    except Exception:
        pass
    return False


class MockBackendHandler(BaseHTTPRequestHandler):
    # Respond with HTTP/1.1 so framed (Content-Length / close-delimited)
    # responses parse cleanly through the proxy's Pekko HTTP client, which
    # rejects chunked entities on HTTP/1.0 responses. Real backends are 1.1.
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass

    def _send_framed(self, status, body, content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _drain_body(self):
        length = int(self.headers.get('Content-Length', 0))
        if length:
            self.rfile.read(length)

    def do_GET(self):
        if self.path == '/health':
            self._send_framed(200, b'OK', 'text/plain')
        elif self.path == '/control/disconnects':
            with _disconnect_lock:
                body = json.dumps(dict(DISCONNECTS)).encode('utf-8')
            self._send_framed(200, body)
        else:
            self._send_framed(404, b'')

    def do_POST(self):
        if self.path == '/control/reset':
            self._drain_body()
            with _disconnect_lock:
                DISCONNECTS["streaming"] = 0
                DISCONNECTS["nonstreaming"] = 0
            self._send_framed(200, b'{"ok":true}')
            return

        if self.path == '/v1/embeddings':
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length).decode('utf-8')

            try:
                request_data = json.loads(body)
                input_data = request_data.get('input', '')

                if isinstance(input_data, str):
                    inputs = [input_data]
                elif isinstance(input_data, list):
                    inputs = input_data
                else:
                    inputs = []

                embeddings = []
                for i, text in enumerate(inputs):
                    embeddings.append({
                        "object": "embedding",
                        "embedding": [0.1] * 1536,
                        "index": i
                    })

                response_data = {
                    "object": "list",
                    "data": embeddings,
                    "model": request_data.get('model', 'mock-embeddings'),
                    "usage": {
                        "prompt_tokens": sum(len(text.split()) for text in inputs),
                        "total_tokens": sum(len(text.split()) for text in inputs)
                    }
                }

                response_body = json.dumps(response_data).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(response_body)))
                self.end_headers()
                self.wfile.write(response_body)
            except Exception as e:
                error_response = json.dumps({"error": str(e)}).encode('utf-8')
                self._send_framed(400, error_response)

        elif self.path == '/v1/chat/completions':
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length).decode('utf-8')

            try:
                request_data = json.loads(body)
                is_streaming = request_data.get('stream', False)
            except Exception:
                request_data = {}
                is_streaming = False

            is_slow = _request_is_slow(request_data)

            if is_streaming:
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'close')
                self.end_headers()

                if is_slow:
                    self._stream_slow()
                    return

                chunks = [
                    {
                        "id": "chatcmpl-mock-123",
                        "object": "chat.completion.chunk",
                        "created": int(time.time()),
                        "model": "mock-model",
                        "choices": [{
                            "index": 0,
                            "delta": {"role": "assistant", "content": ""},
                            "finish_reason": None
                        }]
                    },
                    {
                        "id": "chatcmpl-mock-123",
                        "object": "chat.completion.chunk",
                        "created": int(time.time()),
                        "model": "mock-model",
                        "choices": [{
                            "index": 0,
                            "delta": {"content": "Mock"},
                            "finish_reason": None
                        }]
                    },
                    {
                        "id": "chatcmpl-mock-123",
                        "object": "chat.completion.chunk",
                        "created": int(time.time()),
                        "model": "mock-model",
                        "choices": [{
                            "index": 0,
                            "delta": {"content": " streaming"},
                            "finish_reason": None
                        }]
                    },
                    {
                        "id": "chatcmpl-mock-123",
                        "object": "chat.completion.chunk",
                        "created": int(time.time()),
                        "model": "mock-model",
                        "choices": [{
                            "index": 0,
                            "delta": {"content": " response"},
                            "finish_reason": None
                        }]
                    },
                    {
                        "id": "chatcmpl-mock-123",
                        "object": "chat.completion.chunk",
                        "created": int(time.time()),
                        "model": "mock-model",
                        "choices": [{
                            "index": 0,
                            "delta": {},
                            "finish_reason": "stop"
                        }]
                    }
                ]

                for chunk in chunks:
                    self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode('utf-8'))
                    self.wfile.flush()

                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            else:
                if is_slow:
                    self._respond_slow_nonstreaming()
                    return

                response_data = {
                    "id": "chatcmpl-mock-123",
                    "object": "chat.completion",
                    "created": int(time.time()),
                    "model": "mock-model",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Mock response"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                    }
                }

                response_body = json.dumps(response_data).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(response_body)))
                self.end_headers()
                self.wfile.write(response_body)
        else:
            self._send_framed(404, b'')

    def _stream_slow(self):
        """Emit many SSE chunks slowly so the client can abort mid-stream.

        If the proxy forwards a client cancellation by closing our connection,
        a write/flush raises BrokenPipeError/ConnectionResetError, which we
        record as a streaming disconnect.
        """
        try:
            for i in range(40):
                chunk = {
                    "id": "chatcmpl-mock-slow",
                    "object": "chat.completion.chunk",
                    "created": int(time.time()),
                    "model": "mock-model",
                    "choices": [{
                        "index": 0,
                        "delta": {"content": f" tok{i}"},
                        "finish_reason": None
                    }]
                }
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode('utf-8'))
                self.wfile.flush()
                time.sleep(0.2)

            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            _record_disconnect("streaming")

    def _respond_slow_nonstreaming(self):
        """Send headers (incl. Content-Length) immediately, then dribble the
        JSON body out slowly. The proxy's passthrough starts as soon as headers
        arrive; if the client aborts mid-body the proxy closes our connection
        and the body write raises, which we record as a non-streaming
        disconnect.
        """
        response_data = {
            "id": "chatcmpl-mock-slow",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": "mock-model",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    # Padded so the body is large enough to dribble out over
                    # several seconds.
                    "content": "Mock slow response " + ("x" * 400)
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15
            }
        }

        response_body = json.dumps(response_data).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response_body)))
        self.end_headers()

        try:
            piece = 16
            for offset in range(0, len(response_body), piece):
                self.wfile.write(response_body[offset:offset + piece])
                self.wfile.flush()
                time.sleep(0.2)
        except (BrokenPipeError, ConnectionResetError):
            _record_disconnect("nonstreaming")


if __name__ == '__main__':
    print(f"Starting mock backend on port {PORT}...")
    server = ThreadingHTTPServer(('127.0.0.1', PORT), MockBackendHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down mock backend...")
        server.shutdown()
