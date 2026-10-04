package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.lifecycle.ConsensusTargetsArtifactCodec;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.GraphPslWeightsArtifactCodec;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortablePslRankingTest {
    @TempDir Path temp;

    @Test void parametersRoundTripAndRunWithoutNd4jOrBinaryCache() throws Exception {
        UnifiedGraph graph = graph();
        graph.putArtifactText(GraphPslWeightsArtifactCodec.ARTIFACT, weights(graph, 4.0));
        graph.putModel("reasoning/incremental-graph-psl-cache.v1.bin", new Trap());
        graph.putModel("reasoning/fol-psl-program.bin", new Trap());
        byte[] cache = graph.artifact("reasoning/incremental-graph-psl-cache.v1.bin");
        Path file = temp.resolve("psl.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        Map<String, Object> expected;
        try (var session = LocalReasoningSession.of(graph)) {
            expected = call(session, Map.of("operation", "RANK"));
            assertNotEquals("ERROR", expected.get("status"), expected.toString());
            assertEquals("SCALAR_JAVA", psl(expected).get("solver"));
            assertEquals(false, psl(expected).get("binaryCacheUsed"));
            assertEquals(Boolean.TRUE.equals(psl(expected).get("converged")) ? "OK" : "PARTIAL", expected.get("status"));
            assertNotEquals("ERROR", call(session, Map.of("operation", "SIMILAR", "entityId", "a")).get("status"));
            assertEquals("SIMILAR", call(session, Map.of("operation", "SIMILAR", "entityId", "missing")).get("intent"));
        }
        var urls = new java.net.URL[]{LocalToolDispatcher.class.getProtectionDomain().getCodeSource().getLocation(),
                UnifiedGraph.class.getProtectionDomain().getCodeSource().getLocation(),
                org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation()};
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.eclipse.deeplearning4j.linalg.factory.Nd4j"));
            Class<?> type = loader.loadClass(LocalReasoningSession.class.getName());
            Object session = type.getMethod("open", Path.class).invoke(null, file);
            try {
                Class<?> dispatcher = loader.loadClass(LocalToolDispatcher.class.getName());
                Object instance = dispatcher.getMethod("create").invoke(null);
                var method = dispatcher.getMethod("dispatch", type, String.class, String.class);
                Map<String, Object> actual = MiniJson.parseObject((String) method.invoke(instance, session,
                        "graph_reasoning_query", "{\"operation\":\"RANK\"}"));
                assertEquals(expected.get("entities"), actual.get("entities"), actual.toString());
                assertEquals(expected.get("status"), actual.get("status"));
                Map<String, Object> similar = MiniJson.parseObject((String) method.invoke(instance, session,
                        "graph_reasoning_query", "{\"operation\":\"SIMILAR\",\"entityId\":\"a\"}"));
                assertNotEquals("ERROR", similar.get("status"), similar.toString());
            } finally { type.getMethod("close").invoke(session); }
        }
        assertFalse(Trap.read);
        assertArrayEquals(cache, UnifiedGraph.load(file).artifact("reasoning/incremental-graph-psl-cache.v1.bin"));
    }

    @Test void learnedWeightsChangeActualRanking() {
        UnifiedGraph graph = graph();
        try (var session = LocalReasoningSession.of(graph)) {
            graph.putArtifactText(GraphPslWeightsArtifactCodec.ARTIFACT, weights(graph, 0));
            Object weak = call(session, Map.of("operation", "RANK")).get("entities");
            graph.putArtifactText(GraphPslWeightsArtifactCodec.ARTIFACT, weights(graph, 20));
            Object strong = call(session, Map.of("operation", "RANK")).get("entities");
            assertNotEquals(weak, strong);
        }
    }

    @Test void legacyKnownWeightsActivateButInvalidCanonicalDoesNotFallBack() {
        UnifiedGraph graph = graph();
        var rule = new GraphPslProgramBuilder().build(graph).rules().get(0);
        graph.putArtifactText(GraphPslWeightsArtifactCodec.LEGACY_ARTIFACT, MiniJson.write(Map.of(rule.toString(), 3.0)));
        try (var session = LocalReasoningSession.of(graph)) {
            assertNotEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            assertEquals("ACTIVE", artifact(session, GraphPslWeightsArtifactCodec.LEGACY_ARTIFACT).get("status"));
            graph.putArtifactText(GraphPslWeightsArtifactCodec.ARTIFACT, "{}");
            assertEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            assertEquals("INVALID", artifact(session, GraphPslWeightsArtifactCodec.ARTIFACT).get("status"));
        }
    }

    @Test void strictParameterSchemaRejectsMalformedAndUnsupportedInputs() {
        String good = weights(graph(), 2);
        assertEquals(5, GraphPslWeightsArtifactCodec.fromJson(good).size());
        for (String bad : List.of("{}", good.replace("\"version\":1", "\"version\":2"),
                good.replace("graph-psl-default-v3", "unknown"), good.replace("\"weight\":2.0", "\"weight\":-1"),
                good.replace("\"weight\":2.0", "\"weight\":\"NaN\""),
                good.replace("\"weight\":2.0", "\"weight\":1e999"),
                good.replace("\"weight\":2.0", "\"weight\":1e308"),
                good.replace("\"weight\":2.0", "\"weight\":1000001"),
                good.replace("\"weight\":2.0", "\"weight\":01"),
                good.replace("\"id\":\"propagation\"", "\"id\":null"),
                good.replace("\"version\":1", "\"version\":1,\"version\":1"))) {
            assertThrows(IllegalArgumentException.class, () -> GraphPslWeightsArtifactCodec.fromJson(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> GraphPslWeightsArtifactCodec.fromLegacyJson("{\"arbitrary()\":1}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict("[[[[]]]]", 3));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict("{\"x\":null,\"x\":1}", 3));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict("\"raw\ncontrol\"", 3));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict("\"\\u+001\"", 3));
        assertEquals(1L, MiniJson.parse("+1")); // legacy attributes retain their existing parser behavior
    }

    @Test void staleParametersAndTargetsStayInvalidAfterMutationAndReload() throws Exception {
        UnifiedGraph graph = graph();
        graph.putArtifactText(GraphPslWeightsArtifactCodec.ARTIFACT, weights(graph, 2));
        graph.putArtifactText(ConsensusTargetsArtifactCodec.ARTIFACT_NAME,
                ConsensusTargetsArtifactCodec.encode(Map.of("State(n0)", 0.6), Map.of("n0", "a")));
        Path file = temp.resolve("stale.kgraph");
        try (var session = LocalReasoningSession.of(graph)) {
            assertEquals("INSPECTION_ONLY", artifact(session, ConsensusTargetsArtifactCodec.ARTIFACT_NAME).get("status"));
            graph.meta("codeIndexGeneration.p", "2");
            assertEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            graph.meta("codeLearningGeneration.p", "2");
            assertNotEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            LocalToolDispatcher.create().dispatch(session, "ask_graph_retract", "{\"atomKey\":\"parent(a, b)\"}");
            assertEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            assertEquals("INVALID", artifact(session, ConsensusTargetsArtifactCodec.ARTIFACT_NAME).get("status"));
            session.save(file);
        }
        try (var session = LocalReasoningSession.open(file)) {
            assertEquals("ERROR", call(session, Map.of("operation", "RANK")).get("status"));
            assertEquals("INVALID", artifact(session, GraphPslWeightsArtifactCodec.ARTIFACT).get("status"));
        }
    }

    @Test void groundingBudgetFailsBeforeSolving() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 0; i <= PortablePslRanking.MAX_ENTITIES; i++) graph.addEntity("e" + i, "ENTITY", "e" + i);
        try (var session = LocalReasoningSession.of(graph)) {
            Map<String, Object> result = call(session, Map.of("operation", "RANK"));
            assertEquals("ERROR", result.get("status"));
            assertTrue(result.toString().contains("budget exceeded"));
        }
    }

    @SuppressWarnings("unchecked")
    private static String weights(UnifiedGraph graph, double conflict) {
        Map<String, Object> json = MiniJson.parseObject(GraphPslWeightsArtifactCodec.toJson(new GraphPslProgramBuilder().build(graph)));
        for (Object item : (List<?>) json.get("rules")) {
            Map<String, Object> row = (Map<String, Object>) item;
            if ("conflict".equals(row.get("id"))) row.put("weight", conflict);
        }
        return MiniJson.write(json);
    }
    private static UnifiedGraph graph() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("a").type("PERSON").label("a").confidence(0.9).embedding(new double[]{1, 0}).build());
        graph.addEntity(GraphEntity.builder("b").type("PERSON").label("b").confidence(0.3).embedding(new double[]{0.8, 0.2}).build());
        graph.addEntity("c", "PERSON", "c");
        graph.addRelation("ab", "a", "b", "parent", 0.9);
        graph.addRelation("bc", "b", "c", "CONFLICTS", 0.9);
        return graph;
    }
    private static Map<String, Object> call(LocalReasoningSession session, Map<String, Object> args) {
        return MiniJson.parseObject(LocalToolDispatcher.create().dispatch(session, "graph_reasoning_query", MiniJson.write(args)));
    }
    private static Map<?, ?> psl(Map<String, Object> result) { return (Map<?, ?>) ((Map<?, ?>) result.get("data")).get("psl"); }
    private static Map<?, ?> artifact(LocalReasoningSession session, String name) {
        Map<?, ?> report = (Map<?, ?>) ((Map<?, ?>) call(session, Map.of("operation", "ASSETS")).get("data")).get("portability");
        return ((List<?>) report.get("artifacts")).stream().map(item -> (Map<?, ?>) item)
                .filter(item -> name.equals(item.get("name"))).findFirst().orElseThrow();
    }
    private static final class Trap implements Serializable {
        static boolean read;
        private void readObject(ObjectInputStream stream) throws IOException { read = true; throw new IOException("must not deserialize"); }
    }
}
