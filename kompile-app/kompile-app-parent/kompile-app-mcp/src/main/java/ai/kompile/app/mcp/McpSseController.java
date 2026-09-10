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

package ai.kompile.app.mcp;

import ai.kompile.app.services.mcp.ScopedMcpCapabilityService;
import ai.kompile.app.services.mcp.ScopedMcpCapabilityService.ScopeUnavailableException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MCP Controller exposing the Model Context Protocol via SSE transport.
 *
 * This controller implements both MCP transport specifications:
 *
 * 1. SSE Transport (protocol version 2024-11-05):
 *    - GET /mcp/sse - Establishes an SSE connection and returns the message endpoint URL
 *    - POST /mcp/message - Receives JSON-RPC messages from clients
 *
 * 2. Streamable HTTP Transport (protocol version 2025-03-26):
 *    - POST /mcp/sse - Handles JSON-RPC messages directly, responds with SSE stream
 *    - DELETE /mcp/sse - Terminates sessions
 *    - Uses Mcp-Session-Id header for session management
 *
 * The SSE connection sends two types of events:
 * - "endpoint" event: Contains the URL where the client should POST messages (SSE transport only)
 * - "message" event: Contains JSON-RPC responses and notifications
 */
@RestController("appMcpSseController")
@RequestMapping("/mcp")
@ConditionalOnBean(SpringMvcSseServerTransport.class)
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    private final SpringMvcSseServerTransport transport;
    private final ScopedMcpCapabilityService scopedCapabilities;

    @Autowired
    public McpSseController(
            SpringMvcSseServerTransport transport,
            ScopedMcpCapabilityService scopedCapabilities) {
        this.transport = transport;
        this.scopedCapabilities = scopedCapabilities;
    }

    /**
     * SSE endpoint for establishing MCP connections (GET - SSE Transport).
     *
     * When a client connects, they receive an "endpoint" event containing
     * the URL where they should POST JSON-RPC messages.
     *
     * @return SseEmitter for the SSE connection
     */
    @GetMapping(path = "/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public SseEmitter connectSse(@RequestHeader(value = "Accept", required = false) String acceptHeader) {
        log.info("New MCP SSE connection request (Accept: {})", acceptHeader);
        try {
            SseEmitter emitter = transport.createConnection();
            log.info("MCP SSE connection established. Active sessions: {}", transport.getSessionCount());
            return emitter;
        } catch (Exception e) {
            log.error("Failed to create MCP SSE connection", e);
            throw e;
        }
    }

    /** Establish an SSE session against one short-lived, one-tool bearer capability. */
    @GetMapping(path = "/scoped/{capability}/sse",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public SseEmitter connectScoped(
            @PathVariable String capability,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        try {
            return sessionId == null
                    ? scopedCapabilities.connect(capability)
                    : scopedCapabilities.openStream(capability, sessionId);
        } catch (ScopeUnavailableException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        } catch (IllegalArgumentException missing) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Scoped MCP session is unavailable");
        } catch (IllegalStateException duplicate) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Scoped MCP common stream is already open");
        }
    }

    /**
     * Streamable HTTP endpoint for MCP (POST - Streamable HTTP Transport).
     *
     * This endpoint implements the MCP Streamable HTTP transport (protocol version 2025-03-26).
     * It handles JSON-RPC messages directly via POST and responds with an SSE stream.
     *
     * Session management:
     * - On initialize request: Creates a new session and returns Mcp-Session-Id header
     * - On subsequent requests: Requires Mcp-Session-Id header to identify the session
     *
     * @param sessionId Optional session ID from Mcp-Session-Id header
     * @param request The HTTP request containing the JSON-RPC message body
     * @param response The HTTP response for setting headers
     * @return SseEmitter for streaming responses
     */
    @PostMapping(path = "/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public SseEmitter handleStreamableHttp(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {

        String body = readRequestBody(request);

        log.info("Streamable HTTP request (Session: {}): {}",
                sessionId,
                body.length() > 200 ? body.substring(0, 200) + "..." : body);

        // Check if this is an initialize request (no session yet)
        boolean isInitialize = body.contains("\"method\"") && body.contains("\"initialize\"");

        if (isInitialize || sessionId == null) {
            log.info("Creating new Streamable HTTP session");
            SpringMvcSseServerTransport.StreamableHttpResult result = transport.createStreamableHttpConnection(body);
            response.setHeader("Mcp-Session-Id", result.sessionId());
            return result.emitter();
        } else {
            if (!transport.hasSession(sessionId)) {
                log.warn("Streamable HTTP message for unknown session: {}", sessionId);
                throw new RuntimeException("Unknown session: " + sessionId);
            }
            return transport.handleStreamableHttpMessage(sessionId, body);
        }
    }

    /** Streamable HTTP transport for one short-lived bearer capability. */
    @PostMapping(path = "/scoped/{capability}/sse",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public Object handleScopedStreamableHttp(
            @PathVariable String capability,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {
        String body = readRequestBody(request, ScopedMcpCapabilityService.MAX_SCHEMA_BYTES * 8);
        try {
            boolean initialize = scopedCapabilities.isInitializeMessage(body);
            if (initialize && sessionId == null) {
                SpringMvcSseServerTransport.StreamableHttpResult result =
                        scopedCapabilities.createStreamableConnection(capability, body);
                response.setHeader("Mcp-Session-Id", result.sessionId());
                return result.emitter();
            }
            if (sessionId == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Scoped MCP session id is required");
            }
            if (!scopedCapabilities.hasSession(capability, sessionId)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Scoped MCP session is unavailable");
            }
            if (scopedCapabilities.isNotificationMessage(body)) {
                scopedCapabilities.handleStreamableMessage(capability, sessionId, body);
                return ResponseEntity.accepted().build();
            }
            return scopedCapabilities.handleStreamableMessage(capability, sessionId, body);
        } catch (ScopeUnavailableException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        } catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid scoped MCP request");
        } catch (IllegalStateException concurrent) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Scoped MCP session already has an in-flight request");
        }
    }

    /**
     * Message endpoint for receiving JSON-RPC messages from clients (SSE Transport).
     *
     * @param sessionId The session ID from the SSE connection
     * @param request The HTTP request containing the JSON-RPC message body
     * @return ResponseEntity indicating success or error
     */
    @PostMapping(path = "/message")
    public ResponseEntity<Void> handleMessage(
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {

        String body = readRequestBody(request);

        log.debug("Received MCP message for session {}: {}",
                sessionId,
                body.length() > 200 ? body.substring(0, 200) + "..." : body);

        if (!transport.hasSession(sessionId)) {
            log.warn("Message received for unknown session: {}", sessionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            Mono<Void> result = transport.handleMessage(sessionId, body);
            // Return 202 immediately; MCP responses are delivered asynchronously
            // over the already-open SSE stream. Blocking here can deadlock when
            // the session handler waits for the outbound emitter write.
            result.subscribeOn(Schedulers.boundedElastic()).subscribe(
                    unused -> { },
                    error -> log.error("Error handling MCP message for session {}: {}", sessionId, error.getMessage(), error),
                    () -> log.info("MCP message processing completed for session {}", sessionId)
            );
            log.info("MCP message accepted for asynchronous processing, session {}", sessionId);
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("Error handling MCP message for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Receive an SSE-transport message for one short-lived bearer capability. */
    @PostMapping(path = "/scoped/{capability}/message")
    public ResponseEntity<Void> handleScopedMessage(
            @PathVariable String capability,
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {
        String body = readRequestBody(request, ScopedMcpCapabilityService.MAX_SCHEMA_BYTES * 8);
        try {
            if (!scopedCapabilities.hasSession(capability, sessionId)) {
                return ResponseEntity.notFound().build();
            }
            scopedCapabilities.handleMessage(capability, sessionId, body)
                    .subscribeOn(Schedulers.boundedElastic()).subscribe(
                            unused -> { },
                            error -> log.warn("Scoped MCP message processing failed"));
            return ResponseEntity.accepted().build();
        } catch (ScopeUnavailableException unavailable) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Session termination endpoint for MCP Streamable HTTP transport.
     *
     * Per MCP Streamable HTTP spec (protocol version 2025-03-26):
     * "Clients that no longer need a particular session SHOULD send an HTTP DELETE
     * to the MCP endpoint with the Mcp-Session-Id header, to explicitly terminate the session."
     *
     * @param sessionId The session ID from Mcp-Session-Id header
     * @return ResponseEntity indicating success or error
     */
    @DeleteMapping(path = "/sse")
    public ResponseEntity<Void> terminateSession(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            log.warn("DELETE /mcp/sse called without Mcp-Session-Id header");
            return ResponseEntity.badRequest().build();
        }

        log.info("Session termination request for session: {}", sessionId);

        if (!transport.hasSession(sessionId)) {
            log.warn("DELETE request for unknown session: {}", sessionId);
            return ResponseEntity.notFound().build();
        }

        try {
            transport.terminateSession(sessionId);
            log.info("Session {} terminated successfully", sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error terminating session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Terminate one streamable HTTP session without revealing bearer-token state. */
    @DeleteMapping(path = "/scoped/{capability}/sse")
    public ResponseEntity<Void> terminateScopedSession(
            @PathVariable String capability,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            if (!scopedCapabilities.hasSession(capability, sessionId)) {
                return ResponseEntity.notFound().build();
            }
            scopedCapabilities.terminateSession(capability, sessionId);
            return ResponseEntity.ok().build();
        } catch (ScopeUnavailableException unavailable) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Health check endpoint for MCP server status.
     */
    @GetMapping("/status")
    public ResponseEntity<McpStatus> getStatus() {
        return ResponseEntity.ok(new McpStatus(
            true,
            transport.getSessionCount(),
            "MCP SSE Server running"
        ));
    }

    /**
     * Status response object.
     */
    public record McpStatus(boolean enabled, int activeSessions, String message) {}

    private static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

    private String readRequestBody(HttpServletRequest request) {
        return readRequestBody(request, MAX_REQUEST_BODY_BYTES);
    }

    private String readRequestBody(HttpServletRequest request, int maximumBytes) {
        try {
            if (request.getContentLengthLong() > maximumBytes) {
                throw new RuntimeException("Request body exceeds " + maximumBytes + " byte limit");
            }
            byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
            if (body.length > maximumBytes) {
                throw new RuntimeException("Request body exceeds " + maximumBytes + " byte limit");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read request body", e);
            throw new RuntimeException("Failed to read request body", e);
        }
    }
}
