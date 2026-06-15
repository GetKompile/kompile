package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.engine.PassthroughNodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.scripting.ExpressionNodeExecutor;
import ai.kompile.compute.graph.scripting.ScriptingNodeExecutor;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real execution tests for the compute graph engine.
 * Uses actual JS execution (GraalVM), SpEL expressions, and passthrough nodes
 * wired together in multi-node DAGs with conditional edges, data mappings,
 * fan-out/fan-in patterns, and error propagation.
 */
class ComputeGraphExecutionTest {

    private DefaultGraphExecutor engine;

    @BeforeEach
    void setUp() {
        List<NodeExecutor> executors = List.of(
                new ScriptingNodeExecutor(),
                new ExpressionNodeExecutor(),
                new PassthroughNodeExecutor()
        );
        engine = new DefaultGraphExecutor(executors, new InMemoryArtifactStore());
    }

    // ==================== Mixed-Type Linear Chains ====================

    @Nested
    class MixedTypeChains {

        @Test
        void testJsToExpressionChain() {
            // JS node computes score, expression node classifies it
            ComputeGraph graph = ComputeGraph.builder()
                    .id("js-expr-chain")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("compute")
                                    .name("Compute Score")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({score: (amount * rate) / 100, currency: 'USD'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("classify")
                                    .name("Classify")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#label = #score > 500 ? 'premium' : 'standard'")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("compute").targetNodeId("classify").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("amount", 10000, "rate", 7.5));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(List.of("compute", "classify"), result.getExecutionOrder());
            assertEquals("premium", result.getFinalOutputs().get("label"));
        }

        @Test
        void testThreeNodePipeline_jsExprPassthrough() {
            // JS → Expression → Passthrough
            ComputeGraph graph = ComputeGraph.builder()
                    .id("three-stage")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("transform")
                                    .name("Transform")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("var _output = {doubled: x * 2, tripled: x * 3};")
                                    .build(),
                            ComputeNode.builder()
                                    .id("aggregate")
                                    .name("Aggregate")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#total = #doubled + #tripled")
                                    .build(),
                            ComputeNode.builder()
                                    .id("output")
                                    .name("Output")
                                    .executionType(NodeExecutionType.PASSTHROUGH)
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("transform").targetNodeId("aggregate").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("aggregate").targetNodeId("output").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("x", 10));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(50, ((Number) result.getFinalOutputs().get("total")).intValue()); // 20+30
        }
    }

    // ==================== Conditional Branching ====================

    @Nested
    class ConditionalBranching {

        @Test
        void testConditionalBranch_highPath() {
            ComputeGraph graph = buildBranchingGraph();
            GraphExecutionResult result = engine.execute(graph, Map.of("score", 85));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals("APPROVED", result.getFinalOutputs().get("_result"));
            assertTrue(result.getSkippedNodes().contains("low-handler"));
        }

        @Test
        void testConditionalBranch_lowPath() {
            ComputeGraph graph = buildBranchingGraph();
            GraphExecutionResult result = engine.execute(graph, Map.of("score", 30));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals("REJECTED", result.getFinalOutputs().get("_result"));
            assertTrue(result.getSkippedNodes().contains("high-handler"));
        }

        private ComputeGraph buildBranchingGraph() {
            return ComputeGraph.builder()
                    .id("branching")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("entry")
                                    .name("Entry")
                                    .executionType(NodeExecutionType.PASSTHROUGH)
                                    .build(),
                            ComputeNode.builder()
                                    .id("high-handler")
                                    .name("High Score Handler")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("'APPROVED'")
                                    .build(),
                            ComputeNode.builder()
                                    .id("low-handler")
                                    .name("Low Score Handler")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("'REJECTED'")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder()
                                    .id("e-high")
                                    .sourceNodeId("entry")
                                    .targetNodeId("high-handler")
                                    .condition("#score >= 70")
                                    .build(),
                            ComputeEdge.builder()
                                    .id("e-low")
                                    .sourceNodeId("entry")
                                    .targetNodeId("low-handler")
                                    .condition("#score < 70")
                                    .build()
                    ))
                    .build();
        }
    }

    // ==================== Data Mapping ====================

    @Nested
    class DataMapping {

        @Test
        void testDataMapping_renamesBetweenNodes() {
            ComputeGraph graph = ComputeGraph.builder()
                    .id("mapping")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("producer")
                                    .name("Producer")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({rawScore: input * 1.5, metadata: 'v1'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("consumer")
                                    .name("Consumer")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#finalScore = #score * 2")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder()
                                    .id("e1")
                                    .sourceNodeId("producer")
                                    .targetNodeId("consumer")
                                    .dataMapping(Map.of("rawScore", "score"))
                                    .build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("input", 10));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            // rawScore = 10 * 1.5 = 15, renamed to score, then finalScore = 15 * 2 = 30
            assertEquals(30, ((Number) result.getFinalOutputs().get("finalScore")).intValue());
        }
    }

    // ==================== Fan-Out / Fan-In ====================

    @Nested
    class FanOutFanIn {

        @Test
        void testFanOut_twoParallelPaths_thenMerge() {
            // Source → [PathA, PathB] → Merge
            ComputeGraph graph = ComputeGraph.builder()
                    .id("fan-out-in")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("source")
                                    .name("Source")
                                    .executionType(NodeExecutionType.PASSTHROUGH)
                                    .build(),
                            ComputeNode.builder()
                                    .id("pathA")
                                    .name("Path A")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({sumResult: val + 100})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("pathB")
                                    .name("Path B")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({productResult: val * 10})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("merge")
                                    .name("Merge")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#combined = #sumResult + #productResult")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("source").targetNodeId("pathA").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("source").targetNodeId("pathB").build(),
                            ComputeEdge.builder().id("e3").sourceNodeId("pathA").targetNodeId("merge").build(),
                            ComputeEdge.builder().id("e4").sourceNodeId("pathB").targetNodeId("merge").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("val", 5));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            // pathA: 5+100=105, pathB: 5*10=50, merge: 105+50=155
            assertEquals(155, ((Number) result.getFinalOutputs().get("combined")).intValue());
        }
    }

    // ==================== Complex Workflows ====================

    @Nested
    class ComplexWorkflows {

        @Test
        void testETLPipeline_extractTransformLoad() {
            // Simulates a data pipeline: parse → validate → enrich → output
            ComputeGraph graph = ComputeGraph.builder()
                    .id("etl-pipeline")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("parse")
                                    .name("Parse Input")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("var s = String(raw); var parts = s.split(','); ({name: parts[0], value: parseInt(parts[1])})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("validate")
                                    .name("Validate")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#valid = #value != null && #value > 0; #errorMsg = #valid ? null : 'invalid value'; #name = #name; #value = #value")
                                    .build(),
                            ComputeNode.builder()
                                    .id("enrich")
                                    .name("Enrich")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({enrichedName: String(name).toUpperCase(), normalizedValue: value / 100.0})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("output")
                                    .name("Format Output")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#record = #enrichedName + ':' + #normalizedValue")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("parse").targetNodeId("validate").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("validate").targetNodeId("enrich")
                                    .condition("#valid == true").build(),
                            ComputeEdge.builder().id("e3").sourceNodeId("enrich").targetNodeId("output").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("raw", "Widget,5000"));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(4, result.getExecutionOrder().size());
            String record = (String) result.getFinalOutputs().get("record");
            assertTrue(record.startsWith("WIDGET:"), "Expected record to start with WIDGET: but was: " + record);
            assertTrue(record.contains("50"), "Expected record to contain 50 but was: " + record);
        }

        @Test
        void testETLPipeline_invalidInput_skipsEnrich() {
            // Same pipeline but with invalid data — enrich and output should be skipped
            ComputeGraph graph = ComputeGraph.builder()
                    .id("etl-invalid")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("parse")
                                    .name("Parse Input")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({name: 'bad', value: -1})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("validate")
                                    .name("Validate")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#valid = #value != null && #value > 0")
                                    .build(),
                            ComputeNode.builder()
                                    .id("enrich")
                                    .name("Enrich")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("'should not reach here'")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("parse").targetNodeId("validate").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("validate").targetNodeId("enrich")
                                    .condition("#valid == true").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of());

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertTrue(result.getSkippedNodes().contains("enrich"));
        }

        @Test
        void testScoringWorkflow_multiCriteria() {
            // Multiple scoring criteria computed in JS, aggregated in Expression
            ComputeGraph graph = ComputeGraph.builder()
                    .id("scoring")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("input")
                                    .name("Input")
                                    .executionType(NodeExecutionType.PASSTHROUGH)
                                    .build(),
                            ComputeNode.builder()
                                    .id("credit-score")
                                    .name("Credit Score")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({creditScore: Math.min(100, Math.max(0, income / 1000))})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("risk-score")
                                    .name("Risk Score")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({riskScore: age >= 25 && age <= 65 ? 20 : 60})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("decision")
                                    .name("Decision")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#totalScore = #creditScore - #riskScore; " +
                                            "#decision = #totalScore > 30 ? 'APPROVE' : 'DECLINE'")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("input").targetNodeId("credit-score").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("input").targetNodeId("risk-score").build(),
                            ComputeEdge.builder().id("e3").sourceNodeId("credit-score").targetNodeId("decision").build(),
                            ComputeEdge.builder().id("e4").sourceNodeId("risk-score").targetNodeId("decision").build()
                    ))
                    .build();

            // Good candidate: income=80000 → credit=80, age=30 → risk=20, total=60 → APPROVE
            GraphExecutionResult approved = engine.execute(graph,
                    Map.of("income", 80000, "age", 30));
            assertEquals(ExecutionStatus.COMPLETED, approved.getStatus());
            assertEquals("APPROVE", approved.getFinalOutputs().get("decision"));

            // Bad candidate: income=20000 → credit=20, age=18 → risk=60, total=-40 → DECLINE
            GraphExecutionResult declined = engine.execute(graph,
                    Map.of("income", 20000, "age", 18));
            assertEquals(ExecutionStatus.COMPLETED, declined.getStatus());
            assertEquals("DECLINE", declined.getFinalOutputs().get("decision"));
        }
    }

    // ==================== Error Handling ====================

    @Nested
    class ErrorHandling {

        @Test
        void testJsError_failsGraph() {
            ComputeGraph graph = ComputeGraph.builder()
                    .id("error-graph")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("bad-js")
                                    .name("Bad JS")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("undefinedVariable.doSomething()")
                                    .build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of());
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertNotNull(result.getNodeResults().get("bad-js").getError());
        }

        @Test
        void testValidation_detectsCyclicGraph() {
            ComputeGraph graph = ComputeGraph.builder()
                    .id("cyclic")
                    .nodes(List.of(
                            ComputeNode.builder().id("A").name("A").executionType(NodeExecutionType.PASSTHROUGH).build(),
                            ComputeNode.builder().id("B").name("B").executionType(NodeExecutionType.PASSTHROUGH).build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("A").targetNodeId("B").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("B").targetNodeId("A").build()
                    ))
                    .build();

            // Validation or execution should detect the cycle
            String error = engine.validate(graph);
            if (error == null) {
                // If validate doesn't catch it, execute will throw
                GraphExecutionResult result = engine.execute(graph, Map.of());
                assertEquals(ExecutionStatus.FAILED, result.getStatus());
            } else {
                assertNotNull(error);
            }
        }
    }

    // ==================== Node Parameters ====================

    @Nested
    class NodeParameters {

        @Test
        void testJsWithParameters() {
            ComputeNode node = ComputeNode.builder()
                    .id("parameterized")
                    .name("Parameterized")
                    .executionType(NodeExecutionType.JAVASCRIPT)
                    .script("input * $multiplier + $offset")
                    .parameters(Map.of("multiplier", 3, "offset", 10))
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("params-test")
                    .nodes(List.of(node))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("input", 5));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(25, ((Number) result.getFinalOutputs().get("_result")).intValue()); // 5*3+10
        }

        @Test
        void testExpressionWithParameters() {
            ComputeNode node = ComputeNode.builder()
                    .id("expr-params")
                    .name("Expr Params")
                    .executionType(NodeExecutionType.EXPRESSION)
                    .script("#result = #value * #param_factor")
                    .parameters(Map.of("factor", 7))
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("expr-params-test")
                    .nodes(List.of(node))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("value", 6));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(42, ((Number) result.getFinalOutputs().get("result")).intValue());
        }
    }

    // ==================== Output Bindings ====================

    @Nested
    class OutputBindings {

        @Test
        void testOutputBindings_selectiveExtraction() {
            ComputeNode node = ComputeNode.builder()
                    .id("bindings")
                    .name("Bindings Test")
                    .executionType(NodeExecutionType.JAVASCRIPT)
                    .script("var total = a + b + c; var avg = total / 3; 'done';")
                    .outputBindings(Map.of("total", "sum", "avg", "average"))
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("bindings-test")
                    .nodes(List.of(node))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("a", 10, "b", 20, "c", 30));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
            assertEquals(60, ((Number) result.getFinalOutputs().get("sum")).intValue());
            assertEquals(20, ((Number) result.getFinalOutputs().get("average")).intValue());
        }
    }
}
