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

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.CliAgentAvailabilityAdapter;
import ai.kompile.core.crawl.graph.LlmTranscriptLogger;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.crawl.graph.ProcessingCapacityTracker;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ResourceGovernorAdapter;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Optional quota-free local extraction lane: the LLM serving subprocess. A {@code LOCAL_MODEL}
     * backend opts in by setting {@code agentName="serving"}; when this bridge is absent or the
     * subprocess isn't running with a model, the dispatcher falls back to its normal path.
     */
    @Autowired(required = false)
    private LocalServingBackend localServingBackend;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

    @Autowired(required = false)
    private LlmTranscriptLogger transcriptLogger;

    @Autowired(required = false)
    private CliAgentQuotaLedger cliAgentQuotaLedger;

    @Autowired(required = false)
    private CliAgentRunner cliAgentRunner;

    /** Optional cluster-wide backend breaker (Phase 4); null on a single node → {@link ClusterBackendHealth#NOOP}. */
    @Autowired(required = false)
    private ClusterBackendHealth clusterBackendHealth;

    /**
     * Optional hardware-budget signal from the resource governor. When present, heavy LOCAL_MODEL
     * and API_AGENT backends are skipped/deprioritized under memory pressure so CLI backends that
     * do not consume local GPU/RAM are preferred. Null in test slices → safe no-op via default methods.
     */
    @Autowired(required = false)
    private ResourceGovernorAdapter resourceGovernor;

    /**
     * Optional CLI-agent availability and model-list bridge. When present, CLI_AGENT backends
     * are skipped when the agent is unavailable or quota-exhausted at the OS level, and opencode
     * model alternation uses the live model list. Null in test slices → safe no-op via defaults.
     */
    @Autowired(required = false)
    CliAgentAvailabilityAdapter cliAgentAvailability;

    /**
     * Per-job round-robin index for opencode model alternation.
     * Keyed by jobId; incremented on every CLI call for opencode-cli backends so successive
     * extraction batches rotate through all available free models.
     */
    private final ConcurrentHashMap<String, AtomicInteger> opencodeModelIndex = new ConcurrentHashMap<>();

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

        // Fast path: no fallback configured, use default LLM directly.
        // Exception: try the local serving lane first when it is available — this is the
        // availability-gated default-entry for the LOCAL_MODEL/serving backend. The gate
        // is: (a) bridge present + subprocess running + model loaded (isAvailable()), AND
        // (b) the route config (if any) does not explicitly opt out via servingLaneEnabled=false.
        if (routeConfig == null || !routeConfig.isFallbackEnabled()
                || processingCapacityTracker == null
                || routeConfig.getBackends() == null || routeConfig.getBackends().isEmpty()) {
            boolean servingLaneAllowed = routeConfig == null || routeConfig.isServingLaneEnabled();
            if (servingLaneAllowed && localServingBackend != null && localServingBackend.isAvailable()) {
                try {
                    log.debug("[Job {}] Default-entry serving lane: routing to LOCAL_MODEL/serving subprocess",
                            job.getJobId());
                    long t0 = System.nanoTime();
                    final String[] resultHolder = {null};
                    CompletableFuture<String> servingFuture =
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return localServingBackend.generate(prompt);
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            }, llmTimeoutExecutor);
                    String servingResult = servingFuture.get(llmCallTimeoutSeconds, TimeUnit.SECONDS);
                    if (isUsableLlmResponse(servingResult)) {
                        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
                        recordLlmCall(job, "serving", taskType, latencyMs, prompt, servingResult,
                                true, false, false, false, null, null);
                        return servingResult;
                    }
                } catch (TimeoutException te) {
                    log.warn("[Job {}] Default-entry serving lane timed out after {}s; falling back to default LLM",
                            job.getJobId(), llmCallTimeoutSeconds);
                } catch (Exception e) {
                    log.debug("[Job {}] Default-entry serving lane failed ({}); falling back to default LLM",
                            job.getJobId(), e.getMessage());
                }
            }
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
                selectBackendWithCircuitBreaker(taskType, routeConfig, job);

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
            boolean responseOk = isUsableLlmResponse(response);
            processingCapacityTracker.recordCompletion(backendId, taskType, responseOk);
            recordTokenUsage(job, backendId, prompt, response);

            if (responseOk) {
                getCircuitBreaker(backendId).recordSuccess();
                recordLlmCall(job, backendId, taskType, latencyMs, prompt, response,
                        true, false, false, false, null, null);
                return response;
            }

            breakerFailure(backendId);
            recordLlmCall(job, backendId, taskType, latencyMs, prompt, response,
                    false, false, false, false, "BAD_RESPONSE", badLlmResponseMessage("Backend", response));

        } catch (TimeoutException te) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            processingCapacityTracker.recordCompletion(backendId, taskType, false);
            breakerFailure(backendId);
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
                    breakerRateLimited(backendId);
                    log.error("[Job {}] CLI agent '{}' quota exhausted — rerouting (recovers in ~{}ms)",
                            job.getJobId(), backend.getAgentName(),
                            cliAgentQuotaLedger.remainingExhaustionMs(backend.getAgentName()));
                } else {
                    breakerQuotaExhausted(backendId);
                    log.error("[Job {}] Backend '{}' quota exhausted — permanently disabled for this job",
                            job.getJobId(), backendId);
                }
            } else if (rateLimited) {
                breakerRateLimited(backendId);
                // Brief backoff before trying fallback to avoid cascading rate limits
                try { Thread.sleep(Math.min(2000, 500 + (long)(Math.random() * 1000))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } else {
                breakerFailure(backendId);
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
            if (backup != null && !isBackendOpen(backup.getId()) && isCapableOf(backup, "llm")) {
                String backupResponse = tryFallbackBackend(backup, prompt, taskType, job, backendId);
                if (backupResponse != null) return backupResponse;
            }
        }

        // General fallback chain: try other backends by priority
        for (ProcessingRouteConfig.ProcessingBackend fallback : routeConfig.getBackends()) {
            if (fallback.getId().equals(backendId) || !fallback.isEnabled()) continue;
            // Skip the explicit backup — already tried above
            if (backend.getBackupBackendId() != null && fallback.getId().equals(backend.getBackupBackendId())) continue;
            if (isBackendOpen(fallback.getId())) {
                log.debug("[Job {}] Skipping circuit-broken backend '{}'", job.getJobId(), fallback.getId());
                continue;
            }
            if (!isCapableOf(fallback, "llm")) continue;
            if (!processingCapacityTracker.canAccept(fallback, taskType)) continue;

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
        final AgentCallContext.ModelDecision[] decisionHolder = new AgentCallContext.ModelDecision[1];
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return llmChat.prompt(prompt).call().content();
                        } finally {
                            // Capture the agent session id AND the model-routing decision on the SAME
                            // thread that ran the call (the LLMChat interface can't return them), then
                            // clear the pooled thread.
                            sessionHolder[0] = AgentCallContext.getSessionId();
                            decisionHolder[0] = AgentCallContext.getModelDecision();
                            AgentCallContext.clear();
                        }
                    },
                    llmTimeoutExecutor);
            String response = future.get(timeoutSec, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            recordTokenUsage(job, backendId, prompt, response);
            boolean success = isUsableLlmResponse(response);
            // Surface which model handled the turn (+ symptom/latency/timeout/bench) in the crawl UI.
            recordModelRoutingDecision(job, decisionHolder[0]);
            // Bridge the captured session id onto this caller thread so recordLlmCall picks it up.
            AgentCallContext.setSessionId(sessionHolder[0]);
            try {
                recordLlmCall(job, backendId, taskType, latencyMs, prompt, response,
                        success, false, false, false,
                        success ? null : "BAD_RESPONSE",
                        success ? null : badLlmResponseMessage("LLM", response));
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

    /**
     * Record a model-routing decision — which model handled the extraction turn, its classified
     * symptom, latency, the adaptive timeout it was given, and (on failure) how long it was benched —
     * as a {@link UnifiedCrawlJob.TuningDecision} so it surfaces in the crawl UI's per-job decision
     * timeline alongside the batch-size / resource decisions. Captured from the LLM call thread via
     * {@link AgentCallContext}; no-op when no decision was stamped (e.g. non-opencode backends).
     */
    private void recordModelRoutingDecision(UnifiedCrawlJob job, AgentCallContext.ModelDecision decision) {
        if (job == null || decision == null || decision.model() == null) {
            return;
        }
        boolean ok = "OK".equals(decision.outcome());
        String detail = ok
                ? String.format("%s · OK · %d chars · %dms (timeout %ds)",
                        decision.model(), decision.responseChars(), decision.latencyMs(), decision.timeoutSeconds())
                : String.format("%s · %s · %dms (timeout %ds) → benched %ds, de-escalating",
                        decision.model(), decision.outcome(), decision.latencyMs(),
                        decision.timeoutSeconds(), decision.benchSeconds());
        try {
            job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                    .timestamp(Instant.now())
                    .stage("MODEL_ROUTING")
                    .oldValue(0).newValue(0)
                    .direction(ok ? "USE" : "DEESCALATE")
                    .reason(ok ? "model_ok" : decision.outcome().toLowerCase(Locale.ROOT))
                    .detail(detail)
                    .memoryPercent(job.getMemoryUsagePercent().get())
                    .build());
            log.info("[Job {}] MODEL_ROUTING decision: {}", job.getJobId(), detail);
        } catch (Exception e) {
            log.debug("[Job {}] Failed to record model-routing decision: {}", job.getJobId(), e.getMessage());
        }
    }

    // ---- Backend dispatch with timeout ----

    private String dispatchToBackendWithTimeout(String prompt,
                                                 ProcessingRouteConfig.ProcessingBackend backend,
                                                 UnifiedCrawlJob job) throws TimeoutException, Exception {
        int timeoutSec = llmCallTimeoutSeconds;
        switch (backend.getType()) {
            case LOCAL_MODEL:
                // A LOCAL_MODEL backend marked agentName="serving" routes to the out-of-process LLM
                // serving subprocess (quota-free lane); everything else uses the in-process LLMChat.
                if ("serving".equalsIgnoreCase(backend.getAgentName())) {
                    return promptViaServing(prompt, backend, timeoutSec);
                }
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

    /**
     * Dispatch to the out-of-process LLM serving subprocess (quota-free LOCAL_MODEL lane). Throws if
     * the serving bridge is absent or the subprocess isn't running with a model loaded — the circuit
     * breaker and fallback chain then route to the next backend.
     */
    private String promptViaServing(String prompt, ProcessingRouteConfig.ProcessingBackend backend,
                                     int timeoutSec) throws Exception {
        if (localServingBackend == null || !localServingBackend.isAvailable()) {
            throw new IllegalStateException("serving backend '" + backend.getId()
                    + "' selected but the serving subprocess is not available (not running / no model loaded)");
        }
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return localServingBackend.generate(prompt);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, llmTimeoutExecutor);
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (ExecutionException ee) {
            throw (ee.getCause() instanceof Exception)
                    ? (Exception) ee.getCause() : new RuntimeException(ee.getCause());
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

    static boolean isUsableLlmResponse(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        String trimmed = response.stripLeading();
        return !trimmed.startsWith("Error:");
    }

    private static String badLlmResponseMessage(String label, String response) {
        if (response == null) {
            return label + " returned null";
        }
        if (response.isBlank()) {
            return label + " returned empty response";
        }
        String trimmed = response.stripLeading();
        if (trimmed.startsWith("Error:")) {
            return label + " returned error payload: "
                    + (trimmed.length() > 180 ? trimmed.substring(0, 180) : trimmed);
        }
        return label + " returned unusable response";
    }

    // ---- Token usage recording ----

    void recordTokenUsage(UnifiedCrawlJob job, String backendId, String prompt, String response) {
        if (job == null) return;
        TokenBudgetTracker tracker = tokenTrackers.get(job.getJobId());
        if (tracker == null) return;
        long inputTokens = prompt != null ? Math.max(1, prompt.length() / 4) : 0;
        long outputTokens = response != null && !response.isBlank() ? Math.max(1, response.length() / 4) : 0;
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

        // Route through the shared persistent CLI-agent subprocess infra (the same
        // AgentSubprocessExecutor the LLMChat pool uses): it builds the proper command,
        // strips ANSI/TUI escapes, and parses the agent's JSON stream — instead of a raw
        // one-shot `<agent> -p <prompt>` spawn that captures the agent's banner/help.
        if (cliAgentRunner != null) {
            try {
                String output = cliAgentRunner.run(agentName, prompt, timeoutSeconds);
                if (output != null && cliAgentQuotaLedger != null) {
                    cliAgentQuotaLedger.recordConsumption(agentName,
                            prompt != null ? prompt.length() / 4L : 0L,
                            output.length() / 4L);
                }
                return output;
            } catch (Exception e) {
                log.warn("[Job {}] CLI agent '{}' (runner) failed: {}",
                        job.getJobId(), agentName, e.getMessage());
                return null;
            }
        }

        // Fallback: legacy one-shot spawn, used only when the CliAgentRunner bean is
        // absent (e.g. the crawl module running outside the app-main agent runtime).
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
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

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
        } catch (HttpTimeoutException hte) {
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

    private ClusterBackendHealth clusterHealth() {
        return clusterBackendHealth != null ? clusterBackendHealth : ClusterBackendHealth.NOOP;
    }

    /** Wiring/test seam for the optional cluster backend breaker. */
    void setClusterBackendHealth(ClusterBackendHealth clusterBackendHealth) {
        this.clusterBackendHealth = clusterBackendHealth;
    }

    /** A backend is unavailable if its local breaker is open OR the cluster has tripped it (Phase 4, advisory). */
    boolean isBackendOpen(String backendId) {
        return getCircuitBreaker(backendId).isOpen() || clusterHealth().isOpen(backendId);
    }

    /** Record a local backend failure on both the per-JVM breaker and (when wired) the cluster-wide breaker. */
    private void breakerFailure(String backendId) {
        getCircuitBreaker(backendId).recordFailure();
        clusterHealth().record(backendId, ClusterBackendHealth.Event.FAILURE);
    }

    private void breakerRateLimited(String backendId) {
        getCircuitBreaker(backendId).recordRateLimited();
        clusterHealth().record(backendId, ClusterBackendHealth.Event.RATE_LIMITED);
    }

    private void breakerQuotaExhausted(String backendId) {
        getCircuitBreaker(backendId).recordQuotaExhausted();
        clusterHealth().record(backendId, ClusterBackendHealth.Event.QUOTA_EXHAUSTED);
    }

    private Optional<ProcessingRouteConfig.ProcessingBackend> selectBackendWithCircuitBreaker(
            String taskType, ProcessingRouteConfig routeConfig, UnifiedCrawlJob job) {
        // ── ResourceGovernor memory gate ────────────────────────────────────────
        // When the host is under heavy memory pressure, skip LOCAL_MODEL and API_AGENT backends
        // (they require local GPU/heap) and prefer CLI backends that run out-of-process.
        boolean memoryThrottled = resourceGovernor != null && resourceGovernor.shouldThrottleHeavyMemory();
        String memPressureReason = memoryThrottled && resourceGovernor != null
                ? resourceGovernor.memoryPressureReason() : null;
        if (memoryThrottled) {
            log.debug("[Job {}] Memory pressure throttle active ({}): skipping LOCAL_MODEL/API_AGENT backends",
                    job != null ? job.getJobId() : "?", memPressureReason);
        }

        // First try normal selection
        Optional<ProcessingRouteConfig.ProcessingBackend> selected =
                processingCapacityTracker.selectBackend(taskType, routeConfig);
        if (selected.isPresent()) {
            ProcessingRouteConfig.ProcessingBackend candidate = selected.get();
            CircuitBreaker cb = getCircuitBreaker(candidate.getId());
            boolean open = isBackendOpen(candidate.getId()); // local OR cluster-wide (Phase 4)
            boolean cliUnavailable = isCliAgentUnavailable(candidate);
            boolean memorySkip = memoryThrottled && isLocalOrApiBackend(candidate);
            boolean capableSkip = !isCapableOf(candidate, "llm");
            if (!open && !cliQuotaExhausted(candidate) && !cliUnavailable && !memorySkip && !capableSkip) {
                // Selected backend is usable; apply opencode model alternation if applicable
                maybeAlternateOpencodeModel(candidate, job, "selected");
                return selected;
            }
            // Selected backend cannot be used — log why and try alternatives
            String skipReason = open ? cb.getStateDescription()
                    : cliUnavailable ? "cli agent unavailable"
                    : memorySkip ? "memory-throttled (prefers CLI)"
                    : capableSkip ? "no llm capability"
                    : "cli quota exhausted";
            log.debug("[Job {}] Selected backend '{}' unavailable ({}), trying alternatives",
                    job != null ? job.getJobId() : "?", candidate.getId(), skipReason);
            if (job != null && (memorySkip || cliUnavailable || capableSkip)) {
                job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                        .timestamp(Instant.now())
                        .stage("LLM_ROUTING")
                        .oldValue(0).newValue(0)
                        .direction("SKIP")
                        .reason(memorySkip ? "memory_pressure" : capableSkip ? "no_llm_capability" : "cli_unavailable")
                        .detail("Skipped backend '" + candidate.getId() + "': " + skipReason
                                + (memPressureReason != null ? " [" + memPressureReason + "]" : ""))
                        .memoryPercent(job.getMemoryUsagePercent().get())
                        .build());
            }
        }

        // Try each backend in priority order, skipping circuit-broken, quota-exhausted,
        // unavailable CLI agents, and memory-throttled local/API backends.
        ProcessingRouteConfig.ProcessingBackend chosenFallback = null;
        List<String> skippedIds = new ArrayList<>();
        if (routeConfig.getBackends() != null) {
            for (ProcessingRouteConfig.ProcessingBackend backend : routeConfig.getBackends()) {
                if (!backend.isEnabled()) continue;
                if (isBackendOpen(backend.getId())) { skippedIds.add(backend.getId() + "(breaker)"); continue; }
                if (cliQuotaExhausted(backend)) { skippedIds.add(backend.getId() + "(quota)"); continue; }
                if (isCliAgentUnavailable(backend)) { skippedIds.add(backend.getId() + "(unavailable)"); continue; }
                if (memoryThrottled && isLocalOrApiBackend(backend)) {
                    skippedIds.add(backend.getId() + "(mem-throttle)");
                    continue;
                }
                if (!isCapableOf(backend, "llm")) {
                    skippedIds.add(backend.getId() + "(no-llm-cap)");
                    continue;
                }
                if (processingCapacityTracker.canAccept(backend, taskType)) {
                    chosenFallback = backend;
                    break;
                }
            }
        }
        if (chosenFallback != null) {
            if (job != null && (!skippedIds.isEmpty() || memoryThrottled)) {
                String detail = "Routed to '" + chosenFallback.getId() + "'"
                        + (skippedIds.isEmpty() ? "" : "; skipped: " + String.join(", ", skippedIds))
                        + (memPressureReason != null ? " [" + memPressureReason + "]" : "");
                job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                        .timestamp(Instant.now())
                        .stage("LLM_ROUTING")
                        .oldValue(0).newValue(0)
                        .direction("REROUTE")
                        .reason(memoryThrottled ? "memory_pressure" : "backend_skip")
                        .detail(detail)
                        .memoryPercent(job.getMemoryUsagePercent().get())
                        .build());
                log.info("[Job {}] LLM routing decision: {}", job.getJobId(), detail);
            }
            maybeAlternateOpencodeModel(chosenFallback, job, "fallback");
            return Optional.of(chosenFallback);
        }
        return Optional.empty();
    }

    /**
     * True when a backend is a LOCAL_MODEL or API_AGENT (consumes local GPU/heap resources).
     * CLI_AGENT backends run out-of-process and are preferred under memory pressure.
     */
    private boolean isLocalOrApiBackend(ProcessingRouteConfig.ProcessingBackend backend) {
        return backend.getType() == ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL
                || backend.getType() == ProcessingRouteConfig.ProcessingBackendType.API_AGENT;
    }

    /**
     * True when a CLI_AGENT backend's underlying agent is reported unavailable by the
     * {@link CliAgentAvailabilityAdapter}. Non-CLI backends always return false.
     */
    private boolean isCliAgentUnavailable(ProcessingRouteConfig.ProcessingBackend backend) {
        if (backend.getType() != ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT) return false;
        if (cliAgentAvailability == null) return false;
        String agentName = backend.getAgentName();
        return agentName != null && !cliAgentAvailability.isAvailable(agentName);
    }

    /**
     * For opencode-cli backends: rotate through available free models across consecutive calls
     * (round-robin per job). claude/codex backends are excluded from extraction per mandate.
     * A DECISION record is emitted when the model actually changes.
     */
    private void maybeAlternateOpencodeModel(ProcessingRouteConfig.ProcessingBackend backend,
                                              UnifiedCrawlJob job, String selectionContext) {
        if (cliAgentAvailability == null || job == null) return;
        if (backend.getType() != ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT) return;
        String agentName = backend.getAgentName();
        if (agentName == null) return;
        String lower = agentName.toLowerCase(Locale.ROOT);
        // Only alternate for opencode; never touch claude or codex (paid agents, must not be used for extraction)
        if (!lower.contains("opencode")) return;
        if (lower.contains("claude") || lower.contains("codex")) return;

        List<String> models = cliAgentAvailability.availableModels(agentName);
        if (models == null || models.isEmpty()) return;
        // Free-only: never alternate onto paid/flagship models (hard mandate — only free opencode models
        // alternate). Mirrors the rotation filter in CliAgentLLMChat so decision records reflect reality.
        models = models.stream()
                .filter(m -> {
                    String l = m.toLowerCase(Locale.ROOT);
                    return !(l.contains("claude") || l.contains("codex") || l.contains("gpt")
                            || l.contains("opus") || l.contains("sonnet") || l.contains("-pro"));
                })
                .collect(Collectors.toList());
        if (models.isEmpty()) return;

        AtomicInteger idx = opencodeModelIndex.computeIfAbsent(job.getJobId(), k -> new AtomicInteger(0));
        int next = idx.getAndIncrement() % models.size();
        String targetModel = models.get(next);
        String currentModel = cliAgentAvailability.currentModel(agentName);
        if (targetModel.equals(currentModel)) return;

        boolean switched = cliAgentAvailability.setModel(agentName, targetModel);
        if (switched) {
            String detail = "opencode model alternation (" + selectionContext + "): "
                    + (currentModel != null ? currentModel : "?") + " → " + targetModel
                    + " (round-robin " + next + "/" + models.size() + ", job=" + job.getJobId() + ")";
            log.info("[Job {}] {}", job.getJobId(), detail);
            job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                    .timestamp(Instant.now())
                    .stage("LLM_MODEL_ALTERNATION")
                    .oldValue(0).newValue(next)
                    .direction("ROTATE")
                    .reason("opencode_round_robin")
                    .detail(detail)
                    .memoryPercent(job.getMemoryUsagePercent().get())
                    .build());
        }
    }

    /** Remove the per-job opencode alternation index when a job finishes. */
    void clearOpencodeModelIndex(String jobId) {
        opencodeModelIndex.remove(jobId);
    }

    /**
     * Returns {@code true} when {@code backend} can handle the given {@code capability}
     * (e.g. {@code "llm"}, {@code "vlm"}).
     *
     * <p>An empty or null capabilities list means the backend handles <em>all</em> task types
     * (backward-compatible default).  A non-empty list is treated as an explicit allowlist:
     * the backend is eligible only when the required capability is present in that list.</p>
     */
    boolean isCapableOf(ProcessingRouteConfig.ProcessingBackend backend, String capability) {
        List<String> caps = backend.getCapabilities();
        return caps == null || caps.isEmpty() || caps.contains(capability);
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
            boolean fallbackOk = isUsableLlmResponse(fallbackResponse);
            processingCapacityTracker.recordCompletion(fallback.getId(), taskType, fallbackOk);

            if (fallbackOk) {
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
                recordLlmCall(job, fallback.getId(), taskType, fallbackLatency, prompt, fallbackResponse,
                        false, false, false, false, "BAD_RESPONSE", badLlmResponseMessage("Fallback", fallbackResponse));
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
