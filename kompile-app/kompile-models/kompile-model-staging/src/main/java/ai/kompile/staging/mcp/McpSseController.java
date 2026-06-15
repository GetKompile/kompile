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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController("stagingMcpSseController")
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    @Autowired
    private StagingSseServerTransport transport;

    /**
     * SSE endpoint - establishes SSE connection (protocol 2024-11-05).
     */
    @GetMapping(value = "/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSseGet() {
        log.debug("New SSE connection request");
        return transport.createConnection();
    }

    /**
     * Streamable HTTP endpoint - POST to /mcp/sse (protocol 2025-03-26).
     */
    @PostMapping(value = "/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSsePost(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            @RequestBody String body) {

        if (sessionId != null && transport.hasSession(sessionId)) {
            log.debug("Streamable HTTP message for existing session: {}", sessionId);
            return transport.handleStreamableHttpMessage(sessionId, body);
        } else {
            log.debug("New Streamable HTTP connection");
            StagingSseServerTransport.StreamableHttpResult result = transport.createStreamableHttpConnection(body);
            return result.emitter();
        }
    }

    /**
     * Message endpoint - receives JSON-RPC messages (protocol 2024-11-05).
     */
    @PostMapping(value = "/mcp/message")
    public ResponseEntity<Void> handleMessage(
            @RequestParam("sessionId") String sessionId,
            @RequestBody String body) {

        log.debug("Received message for session: {}", sessionId);

        if (!transport.hasSession(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        transport.handleMessage(sessionId, body).block();
        return ResponseEntity.ok().build();
    }

    /**
     * Terminate session endpoint (Streamable HTTP protocol 2025-03-26).
     */
    @DeleteMapping(value = "/mcp/sse")
    public ResponseEntity<Void> handleDelete(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {

        if (sessionId != null) {
            transport.terminateSession(sessionId);
        }
        return ResponseEntity.ok().build();
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
}
