/**
 * chat_with_graph.ts — TypeScript reference pipeline for the Kompile Local SDK
 *
 * Pipeline (mirrors kompile-chat-local ChatEngine semantics):
 *   1. Open a .kgraph reasoning session (libkompile_reasoning via koffi)
 *   2. Print the tools catalog
 *   3. Open a GGUF model if libsdx_llm is available and KOMPILE_SDK_MODEL_PATH is set
 *   4. Run demo queries with tool-call round-trips
 *   5. Close cleanly
 *
 * Build: npm install && npm run build
 * Run (reasoning-only): npm start
 * Run (full): KOMPILE_SDK_MODEL_PATH=~/.kompile/models/... npm start
 */

import * as path from "path";
import * as fs from "fs";
import { KgrRuntime, KgrSession, KgrError } from "../../bindings/typescript/kompile_reasoning";

// ── Configuration ─────────────────────────────────────────────────────────────

const SDK_ROOT = path.resolve(__dirname, "..", "..");
const DEFAULT_KGRAPH = path.join(SDK_ROOT, "examples", "data", "fixture.kgraph");
const KGRAPH_PATH = process.env["KOMPILE_SDK_KGRAPH"] ?? DEFAULT_KGRAPH;
const MODEL_PATH = process.env["KOMPILE_SDK_MODEL_PATH"] ?? "";
const MAX_TOOL_ROUNDS = 4;

// ── Tool call parsing (kompile-chat-local conventions) ────────────────────────

interface ToolCallJson {
  tool: string;
  args?: Record<string, unknown>;
}

function parseFirstToolCall(text: string): ToolCallJson | null {
  // Format 1: fenced JSON block
  const fencedMatch = text.match(/```(?:json)?\s*(\{[\s\S]*?\})\s*```/);
  if (fencedMatch) {
    try {
      const obj = JSON.parse(fencedMatch[1]);
      if (obj.tool) return obj as ToolCallJson;
    } catch { /* ignore */ }
  }
  // Format 2: bare JSON containing "tool" key
  const bareMatch = text.match(/\{"tool"\s*:[\s\S]*?\}/);
  if (bareMatch) {
    try {
      const obj = JSON.parse(bareMatch[0]);
      if (obj.tool) return obj as ToolCallJson;
    } catch { /* ignore */ }
  }
  return null;
}

// ── Demo queries ──────────────────────────────────────────────────────────────

interface Demo {
  question: string;
  embeddedCall: ToolCallJson;
}

const DEMOS: Demo[] = [
  {
    question: "Does Alice work at acme?",
    embeddedCall: { tool: "ask_graph_verify", args: { atom: "WORKS_AT(alice, acme)" } },
  },
  {
    question: "What entities are in the graph?",
    embeddedCall: { tool: "graph_reasoning_query", args: { operation: "OVERVIEW" } },
  },
  {
    question: "Who works at acme?",
    embeddedCall: { tool: "ask_graph_query", args: { pattern: "WORKS_AT(?x, acme)" } },
  },
];

// ── SDX model loading (optional) ──────────────────────────────────────────────

async function tryLoadModel(): Promise<{ runtime: unknown; model: unknown } | null> {
  if (!MODEL_PATH) return null;

  const sdxLibPath = process.env["SDX_LLM_LIBRARY"]
    ?? path.join(SDK_ROOT, "lib", "libsdx_llm.so");

  if (!fs.existsSync(sdxLibPath)) {
    console.log("[SDK] libsdx_llm.so not found — degraded mode.");
    return null;
  }

  try {
    // Dynamically import sdx_llm binding (koffi worker-thread based)
    // Note: sdx_llm.ts uses a Worker thread for 128MB stack (GraalVM isolate requirement)
    const { SdxLlmRuntime } = await import("../../bindings/typescript/sdx_llm");
    const runtime = await SdxLlmRuntime.create(sdxLibPath);
    console.log(`[SDK] SDX LLM ABI version: ${runtime.abiVersion}`);
    const modelPathExp = MODEL_PATH.replace("~", process.env["HOME"] ?? "~");
    console.log(`[SDK] Loading model: ${modelPathExp}`);
    const model = await runtime.loadModel(modelPathExp);
    return { runtime, model };
  } catch (e) {
    console.log(`[SDK] Warning — model load failed: ${e}`);
    console.log("[SDK] Continuing in degraded mode.");
    return null;
  }
}

// ── Chat engine (degraded path) ───────────────────────────────────────────────

function runDegraded(
  session: KgrSession,
  _question: string,
  embeddedCall: ToolCallJson
): string {
  const args = embeddedCall.args ?? {};
  const result = session.dispatch(embeddedCall.tool, args) as Record<string, unknown>;
  return `TOOL_RESULT ${embeddedCall.tool}: ${JSON.stringify(result, null, 2)}`;
}

// ── Full pipeline chat turn (with model) ──────────────────────────────────────

async function chatTurnWithModel(
  session: KgrSession,
  catalog: unknown[],
  question: string,
  model: unknown
): Promise<string> {
  // Build system prompt
  const systemPrompt = [
    "You are a helpful assistant with access to a knowledge graph reasoning engine.",
    "Emit tool calls as: {\"tool\": \"<name>\", \"args\": {<arguments>}}",
    "Available tools:",
    JSON.stringify(catalog, null, 2),
  ].join("\n");

  let messages: { role: string; content: string }[] = [
    { role: "user", content: question },
  ];

  for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
    // Build prompt
    const prompt = [
      `SYSTEM:\n${systemPrompt}`,
      ...messages.map((m) => `${m.role.toUpperCase()}:\n${m.content}`),
      "ASSISTANT:",
    ].join("\n\n");

    const m = model as { generate: (p: string, opts?: string) => Promise<string> };
    const response = await m.generate(
      prompt,
      JSON.stringify({ maxNewTokens: 512, sampling: { preset: "greedy" } })
    );

    const tc = parseFirstToolCall(response);
    if (!tc) return response.trim();

    messages.push({ role: "assistant", content: response });
    let toolResult: unknown;
    try {
      toolResult = session.dispatch(tc.tool, tc.args ?? {});
    } catch (e) {
      toolResult = { status: "ERROR", message: String(e) };
    }
    const toolResultMsg = `TOOL_RESULT ${tc.tool}: ${JSON.stringify(toolResult)}`;
    messages.push({ role: "user", content: toolResultMsg });
    console.log(`  [round ${round + 1}] tool=${tc.tool} → ${JSON.stringify(toolResult).slice(0, 120)}`);
  }

  // Exceeded rounds
  const m = model as { generate: (p: string, opts?: string) => Promise<string> };
  return (await m.generate(messages.map((msg) => `${msg.role.toUpperCase()}:\n${msg.content}`).join("\n\n"))).trim();
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  console.log("=".repeat(70));
  console.log("Kompile Local SDK — chat_with_graph.ts reference pipeline");
  console.log("=".repeat(70));

  // 1. Open reasoning session
  const runtime = KgrRuntime.create();
  console.log(`\n[KGR] ABI version: ${runtime.abiVersion}`);
  console.log(`[KGR] Opening graph: ${KGRAPH_PATH}`);

  const session = runtime.open(KGRAPH_PATH);

  // 2. Print tools catalog
  const catalog = session.tools() as unknown[];
  console.log(`[KGR] Tools (${catalog.length}): ${catalog.map((t: unknown) => (t as Record<string,unknown>)["name"] ?? "?").join(", ")}`);

  // 3. Verify sanity check
  const verify = session.dispatch("ask_graph_verify", { atom: "WORKS_AT(alice, acme)" }) as Record<string, unknown>;
  console.log(`[KGR] ask_graph_verify WORKS_AT(alice, acme): ${verify["verdict"] ?? JSON.stringify(verify)}`);

  // 4. Try model load
  const sdx = await tryLoadModel();
  if (sdx) {
    console.log(`\n[SDK] Model loaded. Full pipeline active.`);
  } else {
    console.log(`\n[SDK] No model — reasoning-only (degraded) mode.`);
    console.log(`[SDK] Set KOMPILE_SDK_MODEL_PATH to enable full pipeline.\n`);
  }

  // 5. Demo queries
  console.log("\n" + "─".repeat(70));
  console.log(`DEMO QUERIES (${sdx ? "full pipeline" : "degraded"})`);
  console.log("─".repeat(70));

  for (let i = 0; i < DEMOS.length; i++) {
    const demo = DEMOS[i]!;
    console.log(`\nQ${i + 1}: ${demo.question}`);

    let answer: string;
    if (sdx) {
      answer = await chatTurnWithModel(session, catalog, demo.question, sdx.model);
    } else {
      answer = runDegraded(session, demo.question, demo.embeddedCall);
    }
    console.log(`A${i + 1}: ${answer.slice(0, 800)}`);
  }

  // 6. Cleanup
  session.close();

  if (sdx) {
    const m = sdx.model as { dispose?: () => Promise<void> };
    if (m.dispose) await m.dispose();
    const r = sdx.runtime as { dispose?: () => Promise<void> };
    if (r.dispose) await r.dispose();
  }

  runtime.close();
  console.log("\n[SDK] Done.");
}

main().catch((e) => {
  console.error("[SDK] Fatal error:", e);
  process.exit(1);
});
