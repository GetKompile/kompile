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

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service that aggregates tool call records from:
 * 1. Disk-based JSONL index (passthrough + emulated passthrough sessions)
 * 2. In-memory MCP action log (MCP server tool invocations)
 *
 * Provides unified search, filter, and statistics across all sources.
 */
@Service
public class ToolCallCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallCatalogService.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Autowired(required = false)
    private McpActionLogService mcpActionLogService;

    private Path getToolCallsDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "conversations", "tool-calls");
    }

    private Path getCombinedIndexFile() {
        return getToolCallsDir().resolve("all-tool-calls.jsonl");
    }

    /**
     * Search tool calls across all sources with full filter and sort support.
     */
    public Map<String, Object> search(String query, String toolName, String sessionId,
                                       String category, String agentName, String source,
                                       String projectDirectory,
                                       String sortBy, String sortDir,
                                       int page, int pageSize) {
        List<ToolCallEntry> filtered = applyFilters(
                loadAll(), query, toolName, sessionId, category, agentName, source, projectDirectory);

        // Sort
        Comparator<ToolCallEntry> cmp = buildComparator(sortBy, sortDir);
        filtered.sort(cmp);

        int totalCount = filtered.size();
        int fromIdx = Math.min(page * pageSize, totalCount);
        int toIdx = Math.min(fromIdx + pageSize, totalCount);
        List<ToolCallEntry> pageResults = filtered.subList(fromIdx, toIdx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", pageResults);
        result.put("totalCount", totalCount);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", (int) Math.ceil((double) totalCount / pageSize));
        return result;
    }

    /** Backwards-compatible search (timestamp desc, no project filter). */
    public Map<String, Object> search(String query, String toolName, String sessionId,
                                       String category, String agentName, String source,
                                       int page, int pageSize) {
        return search(query, toolName, sessionId, category, agentName, source,
                null, "timestamp", "desc", page, pageSize);
    }

    /**
     * Return tool calls grouped by a field (category, project, agent, tool).
     */
    public Map<String, Object> groupBy(String groupField, String query, String toolName,
                                        String sessionId, String category, String agentName,
                                        String source, String projectDirectory,
                                        String sortBy, String sortDir,
                                        int limitPerGroup) {
        List<ToolCallEntry> filtered = applyFilters(
                loadAll(), query, toolName, sessionId, category, agentName, source, projectDirectory);

        Comparator<ToolCallEntry> cmp = buildComparator(sortBy, sortDir);
        filtered.sort(cmp);

        java.util.function.Function<ToolCallEntry, String> keyFn = switch (groupField != null ? groupField : "category") {
            case "project" -> e -> e.projectDirectory != null ? e.projectDirectory : "(unknown)";
            case "agent" -> e -> e.agentName != null ? e.agentName : "(unknown)";
            case "tool" -> e -> e.toolName != null ? e.toolName : "(unknown)";
            case "source" -> e -> e.source != null ? e.source : "(unknown)";
            case "session" -> e -> e.sessionId != null ? e.sessionId : "(unknown)";
            default -> e -> e.category != null ? e.category : "general";
        };

        Map<String, List<ToolCallEntry>> grouped = filtered.stream()
                .collect(Collectors.groupingBy(keyFn, LinkedHashMap::new, Collectors.toList()));

        int effectiveLimit = limitPerGroup > 0 ? limitPerGroup : 50;
        grouped.replaceAll((k, v) -> v.size() > effectiveLimit ? v.subList(0, effectiveLimit) : v);

        // Build counts per group
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        grouped.forEach((k, v) -> groupCounts.put(k, v.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", grouped);
        result.put("groupCounts", groupCounts);
        result.put("groupField", groupField != null ? groupField : "category");
        result.put("totalGroups", grouped.size());
        result.put("totalCount", filtered.size());
        return result;
    }

    /**
     * Get aggregate statistics.
     */
    public Map<String, Object> getStats() {
        List<ToolCallEntry> all = loadAll();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalToolCalls", all.size());

        Map<String, Long> byTool = all.stream()
                .collect(Collectors.groupingBy(e -> e.toolName, Collectors.counting()));
        stats.put("byTool", sortDesc(byTool));

        Map<String, Long> byCategory = all.stream()
                .filter(e -> e.category != null)
                .collect(Collectors.groupingBy(e -> e.category, Collectors.counting()));
        stats.put("byCategory", sortDesc(byCategory));

        Map<String, Long> byAgent = all.stream()
                .filter(e -> e.agentName != null)
                .collect(Collectors.groupingBy(e -> e.agentName, Collectors.counting()));
        stats.put("byAgent", sortDesc(byAgent));

        Map<String, Long> bySource = all.stream()
                .filter(e -> e.source != null)
                .collect(Collectors.groupingBy(e -> e.source, Collectors.counting()));
        stats.put("bySource", sortDesc(bySource));

        Map<String, Long> byProject = all.stream()
                .filter(e -> e.projectDirectory != null)
                .collect(Collectors.groupingBy(e -> e.projectDirectory, Collectors.counting()));
        stats.put("byProject", sortDesc(byProject));

        long sessions = all.stream()
                .filter(e -> e.sessionId != null)
                .map(e -> e.sessionId)
                .distinct().count();
        stats.put("sessionCount", sessions);

        long errors = all.stream().filter(e -> e.isError).count();
        stats.put("totalErrors", errors);

        return stats;
    }

    /**
     * Get filter options (distinct tool names, categories, agents, sessions, projects).
     */
    public Map<String, Object> getFilterOptions() {
        List<ToolCallEntry> all = loadAll();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("toolNames", all.stream().map(e -> e.toolName).distinct().sorted().collect(Collectors.toList()));
        options.put("categories", all.stream().filter(e -> e.category != null).map(e -> e.category).distinct().sorted().collect(Collectors.toList()));
        options.put("agents", all.stream().filter(e -> e.agentName != null).map(e -> e.agentName).distinct().sorted().collect(Collectors.toList()));
        options.put("sources", all.stream().filter(e -> e.source != null).map(e -> e.source).distinct().sorted().collect(Collectors.toList()));
        options.put("sessions", all.stream().filter(e -> e.sessionId != null).map(e -> e.sessionId).distinct().collect(Collectors.toList()));
        options.put("projects", all.stream().filter(e -> e.projectDirectory != null).map(e -> e.projectDirectory).distinct().sorted().collect(Collectors.toList()));
        return options;
    }

    private List<ToolCallEntry> applyFilters(List<ToolCallEntry> all, String query,
                                              String toolName, String sessionId,
                                              String category, String agentName,
                                              String source, String projectDirectory) {
        String lq = query != null ? query.toLowerCase() : null;
        return all.stream()
                .filter(r -> toolName == null || r.toolName.equalsIgnoreCase(toolName))
                .filter(r -> sessionId == null || (r.sessionId != null && r.sessionId.equalsIgnoreCase(sessionId)))
                .filter(r -> category == null || (r.category != null && r.category.equalsIgnoreCase(category)))
                .filter(r -> agentName == null || (r.agentName != null && r.agentName.equalsIgnoreCase(agentName)))
                .filter(r -> source == null || (r.source != null && r.source.equalsIgnoreCase(source)))
                .filter(r -> projectDirectory == null || matchesProject(r, projectDirectory))
                .filter(r -> lq == null || matchesQuery(r, lq))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean matchesProject(ToolCallEntry entry, String projectDirectory) {
        if (entry.projectDirectory == null) return false;
        if (entry.projectDirectory.equals(projectDirectory)) return true;
        String norm = projectDirectory.replace("\\", "/");
        String proj = entry.projectDirectory.replace("\\", "/");
        return proj.endsWith("/" + norm) || proj.equals(norm);
    }

    private Comparator<ToolCallEntry> buildComparator(String sortBy, String sortDir) {
        boolean desc = !"asc".equalsIgnoreCase(sortDir);
        Comparator<ToolCallEntry> cmp = switch (sortBy != null ? sortBy : "timestamp") {
            case "toolName", "tool" -> Comparator.comparing(
                    (ToolCallEntry e) -> e.toolName != null ? e.toolName : "", String.CASE_INSENSITIVE_ORDER);
            case "category" -> Comparator.comparing(
                    (ToolCallEntry e) -> e.category != null ? e.category : "", String.CASE_INSENSITIVE_ORDER);
            case "agent" -> Comparator.comparing(
                    (ToolCallEntry e) -> e.agentName != null ? e.agentName : "", String.CASE_INSENSITIVE_ORDER);
            case "project" -> Comparator.comparing(
                    (ToolCallEntry e) -> e.projectDirectory != null ? e.projectDirectory : "", String.CASE_INSENSITIVE_ORDER);
            case "source" -> Comparator.comparing(
                    (ToolCallEntry e) -> e.source != null ? e.source : "", String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(
                    (ToolCallEntry e) -> e.timestamp != null ? e.timestamp : "");
        };
        return desc ? cmp.reversed() : cmp;
    }

    /**
     * Get a single tool call by ID.
     */
    public ToolCallEntry getById(String id) {
        return loadAll().stream()
                .filter(e -> id.equals(e.id))
                .findFirst()
                .orElse(null);
    }

    private List<ToolCallEntry> loadAll() {
        List<ToolCallEntry> entries = new ArrayList<>();

        // Load from disk JSONL index
        entries.addAll(loadDiskRecords());

        // Load from MCP action log (in-memory)
        if (mcpActionLogService != null) {
            entries.addAll(loadMcpActions());
        }

        return entries;
    }

    private List<ToolCallEntry> loadDiskRecords() {
        Path indexFile = getCombinedIndexFile();
        if (!Files.exists(indexFile)) {
            return Collections.emptyList();
        }

        List<ToolCallEntry> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(indexFile.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    ToolCallEntry entry = MAPPER.readValue(line, ToolCallEntry.class);
                    records.add(entry);
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read tool call index: {}", e.getMessage());
        }
        return records;
    }

    private List<ToolCallEntry> loadMcpActions() {
        List<Map<String, Object>> actions = mcpActionLogService.getActionLog(1000, null, null, false);
        List<ToolCallEntry> entries = new ArrayList<>();
        for (Map<String, Object> action : actions) {
            ToolCallEntry entry = new ToolCallEntry();
            entry.id = "mcp-" + action.get("id");
            entry.toolName = (String) action.get("toolName");
            entry.category = (String) action.get("toolCategory");
            entry.timestamp = (String) action.get("timestamp");
            entry.source = "mcp";
            entry.agentName = "external";
            entry.sessionId = (String) action.get("sessionId");
            entry.isError = Boolean.TRUE.equals(action.get("failed"))
                    || "FAILURE".equalsIgnoreCase(String.valueOf(action.get("status")));
            Object duration = action.get("durationMs");
            if (duration instanceof Number number) {
                entry.durationMs = number.longValue();
            }
            Object args = action.get("arguments");
            if (args != null) {
                try {
                    entry.toolInput = MAPPER.writeValueAsString(args);
                    entry.toolInputSummary = entry.toolInput.length() > 100
                            ? entry.toolInput.substring(0, 97) + "..."
                            : entry.toolInput;
                } catch (Exception e) {
                    entry.toolInput = args.toString();
                }
            }
            entries.add(entry);
        }
        return entries;
    }

    private boolean matchesQuery(ToolCallEntry entry, String lowerQuery) {
        if (entry.toolName != null && entry.toolName.toLowerCase().contains(lowerQuery)) return true;
        if (entry.toolInputSummary != null && entry.toolInputSummary.toLowerCase().contains(lowerQuery)) return true;
        if (entry.category != null && entry.category.toLowerCase().contains(lowerQuery)) return true;
        if (entry.agentName != null && entry.agentName.toLowerCase().contains(lowerQuery)) return true;
        if (entry.sessionId != null && entry.sessionId.toLowerCase().contains(lowerQuery)) return true;
        if (entry.toolInput != null && entry.toolInput.toLowerCase().contains(lowerQuery)) return true;
        if (entry.projectDirectory != null && entry.projectDirectory.toLowerCase().contains(lowerQuery)) return true;
        return false;
    }

    private static Map<String, Long> sortDesc(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Unified tool call entry combining disk records and MCP actions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallEntry {
        @JsonProperty("id") public String id;
        @JsonProperty("sessionId") public String sessionId;
        @JsonProperty("toolName") public String toolName;
        @JsonProperty("toolInput") public String toolInput;
        @JsonProperty("toolInputSummary") public String toolInputSummary;
        @JsonProperty("timestamp") public String timestamp;
        @JsonProperty("source") public String source;
        @JsonProperty("agentName") public String agentName;
        @JsonProperty("isError") public boolean isError;
        @JsonProperty("durationMs") public long durationMs;
        @JsonProperty("category") public String category;
        @JsonProperty("projectDirectory") public String projectDirectory;
    }
}
