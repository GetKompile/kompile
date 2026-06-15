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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.ProcessingCapacityTracker;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.TokenBudgetTracker;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.llm.chat.LLMChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handles LLM prompt dispatch with capacity-aware backend selection and fallback.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component
class CrawlLlmDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CrawlLlmDispatcher.class);

    private final ConcurrentHashMap<String, TokenBudgetTracker> tokenTrackers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

    TokenBudgetTracker getOrCreateTracker(String jobId) {
        return tokenTrackers.computeIfAbsent(jobId, k -> new TokenBudgetTracker());
    }

    void registerTracker(String jobId, TokenBudgetTracker tracker) {
        tokenTrackers.put(jobId, tracker);
    }

    void removeTracker(String jobId) {
        tokenTrackers.remove(jobId);
    }

    TokenBudgetTracker getTracker(String jobId) {
        return tokenTrackers.get(jobId);
    }

    boolean hasLlmChat() {
        return llmChat != null;
    }

    String promptWithCapacityFallback(String prompt, String taskType, UnifiedCrawlJob job) {
        ProcessingRouteConfig routeConfig = job.getRequest().getProcessingRoute();

        // Fast path: no fallback configured, use default LLM directly
        if (routeConfig == null || !routeConfig.isFallbackEnabled()
                || processingCapacityTracker == null
                || routeConfig.getBackends() == null || routeConfig.getBackends().isEmpty()) {
            if (llmChat == null) {
                log.error("[Job {}] NO LLM CONFIGURED — cannot perform graph extraction. "
                        + "Configure an LLM provider (OpenAI, Anthropic, or CLI agent) in the application settings.",
                        job.getJobId());
                return null;
            }
            String response = llmChat.prompt(prompt).call().content();
            recordTokenUsage(job, "default", prompt, response);
            return response;
        }

        // Capacity-aware backend selection
        Optional<ProcessingRouteConfig.ProcessingBackend> selected =
                processingCapacityTracker.selectBackend(taskType, routeConfig);

        if (selected.isEmpty()) {
            // All backends at capacity — try the default LLM as last resort
            if (llmChat != null) {
                log.debug("[Job {}] All backends at capacity, falling back to default LLM", job.getJobId());
                String response = llmChat.prompt(prompt).call().content();
                recordTokenUsage(job, "default", prompt, response);
                return response;
            }
            log.warn("[Job {}] All backends at capacity and no default LLM available", job.getJobId());
            return null;
        }

        ProcessingRouteConfig.ProcessingBackend backend = selected.get();
        String backendId = backend.getId();

        try {
            processingCapacityTracker.recordDispatch(backendId, taskType);

            String response;
            switch (backend.getType()) {
                case LOCAL_MODEL:
                    if (llmChat == null) {
                        throw new IllegalStateException("LOCAL_MODEL backend selected but no LLMChat available");
                    }
                    response = llmChat.prompt(prompt).call().content();
                    break;

                case CLI_AGENT:
                    response = promptViaCli(prompt, backend, job);
                    break;

                case API_AGENT:
                    response = promptViaApi(prompt, backend, job);
                    break;

                default:
                    log.warn("[Job {}] Unknown backend type: {}", job.getJobId(), backend.getType());
                    response = null;
            }

            processingCapacityTracker.recordCompletion(backendId, taskType, response != null);
            recordTokenUsage(job, backendId, prompt, response);
            return response;

        } catch (Exception e) {
            processingCapacityTracker.recordCompletion(backendId, taskType, false);
            log.warn("[Job {}] Backend '{}' failed: {}, trying fallback",
                    job.getJobId(), backendId, e.getMessage());

            // Try next available backend on failure
            for (ProcessingRouteConfig.ProcessingBackend fallback : routeConfig.getBackends()) {
                if (fallback.getId().equals(backendId) || !fallback.isEnabled()) continue;
                if (!processingCapacityTracker.canAccept(fallback.getId(), taskType)) continue;

                try {
                    processingCapacityTracker.recordDispatch(fallback.getId(), taskType);
                    String fallbackResponse = dispatchToBackend(prompt, fallback, job);
                    processingCapacityTracker.recordCompletion(fallback.getId(), taskType, fallbackResponse != null);
                    if (fallbackResponse != null) {
                        log.info("[Job {}] Fallback to backend '{}' succeeded (original backend '{}' failed)",
                                job.getJobId(), fallback.getId(), backendId);
                        return fallbackResponse;
                    }
                } catch (Exception fe) {
                    processingCapacityTracker.recordCompletion(fallback.getId(), taskType, false);
                    log.debug("[Job {}] Fallback '{}' also failed: {}", job.getJobId(), fallback.getId(), fe.getMessage());
                }
            }

            // All fallbacks failed, try direct default LLM
            if (llmChat != null) {
                String lastResort = llmChat.prompt(prompt).call().content();
                recordTokenUsage(job, "default", prompt, lastResort);
                return lastResort;
            }
            return null;
        }
    }

    void recordTokenUsage(UnifiedCrawlJob job, String backendId, String prompt, String response) {
        if (job == null) return;
        TokenBudgetTracker tracker = tokenTrackers.get(job.getJobId());
        if (tracker == null) return;
        long inputTokens = prompt != null ? Math.max(1, prompt.length() / 4) : 0;
        long outputTokens = response != null ? Math.max(1, response.length() / 4) : 0;
        tracker.registerBackend(backendId);
        tracker.recordUsage(backendId, inputTokens, outputTokens);
        tracker.publishStats(job);
    }

    String dispatchToBackend(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                             UnifiedCrawlJob job) {
        return switch (backend.getType()) {
            case LOCAL_MODEL -> llmChat != null ? llmChat.prompt(prompt).call().content() : null;
            case CLI_AGENT -> promptViaCli(prompt, backend, job);
            case API_AGENT -> promptViaApi(prompt, backend, job);
        };
    }

    String promptViaCli(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                        UnifiedCrawlJob job) {
        String agentName = backend.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            agentName = "claude-cli";
        }

        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(agentName);
            command.add("-p");
            command.add(prompt);
            pb.command(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Job {}] CLI agent '{}' timed out after 300s", job.getJobId(), agentName);
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[Job {}] CLI agent '{}' exited with code {}", job.getJobId(), agentName, exitCode);
                return null;
            }

            return output;
        } catch (Exception e) {
            log.warn("[Job {}] CLI agent '{}' failed: {}", job.getJobId(), agentName, e.getMessage());
            return null;
        }
    }

    String promptViaApi(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                        UnifiedCrawlJob job) {
        String endpointUrl = backend.getEndpointUrl();
        String apiKey = backend.getApiKey();
        String modelName = backend.getModelName();

        if (endpointUrl == null || endpointUrl.isBlank()) {
            log.warn("[Job {}] API backend '{}' has no endpoint URL", job.getJobId(), backend.getId());
            return null;
        }

        try {
            String requestBody = buildChatCompletionRequest(prompt, modelName);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpointUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(300))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            java.net.http.HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("[Job {}] API backend '{}' returned 429 (rate limited)", job.getJobId(), backend.getId());
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("[Job {}] API backend '{}' returned {}: {}",
                        job.getJobId(), backend.getId(), response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                return null;
            }

            return extractContentFromChatResponse(response.body());
        } catch (Exception e) {
            log.warn("[Job {}] API backend '{}' call failed: {}", job.getJobId(), backend.getId(), e.getMessage());
            return null;
        }
    }

    String buildChatCompletionRequest(String prompt, String modelName) {
        String model = modelName != null ? modelName : "default";
        String escapedPrompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}],\"temperature\":0.0}";
    }

    String extractContentFromChatResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse chat completion response: {}", e.getMessage());
        }
        return null;
    }
}
