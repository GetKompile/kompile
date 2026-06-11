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

import ai.kompile.app.services.mcp.ToolDefinitionService;
import ai.kompile.core.mcp.EnhancedToolDefinition;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ToolDefinitionController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolDefinitionControllerTest {

    @Mock
    private ToolDefinitionService toolDefinitionService;

    private ToolDefinitionController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolDefinitionController(toolDefinitionService);
    }

    @Test
    void getAllTools_noFilter_returnsAll() {
        EnhancedToolDefinition tool = mock(EnhancedToolDefinition.class);
        when(toolDefinitionService.getAllTools()).thenReturn(List.of(tool));

        ResponseEntity<List<EnhancedToolDefinition>> response =
                controller.getAllTools(null, null, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(toolDefinitionService).getAllTools();
    }

    @Test
    void getAllTools_byCategory_returnsFiltered() {
        when(toolDefinitionService.getToolsByCategory("rag")).thenReturn(List.of());

        ResponseEntity<List<EnhancedToolDefinition>> response =
                controller.getAllTools("rag", null, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(toolDefinitionService).getToolsByCategory("rag");
    }

    @Test
    void getAllTools_byQuery_returnsSearchResult() {
        when(toolDefinitionService.searchTools("search")).thenReturn(List.of());

        ResponseEntity<List<EnhancedToolDefinition>> response =
                controller.getAllTools(null, "search", false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(toolDefinitionService).searchTools("search");
    }

    @Test
    void getAllTools_enabledOnly_returnsEnabled() {
        when(toolDefinitionService.getEnabledTools()).thenReturn(List.of());

        ResponseEntity<List<EnhancedToolDefinition>> response =
                controller.getAllTools(null, null, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(toolDefinitionService).getEnabledTools();
    }

    @Test
    void getToolByName_found_returnsOk() {
        EnhancedToolDefinition tool = mock(EnhancedToolDefinition.class);
        when(toolDefinitionService.getToolByName("rag")).thenReturn(Optional.of(tool));

        ResponseEntity<EnhancedToolDefinition> response = controller.getToolByName("rag");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(tool, response.getBody());
    }

    @Test
    void getToolByName_notFound_returnsNotFound() {
        when(toolDefinitionService.getToolByName("missing")).thenReturn(Optional.empty());

        ResponseEntity<EnhancedToolDefinition> response = controller.getToolByName("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getToolPrompt_found_returnsOk() {
        EnhancedToolDefinition tool = mock(EnhancedToolDefinition.class);
        when(tool.getAgentPrompt()).thenReturn("Use this tool for RAG queries");
        when(toolDefinitionService.getToolByName("rag")).thenReturn(Optional.of(tool));

        ResponseEntity<String> response = controller.getToolPrompt("rag");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Use this tool for RAG queries", response.getBody());
    }

    @Test
    void getToolPrompt_notFound_returnsNotFound() {
        when(toolDefinitionService.getToolByName("missing")).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.getToolPrompt("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createTool_success_returnsCreated() {
        EnhancedToolDefinition definition = mock(EnhancedToolDefinition.class);
        EnhancedToolDefinition saved = mock(EnhancedToolDefinition.class);
        when(toolDefinitionService.createCustomTool(definition)).thenReturn(saved);

        ResponseEntity<?> response = controller.createTool(definition);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void deleteTool_success_returnsNoContent() {
        when(toolDefinitionService.deleteToolDefinition("myTool")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteTool("myTool");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void deleteTool_notFound_returnsNotFound() {
        when(toolDefinitionService.deleteToolDefinition("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteTool("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
