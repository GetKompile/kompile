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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.enforcer.EnforcerDiagnostics;
import ai.kompile.cli.main.chat.ChatSessionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resilience decorator over a {@link JudgeBackend}. It makes real-time LLM judge calls robust in
 * two ways:
 * <ol>
 *   <li><b>Model/provider swap on failure</b> — on a failed or rate-limited call it swaps to a
 *       backup judge backend (a different model/provider) and retries, with a short per-backend
 *       cooldown so a just-failed backend is not hammered.</li>
 *   <li><b>Hard per-call deadline</b> — each underlying call runs under a {@code Future} timeout,
 *       so a single hung judge call cannot stall the agent's dispatch thread for the provider's
 *       (10-minute) HTTP timeout.</li>
 * </ol>
 * When every backend fails it returns the last error sentinel text, or rethrows so callers can
 * apply their configured fallback policy without confusing transport failure with a malformed
 * model verdict.
 */
public class ResilientJudgeBackend implements JudgeBackend {

    /** Notified whenever a swap to a different backend happens (for the judge-health surface). */
    @FunctionalInterface
    public interface SwapSink {
        void onSwap(String fromBackend, String toBackend, String reason);
    }

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final JudgeBackend primary;
    private final List<JudgeBackend> backups;
    private final long deadlineMs;
    private final long cooldownMs;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final List<String> swapHistory = Collections.synchronizedList(new ArrayList<>());
    private volatile SwapSink swapSink;
    private volatile String lastFailureReason = "";

    public ResilientJudgeBackend(JudgeBackend primary, List<JudgeBackend> backups,
                                 long deadlineMs, long cooldownMs) {
        this.primary = primary;
        this.backups = backups != null ? List.copyOf(backups) : List.of();
        this.deadlineMs = deadlineMs;
        this.cooldownMs = cooldownMs > 0 ? cooldownMs : 30_000;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "judge-deadline");
            t.setDaemon(true);
            return t;
        });
    }

    public void setSwapSink(SwapSink sink) {
        this.swapSink = sink;
    }

    public List<String> getSwapHistory() {
        return List.copyOf(swapHistory);
    }

    public boolean hasBackups() {
        return !backups.isEmpty();
    }

    @FunctionalInterface
    private interface BackendCall {
        String call(JudgeBackend backend) throws Exception;
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        return generateWithFallback(
                backend -> backend.generate(userPrompt, systemPrompt));
    }

    @Override
    public String generateJson(
            String userPrompt, String systemPrompt, JsonSchema outputSchema) throws Exception {
        return generateWithFallback(
                backend -> backend.generateJson(userPrompt, systemPrompt, outputSchema));
    }

    private String generateWithFallback(BackendCall call) throws Exception {
        List<JudgeBackend> chain = new ArrayList<>();
        chain.add(primary);
        chain.addAll(backups);

        Exception lastError = null;
        String lastErrorText = null;
        JudgeBackend prevTried = null;

        for (JudgeBackend backend : chain) {
            if (isInCooldown(backend)) {
                continue;
            }
            if (prevTried != null) {
                recordSwap(prevTried, backend, lastError != null ? lastError.getMessage() : "judge error");
            }
            prevTried = backend;
            try {
                String text = callWithDeadline(backend, call);
                if (!isErrorResponse(text)) {
                    lastFailureReason = "";
                    return text;
                }
                lastErrorText = text;
                lastFailureReason = text == null ? "judge returned no response" : text.strip();
                markCooldown(backend, lastFailureReason);
            } catch (Exception e) {
                lastError = e;
                lastFailureReason = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName() : e.getMessage();
                markCooldown(backend, lastFailureReason);
            }
        }

        // Everything failed. Preserve the transport error as a sentinel so callers can apply
        // their fallback policy without feeding it to the verdict JSON parser.
        if (lastErrorText != null) {
            return lastErrorText;
        }
        if (lastError != null) {
            throw lastError;
        }
        lastFailureReason = "all judge backends are in cooldown";
        return "[Error: all judge backends are in cooldown]";
    }

    private String callWithDeadline(JudgeBackend backend, BackendCall call) throws Exception {
        if (deadlineMs <= 0) {
            return call.call(backend);
        }
        Future<String> future = executor.submit(sessionContext.wrapCallable(() -> call.call(backend)));
        try {
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new TimeoutException("judge call exceeded " + deadlineMs + "ms (" + backend.describe() + ")");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    private boolean isInCooldown(JudgeBackend backend) {
        Long until = cooldownUntil.get(backend.describe());
        return until != null && System.currentTimeMillis() < until;
    }

    private void markCooldown(JudgeBackend backend, String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        boolean rateLimited = normalized.contains("429")
                || normalized.contains("rate limit") || normalized.contains("rate_limit")
                || normalized.contains("usage limit") || normalized.contains("quota")
                || normalized.contains("hit your limit")
                || normalized.contains("weekly/monthly limit exhausted");
        long duration = rateLimited ? cooldownMs : Math.min(cooldownMs, 30_000L);
        cooldownUntil.put(backend.describe(), System.currentTimeMillis() + duration);
    }

    private void recordSwap(JudgeBackend from, JudgeBackend to, String reason) {
        String entry = from.describe() + " → " + to.describe() + " (" + reason + ")";
        swapHistory.add(entry);
        EnforcerDiagnostics.alert("[enforcer] judge swap: " + entry);
        SwapSink sink = this.swapSink;
        if (sink != null) {
            try {
                sink.onSwap(from.describe(), to.describe(), reason);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** Detect the error sentinels produced by DirectLlmClient (and a blank response). */
    public static boolean isErrorResponse(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        String normalized = t.toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("[Error:")
                || t.startsWith("[LLM API error")
                || t.startsWith("[Anthropic API error")
                || t.startsWith("[OpenAI Codex API error")
                || t.startsWith("[OpenAI Responses API error")
                || t.startsWith("[Radius API error")
                || t.startsWith("[Kompile serving error")
                || normalized.startsWith("[openai codex connection error")
                || normalized.startsWith("[radius model is not present")
                || (normalized.startsWith("[") && normalized.contains(" connection error:"))
                || (normalized.startsWith("[") && normalized.contains(" api error "))
                || normalized.contains("all judge backends are in cooldown")
                || normalized.contains("hit your limit")
                || normalized.contains("usage limit")
                || normalized.contains("weekly/monthly limit exhausted")
                || normalized.contains("subscription access disabled");
    }

    @Override
    public boolean isAvailable() {
        if (primary != null && primary.isAvailable() && !isInCooldown(primary)) {
            return true;
        }
        for (JudgeBackend b : backups) {
            if (b.isAvailable() && !isInCooldown(b)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void warmUp(String systemPrompt) {
        if (primary != null) {
            primary.warmUp(systemPrompt);
        }
    }

    @Override
    public synchronized void restart() throws Exception {
        cooldownUntil.clear();
        lastFailureReason = "";
        if (primary != null) {
            primary.restart();
        }
        for (JudgeBackend backup : backups) {
            backup.restart();
        }
    }

    @Override
    public synchronized boolean modify(String selection) throws Exception {
        if (primary == null || !primary.modify(selection)) {
            return false;
        }
        cooldownUntil.clear();
        lastFailureReason = "";
        return true;
    }

    @Override
    public String failureReason() {
        if (lastFailureReason != null && !lastFailureReason.isBlank()) {
            return lastFailureReason;
        }
        return primary != null ? primary.failureReason() : "No primary judge backend";
    }

    @Override
    public String describe() {
        String base = primary != null ? primary.describe() : "none";
        if (backups.isEmpty()) {
            return base;
        }
        return base + " (+" + backups.size() + " backup judge" + (backups.size() == 1 ? "" : "s") + ")";
    }

    @Override
    public void close() {
        if (primary != null) {
            primary.close();
        }
        for (JudgeBackend b : backups) {
            b.close();
        }
        executor.shutdownNow();
    }
}
