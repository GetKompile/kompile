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

import ai.kompile.app.facts.domain.ChatSessionContext;
import ai.kompile.app.facts.service.ChatSessionContextService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ChatSessionContextController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatSessionContextControllerTest {

    @Mock
    private ChatSessionContextService service;

    private ChatSessionContextController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatSessionContextController(service);
    }

    @Test
    void listContexts_returnsOk() {
        ChatSessionContext ctx = mock(ChatSessionContext.class);
        when(service.getSessionContexts("session-1")).thenReturn(List.of(ctx));

        ResponseEntity<List<ChatSessionContext>> response = controller.listContexts("session-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void listContexts_emptySession_returnsEmptyList() {
        when(service.getSessionContexts("session-2")).thenReturn(List.of());

        ResponseEntity<List<ChatSessionContext>> response = controller.listContexts("session-2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void setMode_validMode_returnsOk() {
        ChatSessionContext ctx = mock(ChatSessionContext.class);
        when(service.setSourceMode(eq("session-1"), any(), any(), any(),
                eq(ChatSessionContext.SourceMode.FULL))).thenReturn(ctx);

        // SetModeRequest is a public static inner class of the controller
        var body = new ChatSessionContextController.SetModeRequest();
        body.factSheetId = 1L;
        body.factId = 10L;
        body.sourceDisplayName = "Doc1";
        body.mode = "FULL";

        ResponseEntity<ChatSessionContext> response = controller.setMode("session-1", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ctx, response.getBody());
    }

    @Test
    void setMode_nullMode_defaultsToFull() {
        ChatSessionContext ctx = mock(ChatSessionContext.class);
        when(service.setSourceMode(eq("session-1"), any(), any(), any(),
                eq(ChatSessionContext.SourceMode.FULL))).thenReturn(ctx);

        var body = new ChatSessionContextController.SetModeRequest();
        body.factSheetId = 1L;
        body.factId = 10L;
        body.mode = null;

        ResponseEntity<ChatSessionContext> response = controller.setMode("session-1", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void listExcluded_returnsOk() {
        when(service.getExcludedFactIds("session-1")).thenReturn(List.of(1L, 2L));

        ResponseEntity<List<Long>> response = controller.listExcluded("session-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void clearSession_returnsNoContent() {
        ResponseEntity<Void> response = controller.clearSession("session-1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).clearSession("session-1");
    }
}
