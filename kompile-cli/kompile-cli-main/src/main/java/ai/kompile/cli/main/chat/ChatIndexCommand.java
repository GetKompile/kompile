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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command to index tool calls and token usage from provider transcripts.
 * Shows live progress with accumulating totals as sessions are scanned.
 * <p>
 * Usage: kompile chat index [--source=claude-code] [--reindex] [--json]
 */
@CommandLine.Command(
        name = "index",
        description = "Index tool calls and token usage from provider transcripts",
        mixinStandardHelpOptions = true
)
public class ChatIndexCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--source", "-s"},
            description = "Source to index (claude-code, codex, qwen, gemini, pi, etc.). Default: all")
    private String source;

    @CommandLine.Option(names = {"--reindex", "-r"},
            description = "Re-index sessions even if already indexed",
            defaultValue = "false")
    private boolean reindex;

    @CommandLine.Option(names = {"--json"},
            description = "Output final result as JSON",
            defaultValue = "false")
    private boolean jsonOutput;

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";

    @Override
    public Integer call() {
        if (!jsonOutput) {
            System.out.println(BOLD + "Indexing provider transcripts..." + RESET);
            if (source != null) {
                System.out.println(DIM + "Source: " + source + RESET);
            }
            if (reindex) {
                System.out.println(YELLOW + "Re-indexing all sessions (force)" + RESET);
            }
            System.out.println();
        }

        TranscriptToolCallIndexer indexer = new TranscriptToolCallIndexer();

        Set<String> sources = null;
        if (source != null && !source.isEmpty() && !"all".equalsIgnoreCase(source)) {
            sources = Set.of(source);
        }

        // Use the progress callback variant
        ProgressTracker tracker = new ProgressTracker();
        TranscriptToolCallIndexer.IndexResult result = indexer.indexAllWithProgress(sources, reindex, tracker);

        if (!jsonOutput) {
            // Clear the progress line and print final results
            System.out.print("\r\033[K");
            printResult(result);
        } else {
            printJsonResult(result);
        }

        return result.errors() > 0 ? 1 : 0;
    }

    private void printResult(TranscriptToolCallIndexer.IndexResult result) {
        System.out.println(BOLD + GREEN + "Indexing complete." + RESET);
        System.out.println();

        System.out.printf("  Sessions scanned:        %s%d%s%n", BOLD, result.sessionsScanned(), RESET);
        System.out.printf("  Tool calls indexed:      %s%d%s%n", BOLD, result.toolCallsIndexed(), RESET);
        System.out.printf("  Token summaries saved:   %s%d%s%n", BOLD, result.tokenSummariesPersisted(), RESET);
        System.out.printf("  Errors:                  %s%s%d%s%n",
                result.errors() > 0 ? RED : "", BOLD, result.errors(), RESET);

        if (result.totalInputTokens() > 0 || result.totalOutputTokens() > 0) {
            System.out.println();
            System.out.println(BOLD + "  Token totals:" + RESET);
            System.out.printf("    Input:   %s%s%s%n", CYAN, formatTokens(result.totalInputTokens()), RESET);
            System.out.printf("    Output:  %s%s%s%n", CYAN, formatTokens(result.totalOutputTokens()), RESET);
            System.out.printf("    Total:   %s%s%s%n", CYAN,
                    formatTokens(result.totalInputTokens() + result.totalOutputTokens()), RESET);
        }

        if (!result.bySource().isEmpty()) {
            System.out.println();
            System.out.println(BOLD + "  By source:" + RESET);
            for (Map.Entry<String, Integer> entry : result.bySource().entrySet()) {
                System.out.printf("    %-20s %s%d%s tool calls%n",
                        entry.getKey(), BOLD, entry.getValue(), RESET);
            }
        }
        System.out.println();
    }

    private void printJsonResult(TranscriptToolCallIndexer.IndexResult result) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sessionsScanned", result.sessionsScanned());
            map.put("toolCallsIndexed", result.toolCallsIndexed());
            map.put("tokenSummariesPersisted", result.tokenSummariesPersisted());
            map.put("errors", result.errors());
            map.put("totalInputTokens", result.totalInputTokens());
            map.put("totalOutputTokens", result.totalOutputTokens());
            map.put("bySource", result.bySource());
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(map));
        } catch (Exception e) {
            System.err.println("Error writing JSON: " + e.getMessage());
        }
    }

    private String formatTokens(long count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000.0);
        return Long.toString(count);
    }

    /**
     * Receives progress callbacks from the indexer and prints live updates.
     */
    private class ProgressTracker implements TranscriptToolCallIndexer.IndexProgressCallback {
        @Override
        public void onSessionIndexed(String sourceId, String sessionId, int toolCalls,
                                     int totalSessions, int totalToolCalls, int totalTokenSummaries) {
            if (jsonOutput) return;
            String truncId = sessionId.length() > 12 ? sessionId.substring(0, 12) + "..." : sessionId;
            System.out.printf("\r\033[K  %s[%s]%s %s%s%s  |  sessions: %s%d%s  tools: %s%d%s  tokens: %s%d%s",
                    DIM, sourceId, RESET,
                    DIM, truncId, RESET,
                    BOLD, totalSessions, RESET,
                    BOLD, totalToolCalls, RESET,
                    BOLD, totalTokenSummaries, RESET);
        }

        @Override
        public void onSourceStarted(String sourceId) {
            if (jsonOutput) return;
            System.out.printf("\r\033[K  Scanning %s%s%s...%n", CYAN, sourceId, RESET);
        }

        @Override
        public void onError(String sourceId, String sessionId, String message) {
            if (jsonOutput) return;
            String truncId = sessionId != null && sessionId.length() > 12
                    ? sessionId.substring(0, 12) + "..." : sessionId;
            System.out.printf("\r\033[K  %s[ERROR]%s %s/%s: %s%n", RED, RESET, sourceId, truncId, message);
        }
    }
}
