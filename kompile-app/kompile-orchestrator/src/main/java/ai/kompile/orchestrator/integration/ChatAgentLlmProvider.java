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
package ai.kompile.orchestrator.integration;

import ai.kompile.orchestrator.api.LlmProvider;
import ai.kompile.orchestrator.integration.cli.CliAgentConfig;
import ai.kompile.orchestrator.integration.cli.CliAgentExecutor;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.llm.LlmSessionStatus;
import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import ai.kompile.orchestrator.model.workflow.ActionType;
import ai.kompile.orchestrator.repository.LlmSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LlmProvider implementation that bridges orchestrator tasks to the AgentChatService.
 * Supports RAG, tool usage, and multiple agent types (Claude, Codex, Gemini, etc.)
 *
 * <p>Execution modes:
 * <ul>
 *   <li>CLI mode (default): Direct process execution via CliAgentExecutor</li>
 *   <li>API mode: HTTP calls to the chat service endpoints</li>
 * </ul>
 */
@Component("chatAgentProvider")
@Slf4j
@RequiredArgsConstructor
public class ChatAgentLlmProvider implements LlmProvider {

    private final LlmSessionRepository sessionRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private CliAgentExecutor cliAgentExecutor;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${kompile.orchestrator.chat.default-agent:claude-cli}")
    private String defaultAgent;

    @Value("${kompile.orchestrator.chat.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${kompile.orchestrator.chat.use-cli-executor:true}")
    private boolean useCliExecutor;

    // Track active sessions for streaming
    private final Map<Long, Sinks.Many<String>> sessionSinks = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> sessionOutputs = new ConcurrentHashMap<>();

    private static final String PROVIDER_ID = "chat-agent";
    private static final String DISPLAY_NAME = "Chat Agent (RAG-enabled)";

    // Pattern for parsing action proposals from LLM output
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\{[^}]*\"action\"\\s*:\\s*\"([^\"]+)\"[^}]*}\\s*```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "```(?:bash|sh|shell)?\\s*(.+?)\\s*```",
            Pattern.DOTALL);

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean isAvailable() {
        // Check CLI availability first if configured to use CLI executor
        if (useCliExecutor && cliAgentExecutor != null) {
            List<CliAgentConfig> availableAgents = cliAgentExecutor.getAvailableAgents();
            if (!availableAgents.isEmpty()) {
                log.debug("CLI agents available: {}", availableAgents.size());
                return true;
            }
        }

        // Fallback: Check if the chat API is reachable
        try {
            String url = getApiBaseUrl() + "/api/chat/agents";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Chat API not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        log.info("Starting chat agent session for orchestrator: {}", request.getOrchestratorInstanceId());

        // Create session entity
        LlmSession session = LlmSession.builder()
                .orchestratorInstanceId(request.getOrchestratorInstanceId())
                .providerId(PROVIDER_ID)
                .providerDisplayName(DISPLAY_NAME)
                .status(LlmSessionStatus.STARTING)
                .initialPrompt(request.getPrompt())
                .systemPrompt(request.getSystemPrompt())
                .workingDirectory(request.getWorkingDirectory())
                .triggerId(request.getTriggerId())
                .taskInstanceId(request.getTaskInstanceId())
                .workflowId(request.getWorkflowId())
                .workflowStepId(request.getWorkflowStepId())
                .modelId(request.getModelId())
                .startTime(LocalDateTime.now())
                .build();

        session = sessionRepository.save(session);
        final Long sessionId = session.getId();

        // Initialize streaming sink and output buffer
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessionSinks.put(sessionId, sink);
        sessionOutputs.put(sessionId, new StringBuilder());

        // Get agent and execution configuration
        String agentName = getAgentName(request);
        boolean skipPermissions = getBooleanParam(request, "skipPermissions", false);

        // Decide execution mode: CLI or API
        boolean useCli = useCliExecutor && cliAgentExecutor != null &&
                         isCliAgentAvailable(agentName);

        try {
            session.markRunning();
            session = sessionRepository.save(session);

            String output;
            if (useCli) {
                // Execute via CLI agent executor (direct process)
                output = executeViaCli(request, sessionId, agentName, skipPermissions, sink);
            } else {
                // Execute via HTTP API
                output = executeViaApi(request, sessionId, agentName, skipPermissions, sink);
            }

            // Update session with results
            session.markCompleted(output);
            session = sessionRepository.save(session);

            log.info("Chat agent session {} completed successfully (mode: {})",
                    sessionId, useCli ? "CLI" : "API");

        } catch (Exception e) {
            log.error("Chat agent session {} failed: {}", sessionId, e.getMessage(), e);
            session.markFailed(e.getMessage());
            session = sessionRepository.save(session);
        } finally {
            // Cleanup
            sessionSinks.remove(sessionId);
            sessionOutputs.remove(sessionId);
        }

        return session;
    }

    /**
     * Check if a CLI agent is available.
     */
    private boolean isCliAgentAvailable(String agentName) {
        if (cliAgentExecutor == null) return false;

        return CliAgentConfig.getByName(agentName)
                .map(CliAgentConfig::checkAvailability)
                .orElse(false);
    }

    /**
     * Execute via CLI agent executor (direct process execution).
     */
    private String executeViaCli(LlmSessionRequest request, Long sessionId,
                                  String agentName, boolean skipPermissions,
                                  Sinks.Many<String> sink) {
        log.info("Executing session {} via CLI agent: {}", sessionId, agentName);

        // Build execution request
        CliAgentExecutor.ExecutionRequest cliRequest = CliAgentExecutor.ExecutionRequest.builder()
                .agentName(agentName)
                .prompt(buildPromptWithSystemContext(request))
                .workingDirectory(request.getWorkingDirectory())
                .skipPermissions(skipPermissions)
                .timeoutSeconds((int) request.getTimeout().toSeconds())
                .orchestratorInstanceId(request.getOrchestratorInstanceId())
                .sessionId(sessionId)
                .build();

        // Execute synchronously
        CliAgentExecutor.ExecutionResult result = cliAgentExecutor.execute(cliRequest);

        // Handle result state
        if (result.getState() == CliAgentExecutor.ProcessState.COMPLETED) {
            return result.getOutput();
        } else if (result.getState() == CliAgentExecutor.ProcessState.TIMEOUT) {
            throw new RuntimeException("CLI agent timed out: " + result.getErrorMessage());
        } else if (result.getState() == CliAgentExecutor.ProcessState.FAILED) {
            throw new RuntimeException("CLI agent failed: " + result.getErrorMessage());
        } else if (result.getState() == CliAgentExecutor.ProcessState.CANCELLED) {
            throw new RuntimeException("CLI agent cancelled");
        }

        return result.getOutput() != null ? result.getOutput() : "";
    }

    /**
     * Execute via HTTP API (original method).
     */
    private String executeViaApi(LlmSessionRequest request, Long sessionId,
                                  String agentName, boolean skipPermissions,
                                  Sinks.Many<String> sink) {
        log.info("Executing session {} via API for agent: {}", sessionId, agentName);

        // Get RAG configuration from request parameters
        boolean enableRag = getBooleanParam(request, "enableRag", false);
        String ragFolderId = getStringParam(request, "ragFolderId", null);
        int ragMaxResults = getIntParam(request, "ragMaxResults", 5);
        double ragSimilarityThreshold = getDoubleParam(request, "ragSimilarityThreshold", 0.5);
        boolean includeKeywordSearch = getBooleanParam(request, "ragIncludeKeywordSearch", true);
        boolean includeSemanticSearch = getBooleanParam(request, "ragIncludeSemanticSearch", true);
        boolean enableTools = getBooleanParam(request, "enableTools", false);

        // Build chat request
        Map<String, Object> chatRequest = new HashMap<>();
        chatRequest.put("agentName", agentName);
        chatRequest.put("message", buildPromptWithSystemContext(request));
        chatRequest.put("workingDirectory", request.getWorkingDirectory());
        chatRequest.put("enableRag", enableRag);
        chatRequest.put("folderId", ragFolderId);
        chatRequest.put("ragMaxResults", ragMaxResults);
        chatRequest.put("ragSimilarityThreshold", ragSimilarityThreshold);
        chatRequest.put("includeKeywordSearch", includeKeywordSearch);
        chatRequest.put("includeSemanticSearch", includeSemanticSearch);
        chatRequest.put("injectMcpTools", enableTools);
        chatRequest.put("skipPermissions", skipPermissions);
        chatRequest.put("timeoutSeconds", (int) request.getTimeout().toSeconds());

        // Execute via HTTP
        return executeChat(chatRequest, sessionId, sink);
    }

    /**
     * Execute chat and collect response synchronously.
     */
    private String executeChat(Map<String, Object> chatRequest, Long sessionId, Sinks.Many<String> sink) {
        StringBuilder output = new StringBuilder();
        CountDownLatch completionLatch = new CountDownLatch(1);

        try {
            String url = getApiBaseUrl() + "/api/chat/execute";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(chatRequest, headers);

            // For SSE, we need to use a different approach
            // For now, let's use a simpler synchronous POST with JSON response
            String syncUrl = getApiBaseUrl() + "/api/chat/execute-sync";

            try {
                HttpHeaders syncHeaders = new HttpHeaders();
                syncHeaders.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> syncEntity = new HttpEntity<>(chatRequest, syncHeaders);

                ResponseEntity<String> response = restTemplate.exchange(
                        syncUrl, HttpMethod.POST, syncEntity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode json = objectMapper.readTree(response.getBody());
                    if (json.has("content")) {
                        output.append(json.get("content").asText());
                    } else if (json.has("output")) {
                        output.append(json.get("output").asText());
                    } else {
                        output.append(response.getBody());
                    }
                    sink.tryEmitNext(output.toString());
                }
            } catch (Exception e) {
                // Fallback: try the SSE endpoint with timeout-based collection
                log.debug("Sync endpoint not available, falling back to SSE collection");
                output.append(executeChatViaSseWithTimeout(chatRequest, sink));
            }

        } catch (Exception e) {
            log.error("Error executing chat: {}", e.getMessage());
            throw new RuntimeException("Failed to execute chat: " + e.getMessage(), e);
        }

        return output.toString();
    }

    /**
     * Execute chat via SSE and collect response with timeout.
     */
    private String executeChatViaSseWithTimeout(Map<String, Object> chatRequest, Sinks.Many<String> sink) {
        StringBuilder output = new StringBuilder();

        try {
            // For environments without WebClient, make a simple POST and read the streamed response
            String url = getApiBaseUrl() + "/api/chat/execute";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(chatRequest, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // Parse SSE events from response body
                String body = response.getBody();
                for (String line : body.split("\n")) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        try {
                            JsonNode json = objectMapper.readTree(data);
                            if (json.has("content")) {
                                String chunk = json.get("content").asText();
                                output.append(chunk);
                                sink.tryEmitNext(chunk);
                            }
                        } catch (Exception e) {
                            // Not JSON, append as-is
                            output.append(data);
                            sink.tryEmitNext(data);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during SSE chat execution: {}", e.getMessage());
        }

        return output.toString();
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        // For continuation messages, start a new session with context
        LlmSession existingSession = sessionRepository.findById(sessionId).orElse(null);
        if (existingSession == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        // Build continuation prompt with previous context
        String contextPrompt = String.format("""
                Previous response:
                %s

                Follow-up message:
                %s
                """, existingSession.getOutput(), message);

        LlmSessionRequest request = LlmSessionRequest.builder()
                .prompt(contextPrompt)
                .orchestratorInstanceId(existingSession.getOrchestratorInstanceId())
                .workingDirectory(existingSession.getWorkingDirectory())
                .build();

        return startSession(request);
    }

    @Override
    public void cancelSession(Long sessionId) {
        log.info("Cancelling chat agent session: {}", sessionId);

        // Cancel CLI process if running
        if (cliAgentExecutor != null) {
            cliAgentExecutor.getRunningProcesses().stream()
                    .filter(p -> sessionId.equals(p.getId()) ||
                                 sessionId.toString().equals(p.getId()))
                    .findFirst()
                    .ifPresent(p -> {
                        log.debug("Cancelling CLI process for session {}", sessionId);
                        cliAgentExecutor.cancelProcess(p.getId());
                    });
        }

        // Complete the sink if active
        Sinks.Many<String> sink = sessionSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
        sessionOutputs.remove(sessionId);

        // Update session status
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.markCancelled();
            sessionRepository.save(session);
        });
    }

    @Override
    public boolean isSessionActive(Long sessionId) {
        return sessionSinks.containsKey(sessionId) ||
               sessionRepository.findById(sessionId)
                       .map(s -> s.getStatus().isActive())
                       .orElse(false);
    }

    @Override
    public Flux<String> streamOutput(Long sessionId) {
        Sinks.Many<String> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            return sink.asFlux();
        }
        return Flux.empty();
    }

    @Override
    public List<String> parseActions(String output) {
        List<String> actions = new ArrayList<>();

        // Parse JSON action blocks
        Matcher actionMatcher = ACTION_PATTERN.matcher(output);
        while (actionMatcher.find()) {
            actions.add(actionMatcher.group(1));
        }

        // Parse shell command blocks
        Matcher commandMatcher = COMMAND_PATTERN.matcher(output);
        while (commandMatcher.find()) {
            actions.add("EXECUTE: " + commandMatcher.group(1).trim());
        }

        return actions;
    }

    @Override
    public Map<String, Object> parseStructuredOutput(String output) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Try to find JSON blocks
            Pattern jsonPattern = Pattern.compile("```json\\s*(.+?)\\s*```", Pattern.DOTALL);
            Matcher matcher = jsonPattern.matcher(output);

            if (matcher.find()) {
                JsonNode json = objectMapper.readTree(matcher.group(1));
                Iterator<Map.Entry<String, JsonNode>> fields = json.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    result.put(field.getKey(), parseJsonValue(field.getValue()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse structured output: {}", e.getMessage());
        }

        return result;
    }

    @Override
    public ActionProposal parseActionProposal(String output) {
        try {
            // Look for action proposal JSON
            Pattern proposalPattern = Pattern.compile(
                    "```json\\s*\\{[^}]*\"actionType\"\\s*:.+?}\\s*```",
                    Pattern.DOTALL);
            Matcher matcher = proposalPattern.matcher(output);

            if (matcher.find()) {
                JsonNode json = objectMapper.readTree(matcher.group());
                String actionTypeStr = getJsonString(json, "actionType", "CUSTOM");
                ActionType actionType;
                try {
                    actionType = ActionType.valueOf(actionTypeStr.toUpperCase().replace("-", "_"));
                } catch (IllegalArgumentException e) {
                    actionType = ActionType.CUSTOM;
                }
                return ActionProposal.builder()
                        .actionType(actionType)
                        .command(getJsonString(json, "command", null))
                        .reasoning(getJsonString(json, "reasoning", null))
                        .confidence(getJsonDouble(json, "confidence", 0.5))
                        .expectedOutcome(getJsonString(json, "expectedOutcome", null))
                        .build();
            }
        } catch (Exception e) {
            log.debug("Failed to parse action proposal: {}", e.getMessage());
        }

        return null;
    }

    @Override
    public int getPriority() {
        return 100; // High priority since it supports RAG
    }

    @Override
    public boolean supportsFileAccess() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        List<ModelInfo> models = new ArrayList<>();
        Set<String> addedAgents = new HashSet<>();

        // First, add available CLI agents
        if (cliAgentExecutor != null) {
            for (CliAgentConfig agent : cliAgentExecutor.getAvailableAgents()) {
                models.add(new ModelInfo(
                        agent.getName(),
                        agent.getDisplayName(),
                        "CLI Agent: " + agent.getDisplayName() +
                                (agent.isMcpSupported() ? " (MCP enabled)" : ""),
                        -1,
                        true));
                addedAgents.add(agent.getName());
            }
        }

        // Then, try to fetch from API (for agents not available locally)
        try {
            String url = getApiBaseUrl() + "/api/chat/agents";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                if (json.isArray()) {
                    for (JsonNode agent : json) {
                        String name = agent.has("name") ? agent.get("name").asText() : "unknown";
                        if (addedAgents.contains(name)) {
                            continue; // Skip if already added from CLI
                        }

                        String displayName = agent.has("displayName") ? agent.get("displayName").asText() : name;
                        boolean available = agent.has("available") && agent.get("available").asBoolean();

                        if (available) {
                            models.add(new ModelInfo(
                                    name,
                                    displayName,
                                    "API Agent: " + displayName,
                                    -1,
                                    true));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to fetch API models: {}", e.getMessage());
        }

        return models;
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    // ==================== Helper Methods ====================

    private String getApiBaseUrl() {
        return "http://localhost:" + serverPort;
    }

    private String buildPromptWithSystemContext(LlmSessionRequest request) {
        StringBuilder prompt = new StringBuilder();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            prompt.append("System Instructions:\n");
            prompt.append(request.getSystemPrompt());
            prompt.append("\n\n---\n\n");
        }

        prompt.append(request.getPrompt());

        return prompt.toString();
    }

    private String getAgentName(LlmSessionRequest request) {
        if (request.getModelId() != null && !request.getModelId().isEmpty()) {
            return request.getModelId();
        }
        return getStringParam(request, "agentName", defaultAgent);
    }

    private String getStringParam(LlmSessionRequest request, String key, String defaultValue) {
        if (request.getParameters() != null && request.getParameters().containsKey(key)) {
            Object value = request.getParameters().get(key);
            return value != null ? value.toString() : defaultValue;
        }
        return defaultValue;
    }

    private boolean getBooleanParam(LlmSessionRequest request, String key, boolean defaultValue) {
        if (request.getParameters() != null && request.getParameters().containsKey(key)) {
            Object value = request.getParameters().get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
        }
        return defaultValue;
    }

    private int getIntParam(LlmSessionRequest request, String key, int defaultValue) {
        if (request.getParameters() != null && request.getParameters().containsKey(key)) {
            Object value = request.getParameters().get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
        }
        return defaultValue;
    }

    private double getDoubleParam(LlmSessionRequest request, String key, double defaultValue) {
        if (request.getParameters() != null && request.getParameters().containsKey(key)) {
            Object value = request.getParameters().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof String) {
                return Double.parseDouble((String) value);
            }
        }
        return defaultValue;
    }

    private Object parseJsonValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            return node.numberValue();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(parseJsonValue(item));
            }
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                map.put(field.getKey(), parseJsonValue(field.getValue()));
            }
            return map;
        }
        return null;
    }

    private String getJsonString(JsonNode json, String key, String defaultValue) {
        if (json.has(key)) {
            return json.get(key).asText(defaultValue);
        }
        return defaultValue;
    }

    private double getJsonDouble(JsonNode json, String key, double defaultValue) {
        if (json.has(key)) {
            return json.get(key).asDouble(defaultValue);
        }
        return defaultValue;
    }

    /**
     * Create an LlmSessionRequest from a TaskDefinition.
     * This is a convenience method for orchestrator integration.
     */
    public static LlmSessionRequest fromTaskDefinition(TaskDefinition task, String prompt, Map<String, String> variables) {
        Map<String, Object> params = new HashMap<>();
        params.put("agentName", task.getAgentName());
        params.put("enableRag", task.isEnableRag());
        params.put("ragFolderId", task.getRagFolderId());
        params.put("ragMaxResults", task.getRagMaxResults());
        params.put("ragSimilarityThreshold", task.getRagSimilarityThreshold());
        params.put("ragIncludeKeywordSearch", task.isRagIncludeKeywordSearch());
        params.put("ragIncludeSemanticSearch", task.isRagIncludeSemanticSearch());
        params.put("enableTools", task.isEnableTools());
        params.put("allowedTools", task.getAllowedToolsList());
        params.put("skipPermissions", task.isSkipPermissions());

        String resolvedPrompt = task.resolvePrompt(variables);
        if (resolvedPrompt == null || resolvedPrompt.isEmpty()) {
            resolvedPrompt = prompt;
        }

        return LlmSessionRequest.builder()
                .prompt(resolvedPrompt)
                .systemPrompt(task.getSystemPrompt())
                .parameters(params)
                .workingDirectory(task.getWorkingDirectory())
                .timeout(task.getTimeout())
                .build();
    }
}
