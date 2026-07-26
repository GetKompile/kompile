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
import ai.kompile.core.graphrag.passes.EntityCandidateProvider;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Offers entities already accumulated in the in-run crawl graph as resolution candidates for a
 * mention.
 *
 * <p>This is the engine half of the identity split: recall is ours, precision is the model's. We
 * retrieve everything plausibly the same entity — using the same normalization and edit-distance
 * scoring the crawler's own entity resolution uses, so a candidate we offer is one the merge step
 * would also have considered — and the model only chooses among what it is handed, or says the
 * mention is new. It can never mint an id we have not seen.</p>
 */
public final class GraphEntityCandidateProvider implements EntityCandidateProvider {

    /** Below this the two names are not the same entity by any reading; offering them is noise. */
    static final double FLOOR = 0.35;

    /** A candidate whose type contradicts the mention's expected type is demoted, not hidden. */
    static final double TYPE_MISMATCH_PENALTY = 0.6;

    private final Graph graph;
    private final double minScore;

    public GraphEntityCandidateProvider(Graph graph, double minScore) {
        this.graph = graph;
        this.minScore = minScore <= 0 ? FLOOR : minScore;
    }

    @Override
    public List<EntityCandidate> candidatesFor(String mentionText, String typeHint,
                                               PassContext context, int limit) {
        if (mentionText == null || mentionText.isBlank() || limit <= 0) {
            return List.of();
        }
        List<Entity> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return List.of();
        }

        String probe = EntityResolutionService.normalize(mentionText);
        if (probe.isEmpty()) {
            return List.of();
        }
        String wantedType = normalizeType(typeHint);

        List<EntityCandidate> scored = new ArrayList<>();
        for (Entity entity : snapshot) {
            if (entity == null || entity.getId() == null || entity.getId().isBlank()) {
                continue;
            }
            double score = bestScore(probe, entity);
            if (score <= 0) {
                continue;
            }
            String entityType = normalizeType(entity.getType());
            if (wantedType != null && entityType != null && !wantedType.equals(entityType)) {
                score *= TYPE_MISMATCH_PENALTY;
            }
            if (score < minScore) {
                continue;
            }
            scored.add(new EntityCandidate(
                    entity.getId(),
                    entity.getTitle() != null ? entity.getTitle() : entity.getId(),
                    entity.getType(),
                    entity.getAliases() != null ? entity.getAliases() : List.of(),
                    round(score),
                    provenance()));
        }

        scored.sort(Comparator.comparingDouble(EntityCandidate::score).reversed()
                .thenComparing(candidate -> candidate.name() == null ? "" : candidate.name()));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    /**
     * Copies the entity list under the graph monitor. The crawl merges chunk results into this same
     * graph from worker threads while extraction runs, so the read has to take the lock the merge
     * takes.
     */
    private List<Entity> snapshot() {
        if (graph == null) {
            return List.of();
        }
        synchronized (graph) {
            List<Entity> entities = graph.getEntities();
            return entities == null || entities.isEmpty() ? List.of() : new ArrayList<>(entities);
        }
    }

    /** Best match over the entity's title and all its aliases. */
    private static double bestScore(String probe, Entity entity) {
        double best = similarity(probe, entity.getTitle());
        if (entity.getAliases() != null) {
            for (String alias : entity.getAliases()) {
                // An alias match is real but slightly weaker evidence than the canonical name.
                best = Math.max(best, similarity(probe, alias) * 0.98);
            }
        }
        return best;
    }

    private static double similarity(String probe, String candidate) {
        String normalized = EntityResolutionService.normalize(candidate);
        if (normalized.isEmpty()) {
            return 0;
        }
        if (normalized.equals(probe)) {
            return 1.0;
        }
        // "Acme" inside "Acme Corporation" is a routine short-form, which edit distance scores
        // badly because it is dominated by the length difference.
        if (normalized.contains(probe) || probe.contains(normalized)) {
            int shorter = Math.min(normalized.length(), probe.length());
            int longer = Math.max(normalized.length(), probe.length());
            if (shorter >= 3) {
                return Math.max(0.75 + 0.2 * ((double) shorter / longer),
                        EntityResolutionService.levenshteinSimilarity(probe, normalized));
            }
        }
        return EntityResolutionService.levenshteinSimilarity(probe, normalized);
    }

    private String provenance() {
        String graphId = graph != null && graph.getId() != null ? graph.getId() : "in-run";
        return "crawl-graph:" + graphId;
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Math.round(Math.min(1.0, value) * 1000.0) / 1000.0;
    }
}
