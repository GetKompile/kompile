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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.enforcer.EnforcerMetricsService;
import ai.kompile.app.services.enforcer.EnforcerSessionManager;
import ai.kompile.app.services.enforcer.EnforcerSessionState;
import ai.kompile.app.web.dto.EnforcerSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EnforcerSessionController")
class EnforcerSessionControllerTest {

    @Mock private EnforcerSessionManager sessionManager;
    @Mock private EnforcerMetricsService metricsService;

    private EnforcerSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new EnforcerSessionController(sessionManager, metricsService);
    }

    // ── Session creation with coding project scoping ────────────────

    @Nested
    @DisplayName("POST /sessions — session creation")
    class CreateSession {

        @Test
        void createdSessionIncludesCodingProjectId() {
            EnforcerSessionState state = new EnforcerSessionState(
                    "s-001", "claude", "no eval", 2, "", "/tmp", "frontend-app");
            when(sessionManager.createSession(
                    eq("claude"), eq("no eval"), eq(2), any(), any(),
                    anyBoolean(), anyBoolean(), eq("frontend-app")))
                    .thenReturn(state);

            EnforcerSessionRequest request = new EnforcerSessionRequest();
            request.setAgentName("claude");
            request.setRules("no eval");
            request.setMaxCorrections(2);
            request.setCodingProjectId("frontend-app");

            ResponseEntity<Map<String, Object>> response = controller.createSession(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("frontend-app", response.getBody().get("codingProjectId"));
        }

        @Test
        void rejectsMissingAgent() {
            when(sessionManager.createSession(
                    anyString(), anyString(), anyInt(), any(), any(),
                    anyBoolean(), anyBoolean(), any()))
                    .thenThrow(new IllegalArgumentException("Agent not found: bad-agent"));

            EnforcerSessionRequest request = new EnforcerSessionRequest();
            request.setAgentName("bad-agent");
            request.setRules("rules");

            ResponseEntity<Map<String, Object>> response = controller.createSession(request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertTrue(response.getBody().get("message").toString().contains("Agent not found"));
        }
    }

    // ── Metrics endpoints: the interesting part ─────────────────────

    @Nested
    @DisplayName("GET /metrics — cross-project agent comparison")
    class AllMetrics {

        @Test
        void returnsPerAgentPerFolderBreakdown() {
            // Simulate two agents in two projects with different violation profiles
            when(metricsService.getAllMetrics()).thenReturn(List.of(
                    metricsRow("frontend", "claude", 3, 1, 0, 0.92, 0.95),
                    metricsRow("frontend", "codex", 2, 7, 3, 0.35, 0.20),
                    metricsRow("backend", "claude", 5, 0, 0, 1.0, 1.0)));

            ResponseEntity<List<Map<String, Object>>> response = controller.getAllMetrics();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<Map<String, Object>> body = response.getBody();
            assertEquals(3, body.size());

            // codex in frontend has the worst record
            Map<String, Object> worstAgent = body.stream()
                    .filter(m -> "codex".equals(m.get("agentName")))
                    .findFirst().orElseThrow();
            assertEquals(7, worstAgent.get("totalViolations"));
            assertEquals(3, worstAgent.get("totalInterruptions"));
            assertEquals(0.20, (double) worstAgent.get("lastScore"), 0.01);

            // claude in backend is clean
            Map<String, Object> cleanAgent = body.stream()
                    .filter(m -> "backend".equals(m.get("codingProjectId")))
                    .findFirst().orElseThrow();
            assertEquals(0, cleanAgent.get("totalViolations"));
            assertEquals(1.0, (double) cleanAgent.get("avgScore"), 0.01);
        }

        @Test
        void emptyMetricsReturnsEmptyList() {
            when(metricsService.getAllMetrics()).thenReturn(List.of());

            ResponseEntity<List<Map<String, Object>>> response = controller.getAllMetrics();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /metrics/{codingProjectId} — per-folder view")
    class ProjectMetrics {

        @Test
        void showsAllAgentsForOneProject() {
            when(metricsService.getMetricsForProject("frontend")).thenReturn(List.of(
                    metricsRow("frontend", "claude", 3, 1, 0, 0.92, 0.95),
                    metricsRow("frontend", "codex", 2, 7, 3, 0.35, 0.20)));

            ResponseEntity<List<Map<String, Object>>> response =
                    controller.getProjectMetrics("frontend");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().size());

            // Both entries are for the same project
            assertTrue(response.getBody().stream()
                    .allMatch(m -> "frontend".equals(m.get("codingProjectId"))));
        }
    }

    @Nested
    @DisplayName("GET /metrics/{codingProjectId}/{agentName} — agent drill-down")
    class ProjectAgentMetrics {

        @Test
        void includesViolationHistoryForDrillDown() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("codingProjectId", "frontend");
            detail.put("agentName", "codex");
            detail.put("totalSessions", 2);
            detail.put("totalViolations", 3);
            detail.put("totalInterruptions", 1);
            detail.put("avgScore", 0.45);
            detail.put("lastScore", 0.20);
            detail.put("history", List.of(
                    historyEvent("session_start", null, 1.0, null, List.of()),
                    historyEvent("violation", "TEXT_VIOLATION", 0.7,
                            "used innerHTML", List.of("innerHTML")),
                    historyEvent("violation", "TOOL_VIOLATION", 0.4,
                            "called curl", List.of("curl")),
                    historyEvent("interruption", "CORRECTION", 0.35,
                            "auto-corrected", List.of()),
                    historyEvent("violation", "BLOCKED", 0.2,
                            "exceeded max corrections", List.of()),
                    historyEvent("session_end", null, 0.2,
                            "violations=3 turns=8", List.of())));
            when(metricsService.getMetricsForProjectAgent("frontend", "codex"))
                    .thenReturn(detail);

            ResponseEntity<Map<String, Object>> response =
                    controller.getProjectAgentMetrics("frontend", "codex");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertEquals(3, body.get("totalViolations"));
            assertEquals(1, body.get("totalInterruptions"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");
            assertEquals(6, history.size());

            // Verify the history tells a coherent story
            assertEquals("session_start", history.get(0).get("type"));
            assertEquals("violation", history.get(1).get("type"));
            assertEquals("TEXT_VIOLATION", history.get(1).get("eventType"));
            assertEquals("session_end", history.get(5).get("type"));

            // Score should degrade through the timeline
            double firstScore = (double) history.get(1).get("score");
            double lastScore = (double) history.get(4).get("score");
            assertTrue(firstScore > lastScore,
                    "Score should degrade: first=" + firstScore + " last=" + lastScore);
        }

        @Test
        void returns404ForAgentNeverUsedInProject() {
            when(metricsService.getMetricsForProjectAgent("frontend", "never-used"))
                    .thenReturn(null);

            ResponseEntity<Map<String, Object>> response =
                    controller.getProjectAgentMetrics("frontend", "never-used");

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }
    }

    // ── Violation retrieval for active sessions ─────────────────────

    @Nested
    @DisplayName("GET /sessions/{id}/violations")
    class ActiveSessionViolations {

        @Test
        void returnsViolationEventsFromLiveSession() {
            List<Map<String, Object>> violations = List.of(
                    Map.of("type", "TEXT_VIOLATION", "score", 0.6,
                            "violations", List.of("eval"), "reason", "banned keyword"),
                    Map.of("type", "TOOL_VIOLATION", "score", 0.3,
                            "violations", List.of("rm"), "reason", "banned tool"));
            when(sessionManager.getViolations("s-001")).thenReturn(violations);

            ResponseEntity<List<Map<String, Object>>> response = controller.getViolations("s-001");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().size());
            // Each violation carries the specific banned items
            assertEquals(List.of("eval"), response.getBody().get(0).get("violations"));
        }

        @Test
        void returns404ForNonexistentSession() {
            when(sessionManager.getViolations("ghost")).thenReturn(null);

            ResponseEntity<List<Map<String, Object>>> response = controller.getViolations("ghost");

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Map<String, Object> metricsRow(String projectId, String agent,
                                            int sessions, int violations, int interruptions,
                                            double avgScore, double lastScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codingProjectId", projectId);
        m.put("agentName", agent);
        m.put("totalSessions", sessions);
        m.put("totalViolations", violations);
        m.put("totalInterruptions", interruptions);
        m.put("avgScore", avgScore);
        m.put("lastScore", lastScore);
        m.put("lastUpdated", "2026-06-10T12:00:00Z");
        return m;
    }

    private Map<String, Object> historyEvent(String type, String eventType,
                                              double score, String reason,
                                              List<String> violations) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("eventType", eventType != null ? eventType : "");
        e.put("score", score);
        e.put("reason", reason != null ? reason : "");
        e.put("violations", violations);
        e.put("timestamp", "2026-06-10T12:00:00Z");
        e.put("sessionId", "s-001");
        return e;
    }
}
