package ai.kompile.compute.graph.drools;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DroolsNodeExecutorTest {

    private DroolsNodeExecutor executor;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new DroolsNodeExecutor(new DroolsRuleCompiler());
        ComputeGraph graph = ComputeGraph.builder().id("test").build();
        context = new ExecutionContext("test-exec", graph, new InMemoryArtifactStore());
    }

    @Test
    void testSimpleRule_setsOutput() {
        ComputeNode node = ComputeNode.builder()
                .id("drools1")
                .name("Simple Rule")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script("$facts.setOutput(\"result\", \"rule_fired\");")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("input", "test"), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals("rule_fired", result.getOutputs().get("result"));
        assertTrue((int) result.getOutputs().get("_rulesFired") > 0);
    }

    @Test
    void testRule_readsInputs() {
        ComputeNode node = ComputeNode.builder()
                .id("drools2")
                .name("Input Rule")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script("$facts.setOutput(\"doubled\", ((Number)$facts.getInput(\"value\")).intValue() * 2);")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("value", 21), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(42, result.getOutputs().get("doubled"));
    }

    @Test
    void testRule_readsParameters() {
        ComputeNode node = ComputeNode.builder()
                .id("drools3")
                .name("Param Rule")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .parameters(Map.of("threshold", 0.5))
                .script("$facts.setOutput(\"threshold_val\", $facts.getParam(\"threshold\"));")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(0.5, result.getOutputs().get("threshold_val"));
    }

    @Test
    void testFullDrlRule() {
        String drl = """
                rule "score-check"
                  when
                    $facts : NodeFacts()
                    $score : NamedFact(name == "score")
                  then
                    double val = ((Number)$score.getValue()).doubleValue();
                    if (val > 0.7) {
                        $facts.setOutput("decision", "APPROVED");
                    } else {
                        $facts.setOutput("decision", "REJECTED");
                    }
                end
                """;

        ComputeNode node = ComputeNode.builder()
                .id("drools4")
                .name("Full DRL")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script(drl)
                .build();

        ExecutionResult resultApproved = executor.execute(node, Map.of("score", 0.85), context);
        assertEquals(ExecutionStatus.COMPLETED, resultApproved.getStatus());
        assertEquals("APPROVED", resultApproved.getOutputs().get("decision"));

        ExecutionResult resultRejected = executor.execute(node, Map.of("score", 0.3), context);
        assertEquals(ExecutionStatus.COMPLETED, resultRejected.getStatus());
        assertEquals("REJECTED", resultRejected.getOutputs().get("decision"));
    }

    @Test
    void testMultipleRules() {
        String drl = """
                rule "classify-high"
                  when
                    $facts : NodeFacts()
                    $score : NamedFact(name == "score", ((Number)value).doubleValue() > 0.8)
                  then
                    $facts.setOutput("tier", "premium");
                end

                rule "classify-low"
                  when
                    $facts : NodeFacts()
                    $score : NamedFact(name == "score", ((Number)value).doubleValue() <= 0.8)
                  then
                    $facts.setOutput("tier", "standard");
                end
                """;

        ComputeNode node = ComputeNode.builder()
                .id("drools5")
                .name("Multi Rules")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script(drl)
                .build();

        ExecutionResult result = executor.execute(node, Map.of("score", 0.9), context);
        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals("premium", result.getOutputs().get("tier"));
    }

    @Test
    void testInvalidDrl_fails() {
        ComputeNode node = ComputeNode.builder()
                .id("bad")
                .name("Bad DRL")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script("rule \"broken\"\n  when\n    INVALID SYNTAX\n  then\n  end")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void testEmptyScript_failsValidation() {
        ComputeNode node = ComputeNode.builder()
                .id("empty")
                .name("Empty")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script("")
                .build();

        String error = executor.validate(node);
        assertNotNull(error);
        assertTrue(error.contains("empty"));
    }

    @Test
    void testSupportedTypes() {
        assertTrue(executor.supportedTypes().contains(NodeExecutionType.DROOLS_RULE));
        assertTrue(executor.supportedTypes().contains(NodeExecutionType.DROOLS_INFERENCE));
    }
}
