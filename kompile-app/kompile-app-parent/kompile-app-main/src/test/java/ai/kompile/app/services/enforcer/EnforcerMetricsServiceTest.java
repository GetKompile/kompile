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

import ai.kompile.app.services.enforcer.EnforcerMetricsService.AgentMetrics;
import ai.kompile.app.services.enforcer.EnforcerMetricsService.MetricEvent;
import ai.kompile.app.services.enforcer.EnforcerMetricsService.ProjectMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EnforcerMetricsService")
class EnforcerMetricsServiceTest {

    @TempDir
    Path tempDir;

    private EnforcerMetricsService service;

    @BeforeEach
    void setUp() {
        System.setProperty("user.dir", tempDir.toString());
        service = new EnforcerMetricsService();
    }

    // ── Full judging lifecycle ──────────────────────────────────────

    @Nested
    @DisplayName("Judge lifecycle: session → violations → score → end")
    class JudgeLifecycle {

        @Test
        void agentThatViolatesRulesHasWorseSummaryThanCleanAgent() {
            // Two agents work in the same project. claude follows the rules;
            // codex triggers violations. After sessions end, their metrics
            // must reflect the difference.

            // claude: clean run
            service.recordSessionStart("frontend", "claude", "s-100");
            service.recordSessionEnd("frontend", "claude", "s-100", 1.0, 0, 12);

            // codex: 3 violations, score degrades
            service.recordSessionStart("frontend", "codex", "s-200");
            service.recordViolation("frontend", "codex", "s-200",
                    "TEXT_VIOLATION", 0.8, "used eval()", List.of("eval"));
            service.recordViolation("frontend", "codex", "s-200",
                    "TOOL_VIOLATION", 0.5, "called banned tool rm -rf",
                    List.of("rm", "rf"));
            service.recordViolation("frontend", "codex", "s-200",
                    "BLOCKED", 0.2, "too many violations", List.of());
            service.recordSessionEnd("frontend", "codex", "s-200", 0.2, 3, 8);

            List<Map<String, Object>> all = service.getMetricsForProject("frontend");
            assertEquals(2, all.size());

            Map<String, Object> claude = all.stream()
                    .filter(m -> "claude".equals(m.get("agentName")))
                    .findFirst().orElseThrow();
            Map<String, Object> codex = all.stream()
                    .filter(m -> "codex".equals(m.get("agentName")))
                    .findFirst().orElseThrow();

            assertEquals(0, claude.get("totalViolations"));
            assertEquals(3, codex.get("totalViolations"));

            double claudeAvg = (double) claude.get("avgScore");
            double codexAvg = (double) codex.get("avgScore");
            assertTrue(claudeAvg > codexAvg,
                    "Clean agent should have better avg score: claude=" + claudeAvg + " codex=" + codexAvg);
        }

        @Test
        void repeatedSessionsAccumulateViolationsNotReset() {
            // Same agent, same project, multiple sessions.
            // Violations should accumulate across sessions, not reset.
            service.recordSessionStart("backend", "claude", "s-1");
            service.recordViolation("backend", "claude", "s-1",
                    "TEXT_VIOLATION", 0.7, "v1", List.of("System.exit"));
            service.recordSessionEnd("backend", "claude", "s-1", 0.7, 1, 5);

            service.recordSessionStart("backend", "claude", "s-2");
            service.recordViolation("backend", "claude", "s-2",
                    "TEXT_VIOLATION", 0.6, "v2", List.of("Runtime.exec"));
            service.recordViolation("backend", "claude", "s-2",
                    "BLOCKED", 0.3, "blocked", List.of());
            service.recordSessionEnd("backend", "claude", "s-2", 0.3, 2, 4);

            Map<String, Object> detail = service.getMetricsForProjectAgent("backend", "claude");
            assertEquals(2, detail.get("totalSessions"));
            assertEquals(3, detail.get("totalViolations"));   // 1 + 2 = 3 accumulated
            assertEquals(0.3, (double) detail.get("lastScore"), 0.001);
        }

        @Test
        void scoreAverageTellsYouIfAnAgentIsDeterioratingOverTime() {
            // Session 1: agent scores 0.9
            service.recordSessionStart("api", "gemini", "s-1");
            service.recordSessionEnd("api", "gemini", "s-1", 0.9, 0, 10);

            // Session 2: agent scores 0.5 (worse)
            service.recordSessionStart("api", "gemini", "s-2");
            service.recordViolation("api", "gemini", "s-2",
                    "TEXT_VIOLATION", 0.5, "SQL injection pattern", List.of("'; DROP TABLE"));
            service.recordSessionEnd("api", "gemini", "s-2", 0.5, 1, 6);

            Map<String, Object> detail = service.getMetricsForProjectAgent("api", "gemini");
            double avg = (double) detail.get("avgScore");
            double last = (double) detail.get("lastScore");

            // avg should be between 0.5 and 0.9 (there are 3 score samples: 0.9, 0.5, 0.5)
            assertTrue(avg > 0.5 && avg < 0.9,
                    "Average should reflect both sessions: " + avg);
            assertEquals(0.5, last, 0.001, "Last score should be from the worse session");
        }
    }

    // ── Event classification ────────────────────────────────────────

    @Nested
    @DisplayName("Violation vs interruption classification")
    class EventClassification {

        @Test
        void violationTypesAreCounted_interruptionTypesAreCountedSeparately() {
            service.recordSessionStart("proj", "claude", "s-1");

            // These are violations
            service.recordViolation("proj", "claude", "s-1",
                    "TEXT_VIOLATION", 0.8, "banned word", List.of("eval"));
            service.recordViolation("proj", "claude", "s-1",
                    "TOOL_VIOLATION", 0.6, "banned tool", List.of("rm"));
            service.recordViolation("proj", "claude", "s-1",
                    "BLOCKED", 0.3, "hard block", List.of());

            // These are interruptions (non-violation events)
            service.recordInterruption("proj", "claude", "s-1",
                    "CORRECTION", 0.5, "auto-corrected");
            service.recordInterruption("proj", "claude", "s-1",
                    "SCORE_DROP", 0.4, "score dropped below threshold");

            Map<String, Object> detail = service.getMetricsForProjectAgent("proj", "claude");
            assertEquals(3, detail.get("totalViolations"));
            assertEquals(2, detail.get("totalInterruptions"));
        }

        @Test
        void violationHistoryPreservesOriginalEventType() {
            service.recordSessionStart("proj", "claude", "s-1");
            service.recordViolation("proj", "claude", "s-1",
                    "TOOL_VIOLATION", 0.5, "used banned tool", List.of("curl"));

            Map<String, Object> detail = service.getMetricsForProjectAgent("proj", "claude");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");

            // Find the violation event (not the session_start)
            Map<String, Object> violationEvent = history.stream()
                    .filter(e -> "violation".equals(e.get("type")))
                    .findFirst().orElseThrow();

            assertEquals("TOOL_VIOLATION", violationEvent.get("eventType"),
                    "Should preserve the specific violation type for drill-down");
            assertEquals(0.5, (double) violationEvent.get("score"), 0.001);
            assertEquals("used banned tool", violationEvent.get("reason"));

            @SuppressWarnings("unchecked")
            List<String> violations = (List<String>) violationEvent.get("violations");
            assertEquals(List.of("curl"), violations);
        }
    }

    // ── Cross-project isolation ─────────────────────────────────────

    @Nested
    @DisplayName("Cross-project isolation")
    class CrossProjectIsolation {

        @Test
        void violationsInOneProjectDontContaminateAnother() {
            // claude is terrible in "frontend" but clean in "backend"
            service.recordSessionStart("frontend", "claude", "s-1");
            service.recordViolation("frontend", "claude", "s-1",
                    "TEXT_VIOLATION", 0.2, "XSS", List.of("innerHTML"));
            service.recordViolation("frontend", "claude", "s-1",
                    "TEXT_VIOLATION", 0.1, "eval", List.of("eval"));
            service.recordSessionEnd("frontend", "claude", "s-1", 0.1, 2, 5);

            service.recordSessionStart("backend", "claude", "s-2");
            service.recordSessionEnd("backend", "claude", "s-2", 1.0, 0, 10);

            Map<String, Object> frontend = service.getMetricsForProjectAgent("frontend", "claude");
            Map<String, Object> backend = service.getMetricsForProjectAgent("backend", "claude");

            assertEquals(2, frontend.get("totalViolations"));
            assertEquals(0, backend.get("totalViolations"));
            assertEquals(0.1, (double) frontend.get("lastScore"), 0.001);
            assertEquals(1.0, (double) backend.get("lastScore"), 0.001);
        }

        @Test
        void getAllMetricsShowsBothProjectsSideBySide() {
            service.recordSessionStart("frontend", "claude", "s-1");
            service.recordViolation("frontend", "claude", "s-1",
                    "TEXT_VIOLATION", 0.5, "bad", List.of("eval"));
            service.recordSessionEnd("frontend", "claude", "s-1", 0.5, 1, 5);

            service.recordSessionStart("backend", "codex", "s-2");
            service.recordSessionEnd("backend", "codex", "s-2", 1.0, 0, 8);

            List<Map<String, Object>> all = service.getAllMetrics();
            assertEquals(2, all.size());

            // Verify both projects are represented
            assertTrue(all.stream().anyMatch(m ->
                    "frontend".equals(m.get("codingProjectId")) && "claude".equals(m.get("agentName"))));
            assertTrue(all.stream().anyMatch(m ->
                    "backend".equals(m.get("codingProjectId")) && "codex".equals(m.get("agentName"))));
        }
    }

    // ── Persistence across restarts ─────────────────────────────────

    @Nested
    @DisplayName("Metrics survive service restart")
    class PersistenceAcrossRestarts {

        @Test
        void violationHistorySurvivesServiceRestart() {
            // First "server lifetime"
            service.recordSessionStart("proj", "claude", "s-1");
            service.recordViolation("proj", "claude", "s-1",
                    "TEXT_VIOLATION", 0.6, "banned keyword detected", List.of("exec"));
            service.recordViolation("proj", "claude", "s-1",
                    "BLOCKED", 0.2, "too many violations", List.of());
            service.recordSessionEnd("proj", "claude", "s-1", 0.2, 2, 5);

            // Simulate server restart: new service instance, same disk
            EnforcerMetricsService restarted = new EnforcerMetricsService();

            Map<String, Object> detail = restarted.getMetricsForProjectAgent("proj", "claude");
            assertNotNull(detail, "Metrics must be loadable after restart");
            assertEquals(1, detail.get("totalSessions"));
            assertEquals(2, detail.get("totalViolations"));
            assertEquals(0.2, (double) detail.get("lastScore"), 0.001);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
            // start + 2 violations + end = 4
            assertEquals(4, history.size());
        }

        @Test
        void newSessionAfterRestartAccumulatesOnTopOfPreviousMetrics() {
            service.recordSessionStart("proj", "claude", "s-1");
            service.recordViolation("proj", "claude", "s-1",
                    "TEXT_VIOLATION", 0.7, "v1", List.of("a"));
            service.recordSessionEnd("proj", "claude", "s-1", 0.7, 1, 5);

            // Restart
            EnforcerMetricsService restarted = new EnforcerMetricsService();

            // New session on restarted service
            restarted.recordSessionStart("proj", "claude", "s-2");
            restarted.recordViolation("proj", "claude", "s-2",
                    "TOOL_VIOLATION", 0.4, "v2", List.of("b"));
            restarted.recordSessionEnd("proj", "claude", "s-2", 0.4, 1, 3);

            Map<String, Object> detail = restarted.getMetricsForProjectAgent("proj", "claude");
            assertEquals(2, detail.get("totalSessions"));   // 1 + 1
            assertEquals(2, detail.get("totalViolations"));  // 1 + 1
        }

        @Test
        void getAllMetricsDiscoverProjectsFromDiskEvenIfNeverLoaded() {
            // Write metrics for two projects
            service.recordSessionStart("proj-a", "claude", "s-1");
            service.recordSessionEnd("proj-a", "claude", "s-1", 0.9, 0, 5);
            service.recordSessionStart("proj-b", "codex", "s-2");
            service.recordSessionEnd("proj-b", "codex", "s-2", 0.5, 2, 3);

            // Fresh service — hasn't loaded anything yet
            EnforcerMetricsService fresh = new EnforcerMetricsService();

            List<Map<String, Object>> all = fresh.getAllMetrics();
            assertEquals(2, all.size(), "Should discover both projects from disk scan");
        }
    }

    // ── Null/blank project ID guard ─────────────────────────────────

    @Nested
    @DisplayName("Sessions without a coding project ID produce no metrics")
    class NoCodingProjectId {

        @Test
        void nullProjectIdProducesNoMetrics() {
            service.recordSessionStart(null, "claude", "s-1");
            service.recordViolation(null, "claude", "s-1",
                    "TEXT_VIOLATION", 0.5, "bad", List.of());
            service.recordSessionEnd(null, "claude", "s-1", 0.5, 1, 3);

            assertTrue(service.getAllMetrics().isEmpty(),
                    "Sessions without codingProjectId should not generate metrics");
        }

        @Test
        void blankProjectIdProducesNoMetrics() {
            service.recordSessionStart("", "claude", "s-1");
            service.recordViolation("  ", "claude", "s-1",
                    "TEXT_VIOLATION", 0.5, "bad", List.of());

            assertTrue(service.getAllMetrics().isEmpty());
        }
    }

    // ── History cap ─────────────────────────────────────────────────

    @Nested
    @DisplayName("History is bounded")
    class HistoryBound {

        @Test
        void oldEventsAreEvictedWhenHistoryExceedsCap() {
            service.recordSessionStart("proj", "claude", "s-1");

            // Pump 520 violation events (cap is 500)
            for (int i = 0; i < 520; i++) {
                service.recordViolation("proj", "claude", "s-1",
                        "TEXT_VIOLATION", 0.5, "event-" + i, List.of());
            }

            Map<String, Object> detail = service.getMetricsForProjectAgent("proj", "claude");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
            assertTrue(history.size() <= 500,
                    "History must be capped, was: " + history.size());

            // The oldest events should have been evicted — the first remaining
            // event should NOT be the session_start or event-0
            String firstReason = (String) history.get(0).get("reason");
            assertFalse("event-0".equals(firstReason),
                    "Oldest events should be evicted; first event is: " + firstReason);

            // But the violation counter should still reflect ALL 520 violations
            assertEquals(520, detail.get("totalViolations"),
                    "Counters must reflect all events, not just those in history");
        }
    }

    // ── File layout ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Persisted file structure")
    class FileLayout {

        @Test
        void metricsFileContainsAgentDataAsJson() throws Exception {
            service.recordSessionStart("my-project", "claude", "s-1");
            service.recordViolation("my-project", "claude", "s-1",
                    "TEXT_VIOLATION", 0.6, "banned", List.of("eval", "exec"));
            service.recordSessionEnd("my-project", "claude", "s-1", 0.6, 1, 4);

            Path metricsFile = tempDir.resolve(".kompile").resolve("code-projects")
                    .resolve("my-project").resolve("enforcer-metrics.json");
            assertTrue(Files.exists(metricsFile), "Metrics file must exist");

            // Parse it back and verify structure
            ObjectMapper mapper = new ObjectMapper();
            ProjectMetrics pm = mapper.readValue(metricsFile.toFile(), ProjectMetrics.class);

            assertEquals("my-project", pm.codingProjectId);
            assertTrue(pm.agents.containsKey("claude"));

            AgentMetrics am = pm.agents.get("claude");
            assertEquals(1, am.totalSessions);
            assertEquals(1, am.totalViolations);
            assertEquals("claude", am.agentName);

            // Verify history events are persisted with full detail
            MetricEvent violation = am.history.stream()
                    .filter(e -> "violation".equals(e.type))
                    .findFirst().orElseThrow();
            assertEquals("TEXT_VIOLATION", violation.eventType);
            assertEquals(List.of("eval", "exec"), violation.violations);
        }
    }
}
