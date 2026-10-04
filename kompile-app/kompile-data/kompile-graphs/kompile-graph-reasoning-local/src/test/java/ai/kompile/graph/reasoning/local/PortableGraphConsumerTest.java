package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortableGraphConsumerTest {
    @TempDir Path temp;

    @Test
    void portableInterchangePreservesObservedConfidence() throws Exception {
        UnifiedGraph graph = graph();
        graph.addRelation("weak", "a", "c", "WEAK", 0.65);
        Path file = temp.resolve("export.kgraph");
        // Same interchange policy as CLI export; this is not a device-execution receipt.
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        try (LocalReasoningSession session = LocalReasoningSession.open(file)) {
            var fact = session.kbState().factStore().factFor("WEAK(a, c)").orElseThrow();
            assertEquals(0.65, fact.value(), 1e-9);
            Map<String, Object> verified = call(session, "ask_graph_verify", Map.of("atom", "WEAK(a, c)"));
            assertEquals("SUPPORTED", verified.get("verdict"));
            assertEquals(0.65, ((Number) verified.get("confidence")).doubleValue(), 1e-9);
        }
    }

    @Test
    void negatedRelationIsNotASeparatePositiveFact() {
        UnifiedGraph graph = graph();
        graph.addRelation("negative", "a", "c", "NOT_BLOCKS", 0.9);
        try (LocalReasoningSession session = LocalReasoningSession.of(graph)) {
            assertEquals("REFUTED", call(session, "ask_graph_verify", Map.of("atom", "BLOCKS(a, c)")).get("verdict"));
        }
    }

    @Test
    void retractionDoesNotLeaveDerivedFactsOrLineageLive() {
        UnifiedGraph graph = graph();
        graph.putArtifactText(DatalogRulesBundle.ARTIFACT_KEY, DatalogRulesBundle.toJson(List.of(
                FolDatalogAdapter.copyRule("ancestor", "parent"),
                FolDatalogAdapter.transitivityRule("ancestor"))));
        try (LocalReasoningSession session = LocalReasoningSession.of(graph)) {
            assertEquals("SUPPORTED", call(session, "ask_graph_verify", Map.of("atom", "ancestor(a, c)")).get("verdict"));
            call(session, "ask_graph_retract", Map.of("atomKey", "parent(b, c)"));
            assertEquals("UNKNOWN", call(session, "ask_graph_verify", Map.of("atom", "ancestor(a, c)")).get("verdict"));
            assertNull(session.kbState().justificationIndex());
        }
    }

    @Test
    void loadAndAssetsDistinguishInvalidAndInspectionOnlyArtifacts() throws Exception {
        UnifiedGraph graph = graph();
        graph.putArtifactText("datalog_rules", "[{\"head\":\"p\",\"body\":[]}] ");
        graph.putArtifact("unknown.bin", new byte[]{1, 2, 3});
        graph.putArtifactText("reasoning/mebn-theory.v1.json", "{}");
        Path file = temp.resolve("assets.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        try (LocalReasoningSession session = LocalReasoningSession.createEmpty()) {
            Map<String, Object> loaded = call(session, "graph_load", Map.of("path", file.toString()));
            assertEquals("OK", loaded.get("status"));
            Map<?, ?> report = (Map<?, ?>) loaded.get("portability");
            assertEquals("materialized-in-memory", report.get("storage"));
            assertEquals("INVALID", artifact(report, "datalog_rules").get("status"));
            assertEquals("INVALID", artifact(report, "reasoning/mebn-theory.v1.json").get("status"));
            assertEquals("INSPECTION_ONLY", artifact(report, "unknown.bin").get("status"));
            Map<?, ?> data = (Map<?, ?>) call(session, "graph_reasoning_query", Map.of("operation", "ASSETS")).get("data");
            assertEquals(report, data.get("portability"));
        }
    }

    @Test
    void trainedRotateRunsAfterInterchangeReloadWithoutNd4jAndReportsItsActivation() throws Exception {
        UnifiedGraph graph = graph();
        graph.putEntityVector("kge", "a", new double[]{1, 0});
        graph.putEntityVector("kge", "b", new double[]{0, 1});
        graph.putGlobalVector("kge-relations", "parent", new double[]{Math.PI / 2});
        graph.putArtifactText("models/kge.json", "{\"algorithm\":\"ROTATE\",\"embeddingDim\":1}");
        Path file = temp.resolve("rotate.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        java.net.URL[] classpath = {
                LocalToolDispatcher.class.getProtectionDomain().getCodeSource().getLocation(),
                UnifiedGraph.class.getProtectionDomain().getCodeSource().getLocation(),
                org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation()
        };
        try (var loader = new java.net.URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.eclipse.deeplearning4j.linalg.factory.Nd4j"));
            Class<?> type = loader.loadClass(LocalReasoningSession.class.getName());
            Object session = type.getMethod("open", Path.class).invoke(null, file);
            try {
                Class<?> dispatcher = loader.loadClass(LocalToolDispatcher.class.getName());
                Object instance = dispatcher.getMethod("create").invoke(null);
                var dispatch = dispatcher.getMethod("dispatch", type, String.class, String.class);
                String score = (String) dispatch.invoke(instance, session, "graph_embeddings",
                        "{\"action\":\"score\",\"head\":\"a\",\"relation\":\"parent\",\"tail\":\"b\"}");
                assertEquals("OK", MiniJson.parseObject(score).get("status"), score);
                // Portable KVEC defaults to F32: compare against the persisted, rounded phase.
                double phase = (double) (float) (Math.PI / 2);
                double expected = 1.0 / (1.0 + Math.hypot(Math.cos(phase), Math.sin(phase) - 1.0));
                assertEquals(expected, ((Number) MiniJson.parseObject(score).get("score")).doubleValue(), 1e-12);
                String assets = (String) dispatch.invoke(instance, session, "graph_reasoning_query", "{\"operation\":\"ASSETS\"}");
                Map<?, ?> report = (Map<?, ?>) ((Map<?, ?>) MiniJson.parseObject(assets).get("data")).get("portability");
                assertEquals("ACTIVE", artifact(report, "models/kge.json").get("status"));
            } finally {
                type.getMethod("close").invoke(session);
            }
        }
        try (LocalReasoningSession session = LocalReasoningSession.open(file)) {
            call(session, "ask_graph_retract", Map.of("atomKey", "parent(a, b)"));
            assertEquals("ERROR", call(session, "graph_embeddings", Map.of("action", "score", "head", "a", "relation", "parent", "tail", "b")).get("status"));
            Map<?, ?> report = (Map<?, ?>) ((Map<?, ?>) call(session, "graph_reasoning_query", Map.of("operation", "ASSETS")).get("data")).get("portability");
            assertEquals("INVALID", artifact(report, "models/kge.json").get("status"));
            session.save(file);
        }
        try (LocalReasoningSession session = LocalReasoningSession.open(file)) {
            assertEquals("ERROR", call(session, "graph_embeddings", Map.of("action", "score", "head", "a", "relation", "parent", "tail", "b")).get("status"));
        }
    }

    private static Map<?, ?> artifact(Map<?, ?> report, String name) {
        return ((List<?>) report.get("artifacts")).stream().map(item -> (Map<?, ?>) item)
                .filter(item -> name.equals(item.get("name"))).findFirst().orElseThrow();
    }

    private static UnifiedGraph graph() {
        UnifiedGraph graph = new UnifiedGraph();
        for (String id : List.of("a", "b", "c")) graph.addEntity(id, "PERSON", id);
        graph.addRelation("ab", "a", "b", "parent", 1.0);
        graph.addRelation("bc", "b", "c", "parent", 1.0);
        return graph;
    }

    private static Map<String, Object> call(LocalReasoningSession session, String name, Map<String, Object> args) {
        return MiniJson.parseObject(LocalToolDispatcher.create().dispatch(session, name, MiniJson.write(args)));
    }
}
