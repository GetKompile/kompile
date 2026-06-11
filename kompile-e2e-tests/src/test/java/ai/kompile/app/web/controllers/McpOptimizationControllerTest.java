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

import ai.kompile.app.services.mcp.optimization.McpOptimizationConfigService;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpOptimizationControllerTest {

    @Mock
    private McpOptimizationConfigService configService;

    private McpOptimizationController controller;

    @BeforeEach
    void setUp() {
        controller = new McpOptimizationController(configService);
    }

    private McpOptimizationConfig defaultConfig() {
        McpOptimizationConfig cfg = new McpOptimizationConfig();
        cfg.setEnabled(true);
        cfg.setRagMaxContentChars(8000);
        cfg.setRagMaxDocs(5);
        return cfg;
    }

    @Test
    void getConfig_returnsCurrentConfiguration() {
        McpOptimizationConfig config = defaultConfig();
        when(configService.getConfiguration()).thenReturn(config);
        when(configService.getConfigFilePath()).thenReturn("/tmp/mcp-opt.json");

        ResponseEntity<Map<String, Object>> response = controller.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("enabled"));
        assertEquals(8000, response.getBody().get("ragMaxContentChars"));
    }

    @Test
    void updateConfig_withValidConfig_returnsUpdatedConfig() {
        McpOptimizationConfig update = defaultConfig();
        McpOptimizationConfig updated = defaultConfig();
        updated.setRagMaxContentChars(12000);
        when(configService.updateConfiguration(update)).thenReturn(updated);
        when(configService.getConfigFilePath()).thenReturn("/tmp/mcp-opt.json");

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(update);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(12000, response.getBody().get("ragMaxContentChars"));
    }

    @Test
    void updateConfig_withInvalidConfig_returnsBadRequest() {
        McpOptimizationConfig update = new McpOptimizationConfig();
        when(configService.updateConfiguration(update))
                .thenThrow(new IllegalArgumentException("Invalid config value"));

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(update);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid config value", response.getBody().get("error"));
    }

    @Test
    void updateConfig_whenServiceThrowsRuntimeException_returnsInternalServerError() {
        McpOptimizationConfig update = new McpOptimizationConfig();
        when(configService.updateConfiguration(update))
                .thenThrow(new RuntimeException("Unexpected error"));

        ResponseEntity<Map<String, Object>> response = controller.updateConfig(update);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void resetConfig_returnsDefaultConfig() {
        McpOptimizationConfig defaults = new McpOptimizationConfig();
        defaults.setEnabled(true);
        when(configService.resetConfiguration()).thenReturn(defaults);
        when(configService.getConfigFilePath()).thenReturn("/tmp/mcp-opt.json");

        ResponseEntity<Map<String, Object>> response = controller.resetConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("message"));
        String msg = (String) response.getBody().get("message");
        assertTrue(msg.contains("reset"));
    }
}
