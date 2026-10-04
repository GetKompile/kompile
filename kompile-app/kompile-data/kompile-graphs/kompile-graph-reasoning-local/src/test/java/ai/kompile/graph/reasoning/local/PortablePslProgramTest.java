package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.psl.*;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortablePslProgramTest {
    @TempDir Path temp;

    private static PslProgram program() {
        var p = new PslProgram().observe("Signal", 0.8, "Alice").target("Custom", "Alice");
        p.addRule(PslRule.weighted(4, true, List.of(PslAtom.of("Signal", false, Term.var("X"))),
                List.of(PslAtom.of("Custom", false, Term.var("X")))));
        p.addRule(PslRule.weighted(1, true, List.of(PslAtom.of("Custom", false, Term.var("X"))), List.of()));
        p.addArithmeticRule(ArithmeticRule.hard(
                List.of(new ArithmeticRule.ArithmeticTerm("Custom", List.of(Term.var("X")), 1, false)),
                List.of(new ArithmeticRule.ArithmeticTerm(null, List.of(), 0.6, false)), RelOp.LEQ));
        return p;
    }

    private static UnifiedGraph graph(PslProgram p) {
        return new UnifiedGraph().putArtifactText(PslProgramArtifactCodec.ARTIFACT, PslProgramArtifactCodec.encode(p));
    }

    private static Map<String, Object> call(UnifiedGraph graph, Map<String, Object> args) {
        try (var session = LocalReasoningSession.of(graph)) {
            return MiniJson.parseObject(LocalToolDispatcher.create().dispatch(session, "graph_psl", MiniJson.write(args)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object v) { return (Map<String, Object>) v; }

    @Test void customPredicateAndArithmeticMatchDirectJavaSolverAfterPortableReload() throws Exception {
        var p = program();
        var ground = p.groundAll(PortablePslProgram.LIMITS);
        var expected = new AdmmHlMrfInference().solve(p, ground.logicalRules(), ground.arithmeticRules(), 2000, 1e-6, 1e6);
        var graph = graph(p);
        Path file = temp.resolve("custom.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        var result = call(UnifiedGraph.load(file), Map.of());
        assertEquals("OK", result.get("status"), result.toString());
        assertEquals("ADMM_JAVA", result.get("solver"));
        assertEquals(expected.values(), object(result.get("values")));
        assertEquals(0.6, ((Number) object(result.get("targets")).get("Custom(Alice)")).doubleValue(), 1e-4);
        assertEquals(true, result.get("hardConstraintsSatisfied"));
        assertEquals(1L, result.get("arithmeticRuleCount"));
    }

    @Test void evidenceIsExactTargetOnlyAndNeverMutatesTheArchivedProgram() {
        var graph = graph(program());
        byte[] original = graph.artifact(PslProgramArtifactCodec.ARTIFACT).clone();
        var result = call(graph, Map.of("evidence", Map.of("Custom(Alice)", 0.3)));
        assertEquals("OK", result.get("status"), result.toString());
        assertEquals(0.3, object(result.get("values")).get("Custom(Alice)"));
        assertEquals(Map.of("Custom(Alice)", 0.3), result.get("evidence"));
        assertArrayEquals(original, graph.artifact(PslProgramArtifactCodec.ARTIFACT));
        assertEquals("ERROR", call(graph, Map.of("evidence", Map.of("Signal(Alice)", 0.2))).get("status"));
        assertEquals("ERROR", call(graph, Map.of("evidence", Map.of("Custom(alice)", 0.2))).get("status"));
        assertEquals("ERROR", call(graph, Map.of("evidence", Map.of("Custom(Alice)", "0.2"))).get("status"));
        assertEquals("ERROR", call(graph, Map.of("evidence", Map.of("Custom(Alice)", 1.1))).get("status"));
    }

    @Test void impossibleObservedHardRuleIsPartialNeverSuccessfulConvergence() {
        var p = new PslProgram().observe("A", 1, "x").observe("B", 0, "x");
        p.addRule(PslRule.hard(List.of(PslAtom.ground("A", "x")), List.of(PslAtom.ground("B", "x"))));
        var result = call(graph(p), Map.of());
        assertEquals("PARTIAL", result.get("status"), result.toString());
        assertEquals(false, result.get("hardConstraintsSatisfied"));
        assertEquals(1.0, result.get("maxHardViolation"));
    }

    @Test void absentInvalidAndStaleProgramsNeverFallBackToBinary() {
        var graph = new UnifiedGraph().putArtifact("reasoning/fol-psl-program.bin", new byte[]{1,2,3});
        assertEquals("ERROR", call(graph, Map.of()).get("status"));
        graph.putArtifactText(PslProgramArtifactCodec.ARTIFACT, "{}");
        assertEquals("ERROR", call(graph, Map.of()).get("status"));
        graph.putArtifactText(PslProgramArtifactCodec.ARTIFACT, PslProgramArtifactCodec.encode(program()));
        graph.meta("learning.reasoningStale", true);
        assertEquals("ERROR", call(graph, Map.of()).get("status"));
        graph.meta("learning.reasoningStale", false).meta("codeIndexGeneration.project", "new");
        assertEquals("ERROR", call(graph, Map.of()).get("status"));
        graph.meta("codeLearningGeneration.project", "old");
        assertEquals("ERROR", call(graph, Map.of()).get("status"));
        graph.meta("codeLearningGeneration.project", "new");
        assertEquals("OK", call(graph, Map.of()).get("status"));
    }

    @Test void argumentCapsFailAndRuntimeGroundingNeverReturnsPartialWork() {
        var graph = graph(program());
        for (Object value : List.of(0, 2001, 1.5, "3")) {
            assertEquals("ERROR", call(graph, Map.of("maxIterations", value)).get("status"));
        }
        assertEquals("ERROR", call(graph, Map.of("typo", true)).get("status"));
        var p = new PslProgram();
        for (int i = 0; i < 120; i++) p.target("P", "n" + i);
        p.addRule(PslRule.weighted(1, true, List.of(PslAtom.of("P", false, Term.var("X"))),
                List.of(PslAtom.of("P", false, Term.var("Y")))));
        var overBudget = call(graph(p), Map.of());
        assertEquals("ERROR", overBudget.get("status"), overBudget.toString());
        assertTrue(overBudget.get("message").toString().toLowerCase().contains("budget"), overBudget.toString());
        assertFalse(overBudget.containsKey("values"));
    }

    @Test void activationReportsExecutableAndInvalidContracts() {
        var graph = graph(program());
        assertEquals("ACTIVE", PortablePslProgram.activation(graph).get("status"));
        graph.putArtifactText(PslProgramArtifactCodec.ARTIFACT, "{}");
        assertEquals("INVALID", PortablePslProgram.activation(graph).get("status"));
    }

    @Test void emptySumCannotSilentlyDropAnImpossibleHardConstraint() {
        var p = new PslProgram().addArithmeticRule(ArithmeticRule.hard(
                List.of(new ArithmeticRule.ArithmeticTerm("P", List.of(Term.var("X")), 1, true)),
                List.of(new ArithmeticRule.ArithmeticTerm(null, List.of(), 1, false)), RelOp.GEQ));
        var result = call(graph(p), Map.of());
        assertEquals("PARTIAL", result.get("status"), result.toString());
        assertEquals(false, result.get("hardConstraintsSatisfied"));
        assertEquals(1.0, result.get("maxHardViolation"));
        assertEquals(1L, result.get("arithmeticRuleCount"));
    }

    @Test void iterationCapIsReportedAsPartialWithoutClaimingConvergence() {
        var result = call(graph(program()), Map.of("maxIterations", 1));
        assertEquals("PARTIAL", result.get("status"), result.toString());
        assertEquals(false, result.get("converged"));
    }

    @Test void unavailableProducerDiagnosticSurvivesReloadAndPreventsBinaryFallback() throws Exception {
        var graph = graph(program());
        var unsupported = new PslProgram().registerFunction("Callback", args -> 0);
        graph.putArtifactText(PslProgramArtifactCodec.ARTIFACT, PslProgramArtifactCodec.encodeForStorage(unsupported));
        graph.putArtifact("reasoning/fol-psl-program.bin", new byte[]{1, 2, 3});
        Path file = temp.resolve("unavailable.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        var loaded = UnifiedGraph.load(file);
        var result = call(loaded, Map.of());
        assertEquals("ERROR", result.get("status"));
        assertTrue(result.get("message").toString().contains("external functions"));
        assertEquals("INVALID", PortablePslProgram.activation(loaded).get("status"));
        assertFalse(result.containsKey("values"));
    }

    @Test void isolatedJavaOnlyClasspathCanExecuteLoadedProgram() throws Exception {
        var graph = graph(program());
        Path file = temp.resolve("java-only.kgraph");
        graph.save(file, KGraphCompatibilityPolicy.PORTABLE_V2);
        var expected = call(graph, Map.of());
        var urls = new java.net.URL[]{LocalToolDispatcher.class.getProtectionDomain().getCodeSource().getLocation(),
                UnifiedGraph.class.getProtectionDomain().getCodeSource().getLocation(),
                org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation()};
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.eclipse.deeplearning4j.linalg.factory.Nd4j"));
            Class<?> sessionType = loader.loadClass(LocalReasoningSession.class.getName());
            Object session = sessionType.getMethod("open", Path.class).invoke(null, file);
            try {
                Class<?> dispatcherType = loader.loadClass(LocalToolDispatcher.class.getName());
                Object dispatcher = dispatcherType.getMethod("create").invoke(null);
                var actual = MiniJson.parseObject((String) dispatcherType.getMethod("dispatch", sessionType, String.class, String.class)
                        .invoke(dispatcher, session, "graph_psl", "{}"));
                assertEquals(expected.get("status"), actual.get("status"));
                assertEquals(expected.get("values"), actual.get("values"));
                assertEquals(false, actual.get("binaryCacheUsed"));
            } finally { sessionType.getMethod("close").invoke(session); }
        }
    }
}
