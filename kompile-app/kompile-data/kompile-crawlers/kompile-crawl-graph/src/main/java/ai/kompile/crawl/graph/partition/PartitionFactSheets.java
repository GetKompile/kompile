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

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.EntityPartition;

import java.util.function.Function;

/**
 * Works out which fact sheet a partition is scoped to.
 *
 * <p>{@link ai.kompile.core.graphrag.partition.PartitionKey} carries no fact-sheet id on purpose —
 * the control plane in {@code kompile-app-core} does not know that kompile stores graphs in fact
 * sheets, and should not learn it. The scope therefore arrives as data, either as an explicit pin
 * or as the key's own snapshot id, and this class is the one place that reading is written down.</p>
 *
 * <p>The snapshot fallback is not a convenience: a partition's key already declares which graph
 * snapshot it was discovered against, and for a kompile graph the snapshot <em>is</em> the fact
 * sheet. Reading it there keeps the two from disagreeing.</p>
 */
public final class PartitionFactSheets {

    /** Pin name carrying the fact sheet a partition is discovered against. */
    public static final String FACT_SHEET_PIN = "factSheetId";

    private PartitionFactSheets() {
    }

    /** Resolver reading the pin, then the key's snapshot id. Never throws; unknown means null. */
    public static Function<EntityPartition, Long> fromPinOrSnapshot() {
        return PartitionFactSheets::resolve;
    }

    /** Resolver pinned to one fact sheet, for callers that already know the scope. */
    public static Function<EntityPartition, Long> fixed(Long factSheetId) {
        return partition -> factSheetId;
    }

    /**
     * The fact sheet a partition is scoped to, or {@code null} when it does not say.
     *
     * <p>A null is returned rather than a default, because there is no safe default: guessing a
     * fact sheet would make a channel walk a graph the partition never claimed to be about, and
     * the resulting evidence would carry a coverage claim nobody made.</p>
     */
    public static Long resolve(EntityPartition partition) {
        if (partition == null) {
            return null;
        }
        Long pinned = parse(partition.pins().get(FACT_SHEET_PIN));
        return pinned != null ? pinned : parse(partition.key().snapshotId());
    }

    private static Long parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            // A snapshot id that is not a fact-sheet id is a legitimate use of the field, not an
            // error — some other store's snapshot handle. It simply does not resolve here.
            return null;
        }
    }
}
