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
import ai.kompile.core.graphrag.passes.EntityCandidateProvider;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;
import ai.kompile.core.graphrag.passes.ExtractionCandidates.IdentitySignals;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.core.evaluation.graph.GraphDecisionTraceEvent;
import ai.kompile.core.evaluation.graph.GraphDecisionTraceSink;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.core.evaluation.graph.GraphMissStage;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    /** Keep graph reasoning useful to a small model without turning the ballot into a graph dump. */
    static final int MAX_NEIGHBORHOOD_EDGES = 4;
    static final int MAX_NEIGHBORHOOD_CHARS = 320;
    static final int MAX_FACET_ATOM_CHARS = 48;

    private static final Set<String> DISTINCT_RELATION_TYPES = Set.of(
            "DISTINCT_FROM", "MUST_NOT_MERGE", "NOT_SAME_AS", "SEPARATE_IDENTITY");
    private static final Set<String> DISTINCT_METADATA_KEYS = Set.of(
            "distinctfrom", "mustnotmergewith", "forbiddenmergeids", "separatefrom",
            "notsameas");
    private static final Set<String> IDENTIFIER_METADATA_KEYS = Set.of(
            "id", "externalid", "sourceid", "identifier", "identifiers", "email", "emails",
            "emailaddress", "phone", "phonenumber", "url", "uri", "username", "handle",
            "accountid", "customerid", "employeeid", "vendorid", "gtin", "sku", "isin",
            "cusip", "ticker");

    private final Graph graph;
    private final double minScore;
    private final GraphDecisionTraceSink traceSink;

    public GraphEntityCandidateProvider(Graph graph, double minScore) {
        this(graph, minScore, GraphDecisionTraceSink.noop());
    }

    public GraphEntityCandidateProvider(Graph graph, double minScore,
                                        GraphDecisionTraceSink traceSink) {
        this.graph = graph;
        this.minScore = minScore <= 0 ? FLOOR : minScore;
        this.traceSink = traceSink == null ? GraphDecisionTraceSink.noop() : traceSink;
    }

    @Override
    public List<EntityCandidate> candidatesFor(String mentionText, String typeHint,
                                               PassContext context, int limit) {
        if (mentionText == null || mentionText.isBlank() || limit <= 0) {
            return List.of();
        }
        Snapshot snapshot = snapshot();
        if (snapshot.entities().isEmpty()) {
            return List.of();
        }

        String probe = EntityResolutionService.normalize(mentionText);
        if (probe.isEmpty()) {
            return List.of();
        }
        String wantedType = normalizeType(typeHint);

        List<CandidateSeed> seeds = new ArrayList<>();
        for (Entity entity : snapshot.entities()) {
            if (entity == null || entity.getId() == null || entity.getId().isBlank()) {
                continue;
            }
            IdentityMatch match = identityMatch(mentionText, probe, entity, wantedType);
            double score = bestScore(probe, entity);
            if (match.identifierExact()) {
                score = Math.max(score, 1.0);
            }
            if (score <= 0) {
                continue;
            }
            if (!match.typeCompatible()) {
                score *= TYPE_MISMATCH_PENALTY;
            }
            if (score < minScore) {
                trace(context, mentionText, entity.getId(), score,
                        GraphMissReason.CANDIDATE_SCORE_BELOW_THRESHOLD, "rejected_score",
                        Map.of("threshold", String.valueOf(minScore)));
                continue;
            }
            seeds.add(new CandidateSeed(entity, round(score), match));
        }

        String anchorId = identityAnchor(seeds);
        List<EntityCandidate> scored = new ArrayList<>();
        for (CandidateSeed seed : seeds) {
            Entity entity = seed.entity();
            Set<String> distinctPeers = distinctPeers(entity.getId(), snapshot);
            boolean forbidden = anchorId != null && !anchorId.equals(entity.getId())
                    && distinctPeers.contains(anchorId);
            String constraintReason = constraintReason(entity.getId(), distinctPeers, anchorId,
                    snapshot);
            IdentityMatch match = seed.match();
            IdentitySignals signals = new IdentitySignals(
                    match.canonicalExact(), match.aliasExact(), match.identifierExact(),
                    match.typeCompatible(), match.sharedTokensOnly(), forbidden,
                    match.matchedIdentifier(), constraintReason);
            // A graph constraint relative to an unambiguous source identifier is a hard purity
            // rule. The forbidden entity is not a legal ballot option at all.
            if (forbidden) {
                trace(context, mentionText, entity.getId(), seed.score(),
                        GraphMissReason.MENTION_IDENTITY_UNRESOLVED, "rejected_forbidden_merge",
                        Map.of("purityConstraint", "true", "constraint",
                                constraintReason == null ? "forbidden merge" : constraintReason));
                continue;
            }
            scored.add(new EntityCandidate(
                    entity.getId(),
                    entity.getTitle() != null ? entity.getTitle() : entity.getId(),
                    entity.getType(),
                    entity.getAliases() != null ? entity.getAliases() : List.of(),
                    seed.score(),
                    provenance(),
                    identityContext(entity, snapshot, constraintReason),
                    signals));
            trace(context, mentionText, entity.getId(), seed.score(), null, "admitted",
                    Map.of("threshold", String.valueOf(minScore),
                            "forbidden", "false", "typeCompatible",
                            String.valueOf(match.typeCompatible())));
        }

        scored.sort(Comparator.comparingDouble(EntityCandidate::score).reversed()
                .thenComparing(candidate -> candidate.name() == null ? "" : candidate.name()));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    private void trace(PassContext context, String atom, String candidateId, double score,
                       GraphMissReason reason, String disposition, Map<String, String> metadata) {
        traceSink.trace(new GraphDecisionTraceEvent(
                UUID.randomUUID().toString(), null,
                context == null ? null : context.documentId(),
                context == null ? null : context.chunkId(),
                context == null ? null : context.partitionId(), atom,
                reason == null ? GraphMissStage.GRAPH_ADMISSION : reason.stage(), reason,
                disposition, List.of(candidateId), Map.of(candidateId, score), metadata));
    }

    /**
     * Copies entities and relationships under the graph monitor. The crawl merges chunk results
     * into this same graph from worker threads while extraction runs, so both the candidate and its
     * identity neighborhood must come from one coherent snapshot.
     */
    private Snapshot snapshot() {
        if (graph == null) {
            return Snapshot.empty();
        }
        synchronized (graph) {
            List<Entity> entities = graph.getEntities();
            if (entities == null || entities.isEmpty()) {
                return Snapshot.empty();
            }
            List<Entity> entityCopy = new ArrayList<>(entities);
            List<Relationship> relationshipCopy = graph.getRelationships() == null
                    ? List.of() : new ArrayList<>(graph.getRelationships());
            Map<String, String> names = new HashMap<>();
            for (Entity entity : entityCopy) {
                if (entity != null && entity.getId() != null) {
                    names.put(entity.getId(), entity.getTitle() == null
                            ? entity.getId() : entity.getTitle());
                }
            }
            return new Snapshot(entityCopy, relationshipCopy, names);
        }
    }

    /** Candidate-scoped identity state, with stable ids and graph constraints before event edges. */
    private static String identityContext(Entity entity, Snapshot snapshot, String constraintReason) {
        if (entity == null || entity.getId() == null) {
            return null;
        }
        String entityId = entity.getId();
        StringBuilder rendered = new StringBuilder();
        List<String> identifiers = stableIdentifiers(entity);
        if (!identifiers.isEmpty()) {
            appendBounded(rendered, "stableIdentifiers="
                    + String.join(", ", identifiers.stream().limit(3).toList()));
        }
        appendBounded(rendered, constraintReason);
        if (snapshot.relationships().isEmpty()) {
            return rendered.isEmpty() ? null : rendered.toString();
        }
        List<Relationship> touching = snapshot.relationships().stream()
                .filter(Objects::nonNull)
                .filter(relation -> entityId.equals(relation.getSource())
                        || entityId.equals(relation.getTarget()))
                .sorted(Comparator
                        .comparing((Relationship relation) -> !isDistinctRelation(relation))
                        .thenComparing(relation -> !hasDecisionFacet(relation))
                        .thenComparing(GraphEntityCandidateProvider::relationshipKey))
                .limit(MAX_NEIGHBORHOOD_EDGES)
                .toList();
        for (Relationship relation : touching) {
            boolean outgoing = entityId.equals(relation.getSource());
            String neighborId = outgoing ? relation.getTarget() : relation.getSource();
            String neighbor = snapshot.names().getOrDefault(neighborId,
                    neighborId == null ? "?" : neighborId);
            String edge = (outgoing ? "OUT " : "IN ")
                    + Objects.toString(relation.getType(), "RELATED_TO")
                    + (outgoing ? " -> " : " <- ") + neighbor;
            String facet = relationshipDecisionFacet(relation);
            appendBounded(rendered, facet == null ? edge : edge + " [" + facet + "]");
        }
        return rendered.isEmpty() ? null : rendered.toString();
    }

    /**
     * Bounded, typed evidence attached to one graph edge. Rule programs and vectors stay engine-side;
     * the small model sees only provenance/version/strength and soft embedding scores.
     */
    private static String relationshipDecisionFacet(Relationship relationship) {
        if (relationship == null || relationship.getMetadata() == null
                || relationship.getMetadata().isEmpty()) {
            return null;
        }
        List<String> facets = new ArrayList<>();
        String provenanceType = facetAtom(metadataValue(relationship, "provenanceType"));
        boolean inferred = "INFERRED".equalsIgnoreCase(provenanceType);
        if (inferred) {
            facets.add("logic=INFERRED");
            addFacet(facets, "rules", facetList(metadataValue(relationship, "supportingRuleIds"), 2));
            addFacet(facets, "logicVersion", facetAtom(metadataValue(relationship, "inferenceVersion")));
            addFacet(facets, "run", facetAtom(metadataValue(relationship, "inferenceRunId")));
            addFacet(facets, "strength", facetAtom(metadataValue(relationship, "strengthBand")));
            if (relationship.getConfidence() != null) {
                addFacet(facets, "confidence", String.format(Locale.ROOT, "%.3f",
                        relationship.getConfidence()));
            }
        }
        String embedding = facetAtom(metadataValue(relationship, "kgEmbeddingAlgorithm"));
        String embeddingVersion = facetAtom(metadataValue(relationship, "kgEmbeddingVersion"));
        if (embedding != null) {
            facets.add("embedding=" + embedding
                    + (embeddingVersion == null ? "" : "@" + embeddingVersion) + "(soft)");
        }
        Object similarity = metadataValue(relationship, "similarityScore");
        if (similarity != null) {
            addFacet(facets, "similarity", facetAtom(similarity) + "(soft)");
        }
        Object graphScore = metadataValue(relationship,
                "gnnScore", "linkPredictionScore", "graphPredictionScore");
        if (graphScore != null) {
            addFacet(facets, "graphScore", facetAtom(graphScore) + "(soft)");
        }
        return facets.isEmpty() ? null : String.join(", ", facets);
    }

    private static boolean hasDecisionFacet(Relationship relationship) {
        return relationshipDecisionFacet(relationship) != null;
    }

    private static Object metadataValue(Relationship relationship, String... keys) {
        if (relationship == null || relationship.getMetadata() == null || keys == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : relationship.getMetadata().entrySet()) {
            String actual = normalizeKey(entry.getKey());
            for (String key : keys) {
                if (actual.equals(normalizeKey(key))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static void addFacet(List<String> facets, String name, String value) {
        if (value != null && !value.isBlank()) {
            facets.add(name + "=" + value);
        }
    }

    private static String facetList(Object value, int limit) {
        LinkedHashSet<String> atoms = new LinkedHashSet<>();
        addStringValues(atoms, value);
        String joined = atoms.stream().limit(Math.max(1, limit))
                .map(GraphEntityCandidateProvider::facetAtom)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "|" + right)
                .orElse(null);
        return joined == null || joined.isBlank() ? null : joined;
    }

    private static String facetAtom(Object value) {
        if (value == null) {
            return null;
        }
        String atom;
        if (value instanceof Number number) {
            atom = number instanceof Float || number instanceof Double
                    ? String.format(Locale.ROOT, "%.3f", number.doubleValue())
                    : String.valueOf(number);
        } else {
            atom = String.valueOf(value).strip()
                    .replaceAll("[^\\p{L}\\p{N}._:/@+-]+", "_");
        }
        if (atom.isBlank()) {
            return null;
        }
        return atom.length() <= MAX_FACET_ATOM_CHARS
                ? atom : atom.substring(0, MAX_FACET_ATOM_CHARS);
    }

    private static void appendBounded(StringBuilder rendered, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        int separator = rendered.isEmpty() ? 0 : 2;
        if (rendered.length() + separator + value.length() > MAX_NEIGHBORHOOD_CHARS) {
            return;
        }
        if (!rendered.isEmpty()) {
            rendered.append("; ");
        }
        rendered.append(value);
    }

    private static IdentityMatch identityMatch(String mentionText, String probe, Entity entity,
                                               String wantedType) {
        String canonical = EntityResolutionService.normalize(entity.getTitle());
        boolean canonicalExact = !canonical.isEmpty() && canonical.equals(probe);
        boolean aliasExact = entity.getAliases() != null && entity.getAliases().stream()
                .filter(Objects::nonNull)
                .map(EntityResolutionService::normalize)
                .anyMatch(probe::equals);
        String matchedIdentifier = stableIdentifiers(entity).stream()
                .filter(identifier -> identifierAppearsIn(mentionText, identifier))
                .findFirst().orElse(null);
        boolean identifierExact = matchedIdentifier != null;
        String entityType = normalizeType(entity.getType());
        boolean typeCompatible = wantedType == null || entityType == null || wantedType.equals(entityType);
        boolean sharedTokensOnly = !canonicalExact && !aliasExact && !identifierExact
                && sharesMeaningfulToken(probe, entity);
        return new IdentityMatch(canonicalExact, aliasExact, identifierExact, typeCompatible,
                sharedTokensOnly, matchedIdentifier);
    }

    private static String identityAnchor(List<CandidateSeed> seeds) {
        List<CandidateSeed> identifierMatches = seeds.stream()
                .filter(seed -> seed.match().identifierExact() && seed.match().typeCompatible())
                .toList();
        if (identifierMatches.size() == 1) {
            return identifierMatches.get(0).entity().getId();
        }
        List<CandidateSeed> exactMatches = seeds.stream()
                .filter(seed -> seed.match().typeCompatible()
                        && (seed.match().canonicalExact() || seed.match().aliasExact()))
                .toList();
        return exactMatches.size() == 1 ? exactMatches.get(0).entity().getId() : null;
    }

    private static Set<String> distinctPeers(String entityId, Snapshot snapshot) {
        if (entityId == null) {
            return Set.of();
        }
        Set<String> peers = new LinkedHashSet<>();
        for (Relationship relationship : snapshot.relationships()) {
            if (relationship == null || !isDistinctRelation(relationship)) {
                continue;
            }
            if (entityId.equals(relationship.getSource()) && relationship.getTarget() != null) {
                peers.add(relationship.getTarget());
            } else if (entityId.equals(relationship.getTarget()) && relationship.getSource() != null) {
                peers.add(relationship.getSource());
            }
        }
        Entity entity = snapshot.entities().stream()
                .filter(value -> value != null && entityId.equals(value.getId()))
                .findFirst().orElse(null);
        if (entity != null && entity.getMetadata() != null) {
            entity.getMetadata().forEach((key, value) -> {
                if (DISTINCT_METADATA_KEYS.contains(normalizeKey(key))) {
                    addStringValues(peers, value);
                }
            });
        }
        return Set.copyOf(peers);
    }

    private static String constraintReason(String entityId, Set<String> peers, String anchorId,
                                           Snapshot snapshot) {
        if (peers == null || peers.isEmpty()) {
            return null;
        }
        if (anchorId != null && !anchorId.equals(entityId) && peers.contains(anchorId)) {
            return "MUST_NOT_MERGE with engine identity anchor "
                    + snapshot.names().getOrDefault(anchorId, anchorId);
        }
        String names = peers.stream().sorted()
                .limit(3)
                .map(id -> snapshot.names().getOrDefault(id, id))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return names.isBlank() ? null : "graph marks this identity distinct from " + names;
    }

    private static boolean isDistinctRelation(Relationship relationship) {
        String type = normalizeType(relationship == null ? null : relationship.getType());
        return type != null && DISTINCT_RELATION_TYPES.contains(type);
    }

    private static List<String> stableIdentifiers(Entity entity) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        if (entity.getMetadata() != null) {
            entity.getMetadata().forEach((key, value) -> {
                if (IDENTIFIER_METADATA_KEYS.contains(normalizeKey(key))) {
                    addStringValues(identifiers, value);
                }
            });
        }
        if (entity.getAliases() != null) {
            entity.getAliases().stream()
                    .filter(GraphEntityCandidateProvider::looksLikeStableIdentifier)
                    .forEach(identifiers::add);
        }
        return List.copyOf(identifiers);
    }

    private static void addStringValues(Collection<String> sink, Object value) {
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().strip();
            if (!text.isEmpty()) {
                sink.add(text);
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                addStringValues(sink, item);
            }
        }
    }

    private static boolean looksLikeStableIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.strip();
        return text.contains("@") || text.contains("://")
                || (text.length() >= 4 && text.chars().anyMatch(Character::isDigit)
                && text.chars().noneMatch(Character::isWhitespace));
    }

    private static boolean identifierAppearsIn(String mentionText, String identifier) {
        if (mentionText == null || identifier == null || identifier.length() < 3) {
            return false;
        }
        return mentionText.toLowerCase(Locale.ROOT).contains(identifier.toLowerCase(Locale.ROOT));
    }

    private static boolean sharesMeaningfulToken(String probe, Entity entity) {
        Set<String> probeTokens = meaningfulTokens(probe);
        if (probeTokens.isEmpty()) {
            return false;
        }
        Set<String> candidateTokens = new LinkedHashSet<>(
                meaningfulTokens(EntityResolutionService.normalize(entity.getTitle())));
        if (entity.getAliases() != null) {
            for (String alias : entity.getAliases()) {
                candidateTokens.addAll(meaningfulTokens(EntityResolutionService.normalize(alias)));
            }
        }
        return probeTokens.stream().anyMatch(candidateTokens::contains);
    }

    private static Set<String> meaningfulTokens(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String relationshipKey(Relationship relationship) {
        return Objects.toString(relationship.getType(), "") + '|'
                + Objects.toString(relationship.getSource(), "") + '|'
                + Objects.toString(relationship.getTarget(), "");
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

    private record IdentityMatch(boolean canonicalExact, boolean aliasExact,
                                 boolean identifierExact, boolean typeCompatible,
                                 boolean sharedTokensOnly, String matchedIdentifier) {
    }

    private record CandidateSeed(Entity entity, double score, IdentityMatch match) {
    }

    private record Snapshot(List<Entity> entities, List<Relationship> relationships,
                            Map<String, String> names) {

        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of());
        }
    }
}
