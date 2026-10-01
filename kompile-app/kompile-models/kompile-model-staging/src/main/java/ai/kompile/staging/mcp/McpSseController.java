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

package ai.kompile.staging.mcp;

import ai.kompile.staging.mcp.StagingSseServerTransport.DuplicateRequestIdException;
import ai.kompile.staging.mcp.StagingSseServerTransport.UnknownSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.Map;

/**
 * MCP endpoints of the staging server; {@link StagingSseServerTransport} does the routing.
 *
 * <p>SSE transport (protocol 2024-11-05): {@code GET /mcp/sse} opens the session's stream and
 * {@code POST /mcp/message?sessionId=...} receives the client's messages, answered on that stream.</p>
 *
 * <p>Streamable HTTP transport (protocol 2025-03-26): an initialize request POSTed to
 * {@code /mcp/sse} starts a session, named by the Mcp-Session-Id response header. Every other
 * request gets its own SSE stream carrying its response; notifications and responses are answered
 * with 202 Accepted. {@code GET /mcp/sse} with the session header opens a server stream, and
 * {@code DELETE /mcp/sse} ends the session.</p>
 */
@RestController("stagingMcpSseController")
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnExpression(
        "${kompile.staging.app.enabled:false} && ${kompile.staging.mcp.enabled:true}")
@ConditionalOnProperty(name = "mcp.server.transport", havingValue = "sse", matchIfMissing = true)
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final int LOGGED_BODY_CHARS = 200;

    private final StagingSseServerTransport transport;

    @Autowired
    public McpSseController(StagingSseServerTransport transport) {
        this.transport = transport;
    }

    /**
     * Without a session header, start an SSE-transport session whose first event names the
     * message endpoint. With the Mcp-Session-Id of a Streamable HTTP session, open its server stream.
     */
    @GetMapping(value = "/mcp/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public SseEmitter handleSseGet(
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
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
        try {
            SseEmitter emitter = transport.createConnection();
            log.debug("MCP SSE connection established. Active sessions: {}", transport.getSessionCount());
            return emitter;
        } catch (IllegalStateException closing) {
            log.warn("Refused an MCP SSE connection: {}", closing.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP transport is unavailable");
        }
    }

    /**
     * Streamable HTTP messages. An initialize request always starts a new session; every other
     * message needs the Mcp-Session-Id header of a live session.
     *
     * @return the request's SSE response stream, or 202 Accepted for a notification or a response
     */
    @PostMapping(value = "/mcp/sse", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.ALL_VALUE})
    public Object handleSsePost(
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {
        String body = readRequestBody(request);
        log.debug("Streamable HTTP request (session {}): {}", sessionId, abbreviate(body));

        try {
            if (transport.isInitializeRequest(body)) {
                // Always a new session: a stale session header must not block re-initialization.
                StagingSseServerTransport.StreamableHttpResult result =
                        transport.createStreamableHttpConnection(body);
                response.setHeader(SESSION_HEADER, result.sessionId());
                log.debug("Created Streamable HTTP session {}. Active sessions: {}",
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

    /**
     * SSE-transport messages.
     *
     * @return 202 Accepted (the reply arrives on the session's SSE stream), 404 for an unknown
     *         session, 400 for a body that is not a single JSON-RPC message
     */
    @PostMapping(value = "/mcp/message")
    public ResponseEntity<Void> handleMessage(
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {
        String body = readRequestBody(request);
        log.debug("Received MCP message for session {}: {}", sessionId, abbreviate(body));

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

        // Answer at once: the reply travels on the SSE stream, and blocking here can deadlock
        // when the session handler waits for a write to that stream.
        result.subscribeOn(Schedulers.boundedElastic()).subscribe(
                unused -> { },
                error -> log.warn("Error handling MCP message for session {}: {}", sessionId, error.toString()));
        return ResponseEntity.accepted().build();
    }

    /** End a Streamable HTTP session: 400 without the session header, 404 for an unknown session. */
    @DeleteMapping(value = "/mcp/sse")
    public ResponseEntity<Void> handleDelete(
            @RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("DELETE /mcp/sse called without the Mcp-Session-Id header");
            return ResponseEntity.badRequest().build();
        }
        if (!transport.hasSession(sessionId)) {
            log.warn("DELETE request for unknown MCP session: {}", sessionId);
            return ResponseEntity.notFound().build();
        }
        try {
            transport.terminateSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.error("Error terminating MCP session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Status/health check endpoint.
     */
    @GetMapping(value = "/mcp/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "activeSessions", transport.getSessionCount()
        ));
    }

    private static String abbreviate(String body) {
        return body.length() > LOGGED_BODY_CHARS ? body.substring(0, LOGGED_BODY_CHARS) + "..." : body;
    }

    private static String readRequestBody(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + MAX_REQUEST_BODY_BYTES + " byte limit");
            }
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (body.length > MAX_REQUEST_BODY_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + MAX_REQUEST_BODY_BYTES + " byte limit");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read MCP request body: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read request body");
        }
    }
}
