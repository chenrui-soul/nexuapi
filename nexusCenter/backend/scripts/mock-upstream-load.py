#!/usr/bin/env python3
"""Deterministic local upstream for Gateway load tests.

This server never calls a real supplier. It accepts OpenAI-compatible JSON and
returns a small deterministic response while recording request counts.
"""
import argparse
import json
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.total = 0
        self.by_path = {}
        self.in_flight = 0
        self.max_in_flight = 0
        self.started = time.time()

    def record_start(self, path):
        with self.lock:
            self.total += 1
            self.by_path[path] = self.by_path.get(path, 0) + 1
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)

    def record_end(self):
        with self.lock:
            self.in_flight -= 1

    def snapshot(self):
        with self.lock:
            return {
                "total": self.total,
                "by_path": dict(self.by_path),
                "in_flight": self.in_flight,
                "max_in_flight": self.max_in_flight,
                "uptime_seconds": round(time.time() - self.started, 3),
            }


STATE = State()


class LoadTestServer(ThreadingHTTPServer):
    # Keep the mock listener from becoming the bottleneck at 100 VU.
    request_queue_size = 512


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/__stats":
            self._send_json(STATE.snapshot())
            return
        if self.path == "/health":
            self._send_json({"status": "ok"})
            return
        self._send_json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        if self.path == "/__reset":
            global STATE
            STATE = State()
            self._send_json({"ok": True})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            request = json.loads(raw.decode("utf-8"))
        except Exception:
            request = {}
        STATE.record_start(self.path)
        try:
            # Small deterministic delay approximates a fast upstream without
            # making the load test depend on network variability.
            time.sleep(0.02)
            model = request.get("model", "loadtest-model")
            if self.path.endswith("/responses"):
                if request.get("stream"):
                    chunks = [
                        {"type": "response.output_text.delta", "delta": "load"},
                        {"type": "response.output_text.delta", "delta": "test"},
                        {"type": "response.completed"},
                    ]
                    body = b"".join(
                        (b"data: " + json.dumps(c, separators=(",", ":")).encode() + b"\n\n")
                        for c in chunks
                    ) + b"data: [DONE]\n\n"
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self._send_json({
                        "id": "resp_loadtest",
                        "object": "response",
                        "model": model,
                        "output": [{"type": "message", "content": [{"type": "output_text", "text": "loadtest"}]}],
                        "usage": {"input_tokens": 8, "output_tokens": 2, "total_tokens": 10},
                    })
            else:
                self._send_json({
                    "id": "chatcmpl_loadtest",
                    "object": "chat.completion",
                    "model": model,
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": "loadtest"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10},
                })
        finally:
            STATE.record_end()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()
    server = LoadTestServer((args.host, args.port), Handler)
    signal.signal(signal.SIGTERM, lambda *_: server.shutdown())
    signal.signal(signal.SIGINT, lambda *_: server.shutdown())
    print(json.dumps({"listening": f"{args.host}:{args.port}"}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
