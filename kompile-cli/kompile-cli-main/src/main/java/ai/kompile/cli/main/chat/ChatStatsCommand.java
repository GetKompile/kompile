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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * CLI command to display tool call and token usage statistics from
 * chat session metrics files (~/.kompile/conversations/*.metrics.json).
 * Breaks down by project and per chat transcript.
 */
@CommandLine.Command(
        name = "stats",
        description = "Show tool call and token usage statistics across chat sessions",
        mixinStandardHelpOptions = true
)
public class ChatStatsCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--project"}, description = "Filter by project directory (substring match)")
    private String projectFilter;

    @CommandLine.Option(names = {"--session"}, description = "Show details for a specific session ID")
    private String sessionId;

    @CommandLine.Option(names = {"--last", "-n"}, description = "Show only the last N sessions", defaultValue = "0")
    private int lastN;

    @CommandLine.Option(names = {"--json"}, description = "Output as JSON", defaultValue = "false")
    private boolean jsonOutput;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";
    private static final String WHITE = "\033[37m";

    @Override
    public Integer call() {
        Path conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        if (!Files.isDirectory(conversationsDir)) {
            System.err.println("No conversations directory found at: " + conversationsDir);
            return 1;
        }

        // Load all metrics files
        List<SessionData> sessions = loadAllMetrics(conversationsDir);

        // Load provider transcript token usage
        List<ProviderUsageData> providerUsage = loadProviderUsage(conversationsDir);

        if (sessions.isEmpty() && providerUsage.isEmpty()) {
            System.err.println("No session metrics found. Run a chat session first.");
            return 0;
        }

        // Correlate with project directories from tool-call index
        Map<String, String> sessionToProject = loadSessionProjects(conversationsDir);
        for (SessionData s : sessions) {
            s.projectDirectory = sessionToProject.getOrDefault(s.sessionId, null);
        }

        // Single session detail
        if (sessionId != null && !sessionId.isBlank()) {
            SessionData match = sessions.stream()
                    .filter(s -> s.sessionId.contains(sessionId))
                    .findFirst().orElse(null);
            if (match == null) {
                System.err.println("Session not found: " + sessionId);
                return 1;
            }
            if (jsonOutput) {
                printJson(match);
            } else {
                printSessionDetail(match);
            }
            return 0;
        }

        // Apply project filter
        if (projectFilter != null && !projectFilter.isBlank()) {
            String filter = projectFilter.toLowerCase();
            sessions = sessions.stream()
                    .filter(s -> s.projectDirectory != null
                            && s.projectDirectory.toLowerCase().contains(filter))
                    .collect(Collectors.toList());
            providerUsage = providerUsage.stream()
                    .filter(p -> p.projectDirectory != null
                            && p.projectDirectory.toLowerCase().contains(filter))
                    .collect(Collectors.toList());
        }

        // Apply --last N
        if (lastN > 0 && sessions.size() > lastN) {
            sessions = sessions.subList(0, lastN);
        }

        if (jsonOutput) {
            printJsonOverview(sessions, providerUsage);
        } else {
            printOverview(sessions, providerUsage);
        }

        return 0;
    }

    private void printOverview(List<SessionData> sessions, List<ProviderUsageData> providerUsage) {
        // Global totals from kompile metrics
        long totalInput = 0, totalOutput = 0, totalCacheRead = 0;
        int totalToolCalls = 0, totalToolErrors = 0;
        long totalDuration = 0;
        int totalUserTurns = 0, totalAssistantTurns = 0;
        Map<String, Long> toolBreakdown = new LinkedHashMap<>();

        for (SessionData s : sessions) {
            totalInput += s.inputTokens;
            totalOutput += s.outputTokens;
            totalCacheRead += s.cacheReadTokens;
            totalToolCalls += s.totalToolCalls;
            totalToolErrors += s.totalToolErrors;
            totalDuration += s.durationSeconds;
            totalUserTurns += s.userTurns;
            totalAssistantTurns += s.assistantTurns;
            if (s.toolBreakdown != null) {
                s.toolBreakdown.forEach((tool, count) ->
                        toolBreakdown.merge(tool, (long) count, Long::sum));
            }
        }

        // Add provider transcript token totals
        long provInput = providerUsage.stream().mapToLong(p -> p.inputTokens).sum();
        long provOutput = providerUsage.stream().mapToLong(p -> p.outputTokens).sum();
        long provCacheRead = providerUsage.stream().mapToLong(p -> p.cacheReadTokens).sum();

        System.out.println();
        System.out.println(BOLD + CYAN + "  Chat Usage Statistics" + RESET);
        System.out.println(DIM + "  " + "\u2500".repeat(50) + RESET);

        // Summary
        System.out.println();
        System.out.println(BOLD + "  Overview" + RESET);
        System.out.printf("  Sessions:    %s%d%s", WHITE, sessions.size(), RESET);
        if (!providerUsage.isEmpty()) {
            System.out.printf("  %s(+ %d provider transcripts)%s", DIM, providerUsage.size(), RESET);
        }
        System.out.println();
        System.out.printf("  Duration:    %s%s%s%n", WHITE, formatDuration(totalDuration), RESET);
        System.out.printf("  Turns:       %s%d user / %d assistant%s%n",
                WHITE, totalUserTurns, totalAssistantTurns, RESET);

        // Token usage (combined)
        System.out.println();
        System.out.println(BOLD + "  Token Usage (All Sources)" + RESET);
        System.out.printf("  Input:       %s%s%s%n", GREEN, formatNum(totalInput + provInput), RESET);
        System.out.printf("  Output:      %s%s%s%n", GREEN, formatNum(totalOutput + provOutput), RESET);
        System.out.printf("  Total:       %s%s%s%n", BOLD + GREEN,
                formatNum(totalInput + totalOutput + provInput + provOutput), RESET);
        if (totalCacheRead + provCacheRead > 0) {
            System.out.printf("  Cache Read:  %s%s%s%n", CYAN, formatNum(totalCacheRead + provCacheRead), RESET);
        }

        // Per-provider breakdown
        if (!providerUsage.isEmpty()) {
            System.out.println();
            System.out.println(BOLD + "  By Provider" + RESET);
            System.out.println(DIM + "  " + "\u2500".repeat(50) + RESET);

            Map<String, List<ProviderUsageData>> byProv = providerUsage.stream()
                    .filter(p -> p.provider != null)
                    .collect(Collectors.groupingBy(p -> p.provider, LinkedHashMap::new, Collectors.toList()));

            for (var entry : byProv.entrySet()) {
                List<ProviderUsageData> pSessions = entry.getValue();
                long pInput = pSessions.stream().mapToLong(p -> p.inputTokens).sum();
                long pOutput = pSessions.stream().mapToLong(p -> p.outputTokens).sum();
                long pCache = pSessions.stream().mapToLong(p -> p.cacheReadTokens).sum();
                int pCalls = pSessions.stream().mapToInt(p -> p.apiCalls).sum();

                System.out.printf("  %s%-16s%s  %s%s in / %s out%s  %ssessions: %d  api: %d%s%n",
                        BOLD + CYAN, entry.getKey(), RESET,
                        GREEN, formatNum(pInput), formatNum(pOutput), RESET,
                        DIM, pSessions.size(), pCalls, RESET);

                // Show models used
                Map<String, Long> models = pSessions.stream()
                        .filter(p -> p.model != null)
                        .collect(Collectors.groupingBy(p -> p.model,
                                Collectors.summingLong(p -> p.inputTokens + p.outputTokens)));
                models.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(5)
                        .forEach(m -> System.out.printf("    %s%-20s %s%s%s%n",
                                DIM, m.getKey(), WHITE, formatNum(m.getValue()), RESET));
            }
        }

        // Tool calls
        System.out.println();
        System.out.println(BOLD + "  Tool Calls" + RESET);
        System.out.printf("  Total:       %s%d%s%n", WHITE, totalToolCalls, RESET);
        if (totalToolErrors > 0) {
            System.out.printf("  Errors:      %s%d%s%n", YELLOW, totalToolErrors, RESET);
        }

        // Top tools
        if (!toolBreakdown.isEmpty()) {
            System.out.println();
            System.out.println(BOLD + "  Top Tools" + RESET);
            toolBreakdown.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> System.out.printf("  %-20s %s%d%s%n",
                            e.getKey(), DIM, e.getValue(), RESET));
        }

        // By project
        Map<String, List<SessionData>> byProject = sessions.stream()
                .collect(Collectors.groupingBy(
                        s -> s.projectDirectory != null ? s.projectDirectory : "(unknown)",
                        LinkedHashMap::new, Collectors.toList()));

        // Merge provider usage into project breakdown
        Map<String, List<ProviderUsageData>> provByProject = providerUsage.stream()
                .filter(p -> p.projectDirectory != null)
                .collect(Collectors.groupingBy(p -> p.projectDirectory, LinkedHashMap::new, Collectors.toList()));

        Set<String> allProjects = new LinkedHashSet<>(byProject.keySet());
        allProjects.addAll(provByProject.keySet());

        if (allProjects.size() > 1 || !allProjects.contains("(unknown)")) {
            System.out.println();
            System.out.println(BOLD + "  By Project" + RESET);
            System.out.println(DIM + "  " + "\u2500".repeat(50) + RESET);

            for (String project : allProjects) {
                List<SessionData> projSessions = byProject.getOrDefault(project, Collections.emptyList());
                List<ProviderUsageData> projProvider = provByProject.getOrDefault(project, Collections.emptyList());

                long projInput = projSessions.stream().mapToLong(s -> s.inputTokens).sum()
                        + projProvider.stream().mapToLong(p -> p.inputTokens).sum();
                long projOutput = projSessions.stream().mapToLong(s -> s.outputTokens).sum()
                        + projProvider.stream().mapToLong(p -> p.outputTokens).sum();
                int projTools = projSessions.stream().mapToInt(s -> s.totalToolCalls).sum();
                int totalSess = projSessions.size() + projProvider.size();

                String shortName = getProjectShortName(project);
                System.out.println();
                System.out.printf("  %s%s%s %s(%d sessions)%s%n",
                        BOLD + CYAN, shortName, RESET, DIM, totalSess, RESET);
                System.out.printf("    Tokens: %s%s in / %s out%s  |  Tools: %s%d%s%n",
                        GREEN, formatNum(projInput), formatNum(projOutput), RESET,
                        WHITE, projTools, RESET);

                // List kompile sessions
                for (SessionData s : projSessions) {
                    System.out.printf("    %s%-24s%s  %s%s in / %s out%s  %stools: %d%s  %s%s%s%n",
                            DIM, s.sessionId, RESET,
                            GREEN, formatNum(s.inputTokens), formatNum(s.outputTokens), RESET,
                            DIM, s.totalToolCalls, RESET,
                            DIM, formatDuration(s.durationSeconds), RESET);
                }
                // List provider sessions
                for (ProviderUsageData p : projProvider) {
                    System.out.printf("    %s%-24s%s  %s%s in / %s out%s  %s[%s]%s%n",
                            DIM, truncId(p.sessionId), RESET,
                            GREEN, formatNum(p.inputTokens), formatNum(p.outputTokens), RESET,
                            CYAN, p.provider, RESET);
                }
            }
        } else {
            // Single/unknown project — just list sessions
            System.out.println();
            System.out.println(BOLD + "  Sessions" + RESET);
            System.out.println(DIM + "  " + "\u2500".repeat(50) + RESET);
            for (SessionData s : sessions) {
                System.out.printf("  %s%-24s%s  %s%s in / %s out%s  tools: %s%d%s  %s%s%s%n",
                        CYAN, s.sessionId, RESET,
                        GREEN, formatNum(s.inputTokens), formatNum(s.outputTokens), RESET,
                        WHITE, s.totalToolCalls, RESET,
                        DIM, formatDuration(s.durationSeconds), RESET);
            }
        }

        System.out.println();
    }

    private void printSessionDetail(SessionData s) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  Session: " + s.sessionId + RESET);
        System.out.println(DIM + "  " + "\u2500".repeat(50) + RESET);

        if (s.started != null) System.out.printf("  Started:     %s%s%n", s.started, RESET);
        if (s.ended != null) System.out.printf("  Ended:       %s%s%n", s.ended, RESET);
        System.out.printf("  Duration:    %s%n", formatDuration(s.durationSeconds));
        if (s.provider != null) System.out.printf("  Provider:    %s%n", s.provider);
        if (s.model != null) System.out.printf("  Model:       %s%n", s.model);
        if (s.agent != null) System.out.printf("  Agent:       %s%n", s.agent);
        if (s.projectDirectory != null) System.out.printf("  Project:     %s%n", s.projectDirectory);

        System.out.println();
        System.out.println(BOLD + "  Turns" + RESET);
        System.out.printf("  User:        %d%n", s.userTurns);
        System.out.printf("  Assistant:   %d%n", s.assistantTurns);
        System.out.printf("  Total:       %d%n", s.totalTurns);

        System.out.println();
        System.out.println(BOLD + "  Token Usage" + RESET);
        System.out.printf("  Input:       %s%s%s%n", GREEN, formatNum(s.inputTokens), RESET);
        System.out.printf("  Output:      %s%s%s%n", GREEN, formatNum(s.outputTokens), RESET);
        System.out.printf("  Total:       %s%s%s%n", BOLD + GREEN, formatNum(s.inputTokens + s.outputTokens), RESET);
        if (s.cacheReadTokens > 0) System.out.printf("  Cache Read:  %s%s%s%n", CYAN, formatNum(s.cacheReadTokens), RESET);
        if (s.cacheCreationTokens > 0) System.out.printf("  Cache New:   %s%s%s%n", CYAN, formatNum(s.cacheCreationTokens), RESET);

        System.out.println();
        System.out.println(BOLD + "  Tool Calls" + RESET);
        System.out.printf("  Total:       %d%n", s.totalToolCalls);
        if (s.totalToolErrors > 0) System.out.printf("  Errors:      %s%d%s%n", YELLOW, s.totalToolErrors, RESET);

        if (s.toolBreakdown != null && !s.toolBreakdown.isEmpty()) {
            System.out.println();
            System.out.println(BOLD + "  Tool Breakdown" + RESET);
            s.toolBreakdown.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  %-20s %d%n", e.getKey(), e.getValue()));
        }

        if (s.agenticSteps > 0 || s.compactions > 0) {
            System.out.println();
            System.out.println(BOLD + "  Agentic" + RESET);
            if (s.agenticSteps > 0) System.out.printf("  Steps:       %d%n", s.agenticSteps);
            if (s.compactions > 0) System.out.printf("  Compactions: %d%n", s.compactions);
            if (s.thinkingTokens > 0) System.out.printf("  Thinking:    %s tokens%n", formatNum(s.thinkingTokens));
            if (s.subagentsSpawned > 0) System.out.printf("  Subagents:   %d%n", s.subagentsSpawned);
        }

        System.out.println();
    }

    private void printJson(SessionData s) {
        try {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s));
        } catch (Exception e) {
            System.err.println("Failed to serialize session: " + e.getMessage());
        }
    }

    private void printJsonOverview(List<SessionData> sessions, List<ProviderUsageData> providerUsage) {
        Map<String, Object> output = new LinkedHashMap<>();

        long totalInput = sessions.stream().mapToLong(s -> s.inputTokens).sum();
        long totalOutput = sessions.stream().mapToLong(s -> s.outputTokens).sum();
        long totalCacheRead = sessions.stream().mapToLong(s -> s.cacheReadTokens).sum();
        int totalToolCalls = sessions.stream().mapToInt(s -> s.totalToolCalls).sum();

        long provInput = providerUsage.stream().mapToLong(p -> p.inputTokens).sum();
        long provOutput = providerUsage.stream().mapToLong(p -> p.outputTokens).sum();
        long provCacheRead = providerUsage.stream().mapToLong(p -> p.cacheReadTokens).sum();

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", totalInput + provInput);
        tokens.put("output", totalOutput + provOutput);
        tokens.put("total", totalInput + totalOutput + provInput + provOutput);
        tokens.put("cacheRead", totalCacheRead + provCacheRead);

        output.put("sessionCount", sessions.size());
        output.put("providerSessionCount", providerUsage.size());
        output.put("tokens", tokens);
        output.put("totalToolCalls", totalToolCalls);

        // By provider
        Map<String, Object> byProvider = new LinkedHashMap<>();
        Map<String, List<ProviderUsageData>> grouped = providerUsage.stream()
                .filter(p -> p.provider != null)
                .collect(Collectors.groupingBy(p -> p.provider, LinkedHashMap::new, Collectors.toList()));
        for (var entry : grouped.entrySet()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("sessions", entry.getValue().size());
            ps.put("inputTokens", entry.getValue().stream().mapToLong(p -> p.inputTokens).sum());
            ps.put("outputTokens", entry.getValue().stream().mapToLong(p -> p.outputTokens).sum());
            ps.put("totalTokens", entry.getValue().stream().mapToLong(p -> p.inputTokens + p.outputTokens).sum());
            ps.put("apiCalls", entry.getValue().stream().mapToInt(p -> p.apiCalls).sum());
            byProvider.put(entry.getKey(), ps);
        }
        output.put("byProvider", byProvider);

        // By project
        Map<String, List<SessionData>> byProject = sessions.stream()
                .collect(Collectors.groupingBy(
                        s -> s.projectDirectory != null ? s.projectDirectory : "(unknown)",
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, Object> projects = new LinkedHashMap<>();
        for (var entry : byProject.entrySet()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("sessions", entry.getValue().size());
            ps.put("inputTokens", entry.getValue().stream().mapToLong(s -> s.inputTokens).sum());
            ps.put("outputTokens", entry.getValue().stream().mapToLong(s -> s.outputTokens).sum());
            ps.put("toolCalls", entry.getValue().stream().mapToInt(s -> s.totalToolCalls).sum());
            projects.put(entry.getKey(), ps);
        }
        output.put("byProject", projects);
        output.put("sessions", sessions);

        try {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        } catch (Exception e) {
            System.err.println("Failed to serialize: " + e.getMessage());
        }
    }

    // ========================================================================
    // Data loading
    // ========================================================================

    private List<SessionData> loadAllMetrics(Path conversationsDir) {
        List<SessionData> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(conversationsDir, "*.metrics.json")) {
            for (Path file : stream) {
                try {
                    SessionData data = parseMetricsFile(file);
                    if (data != null) result.add(data);
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            System.err.println("Failed to read conversations directory: " + e.getMessage());
        }

        result.sort(Comparator.comparing(
                (SessionData s) -> s.started != null ? s.started : "").reversed());
        return result;
    }

    private SessionData parseMetricsFile(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        SessionData data = new SessionData();

        JsonNode session = root.path("session");
        data.sessionId = session.path("sessionId").asText(
                file.getFileName().toString().replace(".metrics.json", ""));
        data.started = session.path("started").asText(null);
        data.ended = session.path("ended").asText(null);
        data.durationSeconds = session.path("durationSeconds").asLong(0);
        data.provider = session.path("provider").asText(null);
        data.model = session.path("model").asText(null);
        data.agent = session.path("agent").asText(null);

        JsonNode turnsNode = root.path("turns");
        data.userTurns = turnsNode.path("user").asInt(0);
        data.assistantTurns = turnsNode.path("assistant").asInt(0);
        data.totalTurns = turnsNode.path("total").asInt(0);

        JsonNode tokensNode = root.path("tokens");
        data.inputTokens = tokensNode.path("input").asLong(
                tokensNode.path("estimatedInput").asLong(0));
        data.outputTokens = tokensNode.path("output").asLong(
                tokensNode.path("estimatedOutput").asLong(0));
        data.cacheReadTokens = tokensNode.path("cacheRead").asLong(0);
        data.cacheCreationTokens = tokensNode.path("cacheCreation").asLong(0);

        JsonNode toolsNode = root.path("tools");
        data.totalToolCalls = toolsNode.path("totalCalls").asInt(0);
        data.totalToolErrors = toolsNode.path("totalErrors").asInt(0);
        JsonNode breakdown = toolsNode.path("breakdown");
        if (breakdown.isObject()) {
            data.toolBreakdown = new LinkedHashMap<>();
            breakdown.fields().forEachRemaining(f ->
                    data.toolBreakdown.put(f.getKey(), f.getValue().asInt(0)));
        }

        JsonNode agentic = root.path("agentic");
        data.agenticSteps = agentic.path("steps").asInt(0);
        data.compactions = agentic.path("compactions").asInt(0);
        data.thinkingTokens = agentic.path("thinkingTokens").asLong(0);
        data.subagentsSpawned = agentic.path("subagentsSpawned").asInt(0);

        return data;
    }

    private Map<String, String> loadSessionProjects(Path conversationsDir) {
        Map<String, String> map = new LinkedHashMap<>();
        Path indexFile = conversationsDir.resolve("tool-calls").resolve("all-tool-calls.jsonl");
        if (!Files.exists(indexFile)) return map;

        try {
            List<String> lines = Files.readAllLines(indexFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sid = node.path("sessionId").asText(null);
                    String project = node.path("projectDirectory").asText(null);
                    if (sid != null && project != null && !map.containsKey(sid)) {
                        map.put(sid, project);
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return map;
    }

    // ========================================================================
    // Formatting helpers
    // ========================================================================

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        mins = mins % 60;
        return hours + "h " + mins + "m";
    }

    private static String formatNum(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    private static String getProjectShortName(String project) {
        if (project == null || project.isEmpty()) return "(unknown)";
        String normalized = project.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : project;
    }

    // ========================================================================
    // Provider usage loading
    // ========================================================================

    private List<ProviderUsageData> loadProviderUsage(Path conversationsDir) {
        Path usageDir = conversationsDir.resolve("provider-usage");
        if (!Files.isDirectory(usageDir)) return Collections.emptyList();

        List<ProviderUsageData> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(usageDir, "*.json")) {
            for (Path file : stream) {
                try {
                    JsonNode root = MAPPER.readTree(file.toFile());
                    ProviderUsageData data = new ProviderUsageData();
                    data.sessionId = root.path("sessionId").asText(
                            file.getFileName().toString().replace(".json", ""));
                    data.provider = root.path("provider").asText(null);
                    data.model = root.path("model").asText(null);
                    data.projectDirectory = root.path("projectDirectory").asText(null);
                    data.inputTokens = root.path("inputTokens").asLong(0);
                    data.outputTokens = root.path("outputTokens").asLong(0);
                    data.cacheReadTokens = root.path("cacheReadTokens").asLong(0);
                    data.cacheCreationTokens = root.path("cacheCreationTokens").asLong(0);
                    data.thinkingTokens = root.path("thinkingTokens").asLong(0);
                    data.apiCalls = root.path("apiCalls").asInt(0);
                    result.add(data);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return result;
    }

    // ========================================================================
    // Formatting helpers (continued)
    // ========================================================================

    private static String truncId(String id) {
        if (id == null) return "?";
        return id.length() > 24 ? id.substring(0, 21) + "..." : id;
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    public static class SessionData {
        public String sessionId;
        public String started;
        public String ended;
        public long durationSeconds;
        public String provider;
        public String model;
        public String agent;
        public String projectDirectory;

        public int userTurns;
        public int assistantTurns;
        public int totalTurns;

        public long inputTokens;
        public long outputTokens;
        public long cacheReadTokens;
        public long cacheCreationTokens;

        public int totalToolCalls;
        public int totalToolErrors;
        public Map<String, Integer> toolBreakdown;

        public int agenticSteps;
        public int compactions;
        public long thinkingTokens;
        public int subagentsSpawned;
    }

    public static class ProviderUsageData {
        public String sessionId;
        public String provider;
        public String model;
        public String projectDirectory;
        public long inputTokens;
        public long outputTokens;
        public long cacheReadTokens;
        public long cacheCreationTokens;
        public long thinkingTokens;
        public int apiCalls;
    }
}
