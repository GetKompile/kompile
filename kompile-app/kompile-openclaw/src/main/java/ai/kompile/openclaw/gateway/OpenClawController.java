/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.openclaw.gateway;

import ai.kompile.openclaw.agent.OpenClawAgentService;
import ai.kompile.openclaw.config.OpenClawConfig;
import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.openclaw.model.OpenClawRequest;
import ai.kompile.openclaw.model.OpenClawResponse;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.HeartbeatScheduler;
import ai.kompile.gateway.core.service.PermissionService;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.react.model.ReActMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/openclaw")
@ConditionalOnBean(OpenClawAgentService.class)
public class OpenClawController {

    private final OpenClawAgentService agentService;
    private final AgentRegistry agentRegistry;
    private final HeartbeatScheduler heartbeatScheduler;
    private final SessionService sessionService;
    private final PermissionService permissionService;
    private final OpenClawConfig config;

    public OpenClawController(
            OpenClawAgentService agentService,
            AgentRegistry agentRegistry,
            @org.springframework.beans.factory.annotation.Qualifier("openclawHeartbeatScheduler") HeartbeatScheduler heartbeatScheduler,
            SessionService sessionService,
            PermissionService permissionService,
            OpenClawConfig config) {
        this.agentService = agentService;
        this.agentRegistry = agentRegistry;
        this.heartbeatScheduler = heartbeatScheduler;
        this.sessionService = sessionService;
        this.permissionService = permissionService;
        this.config = config;
    }

    @PostMapping("/chat")
    public ResponseEntity<OpenClawResponse> chat(@RequestBody OpenClawRequest request) {
        log.debug("Chat request: agent={}, session={}", request.getAgentId(), request.getSessionKey());
        OpenClawResponse response = agentService.execute(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/config")
    public ResponseEntity<OpenClawConfig> getConfig() {
        return ResponseEntity.ok(config);
    }

    @PutMapping("/config")
    public ResponseEntity<OpenClawConfig> updateConfig(@RequestBody OpenClawConfig newConfig) {
        if (newConfig.getWorkspace() != null) {
            config.setWorkspace(newConfig.getWorkspace());
        }
        if (newConfig.getGateway() != null) {
            config.setGateway(newConfig.getGateway());
        }
        if (newConfig.getDefaultAgentId() != null) {
            config.setDefaultAgentId(newConfig.getDefaultAgentId());
        }
        return ResponseEntity.ok(config);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<AgentDefinition>> listAgents() {
        return ResponseEntity.ok(agentRegistry.listAgents());
    }

    @GetMapping("/agents/{name}")
    public ResponseEntity<AgentDefinition> getAgent(@PathVariable String name) {
        return agentRegistry.getAgent(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/agents")
    public ResponseEntity<AgentDefinition> createAgent(@RequestBody AgentDefinition agent) {
        agentRegistry.registerAgent(agent);
        return ResponseEntity.ok(agent);
    }

    @PutMapping("/agents/{name}")
    public ResponseEntity<AgentDefinition> updateAgent(
            @PathVariable String name,
            @RequestBody AgentDefinition agent) {
        if (!agentRegistry.hasAgent(name)) {
            return ResponseEntity.notFound().build();
        }
        agentRegistry.removeAgent(name);
        agentRegistry.registerAgent(agent);
        return ResponseEntity.ok(agent);
    }

    @DeleteMapping("/agents/{name}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String name) {
        agentRegistry.removeAgent(name);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions/{sessionKey}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionKey) {
        sessionService.clearSession(sessionKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionKey}/history")
    public ResponseEntity<?> getSessionHistory(@PathVariable String sessionKey) {
        List<ReActMessage> messages = sessionService.loadSession(sessionKey);
        return ResponseEntity.ok(Map.of(
                "sessionKey", sessionKey,
                "messages", messages,
                "tokenCount", sessionService.estimateTokenCount(sessionKey)
        ));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<String>> listSessions() {
        return ResponseEntity.ok(sessionService.listSessions());
    }

    @GetMapping("/permissions/commands")
    public ResponseEntity<Map<String, Object>> getCommandPermissions() {
        return ResponseEntity.ok(Map.of(
                "allowed", permissionService.getAllowedCommands(),
                "denied", permissionService.getDeniedCommands(),
                "pending", permissionService.getPendingCommands()
        ));
    }

    @PostMapping("/permissions/commands/allow")
    public ResponseEntity<Void> allowCommand(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        if (command != null) {
            permissionService.allowCommand(command);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/permissions/commands/deny")
    public ResponseEntity<Void> denyCommand(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        if (command != null) {
            permissionService.denyCommand(command);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeats")
    public ResponseEntity<Void> createHeartbeat(@RequestBody HeartbeatRequest request) {
        heartbeatScheduler.scheduleHeartbeat(
                request.id(),
                request.cron(),
                request.agentId(),
                request.sessionKey(),
                request.message()
        );
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/heartbeats/{id}")
    public ResponseEntity<Void> cancelHeartbeat(@PathVariable String id) {
        heartbeatScheduler.cancelHeartbeat(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/heartbeats")
    public ResponseEntity<List<HeartbeatScheduler.HeartbeatInfo>> listHeartbeats() {
        return ResponseEntity.ok(heartbeatScheduler.listHeartbeats());
    }

    public record HeartbeatRequest(
            String id,
            String cron,
            String agentId,
            String sessionKey,
            String message
    ) {}
}
