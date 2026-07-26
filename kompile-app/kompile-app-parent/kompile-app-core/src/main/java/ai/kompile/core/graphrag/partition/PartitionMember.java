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

import java.util.Objects;

/**
 * One chunk's membership in a partition, with the reason it is there and where it is in its
 * lifecycle.
 *
 * <p>This record is the unit the manifest counts and the mini-batch planner schedules. It carries
 * the source version so a changed chunk can be invalidated without rediscovering the partition,
 * and the access scope so a chunk that becomes unreadable stops being schedulable.</p>
 *
 * @param chunkId         identifier of the chunk
 * @param documentId      source document, for document-at-a-time invalidation
 * @param channel         strongest channel that has proposed this chunk so far
 * @param state           lifecycle state
 * @param confidence      best confidence any channel has given it
 * @param reason          why it is a member; accumulates as channels agree
 * @param accessScope     domains that may read it
 * @param chunkVersion    source version fingerprint at the time it was admitted
 * @param orderKey        optional chronological key used for batch ordering
 * @param discoveredRound discovery round that first admitted it
 * @param processedRound  round in which it reached a terminal state, or {@code 0}
 * @param note            free-text outcome note, e.g. why it was deferred or invalidated
 */
public record PartitionMember(
        String chunkId,
        String documentId,
        DiscoveryChannel channel,
        MembershipState state,
        double confidence,
        String reason,
        AccessScope accessScope,
        String chunkVersion,
        String orderKey,
        int discoveredRound,
        int processedRound,
        String note) {

    public PartitionMember {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("a partition member needs a chunk id");
        }
        chunkId = chunkId.trim();
        channel = channel == null ? DiscoveryChannel.SEMANTIC : channel;
        state = state == null ? MembershipState.DISCOVERED : state;
        confidence = Double.isNaN(confidence) ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
        accessScope = accessScope == null ? AccessScope.unrestricted() : accessScope;
        discoveredRound = Math.max(0, discoveredRound);
        processedRound = Math.max(0, processedRound);
    }

    /** Admits a candidate into the given state at the given round. */
    public static PartitionMember admit(ChunkCandidate candidate, MembershipState state, int round) {
        return new PartitionMember(
                candidate.chunkId(),
                candidate.documentId(),
                candidate.channel(),
                state,
                candidate.confidence(),
                candidate.reason(),
                candidate.accessScope(),
                candidate.chunkVersion(),
                candidate.orderKey(),
                round,
                state.isAccountedFor() ? round : 0,
                null);
    }

    /** Returns a copy in a new state, recording the round it settled in and an optional note. */
    public PartitionMember withState(MembershipState newState, int round, String note) {
        MembershipState resolved = newState == null ? state : newState;
        return new PartitionMember(chunkId, documentId, channel, resolved, confidence, reason,
                accessScope, chunkVersion, orderKey, discoveredRound,
                resolved.isAccountedFor() ? Math.max(round, processedRound) : 0,
                note == null ? this.note : note);
    }

    /** Returns a copy marked invalidated because its source moved to {@code newVersion}. */
    public PartitionMember invalidated(String newVersion, String why) {
        return new PartitionMember(chunkId, documentId, channel, MembershipState.INVALIDATED,
                confidence, reason, accessScope, newVersion, orderKey, discoveredRound, 0,
                why == null ? "source version changed" : why);
    }

    /**
     * Folds a re-observation of the same chunk into this member.
     *
     * <p>The strongest channel and the highest confidence win, because a second channel agreeing
     * is evidence for membership and never against it. Lifecycle state follows
     * {@link MembershipState#merge}, so re-discovering an already-processed chunk does not
     * schedule it again. Reasons accumulate — "matched an alias; also cited in the same contract"
     * is a better audit trail than either alone.</p>
     */
    public PartitionMember mergeWith(ChunkCandidate candidate, MembershipState incomingState) {
        if (candidate == null) {
            return this;
        }
        DiscoveryChannel mergedChannel = DiscoveryChannel.strongest(channel, candidate.channel());
        MembershipState mergedState = MembershipState.merge(state, incomingState);
        double mergedConfidence = Math.max(confidence, candidate.confidence());
        String mergedReason = mergeReasons(reason, candidate.reason());
        AccessScope mergedScope = accessScope.union(candidate.accessScope());
        String version = candidate.chunkVersion() != null ? candidate.chunkVersion() : chunkVersion;
        String order = orderKey != null ? orderKey : candidate.orderKey();
        String document = documentId != null ? documentId : candidate.documentId();
        return new PartitionMember(chunkId, document, mergedChannel, mergedState, mergedConfidence,
                mergedReason, mergedScope, version, order, discoveredRound,
                mergedState.isAccountedFor() ? processedRound : 0, note);
    }

    /**
     * True when merging {@code candidate} would leave this member exactly as it is.
     *
     * <p>Channels that re-walk the same ground every round — a graph neighbourhood, for instance —
     * need this to tell a genuine re-discovery from a repeat. It matters because
     * {@link #mergeWith} accumulates reasons, so re-proposing a chunk with even a slightly
     * different wording counts as an upgrade, and a round that only upgrades never reads as
     * frontier-exhausted. Defined as the merge itself rather than as a mirror of its rules, so it
     * cannot drift from them.</p>
     */
    public boolean absorbs(ChunkCandidate candidate) {
        return candidate != null && this.equals(mergeWith(candidate, state));
    }

    /** True when a mini-batch may schedule this member. */
    public boolean isSchedulable() {
        return state.isSchedulable();
    }

    /** Short rendering used in manifests and logs. */
    public String describe() {
        return chunkId + " [" + channel + "/" + state + " " + String.format("%.2f", confidence) + "]";
    }

    private static String mergeReasons(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        if (Objects.equals(existing, incoming) || existing.contains(incoming)) {
            return existing;
        }
        return existing + "; " + incoming;
    }
}
