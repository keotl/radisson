#!/usr/bin/env python3

import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9000


class MockBackendHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        if self.path == '/health':
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'OK')
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
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
                self.send_response(400)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                error_response = json.dumps({"error": str(e)}).encode('utf-8')
                self.wfile.write(error_response)

        elif self.path == '/v1/chat/completions':
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length).decode('utf-8')

            try:
                request_data = json.loads(body)
                is_streaming = request_data.get('stream', False)
            except:
                is_streaming = False

            if is_streaming:
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Connection', 'close')
                self.end_headers()

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
            self.send_response(404)
            self.end_headers()


if __name__ == '__main__':
    print(f"Starting mock backend on port {PORT}...")
    server = HTTPServer(('127.0.0.1', PORT), MockBackendHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down mock backend...")
        server.shutdown()
