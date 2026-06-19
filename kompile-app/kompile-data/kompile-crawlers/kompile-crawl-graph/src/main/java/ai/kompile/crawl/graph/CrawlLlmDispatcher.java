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

import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.LlmTranscriptLogger;
import ai.kompile.core.crawl.graph.ProcessingCapacityTracker;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.TokenBudgetTracker;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.llm.chat.LLMChat;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Handles LLM prompt dispatch with capacity-aware backend selection, fallback,
 * per-call timeout protection, circuit breaking, and call-level observability.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component
class CrawlLlmDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CrawlLlmDispatcher.class);

    private final ConcurrentHashMap<String, TokenBudgetTracker> tokenTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    // Shared single-thread executor for wrapping blocking LLM calls with timeouts.
    // Using a cached pool so threads are created on demand and reclaimed after idle.
    private final ExecutorService llmTimeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-timeout-wrapper");
        t.setDaemon(true);
        return t;
    });

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

    @Autowired(required = false)
    private LlmTranscriptLogger transcriptLogger;

    @Autowired(required = false)
    private CliAgentQuotaLedger cliAgentQuotaLedger;

    // ---- Configurable timeouts (synced from CrawlRuntimeConfigManager) ----

    volatile int llmCallTimeoutSeconds = 300;
    volatile int circuitBreakerFailureThreshold = 5;
    volatile int circuitBreakerCooldownSeconds = 60;

    void setLlmCallTimeoutSeconds(int seconds) {
        this.llmCallTimeoutSeconds = Math.max(10, Math.min(1800, seconds));
    }

    void setCircuitBreakerFailureThreshold(int threshold) {
        this.circuitBreakerFailureThreshold = Math.max(1, Math.min(50, threshold));
    }

    void setCircuitBreakerCooldownSeconds(int seconds) {
        this.circuitBreakerCooldownSeconds = Math.max(5, Math.min(600, seconds));
    }

    // ---- Token tracker management ----

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

    /**
     * Returns true when this dispatcher has a wired {@link LlmTranscriptLogger} and will therefore
     * persist a transcript entry for every call that flows through
     * {@link #promptWithCapacityFallback} / {@link #callLlmWithTimeout}.
     *
     * <p>Callers that want belt-and-suspenders transcript recording (e.g.
     * {@link GraphExtractionOrchestrator}) use this to skip their own recording when the
     * dispatcher already handles it, avoiding double entries in the job log.</p>
     */
    boolean hasTranscriptLogger() {
        return transcriptLogger != null;
    }

    // ---- Main dispatch method ----

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
                recordLlmCall(job, "default", taskType, 0, prompt, null,
                        false, false, false, false, "FATAL", "No LLM configured");
                return null;
            }
            return callLlmWithTimeout(prompt, taskType, "default", job);
        }

        // Capacity-aware backend selection with circuit breaker
        Optional<ProcessingRouteConfig.ProcessingBackend> selected =
                selectBackendWithCircuitBreaker(taskType, routeConfig);

        if (selected.isEmpty()) {
            // All backends at capacity or circuit-broken — try the default LLM as last resort
            if (llmChat != null) {
                log.debug("[Job {}] All backends at capacity or circuit-broken, falling back to default LLM",
                        job.getJobId());
                return callLlmWithTimeout(prompt, taskType, "default", job);
            }
            log.warn("[Job {}] All backends at capacity and no default LLM available", job.getJobId());
            recordLlmCall(job, "none", taskType, 0, prompt, null,
                    false, false, false, false, "FATAL", "All backends exhausted");
            return null;
        }

        ProcessingRouteConfig.ProcessingBackend backend = selected.get();
        String backendId = backend.getId();
        long startNanos = System.nanoTime();

        try {
            processingCapacityTracker.recordDispatch(backendId, taskType);

            String response = dispatchToBackendWithTimeout(prompt, backend, job);

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            processingCapacityTracker.recordCompletion(backendId, taskType, response != null);
            recordTokenUsage(job, backendId, prompt, response);

            if (response != null) {
                getCircuitBreaker(backendId).recordSuccess();
                recordLlmCall(job, backendId, taskType, latencyMs, prompt, response,
                        true, false, false, false, null, null);
            } else {
                getCircuitBreaker(backendId).recordFailure();
                recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                        false, false, false, false, "BAD_RESPONSE", "Backend returned null");
            }
            return response;

        } catch (TimeoutException te) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            processingCapacityTracker.recordCompletion(backendId, taskType, false);
            getCircuitBreaker(backendId).recordFailure();
            log.warn("[Job {}] Backend '{}' timed out after {}s",
                    job.getJobId(), backendId, llmCallTimeoutSeconds);
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                    false, true, false, false, "TIMEOUT", "Timed out after " + llmCallTimeoutSeconds + "s");
            // Fall through to fallback chain
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            processingCapacityTracker.recordCompletion(backendId, taskType, false);
            String errorCategory = categorizeError(e.getMessage());
            boolean rateLimited = "RATE_LIMITED".equals(errorCategory);
            boolean quotaExhausted = isQuotaExhaustionError(e.getMessage());

            // Quota-aware escalation
            if (quotaExhausted && backend.isDisableOnQuotaExhaustion()) {
                if (backend.getType() == ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT
                        && cliAgentQuotaLedger != null) {
                    // CLI agents use the shared, time-windowed ledger (auto-recovers) instead of the
                    // permanent per-job kill switch. Open the breaker on the expiring (rate-limited)
                    // path so this job also reroutes immediately and resumes when the window resets.
                    cliAgentQuotaLedger.recordQuotaSignal(backend.getAgentName(), backend);
                    getCircuitBreaker(backendId).recordRateLimited();
                    log.error("[Job {}] CLI agent '{}' quota exhausted — rerouting (recovers in ~{}ms)",
                            job.getJobId(), backend.getAgentName(),
                            cliAgentQuotaLedger.remainingExhaustionMs(backend.getAgentName()));
                } else {
                    getCircuitBreaker(backendId).recordQuotaExhausted();
                    log.error("[Job {}] Backend '{}' quota exhausted — permanently disabled for this job",
                            job.getJobId(), backendId);
                }
            } else if (rateLimited) {
                getCircuitBreaker(backendId).recordRateLimited();
                // Brief backoff before trying fallback to avoid cascading rate limits
                try { Thread.sleep(Math.min(2000, 500 + (long)(Math.random() * 1000))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } else {
                getCircuitBreaker(backendId).recordFailure();
            }

            log.warn("[Job {}] Backend '{}' failed ({}): {}, trying fallback",
                    job.getJobId(), backendId, errorCategory, e.getMessage());
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                    false, false, rateLimited, false, errorCategory, e.getMessage());
        }

        // Try explicit backup backend first (if configured on the primary)
        if (backend.getBackupBackendId() != null) {
            ProcessingRouteConfig.ProcessingBackend backup = routeConfig.getBackends().stream()
                    .filter(b -> b.getId().equals(backend.getBackupBackendId()) && b.isEnabled())
                    .findFirst().orElse(null);
            if (backup != null && !getCircuitBreaker(backup.getId()).isOpen()) {
                String backupResponse = tryFallbackBackend(backup, prompt, taskType, job, backendId);
                if (backupResponse != null) return backupResponse;
            }
        }

        // General fallback chain: try other backends by priority
        for (ProcessingRouteConfig.ProcessingBackend fallback : routeConfig.getBackends()) {
            if (fallback.getId().equals(backendId) || !fallback.isEnabled()) continue;
            // Skip the explicit backup — already tried above
            if (backend.getBackupBackendId() != null && fallback.getId().equals(backend.getBackupBackendId())) continue;
            if (getCircuitBreaker(fallback.getId()).isOpen()) {
                log.debug("[Job {}] Skipping circuit-broken backend '{}'", job.getJobId(), fallback.getId());
                continue;
            }
            if (!processingCapacityTracker.canAccept(fallback.getId(), taskType)) continue;

            String fallbackResponse = tryFallbackBackend(fallback, prompt, taskType, job, backendId);
            if (fallbackResponse != null) return fallbackResponse;
        }

        // All fallbacks failed, try direct default LLM as last resort
        if (llmChat != null) {
            return callLlmWithTimeout(prompt, taskType, "default-last-resort", job);
        }

        recordLlmCall(job, "none", taskType, 0, prompt, null,
                false, false, false, false, "FATAL", "All backends and fallbacks exhausted");
        return null;
    }

    // ---- Timeout-wrapped LLM call ----

    private String callLlmWithTimeout(String prompt, String taskType, String backendId,
                                       UnifiedCrawlJob job) {
        long startNanos = System.nanoTime();
        int timeoutSec = llmCallTimeoutSeconds;
        final String[] sessionHolder = new String[1];
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return llmChat.prompt(prompt).call().content();
                        } finally {
                            // Capture the agent session id on the SAME thread that ran the call
                            // (the LLMChat interface can't return it), then clear the pooled thread.
                            sessionHolder[0] = AgentCallContext.getSessionId();
                            AgentCallContext.clear();
                        }
                    },
                    llmTimeoutExecutor);
            String response = future.get(timeoutSec, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            recordTokenUsage(job, backendId, prompt, response);
            boolean success = response != null && !response.isBlank();
            // Bridge the captured session id onto this caller thread so recordLlmCall picks it up.
            AgentCallContext.setSessionId(sessionHolder[0]);
            try {
                recordLlmCall(job, backendId, taskType, latencyMs, prompt, response,
                        success, false, false, false,
                        success ? null : "BAD_RESPONSE",
                        success ? null : "LLM returned null/empty");
            } finally {
                AgentCallContext.clear();
            }
            return response;
        } catch (TimeoutException te) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("[Job {}] Default LLM '{}' timed out after {}s", job.getJobId(), backendId, timeoutSec);
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                    false, true, false, false, "TIMEOUT",
                    "Default LLM timed out after " + timeoutSec + "s");
            return null;
        } catch (ExecutionException ee) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            String errorCat = categorizeError(msg);
            boolean rateLimited = "RATE_LIMITED".equals(errorCat);
            log.warn("[Job {}] Default LLM '{}' failed: {}", job.getJobId(), backendId, msg);
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                    false, false, rateLimited, false, errorCat, msg);
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, null,
                    false, false, false, false, "UNKNOWN", "Interrupted");
            return null;
        }
    }

    // ---- Backend dispatch with timeout ----

    private String dispatchToBackendWithTimeout(String prompt,
                                                 ProcessingRouteConfig.ProcessingBackend backend,
                                                 UnifiedCrawlJob job) throws TimeoutException, Exception {
        int timeoutSec = llmCallTimeoutSeconds;
        switch (backend.getType()) {
            case LOCAL_MODEL:
                if (llmChat == null) {
                    throw new IllegalStateException("LOCAL_MODEL backend selected but no LLMChat available");
                }
                try {
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(
                            () -> llmChat.prompt(prompt).call().content(),
                            llmTimeoutExecutor);
                    return future.get(timeoutSec, TimeUnit.SECONDS);
                } catch (ExecutionException ee) {
                    throw (ee.getCause() instanceof Exception)
                            ? (Exception) ee.getCause() : new RuntimeException(ee.getCause());
                }

            case CLI_AGENT:
                return promptViaCli(prompt, backend, job, timeoutSec);

            case API_AGENT:
                return promptViaApi(prompt, backend, job, timeoutSec);

            default:
                log.warn("[Job {}] Unknown backend type: {}", job.getJobId(), backend.getType());
                return null;
        }
    }

    String dispatchToBackend(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                             UnifiedCrawlJob job) {
        try {
            return dispatchToBackendWithTimeout(prompt, backend, job);
        } catch (TimeoutException te) {
            log.warn("[Job {}] Backend '{}' timed out", job.getJobId(), backend.getId());
            return null;
        } catch (Exception e) {
            log.warn("[Job {}] Backend '{}' failed: {}", job.getJobId(), backend.getId(), e.getMessage());
            return null;
        }
    }

    // ---- Token usage recording ----

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

    // ---- Per-call observability recording ----

    private void recordLlmCall(UnifiedCrawlJob job, String backendId, String taskType,
                                long latencyMs, String prompt, String response,
                                boolean success, boolean timedOut, boolean rateLimited,
                                boolean circuitBroken, String errorCategory, String errorMessage) {
        if (job == null) return;
        int promptChars = prompt != null ? prompt.length() : 0;
        int responseChars = response != null ? response.length() : 0;
        long inputTokens = Math.max(0, promptChars / 4);
        long outputTokens = Math.max(0, responseChars / 4);

        String truncatedError = errorMessage != null && errorMessage.length() > 200
                ? errorMessage.substring(0, 200) : errorMessage;

        UnifiedCrawlJob.LlmCallRecord record = UnifiedCrawlJob.LlmCallRecord.builder()
                .timestamp(Instant.now())
                .backendId(backendId)
                .taskType(taskType)
                .latencyMs(latencyMs)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .success(success)
                .timedOut(timedOut)
                .rateLimited(rateLimited)
                .circuitBroken(circuitBroken)
                .errorCategory(errorCategory)
                .errorMessage(truncatedError)
                .promptChars(promptChars)
                .responseChars(responseChars)
                .promptText(prompt)
                .responseText(response)
                .build();
        job.recordLlmCall(record);

        // Persist transcript to crawl history for audit
        if (transcriptLogger != null) {
            try {
                transcriptLogger.logTranscript(
                        job.getJobId(), backendId, taskType,
                        prompt, response, latencyMs, success, truncatedError,
                        AgentCallContext.getSessionId());
            } catch (Exception e) {
                log.debug("Failed to persist LLM transcript for job {}: {}",
                        job.getJobId(), e.getMessage());
            }
        }
    }

    // ---- CLI agent dispatch ----

    String promptViaCli(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                        UnifiedCrawlJob job, int timeoutSeconds) {
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

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Job {}] CLI agent '{}' timed out after {}s",
                        job.getJobId(), agentName, timeoutSeconds);
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[Job {}] CLI agent '{}' exited with code {}", job.getJobId(), agentName, exitCode);
                return null;
            }

            if (cliAgentQuotaLedger != null) {
                cliAgentQuotaLedger.recordConsumption(agentName,
                        prompt != null ? prompt.length() / 4L : 0L,
                        output != null ? output.length() / 4L : 0L);
            }
            return output;
        } catch (Exception e) {
            log.warn("[Job {}] CLI agent '{}' failed: {}", job.getJobId(), agentName, e.getMessage());
            return null;
        }
    }

    // ---- API agent dispatch ----

    String promptViaApi(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                        UnifiedCrawlJob job, int timeoutSeconds) {
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
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            java.net.http.HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("[Job {}] API backend '{}' returned 429 (rate limited)", job.getJobId(), backend.getId());
                throw new RuntimeException("Rate limited (429) by API backend '" + backend.getId() + "'");
            }
            if (response.statusCode() != 200) {
                String body = response.body();
                String snippet = body != null ? body.substring(0, Math.min(200, body.length())) : "empty";
                log.warn("[Job {}] API backend '{}' returned {}: {}",
                        job.getJobId(), backend.getId(), response.statusCode(), snippet);
                throw new RuntimeException("API backend '" + backend.getId()
                        + "' returned HTTP " + response.statusCode() + ": " + snippet);
            }

            return extractContentFromChatResponse(response.body());
        } catch (java.net.http.HttpTimeoutException hte) {
            log.warn("[Job {}] API backend '{}' timed out after {}s",
                    job.getJobId(), backend.getId(), timeoutSeconds);
            throw new RuntimeException("Timeout after " + timeoutSeconds + "s: " + hte.getMessage(), hte);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[Job {}] API backend '{}' call failed: {}", job.getJobId(), backend.getId(), e.getMessage());
            throw new RuntimeException("API call failed: " + e.getMessage(), e);
        }
    }

    // ---- JSON helpers ----

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

    // ---- Circuit breaker ----

    private CircuitBreaker getCircuitBreaker(String backendId) {
        return circuitBreakers.computeIfAbsent(backendId,
                id -> new CircuitBreaker(circuitBreakerFailureThreshold, circuitBreakerCooldownSeconds));
    }

    private Optional<ProcessingRouteConfig.ProcessingBackend> selectBackendWithCircuitBreaker(
            String taskType, ProcessingRouteConfig routeConfig) {
        // First try normal selection
        Optional<ProcessingRouteConfig.ProcessingBackend> selected =
                processingCapacityTracker.selectBackend(taskType, routeConfig);
        if (selected.isPresent()) {
            CircuitBreaker cb = getCircuitBreaker(selected.get().getId());
            if (!cb.isOpen() && !cliQuotaExhausted(selected.get())) {
                return selected;
            }
            // Selected backend is circuit-broken or CLI-quota-exhausted, try others
            log.debug("Selected backend '{}' unavailable ({}), trying alternatives",
                    selected.get().getId(),
                    cb.isOpen() ? cb.getStateDescription() : "cli quota exhausted");
        }

        // Try each backend in priority order, skipping circuit-broken and quota-exhausted ones
        if (routeConfig.getBackends() != null) {
            for (ProcessingRouteConfig.ProcessingBackend backend : routeConfig.getBackends()) {
                if (!backend.isEnabled()) continue;
                if (getCircuitBreaker(backend.getId()).isOpen()) continue;
                if (cliQuotaExhausted(backend)) continue;
                if (processingCapacityTracker.canAccept(backend.getId(), taskType)) {
                    return Optional.of(backend);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Proactive quota gate: true when {@code backend} is a CLI agent whose global quota window or
     * request/token cap is currently exhausted. Checked before dispatch so the call is never wasted,
     * and shared across all concurrent jobs via {@link CliAgentQuotaLedger}.
     */
    private boolean cliQuotaExhausted(ProcessingRouteConfig.ProcessingBackend backend) {
        return backend.getType() == ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT
                && cliAgentQuotaLedger != null
                && !cliAgentQuotaLedger.hasBudget(backend.getAgentName(), backend);
    }

    // ---- Fallback helper ----

    private String tryFallbackBackend(ProcessingRouteConfig.ProcessingBackend fallback,
                                       String prompt, String taskType,
                                       UnifiedCrawlJob job, String originalBackendId) {
        long fallbackStart = System.nanoTime();
        try {
            if (cliQuotaExhausted(fallback)) {
                return null; // skip CLI fallback whose quota window/cap is exhausted
            }
            processingCapacityTracker.recordDispatch(fallback.getId(), taskType);
            String fallbackResponse = dispatchToBackendWithTimeout(prompt, fallback, job);
            long fallbackLatency = (System.nanoTime() - fallbackStart) / 1_000_000L;
            processingCapacityTracker.recordCompletion(fallback.getId(), taskType, fallbackResponse != null);

            if (fallbackResponse != null) {
                getCircuitBreaker(fallback.getId()).recordSuccess();
                log.info("[Job {}] Fallback to backend '{}' succeeded (original backend '{}' failed)",
                        job.getJobId(), fallback.getId(), originalBackendId);
                recordTokenUsage(job, fallback.getId(), prompt, fallbackResponse);
                recordLlmCall(job, fallback.getId(), taskType, fallbackLatency, prompt, fallbackResponse,
                        true, false, false, false, null, null);
                if (job != null) {
                    job.recordRerouteEvent(originalBackendId, fallback.getId(),
                            taskType, "primary_failed", 1);
                }
                return fallbackResponse;
            } else {
                getCircuitBreaker(fallback.getId()).recordFailure();
                recordLlmCall(job, fallback.getId(), taskType, fallbackLatency, prompt, null,
                        false, false, false, false, "BAD_RESPONSE", "Fallback returned null");
            }
        } catch (TimeoutException te) {
            long fallbackLatency = (System.nanoTime() - fallbackStart) / 1_000_000L;
            processingCapacityTracker.recordCompletion(fallback.getId(), taskType, false);
            getCircuitBreaker(fallback.getId()).recordFailure();
            recordLlmCall(job, fallback.getId(), taskType, fallbackLatency, prompt, null,
                    false, true, false, false, "TIMEOUT",
                    "Fallback timed out after " + llmCallTimeoutSeconds + "s");
        } catch (Exception fe) {
            long fallbackLatency = (System.nanoTime() - fallbackStart) / 1_000_000L;
            processingCapacityTracker.recordCompletion(fallback.getId(), taskType, false);
            getCircuitBreaker(fallback.getId()).recordFailure();
            recordLlmCall(job, fallback.getId(), taskType, fallbackLatency, prompt, null,
                    false, false, false, false,
                    categorizeError(fe.getMessage()), fe.getMessage());
            log.debug("[Job {}] Fallback '{}' also failed: {}", job.getJobId(), fallback.getId(), fe.getMessage());
        }
        return null;
    }

    // ---- Error categorization ----

    static String categorizeError(String error) {
        if (error == null) return "UNKNOWN";
        String lower = error.toLowerCase();

        if ((lower.contains("cuda") && (lower.contains("out of memory") || lower.contains("oom")))
                || lower.contains("gpu memory") || lower.contains("cublas")) {
            return "GPU_OUT_OF_MEMORY";
        }
        if (lower.contains("outofmemoryerror") || lower.contains("out of memory")
                || lower.contains("oom") || lower.contains("gc overhead")) {
            return "OUT_OF_MEMORY";
        }
        if (lower.contains("rate limit") || lower.contains("429")
                || lower.contains("quota") || lower.contains("capacity")
                || lower.contains("too many requests") || lower.contains("throttl")) {
            return "RATE_LIMITED";
        }
        if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("deadline exceeded")) {
            return "TIMEOUT";
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("invalid api key")
                || lower.contains("authentication")) {
            return "FATAL";
        }
        if (lower.contains("parse") || lower.contains("json") || lower.contains("invalid response")
                || lower.contains("empty response") || lower.contains("malformed")) {
            return "BAD_RESPONSE";
        }
        return "UNKNOWN";
    }

    /**
     * Detects hard quota exhaustion (as opposed to transient rate limiting).
     * Quota exhaustion means the account has hit a billing/plan limit and no further
     * requests will succeed until the quota resets (usually monthly or by upgrade).
     */
    static boolean isQuotaExhaustionError(String error) {
        if (error == null) return false;
        String lower = error.toLowerCase();
        return lower.contains("insufficient_quota") || lower.contains("quota exceeded")
                || lower.contains("billing") || lower.contains("you've hit your limit")
                || lower.contains("plan limit") || lower.contains("usage limit")
                || lower.contains("terminalquotaerror");
    }

    // ---- CircuitBreaker inner class ----

    /**
     * Simple circuit breaker that tracks consecutive failures per backend.
     * Opens (trips) after {@code failureThreshold} consecutive failures and
     * stays open for {@code cooldownSeconds} before allowing a single probe request.
     */
    static class CircuitBreaker {
        private final int failureThreshold;
        private final long cooldownMs;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong openedAtMs = new AtomicLong(0);
        private final AtomicLong totalTrips = new AtomicLong(0);
        /** When true, the backend is permanently disabled for this job (quota exhaustion). */
        private volatile boolean quotaExhausted = false;
        /** Cooldown multiplier for rate-limit events (default 1.0, overridable per backend). */
        private volatile double rateLimitCooldownMultiplier = 3.0;

        CircuitBreaker(int failureThreshold, int cooldownSeconds) {
            this.failureThreshold = Math.max(1, failureThreshold);
            this.cooldownMs = Math.max(5000L, cooldownSeconds * 1000L);
        }

        void setRateLimitCooldownMultiplier(double multiplier) {
            this.rateLimitCooldownMultiplier = Math.max(1.0, multiplier);
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            openedAtMs.set(0);
        }

        void recordFailure() {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold) {
                long now = System.currentTimeMillis();
                if (openedAtMs.compareAndSet(0, now)) {
                    totalTrips.incrementAndGet();
                    log.warn("Circuit breaker opened after {} consecutive failures (cooldown {}s)",
                            failures, cooldownMs / 1000);
                }
            }
        }

        /** Rate-limited: immediately open with an extended cooldown. */
        void recordRateLimited() {
            consecutiveFailures.set(failureThreshold);
            long now = System.currentTimeMillis();
            openedAtMs.set(now);
            totalTrips.incrementAndGet();
            long extendedCooldownMs = (long) (cooldownMs * rateLimitCooldownMultiplier);
            log.warn("Circuit breaker opened due to rate limit (extended cooldown {}s)",
                    extendedCooldownMs / 1000);
        }

        /** Quota exhausted: permanently disable this backend for the current job. */
        void recordQuotaExhausted() {
            quotaExhausted = true;
            consecutiveFailures.set(failureThreshold);
            openedAtMs.set(System.currentTimeMillis());
            totalTrips.incrementAndGet();
            log.error("Backend quota exhausted — permanently disabled for this job");
        }

        boolean isQuotaExhausted() {
            return quotaExhausted;
        }

        boolean isOpen() {
            if (quotaExhausted) return true;
            long openedAt = openedAtMs.get();
            if (openedAt == 0) return false;
            // For rate-limited backends, use the extended cooldown
            long effectiveCooldown = consecutiveFailures.get() >= failureThreshold
                    && rateLimitCooldownMultiplier > 1.0
                    ? (long) (cooldownMs * rateLimitCooldownMultiplier) : cooldownMs;
            long elapsed = System.currentTimeMillis() - openedAt;
            if (elapsed >= effectiveCooldown) {
                if (openedAtMs.compareAndSet(openedAt, 0)) {
                    consecutiveFailures.set(failureThreshold - 1);
                    log.info("Circuit breaker half-open — allowing probe request after {}s cooldown",
                            elapsed / 1000);
                }
                return false;
            }
            return true;
        }

        String getStateDescription() {
            if (quotaExhausted) return "quota_exhausted (permanently disabled)";
            long openedAt = openedAtMs.get();
            if (openedAt == 0) {
                int failures = consecutiveFailures.get();
                return failures > 0
                        ? "closed (" + failures + "/" + failureThreshold + " failures)"
                        : "closed";
            }
            long remaining = cooldownMs - (System.currentTimeMillis() - openedAt);
            return "open (" + Math.max(0, remaining / 1000) + "s remaining, trips=" + totalTrips.get() + ")";
        }
    }
}
