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
 * When every backend fails it returns the last error sentinel text (which parses fail-closed
 * downstream), or rethrows so the enforcer's fallback policy can take over.
 */
public class ResilientJudgeBackend implements JudgeBackend {

    /** Notified whenever a swap to a different backend happens (for the judge-health surface). */
    @FunctionalInterface
    public interface SwapSink {
        void onSwap(String fromBackend, String toBackend, String reason);
    }

    private final JudgeBackend primary;
    private final List<JudgeBackend> backups;
    private final long deadlineMs;
    private final long cooldownMs;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final List<String> swapHistory = Collections.synchronizedList(new ArrayList<>());
    private volatile SwapSink swapSink;

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

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
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
                String text = callWithDeadline(backend, userPrompt, systemPrompt);
                if (!isErrorResponse(text)) {
                    return text;
                }
                lastErrorText = text;
                markCooldown(backend);
            } catch (Exception e) {
                lastError = e;
                markCooldown(backend);
            }
        }

        // Everything failed. Prefer returning an error sentinel (parses fail-closed downstream)
        // so callers that do not catch still get a conservative decision.
        if (lastErrorText != null) {
            return lastErrorText;
        }
        if (lastError != null) {
            throw lastError;
        }
        return "[Error: all judge backends are in cooldown]";
    }

    private String callWithDeadline(JudgeBackend backend, String userPrompt, String systemPrompt) throws Exception {
        if (deadlineMs <= 0) {
            return backend.generate(userPrompt, systemPrompt);
        }
        Future<String> future = executor.submit(() -> backend.generate(userPrompt, systemPrompt));
        try {
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new TimeoutException("judge call exceeded " + deadlineMs + "ms (" + backend.describe() + ")");
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

    private void markCooldown(JudgeBackend backend) {
        cooldownUntil.put(backend.describe(), System.currentTimeMillis() + cooldownMs);
    }

    private void recordSwap(JudgeBackend from, JudgeBackend to, String reason) {
        String entry = from.describe() + " → " + to.describe() + " (" + reason + ")";
        swapHistory.add(entry);
        System.err.println("[enforcer] judge swap: " + entry);
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
    static boolean isErrorResponse(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        return t.startsWith("[Error:")
                || t.startsWith("[LLM API error")
                || t.startsWith("[Anthropic API error");
    }

    @Override
    public boolean isAvailable() {
        if (primary != null && primary.isAvailable()) {
            return true;
        }
        for (JudgeBackend b : backups) {
            if (b.isAvailable()) {
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
