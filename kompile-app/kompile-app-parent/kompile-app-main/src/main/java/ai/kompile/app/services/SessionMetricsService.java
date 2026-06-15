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

package ai.kompile.app.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads {@code ~/.kompile/conversations/*.metrics.json} files and provider
 * transcript token usage from {@code ~/.kompile/conversations/provider-usage/*.json}
 * to provide aggregated token-usage and tool-call statistics broken down by
 * provider, project, and per chat transcript/session.
 */
@Service
public class SessionMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(SessionMetricsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path getConversationsDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "conversations");
    }

    private Path getProviderUsageDir() {
        return getConversationsDir().resolve("provider-usage");
    }

    /**
     * Load all session metrics from disk.
     */
    public List<SessionMetricsSummary> listAll() {
        Path dir = getConversationsDir();
        if (!Files.isDirectory(dir)) return Collections.emptyList();

        List<SessionMetricsSummary> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.metrics.json")) {
            for (Path file : stream) {
                try {
                    SessionMetricsSummary summary = parseMetricsFile(file);
                    if (summary != null) result.add(summary);
                } catch (Exception e) {
                    logger.debug("Skipping malformed metrics file: {}", file, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read conversations directory: {}", e.getMessage());
        }

        result.sort(Comparator.comparing(
                (SessionMetricsSummary s) -> s.started != null ? s.started : "").reversed());
        return result;
    }

    /**
     * Get a single session's metrics by session ID.
     */
    public SessionMetricsSummary getBySessionId(String sessionId) {
        Path dir = getConversationsDir();
        Path file = dir.resolve(sessionId + ".metrics.json");
        if (!Files.exists(file)) return null;
        try {
            return parseMetricsFile(file);
        } catch (Exception e) {
            logger.warn("Failed to read metrics for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Aggregate stats across all sessions, with breakdowns by project and per session.
     * Merges kompile .metrics.json data with provider transcript token usage.
     */
    public Map<String, Object> getAggregatedStats() {
        List<SessionMetricsSummary> all = listAll();
        List<ProviderUsageEntry> providerUsage = loadProviderUsage();
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalSessions", all.size());

        // Global token totals from kompile metrics
        long totalInput = 0, totalOutput = 0, totalCacheRead = 0, totalCacheCreation = 0;
        long totalToolCalls = 0, totalToolErrors = 0;
        long totalDurationSeconds = 0;
        int totalUserTurns = 0, totalAssistantTurns = 0;

        for (SessionMetricsSummary s : all) {
            totalInput += s.inputTokens;
            totalOutput += s.outputTokens;
            totalCacheRead += s.cacheReadTokens;
            totalCacheCreation += s.cacheCreationTokens;
            totalToolCalls += s.totalToolCalls;
            totalToolErrors += s.totalToolErrors;
            totalDurationSeconds += s.durationSeconds;
            totalUserTurns += s.userTurns;
            totalAssistantTurns += s.assistantTurns;
        }

        // Add provider transcript tokens
        long providerInput = providerUsage.stream().mapToLong(p -> p.inputTokens).sum();
        long providerOutput = providerUsage.stream().mapToLong(p -> p.outputTokens).sum();
        long providerCacheRead = providerUsage.stream().mapToLong(p -> p.cacheReadTokens).sum();
        long providerCacheCreation = providerUsage.stream().mapToLong(p -> p.cacheCreationTokens).sum();

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("totalInput", totalInput + providerInput);
        tokens.put("totalOutput", totalOutput + providerOutput);
        tokens.put("total", totalInput + totalOutput + providerInput + providerOutput);
        tokens.put("cacheRead", totalCacheRead + providerCacheRead);
        tokens.put("cacheCreation", totalCacheCreation + providerCacheCreation);
        stats.put("tokens", tokens);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("totalCalls", totalToolCalls);
        tools.put("totalErrors", totalToolErrors);
        stats.put("tools", tools);

        Map<String, Object> turns = new LinkedHashMap<>();
        turns.put("user", totalUserTurns);
        turns.put("assistant", totalAssistantTurns);
        turns.put("total", totalUserTurns + totalAssistantTurns);
        stats.put("turns", turns);

        stats.put("totalDurationSeconds", totalDurationSeconds);

        // By provider — merge kompile metrics + provider transcript data
        Map<String, Long> byProvider = new LinkedHashMap<>();
        all.stream().filter(s -> s.provider != null)
                .forEach(s -> byProvider.merge(s.provider, s.inputTokens + s.outputTokens, Long::sum));
        providerUsage.stream().filter(p -> p.provider != null)
                .forEach(p -> byProvider.merge(p.provider, p.inputTokens + p.outputTokens, Long::sum));
        stats.put("tokensByProvider", sortDesc(byProvider));

        // By model — merge
        Map<String, Long> byModel = new LinkedHashMap<>();
        all.stream().filter(s -> s.model != null)
                .forEach(s -> byModel.merge(s.model, s.inputTokens + s.outputTokens, Long::sum));
        providerUsage.stream().filter(p -> p.model != null)
                .forEach(p -> byModel.merge(p.model, p.inputTokens + p.outputTokens, Long::sum));
        stats.put("tokensByModel", sortDesc(byModel));

        // By agent — from kompile metrics only
        Map<String, Long> byAgent = all.stream()
                .filter(s -> s.agent != null)
                .collect(Collectors.groupingBy(s -> s.agent,
                        Collectors.summingLong(s -> s.inputTokens + s.outputTokens)));
        stats.put("tokensByAgent", sortDesc(byAgent));

        // Tool breakdown across all sessions
        Map<String, Long> toolBreakdown = new LinkedHashMap<>();
        for (SessionMetricsSummary s : all) {
            if (s.toolBreakdown != null) {
                s.toolBreakdown.forEach((tool, count) ->
                        toolBreakdown.merge(tool, (long) count, Long::sum));
            }
        }
        stats.put("toolBreakdown", sortDesc(toolBreakdown));

        // Provider transcript stats
        stats.put("providerSessionCount", providerUsage.size());
        stats.put("providerTotalInput", providerInput);
        stats.put("providerTotalOutput", providerOutput);

        return stats;
    }

    /**
     * Get per-provider token usage stats from provider transcripts.
     */
    public Map<String, Object> getProviderUsageStats() {
        List<ProviderUsageEntry> entries = loadProviderUsage();
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("totalSessions", entries.size());
        result.put("totalInputTokens", entries.stream().mapToLong(e -> e.inputTokens).sum());
        result.put("totalOutputTokens", entries.stream().mapToLong(e -> e.outputTokens).sum());
        result.put("totalTokens", entries.stream().mapToLong(e -> e.inputTokens + e.outputTokens).sum());

        // By provider
        Map<String, Map<String, Object>> byProvider = new LinkedHashMap<>();
        Map<String, List<ProviderUsageEntry>> grouped = entries.stream()
                .filter(e -> e.provider != null)
                .collect(Collectors.groupingBy(e -> e.provider, LinkedHashMap::new, Collectors.toList()));

        for (var entry : grouped.entrySet()) {
            List<ProviderUsageEntry> sessions = entry.getValue();
            Map<String, Object> providerStats = new LinkedHashMap<>();
            providerStats.put("sessionCount", sessions.size());
            providerStats.put("inputTokens", sessions.stream().mapToLong(s -> s.inputTokens).sum());
            providerStats.put("outputTokens", sessions.stream().mapToLong(s -> s.outputTokens).sum());
            providerStats.put("totalTokens", sessions.stream().mapToLong(s -> s.inputTokens + s.outputTokens).sum());
            providerStats.put("cacheReadTokens", sessions.stream().mapToLong(s -> s.cacheReadTokens).sum());
            providerStats.put("cacheCreationTokens", sessions.stream().mapToLong(s -> s.cacheCreationTokens).sum());
            providerStats.put("thinkingTokens", sessions.stream().mapToLong(s -> s.thinkingTokens).sum());
            providerStats.put("apiCalls", sessions.stream().mapToInt(s -> s.apiCalls).sum());

            // Models used in this provider
            Map<String, Long> models = sessions.stream()
                    .filter(s -> s.model != null)
                    .collect(Collectors.groupingBy(s -> s.model,
                            Collectors.summingLong(s -> s.inputTokens + s.outputTokens)));
            providerStats.put("byModel", sortDesc(models));

            // Projects in this provider
            Map<String, Long> projects = sessions.stream()
                    .filter(s -> s.projectDirectory != null)
                    .collect(Collectors.groupingBy(s -> s.projectDirectory,
                            Collectors.summingLong(s -> s.inputTokens + s.outputTokens)));
            providerStats.put("byProject", sortDesc(projects));

            providerStats.put("sessions", sessions);

            byProvider.put(entry.getKey(), providerStats);
        }

        result.put("byProvider", byProvider);
        return result;
    }

    /**
     * Group sessions by project directory.
     */
    public Map<String, Object> getByProject() {
        List<SessionMetricsSummary> all = listAll();

        // Also try to correlate with tool call JSONL for project directories
        Map<String, String> sessionToProject = loadSessionProjects();

        Map<String, List<SessionMetricsSummary>> grouped = new LinkedHashMap<>();
        for (SessionMetricsSummary s : all) {
            String project = sessionToProject.getOrDefault(s.sessionId, "(unknown)");
            grouped.computeIfAbsent(project, k -> new ArrayList<>()).add(s);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> projects = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            List<SessionMetricsSummary> sessions = entry.getValue();
            Map<String, Object> projectStats = new LinkedHashMap<>();
            projectStats.put("sessionCount", sessions.size());

            long inputTokens = sessions.stream().mapToLong(s -> s.inputTokens).sum();
            long outputTokens = sessions.stream().mapToLong(s -> s.outputTokens).sum();
            long cacheRead = sessions.stream().mapToLong(s -> s.cacheReadTokens).sum();
            long toolCalls = sessions.stream().mapToLong(s -> s.totalToolCalls).sum();

            Map<String, Object> tokenSummary = new LinkedHashMap<>();
            tokenSummary.put("input", inputTokens);
            tokenSummary.put("output", outputTokens);
            tokenSummary.put("total", inputTokens + outputTokens);
            tokenSummary.put("cacheRead", cacheRead);
            projectStats.put("tokens", tokenSummary);
            projectStats.put("toolCalls", toolCalls);
            projectStats.put("sessions", sessions);

            projects.put(entry.getKey(), projectStats);
        }
        result.put("projects", projects);
        result.put("totalProjects", grouped.size());
        result.put("totalSessions", all.size());
        return result;
    }

    /**
     * Parse a single .metrics.json file into a summary object.
     */
    private SessionMetricsSummary parseMetricsFile(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        SessionMetricsSummary summary = new SessionMetricsSummary();

        // Session info
        JsonNode session = root.path("session");
        summary.sessionId = session.path("sessionId").asText(
                file.getFileName().toString().replace(".metrics.json", ""));
        summary.started = session.path("started").asText(null);
        summary.ended = session.path("ended").asText(null);
        summary.durationSeconds = session.path("durationSeconds").asLong(0);
        summary.provider = session.path("provider").asText(null);
        summary.model = session.path("model").asText(null);
        summary.agent = session.path("agent").asText(null);
        summary.ragEnabled = session.path("ragEnabled").asBoolean(false);

        // Turns
        JsonNode turnsNode = root.path("turns");
        summary.userTurns = turnsNode.path("user").asInt(0);
        summary.assistantTurns = turnsNode.path("assistant").asInt(0);
        summary.totalTurns = turnsNode.path("total").asInt(0);

        // Tokens — prefer actual, fall back to estimated
        JsonNode tokensNode = root.path("tokens");
        summary.inputTokens = tokensNode.path("input").asLong(
                tokensNode.path("estimatedInput").asLong(0));
        summary.outputTokens = tokensNode.path("output").asLong(
                tokensNode.path("estimatedOutput").asLong(0));
        summary.cacheReadTokens = tokensNode.path("cacheRead").asLong(0);
        summary.cacheCreationTokens = tokensNode.path("cacheCreation").asLong(0);

        // Timing
        JsonNode timing = root.path("timing");
        summary.apiCalls = timing.path("apiCalls").asInt(0);
        summary.avgResponseTimeMs = timing.path("avgResponseTimeMs").asLong(0);

        // Tools
        JsonNode toolsNode = root.path("tools");
        summary.totalToolCalls = toolsNode.path("totalCalls").asInt(0);
        summary.totalToolErrors = toolsNode.path("totalErrors").asInt(0);
        JsonNode breakdown = toolsNode.path("breakdown");
        if (breakdown.isObject()) {
            summary.toolBreakdown = new LinkedHashMap<>();
            breakdown.fields().forEachRemaining(f ->
                    summary.toolBreakdown.put(f.getKey(), f.getValue().asInt(0)));
        }

        // Agentic
        JsonNode agentic = root.path("agentic");
        summary.agenticSteps = agentic.path("steps").asInt(0);
        summary.compactions = agentic.path("compactions").asInt(0);
        summary.thinkingTokens = agentic.path("thinkingTokens").asLong(0);
        summary.subagentsSpawned = agentic.path("subagentsSpawned").asInt(0);

        return summary;
    }

    /**
     * Load session-to-project mappings from tool-call JSONL files.
     */
    private Map<String, String> loadSessionProjects() {
        Map<String, String> map = new LinkedHashMap<>();
        Path indexFile = Path.of(System.getProperty("user.home"),
                ".kompile", "conversations", "tool-calls", "all-tool-calls.jsonl");
        if (!Files.exists(indexFile)) return map;

        try {
            List<String> lines = Files.readAllLines(indexFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sessionId = node.path("sessionId").asText(null);
                    String project = node.path("projectDirectory").asText(null);
                    if (sessionId != null && project != null && !map.containsKey(sessionId)) {
                        map.put(sessionId, project);
                    }
                } catch (Exception e) {
                    logger.debug("Skipping malformed tool-calls index line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to read tool-calls index for project mappings: {}", e.getMessage());
        }
        return map;
    }

    private static Map<String, Long> sortDesc(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Load provider token usage from ~/.kompile/conversations/provider-usage/*.json
     */
    private List<ProviderUsageEntry> loadProviderUsage() {
        Path dir = getProviderUsageDir();
        if (!Files.isDirectory(dir)) return Collections.emptyList();

        List<ProviderUsageEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    JsonNode root = MAPPER.readTree(file.toFile());
                    ProviderUsageEntry entry = new ProviderUsageEntry();
                    entry.sessionId = root.path("sessionId").asText(
                            file.getFileName().toString().replace(".json", ""));
                    entry.source = root.path("source").asText(null);
                    entry.provider = root.path("provider").asText(null);
                    entry.model = root.path("model").asText(null);
                    entry.projectDirectory = root.path("projectDirectory").asText(null);
                    entry.inputTokens = root.path("inputTokens").asLong(0);
                    entry.outputTokens = root.path("outputTokens").asLong(0);
                    entry.totalTokens = root.path("totalTokens").asLong(0);
                    entry.cacheReadTokens = root.path("cacheReadTokens").asLong(0);
                    entry.cacheCreationTokens = root.path("cacheCreationTokens").asLong(0);
                    entry.thinkingTokens = root.path("thinkingTokens").asLong(0);
                    entry.apiCalls = root.path("apiCalls").asInt(0);
                    entry.indexedAt = root.path("indexedAt").asText(null);
                    entries.add(entry);
                } catch (Exception e) {
                    logger.debug("Skipping malformed provider usage file: {}", file, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read provider-usage directory: {}", e.getMessage());
        }
        return entries;
    }

    /**
     * Summary of a single session's metrics, parsed from a .metrics.json file.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionMetricsSummary {
        @JsonProperty public String sessionId;
        @JsonProperty public String started;
        @JsonProperty public String ended;
        @JsonProperty public long durationSeconds;
        @JsonProperty public String provider;
        @JsonProperty public String model;
        @JsonProperty public String agent;
        @JsonProperty public boolean ragEnabled;

        @JsonProperty public int userTurns;
        @JsonProperty public int assistantTurns;
        @JsonProperty public int totalTurns;

        @JsonProperty public long inputTokens;
        @JsonProperty public long outputTokens;
        @JsonProperty public long cacheReadTokens;
        @JsonProperty public long cacheCreationTokens;

        @JsonProperty public int apiCalls;
        @JsonProperty public long avgResponseTimeMs;

        @JsonProperty public int totalToolCalls;
        @JsonProperty public int totalToolErrors;
        @JsonProperty public Map<String, Integer> toolBreakdown;

        @JsonProperty public int agenticSteps;
        @JsonProperty public int compactions;
        @JsonProperty public long thinkingTokens;
        @JsonProperty public int subagentsSpawned;
    }

    /**
     * Token usage entry from a provider transcript (Claude Code, Codex, Gemini, Qwen, etc.).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderUsageEntry {
        @JsonProperty public String sessionId;
        @JsonProperty public String source;
        @JsonProperty public String provider;
        @JsonProperty public String model;
        @JsonProperty public String projectDirectory;
        @JsonProperty public long inputTokens;
        @JsonProperty public long outputTokens;
        @JsonProperty public long totalTokens;
        @JsonProperty public long cacheReadTokens;
        @JsonProperty public long cacheCreationTokens;
        @JsonProperty public long thinkingTokens;
        @JsonProperty public int apiCalls;
        @JsonProperty public String indexedAt;
    }
}
