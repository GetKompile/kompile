/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.enforcer;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ClaudeStreamParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that events flowing through EnforcerSessionManager are correctly
 * classified and recorded in EnforcerMetricsService. Uses a real metrics
 * service backed by a temp dir — no mocking of the metrics layer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EnforcerSessionManager → MetricsService integration")
class EnforcerSessionManagerMetricsTest {

    @TempDir
    Path tempDir;

    @Mock private AgentRegistryService agentRegistry;
    @Mock private AgentChatService agentChatService;
    @Mock private ClaudeStreamParser streamParser;

    private EnforcerMetricsService metricsService;
    private EnforcerSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        System.setProperty("user.dir", tempDir.toString());
        metricsService = new EnforcerMetricsService();
        sessionManager = new EnforcerSessionManager(
                agentRegistry, agentChatService, streamParser, metricsService);
    }

    // Helper to create an external session (avoids needing a real subprocess)
    private EnforcerSessionState registerSession(String sessionId, String agent,
                                                  String rules, String codingProjectId) {
        return sessionManager.registerExternalSession(
                sessionId, agent, rules, 2, "", tempDir.toString(), codingProjectId);
    }

    // Helper to create a violation event
    private EnforcerSessionState.InterruptEvent violation(String type, double score,
                                                           String reason, List<String> violations) {
        return new EnforcerSessionState.InterruptEvent(
                "evt-" + System.nanoTime(), Instant.now(),
                type, "error", score, violations, reason, null, "INTERRUPT");
    }

    // Helper to create a non-violation interrupt event
    private EnforcerSessionState.InterruptEvent interruption(String type, double score, String reason) {
        return new EnforcerSessionState.InterruptEvent(
                "evt-" + System.nanoTime(), Instant.now(),
                type, "warning", score, List.of(), reason, null, "CONTINUE");
    }

    // ── Event classification through the manager ────────────────────

    @Nested
    @DisplayName("Event classification: violations vs interruptions")
    class EventClassification {

        @Test
        void textViolationIsCountedAsViolation() {
            registerSession("s-1", "claude", "no eval", "proj");
            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.6, "banned word eval", List.of("eval")));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(1, m.get("totalViolations"));
            assertEquals(0, m.get("totalInterruptions"));
        }

        @Test
        void toolViolationIsCountedAsViolation() {
            registerSession("s-1", "codex", "no rm", "proj");
            sessionManager.recordEvent("s-1",
                    violation("TOOL_VIOLATION", 0.5, "used rm", List.of("rm")));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "codex");
            assertEquals(1, m.get("totalViolations"));
        }

        @Test
        void blockedIsCountedAsViolation() {
            registerSession("s-1", "claude", "rules", "proj");
            sessionManager.recordEvent("s-1",
                    violation("BLOCKED", 0.1, "hard blocked", List.of()));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(1, m.get("totalViolations"));
        }

        @Test
        void correctionIsCountedAsInterruption() {
            registerSession("s-1", "claude", "rules", "proj");
            sessionManager.recordEvent("s-1",
                    interruption("CORRECTION", 0.7, "auto-corrected output"));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(0, m.get("totalViolations"));
            assertEquals(1, m.get("totalInterruptions"));
        }

        @Test
        void scoreDropIsCountedAsInterruption() {
            registerSession("s-1", "claude", "rules", "proj");
            sessionManager.recordEvent("s-1",
                    interruption("SCORE_DROP", 0.4, "below threshold"));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(0, m.get("totalViolations"));
            assertEquals(1, m.get("totalInterruptions"));
        }

        @Test
        void unknownEventTypeIsCountedAsInterruption() {
            registerSession("s-1", "claude", "rules", "proj");
            sessionManager.recordEvent("s-1",
                    interruption("SOME_FUTURE_TYPE", 0.8, "new event kind"));

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(0, m.get("totalViolations"));
            assertEquals(1, m.get("totalInterruptions"));
        }
    }

    // ── Score tracking through real event flow ──────────────────────

    @Nested
    @DisplayName("Score degrades as violations accumulate")
    class ScoreTracking {

        @Test
        void scoreDropsWithEachViolation() {
            registerSession("s-1", "claude", "rules", "proj");

            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.8, "first", List.of("a")));
            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.5, "second", List.of("b")));
            sessionManager.recordEvent("s-1",
                    violation("BLOCKED", 0.1, "final", List.of()));

            // Session state should reflect the last score
            EnforcerSessionState state = sessionManager.getSession("s-1");
            assertEquals(0.1, state.getCurrentScore(), 0.001);
            assertEquals(3, state.getViolationCount());

            // Metrics should have the same final score
            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(0.1, (double) m.get("lastScore"), 0.001);
        }

        @Test
        void endSessionCapturesFinalStateIntoMetrics() {
            registerSession("s-1", "claude", "no eval", "proj");

            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.6, "eval found", List.of("eval")));

            sessionManager.endSession("s-1");

            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertEquals(1, m.get("totalViolations"));

            // History should have: start, violation, end
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) m.get("history");
            assertEquals(3, history.size());
            assertEquals("session_start", history.get(0).get("type"));
            assertEquals("violation", history.get(1).get("type"));
            assertEquals("session_end", history.get(2).get("type"));
        }
    }

    // ── Multi-agent comparison in one project ───────────────────────

    @Nested
    @DisplayName("Compare agents working on the same project")
    class MultiAgentComparison {

        @Test
        void canCompareViolationRatesBetweenAgents() {
            // claude: 1 violation in 1 session
            registerSession("s-claude", "claude", "rules", "myapp");
            sessionManager.recordEvent("s-claude",
                    violation("TEXT_VIOLATION", 0.7, "minor", List.of("x")));
            sessionManager.endSession("s-claude");

            // codex: 4 violations in 1 session (worse)
            registerSession("s-codex", "codex", "rules", "myapp");
            sessionManager.recordEvent("s-codex",
                    violation("TEXT_VIOLATION", 0.8, "v1", List.of("a")));
            sessionManager.recordEvent("s-codex",
                    violation("TOOL_VIOLATION", 0.6, "v2", List.of("b")));
            sessionManager.recordEvent("s-codex",
                    violation("TEXT_VIOLATION", 0.3, "v3", List.of("c")));
            sessionManager.recordEvent("s-codex",
                    violation("BLOCKED", 0.1, "v4", List.of()));
            sessionManager.endSession("s-codex");

            List<Map<String, Object>> metrics = metricsService.getMetricsForProject("myapp");
            assertEquals(2, metrics.size());

            Map<String, Object> claude = metrics.stream()
                    .filter(m -> "claude".equals(m.get("agentName"))).findFirst().orElseThrow();
            Map<String, Object> codex = metrics.stream()
                    .filter(m -> "codex".equals(m.get("agentName"))).findFirst().orElseThrow();

            // codex has 4x the violations
            assertEquals(1, claude.get("totalViolations"));
            assertEquals(4, codex.get("totalViolations"));

            // codex ended with a worse score
            assertTrue((double) claude.get("lastScore") > (double) codex.get("lastScore"));
        }
    }

    // ── Sessions without a project ID ───────────────────────────────

    @Nested
    @DisplayName("Sessions without codingProjectId")
    class NoCodingProject {

        @Test
        void eventsOnSessionWithoutProjectIdProduceNoMetrics() {
            // Register a session with no coding project
            sessionManager.registerExternalSession(
                    "s-1", "claude", "rules", 2, "", tempDir.toString(), null);

            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.5, "bad", List.of("x")));
            sessionManager.endSession("s-1");

            assertTrue(metricsService.getAllMetrics().isEmpty(),
                    "Events on sessions without codingProjectId should not appear in metrics");
        }
    }

    // ── Restart preserves project association ────────────────────────

    @Nested
    @DisplayName("Session restart")
    class SessionRestart {

        @Test
        void endSessionRecordsMetricsBeforeRemoval() {
            registerSession("s-1", "claude", "rules", "proj");
            sessionManager.recordEvent("s-1",
                    violation("TEXT_VIOLATION", 0.5, "bad", List.of("eval")));

            // End session — metrics should be captured
            sessionManager.endSession("s-1");

            // Session is gone
            assertNull(sessionManager.getSession("s-1"));

            // But metrics persist
            Map<String, Object> m = metricsService.getMetricsForProjectAgent("proj", "claude");
            assertNotNull(m);
            assertEquals(1, m.get("totalViolations"));
            assertEquals(1, m.get("totalSessions"));
        }
    }
}
