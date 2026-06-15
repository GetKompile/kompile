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
import ai.kompile.app.web.dto.PassthroughSessionRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents/passthrough")
public class PassthroughChatController {

    private final PassthroughSessionManager sessionManager;

    public PassthroughChatController(PassthroughSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Connect to start a passthrough session. Returns an SSE stream.
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(
            @RequestParam String agentName,
            @RequestParam(defaultValue = "true") boolean skipPermissions,
            @RequestParam(required = false) String workingDirectory,
            @RequestParam(required = false) String sessionName,
            @RequestParam(defaultValue = "true") boolean injectMcpTools,
            @RequestParam(required = false) java.util.List<String> agentArgs) {

        SseEmitter emitter = new SseEmitter(-1L); // No timeout

        PassthroughSessionRequest request = new PassthroughSessionRequest();
        request.setAgentName(agentName);
        request.setSkipPermissions(skipPermissions);
        request.setWorkingDirectory(workingDirectory);
        request.setSessionName(sessionName);
        request.setInjectMcpTools(injectMcpTools);
        request.setAgentArgs(agentArgs);

        String sessionId = sessionManager.startSession(request, emitter);

        // Clean up subprocess and session on client disconnect
        if (sessionId != null) {
            Runnable cleanup = () -> sessionManager.endSession(sessionId);
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(e -> cleanup.run());
        }

        return emitter;
    }

    /**
     * Send a message to an active passthrough session.
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody PassthroughMessageRequest request) {
        boolean success = sessionManager.sendMessage(request.getSessionId(), request.getMessage());
        if (success) {
            return ResponseEntity.ok(Map.of("status", "sent"));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Session not found or inactive: " + request.getSessionId()));
    }

    /**
     * End a passthrough session.
     */
    @PostMapping("/end/{sessionId}")
    public ResponseEntity<Map<String, Object>> endSession(@PathVariable String sessionId) {
        sessionManager.endSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "ended", "sessionId", sessionId));
    }

    /**
     * Get status of a specific session.
     */
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String sessionId) {
        Map<String, Object> status = sessionManager.getStatus(sessionId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * List all active sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(sessionManager.listSessions());
    }
}
