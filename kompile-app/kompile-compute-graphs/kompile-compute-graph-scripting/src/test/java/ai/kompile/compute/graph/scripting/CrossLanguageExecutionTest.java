package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.engine.PassthroughNodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineServiceImpl;
import ai.kompile.process.service.StepExecutionDispatcher;
import ai.kompile.process.workflow.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language execution tests: Python ↔ JavaScript data flow.
 *
 * <p>Uses Python4J (embedded CPython) when native libs are available,
 * otherwise falls back to the subprocess-based Python executor.
 * JavaScript runs via GraalVM polyglot (ScriptingNodeExecutor).
 */
class CrossLanguageExecutionTest {

    private static NodeExecutor pythonExecutor;
    private DefaultGraphExecutor engine;

    @BeforeAll
    static void initPython() {
        // Try Python4J first, fall back to subprocess
        try {
            Class.forName("org.nd4j.python4j.PythonExecutioner");
            pythonExecutor = new Python4jNodeExecutor();
            System.out.println("[CrossLanguageExecutionTest] Using Python4J (embedded CPython)");
        } catch (Throwable e) {
            pythonExecutor = new PythonSubprocessNodeExecutor();
            System.out.println("[CrossLanguageExecutionTest] Using Python subprocess fallback");
        }
    }

    @BeforeEach
    void setUp() {
        List<NodeExecutor> executors = List.of(
                new ScriptingNodeExecutor(),
                new ExpressionNodeExecutor(),
                new PassthroughNodeExecutor(),
                pythonExecutor
        );
        engine = new DefaultGraphExecutor(executors, new InMemoryArtifactStore());
    }

    // ==================== Python → JavaScript ====================

    @Nested
    class PythonToJavaScript {

        @Test
        void testPythonCompute_thenJsFormat() {
            // Python computes statistics, JS formats the report
            ComputeGraph graph = ComputeGraph.builder()
                    .id("py-to-js")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("stats")
                                    .name("Compute Stats")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("_output = {'mean': sum(values) / len(values), 'total': sum(values), 'count': len(values)}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("format")
                                    .name("Format Report")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({report: 'Total: ' + total + ', Mean: ' + mean + ', Count: ' + count})")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("stats").targetNodeId("format").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("values", List.of(10, 20, 30, 40)));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            String report = (String) result.getFinalOutputs().get("report");
            assertNotNull(report);
            assertTrue(report.contains("Total: 100"), "Expected Total: 100 in: " + report);
            assertTrue(report.contains("Mean: 25"), "Expected Mean: 25 in: " + report);
            assertTrue(report.contains("Count: 4"), "Expected Count: 4 in: " + report);
        }

        @Test
        void testPythonStringProcessing_thenJsTransform() {
            // Python does text processing, JS does final transformation
            ComputeGraph graph = ComputeGraph.builder()
                    .id("py-str-js")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("process")
                                    .name("Python Text Process")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("words = text.split()\n_output = {'wordCount': len(words), 'reversed': ' '.join(reversed(words)), 'upper': text.upper()}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("transform")
                                    .name("JS Transform")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({summary: 'Words: ' + wordCount + ' | ' + String(upper).substring(0, 5) + '...'})")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("process").targetNodeId("transform").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("text", "hello world from kompile"));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals("Words: 4 | HELLO...", result.getFinalOutputs().get("summary"));
        }
    }

    // ==================== JavaScript → Python ====================

    @Nested
    class JavaScriptToPython {

        @Test
        void testJsGenerate_thenPythonAnalyze() {
            // JS generates data, Python analyzes it
            ComputeGraph graph = ComputeGraph.builder()
                    .id("js-to-py")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("generate")
                                    .name("Generate Data")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({numbers: [1,4,9,16,25], label: 'squares'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("analyze")
                                    .name("Analyze")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("import math\nroots = [math.sqrt(x) for x in numbers]\n_output = {'roots': roots, 'label': label, 'maxRoot': max(roots)}")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("generate").targetNodeId("analyze").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of());

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals("squares", result.getFinalOutputs().get("label"));
            assertEquals(5.0, ((Number) result.getFinalOutputs().get("maxRoot")).doubleValue(), 0.01);
        }

        @Test
        void testJsParse_thenPythonValidate() {
            // JS parses JSON-like input, Python validates the structure
            ComputeGraph graph = ComputeGraph.builder()
                    .id("js-parse-py-validate")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("parse")
                                    .name("Parse Record")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("var parts = String(raw).split(':'); ({field: parts[0], value: parseInt(parts[1])})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("validate")
                                    .name("Python Validate")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("valid = isinstance(value, int) and value > 0\n_output = {'field': field, 'value': value, 'valid': valid}")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("parse").targetNodeId("validate").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of("raw", "temperature:42"));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals("temperature", result.getFinalOutputs().get("field"));
            assertEquals(42, ((Number) result.getFinalOutputs().get("value")).intValue());
            assertEquals(true, result.getFinalOutputs().get("valid"));
        }
    }

    // ==================== Multi-hop: Python → JS → Python ====================

    @Nested
    class MultiHopCrossLanguage {

        @Test
        void testPythonJsPython_threeNodeChain() {
            // Python preprocesses → JS transforms → Python postprocesses
            ComputeGraph graph = ComputeGraph.builder()
                    .id("py-js-py")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("preprocess")
                                    .name("Python Preprocess")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("cleaned = [x.strip().lower() for x in items]\n_output = {'cleaned': cleaned, 'originalCount': len(items)}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("transform")
                                    .name("JS Transform")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("var unique = []; for (var i = 0; i < cleaned.length; i++) { var item = String(cleaned[i]); if (unique.indexOf(item) < 0) unique.push(item); } ({unique: unique, deduped: unique.length < originalCount})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("postprocess")
                                    .name("Python Postprocess")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("sorted_items = sorted(unique)\n_output = {'sorted': sorted_items, 'deduped': deduped, 'finalCount': len(sorted_items)}")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("preprocess").targetNodeId("transform").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("transform").targetNodeId("postprocess").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("items", List.of(" Apple ", "banana", " APPLE", "Cherry ", "banana")));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals(true, result.getFinalOutputs().get("deduped"));
            assertEquals(3, ((Number) result.getFinalOutputs().get("finalCount")).intValue());
            @SuppressWarnings("unchecked")
            List<String> sorted = (List<String>) result.getFinalOutputs().get("sorted");
            assertEquals(List.of("apple", "banana", "cherry"), sorted);
        }

        @Test
        void testJsPythonJs_threeNodeChain() {
            // JS generates → Python computes → JS formats
            ComputeGraph graph = ComputeGraph.builder()
                    .id("js-py-js")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("generate")
                                    .name("JS Generate")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({width: 10, height: 5, unit: 'meters'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("compute")
                                    .name("Python Compute")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("area = width * height\nperimeter = 2 * (width + height)\n_output = {'area': area, 'perimeter': perimeter, 'unit': unit}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("format")
                                    .name("JS Format")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({result: 'Area: ' + area + ' sq ' + unit + ', Perimeter: ' + perimeter + ' ' + unit})")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("generate").targetNodeId("compute").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("compute").targetNodeId("format").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph, Map.of());

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals("Area: 50 sq meters, Perimeter: 30 meters",
                    result.getFinalOutputs().get("result"));
        }
    }

    // ==================== Python + JS + Expression (all three) ====================

    @Nested
    class AllLanguagesMixed {

        @Test
        void testPythonJsExpression_pipeline() {
            // Python extracts → JS enriches → Expression classifies
            ComputeGraph graph = ComputeGraph.builder()
                    .id("py-js-expr")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("extract")
                                    .name("Python Extract")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("parts = record.split(',')\n_output = {'name': parts[0], 'score': int(parts[1]), 'category': parts[2]}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("enrich")
                                    .name("JS Enrich")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({name: String(name).toUpperCase(), score: score, category: category, grade: score >= 90 ? 'A' : score >= 70 ? 'B' : 'C'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("classify")
                                    .name("Expression Classify")
                                    .executionType(NodeExecutionType.EXPRESSION)
                                    .script("#pass = #score >= 70; #label = #name + ' (' + #grade + ')'; #name = #name; #score = #score")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("extract").targetNodeId("enrich").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("enrich").targetNodeId("classify").build()
                    ))
                    .build();

            GraphExecutionResult result = engine.execute(graph,
                    Map.of("record", "alice,85,math"));

            assertEquals(ExecutionStatus.COMPLETED, result.getStatus(),
                    "Graph failed: " + result.getError());
            assertEquals(true, result.getFinalOutputs().get("pass"));
            assertEquals("ALICE (B)", result.getFinalOutputs().get("label"));
        }
    }

    // ==================== Conditional branching with Python ====================

    @Nested
    class ConditionalWithPython {

        @Test
        void testPythonConditionalBranch() {
            // Python evaluates → conditional edge → JS high path or JS low path
            ComputeGraph graph = ComputeGraph.builder()
                    .id("py-conditional")
                    .nodes(List.of(
                            ComputeNode.builder()
                                    .id("evaluate")
                                    .name("Python Evaluate")
                                    .executionType(NodeExecutionType.PYTHON)
                                    .script("_output = {'value': amount, 'isHigh': amount > 1000}")
                                    .build(),
                            ComputeNode.builder()
                                    .id("high")
                                    .name("High Value Path")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({result: 'HIGH: ' + value + ' (premium processing)'})")
                                    .build(),
                            ComputeNode.builder()
                                    .id("low")
                                    .name("Low Value Path")
                                    .executionType(NodeExecutionType.JAVASCRIPT)
                                    .script("({result: 'LOW: ' + value + ' (standard processing)'})")
                                    .build()
                    ))
                    .edges(List.of(
                            ComputeEdge.builder().id("e1").sourceNodeId("evaluate").targetNodeId("high")
                                    .condition("#isHigh == true").build(),
                            ComputeEdge.builder().id("e2").sourceNodeId("evaluate").targetNodeId("low")
                                    .condition("#isHigh == false").build()
                    ))
                    .build();

            // High path
            GraphExecutionResult highResult = engine.execute(graph, Map.of("amount", 5000));
            assertEquals(ExecutionStatus.COMPLETED, highResult.getStatus(),
                    "Graph failed: " + highResult.getError());
            assertTrue(((String) highResult.getFinalOutputs().get("result")).startsWith("HIGH:"));
            assertTrue(highResult.getSkippedNodes().contains("low"));

            // Low path
            GraphExecutionResult lowResult = engine.execute(graph, Map.of("amount", 200));
            assertEquals(ExecutionStatus.COMPLETED, lowResult.getStatus(),
                    "Graph failed: " + lowResult.getError());
            assertTrue(((String) lowResult.getFinalOutputs().get("result")).startsWith("LOW:"));
            assertTrue(lowResult.getSkippedNodes().contains("high"));
        }
    }

    // ==================== Process Engine: Python + JS workflow ====================

    @Nested
    class ProcessEngineWorkflows {

        private ProcessEngineServiceImpl service;

        @TempDir
        Path tempDir;

        @BeforeEach
        void setUpService() {
            String originalHome = System.getProperty("user.home");
            System.setProperty("user.home", tempDir.toString());
            try {
                service = new ProcessEngineServiceImpl();
                service.init();
            } finally {
                System.setProperty("user.home", originalHome);
            }

            // Wire a dispatcher that routes to actual Python and JS executors
            service.setStepExecutionDispatcher(new StepExecutionDispatcher() {
                @Override
                public Map<String, Object> executeScript(String language, String scriptBody,
                                                          Map<String, Object> runData) {
                    NodeExecutionType type = "python".equalsIgnoreCase(language)
                            ? NodeExecutionType.PYTHON : NodeExecutionType.JAVASCRIPT;
                    NodeExecutor executor = type == NodeExecutionType.PYTHON
                            ? pythonExecutor : new ScriptingNodeExecutor();
                    ComputeNode node = ComputeNode.builder()
                            .id("step").name("Step")
                            .executionType(type).script(scriptBody).build();
                    ComputeGraph graph = ComputeGraph.builder().id("inline").build();
                    ExecutionContext ctx = new ExecutionContext("wf", graph, new InMemoryArtifactStore());
                    ExecutionResult result = executor.execute(node, runData, ctx);
                    if (result.getStatus() == ExecutionStatus.FAILED) {
                        throw new RuntimeException("Script failed: " + result.getError());
                    }
                    return result.getOutputs();
                }

                @Override
                public Map<String, Object> invokeTool(String t, Map<String, Object> a) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Map<String, Object> executeHttpCall(String m, String u,
                                                            Map<String, String> h, Object b) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Map<String, Object> convertExcel(String s, String t) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Map<String, Object> executeExcel(String s, Map<String, Object> c,
                                                         String t, String g) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<Map<String, Object>> listAvailableTools() {
                    return List.of();
                }
            });
        }

        private ProcessDefinition createAndApprove(ProcessDefinition def) {
            ProcessDefinition created = service.createProcess(def);
            return service.approveProcess(created.getId(), "test-admin");
        }

        @Test
        void testPythonThenJavaScript_workflow() {
            // Python step computes discount, JS step formats invoice
            ProcessStep pythonStep = ProcessStep.builder()
                    .id("1.1").name("Compute Discount")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("python")
                    .scriptBody("rate = 0.15 if quantity > 100 else 0.05\n_output = {'discount': price * rate, 'discountRate': rate}")
                    .build();

            ProcessStep jsStep = ProcessStep.builder()
                    .id("1.2").name("Format Invoice")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("({invoice: 'Price: $' + price + ', Discount: $' + discount + ' (' + (discountRate * 100) + '%), Final: $' + (price - discount)})")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Cross-Language Invoice")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Process").order(1)
                            .steps(List.of(pythonStep, jsStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("price", 1000, "quantity", 200));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            String invoice = (String) run.getRunData().get("invoice");
            assertNotNull(invoice, "Invoice should be in run data");
            assertTrue(invoice.contains("$150"), "Expected $150 discount in: " + invoice);
            assertTrue(invoice.contains("15%"), "Expected 15% rate in: " + invoice);
        }

        @Test
        void testJavaScriptThenPython_workflow() {
            // JS parses input, Python does math
            ProcessStep jsStep = ProcessStep.builder()
                    .id("1.1").name("Parse Input")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var s = String(expression); var parts = s.split('+'); var _output = {a: parseInt(parts[0].trim()), b: parseInt(parts[1].trim())};")
                    .build();

            ProcessStep pythonStep = ProcessStep.builder()
                    .id("1.2").name("Compute")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("python")
                    .scriptBody("result = a + b\n_output = {'result': result, 'equation': str(a) + ' + ' + str(b) + ' = ' + str(result)}")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Cross-Language Calculator")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Compute").order(1)
                            .steps(List.of(jsStep, pythonStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("expression", "123 + 456"));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals(579, ((Number) run.getRunData().get("result")).intValue());
            assertEquals("123 + 456 = 579", run.getRunData().get("equation"));
        }

        @Test
        void testThreeStepMixedLanguage_workflow() {
            // Python → JS → Python across two phases
            ProcessStep pyPrep = ProcessStep.builder()
                    .id("1.1").name("Prepare Data")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("python")
                    .scriptBody("_output = {'items': [x * 2 for x in numbers], 'source': 'python'}")
                    .build();

            ProcessStep jsTransform = ProcessStep.builder()
                    .id("2.1").name("Transform")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var sum = 0; for (var i = 0; i < items.length; i++) sum += items[i]; ({total: sum, source: source + '+js'})")
                    .build();

            ProcessStep pyFinalize = ProcessStep.builder()
                    .id("2.2").name("Finalize")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("python")
                    .scriptBody("_output = {'total': total, 'average': total / len(items), 'pipeline': source + '+python'}")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Multi-Phase Cross-Language")
                    .phases(List.of(
                            ProcessPhase.builder()
                                    .id("phase-1").name("Prepare").order(1)
                                    .steps(List.of(pyPrep))
                                    .build(),
                            ProcessPhase.builder()
                                    .id("phase-2").name("Process").order(2)
                                    .steps(List.of(jsTransform, pyFinalize))
                                    .build()
                    ))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("numbers", List.of(1, 2, 3, 4, 5)));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            // numbers doubled: [2,4,6,8,10], sum=30
            assertEquals(30, ((Number) run.getRunData().get("total")).intValue());
            assertEquals("python+js+python", run.getRunData().get("pipeline"));
        }
    }
}
