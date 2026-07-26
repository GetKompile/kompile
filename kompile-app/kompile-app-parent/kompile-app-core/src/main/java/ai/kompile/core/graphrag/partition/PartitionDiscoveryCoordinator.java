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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every discovery channel a policy enables and folds their proposals into one auditable
 * membership set.
 *
 * <p>The channels already existed separately. What this adds is that they run as a set, in
 * evidence-priority order, under one set of thresholds, with the reason each chunk was admitted
 * written down next to it — so the frontier is a thing that can be inspected rather than an
 * emergent property of whichever retriever happened to be called.</p>
 *
 * <p>A channel that throws does not fail the round. Discovery is best-effort by nature: an index
 * being offline should narrow what was found and say so, not destroy the evidence the other
 * channels did find. The failure is counted and surfaced in {@link Outcome#failures()} so the
 * shortfall is visible rather than silent.</p>
 */
public final class PartitionDiscoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PartitionDiscoveryCoordinator.class);

    private final List<DiscoveryChannelProvider> providers;
    private final DiscoveryPolicy policy;

    public PartitionDiscoveryCoordinator(List<DiscoveryChannelProvider> providers,
                                         DiscoveryPolicy policy) {
        this.policy = policy == null ? DiscoveryPolicy.defaults() : policy;
        List<DiscoveryChannelProvider> ordered = new ArrayList<>();
        if (providers != null) {
            for (DiscoveryChannelProvider provider : providers) {
                if (provider != null && provider.channel() != null
                        && this.policy.runs(provider.channel())) {
                    ordered.add(provider);
                }
            }
        }
        // Authoritative channels first, so the priors are built before weaker or conflicting
        // evidence is folded in. Ties keep declaration order.
        ordered.sort(Comparator.comparingInt(p -> p.channel().priority()));
        this.providers = List.copyOf(ordered);
    }

    public DiscoveryPolicy policy() {
        return policy;
    }

    /** Channels that will actually run, in the order they run. */
    public List<DiscoveryChannel> activeChannels() {
        return providers.stream().map(DiscoveryChannelProvider::channel).toList();
    }

    /**
     * Runs one discovery round against {@code partition} and returns the updated partition
     * together with what changed.
     *
     * @param partition partition to expand; its members are the frontier channels expand from
     * @param round     1-based round number, recorded on newly admitted members
     */
    public Outcome discover(EntityPartition partition, int round) {
        if (partition == null) {
            throw new IllegalArgumentException("cannot discover into a null partition");
        }
        EntityPartition working = partition.withPhase(PartitionPhase.DISCOVERING);
        Map<DiscoveryChannel, Integer> proposals = new EnumMap<>(DiscoveryChannel.class);
        Map<DiscoveryChannel, String> failures = new LinkedHashMap<>();
        int added = 0;
        int upgraded = 0;
        int inaccessible = 0;
        int excluded = 0;
        int overCap = 0;
        // Tracked incrementally rather than recounted per candidate: the partition is immutable,
        // so a rescan per proposal would make a round quadratic in its own size.
        int admitted = admittedCount(working);

        for (DiscoveryChannelProvider provider : providers) {
            DiscoveryChannel channel = provider.channel();
            List<ChunkCandidate> candidates;
            try {
                candidates = provider.discover(working, round, policy.maxCandidatesPerChannel());
            } catch (RuntimeException e) {
                failures.put(channel, e.toString());
                log.warn("Discovery channel {} failed for partition {}: {}", channel,
                        partition.id(), e.toString());
                continue;
            }
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            if (candidates.size() > policy.maxCandidatesPerChannel()) {
                // Truncation is a real narrowing of what was looked at, so it is said out loud
                // rather than left for someone to infer from a suspiciously round number.
                log.info("Discovery channel {} returned {} candidates for partition {}; policy {} "
                                + "caps at {} — the remainder was not considered this round",
                        channel, candidates.size(), partition.id(), policy.version(),
                        policy.maxCandidatesPerChannel());
                candidates = candidates.subList(0, policy.maxCandidatesPerChannel());
            }

            for (ChunkCandidate candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                proposals.merge(channel, 1, Integer::sum);
                PartitionMember before = working.member(candidate.chunkId()).orElse(null);
                boolean known = before != null;

                MembershipState state = policy.classify(candidate.confidence());
                if (state == MembershipState.EXCLUDED) {
                    excluded++;
                } else if (!candidate.accessScope().isReadableBy(policy.readerScope())) {
                    // Recorded, not dropped: a chunk we may not read is a known gap in the
                    // evidence, and an answer built without it should be able to say so.
                    state = MembershipState.INACCESSIBLE;
                    inaccessible++;
                } else if (!known && admitted >= policy.maxMembers()) {
                    state = MembershipState.DEFERRED;
                    overCap++;
                }

                EntityPartition next = working.admit(candidate, state, round);
                PartitionMember after = next.member(candidate.chunkId()).orElse(null);
                if (!known) {
                    added++;
                } else if (!java.util.Objects.equals(before, after)) {
                    upgraded++;
                }
                if (wasExcluded(before) && !wasExcluded(after)) {
                    admitted++;
                }
                working = next;
            }
        }

        if (overCap > 0) {
            log.info("Partition {} hit the policy member cap of {}; {} candidate(s) deferred to a "
                    + "later run rather than dropped", partition.id(), policy.maxMembers(), overCap);
        }

        boolean frontierExhausted = added == 0 && upgraded == 0;
        return new Outcome(working.withRound(round), added, upgraded, excluded, inaccessible,
                overCap, frontierExhausted, Map.copyOf(proposals), Map.copyOf(failures));
    }

    /** A chunk that is absent or rejected does not consume the policy's member budget. */
    private static boolean wasExcluded(PartitionMember member) {
        return member == null || member.state() == MembershipState.EXCLUDED;
    }

    private static int admittedCount(EntityPartition partition) {
        int count = 0;
        for (PartitionMember member : partition.memberList()) {
            if (member.state() != MembershipState.EXCLUDED) {
                count++;
            }
        }
        return count;
    }

    /**
     * What one discovery round did.
     *
     * @param partition         updated partition
     * @param added             chunks admitted for the first time
     * @param upgraded          known chunks whose channel, confidence, reason or state changed
     * @param excluded          proposals rejected as below the policy's confidence floor
     * @param inaccessible      proposals the run's access scope does not permit reading
     * @param deferredOverCap   proposals postponed because the member cap was reached
     * @param frontierExhausted true when nothing was added or upgraded — the stop signal
     * @param proposalsByChannel how many proposals each channel made
     * @param failures          channels that threw, with the error, so the shortfall is visible
     */
    public record Outcome(
            EntityPartition partition,
            int added,
            int upgraded,
            int excluded,
            int inaccessible,
            int deferredOverCap,
            boolean frontierExhausted,
            Map<DiscoveryChannel, Integer> proposalsByChannel,
            Map<DiscoveryChannel, String> failures) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        public int totalProposals() {
            return proposalsByChannel.values().stream().mapToInt(Integer::intValue).sum();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("added=").append(added)
                    .append(" upgraded=").append(upgraded)
                    .append(" proposals=").append(totalProposals());
            if (excluded > 0) {
                sb.append(" excluded=").append(excluded);
            }
            if (inaccessible > 0) {
                sb.append(" inaccessible=").append(inaccessible);
            }
            if (deferredOverCap > 0) {
                sb.append(" overCap=").append(deferredOverCap);
            }
            if (!failures.isEmpty()) {
                sb.append(" channelFailures=").append(failures.keySet());
            }
            return sb.toString();
        }
    }
}
