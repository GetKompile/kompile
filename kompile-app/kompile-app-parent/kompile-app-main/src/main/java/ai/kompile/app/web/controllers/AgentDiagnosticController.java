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

import ai.kompile.app.services.agent.AgentProcessDiagnosticService;
import ai.kompile.app.services.agent.AgentProcessDiagnosticService.DiagnosticSummary;
import ai.kompile.app.services.agent.AgentProcessDiagnosticService.FullDiagnosticReport;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for agent management and diagnostics.
 * <p>
 * Provides endpoints to list available agents, check availability,
 * and monitor process execution status.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentDiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(AgentDiagnosticController.class);

    private final AgentRegistryService agentRegistryService;
    private final AgentProcessDiagnosticService diagnosticService;

    public AgentDiagnosticController(AgentRegistryService agentRegistryService,
                                      AgentProcessDiagnosticService diagnosticService) {
        this.agentRegistryService = agentRegistryService;
        this.diagnosticService = diagnosticService;
    }

    /**
     * Get all registered agents. API keys are masked for security.
     */
    @GetMapping
    public ResponseEntity<List<AgentProvider>> getAllAgents() {
        log.debug("Getting all registered agents");
        List<AgentProvider> agents = agentRegistryService.getAllAgents();
        agents.forEach(this::maskApiKey);
        return ResponseEntity.ok(agents);
    }

    /**
     * Get only available agents. API keys are masked for security.
     */
    @GetMapping("/available")
    public ResponseEntity<List<AgentProvider>> getAvailableAgents() {
        log.debug("Getting available agents");
        List<AgentProvider> agents = agentRegistryService.getAvailableAgents();
        agents.forEach(this::maskApiKey);
        return ResponseEntity.ok(agents);
    }

    /**
     * Get the default agent. API key masked for security.
     */
    @GetMapping("/default")
    public ResponseEntity<AgentProvider> getDefaultAgent() {
        log.debug("Getting default agent");
        Optional<AgentProvider> defaultAgent = agentRegistryService.getDefaultAgent();
        defaultAgent.ifPresent(this::maskApiKey);
        return defaultAgent.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get a specific agent by name. API key masked for security.
     */
    @GetMapping("/{name}")
    public ResponseEntity<AgentProvider> getAgent(@PathVariable String name) {
        log.debug("Getting agent: {}", name);
        Optional<AgentProvider> agent = agentRegistryService.getAgent(name);
        agent.ifPresent(this::maskApiKey);
        return agent.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Check availability of a specific agent.
     */
    @PostMapping("/{name}/check")
    public ResponseEntity<Map<String, Object>> checkAgentAvailability(@PathVariable String name) {
        log.debug("Checking availability for agent: {}", name);

        boolean available = agentRegistryService.checkAgentAvailability(name);

        Map<String, Object> response = new HashMap<>();
        response.put("agentName", name);
        response.put("available", available);
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * Refresh availability status for all agents. API keys masked.
     */
    @PostMapping("/refresh")
    public ResponseEntity<List<AgentProvider>> refreshAllAgents() {
        log.info("Refreshing all agent availability status");
        agentRegistryService.checkAllAgentsAvailability();
        List<AgentProvider> agents = agentRegistryService.getAllAgents();
        agents.forEach(this::maskApiKey);
        return ResponseEntity.ok(agents);
    }

    /**
     * Mask API key in agent provider for frontend display.
     */
    private void maskApiKey(AgentProvider agent) {
        if (agent.isApiAgent() && agent.getApiKey() != null && !agent.getApiKey().isEmpty()) {
            agent.setApiKey(agent.getMaskedApiKey());
        }
    }

    /**
     * Get agent count summary.
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Integer>> getAgentCounts() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("total", agentRegistryService.getAllAgents().size());
        counts.put("available", agentRegistryService.getAvailableAgentCount());
        return ResponseEntity.ok(counts);
    }

    // === Diagnostic Endpoints ===

    /**
     * Get diagnostic summary for quick status check.
     */
    @GetMapping("/diagnostics/summary")
    public ResponseEntity<DiagnosticSummary> getDiagnosticSummary() {
        log.debug("Getting diagnostic summary");
        return ResponseEntity.ok(diagnosticService.getSummary());
    }

    /**
     * Get full diagnostic report including process history.
     */
    @GetMapping("/diagnostics/report")
    public ResponseEntity<FullDiagnosticReport> getFullDiagnosticReport() {
        log.debug("Getting full diagnostic report");
        return ResponseEntity.ok(diagnosticService.getFullReport());
    }

    /**
     * Get current active process (if any).
     */
    @GetMapping("/diagnostics/current")
    public ResponseEntity<ProcessStatus> getCurrentProcess() {
        log.debug("Getting current process");
        Optional<ProcessStatus> current = diagnosticService.getCurrentProcess();
        return current.map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Get specific process by ID.
     */
    @GetMapping("/diagnostics/process/{processId}")
    public ResponseEntity<ProcessStatus> getProcess(@PathVariable String processId) {
        log.debug("Getting process: {}", processId);
        Optional<ProcessStatus> process = diagnosticService.getProcess(processId);
        return process.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get process history.
     */
    @GetMapping("/diagnostics/history")
    public ResponseEntity<List<ProcessStatus>> getProcessHistory() {
        log.debug("Getting process history");
        return ResponseEntity.ok(diagnosticService.getHistory());
    }

    /**
     * Clear process history.
     */
    @DeleteMapping("/diagnostics/history")
    public ResponseEntity<Void> clearProcessHistory() {
        log.info("Clearing process history");
        diagnosticService.clearHistory();
        return ResponseEntity.noContent().build();
    }

    /**
     * Check if there's an active process.
     */
    @GetMapping("/diagnostics/active")
    public ResponseEntity<Map<String, Object>> hasActiveProcess() {
        Map<String, Object> response = new HashMap<>();
        response.put("hasActiveProcess", diagnosticService.hasActiveProcess());
        diagnosticService.getCurrentProcess().ifPresent(p -> {
            response.put("processId", p.getId());
            response.put("agentName", p.getAgentName());
            response.put("state", p.getState());
        });
        return ResponseEntity.ok(response);
    }
}
