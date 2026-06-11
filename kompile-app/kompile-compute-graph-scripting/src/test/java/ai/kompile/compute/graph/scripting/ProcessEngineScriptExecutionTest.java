package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.scripting.ExpressionNodeExecutor;
import ai.kompile.compute.graph.scripting.ScriptingNodeExecutor;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineServiceImpl;
import ai.kompile.process.service.StepExecutionDispatcher;
import ai.kompile.process.workflow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real execution tests for the process engine with SCRIPT and AUTO steps.
 * Wires a real StepExecutionDispatcher backed by actual ScriptingNodeExecutor (GraalVM JS)
 * into ProcessEngineServiceImpl. Verifies full lifecycle: create → approve → run → complete.
 */
class ProcessEngineScriptExecutionTest {

    private ProcessEngineServiceImpl service;
    private ScriptingNodeExecutor jsExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            service = new ProcessEngineServiceImpl();
            service.init();
        } finally {
            System.setProperty("user.home", originalHome);
        }

        jsExecutor = new ScriptingNodeExecutor();

        // Wire a real dispatcher that executes JavaScript via GraalVM
        service.setStepExecutionDispatcher(new StepExecutionDispatcher() {
            @Override
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                throw new UnsupportedOperationException("No tools in this test");
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException("No HTTP calls in this test");
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                ComputeNode node = ComputeNode.builder()
                        .id("script-step")
                        .name("Script Step")
                        .executionType(NodeExecutionType.JAVASCRIPT)
                        .script(scriptBody)
                        .build();
                ComputeGraph graph = ComputeGraph.builder().id("inline").build();
                ExecutionContext ctx = new ExecutionContext("test", graph, new InMemoryArtifactStore());
                ExecutionResult result = jsExecutor.execute(node, runData, ctx);
                if (result.getStatus() == ExecutionStatus.FAILED) {
                    throw new RuntimeException("Script failed: " + result.getError());
                }
                return result.getOutputs();
            }

            @Override
            public Map<String, Object> convertExcel(String spreadsheetGraphJson, String targetLanguage) {
                throw new UnsupportedOperationException("No Excel in this test");
            }

            @Override
            public Map<String, Object> executeExcel(String spreadsheetGraphJson,
                                                     Map<String, Object> cellOverrides,
                                                     String targetLanguage, String generatedCode) {
                throw new UnsupportedOperationException("No Excel in this test");
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                return List.of();
            }
        });
    }

    // ==================== Helpers ====================

    private ProcessDefinition createAndApprove(ProcessDefinition def) {
        ProcessDefinition created = service.createProcess(def);
        return service.approveProcess(created.getId(), "test-admin");
    }

    // ==================== SCRIPT Step Tests ====================

    @Nested
    class ScriptSteps {

        @Test
        void testSingleScriptStep_executesJavaScript() {
            ProcessStep scriptStep = ProcessStep.builder()
                    .id("1.1")
                    .name("Compute Tax")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var _output = {tax: amount * 0.15, netAmount: amount * 0.85};")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Tax Calculation")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Calculate").order(1)
                            .steps(List.of(scriptStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(), Map.of("amount", 1000));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertNotNull(run.getRunData().get("tax"));
            assertEquals(150.0, ((Number) run.getRunData().get("tax")).doubleValue(), 0.01);
            assertEquals(850.0, ((Number) run.getRunData().get("netAmount")).doubleValue(), 0.01);

            // Verify step execution recorded correctly
            StepExecution stepExec = run.getStepExecutions().stream()
                    .filter(se -> se.getStepId().equals("1.1"))
                    .findFirst().orElseThrow();
            assertEquals(StepExecutionStatus.COMPLETED, stepExec.getStatus());
            assertEquals("script:javascript", stepExec.getExecutedBy());
        }

        @Test
        void testScriptStep_withOutputKey() {
            ProcessStep scriptStep = ProcessStep.builder()
                    .id("1.1")
                    .name("Compute Ratio")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("a / b")
                    .scriptOutputKey("ratio")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Ratio Calculator")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Compute").order(1)
                            .steps(List.of(scriptStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(), Map.of("a", 100, "b", 4));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals(25, ((Number) run.getRunData().get("ratio")).intValue());
        }

        @Test
        void testScriptStep_failingScript_failsRun() {
            ProcessStep badStep = ProcessStep.builder()
                    .id("1.1")
                    .name("Bad Script")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("undefinedVar.crash()")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Failing Workflow")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Fail").order(1)
                            .steps(List.of(badStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(), Map.of());

            assertEquals(RunStatus.FAILED, run.getStatus());
            StepExecution stepExec = run.getStepExecutions().get(0);
            assertEquals(StepExecutionStatus.FAILED, stepExec.getStatus());
            assertNotNull(stepExec.getError());
        }
    }

    // ==================== Mixed AUTO + SCRIPT Workflows ====================

    @Nested
    class MixedWorkflows {

        @Test
        void testAutoThenScript_dataFlowsBetweenSteps() {
            // AUTO step computes initial values via SpEL, SCRIPT step processes them
            ProcessStep autoStep = ProcessStep.builder()
                    .id("1.1")
                    .name("Initialize")
                    .stepType(StepType.AUTO)
                    .executionExpressions(Map.of(
                            "basePrice", "#quantity * #unitPrice",
                            "isWholesale", "#quantity > 100"
                    ))
                    .build();

            ProcessStep scriptStep = ProcessStep.builder()
                    .id("1.2")
                    .name("Apply Discount")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var discount = isWholesale ? 0.2 : 0.05; " +
                            "var _output = {discountRate: discount, " +
                            "finalPrice: basePrice * (1 - discount), " +
                            "savings: basePrice * discount};")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Pricing Workflow")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Price").order(1)
                            .steps(List.of(autoStep, scriptStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("quantity", 200, "unitPrice", 50));

            assertEquals(RunStatus.COMPLETED, run.getStatus());

            Map<String, Object> data = run.getRunData();
            // AUTO: basePrice = 200*50 = 10000, isWholesale = true
            assertEquals(10000, ((Number) data.get("basePrice")).intValue());
            assertTrue((Boolean) data.get("isWholesale"));

            // SCRIPT: discount=0.2, finalPrice=10000*0.8=8000, savings=10000*0.2=2000
            assertEquals(0.2, ((Number) data.get("discountRate")).doubleValue(), 0.01);
            assertEquals(8000.0, ((Number) data.get("finalPrice")).doubleValue(), 0.01);
            assertEquals(2000.0, ((Number) data.get("savings")).doubleValue(), 0.01);
        }

        @Test
        void testMultiPhase_autoAndScript() {
            // Phase 1: Data collection (AUTO), Phase 2: Processing (SCRIPT)
            ProcessStep collect = ProcessStep.builder()
                    .id("1.1").name("Collect").stepType(StepType.AUTO)
                    .executionExpressions(Map.of("total", "#items.size()"))
                    .build();

            ProcessStep process = ProcessStep.builder()
                    .id("2.1").name("Process").stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("({processed: true, itemCount: total, summary: 'Processed ' + total + ' items'})")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Multi-Phase")
                    .phases(List.of(
                            ProcessPhase.builder()
                                    .id("phase-1").name("Collection").order(1)
                                    .steps(List.of(collect))
                                    .build(),
                            ProcessPhase.builder()
                                    .id("phase-2").name("Processing").order(2)
                                    .steps(List.of(process))
                                    .build()
                    ))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("items", List.of("a", "b", "c")));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            Map<String, Object> data = run.getRunData();
            assertEquals(3, ((Number) data.get("total")).intValue());
            assertEquals(true, data.get("processed"));
            assertEquals("Processed 3 items", data.get("summary"));
        }
    }

    // ==================== HUMAN Step + SCRIPT Workflow ====================

    @Nested
    class HumanAndScriptSteps {

        @Test
        void testHumanStep_pausesThenResumesWithScript() {
            ProcessStep autoStep = ProcessStep.builder()
                    .id("1.1").name("Prepare").stepType(StepType.AUTO)
                    .executionExpressions(Map.of("amount", "#requestedAmount"))
                    .build();

            ProcessStep humanStep = ProcessStep.builder()
                    .id("1.2").name("Manager Review").stepType(StepType.HUMAN)
                    .build();

            ProcessStep scriptStep = ProcessStep.builder()
                    .id("1.3").name("Finalize").stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("({status: approved ? 'APPROVED' : 'DENIED', " +
                            "processedAmount: approved ? amount : 0})")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Approval Workflow")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Review").order(1)
                            .steps(List.of(autoStep, humanStep, scriptStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);

            // Start run — should pause at the HUMAN step
            WorkflowRun run = service.startRun(approved.getId(),
                    Map.of("requestedAmount", 5000));
            assertEquals(RunStatus.PAUSED_FOR_HUMAN, run.getStatus());

            // Complete the human step with approval data
            WorkflowRun resumed = service.completeHumanStep(
                    run.getId(), "1.2", "manager@test.com",
                    Map.of("approved", true, "reviewNotes", "Looks good"));

            assertEquals(RunStatus.COMPLETED, resumed.getStatus());
            Map<String, Object> data = resumed.getRunData();
            assertEquals("APPROVED", data.get("status"));
            assertEquals(5000, ((Number) data.get("processedAmount")).intValue());
        }
    }

    // ==================== Multiple Script Steps ====================

    @Nested
    class MultipleScriptSteps {

        @Test
        void testChainedScriptSteps_outputFlowsForward() {
            ProcessStep step1 = ProcessStep.builder()
                    .id("1.1").name("Step 1: Parse").stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var parts = input.split('-'); " +
                            "var _output = {year: parseInt(parts[0]), month: parseInt(parts[1]), day: parseInt(parts[2])};")
                    .build();

            ProcessStep step2 = ProcessStep.builder()
                    .id("1.2").name("Step 2: Validate").stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("var valid = year >= 2000 && month >= 1 && month <= 12 && day >= 1 && day <= 31; " +
                            "var _output = {dateValid: valid, formatted: year + '/' + month + '/' + day};")
                    .build();

            ProcessStep step3 = ProcessStep.builder()
                    .id("1.3").name("Step 3: Transform").stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("({result: dateValid ? 'Date ' + formatted + ' is valid' : 'Invalid date'})")
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Date Pipeline")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Process").order(1)
                            .steps(List.of(step1, step2, step3))
                            .build()))
                    .build();

            ProcessDefinition approvedDef = createAndApprove(def);
            WorkflowRun run = service.startRun(approvedDef.getId(),
                    Map.of("input", "2026-06-01"));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals(3, run.getStepExecutions().stream()
                    .filter(se -> se.getStatus() == StepExecutionStatus.COMPLETED)
                    .count());
            assertEquals("Date 2026/6/1 is valid", run.getRunData().get("result"));
        }
    }

    // ==================== Run Lifecycle ====================

    @Nested
    class RunLifecycle {

        @Test
        void testCancelRun() {
            ProcessStep humanStep = ProcessStep.builder()
                    .id("1.1").name("Wait").stepType(StepType.HUMAN)
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Cancellable")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Phase").order(1)
                            .steps(List.of(humanStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            WorkflowRun run = service.startRun(approved.getId(), Map.of());

            assertEquals(RunStatus.PAUSED_FOR_HUMAN, run.getStatus());
            WorkflowRun cancelled = service.cancelRun(run.getId());
            assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
        }

        @Test
        void testListActiveRuns() {
            ProcessStep humanStep = ProcessStep.builder()
                    .id("1.1").name("Wait").stepType(StepType.HUMAN)
                    .build();

            ProcessDefinition def = ProcessDefinition.builder()
                    .name("Active Test")
                    .phases(List.of(ProcessPhase.builder()
                            .id("phase-1").name("Phase").order(1)
                            .steps(List.of(humanStep))
                            .build()))
                    .build();

            ProcessDefinition approved = createAndApprove(def);
            service.startRun(approved.getId(), Map.of());
            service.startRun(approved.getId(), Map.of());

            List<WorkflowRun> active = service.listActiveRuns();
            assertTrue(active.size() >= 2);
        }
    }
}
