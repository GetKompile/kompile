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

package ai.kompile.cli.main.chat.enforcer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision tests for the promoted agent-fallback policy (WP13). */
class FallbackSupervisorTest {

    private static FallbackSupervisor claudeFirst(FallbackSupervisor.Config config) {
        return new FallbackSupervisor(config, "claude");
    }

    @Test
    void fallsBackAfterConsecutiveFailures() {
        // limit=3, catastrophic=-6 (each -2 score won't trip catastrophic), floor=-10.
        FallbackSupervisor s = claudeFirst(new FallbackSupervisor.Config(-10, 3, -6,
                List.of("claude", "codex", "gemini")));

        assertFalse(s.recordTurnScore(-2, true).fallback());
        assertFalse(s.recordTurnScore(-2, true).fallback());
        FallbackSupervisor.Decision third = s.recordTurnScore(-2, true);

        assertTrue(third.fallback(), "3rd consecutive failure triggers fallback");
        assertEquals("claude", third.fromAgent());
        assertEquals("codex", third.toAgent(), "switches to the next agent in priority");
        assertTrue(third.reason().startsWith("CONSECUTIVE"));
        // Scores reset for the new agent.
        assertEquals("codex", s.currentAgent());
        assertEquals(0, s.cumulativeScore());
        assertEquals(0, s.consecutiveFailures());
    }

    @Test
    void catastrophicSingleTurnFallsBackImmediately() {
        FallbackSupervisor s = claudeFirst(FallbackSupervisor.Config.defaults());
        FallbackSupervisor.Decision d = s.recordTurnScore(-7, true); // <= -6 catastrophic
        assertTrue(d.fallback(), "a single catastrophic turn triggers immediate fallback");
        assertTrue(d.reason().startsWith("CATASTROPHIC"));
        assertEquals("codex", d.toAgent());
    }

    @Test
    void cumulativeFloorFallsBack() {
        // floor=-8, but keep each turn above catastrophic(-6) and below the consecutive limit(5).
        FallbackSupervisor s = claudeFirst(new FallbackSupervisor.Config(-8, 5, -6,
                List.of("claude", "codex")));
        assertFalse(s.recordTurnScore(-3, true).fallback()); // cumulative -3
        assertFalse(s.recordTurnScore(-3, true).fallback()); // cumulative -6
        FallbackSupervisor.Decision d = s.recordTurnScore(-3, true); // cumulative -9 <= -8
        assertTrue(d.fallback(), "cumulative floor triggers fallback");
        assertTrue(d.reason().startsWith("CUMULATIVE"));
    }

    @Test
    void positiveScoresResetConsecutiveAndNeverFallBack() {
        FallbackSupervisor s = claudeFirst(FallbackSupervisor.Config.defaults());
        s.recordTurnScore(-2, true);
        s.recordTurnScore(3, false); // a good turn resets consecutive failures
        assertEquals(0, s.consecutiveFailures());
        assertFalse(s.recordTurnScore(2, false).fallback());
        assertEquals("claude", s.currentAgent(), "no fallback while healthy");
    }

    @Test
    void advisoryModeRecommendsButKeepsCurrentAgent() {
        // autoAdvance=false: fallback is RECOMMENDED (Decision) but currentAgent stays put.
        FallbackSupervisor s = new FallbackSupervisor(
                new FallbackSupervisor.Config(-10, 3, -6, List.of("claude", "codex")), "claude", false);
        s.recordTurnScore(-2, true);
        s.recordTurnScore(-2, true);
        FallbackSupervisor.Decision d = s.recordTurnScore(-2, true);
        assertTrue(d.fallback(), "advisory still emits a fallback recommendation");
        assertEquals("codex", d.toAgent(), "recommends the next agent");
        assertEquals("claude", s.currentAgent(), "but does NOT switch — advisory keeps the current agent");
        assertEquals(0, s.consecutiveFailures(), "counters reset to warn-once, not every turn");
    }

    @Test
    void scoreForOutcomeMapsSeverity() {
        assertEquals(1, FallbackSupervisor.scoreForOutcome(true, "critical"), "accepted → positive");
        assertEquals(-6, FallbackSupervisor.scoreForOutcome(false, "critical"), "critical → catastrophic band");
        assertEquals(-3, FallbackSupervisor.scoreForOutcome(false, "error"));
        assertEquals(-1, FallbackSupervisor.scoreForOutcome(false, "warning"));
        assertEquals(-2, FallbackSupervisor.scoreForOutcome(false, null), "unknown severity → mild negative");
    }

    @Test
    void becomesExhaustedWhenNoAgentsRemain() {
        // Single-agent priority: a catastrophic score cannot switch anywhere → exhausted, no fallback.
        FallbackSupervisor s = new FallbackSupervisor(
                new FallbackSupervisor.Config(-10, 3, -6, List.of("claude")), "claude");
        FallbackSupervisor.Decision d = s.recordTurnScore(-9, true);
        assertFalse(d.fallback(), "cannot fall back with no other agent");
        assertTrue(d.exhausted());
        assertTrue(s.isExhausted());
        // Once exhausted, further bad turns never trigger.
        assertFalse(s.recordTurnScore(-9, true).fallback());
    }
}
