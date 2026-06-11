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

package ai.kompile.app.tools;

import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.app.services.diffindex.DiffIndexService.DiffIndexEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool for searching the diff index — file changes extracted from CLI
 * chat conversations (Claude Code, Codex, OpenCode, Qwen, Cline, Cursor,
 * Continue, Aider, Gemini, Kompile, Pi).
 *
 * Agents use this to find what file changes were made across different
 * CLI coding sessions, filterable by agent, project directory, file path,
 * and content.
 */
@Component
public class DiffIndexTool {

    private static final Logger logger = LoggerFactory.getLogger(DiffIndexTool.class);

    private final DiffIndexService indexService;

    @Autowired
    public DiffIndexTool(@Autowired(required = false) DiffIndexService indexService) {
        this.indexService = indexService;
        logger.info("DiffIndexTool initialized");
    }

    // ── Input DTOs ───────────────────────────────────────────────────────────

    public record SearchDiffIndexInput(
            String agent,
            String projectDirectory,
            String filePath,
            String contentQuery,
            String source,
            Integer limit
    ) {}

    public record GetDiffEntryInput(String id) {}

    public record ListDiffProjectsInput() {}

    public record ListDiffAgentsInput() {}

    public record DiffIndexStatsInput() {}

    public record ReindexDiffsInput(String confirm) {}

    // ── Tool methods ─────────────────────────────────────────────────────────

    @Tool(name = "search_diff_index",
          description = "Search the diff index for file changes made by CLI coding agents. " +
                        "Filter by: agent (e.g. 'claude-code', 'codex', 'opencode', 'qwen', 'cline', " +
                        "'cursor', 'continue', 'aider', 'gemini', 'kompile', 'pi'), " +
                        "projectDirectory (project path or name substring), " +
                        "filePath (file path substring), " +
                        "contentQuery (search within diff content), " +
                        "source (CLI source name). " +
                        "Returns matching diff entries with file path, diff type, and line counts.")
    public Map<String, Object> searchDiffIndex(SearchDiffIndexInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        try {
            List<DiffIndexEntry> results = indexService.search(
                    input.agent(), input.projectDirectory(), input.filePath(),
                    input.contentQuery(), input.source(), input.limit());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", results.size());
            result.put("entries", results.stream().map(this::toSummaryMap).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error searching diff index: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "get_diff_entry",
          description = "Get full details of a diff index entry by ID, including the actual " +
                        "old/new content and unified diff patch.")
    public Map<String, Object> getDiffEntry(GetDiffEntryInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        if (input.id() == null || input.id().isBlank()) return errorMap("id is required");
        try {
            DiffIndexEntry entry = indexService.get(input.id());
            if (entry == null) return errorMap("Entry not found: " + input.id());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("entry", toFullMap(entry));
            return result;
        } catch (Exception e) {
            logger.error("Error getting diff entry {}: {}", input.id(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_diff_projects",
          description = "List all projects (directories) that have indexed diffs, with per-project " +
                        "statistics: entry count, agents used, files changed, lines added/removed.")
    public Map<String, Object> listDiffProjects(ListDiffProjectsInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        try {
            List<Map<String, Object>> projects = indexService.listProjects();
            return Map.of("status", "success", "count", projects.size(), "projects", projects);
        } catch (Exception e) {
            logger.error("Error listing diff projects: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_diff_agents",
          description = "List all CLI agents that have indexed diffs, with per-agent statistics: " +
                        "entry count, files changed, projects, lines added/removed.")
    public Map<String, Object> listDiffAgents(ListDiffAgentsInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        try {
            List<Map<String, Object>> agents = indexService.listAgents();
            return Map.of("status", "success", "count", agents.size(), "agents", agents);
        } catch (Exception e) {
            logger.error("Error listing diff agents: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "diff_index_stats",
          description = "Get overall diff index statistics: total entries, unique files, projects, " +
                        "lines added/removed, breakdown by agent and source.")
    public Map<String, Object> diffIndexStats(DiffIndexStatsInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        try {
            Map<String, Object> stats = indexService.getStats();
            stats.put("status", "success");
            return stats;
        } catch (Exception e) {
            logger.error("Error getting diff index stats: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "reindex_diffs",
          description = "Trigger a reindex of all CLI transcript sources. Scans all available " +
                        "CLI conversations for file edit tool calls and indexes them. " +
                        "Pass confirm='yes' to proceed.")
    public Map<String, Object> reindexDiffs(ReindexDiffsInput input) {
        if (indexService == null) return errorMap("DiffIndexService not available");
        if (!"yes".equalsIgnoreCase(input.confirm())) {
            return errorMap("Pass confirm='yes' to trigger reindex");
        }
        if (indexService.isIndexing()) {
            return Map.of("status", "info", "message", "Indexing already in progress");
        }
        try {
            indexService.reindexAll();
            return Map.of("status", "success", "message", "Reindex started in background");
        } catch (Exception e) {
            logger.error("Error triggering reindex: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> toSummaryMap(DiffIndexEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.getId());
        m.put("agent", entry.getAgent());
        m.put("source", entry.getSource());
        m.put("projectDirectory", entry.getProjectDirectory());
        m.put("filePath", entry.getFilePath());
        m.put("toolName", entry.getToolName());
        m.put("diffType", entry.getDiffType());
        m.put("linesAdded", entry.getLinesAdded());
        m.put("linesRemoved", entry.getLinesRemoved());
        m.put("timestamp", entry.getTimestamp());
        return m;
    }

    private Map<String, Object> toFullMap(DiffIndexEntry entry) {
        Map<String, Object> m = toSummaryMap(entry);
        m.put("sessionId", entry.getSessionId());
        m.put("oldString", entry.getOldString());
        m.put("newString", entry.getNewString());
        m.put("unifiedDiff", entry.getUnifiedDiff());
        return m;
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("status", "error", "error", message);
    }
}
