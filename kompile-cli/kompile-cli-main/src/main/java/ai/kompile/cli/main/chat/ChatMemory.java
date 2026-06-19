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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.MemoryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Memory component for the chat REPL that provides cross-conversation
 * context through three layers:
 * <ol>
 *   <li><b>Persistent MEMORY.md</b> - Markdown files loaded at session start,
 *       containing stable facts, decisions, patterns, and preferences.
 *       Stored at {@code ~/.kompile/memory/MEMORY.md} (global) and
 *       {@code .kompile/memory/MEMORY.md} (project-level).</li>
 *   <li><b>Transcript search</b> - Keyword search across previous conversation
 *       transcripts in {@code ~/.kompile/conversations/}.</li>
 *   <li><b>RAG search</b> - Semantic/keyword search across indexed documents
 *       via the kompile-app vector store.</li>
 * </ol>
 * <p>
 * This mirrors how tools like Claude Code (MEMORY.md), Codex (AGENTS.md + SQLite),
 * and Windsurf (auto-memories) provide long-term context across sessions.
 */
public class ChatMemory {

    private static final int MAX_TRANSCRIPT_SNIPPETS = 5;
    private static final int MAX_SNIPPET_LINES = 8;
    private static final int MAX_RAG_RESULTS = 3;
    private static final int MAX_MEMORY_CONTEXT_CHARS = 6000;

    private final McpSseClient mcpClient; // may be null in local mode
    private final ObjectMapper objectMapper;
    private final String currentSessionId;
    private final Path conversationsDir;
    private final Path workDir;

    private boolean enabled;
    private boolean persistentMemoryEnabled;
    private boolean transcriptSearchEnabled;
    private boolean ragSearchEnabled;

    // Cached persistent memory content (loaded once at startup)
    private String persistentMemoryContent;

    // Cached transcript index: maps session IDs to their parsed turns
    private Map<String, List<ChatHistory.Turn>> transcriptCache;
    private long lastCacheRefresh;
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    public ChatMemory(McpSseClient mcpClient, String currentSessionId, boolean enabled) {
        this.mcpClient = mcpClient;
        this.objectMapper = mcpClient != null ? mcpClient.getObjectMapper() : JsonUtils.standardMapper();
        this.currentSessionId = currentSessionId;
        this.conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        this.workDir = Paths.get(System.getProperty("user.dir"));
        this.enabled = enabled;
        this.persistentMemoryEnabled = true;
        this.transcriptSearchEnabled = true;
        this.ragSearchEnabled = mcpClient != null; // only if server connected

        // Load persistent memory at construction
        this.persistentMemoryContent = MemoryTool.loadMemoryForContext(workDir);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPersistentMemoryEnabled() {
        return persistentMemoryEnabled;
    }

    public void setPersistentMemoryEnabled(boolean persistentMemoryEnabled) {
        this.persistentMemoryEnabled = persistentMemoryEnabled;
    }

    /**
     * Reloads the persistent MEMORY.md files from disk.
     */
    public void reloadPersistentMemory() {
        this.persistentMemoryContent = MemoryTool.loadMemoryForContext(workDir);
    }

    public boolean isTranscriptSearchEnabled() {
        return transcriptSearchEnabled;
    }

    public void setTranscriptSearchEnabled(boolean transcriptSearchEnabled) {
        this.transcriptSearchEnabled = transcriptSearchEnabled;
    }

    public boolean isRagSearchEnabled() {
        return ragSearchEnabled;
    }

    public void setRagSearchEnabled(boolean ragSearchEnabled) {
        this.ragSearchEnabled = ragSearchEnabled;
    }

    /**
     * Returns the persistent MEMORY.md content for injection into the system
     * prompt at session start. This is always injected regardless of query.
     */
    public String getPersistentMemoryContent() {
        if (!enabled || !persistentMemoryEnabled) {
            return null;
        }
        return persistentMemoryContent;
    }

    /**
     * Builds a memory context block for the given user query by searching
     * previous transcripts and RAG. Returns null if no relevant context found.
     * <p>
     * Note: Persistent MEMORY.md is injected separately at session start via
     * {@link #getPersistentMemoryContent()}, not per-message.
     */
    public String buildMemoryContext(String query) {
        if (!enabled) {
            return null;
        }

        StringBuilder context = new StringBuilder();

        // 1. Search previous conversation transcripts
        if (transcriptSearchEnabled) {
            String transcriptContext = searchTranscripts(query);
            if (transcriptContext != null && !transcriptContext.isBlank()) {
                context.append("[Previous conversations]\n");
                context.append(transcriptContext);
                context.append("\n");
            }
        }

        // 2. Search RAG index for relevant documents
        if (ragSearchEnabled && mcpClient != null) {
            String ragContext = searchRag(query);
            if (ragContext != null && !ragContext.isBlank()) {
                context.append("[Retrieved documents]\n");
                context.append(ragContext);
                context.append("\n");
            }
        }

        if (context.length() == 0) {
            return null;
        }

        // Truncate if too long
        String result = context.toString();
        if (result.length() > MAX_MEMORY_CONTEXT_CHARS) {
            result = result.substring(0, MAX_MEMORY_CONTEXT_CHARS) + "\n... (truncated)";
        }

        return result;
    }

    /**
     * Searches previous conversation transcripts for turns relevant to the query.
     * Uses keyword matching across all saved transcripts except the current one.
     */
    public String searchTranscripts(String query) {
        refreshCacheIfNeeded();
        if (transcriptCache == null || transcriptCache.isEmpty()) {
            return null;
        }

        // Extract keywords from query (words >= 3 chars, lowercased)
        Set<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return null;
        }

        // Score each turn across all transcripts
        List<ScoredSnippet> scored = new ArrayList<>();
        for (Map.Entry<String, List<ChatHistory.Turn>> entry : transcriptCache.entrySet()) {
            String sid = entry.getKey();
            if (sid.equals(currentSessionId)) {
                continue; // skip current conversation
            }

            List<ChatHistory.Turn> turns = entry.getValue();
            for (int i = 0; i < turns.size(); i++) {
                ChatHistory.Turn turn = turns.get(i);
                int score = scoreTurn(turn, keywords);
                if (score > 0) {
                    // Build a snippet: include the turn and its context (adjacent turn)
                    StringBuilder snippet = new StringBuilder();
                    snippet.append("[").append(sid).append("]\n");

                    // Include the preceding user message if this is an assistant response
                    if (turn.role().equals("assistant") && i > 0) {
                        ChatHistory.Turn prev = turns.get(i - 1);
                        if (prev.role().equals("user")) {
                            snippet.append("> ").append(truncateLines(prev.content(), 2)).append("\n");
                        }
                    }

                    // The matching turn itself
                    if (turn.role().equals("user")) {
                        snippet.append("> ").append(truncateLines(turn.content(), MAX_SNIPPET_LINES)).append("\n");
                        // Include the following assistant response
                        if (i + 1 < turns.size() && turns.get(i + 1).role().equals("assistant")) {
                            snippet.append(truncateLines(turns.get(i + 1).content(), MAX_SNIPPET_LINES)).append("\n");
                        }
                    } else {
                        snippet.append(truncateLines(turn.content(), MAX_SNIPPET_LINES)).append("\n");
                    }

                    scored.add(new ScoredSnippet(score, snippet.toString()));
                }
            }
        }

        if (scored.isEmpty()) {
            return null;
        }

        // Sort by score descending, take top N
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        return scored.stream()
                .limit(MAX_TRANSCRIPT_SNIPPETS)
                .map(s -> s.text)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Searches the RAG index via MCP tools for documents relevant to the query.
     */
    public String searchRag(String query) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("query", query);
            args.put("maxResults", MAX_RAG_RESULTS);

            String rawResult = mcpClient.callTool("rag_query", args);
            JsonNode json = objectMapper.readTree(rawResult);

            JsonNode docs = json.path("retrieved_documents");
            if (!docs.isArray() || docs.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size() && i < MAX_RAG_RESULTS; i++) {
                JsonNode doc = docs.get(i);
                String content;
                if (doc.isTextual()) {
                    content = doc.asText();
                } else {
                    content = doc.path("content").asText(doc.path("text").asText(""));
                }
                if (!content.isBlank()) {
                    sb.append("[doc ").append(i + 1).append("] ");
                    sb.append(truncateChars(content, 800));
                    sb.append("\n\n");
                }
            }

            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            // RAG search is best-effort
            return null;
        }
    }

    /**
     * Explicit search command that returns formatted results for display.
     * Searches persistent memory, transcripts, and RAG.
     */
    public String search(String query) {
        StringBuilder results = new StringBuilder();

        // Search persistent memory files
        String memoryResults = searchPersistentMemory(query);
        if (memoryResults != null && !memoryResults.isBlank()) {
            results.append("── Persistent memory ──\n\n");
            results.append(memoryResults);
            results.append("\n");
        }

        // Search transcripts
        String transcriptResults = searchTranscripts(query);
        if (transcriptResults != null && !transcriptResults.isBlank()) {
            results.append("── Previous conversations ──\n\n");
            results.append(transcriptResults);
            results.append("\n");
        }

        // Search RAG
        if (mcpClient != null) {
            String ragResults = searchRag(query);
            if (ragResults != null && !ragResults.isBlank()) {
                results.append("── Retrieved documents ──\n\n");
                results.append(ragResults);
            }
        }

        if (results.length() == 0) {
            return "No results found for: " + query;
        }

        return results.toString();
    }

    /**
     * Searches persistent memory files (MEMORY.md and topic files) for
     * lines matching the query.
     */
    private String searchPersistentMemory(String query) {
        String queryLower = query.toLowerCase();
        StringBuilder sb = new StringBuilder();

        // Search project memory
        Path projectDir = workDir.resolve(".kompile").resolve("memory");
        searchMemoryDir(projectDir, queryLower, "project", sb);

        // Search global memory
        Path globalDir = KompileHome.homeDirectory().toPath().resolve("memory");
        searchMemoryDir(globalDir, queryLower, "global", sb);

        return sb.length() > 0 ? sb.toString() : null;
    }

    private void searchMemoryDir(Path dir, String queryLower, String scope, StringBuilder sb) {
        if (!Files.exists(dir)) return;

        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> !Files.isDirectory(p)).toList()) {
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(queryLower)) {
                            sb.append("[").append(scope).append("/").append(f.getFileName()).append(":").append(i + 1).append("] ");
                            sb.append(lines[i].trim()).append("\n");
                        }
                    }
                } catch (IOException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            // skip
        }
    }

    /**
     * Returns a status summary of the memory component.
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Memory:              ").append(enabled ? "enabled" : "disabled").append("\n");
        sb.append("  Persistent (MEMORY.md): ").append(persistentMemoryEnabled ? "on" : "off");
        if (persistentMemoryContent != null) {
            int lines = persistentMemoryContent.split("\n").length;
            sb.append(" (").append(lines).append(" lines loaded)");
        }
        sb.append("\n");

        // Show persistent memory file details
        sb.append(MemoryTool.getMemoryStatus(workDir));

        sb.append("  Transcript search: ").append(transcriptSearchEnabled ? "on" : "off").append("\n");
        sb.append("  RAG search:        ").append(ragSearchEnabled ? "on" : "off").append("\n");

        // Count available transcripts
        refreshCacheIfNeeded();
        int totalTranscripts = transcriptCache != null ? transcriptCache.size() : 0;
        int totalTurns = 0;
        if (transcriptCache != null) {
            for (List<ChatHistory.Turn> turns : transcriptCache.values()) {
                totalTurns += turns.size();
            }
        }
        sb.append("  Transcripts:       ").append(totalTranscripts).append(" conversations, ").append(totalTurns).append(" turns\n");

        // Get document count from RAG (server mode only)
        if (mcpClient != null) {
            try {
                String result = mcpClient.callTool("get_document_count", (JsonNode) null);
                JsonNode json = objectMapper.readTree(result);
                int docCount = json.path("documentCount").asInt(json.path("count").asInt(-1));
                if (docCount >= 0) {
                    sb.append("  RAG documents:     ").append(docCount).append(" indexed\n");
                }
            } catch (Exception e) {
                sb.append("  RAG documents:     (unavailable)\n");
            }
        }

        return sb.toString();
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private void refreshCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (transcriptCache != null && (now - lastCacheRefresh) < CACHE_TTL_MS) {
            return;
        }

        transcriptCache = new LinkedHashMap<>();
        lastCacheRefresh = now;

        if (!Files.exists(conversationsDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(conversationsDir)) {
            files.filter(p -> p.toString().endsWith(".txt"))
                    .filter(p -> !p.getFileName().toString().equals("index.properties"))
                    .forEach(p -> {
                        String sid = p.getFileName().toString().replace(".txt", "");
                        try {
                            ChatHistory history = new ChatHistory(sid);
                            List<ChatHistory.Turn> turns = history.readTurns();
                            if (!turns.isEmpty()) {
                                transcriptCache.put(sid, turns);
                            }
                        } catch (Exception e) {
                            // Skip unreadable transcripts
                        }
                    });
        } catch (IOException e) {
            // Best effort
        }
    }

    /**
     * Extracts search keywords from a query string.
     * Filters out common stop words and short words.
     */
    static Set<String> extractKeywords(String query) {
        Set<String> stopWords = Set.of(
                "the", "and", "for", "are", "but", "not", "you", "all",
                "can", "had", "her", "was", "one", "our", "out", "has",
                "what", "when", "how", "who", "which", "where", "why",
                "this", "that", "with", "from", "they", "been", "have",
                "will", "would", "could", "should", "does", "did", "about",
                "into", "than", "then", "them", "these", "those", "some",
                "just", "also", "more", "other", "very", "much", "such"
        );

        return Arrays.stream(query.toLowerCase().split("[\\s,;.!?()\\[\\]{}\"']+"))
                .filter(w -> w.length() >= 3)
                .filter(w -> !stopWords.contains(w))
                .collect(Collectors.toSet());
    }

    /**
     * Scores a turn based on keyword matches.
     */
    private int scoreTurn(ChatHistory.Turn turn, Set<String> keywords) {
        String text = turn.content().toLowerCase();
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score++;
                // Bonus for exact word match (not just substring)
                if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(text).find()) {
                    score++;
                }
            }
        }
        return score;
    }

    private static String truncateLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        sb.append("\n... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    private static String truncateChars(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private record ScoredSnippet(int score, String text) {}
}
