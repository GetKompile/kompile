// chat_with_graph.swift — Swift reference pipeline for the Kompile Local SDK
//
// Syntax review only — no swiftc on Linux.
// Build on macOS: swift build -Xlinker -L<sdk>/lib -Xcc -I<sdk>/include
//
// Pipeline (mirrors kompile-chat-local ChatEngine semantics):
//   1. Open a .kgraph reasoning session (KgrIsolate from KompileReasoning.swift)
//   2. Print tools catalog
//   3. Open model if KOMPILE_SDK_MODEL_PATH is set (SdxLlmRuntime from SdxLlm.swift)
//   4. Demo queries with tool-call round-trips
//   5. Close cleanly

import Foundation

// Imports below require the bridging module maps set up for the build target.
// In a SwiftPM package:
//   - CSdxKgr system library target exposes kompile_reasoning.h
//   - CSdxLlm system library target exposes sdx_llm_c.h
// Both KcompileReasoning.swift and SdxLlm.swift are source files in the main target.

// ── Configuration ──────────────────────────────────────────────────────────────

let sdkRoot = URL(fileURLWithPath: #file).deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
let defaultKgraph = sdkRoot.appendingPathComponent("examples/data/fixture.kgraph").path
let kgraphPath = ProcessInfo.processInfo.environment["KOMPILE_SDK_KGRAPH"] ?? defaultKgraph
let modelPath = ProcessInfo.processInfo.environment["KOMPILE_SDK_MODEL_PATH"]

// ── Main ──────────────────────────────────────────────────────────────────────

func main() {
    print(String(repeating: "=", count: 70))
    print("Kompile Local SDK — chat_with_graph.swift reference pipeline")
    print(String(repeating: "=", count: 70))

    // 1. Open reasoning session
    let isolate: KgrIsolate
    do {
        isolate = try KgrIsolate()
        print("\n[KGR] ABI version: \(isolate.abiVersion)")
        print("[KGR] Opening graph: \(kgraphPath)")
    } catch {
        print("[KGR] Failed to create isolate: \(error)")
        return
    }

    let session: KgrSession
    do {
        session = try isolate.open(path: kgraphPath)
    } catch {
        print("[KGR] Failed to open graph: \(error)")
        isolate.close()
        return
    }
    defer {
        session.close()
        isolate.close()
    }

    // 2. Print tools catalog
    do {
        let catalog = try session.tools()
        let names = catalog.compactMap { $0["name"] as? String }
        print("[KGR] Tools (\(names.count)): \(names.joined(separator: ", "))")
    } catch {
        print("[KGR] Failed to get tools: \(error)")
    }

    // 3. Verify sanity check
    do {
        let verify = try session.dispatch(tool: "ask_graph_verify",
                                          argsJSON: #"{"atom":"WORKS_AT(alice, acme)"}"#)
        let verdict = verify["verdict"] as? String ?? "(none)"
        print("[KGR] ask_graph_verify WORKS_AT(alice, acme): \(verdict)")
    } catch {
        print("[KGR] Verify failed: \(error)")
    }

    // 4. Try model load
    var sdxRuntime: SdxLlmRuntime? = nil
    var sdxModel: SdxLlmModel? = nil

    if let mp = modelPath {
        do {
            sdxRuntime = try SdxLlmRuntime()
            print("\n[SDK] SDX LLM ABI version: \(sdxRuntime!.abiVersion())")
            print("[SDK] Loading model: \(mp)")
            sdxModel = try sdxRuntime!.loadModel(modelPath: mp, tokenizerPath: nil, optionsJson: nil)
            print("[SDK] Model loaded. Full pipeline active.")
        } catch {
            print("[SDK] Warning — model load failed: \(error)")
            print("[SDK] Continuing in degraded mode.")
        }
    } else {
        print("\n[SDK] No model — reasoning-only (degraded) mode.")
        print("[SDK] Set KOMPILE_SDK_MODEL_PATH to enable full pipeline.\n")
    }

    defer {
        sdxModel?.close()
        sdxRuntime?.close()
    }

    // 5. Demo queries
    let demos: [(question: String, tool: String, argsJSON: String)] = [
        ("Does Alice work at acme?",
         "ask_graph_verify",
         #"{"atom":"WORKS_AT(alice, acme)"}"#),
        ("What entities are in the graph?",
         "graph_reasoning_query",
         #"{"operation":"OVERVIEW"}"#),
        ("Who works at acme?",
         "ask_graph_query",
         #"{"pattern":"WORKS_AT(?x, acme)"}"#),
    ]

    print("\n" + String(repeating: "─", count: 70))
    print("DEMO QUERIES (\(sdxModel != nil ? "full pipeline" : "degraded"))")
    print(String(repeating: "─", count: 70))

    for (i, demo) in demos.enumerated() {
        print("\nQ\(i + 1): \(demo.question)")
        do {
            if let model = sdxModel, let runtime = sdxRuntime {
                // Full pipeline: build prompt and generate
                let systemPrompt = "You are an assistant with graph reasoning tools. Use tool: {\"tool\":\"<name>\",\"args\":{...}}"
                let prompt = "SYSTEM:\n\(systemPrompt)\n\nUSER:\n\(demo.question)\n\nASSISTANT:"
                let response = try model.generate(prompt, optionsJson: #"{"maxNewTokens":256}"#)
                print("A\(i + 1): \(String(response.prefix(600)))")
            } else {
                // Degraded: direct dispatch
                let result = try session.dispatch(tool: demo.tool, argsJSON: demo.argsJSON)
                let json = try JSONSerialization.data(withJSONObject: result, options: .prettyPrinted)
                let str = String(data: json, encoding: .utf8) ?? "{}"
                print("A\(i + 1): TOOL_RESULT \(demo.tool): \(String(str.prefix(600)))")
            }
        } catch {
            print("A\(i + 1): ERROR — \(error)")
        }
    }

    print("\n[SDK] Done.")
}

main()
