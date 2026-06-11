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

import ai.kompile.app.config.ModelWarmupConfig;
import ai.kompile.app.services.ModelWarmupConfigService;
import ai.kompile.app.services.ModelWarmupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelWarmupControllerTest {

    @Mock
    private ModelWarmupConfigService configService;

    @Mock
    private ModelWarmupService warmupService;

    private ModelWarmupController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelWarmupController();
        ReflectionTestUtils.setField(controller, "configService", configService);
        ReflectionTestUtils.setField(controller, "warmupService", warmupService);
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        ModelWarmupConfig config = new ModelWarmupConfig();
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<ModelWarmupConfig> response = controller.getConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void updateConfig_successfullySavesConfig() throws IOException {
        ModelWarmupConfig config = new ModelWarmupConfig();
        ModelWarmupConfig updated = new ModelWarmupConfig();
        doNothing().when(configService).saveConfiguration(config);
        when(configService.getConfiguration()).thenReturn(updated);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("updated", response.getBody().get("status"));
        assertEquals(updated, response.getBody().get("config"));
    }

    @Test
    void updateConfig_whenSaveThrows_returnsInternalServerError() throws IOException {
        ModelWarmupConfig config = new ModelWarmupConfig();
        doThrow(new IOException("Write error")).when(configService).saveConfiguration(config);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(config);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void resetConfig_resetsAndReturnsOk() throws IOException {
        ModelWarmupConfig defaults = new ModelWarmupConfig();
        doNothing().when(configService).resetToDefaults();
        when(configService.getConfiguration()).thenReturn(defaults);

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("reset", response.getBody().get("status"));
    }

    @Test
    void resetConfig_whenResetThrows_returnsInternalServerError() throws IOException {
        doThrow(new IOException("Cannot reset")).when(configService).resetToDefaults();

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void getStatus_returnsWarmupStatus() {
        Map<String, Object> status = Map.of("lastWarmup", "2025-01-01T00:00:00Z");
        when(warmupService.getStatus()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
    }

    @Test
    void triggerWarmupAll_triggersAndReturnsResults() {
        ModelWarmupService.WarmupResult result = new ModelWarmupService.WarmupResult(
                "embedding", true, 250L, 5, Instant.now(), null);
        Map<String, ModelWarmupService.WarmupResult> results = Map.of("embedding", result);
        when(warmupService.warmupAll()).thenReturn(results);

        ResponseEntity<Map<String, Object>> response = controller.triggerWarmupAll();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("completed", response.getBody().get("status"));
        assertEquals(results, response.getBody().get("results"));
    }

    @Test
    void triggerWarmup_withValidServiceType_returnsResult() {
        ModelWarmupService.WarmupResult result = new ModelWarmupService.WarmupResult(
                "embedding", true, 250L, 5, Instant.now(), null);
        when(warmupService.warmupService("embedding")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.triggerWarmup("embedding");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("completed", response.getBody().get("status"));
        assertEquals(result, response.getBody().get("result"));
    }

    @Test
    void triggerWarmup_withUnknownServiceType_returnsNotFound() {
        when(warmupService.warmupService("unknown")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.triggerWarmup("unknown");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
