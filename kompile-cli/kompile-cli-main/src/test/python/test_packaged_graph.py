"""Packaged MCP graph regression. Run with --jar <fresh Maven shaded CLI jar>.
Uses only stdlib, temporary HOME/corpus, and public MCP tools. No installed files change.
This qualifies the agent-facing transport, not an LLM's autonomous tool selection.
"""
import argparse
import collections
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time


class Mcp:
    def __init__(self, jar, root, home, java):
        env = {k: v for k, v in os.environ.items() if not k.startswith("KOMPILE_")}
        env.update(HOME=str(home), KOMPILE_CODE_INDEX_BACKGROUND="false")
        self.process = subprocess.Popen(
            [java, "-Xmx1g", "-Duser.home=" + str(home), "-jar", str(jar),
             "mcp-stdio", "--work-dir", str(root), "--profile", "full", "--schema-level", "none"],
            cwd=root, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.errors = collections.deque(maxlen=40)
        self.seq = 0
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()
        try:
            self.rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                    "clientInfo": {"name": "packaged-graph-regression", "version": "1"}})
            self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
            self.process.stdin.flush()
        except BaseException:
            self.close()
            raise

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def _stderr(self):
        for line in self.process.stderr:
            self.errors.append(line.rstrip())

    def rpc(self, method, params):
        self.seq += 1
        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.seq,
                                             "method": method, "params": params}) + "\n")
        self.process.stdin.flush()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty:
                raise AssertionError(f"MCP timeout: {method}; stderr={list(self.errors)}")
            if line is None:
                raise AssertionError(f"MCP exited: {self.process.poll()}; stderr={list(self.errors)}")
            response = json.loads(line)  # Non-JSON stdout is a transport failure.
            if response.get("id") == self.seq:
                assert "error" not in response, response
                return response["result"]
        raise AssertionError("MCP deadline expired")

    def call(self, name, **arguments):
        print("MCP", name, flush=True)
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        assert not result.get("isError"), result
        structured = result.get("structuredContent", {})
        output = structured.get("output") or "\n".join(
            c.get("text", "") for c in result.get("content", []))
        data = None
        try:
            data = json.loads(output)
        except ValueError:
            pass
        return output, structured.get("metadata", {}), data

    def close(self):
        forced = False
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            forced = True
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        for stream in (self.process.stdout, self.process.stderr):
            stream.close()
        assert not forced and self.process.returncode == 0, (
            f"MCP did not shut down cleanly: forced={forced}, exit={self.process.returncode}", list(self.errors))


def pdf(path):
    text = b"BT /F1 12 Tf 72 720 Td (Packaged PDF evidence: violet orchard.) Tj ET"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
               b"<< /Length " + str(len(text)).encode() + b" >>\nstream\n" + text + b"\nendstream"]
    data = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data.extend(f"{i} 0 obj\n".encode() + obj + b"\nendobj\n")
    start = len(data)
    data.extend(b"xref\n0 6\n0000000000 65535 f \n")
    for offset in offsets[1:]:
        data.extend(f"{offset:010d} 00000 n \n".encode())
    data.extend(f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode())
    path.write_bytes(data)


def qualify(jar, java):
    with tempfile.TemporaryDirectory(prefix="kompile-packaged-graph-") as temp:
        home = Path(temp) / "home"
        root = Path(temp) / "project"
        home.mkdir()
        root.mkdir()
        (root / "Example.java").write_text("class Example { void run() {} }\n")
        pdf(root / "evidence.pdf")
        kb = "qualification-knowledge"
        client = Mcp(jar, root, home, java)
        try:
            client.call("local_code_index", action="index", directory=str(root), project_id="qualification",
                        include_patterns="*.java", background=False)
            out, _, _ = client.call("graph_search", query="Example", knowledgeBase=kb)
            assert "Example" in out, out
            out, meta, data = client.call("crawl_documents", documents=[{"path": str(root / "evidence.pdf")}],
                knowledgeBase={"name": kb}, steps=["LOADING", "MARKDOWN_EXTRACTION", "CHUNKING", "LEXICAL_INDEX"],
                strictSteps=True, deriveOntology=False, reasoningLearning={"enabled": False},
                embeddingTraining={"enabled": False}, runtimeConfig={"runReasoningLearning": False})
            job = meta.get("jobId") or (data or {}).get("jobId")
            assert job, (out, meta)
            deadline = time.monotonic() + 120
            while True:
                out, meta, data = client.call("crawl_control", operation="status", jobId=job)
                state = data or meta
                if state.get("terminal"):
                    break
                assert time.monotonic() < deadline, (out, meta)
                time.sleep(max(0.05, min(5, state.get("pollAfterMs", 250) / 1000)))
            client.call("crawl_result", jobId=job)
            out, meta, _ = client.call("knowledge_search", query="violet orchard", knowledgeBase=kb)
            assert "violet orchard" in out, out
            assert any(e.get("pages") == [1] for e in meta.get("evidence", [])), (out, meta)
            out, meta, data = client.call("ask_graph_assert", atom="RELATED_TO(Alex, Morgan)", value=1.0, knowledgeBase=kb)
            print("ASSERT RECEIPT", out, meta, flush=True)
            assert data and data.get("status") == "ASSERTED", (out, meta)
            out, meta, _ = client.call("graph_search", query="Alex", knowledgeBase=kb)
            print("BEFORE RESTART", out, meta, flush=True)
            client.call("graph_export", path=str(root / "snapshot.kgraph"), knowledgeBase=kb)
        finally:
            client.close()
        # New process: no in-memory graph/index state can satisfy the following assertions.
        client = Mcp(jar, root, home, java)
        try:
            out, _, _ = client.call("graph_search", query="Example", knowledgeBase=kb)
            assert "Example" in out, out
            out, meta, _ = client.call("graph_search", query="Alex", knowledgeBase=kb)
            print("AFTER RESTART", out, meta, flush=True)
            out, meta, data = client.call("graph_reasoning_query", operation="VERIFY", entityId="Alex",
                                      targetId="Morgan", relationTypes=["RELATED_TO"], knowledgeBase=kb)
            assert meta.get("status") == "SUPPORTED" or (data and data.get("status") == "SUPPORTED"), (out, meta)
            client.call("code_graph", action="remove_directory", directory_path=str(root), project_id="qualification")
            out, _, _ = client.call("graph_search", query="Example", knowledgeBase=kb)
            assert "[id:" not in out, out
            out, meta, _ = client.call("knowledge_search", query="violet orchard", knowledgeBase=kb)
            assert "violet orchard" in out and meta.get("evidence"), (out, meta)
            assert (root / "snapshot.kgraph").is_file()
        finally:
            client.close()
        print("PASS: packaged MCP PDF citations, graph retrieval, persistence/restart and scoped removal", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    qualify(args.jar.resolve(strict=True), args.java)
