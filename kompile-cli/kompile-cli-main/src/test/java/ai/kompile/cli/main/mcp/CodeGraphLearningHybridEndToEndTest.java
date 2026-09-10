/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real end-to-end proof for the code-graph to hybrid-reasoner integration
 * (Phases 1-4): a real {@code .kompile/code-graph-reasoning.json} config makes
 * the normal {@code local_code_index} path trigger the real learning pass (KGE + PSL +
 * MEBN via the executor's sanctioned inline fallback), and the real
 * {@code graph_search}, {@code graph_embeddings}, and hybrid query consumers then
 * read the learned model back out of the persisted .kgraph.
 *
 * <p>No component is mocked. The inline fallback executes the same
 * {@code UnifiedGraphKgeLifecycle} / {@code UnifiedGraphReasoningLifecycle}
 * training the subprocess would, inside this JVM, because surefire has no
 * kompile-app executable to re-exec.</p>
 */
class CodeGraphLearningHybridEndToEndTest {

    @TempDir
    Path project;

    @TempDir
    Path hermeticHome;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String savedHome;
    private JvmToolHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", hermeticHome.toString());
        // Run the real learning lifecycles in-process (surefire has no kompile-app
        // binary to re-exec); this is the executor's sanctioned fallback path.
        System.setProperty("kompile.local.learning.subprocess.enabled", "false");
        System.setProperty("kompile.local.learning.inlineFallback", "true");

        AgentConfig agent = AgentConfig.builder("code-graph-learning-e2e")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        ToolRegistry registry = ToolRegistryFactory.create(
                mapper,
                null,
                new AgentRegistry(),
                permissions,
                null,
                null,
                null,
                null);
        for (String tool : registry.ids()) {
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        }
        permissions.setUserOverride("external_directory", PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext(
                "code-graph-learning-e2e", agent, permissions, project, registry);
        harness = new JvmToolHarness(registry, context);

        writeLearningEnabledConfig();
        writeModule();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("kompile.local.learning.subprocess.enabled");
        System.clearProperty("kompile.local.learning.inlineFallback");
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
        }
    }

    @Test
    void normalLocalIndexTrainsRealModelAndGraphConsumersReadItBack() throws Exception {
        // The normal local index entry point must project and run configured learning.
        ToolResult build = harness.call("local_code_index", mapper.createObjectNode()
                .put("action", "index")
                .put("directory", project.toString())
                .put("project_id", "learning-e2e")
                .put("background", false));
        assertSucceeded(build, "local_code_index index");
        assertEquals("COMPLETED", build.getMetadata().get("learningStatus"),
                "build learningStatus was not COMPLETED: " + build.getOutput());

        String knowledgeBase = String.valueOf(build.getMetadata().get("knowledgeBase"));
        Path graphPath = Path.of(String.valueOf(build.getMetadata().get("graphPath")));
        assertTrue(Files.isRegularFile(graphPath), "build did not persist a graph");

        UnifiedGraph learned = UnifiedGraph.load(graphPath);
        assertEquals("CODE_GRAPH_BUILD", learned.meta().get("reasoningLearning.phase"),
                "learning provenance phase stamp missing");
        assertEquals("local_code_index", learned.meta().get("reasoningLearning.trigger"),
                "learning provenance trigger stamp missing");
        assertEquals("COMPLETED", learned.meta().get("phase.codeLearning.learning-e2e"));
        assertEquals(learned.meta().get("codeIndexGeneration.learning-e2e"),
                learned.meta().get("codeLearningGeneration.learning-e2e"));

        // ---- Phase 4 proof: the learned KGE model is consumable.
        // The lifecycle writes ENTITY_LAYER "kge", RELATION_LAYER "kge-relations"
        // and MODEL_ARTIFACT "models/kge.json"; graph_embeddings reads exactly those.
        VectorLayer entities = learned.vectorLayer("kge");
        assertNotNull(entities, "no learned 'kge' entity vector layer");
        assertTrue(entities.size() > 0, "learned 'kge' layer is empty");
        assertNotNull(learned.vectorLayer("kge-relations"), "no learned relation vector layer");
        String modelJson = learned.artifactText("models/kge.json");
        assertNotNull(modelJson, "no models/kge.json model artifact");
        assertTrue(modelJson.contains("TRANSE"), "model artifact missing algorithm: " + modelJson);

        // Graph search must consume the projected code graph using natural-language terms.
        ToolResult graphSearch = harness.call("graph_search", mapper.createObjectNode()
                .put("query", "local service")
                .put("search_type", "hybrid")
                .put("knowledgeBase", knowledgeBase)
                .put("code_project_id", "learning-e2e")
                .put("max_results", 10));
        assertSucceeded(graphSearch, "graph_search");
        assertTrue(graphSearch.getOutput().contains("LocalService"), graphSearch.getOutput());
        assertTrue(graphSearch.getOutput().contains("id: `code:"), graphSearch.getOutput());

        // ---- Real consumer 1: triple plausibility over the code graph.
        ToolResult score = harness.call("graph_embeddings", mapper.createObjectNode()
                .put("action", "score")
                .put("knowledgeBase", knowledgeBase)
                .put("head", "LocalService")
                .put("relation", "EXTENDS")
                .put("tail", "BaseService"));
        assertSucceeded(score, "graph_embeddings score");
        assertTrue(score.getOutput().contains("Plausibility score"),
                "score output missing plausibility: " + score.getOutput());

        // ---- Real consumer 2: similarity neighborhood from learned vectors.
        ToolResult similar = harness.call("graph_embeddings", mapper.createObjectNode()
                .put("action", "similar")
                .put("knowledgeBase", knowledgeBase)
                .put("entity_name", "LocalService"));
        assertSucceeded(similar, "graph_embeddings similar");
        assertTrue(number(similar, "count") > 0,
                "similar returned no neighbors over a trained code graph");

        // ---- Real consumer 3: the hybrid query engine resolves the persisted KGE layer.
        String localServiceId = learned.entities().stream()
                .filter(entity -> "LocalService".equals(entity.label()) && "CLASS".equals(entity.type()))
                .findFirst().orElseThrow().id();
        ObjectNode similarQuery = mapper.createObjectNode()
                .put("operation", "SIMILAR")
                .put("knowledgeBase", knowledgeBase)
                .put("entityId", localServiceId)
                .put("queryText", "kge")
                .put("topK", 5);
        var hybrid = new LocalProjectGraphBackend(mapper)
                .reasoningQuery(similarQuery, harness.context());
        assertTrue(hybrid.path("data").path("semanticVectorAvailable").asBoolean(), hybrid.toString());
        assertEquals("kge", hybrid.path("data").path("embeddingLayer").asText());
        assertTrue(hybrid.path("entities").size() > 0, hybrid.toString());
    }

    /**
     * A build whose config disables learning must leave the graph structural-only:
     * no learned layers, no model artifact, and a not-run learning status.
     */
    @Test
    void disabledConfigKeepsBuildStructuralOnly() throws Exception {
        writeConfig("{\"enabled\":false,\"triggers\":[\"build\"]}");

        ToolResult build = harness.call("local_code_index", mapper.createObjectNode()
                .put("action", "index")
                .put("directory", project.toString())
                .put("project_id", "learning-e2e")
                .put("background", false));
        assertSucceeded(build, "local_code_index index (learning disabled)");
        assertFalse("COMPLETED".equals(build.getMetadata().get("learningStatus")),
                "learning ran despite disabled config");

        UnifiedGraph structural = UnifiedGraph.load(
                Path.of(String.valueOf(build.getMetadata().get("graphPath"))));
        assertEquals(null, structural.vectorLayer("kge"),
                "kge layer present despite learning disabled");
        assertEquals(null, structural.artifactText("models/kge.json"),
                "kge model artifact present despite learning disabled");
    }

    @Test
    void learningConfigIsDiscoverableAndPartiallyUpdatableThroughCodeGraphTool() throws Exception {
        ToolResult updated = harness.call("code_graph", mapper.createObjectNode()
                .put("action", "learning_config_update")
                .put("config_json", "{\"enabled\":false,\"kgeDim\":999}"));
        assertSucceeded(updated, "code_graph learning_config_update");
        assertEquals(false, updated.getMetadata().get("enabled"));

        ToolResult loaded = harness.call("code_graph", mapper.createObjectNode()
                .put("action", "learning_config_get"));
        assertSucceeded(loaded, "code_graph learning_config_get");
        assertTrue(loaded.getOutput().contains("\"kgeDim\" : 256"), loaded.getOutput());
        assertTrue(loaded.getOutput().contains("\"kgeTraining\" : true"), loaded.getOutput());
    }

    private void writeLearningEnabledConfig() throws Exception {
        writeConfig("{\"enabled\":true,\"kgeTraining\":true,\"kgeAlgorithm\":\"TRANSE\","
                + "\"kgeDim\":8,\"kgeEpochs\":2,\"kgeLearningRate\":0.05,"
                + "\"pslSteps\":1,\"mebnEpochs\":1,\"consensusRounds\":1,"
                + "\"consensusWeight\":0.35,\"maxRelationTypes\":25,"
                + "\"minGraphSize\":5,\"triggers\":[\"build\"]}");
    }

    private void writeConfig(String json) throws Exception {
        Path configDirectory = project.resolve(".kompile");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("code-graph-reasoning.json"),
                json, StandardCharsets.UTF_8);
    }

    private void writeModule() throws Exception {
        Path sources = project.resolve("src/main/java/example");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("BaseService.java"), """
                package example;

                public class BaseService {
                    protected String describe() {
                        return "base";
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(sources.resolve("LocalService.java"), """
                package example;

                public class LocalService extends BaseService {
                    public String answer(int mode) {
                        return describe() + mode;
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private record JvmToolHarness(ToolRegistry registry, ToolContext context) {
        ToolResult call(String toolId, ObjectNode arguments) throws Exception {
            var tool = registry.get(toolId);
            assertNotNull(tool, () -> "JVM harness has no registered tool " + toolId);
            return tool.execute(arguments, context);
        }
    }

    private static void assertSucceeded(ToolResult result, String tool) {
        assertFalse(result.isError(), tool + " failed: " + result.getOutput());
    }

    private static int number(ToolResult result, String key) {
        Object value = result.getMetadata().get(key);
        assertTrue(value instanceof Number,
                () -> "Missing numeric metadata " + key + " in " + result.getMetadata());
        return ((Number) value).intValue();
    }
}
