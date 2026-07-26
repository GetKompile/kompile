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

package ai.kompile.crawl.graph.passes;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.passes.ClaimCandidateProvider;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;
import ai.kompile.core.graphrag.passes.PassContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offers claims the in-run crawl graph already holds for an endpoint pair, so a new piece of
 * evidence can be attached to an existing claim rather than silently duplicating it.
 *
 * <p>Two families are retrieved:</p>
 * <ul>
 *   <li><b>Same endpoints</b> — any relation already recorded between the two entities, in either
 *       direction. This is the "is this the same claim again?" case.</li>
 *   <li><b>Same subject and predicate, different object</b> — the competing claim. Without it the
 *       model has nothing to contradict and every rival assertion looks new; with it a functional
 *       predicate's second value can be flagged.</li>
 * </ul>
 *
 * <p>Flagging is all the model may do. Which of two competing claims survives is a truth-maintenance
 * decision made downstream against the whole graph, not something a chunk-local reader can settle.</p>
 */
public final class GraphClaimCandidateProvider implements ClaimCandidateProvider {

    /** Metadata keys the crawl already stamps for evidence tallies, when present. */
    static final String META_SUPPORTING = "supportingCount";
    static final String META_REFUTING = "refutingCount";

    private final Graph graph;

    public GraphClaimCandidateProvider(Graph graph) {
        this.graph = graph;
    }

    /** Stable key for a claim: {@code TYPE(subjectId,objectId)}. */
    public static String atomKey(String type, String source, String target) {
        return normalizeType(type) + "(" + safe(source) + "," + safe(target) + ")";
    }

    @Override
    public List<ClaimCandidate> candidatesFor(String subjectEntityId, String predicate,
                                              String objectEntityId, PassContext context,
                                              int limit) {
        if (subjectEntityId == null || subjectEntityId.isBlank() || limit <= 0) {
            return List.of();
        }
        Snapshot snapshot = snapshot();
        if (snapshot.relationships().isEmpty()) {
            return List.of();
        }
        String predicateType = predicate == null || predicate.isBlank() ? null : normalizeType(predicate);

        List<ClaimCandidate> sameEndpoints = new ArrayList<>();
        List<ClaimCandidate> competing = new ArrayList<>();
        for (Relationship relationship : snapshot.relationships()) {
            if (relationship == null || relationship.getSource() == null
                    || relationship.getTarget() == null) {
                continue;
            }
            boolean forward = subjectEntityId.equals(relationship.getSource())
                    && objectEntityId != null && objectEntityId.equals(relationship.getTarget());
            boolean reverse = subjectEntityId.equals(relationship.getTarget())
                    && objectEntityId != null && objectEntityId.equals(relationship.getSource());
            if (forward || reverse) {
                sameEndpoints.add(toCandidate(relationship, snapshot, reverse ? "reversed" : "same pair"));
                continue;
            }
            boolean rival = predicateType != null
                    && subjectEntityId.equals(relationship.getSource())
                    && predicateType.equals(normalizeType(relationship.getType()))
                    && !relationship.getTarget().equals(objectEntityId);
            if (rival) {
                competing.add(toCandidate(relationship, snapshot, "same subject and predicate"));
            }
        }

        List<ClaimCandidate> ordered = new ArrayList<>(sameEndpoints);
        ordered.addAll(competing);
        return ordered.size() > limit
                ? List.copyOf(ordered.subList(0, limit))
                : List.copyOf(ordered);
    }

    private ClaimCandidate toCandidate(Relationship relationship, Snapshot snapshot, String note) {
        String type = normalizeType(relationship.getType());
        String subjectName = snapshot.nameOf(relationship.getSource());
        String objectName = snapshot.nameOf(relationship.getTarget());
        StringBuilder summary = new StringBuilder()
                .append(subjectName).append(' ').append(type).append(' ').append(objectName);
        if (relationship.getOccurredAt() != null && !relationship.getOccurredAt().isBlank()) {
            summary.append(" (").append(relationship.getOccurredAt()).append(')');
        }
        summary.append(" [").append(note).append(']');

        double confidence = relationship.getConfidence() != null ? relationship.getConfidence() : 0.5;
        return new ClaimCandidate(
                atomKey(type, relationship.getSource(), relationship.getTarget()),
                relationship.getSource(),
                type,
                relationship.getTarget(),
                confidence,
                intMeta(relationship, META_SUPPORTING, 1),
                intMeta(relationship, META_REFUTING, 0),
                summary.toString());
    }

    /**
     * Copies entities and relationships under the graph monitor — the crawl merges chunk results
     * into this same graph from worker threads while extraction runs.
     */
    private Snapshot snapshot() {
        if (graph == null) {
            return new Snapshot(List.of(), Map.of());
        }
        synchronized (graph) {
            List<Relationship> relationships = graph.getRelationships();
            if (relationships == null || relationships.isEmpty()) {
                return new Snapshot(List.of(), Map.of());
            }
            Map<String, String> names = new HashMap<>();
            if (graph.getEntities() != null) {
                for (Entity entity : graph.getEntities()) {
                    if (entity != null && entity.getId() != null) {
                        names.put(entity.getId(),
                                entity.getTitle() != null ? entity.getTitle() : entity.getId());
                    }
                }
            }
            return new Snapshot(new ArrayList<>(relationships), names);
        }
    }

    private static int intMeta(Relationship relationship, String key, int fallback) {
        Map<String, Object> metadata = relationship.getMetadata();
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank() ? "RELATED_TO" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Snapshot(List<Relationship> relationships, Map<String, String> names) {

        String nameOf(String id) {
            String name = names.get(id);
            return name != null ? name : (id == null ? "?" : id);
        }
    }
}
