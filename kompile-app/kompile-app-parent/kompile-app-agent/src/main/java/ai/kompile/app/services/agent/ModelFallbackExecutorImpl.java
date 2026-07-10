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
package ai.kompile.app.services.agent;

import ai.kompile.app.services.agent.ModelFallbackConfigManager.FallbackEntry;
import ai.kompile.app.services.agent.ModelFallbackConfigManager.ModelFallbackConfig;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.fallback.LlmFallbackExecutor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Model-fallback executor for crawl / graph-constructor LLM calls.
 *
 * <p>Wraps every LLM call in a per-call {@link CompletableFuture} with a hard
 * timeout. On timeout or throttle detection the executor advances along the
 * configured fallback chain via {@link CliAgentModelService#setAgentModel}.</p>
 *
 * <p>All injections are {@code required = false}: when cli-agent infrastructure is
 * absent (e.g. library-only boot contexts) this bean still constructs but falls
 * back to a direct {@code llmChat} call (or a no-op if both are null).</p>
 */
@Service
@ConditionalOnBean(CliAgentModelService.class)
@Slf4j
public class ModelFallbackExecutorImpl implements LlmFallbackExecutor {

    // Named thread pool so it shows up clearly in thread dumps.
    private static final AtomicInteger POOL_INDEX = new AtomicInteger(0);

    private final ExecutorService executor;

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private ModelFallbackConfigManager configManager;

    @Autowired(required = false)
    private CliAgentModelService modelService;

    @Autowired(required = false)
    private AgentRegistryService agentRegistry;

    public ModelFallbackExecutorImpl() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "model-fallback-exec-" + POOL_INDEX.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Per-crawl paid-model call counter (keyed by jobId) for the maxPaidCallsPerCrawl budget cap. */
    private final ConcurrentHashMap<String, AtomicInteger> paidCallsByJob
            = new ConcurrentHashMap<>();

    /** A chain entry is "paid" when its agent is in the configured paidAgents set (e.g. claude-cli). */
    private boolean isPaid(ModelFallbackConfigManager.FallbackEntry entry,
                           ModelFallbackConfigManager.ModelFallbackConfig config) {
        return entry != null && config.paidAgents.contains(entry.agentName());
    }

    private String invokeChainEntry(FallbackEntry entry, String prompt) {
        if (entry != null && entry.agentName() != null && !entry.agentName().isBlank()
                && llmChat instanceof CliAgentLLMChat cliAgentChat) {
            return cliAgentChat.executeAgentForAgent(entry.agentName(), entry.modelId(), prompt, null);
        }
        return llmChat.prompt().user(prompt).call().content();
    }

    // ── LlmFallbackExecutor ──────────────────────────────────────────────────

    @Override
    public String executeWithFallback(String prompt, String backendId,
                                      Predicate<String> responseValidator) {
        // If config manager or model service is absent, fall through to a simple direct call.
        if (configManager == null || modelService == null || llmChat == null) {
            log.debug("[ModelFallback][{}] Fallback infrastructure unavailable — direct LLM call", backendId);
            if (llmChat == null) {
                throw new IllegalStateException("[ModelFallback] LLMChat is not available");
            }
            return llmChat.prompt().user(prompt).call().content();
        }

        ModelFallbackConfig config = configManager.getConfig();

        if (!config.enabled) {
            log.debug("[ModelFallback][{}] Fallback disabled — direct LLM call", backendId);
            return llmChat.prompt().user(prompt).call().content();
        }

        // Build the chain DYNAMICALLY from the live CLI model catalog — free models first (via the
        // canonical discovery∩policy∩health selector), paid agents appended LAST. Never a hardcoded
        // model list. See buildDynamicChain.
        List<FallbackEntry> chain = buildDynamicChain(config, backendId);
        int currentChainIndex = 0;
        int consecutiveTimeouts = 0;
        int totalAttempts = 0;

        // Pin the live model to the head of the chain before the first call — otherwise attempt 1
        // would use whatever model happened to be active from a previous call rather than the
        // best-ranked free model the selector chose.
        if (!chain.isEmpty()) switchToChainEntry(chain.get(0));

        while (totalAttempts < config.maxAttempts) {
            totalAttempts++;
            // Paid-tier per-crawl budget cap: if the active model is paid, count this call and
            // degrade (throw) once the cap is hit, so a crawl can never run away with paid usage.
            FallbackEntry activeEntry = chain.get(currentChainIndex);
            if (isPaid(activeEntry, config)) {
                String jobKey = AgentCallContext.getJobId();
                if (jobKey == null) jobKey = "__no_job__";
                int usedPaid = paidCallsByJob
                        .computeIfAbsent(jobKey, k -> new AtomicInteger())
                        .incrementAndGet();
                if (usedPaid > config.maxPaidCallsPerCrawl) {
                    throw new RuntimeException("[ModelFallback][" + backendId
                            + "] Paid-model call cap reached (" + config.maxPaidCallsPerCrawl
                            + "/crawl) — degrading instead of consuming more paid quota");
                }
            }
            log.debug("[ModelFallback][{}] Attempt {}/{} (chain[{}])",
                    backendId, totalAttempts, config.maxAttempts, currentChainIndex);

            try {
                FallbackEntry entryForAttempt = activeEntry;
                CompletableFuture<String> future = CompletableFuture.supplyAsync(
                        () -> invokeChainEntry(entryForAttempt, prompt),
                        executor
                );

                String response;
                try {
                    response = future.get(config.perCallTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    log.warn("[ModelFallback][{}] Timeout after {}s on attempt {}/{}",
                            backendId, config.perCallTimeoutSeconds, totalAttempts, config.maxAttempts);
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= config.consecutiveTimeoutsBeforeSwitch) {
                        currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                                totalAttempts, "consecutive-timeout");
                        consecutiveTimeouts = 0;
                    }
                    continue;
                }

                // Null / blank response — treat as empty failure.
                if (response == null || response.isBlank()) {
                    log.warn("[ModelFallback][{}] Blank/null response on attempt {}/{}",
                            backendId, totalAttempts, config.maxAttempts);
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= config.consecutiveTimeoutsBeforeSwitch) {
                        currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                                totalAttempts, "blank-response");
                        consecutiveTimeouts = 0;
                    }
                    continue;
                }

                // Check for throttle signal embedded in a successful-looking response.
                if (isThrottleResponse(response, config)) {
                    log.warn("[ModelFallback][{}] Throttle signal in response on attempt {}/{}; switching model",
                            backendId, totalAttempts, config.maxAttempts);
                    applyBackoff(config.throttleBackoffSeconds, backendId);
                    currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                            totalAttempts, "throttle-in-response");
                    consecutiveTimeouts = 0;
                    continue;
                }

                // Caller-supplied validation (e.g. JSON parseability). A quota-truncated response
                // is non-blank and keyword-free, so it would otherwise be returned as "success"
                // and fail to parse downstream with NO failover. Treat a rejected response as a
                // failure and switch to the next model in the chain.
                if (responseValidator != null && !responseValidator.test(response)) {
                    log.warn("[ModelFallback][{}] Response failed caller validation on attempt {}/{} " +
                            "(likely truncated / quota-cut); switching model",
                            backendId, totalAttempts, config.maxAttempts);
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= config.consecutiveTimeoutsBeforeSwitch) {
                        currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                                totalAttempts, "invalid-response");
                        consecutiveTimeouts = 0;
                    }
                    continue;
                }

                // Success.
                if (totalAttempts > 1) {
                    log.info("[ModelFallback][{}] Succeeded on attempt {}/{}", backendId, totalAttempts, config.maxAttempts);
                }
                consecutiveTimeouts = 0;
                return response;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[ModelFallback] Interrupted during LLM call", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                String msg = cause.getMessage();
                log.warn("[ModelFallback][{}] LLM call failed on attempt {}/{}: {}",
                        backendId, totalAttempts, config.maxAttempts, msg);

                if (isThrottleMessage(msg, config)) {
                    log.warn("[ModelFallback][{}] Throttle signal detected — switching model", backendId);
                    applyBackoff(config.throttleBackoffSeconds, backendId);
                    currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                            totalAttempts, "throttle-exception");
                    consecutiveTimeouts = 0;
                } else {
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= config.consecutiveTimeoutsBeforeSwitch) {
                        currentChainIndex = advanceChain(chain, currentChainIndex, config, backendId,
                                totalAttempts, "consecutive-errors");
                        consecutiveTimeouts = 0;
                    }
                }
            }
        }

        throw new RuntimeException(
                "[ModelFallback][" + backendId + "] All " + config.maxAttempts + " attempts exhausted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build the ordered fallback chain DYNAMICALLY from the live CLI model catalog — never a
     * hardcoded model list.
     *
     * <ol>
     *   <li><b>Free rotation</b>: every non-paid agent's {@link CliAgentModelService#selectExtractionModels}
     *       output, one chain entry per model. That selector is discovery ({@code opencode models})
     *       ∩ policy (providerAllow / excludeMarkers — so claude/codex never appear here) ∩ health
     *       (proven-correct models first), so the chain enumerates exactly the models that are
     *       actually available and working, in the right order.</li>
     *   <li><b>Paid last-resort tail</b>: the paid agents declared in the configured chain (e.g.
     *       {@code claude-cli}) appended AFTER the entire free rotation, gated by
     *       {@code paidFallbackEnabled} + the per-crawl paid-call cap in {@link #advanceChain}.</li>
     * </ol>
     *
     * <p>If discovery yields nothing (cold start / CLI absent), falls back to the configured chain.</p>
     */
    List<FallbackEntry> buildDynamicChain(ModelFallbackConfig config, String backendId) {
        List<FallbackEntry> chain = new ArrayList<>();
        Set<String> visitedFreeAgents = new LinkedHashSet<>();
        try {
            if (modelService != null) {
                for (FallbackEntry configured : config.fallbackChain) {
                    String name = configured.agentName();
                    if (name == null || name.isBlank() || config.paidAgents.contains(name)) {
                        continue;
                    }
                    visitedFreeAgents.add(name);
                    appendSelectedModels(chain, name);
                }
                if (agentRegistry != null) {
                    for (AgentProvider agent : agentRegistry.getAllAgents()) {
                        String name = agent.getName();
                        if (name == null || name.isBlank()
                                || config.paidAgents.contains(name)
                                || visitedFreeAgents.contains(name)) {
                            continue;
                        }
                        visitedFreeAgents.add(name);
                        appendSelectedModels(chain, name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ModelFallback][{}] dynamic chain discovery failed ({}) — using configured chain",
                    backendId, e.getMessage());
        }
        int freeCount = chain.size();
        if (freeCount == 0) {
            // No free models discovered (cold start / CLI absent): fall back to the FULL configured
            // chain — which still tries configured free agents BEFORE the paid tail, so we never go
            // paid-first. Local-staging may be present here with a blank model id; execution will
            // no-op/error cleanly if no local staged LLM is available.
            return config.fallbackChain;
        }
        // Paid last-resort tail (configured order), AFTER the entire free rotation.
        for (FallbackEntry e : config.fallbackChain) {
            if (config.paidAgents.contains(e.agentName())) {
                chain.add(e);
            }
        }
        log.info("[ModelFallback][{}] dynamic chain: {} free model(s) across {} free agent(s), then {} paid last-resort entr(ies)",
                backendId, freeCount, visitedFreeAgents.size(), chain.size() - freeCount);
        return chain;
    }

    private void appendSelectedModels(List<FallbackEntry> chain, String agentName) {
        List<String> selected = modelService.selectExtractionModels(agentName);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        for (String modelId : selected) {
            chain.add(new FallbackEntry(agentName, modelId));
        }
    }

    /**
     * Advance to the next chain entry and switch the live model.
     *
     * @return the new (incremented) chain index
     * @throws RuntimeException if the chain is exhausted
     */
    private int advanceChain(List<FallbackEntry> chain,
                              int currentIndex,
                              ModelFallbackConfig config,
                              String backendId,
                              int totalAttempts,
                              String reason) {
        int next = currentIndex + 1;
        if (next >= chain.size()) {
            throw new RuntimeException(
                    "[ModelFallback][" + backendId + "] Fallback chain exhausted after "
                            + totalAttempts + " attempt(s) (reason=" + reason + ")");
        }
        FallbackEntry entry = chain.get(next);
        // Paid tier is gated: when paidFallbackEnabled=false, never switch to a paid model —
        // skip past paid entries to the next free one, or degrade (throw) if none remain.
        while (isPaid(entry, config) && !config.paidFallbackEnabled) {
            log.warn("[ModelFallback][{}] Skipping PAID chain entry agent={} model={} (paidFallbackEnabled=false)",
                    backendId, entry.agentName(), entry.modelId());
            next++;
            if (next >= chain.size()) {
                throw new RuntimeException(
                        "[ModelFallback][" + backendId + "] No non-paid fallback remaining "
                                + "(paid tier disabled) — degrading after " + totalAttempts + " attempt(s)");
            }
            entry = chain.get(next);
        }
        log.warn("[ModelFallback][{}] Switching to agent={} model={} (attempt {}/{}, reason={})",
                backendId, entry.agentName(), entry.modelId(), totalAttempts, config.maxAttempts, reason);
        switchToChainEntry(entry);
        return next;
    }

    private void switchToChainEntry(FallbackEntry entry) {
        try {
            // A blank modelId means "use the agent's own default/current model" — don't push an
            // empty id into the agent (the dynamic chain leaves the paid tail's model unset so the
            // agent's configured default, e.g. haiku, is used rather than a hardcoded version).
            if (entry.modelId() == null || entry.modelId().isBlank()) {
                return;
            }
            boolean switched = modelService.setAgentModel(entry.agentName(), entry.modelId());
            if (!switched) {
                log.warn("[ModelFallback] Agent '{}' not found in registry — cannot switch", entry.agentName());
            }
        } catch (Exception e) {
            log.warn("[ModelFallback] Failed to switch model to agent='{}' model='{}': {}",
                    entry.agentName(), entry.modelId(), e.getMessage());
        }
    }

    /**
     * Returns true if a response looks like a throttle/error rather than a real answer:
     * it must contain a known throttle signal AND be short (< 200 chars) or not start with '{'.
     */
    private boolean isThrottleResponse(String response, ModelFallbackConfig config) {
        if (response == null) return false;
        String lower = response.toLowerCase();
        boolean hasSignal = config.throttleSignals.stream().anyMatch(lower::contains);
        if (!hasSignal) return false;
        // Only flag short responses or ones that don't look like JSON.
        return response.length() < 200 || !response.stripLeading().startsWith("{");
    }

    /** Returns true if the exception message contains a known throttle signal. */
    private boolean isThrottleMessage(String msg, ModelFallbackConfig config) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return config.throttleSignals.stream().anyMatch(lower::contains);
    }

    private void applyBackoff(int seconds, String backendId) {
        if (seconds <= 0) return;
        log.info("[ModelFallback][{}] Backing off {}s before retry", backendId, seconds);
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
