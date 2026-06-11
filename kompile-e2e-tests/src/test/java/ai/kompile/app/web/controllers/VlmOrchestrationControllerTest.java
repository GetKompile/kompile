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

import ai.kompile.app.config.VlmOrchestrationConfig;
import ai.kompile.app.services.VlmOrchestrationConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * Tests for {@link VlmOrchestrationController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmOrchestrationControllerTest {

    @Mock
    private VlmOrchestrationConfigService configService;

    @InjectMocks
    private VlmOrchestrationController controller;

    @Test
    void getConfig_returnsOk() {
        VlmOrchestrationConfig config = VlmOrchestrationConfig.defaults();
        when(configService.getConfig()).thenReturn(config);

        ResponseEntity<Map<String, Object>> response = controller.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals(config, response.getBody().get("config"));
    }

    @Test
    void updateConfig_success_returnsOk() throws Exception {
        VlmOrchestrationConfig update = VlmOrchestrationConfig.defaults();
        VlmOrchestrationConfig saved = VlmOrchestrationConfig.defaults();
        when(configService.updateConfig(update)).thenReturn(saved);

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(update);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals(saved, response.getBody().get("config"));
    }

    @Test
    void updateConfig_exception_returnsInternalServerError() throws Exception {
        VlmOrchestrationConfig update = VlmOrchestrationConfig.defaults();
        when(configService.updateConfig(update)).thenThrow(new RuntimeException("save failed"));

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(update);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void resetConfig_success_returnsOk() throws Exception {
        VlmOrchestrationConfig defaults = VlmOrchestrationConfig.defaults();
        when(configService.resetToDefaults()).thenReturn(defaults);

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals(defaults, response.getBody().get("config"));
    }

    @Test
    void resetConfig_exception_returnsInternalServerError() throws Exception {
        when(configService.resetToDefaults()).thenThrow(new RuntimeException("reset failed"));

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
    }
}
