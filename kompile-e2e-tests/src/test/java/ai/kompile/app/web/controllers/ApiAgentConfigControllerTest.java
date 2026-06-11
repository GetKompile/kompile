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

import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ApiAgentChatExecutor;

import ai.kompile.core.agent.AgentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ApiAgentConfigController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAgentConfigControllerTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private ApiAgentChatExecutor apiAgentChatExecutor;

    private ApiAgentConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAgentConfigController(agentRegistryService, apiAgentChatExecutor);
        when(agentRegistryService.getAllAgents()).thenReturn(Collections.emptyList());
    }

    @Test
    void listApiAgents_returnsOk() {
        ResponseEntity<List<AgentProvider>> response = controller.listApiAgents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void addApiAgent_missingName_returnsBadRequest() {
        ApiAgentConfigController.ApiAgentRequest request = new ApiAgentConfigController.ApiAgentRequest();
        request.endpointUrl = "http://localhost:11434/v1";
        request.modelName = "llama3";

        ResponseEntity<Map<String, Object>> response = controller.addApiAgent(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    @Test
    void addApiAgent_missingEndpoint_returnsBadRequest() {
        ApiAgentConfigController.ApiAgentRequest request = new ApiAgentConfigController.ApiAgentRequest();
        request.name = "ollama";
        request.modelName = "llama3";

        ResponseEntity<Map<String, Object>> response = controller.addApiAgent(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void addApiAgent_missingModelName_returnsBadRequest() {
        ApiAgentConfigController.ApiAgentRequest request = new ApiAgentConfigController.ApiAgentRequest();
        request.name = "ollama";
        request.endpointUrl = "http://localhost:11434/v1";

        ResponseEntity<Map<String, Object>> response = controller.addApiAgent(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void addApiAgent_duplicateName_returnsBadRequest() {
        ApiAgentConfigController.ApiAgentRequest request = new ApiAgentConfigController.ApiAgentRequest();
        request.name = "ollama";
        request.endpointUrl = "http://localhost:11434/v1";
        request.modelName = "llama3";

        AgentProvider existing = mock(AgentProvider.class);
        when(agentRegistryService.getAgent("ollama")).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> response = controller.addApiAgent(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void addApiAgent_validRequest_returnsOk() {
        ApiAgentConfigController.ApiAgentRequest request = new ApiAgentConfigController.ApiAgentRequest();
        request.name = "ollama";
        request.endpointUrl = "http://localhost:11434/v1";
        request.modelName = "llama3";

        when(agentRegistryService.getAgent("ollama")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.addApiAgent(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ollama", response.getBody().get("name"));
    }

    @Test
    void deleteApiAgent_notFound_returnsNotFound() {
        when(agentRegistryService.getAgent("missing")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.deleteApiAgent("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
