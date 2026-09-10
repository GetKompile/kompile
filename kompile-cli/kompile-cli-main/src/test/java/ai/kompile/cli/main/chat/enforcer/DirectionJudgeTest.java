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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the direction judge: strictly opt-in config, fail-open verdicts,
 * redirect-then-halt semantics, and bounded budgets.
 */
class DirectionJudgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Config defaults: strictly opt-in ─────────────────────────────────────

    @Test
    void directionMonitoringIsOffByDefault() {
        EnforcerConfig config = new EnforcerConfig();
        assertFalse(config.isDirectionMonitoring(),
                "direction monitoring must be OFF by default");
        assertNull(config.getDirectionGoal());
        assertEquals(3, config.getDirectionCheckEvery());
        assertEquals(2, config.getDirectionMaxRedirects());
        assertEquals(0.6, config.getDirectionConfidenceThreshold());
        assertEquals(3, config.getDirectionCrossTurnDriftLimit());
        assertFalse(config.isDirectionReportOnly());
    }

    @Test
    void directionConfigRoundTripsThroughJson() throws Exception {
        Path dir = tempDir();
        EnforcerConfig config = new EnforcerConfig();
        config.setDirectionMonitoring(true);
        config.setDirectionGoal("ship the parser fix");
        config.setDirectionCheckEvery(2);
        config.setDirectionMaxRedirects(1);
        config.setDirectionConfidenceThreshold(0.75);
        config.setDirectionCrossTurnDriftLimit(4);
        config.setDirectionReportOnly(true);
        config.save(dir);

        EnforcerConfig loaded = EnforcerConfig.load(dir);
        assertTrue(loaded.isDirectionMonitoring());
        assertEquals("ship the parser fix", loaded.getDirectionGoal());
        assertEquals(2, loaded.getDirectionCheckEvery());
        assertEquals(1, loaded.getDirectionMaxRedirects());
        assertEquals(0.75, loaded.getDirectionConfidenceThreshold());
        assertEquals(4, loaded.getDirectionCrossTurnDriftLimit());
        assertTrue(loaded.isDirectionReportOnly());
    }

    // ── Verdict parsing: fail-open on any judge bug ─────────────────────────

    @Test
    void unavailableBackendIsFailOpen() {
        DirectionJudge judge = new DirectionJudge(
                unavailableBackend(), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict = judge.checkTurn(
                "goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.failOpen(), "unavailable backend must be fail-open");
        assertTrue(verdict.onTrack(), "fail-open must never be actionable drift");
    }

    @Test
    void malformedJsonIsFailOpen() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{not json"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.failOpen());
    }

    @Test
    void malformedJsonGetsOneBoundedRepairAndCanRecover() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                return calls.getAndIncrement() == 0
                        ? "{\"on_track\": context}"
                        : "{\"on_track\":true,\"confidence\":0.9,"
                        + "\"drift_reason\":\"\",\"redirect_prompt\":\"\",\"notes\":\"\"}";
            }

            @Override public boolean isAvailable() { return true; }
        };
        DirectionJudge judge = new DirectionJudge(
                backend, MAPPER, new DirectionJudge.Options("goal", 1, 2, false));

        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);

        assertFalse(verdict.failOpen());
        assertTrue(verdict.onTrack());
        assertEquals(2, calls.get());
    }

    @Test
    void backendErrorIsNotRetriedAsMalformedJson() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "[Error: all judge backends are in cooldown]";
            }

            @Override public boolean isAvailable() { return true; }
        };
        DirectionJudge judge = new DirectionJudge(
                backend, MAPPER, new DirectionJudge.Options("goal", 1, 2, false));

        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);

        assertTrue(verdict.failOpen());
        assertTrue(verdict.reason().contains("backend error"));
        assertEquals(1, calls.get());
    }

    @Test
    void offTrackWithoutReasonIsFailOpen() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"confidence\":0.99}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.failOpen(),
                "an unexplained drift flag must never redirect or halt");
    }

    @Test
    void lowConfidenceDriftIsNotActionable() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"confidence\":0.3,"
                        + "\"drift_reason\":\"vibe\",\"redirect_prompt\":\"do X\"}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertFalse(verdict.isActionable(0.6));
        assertTrue(verdict.failOpen(),
                "below-threshold verdicts are normalized before callers can act");
        assertTrue(verdict.onTrack());
    }

    @Test
    void confidentWellFormedDriftIsActionable() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"confidence\":0.9,"
                        + "\"drift_reason\":\"circular rewrites of the same file\","
                        + "\"redirect_prompt\":\"stop refactoring; write the missing test\"}"),
                MAPPER, new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.isActionable(0.6));
        assertEquals("stop refactoring; write the missing test", verdict.redirectPrompt());
    }

    @Test
    void exceptionThrowingBackendIsFailOpen() {
        DirectionJudge judge = new DirectionJudge(
                throwingBackend(), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.failOpen());
        assertTrue(verdict.onTrack());
    }

    @Test
    void missingOnTrackIsFailOpenAndCannotResetAStreak() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"confidence\":0.5}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        DirectionJudge.Verdict verdict =
                judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(verdict.onTrack());
        assertTrue(verdict.failOpen(),
                "missing required fields must not become a conclusive on-track verdict");
    }

    @Test
    void missingOrOutOfRangeConfidenceIsFailOpen() {
        DirectionJudge missing = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"drift_reason\":\"drift\","
                        + "\"redirect_prompt\":\"recover\"}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        assertTrue(missing.checkTurn("goal", "u", "t", "o", 1).failOpen());

        DirectionJudge invalid = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"confidence\":99,"
                        + "\"drift_reason\":\"drift\","
                        + "\"redirect_prompt\":\"recover\"}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        assertTrue(invalid.checkTurn("goal", "u", "t", "o", 1).failOpen());
    }

    // ── Turn lifecycle: redirect budget then halt ───────────────────────────

    @Test
    void redirectBudgetThenHaltSemantics() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"on_track\":false,\"confidence\":0.95,"
                        + "\"drift_reason\":\"scope runaway\","
                        + "\"redirect_prompt\":\"return to the failing test\"}"),
                MAPPER, new DirectionJudge.Options("goal", 1, 2, false));
        judge.beginTurn();

        assertTrue(judge.canRedirect());
        judge.recordRedirect();
        assertTrue(judge.canRedirect());
        judge.recordRedirect();
        assertFalse(judge.canRedirect(),
                "after the budget is spent the loop must halt, not redirect again");
        assertEquals(2, judge.getRedirectsThisTurn());
    }

    @Test
    void beginTurnResetsCounters() {
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend("{\"on_track\":true,\"confidence\":1.0}"), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false));
        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        judge.recordRedirect();
        assertTrue(judge.getChecksThisTurn() > 0);
        assertEquals(1, judge.getRedirectsThisTurn());

        judge.beginTurn();
        assertEquals(0, judge.getChecksThisTurn());
        assertEquals(0, judge.getRedirectsThisTurn());
    }

    @Test
    void crossTurnStreakIncrementsOncePerDriftAffectedTurnAndCleanTurnResetsIt() {
        DirectionJudge judge = new DirectionJudge(
                sequenceBackend(
                        "{\"on_track\":false,\"confidence\":0.9,"
                                + "\"drift_reason\":\"scope runaway\","
                                + "\"redirect_prompt\":\"return to the test\"}",
                        "{\"on_track\":true,\"confidence\":0.9}"),
                MAPPER, new DirectionJudge.Options("goal", 1, 2, false, 0.6, 3));

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        judge.checkTurn("goal", "u", "t", "o", 2);
        DirectionJudge.SessionState first = judge.completeTurn(true);
        assertEquals(1, first.assessedTurns());
        assertEquals(1, first.consecutiveDriftTurns(),
                "multiple checks in one turn count only once");
        assertEquals(1, first.totalDriftTurns());

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        DirectionJudge.SessionState clean = judge.completeTurn(true);
        assertEquals(2, clean.assessedTurns());
        assertEquals(0, clean.consecutiveDriftTurns(),
                "a clean confidently assessed turn resets the streak");
        assertEquals(1, clean.totalDriftTurns());
    }

    @Test
    void lowConfidenceFailedAndCancelledTurnsPreservePriorStreak() {
        DirectionJudge judge = new DirectionJudge(
                sequenceBackend(
                        "{\"on_track\":false,\"confidence\":0.9,"
                                + "\"drift_reason\":\"drift\","
                                + "\"redirect_prompt\":\"recover\"}",
                        "{\"on_track\":false,\"confidence\":0.2,"
                                + "\"drift_reason\":\"uncertain\","
                                + "\"redirect_prompt\":\"ignore\"}",
                        "{not json",
                        "{still not json",
                        "{\"on_track\":false,\"confidence\":0.9,"
                                + "\"drift_reason\":\"cancelled drift\","
                                + "\"redirect_prompt\":\"recover\"}"),
                MAPPER, new DirectionJudge.Options("goal", 1, 2, false, 0.6, 3));

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertEquals(1, judge.completeTurn(true).consecutiveDriftTurns());

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertEquals(1, judge.completeTurn(true).consecutiveDriftTurns(),
                "low-confidence turns neither increment nor reset");

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertEquals(1, judge.completeTurn(true).consecutiveDriftTurns(),
                "fail-open turns neither increment nor reset");

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertEquals(1, judge.completeTurn(false).consecutiveDriftTurns(),
                "cancelled turns do not alter the streak");
    }

    @Test
    void escalationUsesProjectedCurrentTurnAndCanBeDisabled() {
        String drift = "{\"on_track\":false,\"confidence\":0.9,"
                + "\"drift_reason\":\"drift\",\"redirect_prompt\":\"recover\"}";
        DirectionJudge judge = new DirectionJudge(
                scriptedBackend(drift), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false, 0.6, 2));

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertFalse(judge.isCrossTurnEscalationDue());
        judge.completeTurn(true);

        judge.beginTurn();
        judge.checkTurn("goal", "u", "t", "o", 1);
        assertEquals(2, judge.projectedConsecutiveDriftTurns());
        assertTrue(judge.isCrossTurnEscalationDue());

        DirectionJudge disabled = new DirectionJudge(
                scriptedBackend(drift), MAPPER,
                new DirectionJudge.Options("goal", 1, 2, false, 0.6, 0));
        disabled.beginTurn();
        disabled.checkTurn("goal", "u", "t", "o", 1);
        assertFalse(disabled.isCrossTurnEscalationDue());
    }

    @Test
    void crossTurnStateSurvivesJudgeReloadAndGoalChangeResetsIt() throws Exception {
        Path stateFile = tempDir().resolve("direction-state.json");
        String drift = "{\"on_track\":false,\"confidence\":0.9,"
                + "\"drift_reason\":\"drift\",\"redirect_prompt\":\"recover\"}";
        DirectionJudge.Options options =
                new DirectionJudge.Options("goal", 1, 2, false, 0.6, 3);
        DirectionJudge first = new DirectionJudge(scriptedBackend(drift), MAPPER, options);
        first.bindStateFile(stateFile);
        first.beginTurn();
        first.checkTurn("goal", "u", "t", "o", 1);
        first.completeTurn(true);

        DirectionJudge reloaded = new DirectionJudge(scriptedBackend(drift), MAPPER, options);
        reloaded.bindStateFile(stateFile);
        assertEquals(1, reloaded.getSessionState().consecutiveDriftTurns());
        assertEquals("drift", reloaded.getSessionState().lastDriftReason());

        reloaded.setGoal("new goal");
        assertEquals(0, reloaded.getSessionState().consecutiveDriftTurns(),
                "changing the monitored goal invalidates the old streak");
        reloaded.setGoal(null);
        assertNull(reloaded.getGoal(),
                "session goal clear must not fall back to the configured goal");
    }

    @Test
    void optionsAreClampedAndImmutable() {
        DirectionJudge.Options options =
                new DirectionJudge.Options("goal", 0, 99, false,
                        Double.NaN, 99);
        assertEquals(1, options.checkEvery(), "checkEvery is clamped to >= 1");
        assertEquals(4, options.maxRedirects(), "maxRedirects is clamped to <= 4");
        assertEquals(0.6, options.confidenceThreshold(),
                "non-finite confidence falls back to 0.6");
        assertEquals(20, options.crossTurnDriftLimit(),
                "cross-turn limit is bounded");
    }

    @Test
    void guidanceSupplierRidesIntoThePrompt() {
        StringBuilder captured = new StringBuilder();
        DirectionJudge judge = new DirectionJudge(
                new JudgeBackend() {
                    @Override public String generate(String userPrompt, String systemPrompt) {
                        captured.append(userPrompt);
                        return "{\"on_track\":true,\"confidence\":1.0}";
                    }
                    @Override public boolean isAvailable() {
                        return true;
                    }
                },
                MAPPER, new DirectionJudge.Options("goal", 1, 2, false));
        judge.setGuidanceSupplier(() -> "always build with maven from the repo root");
        judge.checkTurn("goal", "user msg", "trail", "output", 1);
        assertTrue(captured.toString().contains("always build with maven from the repo root"),
                "the user's durable guidance must reach the direction prompt");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("direction-judge-test");
    }

    private static JudgeBackend unavailableBackend() {
        return new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                throw new IllegalStateException("unavailable");
            }
            @Override public boolean isAvailable() {
                return false;
            }
        };
    }

    private static JudgeBackend scriptedBackend(String response) {
        return new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return response;
            }
            @Override public boolean isAvailable() {
                return true;
            }
        };
    }

    private static JudgeBackend sequenceBackend(String... responses) {
        AtomicInteger index = new AtomicInteger();
        return new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                int current = Math.min(index.getAndIncrement(), responses.length - 1);
                return responses[current];
            }
            @Override public boolean isAvailable() {
                return true;
            }
        };
    }

    private static JudgeBackend throwingBackend() {
        return new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) throws Exception {
                throw new IOException("boom");
            }
            @Override public boolean isAvailable() {
                return true;
            }
        };
    }
}
