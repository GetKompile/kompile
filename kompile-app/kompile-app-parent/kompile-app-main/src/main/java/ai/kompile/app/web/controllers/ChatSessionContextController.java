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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for per-session, per-source context control.
 *
 * Lets the UI toggle each source within a chat session between FULL / SUMMARY_ONLY /
 * EXCLUDED without touching the FactSheet itself or other sessions.
 *
 * Endpoints:
 * <ul>
 *   <li>GET    /api/chat-sessions/{sessionId}/context          — list all source-mode rows</li>
 *   <li>PUT    /api/chat-sessions/{sessionId}/context          — upsert a (factId → mode) entry</li>
 *   <li>GET    /api/chat-sessions/{sessionId}/context/excluded — list excluded factIds</li>
 *   <li>DELETE /api/chat-sessions/{sessionId}/context          — clear all rows for the session</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat-sessions/{sessionId}/context")
public class ChatSessionContextController {

    private final ChatSessionContextService service;

    @Autowired
    public ChatSessionContextController(ChatSessionContextService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ChatSessionContext>> listContexts(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getSessionContexts(sessionId));
    }

    @PutMapping
    public ResponseEntity<ChatSessionContext> setMode(@PathVariable String sessionId,
                                                      @RequestBody SetModeRequest body) {
        ChatSessionContext.SourceMode mode = parseMode(body.mode);
        ChatSessionContext ctx = service.setSourceMode(sessionId, body.factSheetId, body.factId,
                body.sourceDisplayName, mode);
        return ResponseEntity.ok(ctx);
    }

    @GetMapping("/excluded")
    public ResponseEntity<List<Long>> listExcluded(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.getExcludedFactIds(sessionId));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        service.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    private static ChatSessionContext.SourceMode parseMode(String mode) {
        if (mode == null) return ChatSessionContext.SourceMode.FULL;
        try {
            return ChatSessionContext.SourceMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChatSessionContext.SourceMode.FULL;
        }
    }

    public static class SetModeRequest {
        public Long factSheetId;
        public Long factId;
        public String sourceDisplayName;
        public String mode;
    }
}
