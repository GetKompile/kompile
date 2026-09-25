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

import ai.kompile.cli.common.metrics.ToolCallUsageJournal;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes tool call records to the shared JSONL index at
 * {@code ~/.kompile/conversations/tool-calls/}. Used by both
 * PassthroughSessionManager and AgentChatService to persist tool calls
 * in real time as they are streamed from agent subprocesses.
 */
@Service
public class ToolCallWriterService {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallWriterService.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final AtomicLong ID_GEN = new AtomicLong(System.currentTimeMillis());

    private Path getToolCallsDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "conversations", "tool-calls");
    }

    private Path getCombinedIndexFile() {
        return getToolCallsDir().resolve("all-tool-calls.jsonl");
    }

    /**
     * Record a tool call to the JSONL index.
     *
     * @param sessionId        session identifier
     * @param toolName         name of the tool invoked
     * @param toolInput        raw JSON input to the tool (may be large)
     * @param agentName        name of the agent that invoked the tool
     * @param source           source identifier (e.g. "passthrough", "agent-chat")
     * @param isError          whether the tool call resulted in an error
     * @param projectDirectory working directory of the agent process, may be null
     */
    public void record(String sessionId, String toolName, String toolInput,
                       String agentName, String source, boolean isError,
                       String projectDirectory) {
        record(sessionId, toolName, toolInput, agentName, source, isError,
                projectDirectory, 0L, null);
    }

    /**
     * Typed overload (Task 2): record a call with optional duration and usage.
     * Usage is never packed into the argument string; it is attached as the record's
     * optional usage block and mirrored into the shared usage journal (cross-process
     * locked, idempotent per invocationId+revision).
     *
     * @return true when the usage journal write succeeded (no usage → false)
     */
    public boolean record(String sessionId, String toolName, String toolInput,
                          String agentName, String source, boolean isError,
                          String projectDirectory, long durationMs,
                          ai.kompile.cli.common.metrics.ToolCallUsage usage) {
        boolean journaled = false;
        try {
            ToolCallUsageJournal.validateSessionKey(sessionId);
            Path dir = getToolCallsDir();
            Files.createDirectories(dir);

            String id = sessionId + "-" + ID_GEN.incrementAndGet();
            String summary = toolInput != null && toolInput.length() > 100
                    ? toolInput.substring(0, 97) + "..." : toolInput;
            String category = categorize(toolName);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id);
            record.put("sessionId", sessionId);
            record.put("toolName", toolName);
            record.put("toolInput", toolInput);
            record.put("toolInputSummary", summary);
            record.put("timestamp", Instant.now().toString());
            record.put("source", source);
            record.put("agentName", agentName);
            record.put("isError", isError);
            record.put("durationMs", Math.max(0, durationMs));
            record.put("category", category);
            record.put("projectDirectory", projectDirectory);

            if (usage != null) {
                record.put("usage", MAPPER.convertValue(usage.toJsonNode(MAPPER), Map.class));
            }

            String jsonLine = MAPPER.writeValueAsString(record) + "\n";

            synchronized (this) {
                // Append to per-session file
                Path sessionFile = dir.resolve(sessionId + ".jsonl");
                Files.writeString(sessionFile, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                // Append to combined index
                Files.writeString(getCombinedIndexFile(), jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            if (usage != null) {
                journaled = usageJournalFor().record(usage, revisionFor(usage))
                        instanceof ToolCallUsageJournal.WriteResult.Written;
            }
        } catch (IllegalArgumentException unsafeSessionKey) {
            logger.debug("Rejected unsafe session key for tool call record: {}", unsafeSessionKey.getMessage());
        } catch (IOException e) {
            logger.debug("Failed to write tool call record: {}", e.getMessage());
        }
        return journaled;
    }

    /**
     * Usage journal revision for finalization: derived from the completion timestamp so
     * a later finalize of the same invocation supersedes any earlier partial event.
     */
    private static long revisionFor(ai.kompile.cli.common.metrics.ToolCallUsage usage) {
        return usage.finishedEpochMs() != null ? usage.finishedEpochMs() : usage.startedEpochMs();
    }

    /** Lazy shared usage journal in the same tool-calls directory. */
    private ToolCallUsageJournal usageJournalFor() {
        ToolCallUsageJournal journal = usageJournal;
        if (journal == null) {
            synchronized (this) {
                if (usageJournal == null) {
                    usageJournal = new ToolCallUsageJournal(
                            ToolCallUsageJournal.defaultJournalFile(getToolCallsDir()), MAPPER);
                }
                journal = usageJournal;
            }
        }
        return journal;
    }

    private volatile ToolCallUsageJournal usageJournal;

    /**
     * Journal usage WITHOUT writing a legacy catalog line (mirrors the CLI's
     * ToolCallIndex.recordUsageDirect): one authoritative usage stream, no double
     * counting across writers. Never throws; accounting health is visible via the
     * journal's own health surface.
     */
    public void recordUsageDirect(ai.kompile.cli.common.metrics.ToolCallUsage usage) {
        if (usage == null) {
            return;
        }
        usageJournalFor().record(usage, revisionFor(usage));
    }

    private static String categorize(String toolName) {
        if (toolName == null) return "general";
        String lower = toolName.toLowerCase();
        if (lower.contains("read") || lower.contains("write") || lower.contains("edit")
                || lower.contains("glob") || lower.contains("file")) return "filesystem";
        if (lower.contains("bash") || lower.contains("shell") || lower.contains("exec")) return "shell";
        if (lower.contains("grep") || lower.contains("search") || lower.contains("find")) return "search";
        if (lower.contains("rag") || lower.contains("retriev")) return "rag";
        if (lower.startsWith("agent")) return "agent";
        if (lower.contains("model") || lower.contains("embed")) return "model";
        if (lower.contains("web") || lower.contains("fetch") || lower.contains("url")) return "web";
        if (lower.contains("notebook")) return "notebook";
        if (lower.contains("todo") || lower.contains("task") || lower.contains("plan")) return "planning";
        return "general";
    }
}
