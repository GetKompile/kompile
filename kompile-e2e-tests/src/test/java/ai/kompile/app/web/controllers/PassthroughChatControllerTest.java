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

import ai.kompile.app.services.agent.PassthroughSessionManager;
import ai.kompile.app.web.dto.PassthroughMessageRequest;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PassthroughChatControllerTest {

    @Mock
    private PassthroughSessionManager sessionManager;

    private PassthroughChatController controller;

    @BeforeEach
    void setUp() {
        controller = new PassthroughChatController(sessionManager);
    }

    // ── sendMessage ───────────────────────────────────────────────────────

    @Test
    void sendMessage_success_returnsSent() {
        PassthroughMessageRequest req = new PassthroughMessageRequest();
        req.setSessionId("sess-1");
        req.setMessage("Hello");
        when(sessionManager.sendMessage("sess-1", "Hello")).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("sent", resp.getBody().get("status"));
    }

    @Test
    void sendMessage_sessionNotFound_returnsBadRequest() {
        PassthroughMessageRequest req = new PassthroughMessageRequest();
        req.setSessionId("unknown");
        req.setMessage("Hello");
        when(sessionManager.sendMessage("unknown", "Hello")).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.sendMessage(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("error", resp.getBody().get("status"));
    }

    // ── endSession ────────────────────────────────────────────────────────

    @Test
    void endSession_returns200WithEndedStatus() {
        doNothing().when(sessionManager).endSession("sess-1");

        ResponseEntity<Map<String, Object>> resp = controller.endSession("sess-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ended", resp.getBody().get("status"));
        assertEquals("sess-1", resp.getBody().get("sessionId"));
        verify(sessionManager).endSession("sess-1");
    }

    // ── getStatus ─────────────────────────────────────────────────────────

    @Test
    void getStatus_found_returns200() {
        Map<String, Object> status = Map.of("sessionId", "sess-1", "active", true);
        when(sessionManager.getStatus("sess-1")).thenReturn(status);

        ResponseEntity<Map<String, Object>> resp = controller.getStatus("sess-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(status, resp.getBody());
    }

    @Test
    void getStatus_notFound_returns404() {
        when(sessionManager.getStatus("unknown")).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.getStatus("unknown");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── listSessions ──────────────────────────────────────────────────────

    @Test
    void listSessions_returnsList() {
        List<Map<String, Object>> sessions = List.of(
                Map.of("sessionId", "sess-1"),
                Map.of("sessionId", "sess-2")
        );
        when(sessionManager.listSessions()).thenReturn(sessions);

        ResponseEntity<List<Map<String, Object>>> resp = controller.listSessions();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }
}
