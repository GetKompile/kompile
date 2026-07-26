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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a partition has and has not looked at, stated so it can be checked.
 *
 * <p>A claim dossier answers "how strong is the evidence for X?". This answers the prior
 * question — "is the evidence for X all the evidence there is?" — and it answers it honestly,
 * because it distinguishes chunks nobody processed yet from chunks deliberately postponed from
 * chunks we are not allowed to read from chunks whose source moved underneath us. A single
 * "processed 41 of 50" figure hides all four.</p>
 *
 * <p>Derived from an {@link EntityPartition}; never stored separately, so it cannot drift from
 * the membership it summarises.</p>
 *
 * @param key               partition identity, including its policy version and snapshot
 * @param round             discovery round the manifest was taken at
 * @param byState           member count per lifecycle state
 * @param byChannel         member count per discovery channel
 * @param deferred          chunk ids admitted but postponed
 * @param inaccessible      chunk ids known to exist but unreadable under this policy's scope
 * @param invalidated       chunk ids whose source moved and which must be reprocessed
 * @param outstanding       every chunk id the partition still owes work on
 * @param frontierExhausted whether the last discovery round proposed nothing new
 */
public record EvidenceManifest(
        PartitionKey key,
        int round,
        Map<MembershipState, Integer> byState,
        Map<DiscoveryChannel, Integer> byChannel,
        List<String> deferred,
        List<String> inaccessible,
        List<String> invalidated,
        List<String> outstanding,
        boolean frontierExhausted) {

    public EvidenceManifest {
        byState = byState == null ? Map.of() : Map.copyOf(byState);
        byChannel = byChannel == null ? Map.of() : Map.copyOf(byChannel);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
        inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
        invalidated = invalidated == null ? List.of() : List.copyOf(invalidated);
        outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }

    static EvidenceManifest of(EntityPartition partition, boolean frontierExhausted) {
        Map<MembershipState, Integer> states = new EnumMap<>(MembershipState.class);
        Map<DiscoveryChannel, Integer> channels = new EnumMap<>(DiscoveryChannel.class);
        List<String> deferred = new ArrayList<>();
        List<String> inaccessible = new ArrayList<>();
        List<String> invalidated = new ArrayList<>();
        List<String> outstanding = new ArrayList<>();

        for (PartitionMember member : partition.memberList()) {
            states.merge(member.state(), 1, Integer::sum);
            channels.merge(member.channel(), 1, Integer::sum);
            switch (member.state()) {
                case DEFERRED -> deferred.add(member.chunkId());
                case INACCESSIBLE -> inaccessible.add(member.chunkId());
                case INVALIDATED -> invalidated.add(member.chunkId());
                default -> { }
            }
            if (member.state().isOutstanding()) {
                outstanding.add(member.chunkId());
            }
        }
        return new EvidenceManifest(partition.key(), partition.round(), states, channels, deferred,
                inaccessible, invalidated, outstanding, frontierExhausted);
    }

    public int total() {
        return byState.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int count(MembershipState state) {
        return byState.getOrDefault(state, 0);
    }

    public int count(DiscoveryChannel channel) {
        return byChannel.getOrDefault(channel, 0);
    }

    /** Members actually processed — the only ones that contributed evidence. */
    public int covered() {
        return count(MembershipState.PROCESSED);
    }

    /** Members that need no further decision: processed, unreadable, or rejected by policy. */
    public int accountedFor() {
        return covered() + count(MembershipState.INACCESSIBLE) + count(MembershipState.EXCLUDED);
    }

    /**
     * Proposals the policy judged to be evidence about the subject — everything discovered except
     * those rejected outright.
     */
    public int admitted() {
        return total() - count(MembershipState.EXCLUDED);
    }

    /**
     * Processed share of admitted evidence, in [0,1].
     *
     * <p>Rejected proposals are out of the denominator because "this chunk is not about Acme" and
     * "this chunk is about Acme and we did not read it" are different failures, and averaging them
     * hides the second. The guard against a policy that rejects everything and then reports full
     * coverage is that {@link #count(MembershipState)} for {@link MembershipState#EXCLUDED} is
     * reported alongside it and appears in {@link #describe()} — the rejection is stated, not
     * netted out.</p>
     */
    public double coverage() {
        int admitted = admitted();
        return admitted == 0 ? 0.0 : (double) covered() / admitted;
    }

    /**
     * True when nothing is outstanding, the frontier stopped producing candidates, and at least
     * one chunk was actually processed.
     *
     * <p>"Provisionally" is load-bearing: it holds only against this policy version and this
     * snapshot. New sources, a wider policy, or a later snapshot can all re-open it, and none of
     * those would make this answer wrong at the time it was given.</p>
     */
    public boolean isProvisionallyComplete() {
        return frontierExhausted && outstanding.isEmpty() && covered() > 0;
    }

    /**
     * True when the partition believes it is done but has unread material — complete only in the
     * sense that it gave up. Worth surfacing separately: an answer built from this is missing
     * evidence that exists and is known to exist.
     */
    public boolean isCompleteWithGaps() {
        return isProvisionallyComplete() && !inaccessible.isEmpty();
    }

    /** One-line rendering for logs and telemetry. */
    public String describe() {
        StringBuilder sb = new StringBuilder(key.id())
                .append(" round=").append(round)
                .append(" covered=").append(covered()).append('/').append(admitted())
                .append(String.format(" (%.0f%%)", coverage() * 100));
        int excluded = count(MembershipState.EXCLUDED);
        if (excluded > 0) {
            sb.append(" excluded=").append(excluded);
        }
        if (!outstanding.isEmpty()) {
            sb.append(" outstanding=").append(outstanding.size());
        }
        if (!deferred.isEmpty()) {
            sb.append(" deferred=").append(deferred.size());
        }
        if (!inaccessible.isEmpty()) {
            sb.append(" inaccessible=").append(inaccessible.size());
        }
        if (!invalidated.isEmpty()) {
            sb.append(" invalidated=").append(invalidated.size());
        }
        sb.append(isProvisionallyComplete() ? " [provisionally complete]" : " [open]");
        return sb.toString();
    }
}
