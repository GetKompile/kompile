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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link CliAgentQuotaLedger} using an injected clock. Covers both gates
 * (time window + request/token caps), auto-recovery, and the rapid-re-exhaustion backoff hysteresis.
 */
class CliAgentQuotaLedgerTest {

    /** Non-zero base avoids any epoch (t=0) edge cases. */
    private static final long BASE = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(BASE);

    private CliAgentQuotaLedger newLedger() {
        return new CliAgentQuotaLedger(clock::get);
    }

    @Test
    void hasBudget_trueWhenNoSignalRecorded() {
        assertTrue(newLedger().hasBudget("claude"));
    }

    @Test
    void timeWindow_exhaustsThenAutoRecovers() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(10_000);
        clock.set(BASE + 1_000);
        l.recordQuotaSignal("claude");
        clock.set(BASE + 5_000);
        assertFalse(l.hasBudget("claude"), "within window → exhausted");
        clock.set(BASE + 11_001);
        assertTrue(l.hasBudget("claude"), "after window → recovered");
    }

    @Test
    void hysteresis_backsOffOnRapidReexhaustion() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(10_000);
        l.setMinHealthyMs(60_000);
        clock.set(BASE + 1_000);
        l.recordQuotaSignal("claude");                 // base window → until BASE+11_000
        clock.set(BASE + 2_000);                        // 1s later (< minHealthy) → x2 backoff
        l.recordQuotaSignal("claude");                 // 20_000 window → until BASE+22_000
        clock.set(BASE + 15_000);
        assertFalse(l.hasBudget("claude"), "backed-off window still active");
        clock.set(BASE + 22_001);
        assertTrue(l.hasBudget("claude"));
    }

    @Test
    void hysteresis_resetsAfterHealthyGap() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(10_000);
        l.setMinHealthyMs(5_000);
        clock.set(BASE + 1_000);
        l.recordQuotaSignal("claude");                 // until BASE+11_000
        clock.set(BASE + 20_000);                       // 19s gap (> minHealthy) → reset to base
        l.recordQuotaSignal("claude");                 // base window → until BASE+30_000
        clock.set(BASE + 29_000);
        assertFalse(l.hasBudget("claude"));
        clock.set(BASE + 30_001);
        assertTrue(l.hasBudget("claude"));
    }

    @Test
    void requestCap_blocksProactivelyAndRolls() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(60_000);
        l.setMaxRequestsPerWindow(2);
        clock.set(BASE);
        l.recordConsumption("claude", 5, 5);
        l.recordConsumption("claude", 5, 5);
        assertFalse(l.hasBudget("claude"), "request cap reached → proactively blocked");
        clock.set(BASE + 60_001);
        assertTrue(l.hasBudget("claude"), "rolling window reset");
    }

    @Test
    void tokenCap_blocksWhenExceeded() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(60_000);
        l.setMaxTokensPerWindow(100);
        clock.set(BASE);
        l.recordConsumption("claude", 60, 50); // 110 tokens > 100
        assertFalse(l.hasBudget("claude"));
    }

    @Test
    void agentsAreIndependent() {
        CliAgentQuotaLedger l = newLedger();
        l.setQuotaWindowMs(10_000);
        clock.set(BASE);
        l.recordQuotaSignal("claude");
        assertFalse(l.hasBudget("claude"));
        assertTrue(l.hasBudget("codex"));
    }

    @Test
    void nullOrBlankAgent_unrestricted() {
        CliAgentQuotaLedger l = newLedger();
        assertTrue(l.hasBudget(null));
        assertTrue(l.hasBudget(""));
    }
}
