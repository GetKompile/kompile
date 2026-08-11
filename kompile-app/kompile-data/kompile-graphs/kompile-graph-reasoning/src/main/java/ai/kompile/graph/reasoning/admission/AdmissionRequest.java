/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, replayable input shared by the LLM and graph admission branches.
 *
 * <p>The graph must be a read-only snapshot from the caller's perspective. The generic reasoning
 * library cannot freeze a store-specific implementation, so adapters should materialize one snapshot
 * before constructing this request and attach its revision in {@link #snapshotId()}. The graph may
 * be {@code null} for LLM_ONLY requests, allowing existing callers to opt in without materializing a
 * snapshot first.</p>
 */
public record AdmissionRequest(
        String decisionGroupId,
        AdmissionCandidate candidate,
        List<AdmissionCandidate> ballot,
        String snapshotId,
        String policyVersion,
        ReasoningGraph graph) {

    public AdmissionRequest {
        decisionGroupId = requireText(decisionGroupId, "decisionGroupId");
        Objects.requireNonNull(candidate, "candidate");
        snapshotId = requireText(snapshotId, "snapshotId");
        policyVersion = policyVersion == null || policyVersion.isBlank()
                ? "unversioned"
                : policyVersion.trim();

        List<AdmissionCandidate> supplied = ballot == null
                ? List.of(candidate)
                : List.copyOf(ballot);
        if (supplied.stream().noneMatch(item -> candidate.candidateId().equals(item.candidateId()))) {
            throw new IllegalArgumentException("ballot must contain the selected candidate");
        }
        Map<String, String> canonicalById = new java.util.LinkedHashMap<>();
        List<AdmissionCandidate> deduplicated = new ArrayList<>(supplied.size());
        for (AdmissionCandidate item : supplied) {
            String priorCanonical = canonicalById.putIfAbsent(item.candidateId(), item.canonicalKey());
            if (priorCanonical != null && !priorCanonical.equals(item.canonicalKey())) {
                throw new IllegalArgumentException(
                        "ballot contains conflicting canonical keys for candidate " + item.candidateId());
            }
            if (deduplicated.stream().noneMatch(existing ->
                    existing.candidateId().equals(item.candidateId()))) {
                deduplicated.add(item);
            }
        }
        ballot = List.copyOf(deduplicated);
        graph = freeze(graph);
    }

    /**
     * Stable digest of the frozen ballot. This is the join key proving that both branches saw the
     * same ordered candidate set.
     */
    public String ballotFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AdmissionCandidate item : ballot) {
                digest.update(item.candidateId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(item.canonicalKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the runtime", e);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    /** Materialize the caller-owned graph once so replaying a request cannot observe later mutations. */
    private static ReasoningGraph freeze(ReasoningGraph source) {
        if (source == null) {
            return null;
        }
        MutableReasoningGraph copy = new MutableReasoningGraph();
        for (GraphEntity entity : source.entities()) {
            copy.addEntity(copyEntity(entity));
        }
        for (GraphRelation relation : source.relations()) {
            copy.addRelation(copyRelation(relation));
        }
        return new FrozenGraph(copy);
    }

    private static GraphEntity copyEntity(GraphEntity entity) {
        double[] embedding = entity.embedding() == null ? null : entity.embedding().clone();
        return new SimpleGraphEntity(entity.id(), entity.type(), entity.label(), entity.weight(),
                entity.confidence(), entity.tags(), embedding, entity.timestamp(), entity.attributes());
    }

    private static GraphRelation copyRelation(GraphRelation relation) {
        double[] embedding = relation.embedding() == null ? null : relation.embedding().clone();
        return new SimpleGraphRelation(relation.id(), relation.sourceId(), relation.targetId(), relation.type(),
                relation.weight(), relation.confidence(), relation.directed(), relation.tags(), embedding,
                relation.timestamp(), relation.attributes());
    }

    /** Read-only facade over a private, fully materialized graph projection. */
    private static final class FrozenGraph implements ReasoningGraph {
        private final MutableReasoningGraph delegate;

        private FrozenGraph(MutableReasoningGraph delegate) {
            this.delegate = delegate;
        }

        @Override
        public Collection<GraphEntity> entities() {
            return delegate.entities();
        }

        @Override
        public Collection<GraphRelation> relations() {
            return delegate.relations();
        }

        @Override
        public Optional<GraphEntity> entity(String id) {
            return delegate.entity(id);
        }

        @Override
        public List<GraphRelation> outgoing(String entityId) {
            return delegate.outgoing(entityId);
        }

        @Override
        public List<GraphRelation> incoming(String entityId) {
            return delegate.incoming(entityId);
        }

        @Override
        public List<GraphRelation> relationsOf(String entityId) {
            return delegate.relationsOf(entityId);
        }
    }
}
