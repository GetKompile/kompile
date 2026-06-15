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

package ai.kompile.process.controller;

import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.controls.ControlGateType;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalAction;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for {@link ProcessEngineController}.
 *
 * The service layer is replaced by a Mockito mock via {@code @MockBean}.
 * Tests verify HTTP status codes, content type, and basic JSON shape.
 *
 * Note: The controller catches {@link NoSuchElementException} to return 404.
 * Tests that exercise the 404 path must configure the mock to throw that
 * exception type (not {@link IllegalArgumentException}, which the real service
 * implementation uses).
 */
@WebMvcTest(controllers = ProcessEngineController.class,
            properties = "kompile.process.engine.enabled=true")
class ProcessEngineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProcessEngineService processEngineService;

    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private static final String ONTOLOGY_ID = "ont-001";
    private static final String DEF_ID      = "def-001";
    private static final String RUN_ID      = "wf-2026-05-01-abc12345";
    private static final String APPROVAL_ID = "approval-001";
    private static final String CONTROL_ID  = "ctrl-001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // -------------------------------------------------------------------------
    // Ontology endpoints
    // -------------------------------------------------------------------------

    @Test
    void postOntology_returns201WithCreatedBody() throws Exception {
        OntologySchema input = OntologySchema.builder()
                .name("Test Ontology")
                .build();

        OntologySchema created = OntologySchema.builder()
                .id(ONTOLOGY_ID)
                .name("Test Ontology")
                .version(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(processEngineService.createOntology(any())).thenReturn(created);

        mockMvc.perform(post("/api/process/ontology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
               .andExpect(status().isCreated())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.id").value(ONTOLOGY_ID))
               .andExpect(jsonPath("$.version").value(1))
               .andExpect(jsonPath("$.name").value("Test Ontology"));
    }

    @Test
    void getOntology_returns200WhenFound() throws Exception {
        OntologySchema schema = OntologySchema.builder()
                .id(ONTOLOGY_ID)
                .name("Found Ontology")
                .version(1)
                .build();

        when(processEngineService.getOntology(eq(ONTOLOGY_ID), eq(1))).thenReturn(schema);

        mockMvc.perform(get("/api/process/ontology/{id}", ONTOLOGY_ID)
                        .param("version", "1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(ONTOLOGY_ID))
               .andExpect(jsonPath("$.name").value("Found Ontology"));
    }

    @Test
    void getOntology_returns404WhenNotFound() throws Exception {
        when(processEngineService.getOntology(eq("bad-id"), anyInt()))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/process/ontology/{id}", "bad-id"))
               .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Process definition endpoints
    // -------------------------------------------------------------------------

    @Test
    void postDefinition_returns201WithBody() throws Exception {
        ProcessDefinition input = ProcessDefinition.builder().name("My Process").build();
        ProcessDefinition created = ProcessDefinition.builder()
                .id(DEF_ID)
                .name("My Process")
                .version(1)
                .build();

        when(processEngineService.createProcess(any())).thenReturn(created);

        mockMvc.perform(post("/api/process/definition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").value(DEF_ID))
               .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getDefinition_returns200WhenFound() throws Exception {
        ProcessDefinition def = ProcessDefinition.builder()
                .id(DEF_ID)
                .name("Found Process")
                .version(1)
                .build();

        when(processEngineService.getProcess(eq(DEF_ID), eq(1))).thenReturn(def);

        mockMvc.perform(get("/api/process/definition/{id}", DEF_ID)
                        .param("version", "1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(DEF_ID));
    }

    @Test
    void getDefinition_returns404WhenNotFound() throws Exception {
        when(processEngineService.getProcess(eq("bad-id"), anyInt()))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/process/definition/{id}", "bad-id"))
               .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Workflow run endpoints
    // -------------------------------------------------------------------------

    @Test
    void postRun_returns201WithRunBody() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id(RUN_ID)
                .processDefinitionId(DEF_ID)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .build();

        when(processEngineService.startRun(eq(DEF_ID), any())).thenReturn(run);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("processDefinitionId", DEF_ID, "initialData", Map.of()));

        mockMvc.perform(post("/api/process/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").value(RUN_ID))
               .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getActiveRuns_returns200WithList() throws Exception {
        WorkflowRun run1 = WorkflowRun.builder()
                .id(RUN_ID)
                .status(RunStatus.RUNNING)
                .build();

        when(processEngineService.listActiveRuns()).thenReturn(List.of(run1));

        mockMvc.perform(get("/api/process/run/active"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$.length()").value(1))
               .andExpect(jsonPath("$[0].id").value(RUN_ID));
    }

    @Test
    void getActiveRuns_returns200WithEmptyListWhenNoActiveRuns() throws Exception {
        when(processEngineService.listActiveRuns()).thenReturn(List.of());

        mockMvc.perform(get("/api/process/run/active"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // Approval endpoints
    // -------------------------------------------------------------------------

    @Test
    void postApprovalRespond_returns200WithUpdatedRun() throws Exception {
        WorkflowRun completed = WorkflowRun.builder()
                .id(RUN_ID)
                .status(RunStatus.COMPLETED)
                .build();

        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(APPROVAL_ID)
                .respondedBy("tester@example.com")
                .action(ApprovalAction.APPROVE)
                .build();

        when(processEngineService.submitApproval(any())).thenReturn(completed);

        mockMvc.perform(post("/api/process/approval/{id}/respond", APPROVAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(response)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(RUN_ID))
               .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void postApprovalRespond_reject_returns200WithFailedRun() throws Exception {
        WorkflowRun failed = WorkflowRun.builder()
                .id(RUN_ID)
                .status(RunStatus.FAILED)
                .build();

        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(APPROVAL_ID)
                .respondedBy("tester@example.com")
                .action(ApprovalAction.REJECT)
                .build();

        when(processEngineService.submitApproval(any())).thenReturn(failed);

        mockMvc.perform(post("/api/process/approval/{id}/respond", APPROVAL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(response)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("FAILED"));
    }

    // -------------------------------------------------------------------------
    // Control endpoints
    // -------------------------------------------------------------------------

    @Test
    void postControlEvaluate_returns200WithAttestation() throws Exception {
        ControlAttestation attestation = ControlAttestation.builder()
                .id("att-001")
                .controlId(CONTROL_ID)
                .workflowRunId(RUN_ID)
                .passed(true)
                .evaluatedAt(Instant.now())
                .evaluatedBy("system")
                .build();

        when(processEngineService.evaluateControl(eq(CONTROL_ID), eq(RUN_ID), any()))
                .thenReturn(attestation);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("runId", RUN_ID, "data", Map.of("value", 42)));

        mockMvc.perform(post("/api/process/control/{id}/evaluate", CONTROL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value("att-001"))
               .andExpect(jsonPath("$.passed").value(true))
               .andExpect(jsonPath("$.controlId").value(CONTROL_ID));
    }

    // -------------------------------------------------------------------------
    // List endpoints
    // -------------------------------------------------------------------------

    @Test
    void getOntologyList_returns200WithArray() throws Exception {
        OntologySchema s1 = OntologySchema.builder().id("ont-1").name("A").version(1).build();
        OntologySchema s2 = OntologySchema.builder().id("ont-2").name("B").version(2).build();
        when(processEngineService.listOntologies()).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/process/ontology"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$.length()").value(2))
               .andExpect(jsonPath("$[0].name").value("A"));
    }

    @Test
    void getDefinitionList_returns200WithArray() throws Exception {
        ProcessDefinition d1 = ProcessDefinition.builder()
                .id("def-1").name("Proc A").version(1).status(ProcessStatus.DRAFT).build();
        when(processEngineService.listProcessDefinitions()).thenReturn(List.of(d1));

        mockMvc.perform(get("/api/process/definition"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$[0].name").value("Proc A"));
    }

    @Test
    void getAllRuns_returns200WithArray() throws Exception {
        WorkflowRun r1 = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
        WorkflowRun r2 = WorkflowRun.builder().id("run-2").status(RunStatus.RUNNING).build();
        when(processEngineService.listAllRuns()).thenReturn(List.of(r1, r2));

        mockMvc.perform(get("/api/process/run/all"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(2));
    }

    // -------------------------------------------------------------------------
    // Cancel run endpoint
    // -------------------------------------------------------------------------

    @Test
    void postCancelRun_returns200WithCancelledRun() throws Exception {
        WorkflowRun cancelled = WorkflowRun.builder()
                .id(RUN_ID).status(RunStatus.CANCELLED).build();
        when(processEngineService.cancelRun(eq(RUN_ID))).thenReturn(cancelled);

        mockMvc.perform(post("/api/process/run/{id}/cancel", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void postCancelRun_returns404WhenNotFound() throws Exception {
        when(processEngineService.cancelRun(eq("bad-id")))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(post("/api/process/run/{id}/cancel", "bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
               .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Complete human step endpoint
    // -------------------------------------------------------------------------

    @Test
    void postCompleteStep_returns200WithUpdatedRun() throws Exception {
        WorkflowRun updated = WorkflowRun.builder()
                .id(RUN_ID).status(RunStatus.COMPLETED).build();
        when(processEngineService.completeHumanStep(eq(RUN_ID), eq("step-1"), eq("user"), any()))
                .thenReturn(updated);

        String body = objectMapper.writeValueAsString(
                Map.of("stepId", "step-1", "completedBy", "user", "outputs", Map.of()));

        mockMvc.perform(post("/api/process/run/{id}/complete-step", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // -------------------------------------------------------------------------
    // Control CRUD endpoints
    // -------------------------------------------------------------------------

    @Test
    void postControl_returns201WithCreatedControl() throws Exception {
        ControlDefinition input = ControlDefinition.builder()
                .name("Test Ctrl").expression("true").gateType(ControlGateType.SOFT).build();
        ControlDefinition created = ControlDefinition.builder()
                .id("ctrl-new").name("Test Ctrl").expression("true").gateType(ControlGateType.SOFT).build();
        when(processEngineService.createControl(any())).thenReturn(created);

        mockMvc.perform(post("/api/process/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").value("ctrl-new"))
               .andExpect(jsonPath("$.name").value("Test Ctrl"));
    }

    @Test
    void getControlList_returns200WithArray() throws Exception {
        ControlDefinition c1 = ControlDefinition.builder()
                .id("c1").name("Ctrl A").gateType(ControlGateType.HARD).build();
        when(processEngineService.listControls()).thenReturn(List.of(c1));

        mockMvc.perform(get("/api/process/control"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$[0].name").value("Ctrl A"));
    }

    @Test
    void getControlById_returns200WhenFound() throws Exception {
        ControlDefinition ctrl = ControlDefinition.builder()
                .id(CONTROL_ID).name("Found Ctrl").gateType(ControlGateType.SOFT).build();
        when(processEngineService.getControl(eq(CONTROL_ID))).thenReturn(ctrl);

        mockMvc.perform(get("/api/process/control/{id}", CONTROL_ID))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("Found Ctrl"));
    }

    @Test
    void getControlById_returns404WhenNotFound() throws Exception {
        when(processEngineService.getControl(eq("bad-ctrl")))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/process/control/{id}", "bad-ctrl"))
               .andExpect(status().isNotFound());
    }

    @Test
    void postControlEvaluate_returns404WhenControlNotFound() throws Exception {
        when(processEngineService.evaluateControl(eq("unknown-ctrl"), anyString(), any()))
                .thenThrow(new NoSuchElementException("control not found"));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("runId", RUN_ID, "data", Map.of()));

        mockMvc.perform(post("/api/process/control/{id}/evaluate", "unknown-ctrl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
               .andExpect(status().isNotFound());
    }
}
