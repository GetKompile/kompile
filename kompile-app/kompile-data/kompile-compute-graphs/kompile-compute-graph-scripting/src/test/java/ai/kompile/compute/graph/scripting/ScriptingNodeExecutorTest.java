package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptingNodeExecutorTest {

    private ScriptingNodeExecutor executor;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new ScriptingNodeExecutor();
        ComputeGraph graph = ComputeGraph.builder().id("test").build();
        context = new ExecutionContext("test-exec", graph, new InMemoryArtifactStore());
    }

    @Test
    void testJavascript_simpleReturn() {
        ComputeNode node = ComputeNode.builder()
                .id("js1")
                .name("JS Test")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("40 + 2")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(42, result.getOutputs().get("_result"));
    }

    @Test
    void testJavascript_accessInputs() {
        ComputeNode node = ComputeNode.builder()
                .id("js2")
                .name("JS Inputs")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("x * 2 + y")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("x", 10, "y", 5), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(25, result.getOutputs().get("_result"));
    }

    @Test
    void testJavascript_returnObject() {
        ComputeNode node = ComputeNode.builder()
                .id("js3")
                .name("JS Object")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("({score: input * 0.5, label: 'computed'})")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("input", 10), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(5, ((Number) result.getOutputs().get("score")).intValue());
        assertEquals("computed", result.getOutputs().get("label"));
    }

    @Test
    void testJavascript_outputVariable() {
        ComputeNode node = ComputeNode.builder()
                .id("js4")
                .name("JS Output Var")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("var _output = {mean: (a + b) / 2, sum: a + b};")
                .build();

        ExecutionResult result = executor.execute(node, Map.of("a", 10, "b", 20), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(15, ((Number) result.getOutputs().get("mean")).intValue());
        assertEquals(30, ((Number) result.getOutputs().get("sum")).intValue());
    }

    @Test
    void testJavascript_accessParameters() {
        ComputeNode node = ComputeNode.builder()
                .id("js5")
                .name("JS Params")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("x * $multiplier")
                .parameters(Map.of("multiplier", 3))
                .build();

        ExecutionResult result = executor.execute(node, Map.of("x", 7), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(21, result.getOutputs().get("_result"));
    }

    @Test
    void testJavascript_syntaxError_fails() {
        ComputeNode node = ComputeNode.builder()
                .id("js-bad")
                .name("Bad JS")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("function( { broken syntax")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void testJavascript_runtimeError_fails() {
        ComputeNode node = ComputeNode.builder()
                .id("js-err")
                .name("Error JS")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("nonExistentFunction()")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void testJavascript_consoleOutputCaptured() {
        ComputeNode node = ComputeNode.builder()
                .id("js-console")
                .name("Console JS")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("console.log('hello from node'); 42")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertTrue(result.getConsoleOutput().contains("hello from node"));
    }

    @Test
    void testJavascript_arrayReturn() {
        ComputeNode node = ComputeNode.builder()
                .id("js-arr")
                .name("Array JS")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("[1, 2, 3, 4, 5]")
                .build();

        ExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        Object resultVal = result.getOutputs().get("_result");
        assertTrue(resultVal instanceof List);
        assertEquals(List.of(1, 2, 3, 4, 5), resultVal);
    }

    @Test
    void testJavascript_outputBindings() {
        ComputeNode node = ComputeNode.builder()
                .id("js-bindings")
                .name("Bindings JS")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("var result = x + y; var product = x * y; result;")
                .outputBindings(Map.of("result", "sum", "product", "prod"))
                .build();

        ExecutionResult result = executor.execute(node, Map.of("x", 3, "y", 4), context);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(7, result.getOutputs().get("sum"));
        assertEquals(12, result.getOutputs().get("prod"));
    }

    @Test
    void testValidate_validScript() {
        ComputeNode node = ComputeNode.builder()
                .id("valid")
                .name("Valid")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("var x = 1 + 2; x;")
                .build();

        assertNull(executor.validate(node));
    }

    @Test
    void testValidate_invalidScript() {
        ComputeNode node = ComputeNode.builder()
                .id("invalid")
                .name("Invalid")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("function( { broken")
                .build();

        String error = executor.validate(node);
        assertNotNull(error);
        assertTrue(error.contains("parse error"));
    }

    @Test
    void testValidate_emptyScript() {
        ComputeNode node = ComputeNode.builder()
                .id("empty")
                .name("Empty")
                .executionType(NodeExecutionType.JAVASCRIPT)
                .script("")
                .build();

        String error = executor.validate(node);
        assertNotNull(error);
        assertTrue(error.contains("empty"));
    }

    @Test
    void testSupportedTypes() {
        assertTrue(executor.supportedTypes().contains(NodeExecutionType.JAVASCRIPT));
        // Python is now handled by Python4jNodeExecutor, not this executor
        assertFalse(executor.supportedTypes().contains(NodeExecutionType.PYTHON));
    }
}
