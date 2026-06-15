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

package ai.kompile.a2a.server;

import ai.kompile.a2a.config.A2AConfigService;
import ai.kompile.a2a.model.AgentCard;
import ai.kompile.a2a.model.JsonRpcRequest;
import ai.kompile.a2a.model.JsonRpcResponse;
import ai.kompile.a2a.service.A2AServerService;
import ai.kompile.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A2A Protocol server endpoint.
 * <p>
 * Exposes kompile agents via the standard A2A protocol:
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} — agent card discovery</li>
 *   <li>{@code POST /a2a} — JSON-RPC 2.0 endpoint ({@code message/send}, {@code tasks/get}, {@code tasks/cancel})</li>
 *   <li>{@code POST /a2a/stream} — SSE streaming for {@code message/stream}</li>
 * </ul>
 * <p>
 * Always loaded; runtime enable/disable is managed via {@link A2AConfigService}.
 */
@RestController
public class A2AServerController {

    private static final Logger logger = LoggerFactory.getLogger(A2AServerController.class);

    private final A2AServerService serverService;
    private final A2AConfigService configService;

    /**
     * Supplier for the list of available agents.
     * Set from kompile-app-main where AgentRegistryService is available.
     */
    private Supplier<List<AgentProvider>> agentListSupplier = Collections::emptyList;

    @Autowired
    public A2AServerController(A2AServerService serverService, A2AConfigService configService) {
        this.serverService = serverService;
        this.configService = configService;
    }

    /**
     * Set the supplier that provides available agents.
     * Called during application startup from the wiring configuration.
     */
    public void setAgentListSupplier(Supplier<List<AgentProvider>> supplier) {
        this.agentListSupplier = supplier;
    }

    /**
     * A2A agent card discovery endpoint.
     * Returns the platform-level agent card advertising all available kompile agents.
     */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getAgentCard(HttpServletRequest request) {
        if (!configService.isServerEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(java.util.Map.of("error", "A2A server is disabled"));
        }
        String baseUrl = buildBaseUrl(request);
        List<AgentProvider> agents = agentListSupplier.get();
        AgentCard card = serverService.getPlatformCard(baseUrl, agents);
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=3600")
                .body(card);
    }

    /**
     * A2A agent card for a specific agent.
     */
    @GetMapping(value = "/a2a/agents/{agentName}/card", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCard> getSpecificAgentCard(
            @PathVariable String agentName, HttpServletRequest request) {
        String baseUrl = buildBaseUrl(request);
        Optional<AgentProvider> agent = agentListSupplier.get().stream()
                .filter(a -> a.getName().equals(agentName))
                .findFirst();

        if (agent.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AgentCard card = serverService.getAgentCard(agent.get(), baseUrl);
        return ResponseEntity.ok(card);
    }

    /**
     * A2A JSON-RPC 2.0 endpoint.
     * Handles: message/send, tasks/get, tasks/cancel
     */
    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpcResponse> handleJsonRpc(
            @RequestBody JsonRpcRequest request,
            @RequestParam(value = "agent", required = false) String agentName) {

        if (!configService.isServerEnabled()) {
            return ResponseEntity.ok(JsonRpcResponse.error(request.getId(),
                    JsonRpcResponse.INTERNAL_ERROR, "A2A server is disabled"));
        }

        logger.debug("A2A JSON-RPC: method={}, id={}", request.getMethod(), request.getId());

        if (request.getJsonrpc() == null || !"2.0".equals(request.getJsonrpc())) {
            return ResponseEntity.badRequest().body(
                    JsonRpcResponse.error(request.getId(),
                            JsonRpcResponse.INVALID_REQUEST,
                            "Invalid JSON-RPC version"));
        }

        JsonRpcResponse response = serverService.handleJsonRpc(request, agentName);
        return ResponseEntity.ok(response);
    }

    /**
     * A2A SSE streaming endpoint.
     * Handles: message/stream via Server-Sent Events
     */
    @PostMapping(value = "/a2a/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleStream(
            @RequestBody JsonRpcRequest request,
            @RequestParam(value = "agent", required = false) String agentName) {

        logger.debug("A2A stream: method={}, id={}", request.getMethod(), request.getId());

        SseEmitter emitter = new SseEmitter(600_000L); // 10 minute timeout

        serverService.handleStream(request, agentName, emitter);

        return emitter;
    }

    /**
     * Health check for the A2A server.
     */
    @GetMapping(value = "/a2a/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "status", serverService.isReady() ? "ready" : "not_ready",
                "protocol", "A2A",
                "protocolVersion", "1.0",
                "agents", agentListSupplier.get().stream()
                        .filter(AgentProvider::isAvailable)
                        .map(AgentProvider::getName)
                        .toList()
        ));
    }

    private String buildBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if ((port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme))) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}
