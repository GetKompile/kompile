#!/usr/bin/env python3
"""
chat_with_graph.py — Reference pipeline for the Kompile Local SDK

Pipeline (mirrors kompile-chat-local ChatEngine semantics):
  1. Open a .kgraph reasoning session (libkompile_reasoning via ctypes)
  2. Print the tools catalog
  3. Open a GGUF model if libsdx_llm.so is available and KOMPILE_SDK_MODEL_PATH is set
  4. Chat loop: user message → system prompt (instructions + tool catalog) →
     model generation → tool call parsing → kgr_dispatch → TOOL_RESULT injection →
     re-generation (max 4 rounds) → print response
  5. Close everything cleanly

Run (reasoning-only / degraded mode — works today with no model):
    python3 chat_with_graph.py

Run (full pipeline with model):
    KOMPILE_SDK_MODEL_PATH=~/.kompile/models/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf \\
        python3 chat_with_graph.py

Environment variables:
    KGR_LIBRARY             Explicit path to libkompile_reasoning.so
    SDX_LLM_AOT_HOME        Directory containing lib/libsdx_llm.so + side-loaded libs
    KOMPILE_SDK_MODEL_PATH  Path to a GGUF chat model
    KOMPILE_SDK_KGRAPH      Path to a .kgraph file (default: examples/data/fixture.kgraph)
"""

import json
import os
import re
import sys
import textwrap
from typing import Optional

# ── SDK path bootstrap ────────────────────────────────────────────────────────

_SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
_SDK_ROOT     = os.path.join(_SCRIPT_DIR, "..", "..")

# Add bindings to sys.path so we can import kompile_reasoning + sdx_llm
_BINDINGS_PY = os.path.join(_SDK_ROOT, "bindings", "python")
if _BINDINGS_PY not in sys.path:
    sys.path.insert(0, _BINDINGS_PY)

from kompile_reasoning import KomReasoningLib, find_library as find_kgr_library

# ── Configuration ──────────────────────────────────────────────────────────────

_DEFAULT_KGRAPH = os.path.join(_SDK_ROOT, "examples", "data", "fixture.kgraph")
KGRAPH_PATH     = os.environ.get("KOMPILE_SDK_KGRAPH",    _DEFAULT_KGRAPH)
MODEL_PATH      = os.environ.get("KOMPILE_SDK_MODEL_PATH", "")
SDX_AOT_HOME    = os.environ.get("SDX_LLM_AOT_HOME",       "")
MAX_TOOL_ROUNDS = 4

# ── Tool call parsing (kompile-chat-local conventions) ────────────────────────

# Two formats accepted:
#   Format 1: bare JSON object:   {"tool": "name", "args": {...}}
#   Format 2: fenced JSON block:  ```json\n{"tool":...}\n```
_FENCED_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)
_BARE_RE   = re.compile(r'^\s*(\{"tool"\s*:.*?\})\s*$', re.MULTILINE | re.DOTALL)


def parse_tool_calls(text: str) -> list[dict]:
    """Extract all tool call JSON objects from model output."""
    calls = []
    for m in _FENCED_RE.finditer(text):
        try:
            obj = json.loads(m.group(1))
            if "tool" in obj:
                calls.append(obj)
        except json.JSONDecodeError:
            pass
    if not calls:
        for m in _BARE_RE.finditer(text):
            try:
                obj = json.loads(m.group(1))
                if "tool" in obj:
                    calls.append(obj)
            except json.JSONDecodeError:
                pass
    return calls


# ── System prompt builder ─────────────────────────────────────────────────────

def build_system_prompt(tools_catalog: list[dict]) -> str:
    tools_json = json.dumps(tools_catalog, indent=2)
    return textwrap.dedent(f"""\
        You are a helpful assistant with access to a knowledge graph reasoning engine.
        To query the graph, emit a tool call in one of these formats:

        Format 1 (preferred — bare JSON on its own line):
        {{"tool": "<tool_name>", "args": {{<arguments>}}}}

        Format 2 (fenced JSON block):
        ```json
        {{"tool": "<tool_name>", "args": {{<arguments>}}}}
        ```

        Available tools:
        {tools_json}

        After you receive a TOOL_RESULT, incorporate the result into your answer.
        Emit at most one tool call per response turn.
    """)


# ── Model integration (optional) ──────────────────────────────────────────────

def try_load_sdx():
    """Attempt to load libsdx_llm and return (SdxLlmRuntime, SdxLlmModel) or (None, None)."""
    if not MODEL_PATH:
        return None, None

    lib_path = None
    if SDX_AOT_HOME:
        candidate = os.path.join(SDX_AOT_HOME, "lib", "libsdx_llm.so")
        if os.path.isfile(candidate):
            lib_path = candidate
    if not lib_path:
        sdk_candidate = os.path.join(_SDK_ROOT, "lib", "libsdx_llm.so")
        if os.path.isfile(sdk_candidate):
            lib_path = sdk_candidate
            if not SDX_AOT_HOME:
                os.environ["SDX_LLM_AOT_HOME"] = os.path.join(_SDK_ROOT)

    if not lib_path:
        print("[SDK] libsdx_llm.so not found — running in degraded mode (no model).")
        return None, None

    try:
        from sdx_llm import load_library, SdxLlmRuntime
        load_library(lib_path)
        runtime = SdxLlmRuntime()
        print(f"[SDK] SDX LLM ABI version: {runtime.abi_version()}")
        model_path_exp = os.path.expanduser(MODEL_PATH)
        print(f"[SDK] Loading model: {model_path_exp}")
        model = runtime.load_model(model_path_exp)
        return runtime, model
    except Exception as e:
        print(f"[SDK] Warning — model load failed: {e}")
        print("[SDK] Continuing in degraded mode (graph reasoning only).")
        return None, None


# ── Chat engine ───────────────────────────────────────────────────────────────

def generate_with_model(model, system_prompt: str, messages: list[dict]) -> str:
    """Call the model with the current message history."""
    # Build a flat prompt in the format: SYSTEM\n...\nUSER\n...\nASSISTANT
    parts = [f"SYSTEM:\n{system_prompt}\n"]
    for msg in messages:
        role = msg.get("role", "user").upper()
        parts.append(f"{role}:\n{msg.get('content', '')}\n")
    parts.append("ASSISTANT:\n")
    prompt = "\n".join(parts)
    options_json = json.dumps({"maxNewTokens": 512, "sampling": {"preset": "greedy"}})
    return model.generate(prompt, options_json=options_json)


def chat_turn(
    session,
    tools_catalog: list[dict],
    user_message: str,
    model=None,
    system_prompt: str = "",
) -> str:
    """
    Execute one user turn with up to MAX_TOOL_ROUNDS tool-call rounds.

    Returns the final assistant response text.
    """
    messages = [{"role": "user", "content": user_message}]

    if model is None:
        # Degraded mode: directly dispatch the first tool call found in the user message,
        # or run an OVERVIEW query and return the raw result.
        tool_calls = parse_tool_calls(user_message)
        if tool_calls:
            call = tool_calls[0]
            tool = call.get("tool", "")
            args = call.get("args", {})
            result = session.dispatch(tool, args)
            return f"TOOL_RESULT {tool}: {json.dumps(result, indent=2)}"
        else:
            overview = session.dispatch("graph_reasoning_query", {"operation": "OVERVIEW"})
            return (
                f"[Degraded mode — no LLM loaded. Graph OVERVIEW:\n"
                f"{json.dumps(overview, indent=2)}]"
            )

    # Full pipeline with model
    for round_idx in range(MAX_TOOL_ROUNDS):
        response = generate_with_model(model, system_prompt, messages)

        # Parse tool calls from response
        tool_calls = parse_tool_calls(response)
        if not tool_calls:
            # No tool call — this is the final response
            return response.strip()

        # Execute first tool call and inject result
        messages.append({"role": "assistant", "content": response})
        call = tool_calls[0]
        tool = call.get("tool", "")
        args = call.get("args", {})
        try:
            result = session.dispatch(tool, args)
        except RuntimeError as e:
            result = {"status": "ERROR", "message": str(e)}

        tool_result_msg = f"TOOL_RESULT {tool}: {json.dumps(result)}"
        messages.append({"role": "user", "content": tool_result_msg})
        print(f"  [round {round_idx + 1}] tool={tool} → {json.dumps(result)[:120]}")

    # Exceeded rounds — return last model response
    return generate_with_model(model, system_prompt, messages).strip()


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("Kompile Local SDK — chat_with_graph.py reference pipeline")
    print("=" * 70)

    # 1. Open reasoning session
    kgr_lib_path = os.environ.get("KGR_LIBRARY") or find_kgr_library(
        os.path.join(_SDK_ROOT, "lib")
    )
    print(f"\n[KGR] Library: {kgr_lib_path}")
    kgr = KomReasoningLib(kgr_lib_path)
    print(f"[KGR] ABI version: {kgr.abi_version()}")

    kgraph_path = os.path.expanduser(KGRAPH_PATH)
    print(f"[KGR] Opening graph: {kgraph_path}")

    with kgr.session(kgraph_path) as sess:
        # 2. Print tools catalog
        catalog = sess.tools()
        tool_names = [t.get("name", t.get("id", "?")) for t in catalog] if isinstance(catalog, list) else []
        print(f"\n[KGR] Tools ({len(tool_names)}): {', '.join(tool_names)}")

        # 3. Quick graph verify sanity check
        verify = sess.dispatch("ask_graph_verify", {"atom": "WORKS_AT(alice, acme)"})
        print(f"[KGR] ask_graph_verify WORKS_AT(alice, acme): {verify.get('verdict', verify)}")

        # 4. Try to load model
        sdx_runtime, model = try_load_sdx()
        system_prompt = build_system_prompt(catalog) if model else ""

        if model:
            print(f"\n[SDK] Model loaded. Full pipeline active.")
        else:
            print(f"\n[SDK] No model. Running in reasoning-only (degraded) mode.")
            print("[SDK] Set KOMPILE_SDK_MODEL_PATH to enable the full pipeline.\n")

        # 5. Demonstration queries
        demo_queries = [
            ("Does Alice work at acme?",
             '{"tool": "ask_graph_verify", "args": {"atom": "WORKS_AT(alice, acme)"}}'),
            ("What can you tell me about the entities in this graph?",
             '{"tool": "graph_reasoning_query", "args": {"operation": "OVERVIEW"}}'),
            ("Explain why Alice works at acme.",
             '{"tool": "ask_graph_explain", "args": {"target": "WORKS_AT(alice, acme)"}}'),
        ]

        print("\n" + "─" * 70)
        print("DEMO QUERIES")
        print("─" * 70)

        for i, (question, embedded_tool_call) in enumerate(demo_queries, 1):
            print(f"\nQ{i}: {question}")
            # In degraded mode we embed the tool call in the user message so parse_tool_calls finds it.
            # In full-model mode we let the model decide which tool to call.
            user_msg = question if model else f"{question}\n{embedded_tool_call}"
            response = chat_turn(sess, catalog, user_msg, model, system_prompt)
            # Pretty-print JSON responses
            try:
                parsed = json.loads(response) if response.startswith("{") else None
                if parsed:
                    response = json.dumps(parsed, indent=2)
            except Exception:
                pass
            print(f"A{i}: {response[:800]}")
            print()

        # 6. Interactive mode (optional)
        if sys.stdin.isatty():
            print("─" * 70)
            print("Interactive mode — type your questions (Ctrl+C to exit)")
            print("─" * 70)
            try:
                while True:
                    try:
                        user_input = input("\nYou: ").strip()
                    except EOFError:
                        break
                    if not user_input:
                        continue
                    if user_input.lower() in {"exit", "quit", "q"}:
                        break
                    response = chat_turn(sess, catalog, user_input, model, system_prompt)
                    print(f"\nAssistant: {response}")
            except KeyboardInterrupt:
                pass
            print("\n[SDK] Exiting interactive mode.")

    # 7. Cleanup
    if sdx_runtime:
        if model:
            model.unload()
        sdx_runtime.destroy()
    kgr.close()

    print("\n[SDK] Done.")


if __name__ == "__main__":
    main()
