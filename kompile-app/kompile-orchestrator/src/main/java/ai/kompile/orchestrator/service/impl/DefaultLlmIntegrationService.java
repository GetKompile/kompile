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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.LlmProvider;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.event.LlmSessionEvent;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.llm.LlmSessionStatus;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import ai.kompile.orchestrator.model.workflow.ActionType;
import ai.kompile.orchestrator.repository.LlmSessionRepository;
import ai.kompile.orchestrator.repository.LlmTriggerRepository;
import ai.kompile.orchestrator.service.registry.LlmProviderRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of LlmIntegrationService.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultLlmIntegrationService implements LlmIntegrationService {

    private final LlmProviderRegistry providerRegistry;
    private final LlmSessionRepository sessionRepository;
    private final LlmTriggerRepository triggerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, LlmTrigger> triggerCache = new ConcurrentHashMap<>();

    // Pattern for parsing JSON from LLM output
    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```|\\{[\\s\\S]*\\}");

    // ==================== Session Management ====================

    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        LlmProvider provider = providerRegistry.getDefault()
                .orElseThrow(() -> new IllegalStateException("No default LLM provider configured"));
        return startSession(provider.getId(), request);
    }

    @Override
    public LlmSession startSession(String providerId, LlmSessionRequest request) {
        LlmProvider provider = providerRegistry.get(providerId)
                .orElseThrow(() -> new IllegalArgumentException("LLM provider not found: " + providerId));

        if (!provider.isAvailable()) {
            throw new IllegalStateException("LLM provider is not available: " + providerId);
        }

        log.info("Starting LLM session with provider {} for orchestrator {}",
                providerId, request.getOrchestratorInstanceId());

        // Create initial session record
        LlmSession session = LlmSession.builder()
                .orchestratorInstanceId(request.getOrchestratorInstanceId())
                .providerId(providerId)
                .providerDisplayName(provider.getDisplayName())
                .initialPrompt(request.getPrompt())
                .systemPrompt(request.getSystemPrompt())
                .workingDirectory(request.getWorkingDirectory())
                .triggerId(request.getTriggerId())
                .taskInstanceId(request.getTaskInstanceId())
                .workflowId(request.getWorkflowId())
                .workflowStepId(request.getWorkflowStepId())
                .modelId(request.getModelId())
                .status(LlmSessionStatus.STARTING)
                .build();

        session = sessionRepository.save(session);

        // Publish start event
        eventPublisher.publishEvent(LlmSessionEvent.started(this, session));

        try {
            // Delegate to provider
            LlmSession startedSession = provider.startSession(request);

            // Update with provider results
            session.setProcessId(startedSession.getProcessId());
            session.setStatus(startedSession.getStatus());
            // Copy output if provider already completed (e.g., synchronous providers)
            if (startedSession.getOutput() != null) {
                session.setOutput(startedSession.getOutput());
            }
            if (startedSession.getEndTime() != null) {
                session.setEndTime(startedSession.getEndTime());
            }
            session = sessionRepository.save(session);

            return session;
        } catch (Exception e) {
            log.error("Failed to start LLM session: {}", e.getMessage(), e);
            session.markFailed(e.getMessage());
            session = sessionRepository.save(session);
            eventPublisher.publishEvent(LlmSessionEvent.failed(this, session, e.getMessage()));
            throw new RuntimeException("Failed to start LLM session", e);
        }
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        LlmSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (!session.isActive()) {
            throw new IllegalStateException("Session is not active: " + sessionId);
        }

        LlmProvider provider = providerRegistry.get(session.getProviderId())
                .orElseThrow(() -> new IllegalStateException("Provider not found: " + session.getProviderId()));

        log.info("Sending message to session {}", sessionId);

        return provider.sendMessage(sessionId, message);
    }

    @Override
    public void cancelSession(Long sessionId) {
        LlmSession session = sessionRepository.findById(sessionId)
                .orElse(null);

        if (session == null || !session.isActive()) {
            return;
        }

        LlmProvider provider = providerRegistry.get(session.getProviderId()).orElse(null);
        if (provider != null) {
            try {
                provider.cancelSession(sessionId);
            } catch (Exception e) {
                log.warn("Error cancelling session with provider: {}", e.getMessage());
            }
        }

        session.markCancelled();
        sessionRepository.save(session);
        eventPublisher.publishEvent(LlmSessionEvent.cancelled(this, session));

        log.info("Cancelled LLM session: {}", sessionId);
    }

    @Override
    public Optional<LlmSession> getSession(Long sessionId) {
        return sessionRepository.findById(sessionId);
    }

    @Override
    public List<LlmSession> getSessions(String orchestratorInstanceId) {
        return sessionRepository.findByOrchestratorInstanceId(orchestratorInstanceId);
    }

    @Override
    public List<LlmSession> getActiveSessions(String orchestratorInstanceId) {
        return sessionRepository.findActiveByOrchestratorInstanceId(orchestratorInstanceId);
    }

    @Override
    public boolean isSessionActive(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .map(LlmSession::isActive)
                .orElse(false);
    }

    @Override
    public Flux<String> streamOutput(Long sessionId) {
        LlmSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        LlmProvider provider = providerRegistry.get(session.getProviderId())
                .orElseThrow(() -> new IllegalStateException("Provider not found: " + session.getProviderId()));

        return provider.streamOutput(sessionId);
    }

    // ==================== Trigger Management ====================

    @Override
    public void registerTrigger(LlmTrigger trigger) {
        triggerCache.put(trigger.getTriggerId(), trigger);
        triggerRepository.save(trigger);
        log.info("Registered LLM trigger: {} ({})", trigger.getTriggerId(), trigger.getName());
    }

    @Override
    public void unregisterTrigger(String triggerId) {
        triggerCache.remove(triggerId);
        triggerRepository.deleteById(triggerId);
        log.info("Unregistered LLM trigger: {}", triggerId);
    }

    @Override
    public Optional<LlmTrigger> getTrigger(String triggerId) {
        LlmTrigger cached = triggerCache.get(triggerId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return triggerRepository.findById(triggerId);
    }

    @Override
    public List<LlmTrigger> getAllTriggers() {
        return triggerRepository.findAll();
    }

    @Override
    public void enableTrigger(String triggerId) {
        triggerRepository.findById(triggerId).ifPresent(trigger -> {
            trigger.setEnabled(true);
            triggerRepository.save(trigger);
            triggerCache.put(triggerId, trigger);
            log.info("Enabled LLM trigger: {}", triggerId);
        });
    }

    @Override
    public void disableTrigger(String triggerId) {
        triggerRepository.findById(triggerId).ifPresent(trigger -> {
            trigger.setEnabled(false);
            triggerRepository.save(trigger);
            triggerCache.put(triggerId, trigger);
            log.info("Disabled LLM trigger: {}", triggerId);
        });
    }

    // ==================== Manual Invocation ====================

    @Override
    public LlmSession invoke(String prompt, String orchestratorInstanceId) {
        LlmProvider provider = providerRegistry.getDefault()
                .orElseThrow(() -> new IllegalStateException("No default LLM provider configured"));
        return invoke(provider.getId(), prompt, orchestratorInstanceId);
    }

    @Override
    public LlmSession invoke(String providerId, String prompt, String orchestratorInstanceId) {
        LlmSessionRequest request = LlmSessionRequest.builder()
                .prompt(prompt)
                .orchestratorInstanceId(orchestratorInstanceId)
                .timeout(properties.getLlm().getDefaultTimeout())
                .build();
        return startSession(providerId, request);
    }

    @Override
    public LlmSession invokeWithContext(String prompt, Map<String, Object> context, String orchestratorInstanceId) {
        String resolvedPrompt = resolveTemplate(prompt, context);
        return invoke(resolvedPrompt, orchestratorInstanceId);
    }

    private String resolveTemplate(String template, Map<String, Object> context) {
        if (template == null || context == null || context.isEmpty()) {
            return template;
        }

        Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
        Matcher matcher = pattern.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = context.get(varName);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    // ==================== Provider Management ====================

    @Override
    public void registerProvider(LlmProvider provider) {
        providerRegistry.register(provider);
        log.info("Registered LLM provider: {} ({})", provider.getId(), provider.getDisplayName());
    }

    @Override
    public Optional<LlmProvider> getProvider(String providerId) {
        return providerRegistry.get(providerId);
    }

    @Override
    public List<LlmProvider> getAllProviders() {
        return providerRegistry.getAll();
    }

    @Override
    public Optional<LlmProvider> getDefaultProvider() {
        return providerRegistry.getDefault();
    }

    // ==================== Response Parsing ====================

    @Override
    public ActionProposal parseActionProposal(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }

        // First, try to get parsed proposal from the best available provider
        Optional<LlmProvider> provider = providerRegistry.getBestAvailable();
        if (provider.isPresent()) {
            ActionProposal parsed = provider.get().parseActionProposal(output);
            if (parsed != null) {
                return parsed;
            }
        }

        // Fall back to basic parsing
        return parseBasicActionProposal(output);
    }

    private ActionProposal parseBasicActionProposal(String output) {
        // Try to parse JSON from output
        Map<String, Object> parsed = parseStructuredOutput(output);
        if (parsed.isEmpty()) {
            return null;
        }

        try {
            ActionType type = ActionType.CUSTOM;
            String typeStr = (String) parsed.get("type");
            if (typeStr != null) {
                try {
                    type = ActionType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Keep CUSTOM
                }
            }

            return ActionProposal.builder()
                    .actionType(type)
                    .command((String) parsed.get("command"))
                    .expectedOutcome((String) parsed.get("expectedOutcome"))
                    .reasoning((String) parsed.getOrDefault("reasoning", (String) parsed.get("description")))
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse action proposal: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> parseStructuredOutput(String output) {
        if (output == null || output.isEmpty()) {
            return Map.of();
        }

        Matcher matcher = JSON_PATTERN.matcher(output);
        while (matcher.find()) {
            String json = matcher.group(1);
            if (json == null) {
                json = matcher.group();
            }
            json = json.trim();

            if (json.startsWith("{")) {
                try {
                    return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                    // Try next match
                }
            }
        }

        return Map.of();
    }

    /**
     * Load triggers from database into cache on startup.
     */
    public void loadTriggersFromDatabase() {
        List<LlmTrigger> triggers = triggerRepository.findByEnabled(true);
        for (LlmTrigger trigger : triggers) {
            triggerCache.put(trigger.getTriggerId(), trigger);
        }
        log.info("Loaded {} LLM triggers from database", triggers.size());
    }

    /**
     * Get enabled triggers by type.
     */
    public List<LlmTrigger> getEnabledTriggersByType(ai.kompile.orchestrator.model.llm.LlmTriggerType type) {
        return triggerRepository.findEnabledByTriggerType(type);
    }

    /**
     * Get triggers for a specific state.
     */
    public List<LlmTrigger> getTriggersForState(String stateId) {
        return triggerRepository.findEnabledByTypeAndState(
                ai.kompile.orchestrator.model.llm.LlmTriggerType.ON_STATE_ENTER, stateId);
    }

    /**
     * Get triggers for a specific task.
     */
    public List<LlmTrigger> getTriggersForTask(String taskId) {
        return triggerRepository.findEnabledByTypeAndTask(
                ai.kompile.orchestrator.model.llm.LlmTriggerType.ON_TASK_COMPLETE, taskId);
    }

    @Override
    public List<LlmTrigger> getTriggers(String orchestratorInstanceId) {
        // Return all triggers as they can apply to any orchestrator
        return new ArrayList<>(triggerCache.values());
    }

    @Override
    public List<String> getAvailableProviders() {
        return new ArrayList<>(providerRegistry.getAllProviderIds());
    }

    @Override
    public List<LlmSession> getSessionHistory(String orchestratorInstanceId, int limit) {
        return sessionRepository.findTopByOrchestratorInstanceIdOrderByStartTimeDesc(
                orchestratorInstanceId, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Override
    public Flux<String> streamResponse(Long sessionId) {
        return streamOutput(sessionId);
    }
}
