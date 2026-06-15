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

package ai.kompile.cli.mcp.stdio;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized progress logger for all MCP tool executions.
 * Writes timestamped entries to {@code ~/.kompile/logs/mcp-activity.log},
 * allowing users to {@code tail -f} the file during long-running operations
 * since Claude Code does not surface MCP progress notifications.
 *
 * <p>Thread-safe. Designed to be a singleton per MCP server session.</p>
 */
public class McpToolProgressLogger {

    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    private final Path logFile;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<String, Long> activeTools = new ConcurrentHashMap<>();

    public McpToolProgressLogger() {
        this(Paths.get(System.getProperty("user.home"), ".kompile", "logs"));
    }

    public McpToolProgressLogger(Path logDir) {
        this.logFile = logDir.resolve("mcp-activity.log");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            System.err.println("[McpToolProgressLogger] Could not create log dir: " + e.getMessage());
        }
    }

    /** Log the start of a tool execution. Returns a call ID for pairing with completion. */
    public String toolStart(String toolName, Map<String, Object> params) {
        String callId = toolName + "-" + System.nanoTime();
        activeTools.put(callId, System.currentTimeMillis());
        String paramSummary = summarizeParams(toolName, params);
        write("START", toolName, paramSummary, callId);
        return callId;
    }

    /** Log an intermediate progress message during a tool execution. */
    public void toolProgress(String callId, String toolName, String message) {
        Long startMs = activeTools.get(callId);
        String elapsed = startMs != null
                ? " [+" + (System.currentTimeMillis() - startMs) + "ms]"
                : "";
        write("PROGRESS", toolName, message + elapsed, callId);
    }

    /** Log successful completion of a tool execution. */
    public void toolComplete(String callId, String toolName, long durationMs, String resultSummary) {
        activeTools.remove(callId);
        String msg = "completed in " + durationMs + "ms";
        if (resultSummary != null && !resultSummary.isEmpty()) {
            msg += " — " + resultSummary;
        }
        write("DONE", toolName, msg, callId);
    }

    /** Log a tool execution error. */
    public void toolError(String callId, String toolName, long durationMs, String error) {
        activeTools.remove(callId);
        write("ERROR", toolName, "failed after " + durationMs + "ms — " + error, callId);
    }

    /** Get the log file path (for display to users). */
    public Path getLogFile() {
        return logFile;
    }

    private void write(String level, String toolName, String message, String callId) {
        String timestamp = TS_FMT.format(Instant.now());
        String line = String.format("[%s] %-8s %-30s %s%n",
                timestamp, level, toolName, message);

        synchronized (writeLock) {
            try {
                rotateIfNeeded();
                try (BufferedWriter w = Files.newBufferedWriter(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                }
            } catch (IOException e) {
                // Don't let logging failures disrupt tool execution
                System.err.println("[McpToolProgressLogger] Write failed: " + e.getMessage());
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_SIZE) {
            Path rotated = logFile.resolveSibling("mcp-activity.log.1");
            Files.move(logFile, rotated, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Produce a compact summary of tool params (avoids logging huge file contents). */
    private String summarizeParams(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder("{");
        int count = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (count > 0) sb.append(", ");
            if (count >= 5) {
                sb.append("...(").append(params.size() - count).append(" more)");
                break;
            }
            sb.append(entry.getKey()).append("=");
            Object val = entry.getValue();
            if (val == null) {
                sb.append("null");
            } else {
                String s = val.toString();
                // Truncate long values (file content, code, etc.)
                if (s.length() > 120) {
                    sb.append('"').append(s, 0, 117).append("...\"");
                } else {
                    sb.append('"').append(s).append('"');
                }
            }
            count++;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Produce a compact result summary (first meaningful line, truncated). */
    public static String summarizeResult(ai.kompile.cli.main.chat.tools.ToolResult result) {
        if (result == null) return "";
        String title = result.getTitle();
        String output = result.getOutput();

        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty() && !"error".equals(title)) {
            sb.append(title);
        }
        if (output != null && !output.isEmpty()) {
            // Take first non-empty line
            String firstLine = output.lines()
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("");
            if (!firstLine.isEmpty()) {
                if (sb.length() > 0) sb.append(" — ");
                if (firstLine.length() > 100) {
                    sb.append(firstLine, 0, 97).append("...");
                } else {
                    sb.append(firstLine);
                }
            }
        }
        if (result.isError()) {
            sb.insert(0, "[ERROR] ");
        }
        return sb.toString();
    }
}
