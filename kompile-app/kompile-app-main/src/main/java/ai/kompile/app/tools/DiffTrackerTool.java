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

import ai.kompile.app.services.difftracker.DiffTrackerService;
import ai.kompile.app.services.difftracker.DiffTrackerService.DiffRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Diff tracker MCP tool for agents.
 *
 * Agents use this tool to record file changes and review the history of
 * modifications. Every time an agent edits a file, it should call
 * {@code record_diff} so the change is tracked persistently.
 *
 * <p><b>Compaction survival:</b> Diffs are stored per-session on disk. When
 * context compaction replaces tool call history with summaries, the agent
 * loses awareness of prior diffs — but the diffs are still on disk. The agent
 * should call {@code list_diffs} with its sessionId after compaction to
 * recover awareness of what it already changed.
 *
 * <p>Cross-session queries (omit sessionId) are supported for full audit.
 */
@Component
public class DiffTrackerTool {

    private static final Logger logger = LoggerFactory.getLogger(DiffTrackerTool.class);

    private final DiffTrackerService diffService;

    @Autowired
    public DiffTrackerTool(@Autowired(required = false) DiffTrackerService diffService) {
        this.diffService = diffService;
        logger.info("DiffTrackerTool initialized");
    }

    // ── Input DTOs ───────────────────────────────────────────────────────────

    public record RecordDiffInput(String sessionId, String filePath, String beforeContent,
                                  String afterContent, String unifiedDiff, String agentId,
                                  String taskId, String description) {}
    public record ListDiffsInput(String sessionId, String filePath, String agentId,
                                 String taskId, Integer limit) {}
    public record GetDiffInput(Long diffId) {}
    public record ListDiffsByFileInput(String sessionId, String filePath) {}
    public record DiffSummaryInput(String sessionId) {}
    public record DeleteDiffInput(Long diffId) {}
    public record ClearSessionDiffsInput(String sessionId, String confirm) {}
    public record ListSessionsInput() {}

    // ── Tool methods ─────────────────────────────────────────────────────────

    @Tool(name = "record_diff",
          description = "Records a file diff. Agents MUST call this after editing any file. " +
                        "Provide sessionId (your chat session ID — persists across compaction) and " +
                        "filePath (required). Include beforeContent and afterContent, and/or unifiedDiff. " +
                        "If unifiedDiff is omitted, one is computed from before/after content. " +
                        "Link to agentId and taskId for traceability.")
    public Map<String, Object> recordDiff(RecordDiffInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        if (input.filePath() == null || input.filePath().isBlank()) return errorMap("filePath is required");
        try {
            DiffRecord rec = diffService.record(
                    input.sessionId(), input.filePath(), input.beforeContent(),
                    input.afterContent(), input.unifiedDiff(), input.agentId(),
                    input.taskId(), input.description());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("diffId", rec.getId());
            result.put("sessionId", rec.getSessionId());
            result.put("linesAdded", rec.getLinesAdded());
            result.put("linesRemoved", rec.getLinesRemoved());
            result.put("message", "Diff recorded for " + rec.getFilePath());
            return result;
        } catch (Exception e) {
            logger.error("Error recording diff for {}: {}", input.filePath(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_diffs",
          description = "Lists recorded diffs. Provide sessionId to scope to your session (recommended " +
                        "after compaction to recover awareness of prior changes). Omit sessionId to see " +
                        "all sessions. Filter by filePath (substring), agentId, taskId. Most recent first.")
    public Map<String, Object> listDiffs(ListDiffsInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        try {
            List<DiffRecord> records;
            if (input.sessionId() != null && !input.sessionId().isBlank()) {
                records = diffService.listForSession(input.sessionId(), input.filePath(),
                        input.agentId(), input.taskId(), input.limit());
            } else {
                records = diffService.listAll(input.filePath(), input.agentId(),
                        input.taskId(), input.limit());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", records.size());
            if (input.sessionId() != null) result.put("sessionId", input.sessionId());
            result.put("diffs", records.stream().map(this::diffToSummaryMap).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing diffs: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "get_diff",
          description = "Gets full details of a single diff by ID, including before/after content " +
                        "and the unified diff patch. Works across all sessions.")
    public Map<String, Object> getDiff(GetDiffInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        if (input.diffId() == null) return errorMap("diffId is required");
        try {
            DiffRecord rec = diffService.get(input.diffId());
            if (rec == null) return errorMap("Diff not found: " + input.diffId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("diff", diffToFullMap(rec));
            return result;
        } catch (Exception e) {
            logger.error("Error getting diff {}: {}", input.diffId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_diffs_by_file",
          description = "Lists all diffs for a specific file path in chronological order. " +
                        "Provide sessionId to scope to one session, or omit for cross-session history.")
    public Map<String, Object> listDiffsByFile(ListDiffsByFileInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        if (input.filePath() == null || input.filePath().isBlank()) return errorMap("filePath is required");
        try {
            List<DiffRecord> records = diffService.listByFile(input.filePath(), input.sessionId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("filePath", input.filePath());
            result.put("count", records.size());
            result.put("diffs", records.stream().map(this::diffToSummaryMap).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing diffs for file {}: {}", input.filePath(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "diff_summary",
          description = "Returns a summary of tracked diffs: total count, files changed, diffs per " +
                        "agent, per session, and total lines added/removed. Provide sessionId to " +
                        "scope to one session, or omit for global summary.")
    public Map<String, Object> diffSummary(DiffSummaryInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(diffService.summary(input.sessionId()));
            return result;
        } catch (Exception e) {
            logger.error("Error getting diff summary: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "delete_diff",
          description = "Deletes a single diff record by ID.")
    public Map<String, Object> deleteDiff(DeleteDiffInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        if (input.diffId() == null) return errorMap("diffId is required");
        try {
            boolean deleted = diffService.delete(input.diffId());
            if (!deleted) return errorMap("Diff not found: " + input.diffId());
            return Map.of("status", "success", "message", "Diff deleted", "diffId", input.diffId());
        } catch (Exception e) {
            logger.error("Error deleting diff {}: {}", input.diffId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "clear_session_diffs",
          description = "Clears all tracked diffs for a specific session. Pass confirm='yes' to proceed.")
    public Map<String, Object> clearSessionDiffs(ClearSessionDiffsInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        if (input.sessionId() == null || input.sessionId().isBlank()) return errorMap("sessionId is required");
        if (!"yes".equalsIgnoreCase(input.confirm())) {
            return errorMap("Pass confirm='yes' to clear session diffs");
        }
        try {
            int count = diffService.clearSession(input.sessionId());
            return Map.of("status", "success", "message", "Cleared " + count + " diffs for session " + input.sessionId(),
                    "cleared", count, "sessionId", input.sessionId());
        } catch (Exception e) {
            logger.error("Error clearing diffs for session {}: {}", input.sessionId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_diff_sessions",
          description = "Lists all session IDs that have recorded diffs. Useful after compaction " +
                        "to find which session you belong to.")
    public Map<String, Object> listSessions(ListSessionsInput input) {
        if (diffService == null) return errorMap("DiffTrackerService not available");
        try {
            List<String> sessions = diffService.listSessions();
            return Map.of("status", "success", "sessions", sessions, "count", sessions.size());
        } catch (Exception e) {
            logger.error("Error listing diff sessions: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> diffToSummaryMap(DiffRecord rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rec.getId());
        m.put("sessionId", rec.getSessionId());
        m.put("filePath", rec.getFilePath());
        m.put("agentId", rec.getAgentId());
        m.put("taskId", rec.getTaskId());
        m.put("description", rec.getDescription());
        m.put("linesAdded", rec.getLinesAdded());
        m.put("linesRemoved", rec.getLinesRemoved());
        m.put("timestamp", rec.getTimestamp());
        return m;
    }

    private Map<String, Object> diffToFullMap(DiffRecord rec) {
        Map<String, Object> m = diffToSummaryMap(rec);
        m.put("beforeContent", rec.getBeforeContent());
        m.put("afterContent", rec.getAfterContent());
        m.put("unifiedDiff", rec.getUnifiedDiff());
        return m;
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("status", "error", "error", message);
    }
}
