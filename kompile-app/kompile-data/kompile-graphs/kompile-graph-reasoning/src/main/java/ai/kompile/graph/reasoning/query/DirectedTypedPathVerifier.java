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
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministically proves an exact ordered path over directed, typed graph edges.
 *
 * <p>This is the truth boundary for small-model graph answers. A model may propose a witness or
 * render an explanation, but it cannot make a path reachable by asserting that it is. Verification
 * can run either against a {@link ReasoningGraph} or against the exact edge list serialized into a
 * compact prompt, which makes information-parity tests independent of hidden full-graph state.</p>
 */
public final class DirectedTypedPathVerifier {

    public static final int MAX_RELATION_STEPS = 64;

    private DirectedTypedPathVerifier() {
    }

    public enum Status {
        REACHABLE,
        UNREACHABLE,
        INVALID
    }

    /** Minimal edge contract suitable for compact JSON evidence. */
    public record Edge(String id, String sourceId, String targetId, String type) {

        public Edge {
            id = id == null ? "" : id;
            sourceId = sourceId == null ? "" : sourceId;
            targetId = targetId == null ? "" : targetId;
            type = type == null ? "" : type;
        }

        public static Edge from(GraphRelation relation) {
            Objects.requireNonNull(relation, "relation");
            return new Edge(relation.id(), relation.sourceId(), relation.targetId(), relation.type());
        }
    }

    /** Engine truth plus one deterministic witness when reachable. */
    public record Proof(
            Status status,
            List<String> entityPath,
            List<String> relationTypes,
            List<String> relationIds,
            String reason) {

        public Proof {
            status = status == null ? Status.INVALID : status;
            entityPath = entityPath == null ? List.of() : List.copyOf(entityPath);
            relationTypes = relationTypes == null ? List.of() : List.copyOf(relationTypes);
            relationIds = relationIds == null ? List.of() : List.copyOf(relationIds);
            reason = reason == null ? "" : reason;
        }

        public boolean reachable() {
            return status == Status.REACHABLE;
        }
    }

    /** Result of checking a model-proposed boolean and witness against engine truth. */
    public record AnswerValidation(boolean accepted, Proof truth, String reason) {

        public AnswerValidation {
            truth = truth == null
                    ? new Proof(Status.INVALID, List.of(), List.of(), List.of(), "missing proof")
                    : truth;
            reason = reason == null ? "" : reason;
        }
    }

    public static Proof verify(ReasoningGraph graph, String sourceId, String targetId,
                               List<String> relationTypes) {
        if (graph == null) {
            return invalid("graph is required");
        }
        if (blank(sourceId) || blank(targetId)) {
            return invalid("sourceId and targetId are required");
        }
        if (!graph.containsEntity(sourceId) || !graph.containsEntity(targetId)) {
            return invalid("sourceId and targetId must both exist in the supplied graph");
        }
        return verifyEdges(graph.relations().stream().map(Edge::from).toList(),
                sourceId, targetId, relationTypes);
    }

    public static Proof verifyEdges(Collection<Edge> edges, String sourceId, String targetId,
                                    List<String> relationTypes) {
        String inputError = inputError(sourceId, targetId, relationTypes);
        if (inputError != null) {
            return invalid(inputError);
        }
        List<String> expected = relationTypes.stream().map(DirectedTypedPathVerifier::canonicalType)
                .toList();
        if (expected.isEmpty()) {
            return sourceId.equals(targetId)
                    ? new Proof(Status.REACHABLE, List.of(sourceId), List.of(), List.of(),
                    "source and target are identical under an empty relation sequence")
                    : unreachable("an empty relation sequence cannot reach a different target");
        }

        List<Edge> orderedEdges = edges == null ? List.of() : edges.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Edge::id)
                        .thenComparing(Edge::sourceId)
                        .thenComparing(Edge::targetId)
                        .thenComparing(Edge::type))
                .toList();
        Map<String, Witness> frontier = new LinkedHashMap<>();
        frontier.put(sourceId, new Witness(List.of(sourceId), List.of(), List.of()));
        for (String expectedType : expected) {
            Map<String, Witness> next = new LinkedHashMap<>();
            for (Map.Entry<String, Witness> state : frontier.entrySet()) {
                for (Edge edge : orderedEdges) {
                    if (!state.getKey().equals(edge.sourceId())
                            || !expectedType.equals(canonicalType(edge.type()))) {
                        continue;
                    }
                    Witness prior = state.getValue();
                    List<String> entities = appended(prior.entityPath(), edge.targetId());
                    List<String> types = appended(prior.relationTypes(), edge.type());
                    List<String> ids = appended(prior.relationIds(), edge.id());
                    next.putIfAbsent(edge.targetId(), new Witness(entities, types, ids));
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                return unreachable("no directed edge matched the next requested relation type");
            }
        }
        Witness witness = frontier.get(targetId);
        return witness == null
                ? unreachable("the ordered relation sequence did not terminate at targetId")
                : new Proof(Status.REACHABLE, witness.entityPath(), witness.relationTypes(),
                witness.relationIds(), "every directed typed edge was found in order");
    }

    public static AnswerValidation validateAnswer(
            ReasoningGraph graph,
            String sourceId,
            String targetId,
            List<String> expectedRelationTypes,
            Boolean claimedReachable,
            List<String> claimedEntityPath,
            List<String> claimedRelationTypes) {
        if (graph == null) {
            Proof invalid = invalid("graph is required");
            return new AnswerValidation(false, invalid, invalid.reason());
        }
        if (blank(sourceId) || blank(targetId)
                || !graph.containsEntity(sourceId) || !graph.containsEntity(targetId)) {
            Proof invalid = verify(graph, sourceId, targetId, expectedRelationTypes);
            return new AnswerValidation(false, invalid, invalid.reason());
        }
        return validateEdgeAnswer(graph.relations().stream().map(Edge::from).toList(), sourceId,
                targetId, expectedRelationTypes, claimedReachable, claimedEntityPath,
                claimedRelationTypes);
    }

    public static AnswerValidation validateEdgeAnswer(
            Collection<Edge> edges,
            String sourceId,
            String targetId,
            List<String> expectedRelationTypes,
            Boolean claimedReachable,
            List<String> claimedEntityPath,
            List<String> claimedRelationTypes) {
        Proof truth = verifyEdges(edges, sourceId, targetId, expectedRelationTypes);
        if (truth.status() == Status.INVALID) {
            return new AnswerValidation(false, truth, truth.reason());
        }
        if (claimedReachable == null) {
            return new AnswerValidation(false, truth, "reachable must be a JSON boolean");
        }
        List<String> entities = claimedEntityPath == null ? List.of() : claimedEntityPath;
        List<String> types = claimedRelationTypes == null ? List.of() : claimedRelationTypes;
        if (!claimedReachable) {
            if (!entities.isEmpty() || !types.isEmpty()) {
                return new AnswerValidation(false, truth,
                        "reachable=false requires empty entityPath and relationTypes arrays");
            }
            return truth.status() == Status.UNREACHABLE
                    ? new AnswerValidation(true, truth,
                    "negative answer agrees with deterministic traversal")
                    : new AnswerValidation(false, truth,
                    "reachable=false contradicts an engine-proven path");
        }
        if (!truth.reachable()) {
            return new AnswerValidation(false, truth,
                    "reachable=true has no engine-proven path");
        }
        String witnessError = witnessError(edges, sourceId, targetId, expectedRelationTypes,
                entities, types);
        return witnessError == null
                ? new AnswerValidation(true, truth,
                "the proposed witness is a directed typed path in the supplied graph")
                : new AnswerValidation(false, truth, witnessError);
    }

    private static String witnessError(
            Collection<Edge> edges,
            String sourceId,
            String targetId,
            List<String> expectedRelationTypes,
            List<String> entityPath,
            List<String> relationTypes) {
        List<String> expected = expectedRelationTypes.stream()
                .map(DirectedTypedPathVerifier::canonicalType).toList();
        List<String> proposed = relationTypes.stream()
                .map(DirectedTypedPathVerifier::canonicalType).toList();
        if (!proposed.equals(expected)) {
            return "proposed relationTypes do not match the requested ordered sequence";
        }
        if (entityPath.size() != relationTypes.size() + 1 || entityPath.isEmpty()) {
            return "entityPath length must equal relationTypes length plus one";
        }
        if (!sourceId.equals(entityPath.get(0))
                || !targetId.equals(entityPath.get(entityPath.size() - 1))) {
            return "entityPath endpoints do not match sourceId and targetId";
        }
        List<Edge> available = edges == null ? List.of() : edges.stream()
                .filter(Objects::nonNull).toList();
        for (int index = 0; index < relationTypes.size(); index++) {
            String from = entityPath.get(index);
            String to = entityPath.get(index + 1);
            String type = canonicalType(relationTypes.get(index));
            boolean present = available.stream().anyMatch(edge -> from.equals(edge.sourceId())
                    && to.equals(edge.targetId()) && type.equals(canonicalType(edge.type())));
            if (!present) {
                return "proposed witness contains a missing, reversed, or wrongly typed edge at step "
                        + (index + 1);
            }
        }
        return null;
    }

    private static String inputError(String sourceId, String targetId, List<String> relationTypes) {
        if (blank(sourceId) || blank(targetId)) {
            return "sourceId and targetId are required";
        }
        if (relationTypes == null) {
            return "relationTypes is required";
        }
        if (relationTypes.size() > MAX_RELATION_STEPS) {
            return "relationTypes exceeds the maximum supported path length";
        }
        if (relationTypes.stream().anyMatch(DirectedTypedPathVerifier::blank)) {
            return "relationTypes cannot contain null or blank values";
        }
        return null;
    }

    private static Proof invalid(String reason) {
        return new Proof(Status.INVALID, List.of(), List.of(), List.of(), reason);
    }

    private static Proof unreachable(String reason) {
        return new Proof(Status.UNREACHABLE, List.of(), List.of(), List.of(), reason);
    }

    private static String canonicalType(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip().toUpperCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record Witness(
            List<String> entityPath,
            List<String> relationTypes,
            List<String> relationIds) {
    }
}
