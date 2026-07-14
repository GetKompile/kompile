// ChatWithGraph.cs — C# reference pipeline for the Kompile Local SDK
//
// Requires: .NET 8 SDK + dotnet tool (not present on this box — source only)
// Build: dotnet run (from this directory with ChatWithGraph.csproj)
//
// Pipeline (mirrors kompile-chat-local ChatEngine semantics):
//   1. Open a .kgraph reasoning session (KgrRuntime from KompileReasoning.cs)
//   2. Print tools catalog
//   3. Open model if KOMPILE_SDK_MODEL_PATH set (SdxLlmRuntime from SdxLlmRuntime.cs)
//   4. Demo queries with tool-call round-trips
//   5. Close cleanly

using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Kompile.Local.Sdk.Reasoning;
using Nd4j.Dsp.Runtime.Llm;   // SdxLlmRuntime from bindings/csharp/SdxLlmRuntime.cs

namespace Kompile.Local.Sdk.Examples
{
    class ChatWithGraph
    {
        private static readonly string SdkRoot =
            Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", ".."));

        private static string KgraphPath =>
            Environment.GetEnvironmentVariable("KOMPILE_SDK_KGRAPH")
            ?? Path.Combine(SdkRoot, "examples", "data", "fixture.kgraph");

        private static string? ModelPath =>
            Environment.GetEnvironmentVariable("KOMPILE_SDK_MODEL_PATH");

        static async Task Main(string[] args)
        {
            Console.WriteLine(new string('=', 70));
            Console.WriteLine("Kompile Local SDK — ChatWithGraph.cs reference pipeline");
            Console.WriteLine(new string('=', 70));

            // 1. Open reasoning session
            using var kgrRuntime = KgrRuntime.Create();
            Console.WriteLine($"\n[KGR] ABI version: {kgrRuntime.AbiVersion}");
            Console.WriteLine($"[KGR] Opening graph: {KgraphPath}");

            using var session = kgrRuntime.Open(KgraphPath);

            // 2. Print tools catalog
            var toolsJson = session.ToolsJson();
            var tools = JsonSerializer.Deserialize<List<JsonElement>>(toolsJson) ?? new();
            var toolNames = new List<string>();
            foreach (var t in tools)
            {
                if (t.TryGetProperty("name", out var n)) toolNames.Add(n.GetString() ?? "?");
            }
            Console.WriteLine($"[KGR] Tools ({toolNames.Count}): {string.Join(", ", toolNames)}");

            // 3. Verify sanity check
            var verify = session.Dispatch("ask_graph_verify", @"{""atom"":""WORKS_AT(alice, acme)""}");
            Console.WriteLine($"[KGR] ask_graph_verify WORKS_AT(alice, acme): " +
                (verify.TryGetProperty("verdict", out var v) ? v.GetString() : verify.ToString()));

            // 4. Try model load
            SdxLlmRuntime? sdxRuntime = null;
            SdxLlmModel? model = null;
            bool hasModel = false;

            if (!string.IsNullOrEmpty(ModelPath))
            {
                try
                {
                    sdxRuntime = SdxLlmRuntime.Create();
                    Console.WriteLine($"\n[SDK] SDX LLM ABI version: {sdxRuntime.AbiVersion()}");
                    Console.WriteLine($"[SDK] Loading model: {ModelPath}");
                    model = sdxRuntime.LoadModel(ModelPath!, null, null);
                    hasModel = true;
                    Console.WriteLine("[SDK] Model loaded. Full pipeline active.");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[SDK] Warning — model load failed: {ex.Message}");
                    Console.WriteLine("[SDK] Continuing in degraded mode.");
                }
            }
            else
            {
                Console.WriteLine("\n[SDK] No model — reasoning-only (degraded) mode.");
                Console.WriteLine("[SDK] Set KOMPILE_SDK_MODEL_PATH to enable full pipeline.\n");
            }

            // 5. Demo queries
            var demos = new[]
            {
                ("Does Alice work at acme?",
                 "ask_graph_verify",
                 @"{""atom"":""WORKS_AT(alice, acme)""}"),
                ("What entities are in the graph?",
                 "graph_reasoning_query",
                 @"{""operation"":""OVERVIEW""}"),
                ("Who works at acme?",
                 "ask_graph_query",
                 @"{""pattern"":""WORKS_AT(?x, acme)""}"),
            };

            Console.WriteLine("\n" + new string('─', 70));
            Console.WriteLine($"DEMO QUERIES ({(hasModel ? "full pipeline" : "degraded")})");
            Console.WriteLine(new string('─', 70));

            for (int i = 0; i < demos.Length; i++)
            {
                var (question, tool, argsJson) = demos[i];
                Console.WriteLine($"\nQ{i + 1}: {question}");

                string answer;
                if (hasModel && model != null)
                {
                    // Full pipeline: build prompt + generate + parse tool calls
                    var systemPrompt =
                        "You are an assistant with graph reasoning tools. " +
                        "Emit tool calls as: {\"tool\":\"<name>\",\"args\":{...}}\n" +
                        $"Available tools:\n{toolsJson}";
                    var prompt =
                        $"SYSTEM:\n{systemPrompt}\n\nUSER:\n{question}\n\nASSISTANT:";
                    try
                    {
                        var response = model.Generate(
                            prompt,
                            @"{""maxNewTokens"":256,""sampling"":{""preset"":""greedy""}}");
                        answer = response.Length > 600 ? response[..600] : response;
                    }
                    catch (Exception ex)
                    {
                        answer = $"Generation failed: {ex.Message}";
                    }
                }
                else
                {
                    // Degraded: direct dispatch
                    var result = session.Dispatch(tool, argsJson);
                    answer = $"TOOL_RESULT {tool}: {result}";
                }

                Console.WriteLine($"A{i + 1}: {answer}");
            }

            // 6. Cleanup
            model?.Dispose();
            sdxRuntime?.Dispose();

            Console.WriteLine("\n[SDK] Done.");
        }
    }
}
