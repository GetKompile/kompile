/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.lite.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat REST endpoints for Kompile Lite.
 */
@RestController
@RequestMapping("/api/lite/chat")
public class LiteChatController {

    @Autowired
    private LiteChatService chatService;

    /**
     * Synchronous chat — returns full JSON response.
     */
    @PostMapping
    public ResponseEntity<LiteChatService.ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : "lite-" + UUID.randomUUID().toString().substring(0, 8);
        var response = chatService.chat(request.message(), sessionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Streaming chat via Server-Sent Events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : "lite-" + UUID.randomUUID().toString().substring(0, 8);
        return chatService.chatStream(request.message(), sessionId);
    }

    /**
     * Get conversation history.
     */
    @GetMapping("/history")
    public ResponseEntity<List<LiteChatService.ChatMessage>> getHistory(
            @RequestParam(defaultValue = "default") String sessionId) {
        return ResponseEntity.ok(chatService.getHistory(sessionId));
    }

    /**
     * Delete conversation history.
     */
    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<Map<String, String>> deleteHistory(@PathVariable String sessionId) {
        chatService.deleteHistory(sessionId);
        return ResponseEntity.ok(Map.of("status", "deleted", "sessionId", sessionId));
    }

    public record ChatRequest(String message, String sessionId) {}
}
