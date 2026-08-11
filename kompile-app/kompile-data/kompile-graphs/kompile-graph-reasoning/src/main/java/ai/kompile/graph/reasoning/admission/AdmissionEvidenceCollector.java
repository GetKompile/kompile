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
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds a deterministic, bounded two-hop semantic trace around one matched entity. */
final class AdmissionEvidenceCollector {

    static final int MAX_DEPTH = 2;
    static final int MAX_RELATION_EVIDENCE = 16;

    private final AdmissionPredicateSemantics semantics;

    AdmissionEvidenceCollector(AdmissionPredicateSemantics semantics) {
        this.semantics = Objects.requireNonNull(semantics, "semantics");
    }

    AdmissionEvidenceTrace collect(ReasoningGraph graph, String focusEntityId) {
        Objects.requireNonNull(graph, "graph");
        GraphEntity focus = graph.entity(focusEntityId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "focus entity is absent from graph: " + focusEntityId));

        List<AdmissionEvidence> discovered = new ArrayList<>();
        ArrayDeque<PathState> queue = new ArrayDeque<>();
        queue.add(new PathState(
                focusEntityId,
                List.of(focusEntityId),
                List.of(),
                List.of(),
                1.0,
                0,
                label(focus)));

        while (!queue.isEmpty()) {
            PathState state = queue.removeFirst();
            if (state.depth() >= MAX_DEPTH) {
                continue;
            }
            List<GraphRelation> incident = graph.relations().stream()
                    .filter(relation -> state.entityId().equals(relation.sourceId())
                            || state.entityId().equals(relation.targetId()))
                    .sorted(Comparator.comparing(GraphRelation::id)
                            .thenComparing(GraphRelation::sourceId)
                            .thenComparing(GraphRelation::targetId)
                            .thenComparing(GraphRelation::type))
                    .toList();

            for (GraphRelation relation : incident) {
                if (state.relationPath().contains(relation.id())) {
                    continue;
                }
                String otherId = state.entityId().equals(relation.sourceId())
                        ? relation.targetId()
                        : relation.sourceId();
                if (state.entityPath().contains(otherId)) {
                    continue;
                }
                GraphEntity source = graph.entity(relation.sourceId()).orElse(null);
                GraphEntity target = graph.entity(relation.targetId()).orElse(null);
                GraphEntity other = graph.entity(otherId).orElse(null);
                if (source == null || target == null || other == null) {
                    continue;
                }

                AdmissionPredicateSemantics.Assessment assessment =
                        semantics.assess(relation, source, target, state.entityId());
                if (state.depth() > 0
                        && (assessment.kind() == AdmissionEvidence.Kind.CONFLICT
                        || assessment.kind() == AdmissionEvidence.Kind.IDENTITY_CONSTRAINT)) {
                    continue;
                }
                List<String> entityPath = append(state.entityPath(), otherId);
                List<String> relationPath = append(state.relationPath(), relation.id());
                List<String> predicatePath = append(state.predicatePath(), normalizedType(relation));
                double strength = clamp(state.strength()
                        * clamp(relation.weight() * relation.confidence()));
                String pathSummary = state.pathSummary()
                        + arrow(relation, state.entityId())
                        + label(other)
                        + " (" + assessment.summary() + ")";
                discovered.add(new AdmissionEvidence(
                        assessment.kind(),
                        assessment.ruleId(),
                        entityPath,
                        relationPath,
                        predicatePath,
                        strength,
                        pathSummary));

                if (state.depth() + 1 < MAX_DEPTH && traversable(assessment.kind())) {
                    queue.addLast(new PathState(
                            otherId,
                            entityPath,
                            relationPath,
                            predicatePath,
                            strength,
                            state.depth() + 1,
                            state.pathSummary() + arrow(relation, state.entityId()) + label(other)));
                }
            }
        }

        List<AdmissionEvidence> unique = deduplicate(discovered);
        unique.sort(evidenceComparator());
        boolean truncated = unique.size() > MAX_RELATION_EVIDENCE;
        List<AdmissionEvidence> selected = truncated
                ? List.copyOf(unique.subList(0, MAX_RELATION_EVIDENCE))
                : List.copyOf(unique);
        return new AdmissionEvidenceTrace(selected, discovered.size(), truncated);
    }

    private static boolean traversable(AdmissionEvidence.Kind kind) {
        return kind == AdmissionEvidence.Kind.CONTEXT_RELATION
                || kind == AdmissionEvidence.Kind.OVERRIDE;
    }

    private static List<AdmissionEvidence> deduplicate(List<AdmissionEvidence> evidence) {
        Map<String, AdmissionEvidence> strongest = new LinkedHashMap<>();
        for (AdmissionEvidence item : evidence) {
            String key = item.kind() + "\u0000" + item.ruleId() + "\u0000"
                    + String.join("\u0000", item.entityPath()) + "\u0000"
                    + String.join("\u0000", item.predicatePath());
            AdmissionEvidence prior = strongest.get(key);
            if (prior == null
                    || item.strength() > prior.strength()
                    || (Double.compare(item.strength(), prior.strength()) == 0
                    && relationPathKey(item).compareTo(relationPathKey(prior)) < 0)) {
                strongest.put(key, item);
            }
        }
        return new ArrayList<>(strongest.values());
    }

    private static String relationPathKey(AdmissionEvidence evidence) {
        return String.join("\u0000", evidence.relationPath());
    }

    private static Comparator<AdmissionEvidence> evidenceComparator() {
        return Comparator.comparingInt((AdmissionEvidence item) -> priority(item.kind()))
                .thenComparing(Comparator.comparingDouble(AdmissionEvidence::strength).reversed())
                .thenComparingInt(item -> item.relationPath().size())
                .thenComparing(AdmissionEvidence::ruleId)
                .thenComparing(item -> String.join("\u0000", item.entityPath()))
                .thenComparing(item -> String.join("\u0000", item.relationPath()));
    }

    private static int priority(AdmissionEvidence.Kind kind) {
        return switch (kind) {
            case CONFLICT -> 0;
            case CAUTION -> 1;
            case OVERRIDE -> 2;
            case REQUIREMENT -> 3;
            case AFFIRMING_RELATION -> 4;
            case IDENTITY_CONSTRAINT -> 5;
            case CONTEXT_RELATION -> 6;
            default -> 7;
        };
    }

    private static String arrow(GraphRelation relation, String fromId) {
        if (!relation.directed()) {
            return " --" + normalizedType(relation) + "-- ";
        }
        return fromId.equals(relation.sourceId())
                ? " --" + normalizedType(relation) + "--> "
                : " <--" + normalizedType(relation) + "-- ";
    }

    private static String normalizedType(GraphRelation relation) {
        String type = relation.type() == null ? "" : relation.type().trim();
        return type.isEmpty() ? "RELATED" : type;
    }

    private static String label(GraphEntity entity) {
        return entity.label() == null || entity.label().isBlank()
                ? entity.id()
                : entity.label().trim();
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> copy = new ArrayList<>(values.size() + 1);
        copy.addAll(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record PathState(
            String entityId,
            List<String> entityPath,
            List<String> relationPath,
            List<String> predicatePath,
            double strength,
            int depth,
            String pathSummary) {
    }
}
