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

/**
 * Lifecycle state of one chunk's membership in an entity partition.
 *
 * <p>The point of naming these states is that "we have not processed this chunk" is not one
 * fact but four different ones, and they call for different responses: schedule it, wait for
 * budget, ask for permission, or re-run it because its source moved. Collapsing them into a
 * boolean is what makes coverage claims dishonest.</p>
 */
public enum MembershipState {

    /** A channel found it and policy admitted it; it is waiting to be scheduled. */
    DISCOVERED(30),

    /** Placed into a mini-batch for this round; processing has not committed yet. */
    SCHEDULED(40),

    /** Extraction ran and its delta was committed. This is the only state that is coverage. */
    PROCESSED(50),

    /**
     * Admitted but deliberately postponed — below the promotion threshold, over the member cap,
     * or out of budget for this round. Still outstanding: a later round may pick it up.
     */
    DEFERRED(20),

    /**
     * Known to exist but unreadable: the requester's access scope does not permit it, or the
     * source refused. Accounted for, never covered, and not retried under this policy.
     */
    INACCESSIBLE(60),

    /**
     * Was processed, but its source version changed or a premise it supported was retracted.
     * Outstanding again — this is the hook selective invalidation drives.
     */
    INVALIDATED(70),

    /** Evaluated and rejected by policy as not worth processing. Accounted for, not retried. */
    EXCLUDED(10);

    private final int rank;

    MembershipState(int rank) {
        this.rank = rank;
    }

    /**
     * Merge precedence when the same chunk is re-observed. Higher wins, so a chunk that is
     * already processed is not knocked back to discovered by a second channel finding it.
     */
    public int rank() {
        return rank;
    }

    /** True when the partition still owes work on this member. */
    public boolean isOutstanding() {
        return this == DISCOVERED || this == SCHEDULED || this == DEFERRED || this == INVALIDATED;
    }

    /** True only for {@link #PROCESSED} — the single state that may be counted as coverage. */
    public boolean isCovered() {
        return this == PROCESSED;
    }

    /**
     * True when the member needs no further decision: it was processed, or it was resolved as
     * unreadable or out of scope. Everything else is still an open question.
     */
    public boolean isAccountedFor() {
        return isCovered() || this == INACCESSIBLE || this == EXCLUDED;
    }

    /** True when a mini-batch may schedule this member. */
    public boolean isSchedulable() {
        return this == DISCOVERED || this == INVALIDATED;
    }

    /**
     * Resolves the state of a member observed twice.
     *
     * <p>Invalidation always wins: a chunk whose source moved must be reprocessed no matter what
     * else is known about it. Inaccessibility wins next, because a chunk we may not read cannot
     * be scheduled — except over {@link #PROCESSED}, since revoking access later does not undo
     * an extraction that already committed. Otherwise the higher {@link #rank()} wins.</p>
     */
    public static MembershipState merge(MembershipState existing, MembershipState incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        if (existing == INVALIDATED || incoming == INVALIDATED) {
            return INVALIDATED;
        }
        if (existing == PROCESSED || incoming == PROCESSED) {
            return PROCESSED;
        }
        if (existing == INACCESSIBLE || incoming == INACCESSIBLE) {
            return INACCESSIBLE;
        }
        return existing.rank() >= incoming.rank() ? existing : incoming;
    }
}
