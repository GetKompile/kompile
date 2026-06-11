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

import ai.kompile.cli.common.chat.sources.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Indexes tool calls from existing provider transcripts into the ToolCallIndex.
 * Uses ToolCallExtractor (shared with Spring service) for provider-specific parsing,
 * then writes results through ToolCallIndex to the JSONL files on disk.
 */
public class TranscriptToolCallIndexer {

    private final ToolCallIndex index = ToolCallIndex.getInstance();

    /** Result of an indexing run. */
    public record IndexResult(int sessionsScanned, int toolCallsIndexed, int errors,
                              Map<String, Integer> bySource) {}

    /**
     * Index tool calls from all discovered provider sessions.
     * @param sources  which sources to index (null or empty = all)
     * @param reindex  if true, re-index even if session was already indexed
     */
    public IndexResult indexAll(Set<String> sources, boolean reindex) {
        ChatSourceRegistry registry = ChatSourceRegistry.getInstance();
        int totalSessions = 0;
        int totalToolCalls = 0;
        int totalErrors = 0;
        Map<String, Integer> bySource = new LinkedHashMap<>();

        Set<String> alreadyIndexed = reindex ? Collections.emptySet() : getIndexedSessionIds();

        for (ChatSourceAdapter adapter : registry.all()) {
            String sourceId = adapter.id();
            if (sources != null && !sources.isEmpty() && !sources.contains(sourceId)) continue;
            // Only index providers we have extractors for
            if (!Set.of("claude-code", "codex", "qwen", "opencode", "gemini", "pi",
                    "aider", "cline", "cursor", "continue", "kompile").contains(sourceId)) continue;

            try {
                List<ChatSessionSummary> sessions = adapter.list();
                for (ChatSessionSummary session : sessions) {
                    if (!reindex && alreadyIndexed.contains(session.sessionId())) continue;

                    try {
                        int count = indexSession(adapter, session);
                        if (count > 0) {
                            totalToolCalls += count;
                            bySource.merge(sourceId, count, Integer::sum);
                        }
                        totalSessions++;
                    } catch (Exception e) {
                        totalErrors++;
                    }
                }
            } catch (Exception e) {
                totalErrors++;
            }
        }

        return new IndexResult(totalSessions, totalToolCalls, totalErrors, bySource);
    }

    /**
     * Index tool calls from a single provider + session.
     */
    public int indexSession(String sourceId, String sessionId) throws IOException {
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance()
                .find(sourceId).orElse(null);
        if (adapter == null) throw new IOException("Unknown source: " + sourceId);

        List<ChatSessionSummary> sessions = adapter.list();
        for (ChatSessionSummary s : sessions) {
            if (s.sessionId().equals(sessionId)) {
                return indexSession(adapter, s);
            }
        }
        throw new IOException("Session not found: " + sessionId + " in " + sourceId);
    }

    private int indexSession(ChatSourceAdapter adapter, ChatSessionSummary session) throws IOException {
        String sourceId = adapter.id();
        String sessionId = session.sessionId();

        ToolCallExtractor.ExtractionResult result;
        if ("opencode".equals(sourceId)) {
            result = ToolCallExtractor.extractOpenCode(sessionId);
        } else if ("cursor".equals(sourceId)) {
            result = ToolCallExtractor.extractCursor(sessionId);
        } else {
            Optional<Path> file = ToolCallExtractor.findSessionFile(adapter, sessionId);
            if (file.isEmpty()) return 0;

            result = switch (sourceId) {
                case "claude-code" -> ToolCallExtractor.extractClaude(file.get(), sessionId);
                case "codex" -> ToolCallExtractor.extractCodex(file.get(), sessionId);
                case "qwen" -> ToolCallExtractor.extractQwen(file.get(), sessionId);
                case "gemini" -> ToolCallExtractor.extractGemini(file.get(), sessionId);
                case "pi" -> ToolCallExtractor.extractPi(file.get(), sessionId);
                case "aider" -> ToolCallExtractor.extractAider(file.get(), sessionId);
                case "cline" -> ToolCallExtractor.extractCline(file.get(), sessionId);
                case "continue" -> ToolCallExtractor.extractContinue(file.get(), sessionId);
                case "kompile" -> ToolCallExtractor.extractKompile(file.get(), sessionId);
                default -> new ToolCallExtractor.ExtractionResult(sessionId, sourceId, null, Collections.emptyList());
            };
        }

        // Write extracted tool calls to the index
        for (ToolCallExtractor.ExtractedToolCall tc : result.toolCalls()) {
            String projDir = tc.projectDirectory() != null ? tc.projectDirectory() : result.projectDirectory();
            index.record(sessionId, tc.toolName(), tc.toolInput(),
                    tc.agentName() != null ? tc.agentName() : sourceId,
                    "transcript", tc.isError(), 0, projDir);
        }
        return result.toolCalls().size();
    }

    private Set<String> getIndexedSessionIds() {
        Set<String> ids = new HashSet<>();
        List<ToolCallRecord> existing = index.search(
                null, null, null, null, null, null,
                ToolCallIndex.SortField.TIMESTAMP, ToolCallIndex.SortDirection.DESC,
                Integer.MAX_VALUE);
        for (ToolCallRecord r : existing) {
            if ("transcript".equals(r.getSource())) {
                ids.add(r.getSessionId());
            }
        }
        return ids;
    }
}
