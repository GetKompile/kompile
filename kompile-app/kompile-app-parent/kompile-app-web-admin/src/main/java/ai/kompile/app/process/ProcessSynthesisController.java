/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.process;

import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * On-demand agentic synthesis of a fully described process: pick a CLI agent, let it explore the
 * fact sheet's graph through its injected MCP tools, and persist the grounded document on the
 * suggestion. Agent runs take minutes — this is a deliberate user action, not part of mining.
 */
@RestController
@RequestMapping("/api/process/synthesis")
public class ProcessSynthesisController {

    private final AgentProcessSynthesisService synthesisService;
    private final ProcessSuggestionStore suggestionStore;
    private final AgentRegistryService agentRegistry;

    public ProcessSynthesisController(AgentProcessSynthesisService synthesisService,
                                      ProcessSuggestionStore suggestionStore,
                                      AgentRegistryService agentRegistry) {
        this.synthesisService = synthesisService;
        this.suggestionStore = suggestionStore;
        this.agentRegistry = agentRegistry;
    }

    /** CLI agents usable for synthesis (sync delegation does not support API agents). */
    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return agentRegistry.getAvailableAgents().stream()
                .filter(a -> !a.isApiAgent())
                .map(a -> Map.<String, Object>of(
                        "name", a.getName(),
                        "displayName", a.getDisplayName() != null ? a.getDisplayName() : a.getName()))
                .toList();
    }

    /**
     * Run synthesis for one suggestion with the chosen agent. Returns the updated suggestion, or
     * 422 with the reason (agent failure / grounding rejection).
     */
    @PostMapping("/suggestions/{id}")
    public ResponseEntity<?> synthesize(@PathVariable String id,
                                        @RequestParam String agent,
                                        @RequestParam(defaultValue = "0") int timeoutSeconds) {
        AgentProcessSynthesisService.SynthesisResult result =
                synthesisService.synthesize(id, agent, timeoutSeconds);
        if (!result.success()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", result.error()));
        }
        ProcessSuggestion updated = suggestionStore.get(id).orElse(null);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }
}
