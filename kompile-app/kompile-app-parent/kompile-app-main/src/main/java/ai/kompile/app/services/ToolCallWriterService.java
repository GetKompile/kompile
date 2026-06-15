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
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        try {
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
            record.put("durationMs", 0);
            record.put("category", category);
            record.put("projectDirectory", projectDirectory);

            String jsonLine = MAPPER.writeValueAsString(record) + "\n";

            synchronized (this) {
                // Append to per-session file
                Path sessionFile = dir.resolve(sessionId + ".jsonl");
                Files.writeString(sessionFile, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                // Append to combined index
                Files.writeString(getCombinedIndexFile(), jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.debug("Failed to write tool call record: {}", e.getMessage());
        }
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
