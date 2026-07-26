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

package ai.kompile.core.graphrag.partition.reuse;

import ai.kompile.core.graphrag.model.Graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers what an extractor already produced for a unit of work, so the same text is not read
 * twice.
 *
 * <p>What comes back is the extraction itself, not permission to skip it. A partition that reuses
 * an answer still stages it: the chunk really does mention what the first read found, and a
 * partition whose manifest says a chunk was processed while its graph never heard of that chunk's
 * entities would be claiming coverage it does not have. Reuse saves the model call, never the
 * evidence.</p>
 *
 * <p>Forgetting is always safe. A miss costs one extraction that had already been done; it can
 * never produce a wrong answer, which is why the in-memory ledger is free to bound itself and why
 * a durable implementation may drop entries whenever it likes.</p>
 *
 * <p>Implementations must be safe for use from several threads: one ledger is shared by every
 * partition in a run, and mini-batches are where a crawl parallelises.</p>
 */
public interface ExtractionLedger {

    /** How many entries the in-memory ledger keeps before it starts forgetting the oldest. */
    int DEFAULT_MAX_ENTRIES = 2048;

    /**
     * What a ledger remembers about one unit of work.
     *
     * @param key          the work's identity
     * @param firstChunkId chunk whose reading actually produced this, kept so an audit can see
     *                     that the model never read the chunk now reusing it
     * @param result       the extraction output; treat as read-only, it is shared
     * @param timesReused  how many times it has been handed out since it was produced, counting
     *                     the hand-out that returned this record; a freshly recorded entry
     *                     nobody has asked for yet is 0
     */
    record Reuse(ExtractionKey key, String firstChunkId, Graph result, int timesReused) {

        public String describe() {
            return "reused extraction first read for chunk " + firstChunkId
                    + " (" + key.describe() + ")";
        }
    }

    /**
     * Running totals for a run's audit.
     *
     * @param firstRuns extractions that actually ran
     * @param reuses    extractions served from what an earlier read produced
     */
    record Stats(int firstRuns, int reuses) {

        public int total() {
            return firstRuns + reuses;
        }

        /** Share of units of work that did not need the extractor, in [0,1]. */
        public double reuseRate() {
            return total() == 0 ? 0.0 : (double) reuses / total();
        }

        public String describe() {
            return "extractions=" + firstRuns + " reused=" + reuses
                    + String.format(" (%.0f%%)", reuseRate() * 100);
        }
    }

    /**
     * Returns what an earlier read produced for {@code key}, counting the hit.
     *
     * @return empty when this work has not been done, which is not an error
     */
    Optional<Reuse> lookup(ExtractionKey key);

    /**
     * Remembers that reading {@code chunkId} under {@code key} produced {@code result}.
     *
     * <p>A {@code null} result is remembered too. "This text yields nothing" is an answer, and
     * re-asking for it is exactly the kind of repeated work this exists to stop.</p>
     */
    void record(ExtractionKey key, String chunkId, Graph result);

    /** Totals so far. */
    Stats stats();

    /** A bounded, thread-safe ledger for a single run. */
    static ExtractionLedger inMemory() {
        return inMemory(DEFAULT_MAX_ENTRIES);
    }

    /**
     * A bounded, thread-safe ledger keeping at most {@code maxEntries} extractions.
     *
     * <p>Sizing it is a memory decision, not a correctness one — see the class note on
     * forgetting.</p>
     */
    static ExtractionLedger inMemory(int maxEntries) {
        return new InMemoryExtractionLedger(maxEntries);
    }

    /** Least-recently-used, synchronised on itself. */
    final class InMemoryExtractionLedger implements ExtractionLedger {

        private final Map<String, Reuse> entries;
        private int firstRuns;
        private int reuses;

        private InMemoryExtractionLedger(int maxEntries) {
            int bound = Math.max(1, maxEntries);
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Reuse> eldest) {
                    return size() > bound;
                }
            };
        }

        @Override
        public Optional<Reuse> lookup(ExtractionKey key) {
            if (key == null) {
                return Optional.empty();
            }
            synchronized (this) {
                Reuse found = entries.get(key.id());
                if (found == null) {
                    return Optional.empty();
                }
                Reuse counted = new Reuse(found.key(), found.firstChunkId(), found.result(),
                        found.timesReused() + 1);
                entries.put(key.id(), counted);
                reuses++;
                return Optional.of(counted);
            }
        }

        @Override
        public void record(ExtractionKey key, String chunkId, Graph result) {
            if (key == null) {
                return;
            }
            synchronized (this) {
                firstRuns++;
                entries.put(key.id(), new Reuse(key, chunkId, result, 0));
            }
        }

        @Override
        public synchronized Stats stats() {
            return new Stats(firstRuns, reuses);
        }
    }
}
