#!/usr/bin/env python3
"""
openai_stub.py — Stateful OpenAI-compatible HTTP stub for kompile-chat-local round-trip testing.

Sequence (per conversation):
  Request 1: assistant returns a tool-call for `ask_graph_query` (simulates the graph tool path)
  Request 2: assistant returns a plain text answer (closes the turn)
  Request 3+: cycles back to request 1 (each user message starts a fresh 2-step sequence)

Usage:
  python3 openai_stub.py [--port 8971]

The Android app sends requests to http://10.0.2.2:8971 (host alias seen from emulator).
"""

import argparse
import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

# ---------- state ----------
# Map session_id (conversation_id derived from first user message content hash)
# to the number of calls made in this conversation.  We use message count as a
# simple proxy: odd call = tool-call response, even call = final answer.
_call_counter: dict[str, int] = {}


def _session_key(body: dict) -> str:
    """Derive a stable session key from the messages list."""
    msgs = body.get("messages", [])
    # Use the content of the first user message as the session anchor.
    for m in msgs:
        if m.get("role") == "user":
            return str(hash(str(m.get("content", ""))))
    return "default"


def _tool_call_response(model: str) -> dict:
    """Return an assistant message that requests a graph query tool call."""
    call_id = f"call_{uuid.uuid4().hex[:16]}"
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": call_id,
                            "type": "function",
                            "function": {
                                "name": "ask_graph_query",
                                "arguments": json.dumps(
                                    {"query": "Who does Alice work for?"}
                                ),
                            },
                        }
                    ],
                },
                "finish_reason": "tool_calls",
            }
        ],
        "usage": {"prompt_tokens": 20, "completion_tokens": 15, "total_tokens": 35},
    }


def _final_answer_response(model: str) -> dict:
    """Return a plain text assistant answer."""
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": (
                        "According to the knowledge graph, Alice works at Acme Corp. "
                        "This was confirmed via the graph reasoning tool."
                    ),
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 40, "completion_tokens": 22, "total_tokens": 62},
    }


class StubHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # noqa: N802
        print(f"[stub] {self.address_string()} {fmt % args}", flush=True)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            self._send_json(400, {"error": "bad json"})
            return

        if self.path in ("/v1/chat/completions", "/chat/completions"):
            model = body.get("model", "stub-model")
            key = _session_key(body)
            _call_counter[key] = _call_counter.get(key, 0) + 1
            call_num = _call_counter[key]

            if call_num % 2 == 1:
                # Odd call: return tool call
                resp = _tool_call_response(model)
                print(f"[stub] key={key[:8]} call={call_num} -> tool_call", flush=True)
            else:
                # Even call: return final answer (tool result was fed back)
                resp = _final_answer_response(model)
                print(f"[stub] key={key[:8]} call={call_num} -> final_answer", flush=True)

            self._send_json(200, resp)
        elif self.path == "/v1/models":
            self._send_json(
                200,
                {
                    "object": "list",
                    "data": [{"id": "stub-model", "object": "model"}],
                },
            )
        else:
            self._send_json(404, {"error": f"unknown path {self.path}"})

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    ap = argparse.ArgumentParser(description="Stateful OpenAI stub for kompile-chat-local e2e tests")
    ap.add_argument("--port", type=int, default=8971, help="Port to listen on (default 8971)")
    ap.add_argument("--host", default="0.0.0.0", help="Bind address (default 0.0.0.0)")
    args = ap.parse_args()

    server = HTTPServer((args.host, args.port), StubHandler)
    print(f"[stub] Listening on http://{args.host}:{args.port}", flush=True)
    print(f"[stub] Emulator reaches host at http://10.0.2.2:{args.port}", flush=True)
    print("[stub] Sequence: odd call -> tool_call(ask_graph_query), even call -> final answer", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[stub] Stopped.", flush=True)


if __name__ == "__main__":
    main()
