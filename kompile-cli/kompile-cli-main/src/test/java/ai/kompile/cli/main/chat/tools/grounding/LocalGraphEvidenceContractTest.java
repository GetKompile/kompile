package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tiny archive fixtures: no indexing, provider calls, or learning subprocess is needed. */
class LocalGraphEvidenceContractTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);

    private ToolContext context() {
        return new ToolContext("evidence-contract", AgentConfig.builder("evidence-contract")
                .enabledTools(Set.of("*")).build(), new PermissionService(), root, new ToolRegistry(mapper));
    }

    private ObjectNode request() { return mapper.createObjectNode().put("knowledgeBase", "fixture"); }

    private UnifiedGraph graph() {
        UnifiedGraph graph = new UnifiedGraph().graphId("fixture");
        for (String id : Set.of("a", "b", "c", "d")) {
            graph.addEntity(GraphEntity.builder(id).type("CLASS").label(id).build());
        }
        return graph;
    }

    private void save(UnifiedGraph graph) throws Exception {
        Path path = root.resolve("data/crawls/fixture/graph.kgraph");
        Files.createDirectories(path.getParent());
        graph.saveCompact(path);
    }

    private void edge(UnifiedGraph graph, String id, String from, String to, double confidence) {
        graph.addRelation(GraphRelation.builder(id, from, to).type("CALLS")
                .confidence(confidence).directed(true).build());
    }

    private JsonNode execute(String tool, ObjectNode request) throws Exception {
        ToolResult result = backend.executeOfflineTool(tool, request, context());
        assertFalse(result.isError(), result.getOutput());
        return mapper.readTree(result.getOutput());
    }

    @Test void verifyHonorsThresholdAndDoesNotClaimHistoricalSupport() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.4);
        save(graph);
        assertEquals("UNKNOWN", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("verdict").asText());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)")
                .put("minConfidence", 0.3)).path("verdict").asText());
        assertEquals("UNKNOWN", execute("ask_graph_verify", request().put("atom", "calls(a, b)")
                .put("minConfidence", 0.3)).path("verdict").asText());
        for (String tool : Set.of("ask_graph_verify", "ask_graph_query")) {
            ToolResult result = backend.executeOfflineTool(tool,
                    request().put("asOf", "2020-01-01T00:00:00Z").put("atom", "CALLS(a, b)"), context());
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("asOf"), result.getOutput());
        }
    }

    @Test void validTimeUsesHalfOpenIntervalsAndDoesNotLeakFullGraphPosterior() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(0.8)
                .attribute("validFrom", "2020-01-01T00:00:00Z")
                .attribute("validUntil", "2021-01-01T00:00:00Z").build());
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.99)));
        save(graph);
        ObjectNode query = request();
        query.putArray("conjuncts").addObject().put("predicate", "CALLS")
                .putArray("args").add("a").add("?target");
        for (String instant : new String[]{"2019-12-31T23:59:59Z", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"}) {
            boolean included = instant.startsWith("2020-");
            JsonNode verified = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)").put("validAt", instant));
            assertEquals(included ? "SUPPORTED" : "UNKNOWN", verified.path("verdict").asText());
            assertFalse(verified.has("learnedPosterior"));
            assertEquals("valid-time", verified.path("temporal").path("axis").asText());
            assertFalse(verified.path("temporal").path("historicalReconstruction").asBoolean(true));
            assertEquals(included ? 1 : 0, execute("ask_graph_query", query.put("validAt", instant)).path("bindings").size());
        }
    }

    @Test void validTimeHonorsEndpointValidityAndPointTimestampsButIncludesTimelessFacts() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("b").type("CLASS").label("b")
                .attribute("validFrom", "2021-01-01T00:00:00Z").build());
        edge(graph, "ab", "a", "b", 1.0);
        graph.addRelation(GraphRelation.builder("ac", "a", "c").type("CALLS").confidence(1.0)
                .timestamp(java.time.Instant.parse("2021-01-01T00:00:00Z")).build());
        edge(graph, "ad", "a", "d", 1.0);
        save(graph);
        for (String target : new String[]{"b", "c", "d"}) {
            JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, " + target + ")")
                    .put("validAt", "2020-01-01T00:00:00Z"));
            assertEquals("d".equals(target) ? "SUPPORTED" : "UNKNOWN", result.path("verdict").asText());
        }
        execute("ask_graph_retract", request().put("atomKey", "CALLS(a, d)"));
        assertEquals("UNKNOWN", execute("ask_graph_verify", request().put("atom", "CALLS(a, d)")
                .put("validAt", "2020-01-01T00:00:00Z")).path("verdict").asText());
    }

    @Test void temporalInputsFailExplicitlyAndSchemasAdvertiseValidAt() throws Exception {
        save(graph());
        ToolResult invalid = backend.executeOfflineTool("ask_graph_verify",
                request().put("atom", "CALLS(a, b)").put("validAt", "not-an-instant"), context());
        assertTrue(invalid.isError());
        assertTrue(invalid.getOutput().contains("validAt"));
        ToolResult mixed = backend.executeOfflineTool("ask_graph_verify", request().put("atom", "CALLS(a, b)")
                .put("validAt", "2020-01-01T00:00:00Z").put("asOf", "2020-01-01T00:00:00Z"), context());
        assertTrue(mixed.isError());
        assertTrue(new AskGraphVerifyTool((String) null, mapper).parameterSchema().path("properties").has("validAt"));
        assertTrue(new AskGraphQueryTool((String) null, mapper).parameterSchema().path("properties").has("validAt"));
    }

    @Test void remoteRoutesRejectLocalOnlyControlsBeforeSendingRequests() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("ask_graph_verify", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("ask_graph_query", PermissionService.PermissionLevel.ALLOW);
        ToolContext allowed = new ToolContext("remote-guards", AgentConfig.builder("remote-guards")
                .enabledTools(Set.of("*")).build(), permissions, root, new ToolRegistry(mapper));
        var verify = new AskGraphVerifyTool("http://127.0.0.1:1", mapper);
        var query = new AskGraphQueryTool("http://127.0.0.1:1", mapper);
        ToolResult rejected = verify.execute(request().put("atom", "CALLS(a, b)")
                .put("validAt", "2020-01-01T00:00:00Z"), allowed);
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("no remote request was sent"));
        for (String control : new String[]{"validAt", "maxWork"}) {
            ObjectNode request = request();
            request.putArray("conjuncts").addObject().put("predicate", "CALLS")
                    .putArray("args").add("a").add("?b");
            if (control.equals("validAt")) request.put(control, "2020-01-01T00:00:00Z");
            else request.put(control, 1);
            rejected = query.execute(request, allowed);
            assertTrue(rejected.isError());
            assertTrue(rejected.getOutput().contains("no remote request was sent"));
        }
    }

    @Test void oversizedConjunctionIsRejectedBeforeGraphAccess() {
        ObjectNode query = request();
        var conjuncts = query.putArray("conjuncts");
        for (int i = 0; i < 65; i++) {
            conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?a").add("?b");
        }
        ToolResult rejected = backend.executeOfflineTool("ask_graph_query", query, context());
        assertTrue(rejected.isError());
        assertTrue(rejected.getOutput().contains("64 conjuncts"));
        assertFalse(Files.exists(root.resolve("data")));
    }

    @Test void cartesianQueriesFailWithinBudgetRatherThanSilentlyTruncateJoins() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 1.0);
        edge(graph, "ac", "a", "c", 1.0);
        edge(graph, "ad", "a", "d", 1.0);
        save(graph);
        ObjectNode query = request().put("maxResults", 1).put("maxWork", 4);
        var conjuncts = query.putArray("conjuncts");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?a").add("?b");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?c").add("?d");
        ToolResult blocked = backend.executeOfflineTool("ask_graph_query", query, context());
        assertTrue(blocked.isError());
        assertTrue(blocked.getOutput().contains("maxWork=4"), blocked.getOutput());
        JsonNode allowed = execute("ask_graph_query", query.put("maxWork", 12));
        assertEquals(1, allowed.path("bindings").size());
        assertTrue(allowed.path("truncated").asBoolean());
        assertEquals(12, allowed.path("work").asInt());
    }

    @Test void conjunctionUsesLukasiewiczConfidenceAndExcludesRefutations() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        edge(graph, "bc", "b", "c", 0.7);
        edge(graph, "bd", "b", "d", 0.0);
        save(graph);
        ObjectNode request = request().put("minConfidence", 0.4);
        var conjuncts = request.putArray("conjuncts");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("a").add("?mid");
        conjuncts.addObject().put("predicate", "CALLS").putArray("args").add("?mid").add("?target");
        JsonNode rows = execute("ask_graph_query", request).path("bindings");
        assertEquals(1, rows.size());
        assertEquals(0.5, rows.get(0).path("confidence").asDouble(), 1e-9);
        assertEquals("c", rows.get(0).path("variables").path("target").asText());
        request.put("minConfidence", 0.6);
        assertEquals(0, execute("ask_graph_query", request).path("bindings").size());
    }

    @Test void heuristicCodeEdgesAreEvidenceNotVerifiedCalls() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("ab", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index")
                .attribute("evidenceKind", "heuristic-source-pattern").build());
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertEquals("UNKNOWN", result.path("verdict").asText());
        assertEquals("heuristic-code-evidence", result.path("unknownReason").asText());
        ObjectNode query = request();
        query.putArray("conjuncts").addObject().put("predicate", "CALLS")
                .putArray("args").add("a").add("?target");
        assertTrue(execute("ask_graph_query", query).path("bindings").get(0)
                .path("heuristicEvidence").asBoolean());
    }

    @Test void heuristicConfidenceCannotBorrowAuthorityFromARefutation() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation(GraphRelation.builder("heuristic", "a", "b").type("CALLS").confidence(1.0)
                .attribute("_kompileProjectionOwner", "local-code-index").build());
        edge(graph, "refutation", "a", "b", 0.0);
        save(graph);
        assertEquals("REFUTED", execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("verdict").asText());
    }

    @Test void archiveMutationsUseFqnAndInvalidateLearningThroughCompaction() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("s1").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "one.Service").build());
        graph.addEntity(GraphEntity.builder("s2").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "two.Service").build());
        graph.meta("codeIndexGeneration.p", "same").meta("codeLearningGeneration.p", "same");
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(s1,b)", 0.99)));
        save(graph);
        ToolResult ambiguous = backend.executeOfflineTool("ask_graph_assert",
                request().put("atom", "CALLS(Service, b)").put("value", 0.4), context());
        assertTrue(ambiguous.isError());
        execute("ask_graph_assert", request().put("atom", "CALLS(one.Service, b)").put("value", 0.4));
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertFalse(result.has("learnedPosterior"));
        Path archive = root.resolve("data/crawls/fixture/graph.kgraph");
        assertTrue(Files.isRegularFile(ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal.pathFor(archive)));
        assertTrue(ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal.compact(archive));
        result = execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertFalse(result.has("learnedPosterior"));
        assertEquals(6, UnifiedGraph.load(archive).entityCount());
        assertEquals("RETRACTED", execute("ask_graph_retract", request()
                .put("atomKey", "CALLS(one.Service, b)")).path("status").asText());
    }

    @Test void staleLearnedPosteriorIsNotReusedAndStalenessIsReported() throws Exception {
        UnifiedGraph graph = graph();
        edge(graph, "ab", "a", "b", 0.8);
        graph.meta("codeIndexGeneration.p", "new").meta("codeLearningGeneration.p", "old");
        graph.putModel("reasoning/consensus-targets.bin", new HashMap<>(Map.of("CALLS(a,b)", 0.1)));
        save(graph);
        JsonNode result = execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"));
        assertTrue(result.path("meta").path("stale").asBoolean());
        assertFalse(result.has("learnedPosterior"));
        assertEquals(0.8, result.path("calibratedConfidence").asDouble(), 1e-9);
        graph.meta("codeLearningGeneration.p", "new");
        save(graph);
        assertEquals(0.1, execute("ask_graph_verify", request().put("atom", "CALLS(a, b)"))
                .path("learnedPosterior").asDouble(), 1e-9);
    }

    @Test void presentButStaleVectorsAreNotUsed() throws Exception {
        UnifiedGraph graph = graph();
        graph.meta("codeIndexGeneration.p", "new").meta("codeKgeGeneration.p", "old");
        graph.putVectorLayer(new ai.kompile.graph.reasoning.unified.VectorLayer("kge",
                ai.kompile.graph.reasoning.unified.VectorLayer.Target.ENTITY, 2,
                ai.kompile.graph.reasoning.unified.Dtype.F64).put("a", new double[]{1, 0}));
        graph.putVectorLayer(new ai.kompile.graph.reasoning.unified.VectorLayer("kge-relations",
                ai.kompile.graph.reasoning.unified.VectorLayer.Target.RELATION, 2,
                ai.kompile.graph.reasoning.unified.Dtype.F64).put("CALLS", new double[]{1, 0}));
        graph.putArtifactText("models/kge.json", "{\"algorithm\":\"TRANSE\",\"embeddingDim\":2}");
        save(graph);
        ToolResult result = backend.embeddings(request().put("action", "similar").put("entity_name", "a"), context());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("stale"), result.getOutput());
    }

    @Test void scopedSearchDoesNotLoseMatchesBehindOtherProjects() throws Exception {
        UnifiedGraph graph = graph();
        for (int i = 0; i < 150; i++) {
            graph.addEntity(GraphEntity.builder("other-" + i).type("CLASS").label("Service")
                    .attribute("codeProjectId", "other").build());
        }
        graph.addEntity(GraphEntity.builder("wanted").type("CLASS").label("Service")
                .attribute("codeProjectId", "wanted-project").build());
        save(graph);
        JsonNode result = execute("graph_search", request().put("query", "Service")
                .put("code_project_id", "wanted-project").put("entity_type", "CLASS").put("max_results", 1));
        assertEquals(1, result.path("entities").size());
        assertEquals("wanted", result.path("entities").get(0).path("id").asText());
    }

    @Test void ambiguousLabelsFailButFqnAndExactIdsResolve() throws Exception {
        UnifiedGraph graph = graph();
        graph.addEntity(GraphEntity.builder("s1").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "one.Service").build());
        graph.addEntity(GraphEntity.builder("s2").type("CLASS").label("Service")
                .attribute("fullyQualifiedName", "two.Service").build());
        edge(graph, "s1b", "s1", "b", 1.0);
        save(graph);
        ToolResult ambiguous = backend.executeOfflineTool("ask_graph_verify",
                request().put("atom", "CALLS(Service, b)"), context());
        assertTrue(ambiguous.isError());
        assertTrue(ambiguous.getOutput().contains("Ambiguous"), ambiguous.getOutput());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(one.Service, b)"))
                .path("verdict").asText());
        assertEquals("SUPPORTED", execute("ask_graph_verify", request().put("atom", "CALLS(s1, b)"))
                .path("verdict").asText());
    }
}
