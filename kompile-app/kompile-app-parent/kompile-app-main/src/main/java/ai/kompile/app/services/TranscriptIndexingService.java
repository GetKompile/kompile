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

import ai.kompile.cli.common.chat.sources.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Spring service that indexes tool calls from provider transcripts
 * into the shared JSONL index at ~/.kompile/conversations/tool-calls/.
 * Uses ToolCallExtractor for provider-specific parsing.
 * <p>
 * Runs a periodic backfill every 10 minutes to catch any tool calls
 * from CLI sessions that weren't captured in real time.
 */
@Service
public class TranscriptIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptIndexingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ToolCallWriterService toolCallWriter;

    private Path getToolCallsDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "conversations", "tool-calls");
    }

    private Path getCombinedIndexFile() {
        return getToolCallsDir().resolve("all-tool-calls.jsonl");
    }

    private int maxSessionsPerRun() {
        return Integer.getInteger("kompile.transcript.indexing.maxSessionsPerRun", 250);
    }

    private long maxMillisPerRun() {
        return Long.getLong("kompile.transcript.indexing.maxMillisPerRun", 30_000L);
    }

    public Map<String, Object> indexTranscripts(String sourceFilter, boolean reindex) {
        ChatSourceRegistry registry;
        try {
            registry = ChatSourceRegistry.getInstance();
        } catch (Exception e) {
            logger.warn("ChatSourceRegistry not available: {}", e.getMessage());
            return Map.of("error", "ChatSourceRegistry not available", "sessionsScanned", 0,
                    "toolCallsIndexed", 0);
        }

        Set<String> alreadyIndexed = reindex ? Collections.emptySet() : loadIndexedSessionIds();

        int totalSessions = 0;
        int totalToolCalls = 0;
        int totalErrors = 0;
        int maxSessions = maxSessionsPerRun();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillisPerRun());
        boolean truncated = false;
        Map<String, Integer> bySource = new LinkedHashMap<>();

        for (ChatSourceAdapter adapter : registry.all()) {
            if (maxSessions > 0 && totalSessions >= maxSessions) {
                truncated = true;
                break;
            }
            if (System.nanoTime() > deadlineNanos) {
                truncated = true;
                break;
            }
            String sourceId = adapter.id();
            if (sourceFilter != null && !"all".equalsIgnoreCase(sourceFilter)
                    && !sourceId.equals(sourceFilter)) continue;

            try {
                List<ChatSessionSummary> sessions = adapter.list();
                for (ChatSessionSummary session : sessions) {
                    if (maxSessions > 0 && totalSessions >= maxSessions) {
                        truncated = true;
                        break;
                    }
                    if (System.nanoTime() > deadlineNanos) {
                        truncated = true;
                        break;
                    }
                    if (!reindex && alreadyIndexed.contains(session.sessionId())) continue;
                    try {
                        int count = indexSession(adapter, session);
                        if (count > 0) {
                            totalToolCalls += count;
                            bySource.merge(sourceId, count, Integer::sum);
                        }
                        totalSessions++;
                    } catch (Exception e) {
                        logger.debug("Error indexing session {}: {}", session.sessionId(), e.getMessage());
                        totalErrors++;
                    }
                }
            } catch (Exception e) {
                logger.warn("Error listing sessions from {}: {}", sourceId, e.getMessage());
                totalErrors++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionsScanned", totalSessions);
        result.put("toolCallsIndexed", totalToolCalls);
        result.put("errors", totalErrors);
        result.put("truncated", truncated);
        result.put("bySource", bySource);
        return result;
    }

    private int indexSession(ChatSourceAdapter adapter, ChatSessionSummary session) throws IOException {
        String sourceId = adapter.id();
        String sessionId = session.sessionId();

        ToolCallExtractor.ExtractionResult result;
        if ("opencode".equals(sourceId)) {
            result = ToolCallExtractor.extractOpenCode(sessionId);
        } else {
            Optional<Path> file = ToolCallExtractor.findSessionFile(adapter, sessionId);
            if (file.isEmpty()) return 0;

            result = switch (sourceId) {
                case "claude-code" -> ToolCallExtractor.extractClaude(file.get(), sessionId);
                case "codex" -> ToolCallExtractor.extractCodex(file.get(), sessionId);
                case "qwen" -> ToolCallExtractor.extractQwen(file.get(), sessionId);
                case "gemini" -> ToolCallExtractor.extractGemini(file.get(), sessionId);
                default -> new ToolCallExtractor.ExtractionResult(sessionId, sourceId, null, Collections.emptyList());
            };
        }

        // Write to JSONL index via shared writer
        for (ToolCallExtractor.ExtractedToolCall tc : result.toolCalls()) {
            String projDir = tc.projectDirectory() != null ? tc.projectDirectory() : result.projectDirectory();
            toolCallWriter.record(sessionId, tc.toolName(), tc.toolInput(),
                    tc.agentName() != null ? tc.agentName() : sourceId,
                    "transcript", tc.isError(), projDir);
        }
        return result.toolCalls().size();
    }

    /**
     * Periodic backfill: indexes any tool calls from provider transcripts
     * (Claude Code, Codex, Qwen, OpenCode, Gemini) that weren't captured
     * in real time. Runs every 10 minutes after an initial 2-minute delay.
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void scheduledBackfill() {
        try {
            Map<String, Object> result = indexTranscripts(null, false);
            int indexed = (int) result.getOrDefault("toolCallsIndexed", 0);
            if (indexed > 0) {
                logger.info("Transcript backfill indexed {} tool calls: {}", indexed, result.get("bySource"));
            }
        } catch (Exception e) {
            logger.warn("Scheduled transcript backfill failed: {}", e.getMessage(), e);
        }
    }

    private Set<String> loadIndexedSessionIds() {
        Set<String> ids = new HashSet<>();
        Path indexFile = getCombinedIndexFile();
        if (!Files.exists(indexFile)) return ids;
        try (var reader = Files.newBufferedReader(indexFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    if ("transcript".equals(node.path("source").asText(""))) {
                        ids.add(node.path("sessionId").asText(""));
                    }
                } catch (Exception e) {
                    logger.debug("Skipping malformed index line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Error reading index for dedup: {}", e.getMessage());
        }
        return ids;
    }

}
