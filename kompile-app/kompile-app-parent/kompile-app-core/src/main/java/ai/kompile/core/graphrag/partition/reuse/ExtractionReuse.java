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
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionMember;

import java.util.Optional;

/**
 * Runs an extraction, or hands back what an identical one already produced.
 *
 * <p>This is the seam between "which chunks is this partition about" and "what does reading one
 * cost". Partitions overlap on purpose — a chunk naming two subjects belongs to both — so without
 * this the same text is read once per partition that claims it, and a document's repeated
 * boilerplate is read once per section it appears in.</p>
 *
 * <p>A reuse is not a skip. The remembered graph is returned so the caller stages it exactly as it
 * would stage a fresh one; only the model call is saved. What changes is the audit trail: the
 * outcome carries a note saying which chunk was actually read, so nobody later mistakes a reused
 * answer for a second independent reading of the same claim.</p>
 *
 * <p>An extraction that throws is not recorded. A failure is the extractor's answer for now, not
 * the text's, and remembering it would turn one bad call into a permanent one.</p>
 */
public final class ExtractionReuse {

    /**
     * Profile used when a run does not name one.
     *
     * <p>Honest only while the ledger's lifetime holds a single extractor — which is exactly true
     * of the per-run ledger, since the extractor is a parameter of the run. A caller sharing a
     * ledger between different models, passes or prompts must name them, or one's answers will be
     * served for another's questions.</p>
     */
    public static final String DEFAULT_PROFILE = "extractor:run-local";

    private final ExtractionLedger ledger;
    private final String profile;

    private ExtractionReuse(ExtractionLedger ledger, String profile) {
        this.ledger = ledger == null ? ExtractionLedger.inMemory() : ledger;
        this.profile = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim();
    }

    /** Reuse within one run: a fresh ledger, and the single extractor that run was given. */
    public static ExtractionReuse forRun() {
        return new ExtractionReuse(ExtractionLedger.inMemory(), DEFAULT_PROFILE);
    }

    /** Reuse against a ledger that may outlive this run, and so must know whose answers it holds. */
    public static ExtractionReuse of(ExtractionLedger ledger, String profile) {
        return new ExtractionReuse(ledger, profile);
    }

    /** Reads one chunk. */
    @FunctionalInterface
    public interface ChunkExtractor {

        Graph extract(PartitionMember member, EntityPartition partition);
    }

    /**
     * What one call to {@link #extract} produced.
     *
     * @param produced the extraction output; null means the text yields nothing, which is a real
     *                 answer and is remembered as one
     * @param reused   true when the extractor was not called because an identical unit of work
     *                 had already been done
     * @param note     what to record on the member, or null when there is nothing worth saying
     */
    public record Outcome(Graph produced, boolean reused, String note) {

        static Outcome ran(Graph produced) {
            return new Outcome(produced, false, null);
        }

        static Outcome from(ExtractionLedger.Reuse hit) {
            return new Outcome(hit.result(), true, hit.describe());
        }
    }

    /**
     * Returns {@code member}'s extraction, running {@code extractor} only if this work is new.
     *
     * @param text the chunk's text when the caller has it; without it the work can still be
     *             matched against the same chunk read for another partition, but not against a
     *             different chunk holding the same words
     */
    public Outcome extract(PartitionMember member, String text, EntityPartition partition,
                           ChunkExtractor extractor) {
        if (extractor == null) {
            throw new IllegalArgumentException("extraction needs an extractor");
        }
        Optional<ExtractionKey> key = ExtractionKey.forMember(member, text, profile);
        if (key.isEmpty()) {
            // Nothing identifies this work, so nothing can be shown identical to it. Doing it is
            // the only honest option.
            return Outcome.ran(extractor.extract(member, partition));
        }
        Optional<ExtractionLedger.Reuse> hit = ledger.lookup(key.get());
        if (hit.isPresent()) {
            return Outcome.from(hit.get());
        }
        Graph produced = extractor.extract(member, partition);
        ledger.record(key.get(), member.chunkId(), produced);
        return Outcome.ran(produced);
    }

    /** The ledger being consulted, shared with anything else running against it. */
    public ExtractionLedger ledger() {
        return ledger;
    }

    /** Whose answers this reuses. */
    public String profile() {
        return profile;
    }

    /** Totals so far, for a run's audit. */
    public ExtractionLedger.Stats stats() {
        return ledger.stats();
    }
}
