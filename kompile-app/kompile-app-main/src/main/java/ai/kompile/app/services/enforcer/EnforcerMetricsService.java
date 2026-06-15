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

package ai.kompile.app.services.enforcer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent metrics tracking for enforcer sessions, keyed by
 * (codingProjectId, agentName). Metrics are persisted to JSON files
 * at {@code .kompile/code-projects/<id>/enforcer-metrics.json}.
 */
@Service
public class EnforcerMetricsService {

    private static final Logger log = LoggerFactory.getLogger(EnforcerMetricsService.class);
    private static final String METRICS_FILENAME = "enforcer-metrics.json";
    private static final int MAX_HISTORY_ENTRIES = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // In-memory cache: codingProjectId -> ProjectMetrics
    private final ConcurrentHashMap<String, ProjectMetrics> metricsCache = new ConcurrentHashMap<>();

    // ========================================================================
    // Data model
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectMetrics {
        @JsonProperty
        public String codingProjectId;
        @JsonProperty
        public Map<String, AgentMetrics> agents = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentMetrics {
        @JsonProperty
        public String agentName;
        @JsonProperty
        public int totalSessions;
        @JsonProperty
        public int totalViolations;
        @JsonProperty
        public int totalInterruptions;
        @JsonProperty
        public double lastScore;
        @JsonProperty
        public double avgScore;
        @JsonProperty
        public double totalScoreSum;
        @JsonProperty
        public int scoreCount;
        @JsonProperty
        public String lastSessionId;
        @JsonProperty
        public String lastUpdated;
        @JsonProperty
        public List<MetricEvent> history = new CopyOnWriteArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricEvent {
        @JsonProperty
        public String timestamp;
        @JsonProperty
        public String sessionId;
        @JsonProperty
        public String type; // "violation", "interruption", "session_start", "session_end"
        @JsonProperty
        public String eventType; // original event type e.g. TEXT_VIOLATION, TOOL_VIOLATION
        @JsonProperty
        public double score;
        @JsonProperty
        public String reason;
        @JsonProperty
        public List<String> violations;
    }

    // ========================================================================
    // Recording methods
    // ========================================================================

    /**
     * Record the start of an enforcer session.
     */
    public void recordSessionStart(String codingProjectId, String agentName, String sessionId) {
        if (codingProjectId == null || codingProjectId.isBlank()) return;

        AgentMetrics metrics = getOrCreateAgentMetrics(codingProjectId, agentName);
        metrics.totalSessions++;
        metrics.lastSessionId = sessionId;
        metrics.lastUpdated = Instant.now().toString();

        MetricEvent event = new MetricEvent();
        event.timestamp = Instant.now().toString();
        event.sessionId = sessionId;
        event.type = "session_start";
        event.score = metrics.lastScore;
        addHistoryEvent(metrics, event);

        persist(codingProjectId);
    }

    /**
     * Record a violation event from an enforcer session.
     */
    public void recordViolation(String codingProjectId, String agentName, String sessionId,
                                 String eventType, double score, String reason,
                                 List<String> violations) {
        if (codingProjectId == null || codingProjectId.isBlank()) return;

        AgentMetrics metrics = getOrCreateAgentMetrics(codingProjectId, agentName);
        metrics.totalViolations++;
        metrics.lastScore = score;
        metrics.lastSessionId = sessionId;
        metrics.lastUpdated = Instant.now().toString();
        updateAvgScore(metrics, score);

        MetricEvent event = new MetricEvent();
        event.timestamp = Instant.now().toString();
        event.sessionId = sessionId;
        event.type = "violation";
        event.eventType = eventType;
        event.score = score;
        event.reason = reason;
        event.violations = violations != null ? violations : List.of();
        addHistoryEvent(metrics, event);

        persist(codingProjectId);
    }

    /**
     * Record an interruption event (any non-violation interrupt from the enforcer).
     */
    public void recordInterruption(String codingProjectId, String agentName, String sessionId,
                                    String eventType, double score, String reason) {
        if (codingProjectId == null || codingProjectId.isBlank()) return;

        AgentMetrics metrics = getOrCreateAgentMetrics(codingProjectId, agentName);
        metrics.totalInterruptions++;
        metrics.lastScore = score;
        metrics.lastSessionId = sessionId;
        metrics.lastUpdated = Instant.now().toString();
        updateAvgScore(metrics, score);

        MetricEvent event = new MetricEvent();
        event.timestamp = Instant.now().toString();
        event.sessionId = sessionId;
        event.type = "interruption";
        event.eventType = eventType;
        event.score = score;
        event.reason = reason;
        addHistoryEvent(metrics, event);

        persist(codingProjectId);
    }

    /**
     * Record the end of an enforcer session with final score.
     */
    public void recordSessionEnd(String codingProjectId, String agentName, String sessionId,
                                  double finalScore, int violations, int totalTurns) {
        if (codingProjectId == null || codingProjectId.isBlank()) return;

        AgentMetrics metrics = getOrCreateAgentMetrics(codingProjectId, agentName);
        metrics.lastScore = finalScore;
        metrics.lastSessionId = sessionId;
        metrics.lastUpdated = Instant.now().toString();
        updateAvgScore(metrics, finalScore);

        MetricEvent event = new MetricEvent();
        event.timestamp = Instant.now().toString();
        event.sessionId = sessionId;
        event.type = "session_end";
        event.score = finalScore;
        event.reason = "violations=" + violations + " turns=" + totalTurns;
        addHistoryEvent(metrics, event);

        persist(codingProjectId);
    }

    // ========================================================================
    // Query methods
    // ========================================================================

    /**
     * Get all metrics across all coding projects.
     */
    public List<Map<String, Object>> getAllMetrics() {
        loadAllFromDisk();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProjectMetrics pm : metricsCache.values()) {
            for (AgentMetrics am : pm.agents.values()) {
                result.add(toSummaryMap(pm.codingProjectId, am));
            }
        }
        result.sort(Comparator.comparing(m -> m.getOrDefault("lastUpdated", "").toString()));
        Collections.reverse(result);
        return result;
    }

    /**
     * Get metrics for a specific coding project.
     */
    public List<Map<String, Object>> getMetricsForProject(String codingProjectId) {
        ProjectMetrics pm = loadProjectMetrics(codingProjectId);
        if (pm == null) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentMetrics am : pm.agents.values()) {
            result.add(toSummaryMap(codingProjectId, am));
        }
        return result;
    }

    /**
     * Get metrics for a specific agent in a specific coding project.
     */
    public Map<String, Object> getMetricsForProjectAgent(String codingProjectId, String agentName) {
        ProjectMetrics pm = loadProjectMetrics(codingProjectId);
        if (pm == null) return null;

        AgentMetrics am = pm.agents.get(agentName);
        if (am == null) return null;

        return toDetailMap(codingProjectId, am);
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private AgentMetrics getOrCreateAgentMetrics(String codingProjectId, String agentName) {
        ProjectMetrics pm = metricsCache.computeIfAbsent(codingProjectId, id -> {
            ProjectMetrics loaded = loadFromDisk(id);
            if (loaded != null) return loaded;
            ProjectMetrics fresh = new ProjectMetrics();
            fresh.codingProjectId = id;
            return fresh;
        });

        return pm.agents.computeIfAbsent(agentName, name -> {
            AgentMetrics am = new AgentMetrics();
            am.agentName = name;
            am.lastUpdated = Instant.now().toString();
            return am;
        });
    }

    private ProjectMetrics loadProjectMetrics(String codingProjectId) {
        return metricsCache.computeIfAbsent(codingProjectId, id -> {
            ProjectMetrics loaded = loadFromDisk(id);
            return loaded; // may be null — computeIfAbsent ignores null
        });
    }

    private void updateAvgScore(AgentMetrics metrics, double score) {
        metrics.totalScoreSum += score;
        metrics.scoreCount++;
        metrics.avgScore = metrics.totalScoreSum / metrics.scoreCount;
    }

    private void addHistoryEvent(AgentMetrics metrics, MetricEvent event) {
        metrics.history.add(event);
        // Trim excess entries in bulk to avoid O(n) per-remove on CopyOnWriteArrayList
        int excess = metrics.history.size() - MAX_HISTORY_ENTRIES;
        if (excess > 0) {
            metrics.history.subList(0, excess).clear();
        }
    }

    private void persist(String codingProjectId) {
        ProjectMetrics pm = metricsCache.get(codingProjectId);
        if (pm == null) return;

        try {
            Path metricsPath = resolveMetricsPath(codingProjectId);
            Files.createDirectories(metricsPath.getParent());
            MAPPER.writeValue(metricsPath.toFile(), pm);
        } catch (IOException e) {
            log.warn("Failed to persist enforcer metrics for {}: {}", codingProjectId, e.getMessage());
        }
    }

    private ProjectMetrics loadFromDisk(String codingProjectId) {
        Path metricsPath = resolveMetricsPath(codingProjectId);
        if (!Files.exists(metricsPath)) return null;
        try {
            return MAPPER.readValue(metricsPath.toFile(), ProjectMetrics.class);
        } catch (IOException e) {
            log.warn("Failed to load enforcer metrics for {}: {}", codingProjectId, e.getMessage());
            return null;
        }
    }

    private void loadAllFromDisk() {
        Path codeProjectsDir = resolveProjectRoot().resolve(".kompile").resolve("code-projects");
        if (!Files.isDirectory(codeProjectsDir)) return;
        try (var dirs = Files.list(codeProjectsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                if (!metricsCache.containsKey(id)) {
                    ProjectMetrics pm = loadFromDisk(id);
                    if (pm != null) {
                        metricsCache.put(id, pm);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan code-projects directory: {}", e.getMessage());
        }
    }

    private Path resolveMetricsPath(String codingProjectId) {
        return resolveProjectRoot().resolve(".kompile").resolve("code-projects")
                .resolve(codingProjectId).resolve(METRICS_FILENAME);
    }

    private Path resolveProjectRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Map<String, Object> toSummaryMap(String codingProjectId, AgentMetrics am) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("codingProjectId", codingProjectId);
        map.put("agentName", am.agentName);
        map.put("totalSessions", am.totalSessions);
        map.put("totalViolations", am.totalViolations);
        map.put("totalInterruptions", am.totalInterruptions);
        map.put("lastScore", am.lastScore);
        map.put("avgScore", am.avgScore);
        map.put("lastSessionId", am.lastSessionId != null ? am.lastSessionId : "");
        map.put("lastUpdated", am.lastUpdated != null ? am.lastUpdated : "");
        return map;
    }

    private Map<String, Object> toDetailMap(String codingProjectId, AgentMetrics am) {
        Map<String, Object> map = toSummaryMap(codingProjectId, am);
        map.put("scoreCount", am.scoreCount);
        map.put("totalScoreSum", am.totalScoreSum);
        List<Map<String, Object>> historyList = new ArrayList<>();
        for (MetricEvent event : am.history) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("timestamp", event.timestamp);
            em.put("sessionId", event.sessionId != null ? event.sessionId : "");
            em.put("type", event.type);
            em.put("eventType", event.eventType != null ? event.eventType : "");
            em.put("score", event.score);
            em.put("reason", event.reason != null ? event.reason : "");
            em.put("violations", event.violations != null ? event.violations : List.of());
            historyList.add(em);
        }
        map.put("history", historyList);
        return map;
    }
}
