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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.SetupStatusService;
import ai.kompile.app.services.StagingServerLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SetupStatusController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SetupStatusControllerTest {

    @Mock
    private SetupStatusService setupStatusService;

    @Mock
    private StagingServerLifecycleService stagingServerLifecycleService;

    private SetupStatusController controller;

    @BeforeEach
    void setUp() {
        controller = new SetupStatusController(setupStatusService, stagingServerLifecycleService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/setup/status
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetSetupStatus() {
        SetupStatusService.SetupStatus mockStatus = SetupStatusService.SetupStatus.builder()
                .setupComplete(false)
                .wizardDismissed(false)
                .currentStep(1)
                .totalSteps(5)
                .stagingServer(SetupStatusService.StepStatus.builder()
                        .stepNumber(1).name("Staging Server").status(SetupStatusService.StepState.NOT_STARTED).complete(false).build())
                .modelSource(SetupStatusService.StepStatus.builder()
                        .stepNumber(2).name("Model Source").status(SetupStatusService.StepState.NOT_STARTED).complete(false).build())
                .embeddingModel(SetupStatusService.StepStatus.builder()
                        .stepNumber(3).name("Embedding Model").status(SetupStatusService.StepState.NOT_STARTED).complete(false).build())
                .indexing(SetupStatusService.StepStatus.builder()
                        .stepNumber(4).name("Document Index").status(SetupStatusService.StepState.NOT_STARTED).complete(false).build())
                .searchReady(SetupStatusService.StepStatus.builder()
                        .stepNumber(5).name("Search Ready").status(SetupStatusService.StepState.NOT_STARTED).complete(false).build())
                .build();

        when(setupStatusService.getStatus()).thenReturn(mockStatus);

        ResponseEntity<SetupStatusService.SetupStatus> response = controller.getSetupStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().getTotalSteps());
        assertFalse(response.getBody().isSetupComplete());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/setup/dismiss
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDismissWizard() {
        ResponseEntity<Map<String, Object>> response = controller.dismissWizard();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("dismissed"));
        verify(setupStatusService).dismissWizard();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/setup/reset
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testResetWizard() {
        ResponseEntity<Map<String, Object>> response = controller.resetWizard();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("dismissed"));
        verify(setupStatusService).resetWizardDismissed();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/setup/staging-server/status
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetStagingServerStatus() {
        StagingServerLifecycleService.StagingServerStatus serverStatus =
                StagingServerLifecycleService.StagingServerStatus.builder()
                        .componentId("kompile-model-staging")
                        .status("running")
                        .installed(true)
                        .port(8081)
                        .pid(12345L)
                        .url("http://localhost:8081")
                        .build();
        when(stagingServerLifecycleService.getStatus()).thenReturn(serverStatus);

        ResponseEntity<?> response = controller.getStagingServerStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetStagingServerStatus_serviceNull() {
        SetupStatusController controllerNoStaging = new SetupStatusController(setupStatusService, null);
        ResponseEntity<?> response = controllerNoStaging.getStagingServerStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("unavailable"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/setup/staging-server/start
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testStartStagingServer() {
        StagingServerLifecycleService.StartResult startResult =
                StagingServerLifecycleService.StartResult.builder()
                        .success(true)
                        .message("Started on port 8081")
                        .port(8081)
                        .pid(12345L)
                        .build();
        when(stagingServerLifecycleService.startServer(8081)).thenReturn(startResult);

        ResponseEntity<?> response = controller.startStagingServer(8081);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testStartStagingServer_serviceNull() {
        SetupStatusController controllerNoStaging = new SetupStatusController(setupStatusService, null);
        ResponseEntity<?> response = controllerNoStaging.startStagingServer(8081);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/setup/staging-server/stop
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testStopStagingServer() {
        StagingServerLifecycleService.StopResult stopResult =
                StagingServerLifecycleService.StopResult.builder()
                        .success(true)
                        .message("Stopped")
                        .pid(12345L)
                        .build();
        when(stagingServerLifecycleService.stopServer()).thenReturn(stopResult);

        ResponseEntity<?> response = controller.stopStagingServer();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testStopStagingServer_serviceNull() {
        SetupStatusController controllerNoStaging = new SetupStatusController(setupStatusService, null);
        ResponseEntity<?> response = controllerNoStaging.stopStagingServer();
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/setup/staging-server/stage-model/{modelId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testStageModel_success() throws Exception {
        when(stagingServerLifecycleService.stageModelFromCatalog(8081, "bge-base-en-v1.5"))
                .thenReturn("{\"status\":\"staging\"}");

        ResponseEntity<?> response = controller.stageModel("bge-base-en-v1.5", 8081);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertTrue((Boolean) body.get("success"));
    }

    @Test
    void testStageModel_failure() throws Exception {
        when(stagingServerLifecycleService.stageModelFromCatalog(8081, "nonexistent-model"))
                .thenThrow(new java.io.IOException("Model not found in catalog"));

        ResponseEntity<?> response = controller.stageModel("nonexistent-model", 8081);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertFalse((Boolean) body.get("success"));
        assertTrue(body.get("message").toString().contains("not found"));
    }

    @Test
    void testStageModel_serviceNull() {
        SetupStatusController controllerNoStaging = new SetupStatusController(setupStatusService, null);
        ResponseEntity<?> response = controllerNoStaging.stageModel("bge-base-en-v1.5", 8081);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/setup/staging-server/catalog
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetCatalog_success() throws Exception {
        when(stagingServerLifecycleService.getCatalog(8081)).thenReturn("[{\"id\":\"bge-base-en-v1.5\"}]");

        ResponseEntity<?> response = controller.getStagingCatalog(8081);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetCatalog_failure() throws Exception {
        when(stagingServerLifecycleService.getCatalog(8081)).thenThrow(new java.io.IOException("Connection refused"));

        ResponseEntity<?> response = controller.getStagingCatalog(8081);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertFalse((Boolean) body.get("success"));
    }

    @Test
    void testGetCatalog_serviceNull() {
        SetupStatusController controllerNoStaging = new SetupStatusController(setupStatusService, null);
        ResponseEntity<?> response = controllerNoStaging.getStagingCatalog(8081);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
