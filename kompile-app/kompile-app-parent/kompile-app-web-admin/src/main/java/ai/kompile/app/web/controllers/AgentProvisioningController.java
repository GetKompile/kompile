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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.AgentProvisioningService;
import ai.kompile.app.services.AgentProvisioningService.AgentNotFoundException;
import ai.kompile.app.services.AgentProvisioningService.InvalidAgentIdException;
import ai.kompile.app.services.AgentProvisioningService.ProvisionedAgent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Authenticated admin provisioning API for owner-bound durable private-graph agents. */
@RestController
@RequestMapping("/api/kclaw/instances")
public final class AgentProvisioningController {

    private final AgentProvisioningService service;

    public AgentProvisioningController(AgentProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProvisionedAgent> create(@RequestBody CreateAgentRequest request)
            throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        ProvisionedAgent created = service.create(request.displayName());
        return ResponseEntity.created(
                URI.create("/api/kclaw/instances/" + created.agentId())).body(created);
    }

    @GetMapping
    public List<ProvisionedAgent> list() throws IOException {
        return service.list();
    }

    @GetMapping("/{agentId}")
    public ProvisionedAgent get(@PathVariable String agentId) throws IOException {
        return service.get(agentId);
    }

    @ExceptionHandler(InvalidAgentIdException.class)
    public ResponseEntity<ErrorResponse> invalidAgentId(InvalidAgentIdException invalid) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_agent_id", invalid.getMessage()));
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<ErrorResponse> agentNotFound(AgentNotFoundException missing) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("agent_not_found", missing.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", invalid.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest(HttpMessageNotReadableException invalid) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_request", "Request body contains invalid fields or values"));
    }

    /** Only display text is accepted; all scope and graph selectors are server-owned. */
    public record CreateAgentRequest(String displayName) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown request field: " + field);
        }
    }

    public record ErrorResponse(String code, String message) {
    }
}
