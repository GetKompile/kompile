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

import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Global, cross-job ledger of CLI-agent quota availability.
 *
 * <p>Replaces the old per-job, JVM-lifetime-permanent {@code CircuitBreaker.recordQuotaExhausted()}.
 * A single shared instance is consulted by {@link CrawlLlmDispatcher} <em>before</em> dispatching to a
 * CLI agent, so one crawl job's discovery of exhaustion protects every other concurrent job, and the
 * agent automatically becomes available again when its quota recovers — no JVM restart needed.</p>
 *
 * <p><b>Two gates, whichever trips first:</b></p>
 * <ul>
 *   <li><b>Time window</b> — a quota error ({@link #recordQuotaSignal}) marks the agent exhausted until
 *       {@code now + window} (default ~5h, matching rolling CLI usage limits). It auto-recovers when the
 *       window elapses. Rapid re-exhaustion within {@code minHealthyMs} applies exponential backoff to
 *       the window (capped) to avoid thrashing at the boundary.</li>
 *   <li><b>Request/token cap</b> — {@link #recordConsumption} tallies requests and tokens in a rolling
 *       window; once a configured cap is reached, {@link #hasBudget} returns {@code false}
 *       <em>proactively</em>, before an error is ever returned.</li>
 * </ul>
 *
 * <p>Caps may be set globally (via crawl runtime config) or per-backend (via
 * {@link ProcessingBackend#getMaxRequestsPerQuotaWindow()} etc.). The clock is injectable for
 * deterministic unit tests. All methods are safe for concurrent use.</p>
 */
@Component
public class CliAgentQuotaLedger {

    private static final Logger log = LoggerFactory.getLogger(CliAgentQuotaLedger.class);

    private static final double MAX_BACKOFF_MULTIPLIER = 4.0;

    private final LongSupplier clock;

    // ---- Global defaults (synced from CrawlRuntimeConfigManager.applyRuntimeConfig) ----
    private volatile long quotaWindowMs = 18_000_000L; // 5 hours
    private volatile long minHealthyMs = 60_000L;      // hysteresis: healthy gap before backoff resets
    private volatile long maxRequestsPerWindow = 0;    // 0 = no global request cap
    private volatile long maxTokensPerWindow = 0;      // 0 = no global token cap

    private final Map<String, QuotaState> states = new ConcurrentHashMap<>();

    /** Production constructor — wall-clock. */
    public CliAgentQuotaLedger() {
        this(System::currentTimeMillis);
    }

    /** Test constructor — injectable clock for deterministic window/backoff assertions. */
    CliAgentQuotaLedger(LongSupplier clock) {
        this.clock = clock;
    }

    // ---- Config setters (no Spring @Value; driven by CrawlRuntimeConfigManager JSON) ----

    public void setQuotaWindowMs(long ms) { if (ms > 0) this.quotaWindowMs = ms; }
    public void setMinHealthyMs(long ms) { this.minHealthyMs = Math.max(0, ms); }
    public void setMaxRequestsPerWindow(long n) { this.maxRequestsPerWindow = Math.max(0, n); }
    public void setMaxTokensPerWindow(long n) { this.maxTokensPerWindow = Math.max(0, n); }

    // ---- Queries ----

    /** True if the agent currently has quota budget (not time-exhausted and under request/token caps). */
    public boolean hasBudget(String agentName) {
        return hasBudget(agentName, null);
    }

    /**
     * As {@link #hasBudget(String)} but honoring per-backend cap/window overrides when present.
     */
    public boolean hasBudget(String agentName, ProcessingBackend backend) {
        QuotaState s = state(agentName);
        long now = clock.getAsLong();
        if (s.isTimeExhausted(now)) {
            return false;
        }
        s.rollWindowIfNeeded(now, effectiveWindowMs(backend));
        long reqCap = effectiveRequestCap(backend);
        if (reqCap > 0 && s.requestsInWindow.get() >= reqCap) {
            return false;
        }
        long tokCap = effectiveTokenCap(backend);
        if (tokCap > 0 && s.tokensInWindow.get() >= tokCap) {
            return false;
        }
        return true;
    }

    /** Remaining ms until the agent's time-window exhaustion clears, or 0 if not time-exhausted. */
    public long remainingExhaustionMs(String agentName) {
        QuotaState s = state(agentName);
        return Math.max(0, s.exhaustedUntilMs.get() - clock.getAsLong());
    }

    // ---- Mutations ----

    /** Record a completed CLI request and its token consumption against the rolling count window. */
    public void recordConsumption(String agentName, long inputTokens, long outputTokens) {
        QuotaState s = state(agentName);
        long now = clock.getAsLong();
        s.rollWindowIfNeeded(now, quotaWindowMs);
        s.requestsInWindow.incrementAndGet();
        s.tokensInWindow.addAndGet(Math.max(0, inputTokens) + Math.max(0, outputTokens));
    }

    /** Convenience overload — no backend-specific window. */
    public void recordQuotaSignal(String agentName) {
        recordQuotaSignal(agentName, null);
    }

    /**
     * A hard quota error was observed for this agent — exhaust it for the (possibly backed-off) window.
     * Cross-job: every concurrent job that consults {@link #hasBudget} will now skip this agent until
     * the window elapses.
     */
    public void recordQuotaSignal(String agentName, ProcessingBackend backend) {
        QuotaState s = state(agentName);
        long now = clock.getAsLong();
        long sinceLast = now - s.lastSignalMs.get();
        double prevMult = s.backoffMultiplierX100.get() / 100.0;
        double mult = (s.lastSignalMs.get() > 0 && sinceLast < minHealthyMs)
                ? Math.min(MAX_BACKOFF_MULTIPLIER, prevMult * 2.0) // rapid re-exhaustion → back off
                : 1.0;                                              // healthy gap → reset to base
        s.backoffMultiplierX100.set((long) (mult * 100));
        long windowMs = (long) (effectiveWindowMs(backend) * mult);
        s.exhaustedUntilMs.set(now + windowMs);
        s.lastSignalMs.set(now);
        log.warn("CLI agent '{}' quota-exhausted for {}ms (x{} backoff window)",
                key(agentName), windowMs, mult);
    }

    /** Observability snapshot of per-agent quota state (for the status endpoint). */
    public Map<String, Object> snapshot() {
        long now = clock.getAsLong();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, QuotaState> e : states.entrySet()) {
            QuotaState s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exhausted", s.exhaustedUntilMs.get() > now);
            m.put("remainingExhaustionMs", Math.max(0, s.exhaustedUntilMs.get() - now));
            m.put("requestsInWindow", s.requestsInWindow.get());
            m.put("tokensInWindow", s.tokensInWindow.get());
            m.put("backoffMultiplier", s.backoffMultiplierX100.get() / 100.0);
            out.put(e.getKey(), m);
        }
        return out;
    }

    // ---- Internals ----

    private String key(String agentName) {
        return (agentName == null || agentName.isBlank()) ? "default-cli" : agentName;
    }

    private QuotaState state(String agentName) {
        return states.computeIfAbsent(key(agentName), k -> new QuotaState());
    }

    private long effectiveWindowMs(ProcessingBackend backend) {
        if (backend != null && backend.getQuotaWindowOverrideMs() > 0) {
            return backend.getQuotaWindowOverrideMs();
        }
        return quotaWindowMs;
    }

    private long effectiveRequestCap(ProcessingBackend backend) {
        if (backend != null && backend.getMaxRequestsPerQuotaWindow() > 0) {
            return backend.getMaxRequestsPerQuotaWindow();
        }
        return maxRequestsPerWindow;
    }

    private long effectiveTokenCap(ProcessingBackend backend) {
        if (backend != null && backend.getMaxTokensPerQuotaWindow() > 0) {
            return backend.getMaxTokensPerQuotaWindow();
        }
        return maxTokensPerWindow;
    }

    /** Per-agent mutable state. All fields atomic; transitions are CAS-guarded. */
    private static final class QuotaState {
        static final long UNINITIALIZED = Long.MIN_VALUE;
        final AtomicLong exhaustedUntilMs = new AtomicLong(0);
        final AtomicLong lastSignalMs = new AtomicLong(0);
        final AtomicLong backoffMultiplierX100 = new AtomicLong(100); // 1.0x
        final AtomicLong windowStartMs = new AtomicLong(UNINITIALIZED);
        final AtomicLong requestsInWindow = new AtomicLong(0);
        final AtomicLong tokensInWindow = new AtomicLong(0);

        boolean isTimeExhausted(long now) {
            long until = exhaustedUntilMs.get();
            if (until == 0) {
                return false;
            }
            if (now >= until) {
                exhaustedUntilMs.compareAndSet(until, 0); // auto-recover when the window elapses
                return false;
            }
            return true;
        }

        void rollWindowIfNeeded(long now, long windowMs) {
            long start = windowStartMs.get();
            if (start == UNINITIALIZED) {
                windowStartMs.compareAndSet(UNINITIALIZED, now);
                return;
            }
            if (now - start >= windowMs && windowStartMs.compareAndSet(start, now)) {
                requestsInWindow.set(0);
                tokensInWindow.set(0);
            }
        }
    }
}
