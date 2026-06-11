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

import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestResult;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestStatus;
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
 * Tests for {@link VlmTestWorkflowController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmTestWorkflowControllerTest {

    @Mock
    private VlmTestSubprocessLauncher launcher;

    @Mock
    private SubprocessConfigService configService;

    private VlmTestWorkflowController controller;
    private VlmTestWorkflowController controllerNoConfig;

    @BeforeEach
    void setUp() {
        controller = new VlmTestWorkflowController(launcher, configService, null);
        controllerNoConfig = new VlmTestWorkflowController(launcher, null, null);
    }

    @Test
    void getStatus_taskNotFound_returnsNotFound() {
        when(launcher.getStatus("missing")).thenReturn(null);

        ResponseEntity<?> response = controller.getStatus("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getStatus_taskRunning_returnsOk() {
        VlmTestStatus status = mock(VlmTestStatus.class);
        when(status.taskId()).thenReturn("task-1");
        when(status.status()).thenReturn("RUNNING");
        when(status.progressPercent()).thenReturn(50);
        when(status.currentPhase()).thenReturn("INFERENCE");
        when(status.pagesCompleted()).thenReturn(5);
        when(launcher.getStatus("task-1")).thenReturn(status);

        ResponseEntity<?> response = controller.getStatus("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getResults_taskNotFound_notRunning_returnsNotFound() {
        when(launcher.isRunning("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.getResults("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getResults_taskStillRunning_returnsOkWithRunningStatus() {
        when(launcher.isRunning("task-1")).thenReturn(true);

        ResponseEntity<?> response = controller.getResults("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("RUNNING", body.get("status"));
    }

    @Test
    void cancelTest_success_returnsOk() {
        when(launcher.cancelTest("task-1")).thenReturn(true);

        ResponseEntity<?> response = controller.cancelTest("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("CANCELLED", body.get("status"));
    }

    @Test
    void cancelTest_notFound_returnsNotFound() {
        when(launcher.cancelTest("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.cancelTest("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getConfig_withConfigService_returnsOk() {
        when(configService.getVlmHeapSize()).thenReturn("4g");
        when(configService.getVlmOffHeapMultiplier()).thenReturn(3);
        when(configService.getVlmTimeoutMinutes()).thenReturn(30);
        when(configService.getJavaPath()).thenReturn("java");

        ResponseEntity<?> response = controller.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getConfig_withoutConfigService_returnsFallback() {
        ResponseEntity<?> response = controllerNoConfig.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("4g", body.get("heapSize"));
    }

    @Test
    void updateConfig_withoutConfigService_returnsBadRequest() {
        ResponseEntity<?> response = controllerNoConfig.updateConfig(Map.of("heapSize", "8g"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateConfig_invalidHeapSize_returnsBadRequest() {
        ResponseEntity<?> response = controller.updateConfig(Map.of("heapSize", "invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateConfig_invalidOffHeapMultiplier_returnsBadRequest() {
        ResponseEntity<?> response = controller.updateConfig(Map.of("offHeapMultiplier", 0));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
