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
package ai.kompile.core.graphrag.maintenance.model;

import java.util.List;

/**
 * Store-agnostic result of a graph pruning/maintenance operation.
 *
 * <p>When {@code dryRun} is {@code true}, {@code affectedIds} contains the IDs
 * that <em>would</em> have been acted on; no mutations were performed.</p>
 *
 * @param affectedIds   Node or edge UUIDs (String nodeId / edgeId) that were
 *                      marked stale, hard-deleted, or would have been in dry-run.
 * @param affectedCount Number of items affected (may equal {@code affectedIds.size()},
 *                      or a higher count when bulk ops are used).
 * @param hardDeleted   Number of records permanently removed (0 on soft-delete or dry-run).
 * @param dryRun        Whether this was a read-only simulation.
 */
public record GraphPruneResult(
        List<String> affectedIds,
        int affectedCount,
        int hardDeleted,
        boolean dryRun
) {

    /** Convenience factory: empty result (nothing to prune). */
    public static GraphPruneResult empty(boolean dryRun) {
        return new GraphPruneResult(List.of(), 0, 0, dryRun);
    }

    /** Convenience factory: result carrying only a hard-delete count (bulk-delete path). */
    public static GraphPruneResult ofHardDelete(int hardDeleted, boolean dryRun) {
        return new GraphPruneResult(List.of(), hardDeleted, hardDeleted, dryRun);
    }

    /** Convenience factory: soft-delete result. */
    public static GraphPruneResult ofSoftDelete(List<String> ids, boolean dryRun) {
        return new GraphPruneResult(ids, ids.size(), 0, dryRun);
    }
}
