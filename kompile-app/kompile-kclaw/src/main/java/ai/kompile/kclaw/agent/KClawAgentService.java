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
package ai.kompile.kclaw.agent;

import ai.kompile.gateway.core.model.AgentDefinition;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.service.ReActAgentService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class KClawAgentService implements AgentExecutor {

    private final ReActAgentService reActAgentService;
    private final AgentRegistry agentRegistry;
    private final SessionService sessionService;
    private final ToolkitRegistry toolkitRegistry;

    public KClawAgentService(
            ReActAgentService reActAgentService,
            AgentRegistry agentRegistry,
            SessionService sessionService,
            ToolkitRegistry toolkitRegistry) {
        this.reActAgentService = reActAgentService;
        this.agentRegistry = agentRegistry;
        this.sessionService = sessionService;
        this.toolkitRegistry = toolkitRegistry;
    }

    /**
     * Implements {@link AgentExecutor} so that channel adapters in
     * kompile-agent-gateway-core can invoke this service without depending on
     * the kclaw-specific request/response types.
     */
    @Override
    public AgentResponse execute(AgentRequest request) {
        KClawRequest kclawRequest = KClawRequest.builder()
                .agentId(request.getAgentId())
                .sessionKey(request.getSessionKey())
                .message(request.getMessage())
                .stream(request.isStream())
                .metadata(request.getMetadata())
                .build();
        KClawResponse kclawResponse = execute(kclawRequest);
        return AgentResponse.builder()
                .response(kclawResponse.getResponse())
                .sessionKey(kclawResponse.getSessionKey())
                .agentId(kclawResponse.getAgentId())
                .tokenUsage(kclawResponse.getTokenUsage())
                .success(kclawResponse.isSuccess())
                .error(kclawResponse.getError())
                .timestamp(kclawResponse.getTimestamp())
                .toolCalls(kclawResponse.getToolCalls())
                .metadata(kclawResponse.getMetadata())
                .build();
    }

    public KClawResponse execute(KClawRequest request) {
        String agentId = request.getAgentId() != null ? request.getAgentId() : "jarvis";
        String sessionKey = resolveSessionKey(request);

        AgentDefinition agentDef = agentRegistry.getAgent(agentId)
                .orElseGet(agentRegistry::getDefaultAgent);

        if (agentDef == null) {
            return KClawResponse.error("Agent not found: " + agentId);
        }

        try {
            List<ReActMessage> history = sessionService.loadSession(sessionKey);
            Toolkit toolkit = toolkitRegistry.getToolkit(agentDef);

            String systemPrompt = agentDef.getSystemPrompt();
            ReActAgentService.AgentOptions options = new ReActAgentService.AgentOptions(
                    systemPrompt,
                    agentDef.getMaxSteps(),
                    true,
                    null
            );

            ReActResult result = reActAgentService.runSync(request.getMessage(), toolkit, options);

            sessionService.appendMessage(sessionKey, ReActMessage.user(request.getMessage()));
            sessionService.appendMessage(sessionKey, ReActMessage.assistant(result.getAnswer()));

            return KClawResponse.builder()
                    .response(result.getAnswer())
                    .sessionKey(sessionKey)
                    .agentId(agentId)
                    .tokenUsage(result.getTotalUsage())
                    .success(result.isSuccess())
                    .build();

        } catch (Exception e) {
            log.error("Agent execution failed for agent: {}", agentId, e);
            return KClawResponse.error("Agent execution failed: " + e.getMessage());
        }
    }

    public CompletableFuture<KClawResponse> executeAsync(KClawRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request));
    }

    public void compactIfNeeded(String sessionKey, int maxTokens) {
        sessionService.compactSession(sessionKey, maxTokens);
    }

    public void clearSession(String sessionKey) {
        sessionService.clearSession(sessionKey);
    }

    public List<ReActMessage> getSessionHistory(String sessionKey) {
        return sessionService.loadSession(sessionKey);
    }

    private String resolveSessionKey(KClawRequest request) {
        if (request.getSessionKey() != null) {
            return request.getSessionKey();
        }
        return "session:" + UUID.randomUUID().toString().substring(0, 8);
    }
}
