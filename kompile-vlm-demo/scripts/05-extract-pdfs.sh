#!/usr/bin/env bash
# Extract image-based PDFs through the project-local stdio MCP pipeline runtime.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOMPILE_BIN="${KOMPILE_BIN:-kompile}"
export KOMPILE_VLM_MODEL_ID="${KOMPILE_VLM_MODEL_ID:-smoldocling-256m}"
export KOMPILE_VLM_OUTPUT_FORMAT="${KOMPILE_VLM_OUTPUT_FORMAT:-MARKDOWN}"
export KOMPILE_VLM_MAX_PAGES="${KOMPILE_VLM_MAX_PAGES:-3}"
export KOMPILE_VLM_PDF_DPI="${KOMPILE_VLM_PDF_DPI:-150}"
export KOMPILE_VLM_MAX_NEW_TOKENS="${KOMPILE_VLM_MAX_NEW_TOKENS:-2048}"
export KOMPILE_BIN DEMO_DIR

command -v "${KOMPILE_BIN}" >/dev/null 2>&1 || {
  echo "ERROR: ${KOMPILE_BIN} is not installed" >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 is required for the stdio MCP demo client" >&2
  exit 1
}

python3 <<'PY'
import json
import os
import pathlib
import shutil
import subprocess
import sys

root = pathlib.Path(os.environ["DEMO_DIR"])
pdfs = sorted(root.joinpath("sample-pdfs").glob("*.[pP][dD][fF]"))
if not pdfs:
    raise SystemExit(f"ERROR: no PDFs found under {root / 'sample-pdfs'}")

proc = subprocess.Popen(
    [os.environ["KOMPILE_BIN"], "mcp-stdio"],
    cwd=root,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=sys.stderr,
    text=True,
    bufsize=1,
)
next_id = 0

def request(method, params=None):
    global next_id
    next_id += 1
    message = {"jsonrpc": "2.0", "id": next_id, "method": method}
    if params is not None:
        message["params"] = params
    proc.stdin.write(json.dumps(message) + "\n")
    proc.stdin.flush()
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("stdio MCP process exited before responding")
        try:
            response = json.loads(line)
        except json.JSONDecodeError:
            continue
        if response.get("id") != next_id:
            continue
        if "error" in response:
            raise RuntimeError(response["error"])
        return response.get("result", {})

def notify(method, params=None):
    message = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        message["params"] = params
    proc.stdin.write(json.dumps(message) + "\n")
    proc.stdin.flush()

def call_tool(name, arguments):
    result = request("tools/call", {"name": name, "arguments": arguments})
    if result.get("isError"):
        text = "\n".join(item.get("text", "") for item in result.get("content", []))
        raise RuntimeError(f"{name} failed: {text}")
    return result

try:
    request("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "kompile-vlm-demo", "version": "1"},
    })
    notify("notifications/initialized")

    model_id = os.environ["KOMPILE_VLM_MODEL_ID"]
    call_tool("model_runtime", {
        "action": "bootstrap",
        "modelId": model_id,
        "autoBootstrap": True,
        "timeoutMinutes": 60,
    })

    pipeline_id = "vlm-demo-pdf"
    call_tool("crawl_documents", {
        "name": "VLM demo PDF extraction",
        "knowledgeBase": {"name": "vlm-demo-extracted"},
        "documents": [
            {"path": str(pdf), "sourceType": "FILE", "pipelineId": pipeline_id}
            for pdf in pdfs
        ],
        "pipelines": [{
            "pipelineId": pipeline_id,
            "pipelineType": "VLM",
            "loaderName": "pdf",
            "chunkerName": "sentence",
            "modelId": model_id,
            "options": {
                "outputFormat": os.environ["KOMPILE_VLM_OUTPUT_FORMAT"],
                "maxPages": int(os.environ["KOMPILE_VLM_MAX_PAGES"]),
                "pdfRenderDpi": int(os.environ["KOMPILE_VLM_PDF_DPI"]),
                "maxNewTokens": int(os.environ["KOMPILE_VLM_MAX_NEW_TOKENS"]),
                "pageBatchSize": 1,
                "temperature": 0.0,
                "doSample": False,
            },
        }],
        "defaultPipelineId": pipeline_id,
        "modelRuntime": {"autoBootstrap": False},
        "steps": ["LOADING", "MARKDOWN_EXTRACTION", "CHUNKING"],
        "strictSteps": True,
        "deriveOntology": False,
        "embeddingTraining": {"enabled": False},
        "reasoningLearning": {"enabled": False},
    })

    source = root / "data" / "markdown" / "vlm-demo-extracted"
    destination = root / "var" / "extracted"
    destination.mkdir(parents=True, exist_ok=True)
    copied = 0
    if source.is_dir():
        for markdown in source.rglob("*.md"):
            shutil.copy2(markdown, destination / markdown.name)
            copied += 1
    print(f"Extracted {len(pdfs)} PDF(s) through stdio MCP; copied {copied} markdown artifact(s) to {destination}")
finally:
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
PY
