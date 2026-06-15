package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionNodeExecutorTest {

    private ExpressionNodeExecutor executor;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new ExpressionNodeExecutor();
        ComputeGraph graph = ComputeGraph.builder().id("test").build();
        context = new ExecutionContext("test-exec", graph, new InMemoryArtifactStore());
    }

    @Test
    void testSimpleArithmetic() {
        ComputeNode node = ComputeNode.builder()
                .id("expr1")
                .name("Add")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#a + #b")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("a", 10, "b", 20), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(30, result.getOutputs().get("_result"));
    }

    @Test
    void testAssignment() {
        ComputeNode node = ComputeNode.builder()
                .id("expr2")
                .name("Assign")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#sum = #a + #b; #product = #a * #b")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("a", 3, "b", 4), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(7, result.getOutputs().get("sum"));
        assertEquals(12, result.getOutputs().get("product"));
    }

    @Test
    void testStringOperations() {
        ComputeNode node = ComputeNode.builder()
                .id("expr3")
                .name("String")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#greeting = #name + ' has ' + #count + ' items'")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("name", "Alice", "count", 5), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals("Alice has 5 items", result.getOutputs().get("greeting"));
    }

    @Test
    void testConditionalExpression() {
        ComputeNode node = ComputeNode.builder()
                .id("expr4")
                .name("Conditional")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#label = #score > 0.5 ? 'high' : 'low'")
                .build();

        ExecutionResult resultHigh = executor.execute(node, Map.of("score", 0.8), context);
        assertEquals("high", resultHigh.getOutputs().get("label"));

        ExecutionResult resultLow = executor.execute(node, Map.of("score", 0.3), context);
        assertEquals("low", resultLow.getOutputs().get("label"));
    }

    @Test
    void testMultipleExpressions() {
        ComputeNode node = ComputeNode.builder()
                .id("expr5")
                .name("Multi")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#x = #a * 2; #y = #b * 3; #total = #x + #y")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("a", 5, "b", 10), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(10, result.getOutputs().get("x"));
        assertEquals(30, result.getOutputs().get("y"));
        assertEquals(40, result.getOutputs().get("total"));
    }

    @Test
    void testParameterAccess() {
        ComputeNode node = ComputeNode.builder()
                .id("expr6")
                .name("Params")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#result = #value * #param_multiplier")
                .parameters(Map.of("multiplier", 10))
                .build();

        ExecutionResult result = executor.execute(node, Map.of("value", 7), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(70, result.getOutputs().get("result"));
    }

    @Test
    void testInvalidExpression_fails() {
        ComputeNode node = ComputeNode.builder()
                .id("bad")
                .name("Bad")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#x = someUndefinedMethod()")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void testValidate_valid() {
        ComputeNode node = ComputeNode.builder()
                .id("v1")
                .name("Valid")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("#x = 1 + 2")
                .build();

        assertNull(executor.validate(node));
    }

    @Test
    void testValidate_empty() {
        ComputeNode node = ComputeNode.builder()
                .id("v2")
                .name("Empty")
                .executionType(NodeExecutionType.EXPRESSION)
                .script("")
                .build();

        assertNotNull(executor.validate(node));
    }

    @Test
    void testSupportedTypes() {
        assertTrue(executor.supportedTypes().contains(NodeExecutionType.EXPRESSION));
        assertEquals(1, executor.supportedTypes().size());
    }
}
