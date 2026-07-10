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

package ai.kompile.cli.main.codeindex;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-healing for the local code index: before a read action, run a cheap
 * incremental index pass (fingerprint fast path — a no-op when nothing
 * changed) so results reflect the working tree instead of warning that they
 * are stale after the fact.
 *
 * <p>Throttled per project so bursts of tool calls don't stat-walk the tree
 * repeatedly, and deliberately failure-proof: any error is logged to stderr
 * and swallowed — a refresh must never break a search.
 */
public final class IndexAutoRefresher {

    private static final long DEFAULT_MIN_INTERVAL_MS = 30_000;

    /** projectId → epoch millis of the last refresh attempt. */
    private static final ConcurrentHashMap<String, Long> LAST_REFRESH = new ConcurrentHashMap<>();

    private IndexAutoRefresher() {}

    /**
     * Refresh the project's index if it exists, its root is still on disk,
     * and the throttle window has elapsed.
     *
     * @return a short annotation like {@code [index auto-refreshed: 3 files
     *         re-indexed, 1 deleted]} when the pass changed anything, else
     *         {@code null} (fresh index, throttled, missing, or failed).
     */
    public static String maybeRefresh(LocalCodeIndexer indexer, String projectId) {
        return maybeRefresh(indexer, projectId, DEFAULT_MIN_INTERVAL_MS);
    }

    /**
     * Variant with an explicit throttle interval (test seam; pass 0 to force).
     */
    static String maybeRefresh(LocalCodeIndexer indexer, String projectId, long minIntervalMs) {
        if (indexer == null || projectId == null || projectId.isBlank()) return null;
        try {
            if (!Files.isDirectory(LocalCodeIndexer.getIndexDir(projectId))) return null;

            long now = System.currentTimeMillis();
            Long previous = LAST_REFRESH.get(projectId);
            if (previous != null && now - previous < minIntervalMs) return null;
            // Claim the slot atomically so concurrent callers don't double-walk.
            if (previous == null) {
                if (LAST_REFRESH.putIfAbsent(projectId, now) != null) return null;
            } else if (!LAST_REFRESH.replace(projectId, previous, now)) {
                return null;
            }

            Map<String, Object> stats = indexer.getStats(projectId);
            Object rootPath = stats == null ? null : stats.get("rootPath");
            if (rootPath == null) return null;
            Path root = Path.of(rootPath.toString());
            if (!Files.isDirectory(root)) return null;

            PrintStream silent = new PrintStream(OutputStream.nullOutputStream(), false,
                    StandardCharsets.UTF_8);
            LocalCodeIndexer.IndexResult result =
                    indexer.index(root, projectId, null, null, silent);

            int reindexed = Math.max(0, result.filesProcessed() - result.filesSkipped());
            int deleted = result.filesDeleted();
            if (reindexed == 0 && deleted == 0) return null;
            StringBuilder note = new StringBuilder("[index auto-refreshed: ")
                    .append(reindexed).append(" file").append(reindexed == 1 ? "" : "s")
                    .append(" re-indexed");
            if (deleted > 0) {
                note.append(", ").append(deleted).append(" deleted");
            }
            return note.append(']').toString();
        } catch (Exception e) {
            System.err.println("[code-index] auto-refresh skipped for '" + projectId + "': "
                    + e.getMessage());
            return null;
        }
    }
}
