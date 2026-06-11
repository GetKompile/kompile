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

import ai.kompile.app.config.ModelAdmissionConfig;
import ai.kompile.app.services.ModelAdmissionConfigService;
import ai.kompile.app.services.ModelAdmissionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelAdmissionRestControllerTest {

    @Mock
    private ModelAdmissionConfigService configService;

    @Mock
    private ModelAdmissionController admissionController;

    private ModelAdmissionRestController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelAdmissionRestController(configService, admissionController);
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        ModelAdmissionConfig config = new ModelAdmissionConfig();
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelAdmissionConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void updateConfig_successfullySavesConfig() throws IOException {
        ModelAdmissionConfig config = new ModelAdmissionConfig();
        doNothing().when(configService).saveConfiguration(config);
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelAdmissionConfig> response = controller.updateConfig(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).saveConfiguration(config);
    }

    @Test
    void updateConfig_whenSaveThrows_returnsInternalServerError() throws IOException {
        ModelAdmissionConfig config = new ModelAdmissionConfig();
        doThrow(new IOException("Disk error")).when(configService).saveConfiguration(config);

        ResponseEntity<ModelAdmissionConfig> response = controller.updateConfig(config);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void resetConfig_resetsAndReturnsDefault() throws IOException {
        ModelAdmissionConfig defaults = new ModelAdmissionConfig();
        doNothing().when(configService).resetToDefaults();
        when(configService.getConfiguration()).thenReturn(defaults);

        ResponseEntity<ModelAdmissionConfig> response = controller.resetConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).resetToDefaults();
    }

    @Test
    void resetConfig_whenResetThrows_returnsInternalServerError() throws IOException {
        doThrow(new IOException("Cannot reset")).when(configService).resetToDefaults();

        ResponseEntity<ModelAdmissionConfig> response = controller.resetConfig();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void getStatus_returnsStatusFromController() {
        Map<String, Object> status = Map.of("gpuHotCount", 2, "cpuWarmCount", 1);
        when(admissionController.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }

    @Test
    void checkAdmission_whenAdmitted_returnsAdmittedTrue() {
        ModelAdmissionController.AdmissionDecision decision =
                new ModelAdmissionController.AdmissionDecision(true, "Sufficient memory",
                        1024L * 1024 * 1024, Collections.emptyList());
        when(admissionController.canAdmit("model-xyz")).thenReturn(decision);

        ResponseEntity<Map<String, Object>> response = controller.checkAdmission("model-xyz");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("admitted"));
        assertEquals("Sufficient memory", response.getBody().get("reason"));
    }

    @Test
    void checkAdmission_whenNotAdmitted_returnsAdmittedFalse() {
        ModelAdmissionController.AdmissionDecision decision =
                new ModelAdmissionController.AdmissionDecision(false, "Out of memory",
                        0L, List.of("model-old"));
        when(admissionController.canAdmit("model-big")).thenReturn(decision);

        ResponseEntity<Map<String, Object>> response = controller.checkAdmission("model-big");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("admitted"));
        assertFalse(((List<?>) response.getBody().get("modelsToEvict")).isEmpty());
    }

    @Test
    void requestLoad_successfullyStartsLoading() {
        CompletableFuture<ModelAdmissionController.LoadedModel> future = CompletableFuture.completedFuture(null);
        when(admissionController.requestLoad("model-xyz")).thenReturn(future);

        ResponseEntity<Map<String, Object>> response = controller.requestLoad("model-xyz");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("loading", response.getBody().get("status"));
        assertEquals("model-xyz", response.getBody().get("modelId"));
    }

    @Test
    void requestLoad_whenExceptionThrown_returnsBadRequest() {
        when(admissionController.requestLoad("bad-model"))
                .thenThrow(new RuntimeException("Cannot load"));

        ResponseEntity<Map<String, Object>> response = controller.requestLoad("bad-model");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void unloadModel_callsUnloadAndReturnsSuccess() {
        doNothing().when(admissionController).unload("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.unloadModel("model-xyz");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        verify(admissionController).unload("model-xyz");
    }

    @Test
    void demoteModel_callsDemoteAndReturnsSuccess() {
        doNothing().when(admissionController).demoteToCpu("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.demoteModel("model-xyz");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("model-xyz"));
    }

    @Test
    void promoteModel_callsPromoteAndReturnsSuccess() {
        doNothing().when(admissionController).promoteToGpu("model-xyz");

        ResponseEntity<Map<String, Object>> response = controller.promoteModel("model-xyz");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("model-xyz"));
    }
}
