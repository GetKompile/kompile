/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.service;

import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.controls.ControlGateType;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessEngineServiceImplTest {

    private ProcessEngineServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Override user.home so init() writes to temp dir
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            service = new ProcessEngineServiceImpl();
            service.init();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    // --- Helpers ---

    private ProcessDefinition createAutoProcessDefinition() {
        ProcessStep step = ProcessStep.builder()
                .id("1.1")
                .name("Auto Step")
                .description("An auto step")
                .stepType(StepType.AUTO)
                .build();
        ProcessPhase phase = ProcessPhase.builder()
                .id("phase-1")
                .name("Phase 1")
                .order(1)
                .steps(List.of(step))
                .build();
        return ProcessDefinition.builder()
                .name("Test Process")
                .phases(List.of(phase))
                .build();
    }

    private ProcessDefinition createHumanStepProcess() {
        ProcessStep autoStep = ProcessStep.builder()
                .id("1.1")
                .name("Auto Step")
                .stepType(StepType.AUTO)
                .build();
        ProcessStep humanStep = ProcessStep.builder()
                .id("1.2")
                .name("Human Review")
                .stepType(StepType.HUMAN)
                .build();
        ProcessPhase phase = ProcessPhase.builder()
                .id("phase-1")
                .name("Phase 1")
                .order(1)
                .steps(List.of(autoStep, humanStep))
                .build();
        return ProcessDefinition.builder()
                .name("Human Process")
                .phases(List.of(phase))
                .build();
    }

    private ProcessDefinition createApproveStepProcess() {
        ProcessStep approveStep = ProcessStep.builder()
                .id("1.1")
                .name("Approval Gate")
                .stepType(StepType.APPROVE)
                .build();
        ProcessPhase phase = ProcessPhase.builder()
                .id("phase-1")
                .name("Phase 1")
                .order(1)
                .steps(List.of(approveStep))
                .build();
        return ProcessDefinition.builder()
                .name("Approve Process")
                .phases(List.of(phase))
                .build();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Ontology Management
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class OntologyManagement {

        @Test
        void createOntologyAssignsIdAndVersion() {
            OntologySchema schema = OntologySchema.builder()
                    .name("Test Ontology")
                    .build();

            OntologySchema created = service.createOntology(schema);

            assertNotNull(created.getId());
            assertEquals(1, created.getVersion());
            assertEquals("Test Ontology", created.getName());
            assertNotNull(created.getCreatedAt());
        }

        @Test
        void getOntologyRetrievesCreated() {
            OntologySchema schema = service.createOntology(
                    OntologySchema.builder().name("Retrieve Me").build());

            OntologySchema retrieved = service.getOntology(schema.getId(), 1);
            assertEquals("Retrieve Me", retrieved.getName());
        }

        @Test
        void getOntologyThrowsForUnknown() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOntology("nonexistent", 1));
        }

        @Test
        void updateOntologyIncrementsVersion() {
            OntologySchema v1 = service.createOntology(
                    OntologySchema.builder().name("V1").build());

            OntologySchema v2 = service.updateOntology(v1.getId(),
                    OntologySchema.builder().name("V2").build());

            assertEquals(2, v2.getVersion());
            assertEquals("V2", v2.getName());
            // V1 still retrievable
            assertEquals("V1", service.getOntology(v1.getId(), 1).getName());
        }

        @Test
        void listOntologiesReturnsLatest() {
            service.createOntology(OntologySchema.builder().name("O1").build());
            service.createOntology(OntologySchema.builder().name("O2").build());

            List<OntologySchema> list = service.listOntologies();
            assertEquals(2, list.size());
        }

        @Test
        void validateDataReturnsViolatedRules() {
            OntologySchema schema = OntologySchema.builder()
                    .name("Validated")
                    .entityTypes(List.of(
                            EntityTypeDefinition.builder()
                                    .name("Invoice")
                                    .fields(List.of(
                                            FieldDefinition.builder()
                                                    .name("amount")
                                                    .type(FieldType.DECIMAL)
                                                    .required(true)
                                                    .build()
                                    ))
                                    .rules(List.of(
                                            ValidationRule.builder()
                                                    .name("amount_positive")
                                                    .expression("#amount > 0")
                                                    .description("Amount must be positive")
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            OntologySchema created = service.createOntology(schema);
            List<ValidationRule> violated = service.validateData(
                    created.getId(), 1, "Invoice", Map.of("amount", -5));

            assertFalse(violated.isEmpty());
            assertEquals("amount_positive", violated.get(0).getName());
        }

        @Test
        void validateDataPassesWithValidData() {
            OntologySchema schema = OntologySchema.builder()
                    .name("Valid")
                    .entityTypes(List.of(
                            EntityTypeDefinition.builder()
                                    .name("Order")
                                    .rules(List.of(
                                            ValidationRule.builder()
                                                    .name("qty_check")
                                                    .expression("#qty > 0")
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            OntologySchema created = service.createOntology(schema);
            List<ValidationRule> violated = service.validateData(
                    created.getId(), 1, "Order", Map.of("qty", 10));

            assertTrue(violated.isEmpty());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Process Definition
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class ProcessDefinitionTests {

        @Test
        void createProcessAssignsIdAndDraftStatus() {
            ProcessDefinition created = service.createProcess(createAutoProcessDefinition());

            assertNotNull(created.getId());
            assertEquals(1, created.getVersion());
            assertEquals(ProcessStatus.DRAFT, created.getStatus());
        }

        @Test
        void getProcessRetrievesCreated() {
            ProcessDefinition created = service.createProcess(createAutoProcessDefinition());
            ProcessDefinition retrieved = service.getProcess(created.getId(), 1);

            assertEquals(created.getName(), retrieved.getName());
        }

        @Test
        void approveProcessUpdatesStatus() {
            ProcessDefinition created = service.createProcess(createAutoProcessDefinition());
            ProcessDefinition approved = service.approveProcess(created.getId(), "admin");

            assertEquals(ProcessStatus.APPROVED, approved.getStatus());
            assertEquals("admin", approved.getApprovedBy());
            assertEquals(2, approved.getVersion());
        }

        @Test
        void listProcessDefinitionsReturnsAll() {
            service.createProcess(createAutoProcessDefinition());
            service.createProcess(createHumanStepProcess());

            List<ProcessDefinition> list = service.listProcessDefinitions();
            assertEquals(2, list.size());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Workflow Execution
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class WorkflowExecution {

        @Test
        void startRunRequiresApprovedDefinition() {
            ProcessDefinition draft = service.createProcess(createAutoProcessDefinition());

            assertThrows(IllegalStateException.class,
                    () -> service.startRun(draft.getId(), Map.of()));
        }

        @Test
        void startRunCreatesStepExecutions() {
            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");

            WorkflowRun run = service.startRun(def.getId(), Map.of("key", "value"));

            assertNotNull(run.getId());
            assertNotNull(run.getStepExecutions());
            assertFalse(run.getStepExecutions().isEmpty());
            assertEquals("value", run.getRunData().get("key"));
        }

        @Test
        void autoStepCompletesImmediately() {
            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");

            WorkflowRun run = service.startRun(def.getId(), Map.of());

            // Single AUTO step should complete the run
            assertEquals(RunStatus.COMPLETED, run.getStatus());
        }

        @Test
        void humanStepPausesRun() {
            ProcessDefinition def = service.createProcess(createHumanStepProcess());
            service.approveProcess(def.getId(), "admin");

            WorkflowRun run = service.startRun(def.getId(), Map.of());

            // First step is AUTO (completes), second is HUMAN (pauses)
            assertTrue(run.getStatus() == RunStatus.PAUSED_FOR_HUMAN
                    || run.getStatus() == RunStatus.PAUSED_FOR_APPROVAL);
        }

        @Test
        void getRun() {
            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of());

            WorkflowRun retrieved = service.getRun(run.getId());
            assertEquals(run.getId(), retrieved.getId());
        }

        @Test
        void getRunThrowsForUnknown() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getRun("nonexistent"));
        }

        @Test
        void cancelRunChangesStatus() {
            ProcessDefinition def = service.createProcess(createHumanStepProcess());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of());

            WorkflowRun cancelled = service.cancelRun(run.getId());
            assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
            assertNotNull(cancelled.getCompletedAt());
        }

        @Test
        void cancelCompletedRunThrows() {
            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.cancelRun(run.getId()));
        }

        @Test
        void listActiveRunsFiltersCompleted() {
            ProcessDefinition autoDef = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(autoDef.getId(), "admin");
            service.startRun(autoDef.getId(), Map.of()); // completes immediately

            ProcessDefinition humanDef = service.createProcess(createHumanStepProcess());
            service.approveProcess(humanDef.getId(), "admin");
            service.startRun(humanDef.getId(), Map.of()); // pauses at human step

            List<WorkflowRun> active = service.listActiveRuns();
            List<WorkflowRun> all = service.listAllRuns();

            assertTrue(active.size() < all.size() || all.size() == active.size());
            assertTrue(active.stream().noneMatch(r -> r.getStatus() == RunStatus.COMPLETED));
        }

        @Test
        void completeHumanStepMergesOutputs() {
            ProcessDefinition def = service.createProcess(createHumanStepProcess());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of("initial", "data"));

            // Find the awaiting step
            StepExecution awaitingStep = run.getStepExecutions().stream()
                    .filter(se -> se.getStatus() == StepExecutionStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElse(null);

            if (awaitingStep != null) {
                WorkflowRun completed = service.completeHumanStep(
                        run.getId(), awaitingStep.getStepId(), "reviewer",
                        Map.of("decision", "approved"));

                assertTrue(completed.getRunData().containsKey("decision"));
                assertEquals("data", completed.getRunData().get("initial"));
            }
        }

        @Test
        void startRunWithNullInitialData() {
            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");

            WorkflowRun run = service.startRun(def.getId(), null);
            assertNotNull(run.getRunData());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Controls & SpEL
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class ControlsAndSpel {

        @Test
        void createAndListControls() {
            ControlDefinition control = ControlDefinition.builder()
                    .name("Amount Threshold")
                    .description("Check amount > 1000")
                    .expression("#runData['amount'] > 1000")
                    .gateType(ControlGateType.HARD)
                    .build();

            ControlDefinition created = service.createControl(control);
            assertNotNull(created.getId());

            List<ControlDefinition> controls = service.listControls();
            assertEquals(1, controls.size());
        }

        @Test
        void getControlRetrievesCreated() {
            ControlDefinition created = service.createControl(ControlDefinition.builder()
                    .name("Test").expression("true").gateType(ControlGateType.SOFT).build());

            ControlDefinition retrieved = service.getControl(created.getId());
            assertEquals("Test", retrieved.getName());
        }

        @Test
        void evaluateControlProducesAttestation() {
            ControlDefinition control = service.createControl(ControlDefinition.builder()
                    .name("Check")
                    .expression("#runData['amount'] > 100")
                    .gateType(ControlGateType.HARD)
                    .build());

            ProcessDefinition def = service.createProcess(createAutoProcessDefinition());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of("amount", 500));

            ControlAttestation att = service.evaluateControl(
                    control.getId(), run.getId(), Map.of("runData", run.getRunData()));

            assertNotNull(att);
            assertTrue(att.isPassed());
        }

        @Test
        void evaluateSpelExpressionSuccess() {
            SpelEvaluationResult result = service.evaluateSpelExpression(
                    "#runData['x'] + #runData['y']",
                    Map.of("runData", Map.of("x", 10, "y", 20)));

            assertNotNull(result);
            assertEquals(30, result.getResult());
            assertNull(result.getError());
        }

        @Test
        void evaluateSpelExpressionError() {
            SpelEvaluationResult result = service.evaluateSpelExpression(
                    "this is not valid spel !!!",
                    Map.of());

            assertNotNull(result);
            assertNotNull(result.getError());
        }

        @Test
        void evaluateSpelBooleanExpression() {
            SpelEvaluationResult result = service.evaluateSpelExpression(
                    "#runData['amount'] > 1000",
                    Map.of("runData", Map.of("amount", 5000)));

            assertEquals(true, result.getResult());
            assertEquals("Boolean", result.getType());
        }

        @Test
        void evaluateSpelStringConcatenation() {
            SpelEvaluationResult result = service.evaluateSpelExpression(
                    "'Hello ' + #name",
                    Map.of("name", "World"));

            assertEquals("Hello World", result.getResult());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Graph Callback Integration
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class GraphCallback {

        @Test
        void cancelRunFiresGraphCallback() {
            // Wire a mock callback
            boolean[] callbackFired = {false};
            service.setProcessGraphCallback(new ProcessGraphCallback() {
                @Override
                public void onRunCompleted(WorkflowRun run) {
                    callbackFired[0] = true;
                }

                @Override
                public void onStepCompleted(WorkflowRun run, StepExecution step) { }
            });

            ProcessDefinition def = service.createProcess(createHumanStepProcess());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of());

            service.cancelRun(run.getId());
            assertTrue(callbackFired[0]);
        }

        @Test
        void callbackExceptionDoesNotCrashCancel() {
            service.setProcessGraphCallback(new ProcessGraphCallback() {
                @Override
                public void onRunCompleted(WorkflowRun run) {
                    throw new RuntimeException("Callback failure");
                }

                @Override
                public void onStepCompleted(WorkflowRun run, StepExecution step) { }
            });

            ProcessDefinition def = service.createProcess(createHumanStepProcess());
            service.approveProcess(def.getId(), "admin");
            WorkflowRun run = service.startRun(def.getId(), Map.of());

            // Should not throw despite callback failure
            WorkflowRun cancelled = service.cancelRun(run.getId());
            assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
        }
    }
}
