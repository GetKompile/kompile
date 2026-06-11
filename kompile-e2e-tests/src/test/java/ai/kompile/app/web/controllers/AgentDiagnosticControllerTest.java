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

import ai.kompile.app.services.agent.AgentProcessDiagnosticService;
import ai.kompile.app.services.agent.AgentRegistryService;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentDiagnosticController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentDiagnosticControllerTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private AgentProcessDiagnosticService diagnosticService;

    private AgentDiagnosticController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentDiagnosticController(agentRegistryService, diagnosticService);
    }

    @Test
    void getAllAgents_returnsOk() {
        AgentProvider agent = mock(AgentProvider.class);
        when(agentRegistryService.getAllAgents()).thenReturn(List.of(agent));

        ResponseEntity<List<AgentProvider>> response = controller.getAllAgents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void getAvailableAgents_returnsOk() {
        when(agentRegistryService.getAvailableAgents()).thenReturn(List.of());

        ResponseEntity<List<AgentProvider>> response = controller.getAvailableAgents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getDefaultAgent_found_returnsOk() {
        AgentProvider agent = mock(AgentProvider.class);
        when(agentRegistryService.getDefaultAgent()).thenReturn(Optional.of(agent));

        ResponseEntity<AgentProvider> response = controller.getDefaultAgent();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(agent, response.getBody());
    }

    @Test
    void getDefaultAgent_notFound_returnsNotFound() {
        when(agentRegistryService.getDefaultAgent()).thenReturn(Optional.empty());

        ResponseEntity<AgentProvider> response = controller.getDefaultAgent();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getAgent_found_returnsOk() {
        AgentProvider agent = mock(AgentProvider.class);
        when(agentRegistryService.getAgent("claude")).thenReturn(Optional.of(agent));

        ResponseEntity<AgentProvider> response = controller.getAgent("claude");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(agent, response.getBody());
    }

    @Test
    void getAgent_notFound_returnsNotFound() {
        when(agentRegistryService.getAgent("missing")).thenReturn(Optional.empty());

        ResponseEntity<AgentProvider> response = controller.getAgent("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getAgentCounts_returnsOk() {
        when(agentRegistryService.getAllAgents()).thenReturn(List.of(mock(AgentProvider.class), mock(AgentProvider.class), mock(AgentProvider.class)));
        when(agentRegistryService.getAvailableAgentCount()).thenReturn(2);

        ResponseEntity<Map<String, Integer>> response = controller.getAgentCounts();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("total"));
        assertTrue(response.getBody().containsKey("available"));
    }

    @Test
    void refreshAllAgents_returnsOk() {
        when(agentRegistryService.getAllAgents()).thenReturn(List.of());

        ResponseEntity<List<AgentProvider>> response = controller.refreshAllAgents();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
