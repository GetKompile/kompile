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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The durable record of which chunks are evidence about a subject, why, and how far along each is.
 *
 * <p>This is the piece that turns partitioning from a scheduling trick into a control plane. A
 * cost batch is a slice of work that exists for the length of a run; a partition is a claim about
 * a subject that survives the run, can be re-opened when a source changes, and can say what it
 * has <em>not</em> looked at. The distinction matters because completeness is a property of the
 * latter and is meaningless for the former.</p>
 *
 * <p>Instances are immutable. Every transition returns a new partition, so a partition can be
 * snapshotted, compared against an earlier round, and shared across the threads of a crawl
 * without locking.</p>
 *
 * @param key     identity, including the policy version and snapshot it is relative to
 * @param phase   lifecycle phase
 * @param members chunk id to membership, in admission order
 * @param round   most recent discovery round
 * @param pins    additional pinned versions (schema, ontology, model, ...) for reproducibility
 */
public record EntityPartition(
        PartitionKey key,
        PartitionPhase phase,
        Map<String, PartitionMember> members,
        int round,
        Map<String, String> pins) {

    public EntityPartition {
        if (key == null) {
            throw new IllegalArgumentException("a partition needs a key");
        }
        phase = phase == null ? PartitionPhase.NEW : phase;
        members = members == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(members));
        round = Math.max(0, round);
        pins = pins == null ? Map.of() : Map.copyOf(pins);
    }

    /** An empty partition for {@code key}, before any channel has run. */
    public static EntityPartition open(PartitionKey key) {
        return new EntityPartition(key, PartitionPhase.NEW, Map.of(), 0, Map.of());
    }

    public String id() {
        return key.id();
    }

    public EntityPartition withPhase(PartitionPhase newPhase) {
        return new EntityPartition(key, newPhase, members, round, pins);
    }

    public EntityPartition withRound(int newRound) {
        return new EntityPartition(key, phase, members, newRound, pins);
    }

    /** Returns a copy carrying an additional named version pin. */
    public EntityPartition withPin(String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(pins);
        merged.put(name, value);
        return new EntityPartition(key, phase, members, round, merged);
    }

    /**
     * Admits or folds in a candidate.
     *
     * <p>A chunk already present is merged rather than replaced — see
     * {@link PartitionMember#mergeWith}. That is what stops a weak semantic hit from overwriting
     * an identifier match, and what stops re-discovery from rescheduling work already committed.</p>
     */
    public EntityPartition admit(ChunkCandidate candidate, MembershipState state, int atRound) {
        if (candidate == null) {
            return this;
        }
        Map<String, PartitionMember> updated = new LinkedHashMap<>(members);
        PartitionMember existing = updated.get(candidate.chunkId());
        updated.put(candidate.chunkId(), existing == null
                ? PartitionMember.admit(candidate, state, atRound)
                : existing.mergeWith(candidate, state));
        return new EntityPartition(key, phase, updated, round, pins);
    }

    /** Replaces a member outright; used when a processor reports an outcome. */
    public EntityPartition withMember(PartitionMember member) {
        if (member == null) {
            return this;
        }
        Map<String, PartitionMember> updated = new LinkedHashMap<>(members);
        updated.put(member.chunkId(), member);
        return new EntityPartition(key, phase, updated, round, pins);
    }

    /**
     * Moves a member to a new state. Unknown chunk ids are ignored rather than creating a
     * member with no discovery reason — membership must always have a recorded cause.
     */
    public EntityPartition withState(String chunkId, MembershipState state, String note) {
        PartitionMember member = members.get(chunkId);
        if (member == null) {
            return this;
        }
        return withMember(member.withState(state, round, note));
    }

    /**
     * Marks every member drawn from {@code documentId} invalidated, so the next round reprocesses
     * them. This is the entry point for selective invalidation: a changed source invalidates the
     * chunks it produced, not the whole partition.
     */
    public EntityPartition invalidateDocument(String documentId, String newVersion, String why) {
        if (documentId == null || documentId.isBlank()) {
            return this;
        }
        Map<String, PartitionMember> updated = new LinkedHashMap<>(members);
        boolean changed = false;
        for (Map.Entry<String, PartitionMember> entry : updated.entrySet()) {
            PartitionMember member = entry.getValue();
            if (documentId.equals(member.documentId())
                    && member.state() != MembershipState.INVALIDATED) {
                entry.setValue(member.invalidated(newVersion, why));
                changed = true;
            }
        }
        return changed ? new EntityPartition(key, phase, updated, round, pins) : this;
    }

    /** Marks a single chunk invalidated because its source version moved. */
    public EntityPartition invalidateChunk(String chunkId, String newVersion, String why) {
        PartitionMember member = members.get(chunkId);
        if (member == null) {
            return this;
        }
        return withMember(member.invalidated(newVersion, why));
    }

    public Optional<PartitionMember> member(String chunkId) {
        return Optional.ofNullable(members.get(chunkId));
    }

    public Collection<PartitionMember> memberList() {
        return members.values();
    }

    /** Members in a given state, in admission order. */
    public List<PartitionMember> inState(MembershipState state) {
        List<PartitionMember> matched = new ArrayList<>();
        for (PartitionMember member : members.values()) {
            if (member.state() == state) {
                matched.add(member);
            }
        }
        return matched;
    }

    /** Members a mini-batch may schedule right now. */
    public List<PartitionMember> schedulable() {
        List<PartitionMember> matched = new ArrayList<>();
        for (PartitionMember member : members.values()) {
            if (member.isSchedulable()) {
                matched.add(member);
            }
        }
        return matched;
    }

    /** True when work remains: something is discovered, scheduled, deferred or invalidated. */
    public boolean hasOutstandingWork() {
        for (PartitionMember member : members.values()) {
            if (member.state().isOutstanding()) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return members.size();
    }

    /**
     * Derives the evidence manifest for this partition.
     *
     * @param frontierExhausted whether the last discovery round produced anything new; the
     *                          completion predicate is not meaningful without it
     */
    public EvidenceManifest manifest(boolean frontierExhausted) {
        return EvidenceManifest.of(this, frontierExhausted);
    }

    /**
     * Whether discovery had run out as of the last time this partition was written.
     *
     * <p>Deliberately derived rather than stored. {@code frontierExhausted} is a property of the
     * last discovery round, and the lifecycle already spends it: a partition only reaches
     * {@link PartitionPhase#RECONCILING} or {@link PartitionPhase#CLOSED} once a round proposed
     * nothing new, and it settles on an earlier phase otherwise. A second field carrying the same
     * fact is a field that can disagree with the phase, and a coverage claim that contradicts
     * itself is worse than one that has to be inferred.</p>
     */
    public boolean frontierExhausted() {
        return phase == PartitionPhase.RECONCILING || phase == PartitionPhase.CLOSED;
    }

    /**
     * The manifest for this partition as it stands, taking {@link #frontierExhausted()} from the
     * phase. This is the form a reader gets: whoever loads a stored partition was not there for
     * the discovery round and has nothing else to derive the flag from.
     */
    public EvidenceManifest manifest() {
        return manifest(frontierExhausted());
    }

    @Override
    public String toString() {
        return "EntityPartition[" + key.id() + " phase=" + phase + " members=" + members.size()
                + " round=" + round + "]";
    }
}
