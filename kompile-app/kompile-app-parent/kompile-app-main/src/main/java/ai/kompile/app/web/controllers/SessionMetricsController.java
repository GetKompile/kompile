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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.SessionMetricsService;
import ai.kompile.app.services.SessionMetricsService.SessionMetricsSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for session-level metrics (token usage, tool calls, timing).
 * Reads from ~/.kompile/conversations/*.metrics.json files.
 */
@RestController
@RequestMapping("/api/session-metrics")
public class SessionMetricsController {

    private final SessionMetricsService metricsService;

    public SessionMetricsController(SessionMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * List all session metrics, sorted by most recent first.
     */
    @GetMapping
    public ResponseEntity<List<SessionMetricsSummary>> listAll() {
        return ResponseEntity.ok(metricsService.listAll());
    }

    /**
     * Get aggregated statistics: total tokens, tool calls, breakdowns by
     * provider/model/agent, and tool breakdown across all sessions.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(metricsService.getAggregatedStats());
    }

    /**
     * Get sessions grouped by project directory, with per-project token
     * and tool call totals.
     */
    @GetMapping("/by-project")
    public ResponseEntity<Map<String, Object>> byProject() {
        return ResponseEntity.ok(metricsService.getByProject());
    }

    /**
     * Get per-provider token usage from actual provider transcripts
     * (Claude Code, Codex, Gemini, Qwen, Pi, etc.).
     */
    @GetMapping("/provider-usage")
    public ResponseEntity<Map<String, Object>> providerUsage() {
        return ResponseEntity.ok(metricsService.getProviderUsageStats());
    }

    /**
     * Get a single session's metrics by session ID.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionMetricsSummary> bySessionId(@PathVariable String sessionId) {
        SessionMetricsSummary summary = metricsService.getBySessionId(sessionId);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(summary);
    }
}
