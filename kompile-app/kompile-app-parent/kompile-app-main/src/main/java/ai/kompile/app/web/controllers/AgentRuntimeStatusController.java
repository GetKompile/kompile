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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.CliAgentLLMChat;
import ai.kompile.app.services.agent.CliAgentLLMChat.CliAgentRuntimeStatus;
import ai.kompile.orchestrator.integration.cli.CliAgentExtractionLlmService;
import ai.kompile.orchestrator.integration.cli.CliAgentExtractionLlmServiceRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only endpoint that exposes live subprocess counts for CLI LLM agents.
 *
 * <p>Intended for the in-progress crawl UI to display "N LLM agents running"
 * without touching any mutable state or spawning processes.
 *
 * <p>Endpoint: {@code GET /api/agents/runtime}
 *
 * <p>Response shape:
 * <pre>{@code
 * {
 *   "activeAgent": "OpenCode",          // display name of the configured chat agent, null if none
 *   "agentCommand": "opencode",         // raw CLI command, null if none
 *   "poolSize": 8,                      // configured target pool size
 *   "pooled": 7,                        // warm/idle processes in chat pool
 *   "inFlight": 1,                      // chat processes actively serving a call
 *   "liveTotal": 8,                     // pooled + inFlight
 *   "extractionProviders": [
 *     { "agent": "opencode-cli", "pooled": 4 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Returns HTTP 200 with zero counts even when no agent has been spawned yet.
 * All injected beans are optional so the endpoint stays safe when the CLI agent
 * subsystem is not active.
 */
@RestController
@RequestMapping("/api/agents/runtime")
public class AgentRuntimeStatusController {

    /** The chat-path CLI LLM service (may be absent when no CLI agents are configured). */
    @Autowired(required = false)
    private CliAgentLLMChat cliAgentLLMChat;

    /** The registrar that owns all per-agent extraction subprocess pools. */
    @Autowired(required = false)
    private CliAgentExtractionLlmServiceRegistrar extractionRegistrar;

    /**
     * Returns a live snapshot of running CLI LLM agent subprocess counts.
     * Always returns HTTP 200; all fields are zero/empty when no agents are active.
     */
    @GetMapping
    public ResponseEntity<AgentRuntimeStatusResponse> getRuntimeStatus() {
        // -- Chat-path pool (CliAgentLLMChat) --
        String activeAgent = null;
        String agentCommand = null;
        int poolSize = 0;
        int pooled = 0;
        int inFlight = 0;
        int liveTotal = 0;

        if (cliAgentLLMChat != null) {
            CliAgentRuntimeStatus status = cliAgentLLMChat.getRuntimeStatus();
            activeAgent = status.activeAgent();
            agentCommand = status.agentCommand();
            poolSize = status.poolSize();
            pooled = status.pooled();
            inFlight = status.inFlight();
            liveTotal = status.liveTotal();
        }

        // -- Extraction-path pools (one per registered CLI agent) --
        List<ExtractionProviderStatus> extractionProviders = new ArrayList<>();
        if (extractionRegistrar != null) {
            for (CliAgentExtractionLlmService svc : extractionRegistrar.getRegisteredServices()) {
                extractionProviders.add(
                        new ExtractionProviderStatus(svc.getAgentName(), svc.getPooledCount()));
            }
        }

        return ResponseEntity.ok(new AgentRuntimeStatusResponse(
                activeAgent,
                agentCommand,
                poolSize,
                pooled,
                inFlight,
                liveTotal,
                extractionProviders
        ));
    }

    // ─── response types ────────────────────────────────────────────────────────

    /**
     * Top-level response DTO for {@code GET /api/agents/runtime}.
     *
     * @param activeAgent         display name of the active chat CLI agent, or null
     * @param agentCommand        raw CLI command of the active chat agent, or null
     * @param poolSize            configured chat pool target size
     * @param pooled              idle/warm chat processes in the pool right now
     * @param inFlight            chat processes actively serving a call right now
     * @param liveTotal           pooled + inFlight
     * @param extractionProviders per-provider pool snapshot for extraction agents
     */
    public record AgentRuntimeStatusResponse(
            String activeAgent,
            String agentCommand,
            int poolSize,
            int pooled,
            int inFlight,
            int liveTotal,
            List<ExtractionProviderStatus> extractionProviders
    ) {}

    /**
     * Pool snapshot for one extraction LLM provider.
     *
     * @param agent  agent ID (e.g. "opencode-cli")
     * @param pooled number of warm/idle processes in this provider's pool
     */
    public record ExtractionProviderStatus(String agent, int pooled) {}
}
