package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.tools.grounding.*;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit ownership for the built-in families shared by chat and MCP. Descriptor construction
 * never creates tools, probes services, provisions models, or changes local learning defaults.
 * Public nested providers have stable no-argument constructors for build-time ServiceLoader use.
 */
public final class BuiltinToolExtensions {
    private BuiltinToolExtensions() { }

    public static final class Code implements CliToolExtension {
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.code", Set.of("code.index"), List.of(
                learningTool("code_search", "search ranked_search blended_search signatures health routing index stats entities",
                        "index search ranked_search blended_search signatures routing"),
                learningTool("code_graph", "build search symbol file impact ranked_search blended_search signatures health routing stats connectivity add_directory remove_directory list_directories learn learning_config_get learning_config_update",
                        "build add_directory learn learning_config_get learning_config_update search symbol file impact ranked_search blended_search signatures routing"),
                learningTool("local_code_index", "index index_status repair search ranked_search blended_search signatures impact health routing spath find replace usages pagerank clones cochanges unused_exports stats list callers implementors trace spring_resolve debug_trace changed_context modules",
                        "index search ranked_search blended_search signatures impact routing spath find replace usages pagerank clones cochanges unused_exports callers implementors trace spring_resolve debug_trace changed_context")));

        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            return List.of(guarded(new CodeSearchTool(c.baseUrl(), c.objectMapper())),
                    guarded(new CodeGraphTool(c.baseUrl(), c.objectMapper())), guarded(new LocalCodeIndexTool()));
        }
    }

    public static final class Knowledge implements CliToolExtension {
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.knowledge", Set.of("knowledge.local"),
                tools("knowledge.local", "knowledge_search", "knowledge_status", "rag_search", "graph_search"));
        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            return List.of(plain(new KnowledgeSearchCliTool(c.baseUrl(), c.objectMapper())),
                    plain(new KnowledgeStatusCliTool(c.baseUrl(), c.objectMapper())),
                    plain(new RagSearchTool(c.baseUrl(), c.objectMapper())),
                    plain(new GraphRagSearchTool(c.baseUrl(), c.objectMapper())));
        }
    }

    public static final class Graph implements CliToolExtension {
        // The graph implementation still links graph DTO/learning dependencies; this is not a slim/model-free claim.
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.graph",
                Set.of("graph.local", "graph.learning"), graphTools());
        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            var m = c.objectMapper();
            var url = c.baseUrl();
            return List.of(plain(new GraphAggregateTool(url, m)), plain(new GraphForecastTool(url, m)),
                    plain(new GraphCentralityTool(url, m)), plain(new KnowledgeGraphTool(url, m)),
                    plain(new AskGraphQueryTool(url, m)), plain(new AskGraphVerifyTool(url, m)),
                    plain(new AskGraphAssertTool(url, m)), plain(new AskGraphRetractTool(url, m)),
                    plain(new AskGraphMebnTool(url, m)), plain(new AskGraphExplainTool(url, m)),
                    plain(new AskGraphFusedTool(url, m)), plain(new AskGraphSynthesizeTool(url, m)),
                    plain(new AskGraphSubscribeTool(url, m)), plain(new GraphReasonTool(url, m)),
                    plain(new GraphImportTool(c.graphBaseUrl(), m)), plain(new GraphExportTool(c.graphBaseUrl(), m)),
                    plain(new ProcessMiningCliTool(url, m)), plain(new AskGraphClaimTool(url, m)),
                    plain(new GraphReasoningQueryTool(c.graphBaseUrl(), m)), plain(new GraphBayesTool(url, m)),
                    guarded(new GraphEmbeddingsTool(url, m)), plain(new GraphSimulateTool(url, m)));
        }
    }

    public static final class Crawl implements CliToolExtension {
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.crawl", Set.of("crawl.local"), List.of(
                new CliToolDescriptor("crawl_source", Set.of("crawl.local"), Map.of(),
                        Map.of("invoke", Set.of("graph.learning"))),
                new CliToolDescriptor("crawl_documents", Set.of("crawl.local"), Map.of(),
                        Map.of("invoke", Set.of("graph.learning"))),
                tool("crawl_discover", "crawl.local"), tool("crawl_control", "crawl.local"),
                tool("crawl_result", "crawl.local")));
        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            return List.of(guarded(new CrawlSourceTool(c.crawlBaseUrl(), c.objectMapper())),
                    guarded(new CrawlDocumentsTool(c.crawlBaseUrl(), c.objectMapper())),
                    plain(new CrawlDiscoveryTool(c.crawlBaseUrl(), c.objectMapper())),
                    guarded(new CrawlControlTool(c.crawlBaseUrl(), c.objectMapper())),
                    guarded(new CrawlResultTool(c.crawlBaseUrl(), c.objectMapper())));
        }
    }

    public static final class Model implements CliToolExtension {
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.model", Set.of("model.runtime"),
                tools("model.runtime", "model_runtime", "vlm_model_definition", "subprocess_watchdog"));
        @Override public Set<String> omittedTools(ToolExtensionContext context) {
            return context.surface() == ToolExtensionContext.Surface.MCP
                    ? Set.of("vlm_model_definition") : Set.of();
        }
        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            List<ToolContribution> result = new ArrayList<>();
            result.add(guarded(new ModelRuntimeTool(c.objectMapper())));
            // MCP historically has no VLM definition tool. Do not expand its public surface here.
            if (c.surface() == ToolExtensionContext.Surface.CHAT) {
                result.add(plain(new VlmModelDefinitionTool(c.objectMapper())));
            }
            result.add(plain(new SubprocessWatchdogTool(c.objectMapper())));
            return List.copyOf(result);
        }
    }

    public static final class Pipeline implements CliToolExtension {
        private static final CliExtensionDescriptor DESCRIPTOR = BuiltinToolExtensions.descriptor("builtin.pipeline", Set.of("pipeline.local"),
                List.of(new CliToolDescriptor("pipeline", Set.of("pipeline.local", "model.runtime"), Map.of())));
        @Override public CliExtensionDescriptor descriptor() { return DESCRIPTOR; }
        @Override public List<ToolContribution> createTools(ToolExtensionContext c) {
            return List.of(guarded(new PipelineTool(c.objectMapper())));
        }
    }

    private static CliExtensionDescriptor descriptor(String id, Set<String> provides, List<CliToolDescriptor> tools) {
        return new CliExtensionDescriptor(id, "1", CliExtensionDescriptor.API_VERSION, provides, Set.of(), tools, Set.of());
    }

    private static CliToolDescriptor tool(String id, String capability) {
        return new CliToolDescriptor(id, Set.of(capability), Map.of());
    }

    private static List<CliToolDescriptor> tools(String capability, String... ids) {
        return java.util.Arrays.stream(ids).map(id -> tool(id, capability)).toList();
    }

    private static CliToolDescriptor learningTool(String id, String actions, String learningActions) {
        Map<String, Set<String>> requirements = new LinkedHashMap<>();
        for (String action : actions.split(" ")) requirements.put(action, Set.of());
        Map<String, Set<String>> conditional = new LinkedHashMap<>();
        // Only explicit learning requires the capability unconditionally. Index/read
        // maintenance can use it when enabled by configuration, but defaults to off.
        for (String action : learningActions.split(" ")) {
            if ("learn".equals(action)) requirements.put(action, Set.of("graph.learning"));
            else if (!action.startsWith("learning_config_")) conditional.put(action, Set.of("graph.learning"));
        }
        return new CliToolDescriptor(id, Set.of("code.index"), requirements, conditional);
    }

    private static List<CliToolDescriptor> graphTools() {
        List<CliToolDescriptor> result = new ArrayList<>(tools("graph.local", "graph_aggregate", "graph_forecast",
                "graph_centrality", "knowledge_graph", "ask_graph_query", "ask_graph_verify", "ask_graph_assert",
                "ask_graph_retract", "ask_graph_mebn", "ask_graph_explain", "ask_graph_explain_fused",
                "ask_graph_synthesize", "ask_graph_subscribe", "graph_reason", "graph_import", "graph_export",
                "process_mining", "ask_graph_claim", "graph_reasoning_query", "graph_bayes", "graph_simulate"));
        result.add(new CliToolDescriptor("graph_embeddings", Set.of("graph.local"), Map.of(),
                Map.of("invoke", Set.of("graph.learning"))));
        return List.copyOf(result);
    }

    private static ToolContribution plain(CliTool tool) { return new ToolContribution(tool, false); }
    private static ToolContribution guarded(CliTool tool) { return new ToolContribution(tool, true); }
}
