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

import ai.kompile.app.services.ToolCallCatalogService;
import ai.kompile.app.services.TranscriptIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP tool for searching, listing, and indexing tool calls across all agent sessions.
 * Exposes the tool call catalog to any agent via MCP.
 */
@Component
public class ToolCallCatalogMcpTool {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallCatalogMcpTool.class);

    private final ToolCallCatalogService catalogService;
    private final TranscriptIndexingService indexingService;

    public ToolCallCatalogMcpTool(ToolCallCatalogService catalogService,
                                   TranscriptIndexingService indexingService) {
        this.catalogService = catalogService;
        this.indexingService = indexingService;
        logger.info("ToolCallCatalogMcpTool initialized");
    }

    // Input records
    public record SearchToolCallsInput(
            @ToolParam(description = "Search query text (matches tool name, input, category, agent, session, project)") String query,
            @ToolParam(description = "Filter by tool name (e.g. 'Read', 'Bash', 'Edit')") String tool,
            @ToolParam(description = "Filter by category (filesystem, shell, search, rag, agent, model, web, etc.)") String category,
            @ToolParam(description = "Filter by agent name") String agent,
            @ToolParam(description = "Filter by session ID") String session,
            @ToolParam(description = "Filter by project directory (exact or suffix match)") String project,
            @ToolParam(description = "Sort by: timestamp, toolName, category, agent, project, source") String sortBy,
            @ToolParam(description = "Sort direction: asc or desc") String sortDir,
            @ToolParam(description = "Maximum results (default 50)") Integer limit
    ) {}

    public record ListGroupedInput(
            @ToolParam(description = "Group by field: category, project, agent, tool, source, session", required = true) String groupBy,
            @ToolParam(description = "Filter by tool name") String tool,
            @ToolParam(description = "Filter by category") String category,
            @ToolParam(description = "Filter by agent name") String agent,
            @ToolParam(description = "Filter by session ID") String session,
            @ToolParam(description = "Filter by project directory") String project,
            @ToolParam(description = "Sort by: timestamp, toolName, category, agent, project") String sortBy,
            @ToolParam(description = "Sort direction: asc or desc") String sortDir,
            @ToolParam(description = "Max results per group (default 20)") Integer limitPerGroup
    ) {}

    public record GetStatsInput() {}

    public record GetFiltersInput() {}

    public record IndexTranscriptsInput(
            @ToolParam(description = "Source to index: all, claude-code, codex, qwen, opencode, gemini (default: all)") String source,
            @ToolParam(description = "Re-index already indexed sessions (default false)") Boolean reindex
    ) {}

    public record GetToolCallByIdInput(
            @ToolParam(description = "Tool call ID", required = true) String id
    ) {}

    @Tool(name = "search_tool_calls",
            description = "Search tool calls across all agent sessions by query text. " +
                    "Matches against tool name, input, category, agent, session ID, and project directory. " +
                    "Supports filtering by tool, category, agent, session, project, and sorting.")
    public Map<String, Object> searchToolCalls(SearchToolCallsInput input) {
        logger.info("Searching tool calls: query={}", input.query());
        return catalogService.search(
                input.query(),
                input.tool(),
                input.session(),
                input.category(),
                input.agent(),
                null, // source
                input.project(),
                input.sortBy() != null ? input.sortBy() : "timestamp",
                input.sortDir() != null ? input.sortDir() : "desc",
                0,
                input.limit() != null ? input.limit() : 50);
    }

    @Tool(name = "list_tool_calls_grouped",
            description = "List tool calls grouped by a field (category, project, agent, tool, source, session). " +
                    "Useful for understanding tool usage patterns across projects or categories.")
    public Map<String, Object> listGrouped(ListGroupedInput input) {
        logger.info("Listing grouped tool calls: groupBy={}", input.groupBy());
        return catalogService.groupBy(
                input.groupBy(),
                null, // query
                input.tool(),
                input.session(),
                input.category(),
                input.agent(),
                null, // source
                input.project(),
                input.sortBy() != null ? input.sortBy() : "timestamp",
                input.sortDir() != null ? input.sortDir() : "desc",
                input.limitPerGroup() != null ? input.limitPerGroup() : 20);
    }

    @Tool(name = "get_tool_call_stats",
            description = "Get aggregate statistics about tool calls: total counts, breakdowns by tool, " +
                    "category, agent, source, and project. Includes error counts and session counts.")
    public Map<String, Object> getStats(GetStatsInput input) {
        logger.info("Getting tool call statistics");
        return catalogService.getStats();
    }

    @Tool(name = "get_tool_call_filters",
            description = "Get available filter options for tool calls: distinct tool names, categories, " +
                    "agents, sources, sessions, and projects.")
    public Map<String, Object> getFilters(GetFiltersInput input) {
        logger.info("Getting tool call filter options");
        return catalogService.getFilterOptions();
    }

    @Tool(name = "index_tool_call_transcripts",
            description = "Index tool calls from provider transcripts (Claude Code, Codex, Qwen, OpenCode, Gemini). " +
                    "Scans provider session files and extracts structured tool call records into the catalog. " +
                    "Skips already-indexed sessions unless reindex=true.")
    public Map<String, Object> indexTranscripts(IndexTranscriptsInput input) {
        String source = input.source() != null ? input.source() : "all";
        boolean reindex = input.reindex() != null && input.reindex();
        logger.info("Indexing transcripts: source={}, reindex={}", source, reindex);
        return indexingService.indexTranscripts(source, reindex);
    }

    @Tool(name = "get_tool_call_detail",
            description = "Get full details of a single tool call by ID, including the complete tool input JSON.")
    public Map<String, Object> getById(GetToolCallByIdInput input) {
        logger.info("Getting tool call by ID: {}", input.id());
        ToolCallCatalogService.ToolCallEntry entry = catalogService.getById(input.id());
        if (entry == null) {
            return Map.of("status", "error", "error", "Tool call not found: " + input.id());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.id);
        result.put("sessionId", entry.sessionId);
        result.put("toolName", entry.toolName);
        result.put("toolInput", entry.toolInput);
        result.put("toolInputSummary", entry.toolInputSummary);
        result.put("timestamp", entry.timestamp);
        result.put("source", entry.source);
        result.put("agentName", entry.agentName);
        result.put("isError", entry.isError);
        result.put("durationMs", entry.durationMs);
        result.put("category", entry.category);
        result.put("projectDirectory", entry.projectDirectory);
        return result;
    }
}
