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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One partition's run, held open over a staged graph, until it is committed or abandoned.
 *
 * <p>A partition reads many chunks and the graph should hear about it once. Between those two
 * facts sits everything a transaction is for: a place to accumulate, a boundary to undo back to,
 * and a single moment where the accumulated picture becomes the graph's problem instead of the
 * partition's.</p>
 *
 * <p><strong>The rollback unit is the chunk</strong>, because that is the granularity the
 * lifecycle hands work over at. A checkpoint is taken before each chunk is staged, so
 * {@link #rollbackTo} can drop a chunk whose extraction turned out to be garbage — and everything
 * staged after it — while keeping the chunks that came before. That is possible only because
 * {@link StagedGraph} is immutable: a checkpoint is a kept reference, not a copy or a log.</p>
 *
 * <p>Not thread-safe, and deliberately so. A transaction belongs to one partition run; sharing it
 * across threads would mean two chunks racing to define the same entity, which is exactly the
 * ambiguity staging is meant to remove.</p>
 */
public final class PartitionGraphTransaction {

    private static final Logger log = LoggerFactory.getLogger(PartitionGraphTransaction.class);

    /** Where a transaction is in its life. */
    public enum Status {
        /** Accepting staged output. */
        OPEN,
        /** Handed to a sink; nothing more can be staged. */
        COMMITTED,
        /** Given up on; whatever was staged was never written. */
        ABANDONED
    }

    private final PartitionKey key;
    private final Long factSheetId;
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private StagedGraph staged;
    private Status status = Status.OPEN;
    private String closedBecause;

    private PartitionGraphTransaction(PartitionKey key, Long factSheetId, StagedGraph staged) {
        this.key = key;
        this.factSheetId = factSheetId;
        this.staged = staged;
    }

    /** Opens a transaction for {@code key}, keyed on entity names. */
    public static PartitionGraphTransaction openOn(PartitionKey key, Long factSheetId) {
        return openOn(key, factSheetId, StagedKeys.byName());
    }

    /** Opens a transaction for {@code key} with a caller-chosen notion of entity identity. */
    public static PartitionGraphTransaction openOn(PartitionKey key, Long factSheetId,
                                                   StagedKeys keys) {
        Objects.requireNonNull(key, "a transaction needs a partition key");
        return new PartitionGraphTransaction(key, factSheetId, StagedGraph.using(keys));
    }

    /** A point the transaction can be rolled back to. */
    public record Checkpoint(int sequence, String label, StagedGraph snapshot) {

        public Checkpoint {
            label = label == null || label.isBlank() ? "checkpoint-" + sequence : label.trim();
        }

        public String describe() {
            return "#" + sequence + " " + label + " (" + snapshot.describe() + ")";
        }
    }

    public PartitionKey key() {
        return key;
    }

    public Long factSheetId() {
        return factSheetId;
    }

    public Status status() {
        return status;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** The merged picture as it stands. */
    public StagedGraph staged() {
        return staged;
    }

    /** Checkpoints taken so far, oldest first. */
    public List<Checkpoint> checkpoints() {
        return List.copyOf(checkpoints);
    }

    /** Why the transaction closed, or null while it is open. */
    public String closedBecause() {
        return closedBecause;
    }

    /** Captures the current state so it can be returned to. */
    public Checkpoint checkpoint(String label) {
        requireOpen("checkpoint");
        Checkpoint checkpoint = new Checkpoint(checkpoints.size(), label, staged);
        checkpoints.add(checkpoint);
        return checkpoint;
    }

    /**
     * Folds one chunk's extraction output into the staged graph.
     *
     * <p>A checkpoint labelled with the chunk is taken first, so this chunk's contribution can be
     * undone on its own.</p>
     *
     * @return the checkpoint taken immediately before staging
     */
    public Checkpoint stage(PartitionMember member, Graph produced) {
        Objects.requireNonNull(member, "staging needs the member whose chunk produced the graph");
        return stage(StagedProvenance.from(member), produced);
    }

    /** Folds one chunk's extraction output in under explicit provenance. */
    public Checkpoint stage(StagedProvenance from, Graph produced) {
        requireOpen("stage output");
        Objects.requireNonNull(from, "staging needs provenance");
        Checkpoint before = checkpoint(from.chunkId());
        staged = staged.stage(produced, from);
        return before;
    }

    /**
     * Discards everything staged at or after {@code checkpoint}.
     *
     * <p>Chunks staged before it are untouched, which is the whole point: one bad extraction
     * should not cost a partition the work it already did.</p>
     */
    public void rollbackTo(Checkpoint checkpoint) {
        requireOpen("roll back");
        Objects.requireNonNull(checkpoint, "rolling back needs a checkpoint");
        if (checkpoint.sequence() >= checkpoints.size()
                || !checkpoints.get(checkpoint.sequence()).equals(checkpoint)) {
            throw new IllegalArgumentException(
                    "checkpoint " + checkpoint.describe() + " does not belong to this transaction");
        }
        staged = checkpoint.snapshot();
        checkpoints.subList(checkpoint.sequence(), checkpoints.size()).clear();
    }

    /**
     * Hands the staged graph to {@code sink} and closes the transaction.
     *
     * <p>An empty staged graph still commits — a partition that read its chunks and found nothing
     * worth adding is a real outcome, and reporting it as a failed transaction would be a lie.</p>
     */
    public CommitReport commit(GraphCommitSink sink) {
        requireOpen("commit");
        Objects.requireNonNull(sink, "committing needs a sink");
        List<String> notes = new ArrayList<>();
        GraphCommitSink.CommitOutcome outcome;
        if (staged.isEmpty()) {
            notes.add("nothing was staged");
            outcome = GraphCommitSink.CommitOutcome.nothing();
        } else {
            Graph graph = staged.toGraph(key.id(), factSheetId);
            try {
                GraphCommitSink.CommitOutcome sunk = sink.commit(key, graph);
                outcome = sunk == null ? GraphCommitSink.CommitOutcome.nothing() : sunk;
            } catch (RuntimeException e) {
                // The transaction is closed as abandoned rather than committed: nothing here knows
                // how much of the graph the sink managed to write, and claiming a commit that may
                // not have happened is worse than saying it failed.
                status = Status.ABANDONED;
                closedBecause = "commit failed: " + e;
                log.warn("Commit of partition {} failed: {}", key.id(), e.toString());
                throw e;
            }
        }
        int dangling = staged.danglingRelationships().size();
        if (dangling > 0) {
            notes.add(dangling + " edge(s) referenced endpoints this partition never staged");
        }
        status = Status.COMMITTED;
        closedBecause = "committed";
        return new CommitReport(key, outcome, staged, staged.conflicts(), List.copyOf(notes));
    }

    /** Closes the transaction without writing anything. */
    public void abandon(String why) {
        requireOpen("abandon");
        status = Status.ABANDONED;
        closedBecause = why == null || why.isBlank() ? "abandoned" : why.trim();
        log.debug("Partition {} transaction abandoned: {}", key.id(), closedBecause);
    }

    private void requireOpen(String action) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("cannot " + action + " on a " + status
                    + " transaction for partition " + key.id()
                    + (closedBecause == null ? "" : " (" + closedBecause + ")"));
        }
    }

    /**
     * What a commit did, and what the caller should know about it.
     *
     * @param key       partition the commit was for
     * @param outcome   what the sink accepted
     * @param staged    the merged graph as committed
     * @param conflicts disagreements staging resolved on the way here
     * @param notes     caveats worth surfacing, e.g. edges with unstaged endpoints
     */
    public record CommitReport(
            PartitionKey key,
            GraphCommitSink.CommitOutcome outcome,
            StagedGraph staged,
            List<StagedConflict> conflicts,
            List<String> notes) {

        public CommitReport {
            outcome = outcome == null ? GraphCommitSink.CommitOutcome.nothing() : outcome;
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        /** True when the sink took everything and nothing had to be resolved on the way. */
        public boolean isClean() {
            return outcome.isClean() && conflicts.isEmpty() && notes.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(key.id()).append(": ")
                    .append(outcome.describe());
            if (!conflicts.isEmpty()) {
                sb.append(", ").append(conflicts.size()).append(" conflict(s)");
            }
            for (String note : notes) {
                sb.append(" | ").append(note);
            }
            return sb.toString();
        }
    }
}
