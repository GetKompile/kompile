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

package ai.kompile.core.graphrag.partition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drives a partition from empty to closed: discover, order, process, expand, repeat.
 *
 * <p>The loop is deliberately round-based rather than one pass. Processing a chunk teaches the
 * graph something — a new alias, a new edge, a new claim — and that new knowledge is exactly what
 * lets the next round's channels find evidence they could not have found before. A single pass
 * over a fixed candidate list cannot do that; it can only find what was already findable.</p>
 *
 * <p><strong>Read-your-writes.</strong> The commit boundary is the mini-batch: each batch's
 * outcomes are written to the store before the next batch runs, and every batch of round N is
 * committed before round N+1 discovers. So a channel expanding the frontier always sees the
 * results of everything already processed, and never sees a half-finished batch.</p>
 */
public final class PartitionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PartitionLifecycle.class);

    private final PartitionStore store;
    private final PartitionDiscoveryCoordinator coordinator;
    private final Function<List<PartitionMember>, List<List<PartitionMember>>> packer;

    /** Uses simple sequential packing of {@code maxItemsPerBatch} members. */
    public PartitionLifecycle(PartitionStore store, PartitionDiscoveryCoordinator coordinator,
                              int maxItemsPerBatch) {
        this(store, coordinator,
                stratum -> MiniBatchPlanner.sequential(stratum, Math.max(1, maxItemsPerBatch)));
    }

    /**
     * Uses a caller-supplied packer inside each evidence stratum — the seam the crawl's
     * cost-balanced batch planner plugs into.
     */
    public PartitionLifecycle(PartitionStore store, PartitionDiscoveryCoordinator coordinator,
                              Function<List<PartitionMember>, List<List<PartitionMember>>> packer) {
        if (coordinator == null) {
            throw new IllegalArgumentException("a lifecycle needs a discovery coordinator");
        }
        this.store = store == null ? PartitionStore.inMemory() : store;
        this.coordinator = coordinator;
        this.packer = packer == null ? stratum -> MiniBatchPlanner.sequential(stratum, 16) : packer;
    }

    /**
     * Decides what happened to one chunk.
     *
     * <p>Implementations run the actual extraction. They return the state the member should end
     * in, which is how "I read it", "I could not read it" and "I chose not to read it yet" stay
     * distinguishable all the way into the manifest.</p>
     */
    @FunctionalInterface
    public interface ChunkProcessor {

        /**
         * @param member    the chunk to process
         * @param partition the partition as committed so far — safe to read
         * @return the outcome; {@code null} is treated as a deferral
         */
        ProcessOutcome process(PartitionMember member, EntityPartition partition);
    }

    /**
     * The result of processing one chunk.
     *
     * @param state final state for the member
     * @param note  short explanation, kept on the member and shown in audits
     */
    public record ProcessOutcome(MembershipState state, String note) {

        public ProcessOutcome {
            state = state == null ? MembershipState.DEFERRED : state;
        }

        public static ProcessOutcome processed() {
            return new ProcessOutcome(MembershipState.PROCESSED, null);
        }

        public static ProcessOutcome processed(String note) {
            return new ProcessOutcome(MembershipState.PROCESSED, note);
        }

        public static ProcessOutcome deferred(String why) {
            return new ProcessOutcome(MembershipState.DEFERRED, why);
        }

        public static ProcessOutcome inaccessible(String why) {
            return new ProcessOutcome(MembershipState.INACCESSIBLE, why);
        }
    }

    /** Opens (or reloads) the partition for {@code key} without running anything. */
    public EntityPartition open(PartitionKey key) {
        EntityPartition partition = store.loadOrOpen(key);
        store.save(partition);
        return partition;
    }

    /**
     * Runs the partition to closure, or until the policy's round budget is spent.
     *
     * @param key       partition identity; its policy version should match the coordinator's
     * @param processor runs extraction for one chunk
     */
    public Result run(PartitionKey key, ChunkProcessor processor) {
        if (key == null) {
            throw new IllegalArgumentException("cannot run a partition without a key");
        }
        if (processor == null) {
            throw new IllegalArgumentException("cannot run a partition without a chunk processor");
        }
        DiscoveryPolicy policy = coordinator.policy();
        EntityPartition partition = store.loadOrOpen(key);
        Map<DiscoveryChannel, String> channelFailures = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int roundsRun = 0;
        int processed = 0;
        int failed = 0;
        boolean frontierExhausted = false;

        for (int round = 1; round <= policy.maxRounds(); round++) {
            roundsRun = round;
            PartitionDiscoveryCoordinator.Outcome discovery = coordinator.discover(partition, round);
            partition = discovery.partition();
            channelFailures.putAll(discovery.failures());
            frontierExhausted = discovery.frontierExhausted();
            store.save(partition);
            log.debug("Partition {} round {}: {}", partition.id(), round, discovery.describe());

            List<MiniBatchPlanner.MiniBatch> plan = MiniBatchPlanner.plan(partition, packer);
            if (plan.isEmpty() && frontierExhausted) {
                break;
            }

            partition = partition.withPhase(PartitionPhase.PROCESSING);
            for (MiniBatchPlanner.MiniBatch batch : plan) {
                // Mark the whole batch scheduled first, so a crash between here and the commit
                // leaves the partition saying "in flight" rather than "never seen".
                for (PartitionMember member : batch.members()) {
                    partition = partition.withState(member.chunkId(), MembershipState.SCHEDULED,
                            null);
                }
                store.save(partition);

                for (PartitionMember member : batch.members()) {
                    PartitionMember current = partition.member(member.chunkId()).orElse(member);
                    ProcessOutcome outcome;
                    try {
                        outcome = processor.process(current, partition);
                    } catch (RuntimeException e) {
                        // A chunk that blew up is still outstanding, not resolved. Deferring it
                        // keeps it eligible for a later round instead of silently losing it.
                        failed++;
                        outcome = ProcessOutcome.deferred("processing failed: " + e);
                        log.warn("Processing chunk {} in partition {} failed: {}",
                                member.chunkId(), partition.id(), e.toString());
                    }
                    if (outcome == null) {
                        outcome = ProcessOutcome.deferred("processor returned no outcome");
                    }
                    if (outcome.state() == MembershipState.PROCESSED) {
                        processed++;
                    }
                    partition = partition.withState(member.chunkId(), outcome.state(),
                            outcome.note());
                }
                // Commit boundary: everything this batch learned is visible to the next batch and
                // to the next round's channels.
                store.save(partition);
            }

            if (frontierExhausted && !partition.hasOutstandingWork()) {
                break;
            }
            partition = partition.withPhase(PartitionPhase.EXPANDING);
        }

        EvidenceManifest manifest = partition.manifest(frontierExhausted);
        PartitionPhase finalPhase;
        if (manifest.isProvisionallyComplete() && channelFailures.isEmpty()) {
            finalPhase = PartitionPhase.CLOSED;
        } else if (frontierExhausted) {
            finalPhase = PartitionPhase.RECONCILING;
        } else {
            finalPhase = PartitionPhase.PROCESSING;
            notes.add("round budget of " + policy.maxRounds()
                    + " was spent before the frontier stopped producing candidates");
        }
        if (!channelFailures.isEmpty()) {
            notes.add("discovery channels failed: " + channelFailures.keySet());
        }
        if (failed > 0) {
            notes.add(failed + " chunk(s) failed processing and were deferred");
        }
        partition = partition.withPhase(finalPhase);
        store.save(partition);

        return new Result(partition, partition.manifest(frontierExhausted), roundsRun, processed,
                failed, frontierExhausted, Map.copyOf(channelFailures), List.copyOf(notes));
    }

    /**
     * What a full run did.
     *
     * @param partition         final partition, as saved
     * @param manifest          coverage and gap ledger at the end of the run
     * @param rounds            discovery rounds actually run
     * @param processed         chunks that reached {@link MembershipState#PROCESSED}
     * @param failed            chunks whose processing threw and were deferred
     * @param frontierExhausted whether the last round proposed nothing new
     * @param channelFailures   channels that failed at least once, with the last error
     * @param notes             human-readable caveats about this run's completeness
     */
    public record Result(
            EntityPartition partition,
            EvidenceManifest manifest,
            int rounds,
            int processed,
            int failed,
            boolean frontierExhausted,
            Map<DiscoveryChannel, String> channelFailures,
            List<String> notes) {

        /**
         * True when the manifest says the subject is covered <em>and</em> every channel the
         * policy names actually ran.
         *
         * <p>The second half is not redundant. Completeness here is relative to a policy, and a
         * policy is partly a list of ways to look; if the semantic index was down for the whole
         * run, the manifest can still show every admitted chunk processed while an entire way of
         * finding evidence never happened. Reporting that as complete is the exact failure this
         * design exists to prevent, so the run-level answer is the conjunction. The manifest's own
         * {@link EvidenceManifest#isProvisionallyComplete()} stays a pure statement about the
         * chunks it knows of.</p>
         */
        public boolean isProvisionallyComplete() {
            return manifest.isProvisionallyComplete() && channelFailures.isEmpty();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(manifest.describe())
                    .append(" rounds=").append(rounds);
            if (failed > 0) {
                sb.append(" failed=").append(failed);
            }
            for (String note : notes) {
                sb.append(" | ").append(note);
            }
            return sb.toString();
        }
    }
}
