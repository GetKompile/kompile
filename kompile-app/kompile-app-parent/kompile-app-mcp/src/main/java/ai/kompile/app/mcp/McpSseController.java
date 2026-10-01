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

import ai.kompile.app.mcp.SpringMvcSseServerTransport.DuplicateRequestIdException;
import ai.kompile.app.mcp.SpringMvcSseServerTransport.UnknownSessionException;
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
import org.springframework.web.server.ResponseStatusException;
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
 *    - POST /mcp/sse - An initialize request starts a session (Mcp-Session-Id response header);
 *      every other request gets its own SSE stream carrying its response; notifications and
 *      responses are answered with 202 Accepted
 *    - GET /mcp/sse with Mcp-Session-Id - Opens a stream for server requests and notifications
 *    - DELETE /mcp/sse - Terminates sessions
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
     * SSE endpoint (GET).
     *
     * Without a session header this starts an SSE-transport session: the client receives an
     * "endpoint" event containing the URL where it should POST JSON-RPC messages. With the
     * Mcp-Session-Id header of a Streamable HTTP session it opens that session's server stream.
     *
     * @return SseEmitter for the SSE connection
     */
    @GetMapping(path = "/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public SseEmitter connectSse(
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                return transport.openStreamableHttpStream(sessionId);
            } catch (UnknownSessionException unknown) {
                log.warn("Server stream requested for unknown MCP session: {}", sessionId);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown MCP session");
            } catch (IllegalStateException closing) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP transport is unavailable");
            }
        }
        log.info("New MCP SSE connection request (Accept: {})", acceptHeader);
        try {
            SseEmitter emitter = transport.createConnection();
            log.info("MCP SSE connection established. Active sessions: {}", transport.getSessionCount());
            return emitter;
        } catch (IllegalStateException closing) {
            log.warn("Refused an MCP SSE connection: {}", closing.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP transport is unavailable");
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP session is unavailable");
        } catch (IllegalStateException revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        }
    }

    /**
     * Streamable HTTP endpoint for MCP (POST - Streamable HTTP Transport).
     *
     * This endpoint implements the MCP Streamable HTTP transport (protocol version 2025-03-26).
     *
     * Session management:
     * - An initialize request always creates a new session and returns the Mcp-Session-Id header
     * - Every other message requires the Mcp-Session-Id header of a live session (404 otherwise)
     *
     * @param sessionId Optional session ID from Mcp-Session-Id header
     * @param request The HTTP request containing the JSON-RPC message body
     * @param response The HTTP response for setting headers
     * @return the request's SSE response stream, or 202 Accepted for a notification or a response
     */
    @PostMapping(path = "/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public Object handleStreamableHttp(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {

        String body = readRequestBody(request);

        log.debug("Streamable HTTP request (Session: {}): {}",
                sessionId,
                body.length() > 200 ? body.substring(0, 200) + "..." : body);

        try {
            if (transport.isInitializeRequest(body)) {
                // Always a new session: a stale session header must not block re-initialization.
                SpringMvcSseServerTransport.StreamableHttpResult result =
                        transport.createStreamableHttpConnection(body);
                response.setHeader("Mcp-Session-Id", result.sessionId());
                log.info("Created Streamable HTTP session {}. Active sessions: {}",
                        result.sessionId(), transport.getSessionCount());
                return result.emitter();
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mcp-Session-Id header is required");
            }
            SseEmitter stream = transport.handleStreamableHttpMessage(sessionId, body);
            return stream != null ? stream : ResponseEntity.accepted().build();
        } catch (UnknownSessionException unknown) {
            log.warn("Streamable HTTP message for unknown session: {}", sessionId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown MCP session");
        } catch (DuplicateRequestIdException duplicate) {
            log.warn("Refused a Streamable HTTP request on session {}: {}", sessionId, duplicate.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP request id is already in flight");
        } catch (IllegalArgumentException invalid) {
            log.warn("Rejected an invalid Streamable HTTP message on session {}: {}", sessionId, invalid.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid MCP JSON-RPC message");
        } catch (IllegalStateException unavailable) {
            log.warn("Refused a Streamable HTTP message on session {}: {}", sessionId, unavailable.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP transport is unavailable");
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scoped MCP session id is required");
            }
            SseEmitter stream = scopedCapabilities.handleStreamableMessage(capability, sessionId, body);
            return stream != null ? stream : ResponseEntity.accepted().build();
        } catch (ScopeUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        } catch (UnknownSessionException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP session is unavailable");
        } catch (DuplicateRequestIdException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scoped MCP request id is already in flight");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scoped MCP request");
        } catch (IllegalStateException revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped MCP capability is unavailable");
        }
    }

    /**
     * Message endpoint for receiving JSON-RPC messages from clients (SSE Transport).
     *
     * @param sessionId The session ID from the SSE connection
     * @param request The HTTP request containing the JSON-RPC message body
     * @return 202 Accepted (the reply arrives on the SSE stream), 404 for an unknown session,
     *         400 for a body that is not a single JSON-RPC message
     */
    @PostMapping(path = "/message")
    public ResponseEntity<Void> handleMessage(
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {

        String body = readRequestBody(request);

        log.debug("Received MCP message for session {}: {}",
                sessionId,
                body.length() > 200 ? body.substring(0, 200) + "..." : body);

        Mono<Void> result;
        try {
            result = transport.handleMessage(sessionId, body);
        } catch (UnknownSessionException unknown) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException invalid) {
            log.warn("Rejected an invalid MCP message for session {}: {}", sessionId, invalid.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException unavailable) {
            log.warn("Refused an MCP message for session {}: {}", sessionId, unavailable.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (RuntimeException e) {
            log.error("Error handling MCP message for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

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
    }

    /** Receive an SSE-transport message for one short-lived bearer capability. */
    @PostMapping(path = "/scoped/{capability}/message")
    public ResponseEntity<Void> handleScopedMessage(
            @PathVariable String capability,
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {
        String body = readRequestBody(request, ScopedMcpCapabilityService.MAX_SCHEMA_BYTES * 8);
        try {
            scopedCapabilities.handleMessage(capability, sessionId, body)
                    .subscribeOn(Schedulers.boundedElastic()).subscribe(
                            unused -> { },
                            error -> log.warn("Scoped MCP message processing failed"));
            return ResponseEntity.accepted().build();
        } catch (ScopeUnavailableException | UnknownSessionException unavailable) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException revoked) {
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

    static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024; // 10 MB

    private String readRequestBody(HttpServletRequest request) {
        return readRequestBody(request, MAX_REQUEST_BODY_BYTES);
    }

    private String readRequestBody(HttpServletRequest request, int maximumBytes) {
        try {
            if (request.getContentLengthLong() > maximumBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + maximumBytes + " byte limit");
            }
            byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
            if (body.length > maximumBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + maximumBytes + " byte limit");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read MCP request body: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read request body");
        }
    }
}
