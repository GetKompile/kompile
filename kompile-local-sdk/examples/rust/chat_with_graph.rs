// chat_with_graph.rs — Rust reference pipeline for the Kompile Local SDK
//
// Pipeline (mirrors kompile-chat-local ChatEngine semantics):
//   1. Open a .kgraph reasoning session (libkompile_reasoning)
//   2. Print the tools catalog
//   3. Open a GGUF model if libsdx_llm is available and KOMPILE_SDK_MODEL_PATH is set
//   4. Run demo queries with tool-call round-trips
//   5. Close cleanly
//
// Build:
//   KGR_LIB_DIR=<sdk>/lib SDX_LLM_LIB_DIR=<sdk>/lib cargo build --release
//
// Run (reasoning-only):
//   LD_LIBRARY_PATH=<sdk>/lib ./target/release/chat_with_graph
//
// Run (full pipeline):
//   KOMPILE_SDK_MODEL_PATH=~/.kompile/models/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf \
//   SDX_NATIVE_LIB_DIR=<sdk>/lib \
//   LD_LIBRARY_PATH=<sdk>/lib ./target/release/chat_with_graph

// Include the kompile_reasoning and sdx_llm modules from the bindings directory.
// In a real project these would be a path-dependency crate; here we inline them.
#[path = "../../bindings/rust/kompile_reasoning.rs"]
mod kompile_reasoning;

#[path = "../../bindings/rust/sdx_llm.rs"]
mod sdx_llm;

use kompile_reasoning::{KgrError, KgrIsolate};
use std::env;

// ── Configuration ──────────────────────────────────────────────────────────────

const MAX_TOOL_ROUNDS: usize = 4;

fn kgraph_path() -> String {
    env::var("KOMPILE_SDK_KGRAPH").unwrap_or_else(|_| {
        // Default: examples/data/fixture.kgraph relative to this binary
        let exe = env::current_exe().unwrap_or_default();
        let sdk_root = exe
            .parent()
            .and_then(|p| p.parent())
            .and_then(|p| p.parent())
            .map(|p| p.to_path_buf())
            .unwrap_or_default();
        sdk_root
            .join("examples")
            .join("data")
            .join("fixture.kgraph")
            .to_string_lossy()
            .into_owned()
    })
}

// ── Tool call parsing ─────────────────────────────────────────────────────────

struct ToolCall {
    tool: String,
    args_json: String,
}

/// Parse the first tool call from model output.
/// Accepts bare JSON `{"tool":"name","args":{...}}` or fenced blocks.
fn parse_first_tool_call(text: &str) -> Option<ToolCall> {
    // Look for {"tool":... pattern
    let start = text.find(r#""tool""#)?;
    // Walk backward to find the opening {
    let before = &text[..start];
    let brace = before.rfind('{')?;
    let fragment = &text[brace..];
    // Find the matching closing }
    let mut depth = 0;
    let mut end = 0;
    for (i, c) in fragment.char_indices() {
        match c {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    end = i + 1;
                    break;
                }
            }
            _ => {}
        }
    }
    if end == 0 { return None; }
    let json = &fragment[..end];

    // Extract "tool" value
    let tool = extract_json_str(json, "tool")?;
    // Extract "args" sub-object (or default to {})
    let args_json = extract_json_obj(json, "args").unwrap_or_else(|| "{}".to_owned());

    Some(ToolCall { tool, args_json })
}

fn extract_json_str(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let start = json.find(&needle)? + needle.len();
    let after = json[start..].trim_start();
    let after = after.strip_prefix(':')?.trim_start();
    if after.starts_with('"') {
        let inner = &after[1..];
        let end = inner.find('"')?;
        Some(inner[..end].to_owned())
    } else {
        None
    }
}

fn extract_json_obj(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let start = json.find(&needle)? + needle.len();
    let after = json[start..].trim_start();
    let after = after.strip_prefix(':')?.trim_start();
    if after.starts_with('{') {
        let mut depth = 0;
        for (i, c) in after.char_indices() {
            match c {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Some(after[..i + 1].to_owned());
                    }
                }
                _ => {}
            }
        }
    }
    None
}

// ── Degraded mode (no model) ──────────────────────────────────────────────────

fn run_degraded(
    session: &kompile_reasoning::KgrSession,
    question: &str,
    embedded_call: Option<&str>,
) -> Result<String, KgrError> {
    if let Some(call_json) = embedded_call {
        if let Some(tc) = parse_first_tool_call(call_json) {
            let result = session.dispatch(&tc.tool, &tc.args_json)?;
            return Ok(format!("TOOL_RESULT {}: {}", tc.tool, result));
        }
    }
    // Fallback: OVERVIEW
    let overview = session.dispatch("graph_reasoning_query", r#"{"operation":"OVERVIEW"}"#)?;
    Ok(format!(
        "[Degraded mode — no LLM. Graph OVERVIEW:\n{}]",
        overview
    ))
}

// ── Main ──────────────────────────────────────────────────────────────────────

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("{}", "=".repeat(70));
    println!("Kompile Local SDK — chat_with_graph.rs reference pipeline");
    println!("{}", "=".repeat(70));

    // 1. Open reasoning session
    let isolate = KgrIsolate::new()?;
    println!("\n[KGR] ABI version: {}", isolate.abi_version());

    let kgraph = kgraph_path();
    println!("[KGR] Opening graph: {}", kgraph);

    let session = isolate.open(Some(&kgraph))?;

    // 2. Print tools catalog
    let catalog_json = session.tools_json()?;
    // Count tools (simple heuristic: count "name" keys at top level)
    let tool_count = catalog_json.matches(r#""name""#).count();
    println!("[KGR] Tools (~{}): (see catalog JSON below)", tool_count);
    println!("{}", &catalog_json[..catalog_json.len().min(400)]);

    // 3. Quick verify
    let verify = session.dispatch("ask_graph_verify", r#"{"atom":"WORKS_AT(alice, acme)"}"#)?;
    println!("\n[KGR] ask_graph_verify WORKS_AT(alice, acme): {}", &verify[..verify.len().min(120)]);

    // 4. Demo queries (degraded mode unless model present)
    let model_path = env::var("KOMPILE_SDK_MODEL_PATH").unwrap_or_default();
    let has_model = !model_path.is_empty();

    let demos: &[(&str, &str)] = &[
        ("Does Alice work at acme?",
         r#"{"tool":"ask_graph_verify","args":{"atom":"WORKS_AT(alice, acme)"}}"#),
        ("What entities are in the graph?",
         r#"{"tool":"graph_reasoning_query","args":{"operation":"OVERVIEW"}}"#),
        ("Who works at acme?",
         r#"{"tool":"ask_graph_query","args":{"pattern":"WORKS_AT(?x, acme)"}}"#),
    ];

    println!("\n{}", "─".repeat(70));
    println!("DEMO QUERIES ({})", if has_model { "full pipeline" } else { "degraded — no model" });
    println!("{}", "─".repeat(70));

    for (i, (question, embedded)) in demos.iter().enumerate() {
        println!("\nQ{}: {}", i + 1, question);
        let answer = if has_model {
            // Full pipeline would call the LLM here; for now degrade with embedded tool call
            // (full LLM integration requires loading sdx_llm — wired in build.rs, but
            //  calling it here is left as an exercise — see sdx_llm.rs in bindings/)
            format!("[Full pipeline not implemented in this minimal example — set KOMPILE_SDK_MODEL_PATH and extend this example using sdx_llm::LlmRuntime]\n{}",
                run_degraded(&session, question, Some(embedded))?)
        } else {
            run_degraded(&session, question, Some(embedded))?
        };
        println!("A{}: {}", i + 1, &answer[..answer.len().min(600)]);
    }

    // 5. Cleanup (Drop handles it, but be explicit)
    drop(session);
    drop(isolate);

    println!("\n[SDK] Done.");
    Ok(())
}
