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

import ai.kompile.toolgateway.model.GatewayAction;
import ai.kompile.toolgateway.model.ToolGatewayConfig;
import ai.kompile.toolgateway.model.ToolGatewayRulesConfig;
import ai.kompile.toolgateway.service.ToolGatewayConfigService;
import ai.kompile.toolgateway.service.ToolGatewayRulesProvider;
import ai.kompile.toolgateway.service.ToolGatewayService;
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
 * Tests for {@link ToolGatewayConfigController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolGatewayConfigControllerTest {

    @Mock
    private ToolGatewayConfigService configService;

    @Mock
    private ToolGatewayRulesProvider rulesProvider;

    @Mock
    private ToolGatewayService gatewayService;

    @Mock
    private ToolGatewayConfig gatewayConfig;

    private ToolGatewayConfigController controller;
    private ToolGatewayConfigController controllerNoOptional;

    @BeforeEach
    void setUp() {
        when(configService.getConfig()).thenReturn(gatewayConfig);
        when(configService.isEnabled()).thenReturn(true);
        when(gatewayConfig.getModelSource()).thenReturn(
                ToolGatewayConfig.ModelSource.STAGING);
        controller = new ToolGatewayConfigController(configService, rulesProvider, gatewayService);
        controllerNoOptional = new ToolGatewayConfigController(configService, null, null);
    }

    @Test
    void getConfig_withRulesProvider_returnsOk() {
        ToolGatewayRulesConfig rulesConfig = mock(ToolGatewayRulesConfig.class);
        when(rulesConfig.getDefaultAction()).thenReturn(GatewayAction.ALLOW);
        when(rulesConfig.getSystemPrompt()).thenReturn("judge system prompt");
        when(rulesConfig.getRules()).thenReturn(java.util.List.of());
        when(rulesProvider.getConfig()).thenReturn(rulesConfig);

        ResponseEntity<Map<String, Object>> response = controller.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("available"));
    }

    @Test
    void getConfig_withoutRulesProvider_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controllerNoOptional.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("available"));
    }

    @Test
    void toggle_enabled_returnsOk() {
        ResponseEntity<Map<String, Object>> response =
                controllerNoOptional.toggle(Map.of("enabled", true));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).setEnabled(true);
    }

    @Test
    void toggle_disabled_returnsOk() {
        ResponseEntity<Map<String, Object>> response =
                controllerNoOptional.toggle(Map.of("enabled", false));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(configService).setEnabled(false);
    }
}
