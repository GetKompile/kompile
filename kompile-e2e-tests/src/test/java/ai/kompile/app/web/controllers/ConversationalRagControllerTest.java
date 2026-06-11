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

import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ConversationalRagController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationalRagControllerTest {

    @Mock
    private ConversationalRagService ragService;

    private ConversationalRagController controller;

    @BeforeEach
    void setUp() {
        controller = new ConversationalRagController(ragService);
    }

    @Test
    void chat_nullRequest_returnsBadRequest() {
        ResponseEntity<ConversationalRagController.ChatResponse> response = controller.chat(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void chat_blankMessage_returnsBadRequest() {
        // ChatRequest(conversationId, message, options)
        ConversationalRagController.ChatRequest request =
                new ConversationalRagController.ChatRequest(null, "", null);

        ResponseEntity<ConversationalRagController.ChatResponse> response = controller.chat(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void chat_validMessage_noConversationId_returnsOk() {
        // ConversationalRagResult is a record — use factory method instead of mock
        ConversationalRagResult result = ConversationalRagResult.empty();
        when(ragService.chat(anyString(), eq("hello"), any())).thenReturn(result);

        // ChatRequest(conversationId, message, options)
        ConversationalRagController.ChatRequest request =
                new ConversationalRagController.ChatRequest(null, "hello", null);

        ResponseEntity<ConversationalRagController.ChatResponse> response = controller.chat(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void chat_ragError_returnsInternalServerError() {
        // ConversationalRagResult is a record — use factory method instead of mock
        ConversationalRagResult result = ConversationalRagResult.error("Service unavailable");
        when(ragService.chat(anyString(), anyString(), any())).thenReturn(result);

        // ChatRequest(conversationId, message, options)
        ConversationalRagController.ChatRequest request =
                new ConversationalRagController.ChatRequest("conv-1", "hello", null);

        ResponseEntity<ConversationalRagController.ChatResponse> response = controller.chat(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void getHistory_returnsOk() {
        when(ragService.getConversationHistory("conv-1")).thenReturn(List.of());

        ResponseEntity<ConversationalRagController.ConversationHistoryResponse> response =
                controller.getHistory("conv-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void clearConversation_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.clearConversation("conv-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("conv-1", response.getBody().get("conversationId"));
        verify(ragService).clearConversation("conv-1");
    }

    @Test
    void getStatus_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("available"));
    }
}
