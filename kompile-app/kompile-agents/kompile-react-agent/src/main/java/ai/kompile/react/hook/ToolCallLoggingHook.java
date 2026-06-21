/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.hook;

import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records each ReAct tool call into the shared kompile tool-call index at
 * {@code ~/.kompile/conversations/tool-calls/} so ReAct-driven "kompile-managed"
 * sessions (e.g. kclaw REACT tasks) surface in the MCP Hub tool-call catalog
 * alongside passthrough, emulated-passthrough, local-chat and MCP server calls.
 *
 * <p>This module intentionally does not depend on {@code kompile-cli-main}, so it
 * writes the same JSONL record shape directly rather than reusing
 * {@code ai.kompile.cli.main.chat.ToolCallIndex} /
 * {@code ai.kompile.app.services.ToolCallWriterService}. Keep the field set in sync
 * with {@code ai.kompile.cli.main.chat.ToolCallRecord} (id, sessionId, toolName,
 * toolInput, toolInputSummary, timestamp, source, agentName, isError, durationMs,
 * category, projectDirectory).
 *
 * <p>Recording happens in {@link #preAct} with a high priority value so it runs
 * after filtering hooks and sees the tool calls that will actually execute.
 */
@Slf4j
public class ToolCallLoggingHook implements AgentHook {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong ID_GEN = new AtomicLong(System.currentTimeMillis());

    private final String source;
    private final String agentName;

    public ToolCallLoggingHook() {
        this("react", "react-agent");
    }

    public ToolCallLoggingHook(String source, String agentName) {
        this.source = source;
        this.agentName = agentName;
    }

    /** Run after filter/validation hooks so we record the tool calls that actually execute. */
    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public void preAct(AgentContext context, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        String sessionId = context != null && context.getExecutionId() != null
                ? context.getExecutionId() : "react";
        Object wd = context != null ? context.getMetadata("workingDirectory") : null;
        if (wd == null && context != null) {
            wd = context.getMetadata("projectDirectory");
        }
        String projectDirectory = wd != null ? wd.toString() : null;

        for (ToolCall call : toolCalls) {
            if (call == null || call.isFinalAnswer()) {
                continue;
            }
            try {
                record(sessionId, call, projectDirectory);
            } catch (Exception e) {
                log.debug("Failed to index ReAct tool call '{}': {}",
                        call.getName(), e.getMessage());
            }
        }
    }

    private void record(String sessionId, ToolCall call, String projectDirectory) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".kompile", "conversations", "tool-calls");
        Files.createDirectories(dir);

        String toolName = call.getName();
        String toolInput = call.getRawArguments() != null
                ? call.getRawArguments()
                : (call.getArguments() != null ? MAPPER.writeValueAsString(call.getArguments()) : "");
        String summary = toolInput.length() > 100 ? toolInput.substring(0, 97) + "..." : toolInput;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", sessionId + "-" + ID_GEN.incrementAndGet());
        record.put("sessionId", sessionId);
        record.put("toolName", toolName);
        record.put("toolInput", toolInput);
        record.put("toolInputSummary", summary);
        record.put("timestamp", Instant.now().toString());
        record.put("source", source);
        record.put("agentName", agentName);
        record.put("isError", false);
        record.put("durationMs", 0);
        record.put("category", categorize(toolName));
        record.put("projectDirectory", projectDirectory);

        String jsonLine = MAPPER.writeValueAsString(record) + "\n";
        synchronized (ToolCallLoggingHook.class) {
            Files.writeString(dir.resolve(sessionId + ".jsonl"), jsonLine,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(dir.resolve("all-tool-calls.jsonl"), jsonLine,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** Mirrors ai.kompile.cli.main.chat.ToolCallRecord#categorize so categories stay consistent. */
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
