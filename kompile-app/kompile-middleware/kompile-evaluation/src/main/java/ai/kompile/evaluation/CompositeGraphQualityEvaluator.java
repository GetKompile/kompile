package ai.kompile.evaluation;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic, domain-neutral quality evaluation for an extracted {@link Graph}.
 *
 * <p>The evaluator never modifies either graph. Entity identity is resolved once, one-to-one,
 * before directed, typed relationships are compared through that mapping.</p>
 */
public final class CompositeGraphQualityEvaluator {

    public Report evaluate(ExpectedGraph expected, Graph actual, ScoringProfile profile) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(profile, "profile");

        List<Entity> actualEntities = safe(actual.getEntities());
        List<Relationship> actualRelations = safe(actual.getRelationships());
        EntityResolution resolution = resolve(expected.entities(), actualEntities,
                profile.aliasSimilarityThreshold());

        Metric entityMetric = metric(resolution.matches.size(),
                actualEntities.size() - resolution.matches.size(),
                expected.entities().size() - resolution.matches.size());

        int correctTypes = 0;
        List<EntityMiss> entityMisses = new ArrayList<>();
        List<EntityMatch> entityMatches = new ArrayList<>();
        Set<Integer> matchedExpected = new HashSet<>();
        Set<Integer> matchedActual = new HashSet<>();
        for (ResolvedMatch match : resolution.matches) {
            ExpectedEntity wanted = expected.entities().get(match.expectedIndex);
            Entity found = actualEntities.get(match.actualIndex);
            boolean typeMatches = normalizeType(wanted.type()).equals(normalizeType(found.getType()));
            if (typeMatches) {
                correctTypes++;
            } else {
                entityMisses.add(new EntityMiss(wanted.key(), EntityMissFacet.WRONG_TYPE,
                        found.getId(), found.getType()));
            }
            matchedExpected.add(match.expectedIndex);
            matchedActual.add(match.actualIndex);
            entityMatches.add(new EntityMatch(wanted.key(), found.getId(), found.getTitle(),
                    match.similarity, match.exact, typeMatches));
        }
        for (int i = 0; i < expected.entities().size(); i++) {
            if (!matchedExpected.contains(i)) {
                entityMisses.add(new EntityMiss(expected.entities().get(i).key(),
                        EntityMissFacet.ABSENT_ENTITY, null, null));
            }
        }
        for (int i = 0; i < actualEntities.size(); i++) {
            if (!matchedActual.contains(i)) {
                Entity extra = actualEntities.get(i);
                entityMisses.add(new EntityMiss(null, EntityMissFacet.UNEXPECTED_ENTITY,
                        extra == null ? null : extra.getId(), extra == null ? null : extra.getTitle()));
            }
        }
        Metric typeMetric = metric(correctTypes,
                resolution.matches.size() - correctTypes,
                expected.entities().size() - correctTypes);

        RelationEvaluation relationEvaluation = evaluateRelations(
                expected, actualEntities, actualRelations, resolution.byExpectedKey);

        CleanlinessReport cleanliness = cleanliness(actualEntities, actualRelations, profile);
        double overall = profile.entityWeight() * entityMetric.f1()
                + profile.relationshipWeight() * relationEvaluation.metric.f1()
                + profile.typeWeight() * typeMetric.f1()
                + profile.cleanlinessWeight() * cleanliness.score();

        return new Report(overall, entityMetric, relationEvaluation.metric, typeMetric,
                cleanliness, entityMatches, entityMisses, relationEvaluation.matches,
                relationEvaluation.misses);
    }

    private static EntityResolution resolve(
            List<ExpectedEntity> expected, List<Entity> actual, double threshold) {
        List<Integer> actualOrder = new ArrayList<>();
        for (int ai = 0; ai < actual.size(); ai++) {
            actualOrder.add(ai);
        }
        actualOrder.sort(Comparator.comparing(index -> actualKey(actual.get(index), index)));

        Candidate[][] candidates = new Candidate[expected.size()][actual.size()];
        for (int ei = 0; ei < expected.size(); ei++) {
            for (int orderedAi = 0; orderedAi < actualOrder.size(); orderedAi++) {
                int actualIndex = actualOrder.get(orderedAi);
                Similarity similarity = similarity(expected.get(ei), actual.get(actualIndex));
                if (similarity.exact || similarity.score >= threshold) {
                    boolean typeMatches = normalizeType(expected.get(ei).type())
                            .equals(normalizeType(actual.get(actualIndex) == null
                                    ? null : actual.get(actualIndex).getType()));
                    candidates[ei][orderedAi] = new Candidate(
                            ei, actualIndex, similarity.score, similarity.exact, typeMatches,
                            actualKey(actual.get(actualIndex), actualIndex));
                }
            }
        }

        int[] assignment = maximumCardinalityAssignment(candidates);
        List<ResolvedMatch> matches = new ArrayList<>();
        for (int expectedIndex = 0; expectedIndex < assignment.length; expectedIndex++) {
            int actualColumn = assignment[expectedIndex];
            if (actualColumn >= actual.size() || candidates[expectedIndex][actualColumn] == null) {
                continue;
            }
            Candidate candidate = candidates[expectedIndex][actualColumn];
            matches.add(new ResolvedMatch(candidate.expectedIndex(), candidate.actualIndex(),
                    candidate.score(), candidate.exact()));
        }
        matches.sort(Comparator.comparingInt(ResolvedMatch::expectedIndex));

        Map<String, Integer> byExpectedKey = new LinkedHashMap<>();
        for (ResolvedMatch match : matches) {
            byExpectedKey.put(expected.get(match.expectedIndex).key(), match.actualIndex);
        }
        return new EntityResolution(List.copyOf(matches), Map.copyOf(byExpectedKey));
    }

    /**
     * Global one-to-one assignment over eligible actual entities plus one dummy per expected entity.
     * The base utility of an eligible pair is deliberately greater than the maximum similarity
     * contribution, so the optimizer maximizes match cardinality before aggregate similarity.
     */
    private static int[] maximumCardinalityAssignment(Candidate[][] candidates) {
        int rows = candidates.length;
        int actualColumns = rows == 0 ? 0 : candidates[0].length;
        int columns = actualColumns + rows;
        double[] u = new double[rows + 1];
        double[] v = new double[columns + 1];
        int[] p = new int[columns + 1];
        int[] way = new int[columns + 1];
        for (int i = 1; i <= rows; i++) {
            p[0] = i;
            double[] min = new double[columns + 1];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[columns + 1];
            int j0 = 0;
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= columns; j++) {
                    if (used[j]) {
                        continue;
                    }
                    Candidate candidate = j <= actualColumns ? candidates[i0 - 1][j - 1] : null;
                    double utility = candidate == null ? 0.0
                            : 2.0 + candidate.score() + (candidate.typeMatches() ? 1.0e-6 : 0.0);
                    double current = -utility - u[i0] - v[j];
                    if (current < min[j] - 1.0e-12) {
                        min[j] = current;
                        way[j] = j0;
                    }
                    if (min[j] < delta - 1.0e-12) {
                        delta = min[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= columns; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        min[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] assignment = new int[rows];
        Arrays.fill(assignment, actualColumns);
        for (int j = 1; j <= columns; j++) {
            if (p[j] != 0) {
                assignment[p[j] - 1] = j - 1;
            }
        }
        return assignment;
    }

    private static Similarity similarity(ExpectedEntity expected, Entity actual) {
        if (actual == null) {
            return new Similarity(0.0, false);
        }
        String expectedId = normalizeLabel(expected.key());
        String actualId = normalizeLabel(actual.getId());
        if (!expectedId.isEmpty() && expectedId.equals(actualId)) {
            return new Similarity(1.0, true);
        }
        List<String> wanted = names(expected.label(), expected.aliases());
        List<String> found = names(actual.getTitle(), actual.getAliases());
        double best = 0.0;
        for (String left : wanted) {
            for (String right : found) {
                if (!left.isEmpty() && left.equals(right)) {
                    return new Similarity(1.0, true);
                }
                best = Math.max(best, normalizedLevenshtein(left, right));
            }
        }
        return new Similarity(best, false);
    }

    private static RelationEvaluation evaluateRelations(ExpectedGraph expected,
            List<Entity> actualEntities, List<Relationship> actualRelations,
            Map<String, Integer> entityMapping) {
        Map<String, String> actualIdByExpectedKey = new HashMap<>();
        for (Map.Entry<String, Integer> entry : entityMapping.entrySet()) {
            Entity entity = actualEntities.get(entry.getValue());
            if (entity != null) {
                actualIdByExpectedKey.put(entry.getKey(), entity.getId());
            }
        }

        Set<Integer> usedRelations = new HashSet<>();
        List<RelationMatch> matches = new ArrayList<>();
        List<RelationMiss> misses = new ArrayList<>();
        for (ExpectedRelation wanted : expected.relations()) {
            String source = actualIdByExpectedKey.get(wanted.sourceKey());
            String target = actualIdByExpectedKey.get(wanted.targetKey());
            if (source == null) {
                misses.add(new RelationMiss(wanted, RelationMissFacet.MISSING_SOURCE_ENDPOINT, null));
                continue;
            }
            if (target == null) {
                misses.add(new RelationMiss(wanted, RelationMissFacet.MISSING_TARGET_ENDPOINT, null));
                continue;
            }

            int exact = findRelation(actualRelations, usedRelations, source, target, wanted.type());
            if (exact >= 0) {
                usedRelations.add(exact);
                matches.add(new RelationMatch(wanted, exact));
                continue;
            }
            int wrongType = findEndpoints(actualRelations, source, target);
            if (wrongType >= 0) {
                misses.add(new RelationMiss(wanted, RelationMissFacet.WRONG_TYPE,
                        describe(actualRelations.get(wrongType))));
                continue;
            }
            int reversed = findEndpoints(actualRelations, target, source);
            if (reversed >= 0) {
                misses.add(new RelationMiss(wanted, RelationMissFacet.REVERSED_DIRECTION,
                        describe(actualRelations.get(reversed))));
                continue;
            }
            misses.add(new RelationMiss(wanted, RelationMissFacet.ABSENT_RELATION, null));
        }
        Metric metric = metric(matches.size(), actualRelations.size() - usedRelations.size(),
                expected.relations().size() - matches.size());
        return new RelationEvaluation(metric, List.copyOf(matches), List.copyOf(misses));
    }

    private static int findRelation(List<Relationship> relations, Set<Integer> used,
            String source, String target, String type) {
        for (int i = 0; i < relations.size(); i++) {
            Relationship relation = relations.get(i);
            if (!used.contains(i) && endpoints(relation, source, target)
                    && normalizeType(type).equals(normalizeType(relation.getType()))) {
                return i;
            }
        }
        return -1;
    }

    private static int findEndpoints(List<Relationship> relations, String source, String target) {
        for (int i = 0; i < relations.size(); i++) {
            if (endpoints(relations.get(i), source, target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean endpoints(Relationship relation, String source, String target) {
        return relation != null && Objects.equals(source, relation.getSource())
                && Objects.equals(target, relation.getTarget());
    }

    private static CleanlinessReport cleanliness(
            List<Entity> entities, List<Relationship> relations, ScoringProfile profile) {
        Set<String> ids = new HashSet<>();
        Map<String, Integer> normalizedLabels = new LinkedHashMap<>();
        for (Entity entity : entities) {
            if (entity == null) {
                continue;
            }
            if (entity.getId() != null) {
                ids.add(entity.getId());
            }
            String label = normalizeLabel(entity.getTitle());
            if (!label.isEmpty()) {
                normalizedLabels.merge(label, 1, Integer::sum);
            }
        }
        int duplicates = normalizedLabels.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
        int dangling = 0;
        Set<String> connected = new HashSet<>();
        for (Relationship relation : relations) {
            if (relation == null || !ids.contains(relation.getSource()) || !ids.contains(relation.getTarget())) {
                dangling++;
            } else {
                connected.add(relation.getSource());
                connected.add(relation.getTarget());
            }
        }
        int isolated = (int) entities.stream().filter(Objects::nonNull)
                .filter(entity -> entity.getId() == null || !connected.contains(entity.getId())).count();

        Optional<CountFacet> oov = Optional.empty();
        if (!profile.allowedEntityTypes().isEmpty() || !profile.allowedRelationshipTypes().isEmpty()) {
            int checked = 0;
            int failures = 0;
            if (!profile.allowedEntityTypes().isEmpty()) {
                Set<String> allowed = normalizeTypes(profile.allowedEntityTypes());
                checked += entities.size();
                failures += (int) entities.stream().filter(entity -> entity == null
                        || !allowed.contains(normalizeType(entity.getType()))).count();
            }
            if (!profile.allowedRelationshipTypes().isEmpty()) {
                Set<String> allowed = normalizeTypes(profile.allowedRelationshipTypes());
                checked += relations.size();
                failures += (int) relations.stream().filter(relation -> relation == null
                        || !allowed.contains(normalizeType(relation.getType()))).count();
            }
            oov = Optional.of(new CountFacet(failures, checked));
        }

        Optional<CountFacet> provenance = Optional.empty();
        if (!profile.provenanceMetadataKeys().isEmpty()) {
            int checked = entities.size() + relations.size();
            int missing = 0;
            for (Entity entity : entities) {
                if (entity == null || !hasEntityProvenance(entity, profile.provenanceMetadataKeys())) {
                    missing++;
                }
            }
            for (Relationship relation : relations) {
                if (relation == null || !hasMetadataKey(relation.getMetadata(),
                        profile.provenanceMetadataKeys())) {
                    missing++;
                }
            }
            provenance = Optional.of(new CountFacet(missing, checked));
        }

        List<Double> facets = new ArrayList<>();
        facets.add(cleanRatio(duplicates, entities.size()));
        facets.add(cleanRatio(dangling, relations.size()));
        facets.add(cleanRatio(isolated, entities.size()));
        oov.ifPresent(facet -> facets.add(facet.score()));
        provenance.ifPresent(facet -> facets.add(facet.score()));
        double score = facets.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        return new CleanlinessReport(score, duplicates, dangling, isolated, oov, provenance);
    }

    private static boolean hasEntityProvenance(Entity entity, Set<String> keys) {
        return entity.getTextUnits() != null && !entity.getTextUnits().isEmpty()
                || hasMetadataKey(entity.getMetadata(), keys);
    }

    private static boolean hasMetadataKey(Map<String, Object> metadata, Set<String> keys) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeTypes(Collection<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            result.add(normalizeType(value));
        }
        return result;
    }

    private static List<String> names(String label, List<String> aliases) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = normalizeLabel(label);
        if (!normalized.isEmpty()) {
            result.add(normalized);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                normalized = normalizeLabel(alias);
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeLabel(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim()
                .replaceAll("\\s+", " ");
    }

    private static String normalizeType(String value) {
        return normalizeLabel(value).replace(' ', '_');
    }

    private static double normalizedLevenshtein(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length());
    }

    private static Metric metric(int tp, int fp, int fn) {
        double precision = tp + fp == 0 ? (fn == 0 ? 1.0 : 0.0) : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0.0 ? 0.0
                : 2.0 * precision * recall / (precision + recall);
        return new Metric(tp, fp, fn, precision, recall, f1);
    }

    private static double cleanRatio(int failures, int opportunities) {
        return opportunities == 0 ? 1.0 : Math.max(0.0, 1.0 - (double) failures / opportunities);
    }

    private static String actualKey(Entity entity, int index) {
        if (entity == null) {
            return "~null-" + index;
        }
        return normalizeLabel(entity.getTitle()) + "\u0000"
                + Objects.toString(entity.getId(), "") + "\u0000" + index;
    }

    private static String describe(Relationship relation) {
        return relation == null ? null
                : Objects.toString(relation.getSource(), "") + " -["
                + Objects.toString(relation.getType(), "") + "]-> "
                + Objects.toString(relation.getTarget(), "");
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    public record ExpectedEntity(String key, String label, String type, List<String> aliases) {
        public ExpectedEntity {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(type, "type");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record ExpectedRelation(String sourceKey, String type, String targetKey) {
        public ExpectedRelation {
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(targetKey, "targetKey");
        }
    }

    public record ExpectedGraph(List<ExpectedEntity> entities, List<ExpectedRelation> relations) {
        public ExpectedGraph {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            Set<String> keys = new HashSet<>();
            for (ExpectedEntity entity : entities) {
                if (!keys.add(entity.key())) {
                    throw new IllegalArgumentException("Duplicate expected entity key: " + entity.key());
                }
            }
            for (ExpectedRelation relation : relations) {
                if (!keys.contains(relation.sourceKey()) || !keys.contains(relation.targetKey())) {
                    throw new IllegalArgumentException("Expected relation references an unknown entity key");
                }
            }
        }
    }

    public record ScoringProfile(double entityWeight, double relationshipWeight, double typeWeight,
            double cleanlinessWeight, double aliasSimilarityThreshold,
            Set<String> allowedEntityTypes, Set<String> allowedRelationshipTypes,
            Set<String> provenanceMetadataKeys) {
        public ScoringProfile {
            requireUnit(entityWeight, "entityWeight");
            requireUnit(relationshipWeight, "relationshipWeight");
            requireUnit(typeWeight, "typeWeight");
            requireUnit(cleanlinessWeight, "cleanlinessWeight");
            requireUnit(aliasSimilarityThreshold, "aliasSimilarityThreshold");
            double total = entityWeight + relationshipWeight + typeWeight + cleanlinessWeight;
            if (Math.abs(total - 1.0) > 1.0e-9) {
                throw new IllegalArgumentException("Scoring weights must sum to 1.0");
            }
            allowedEntityTypes = immutableSet(allowedEntityTypes);
            allowedRelationshipTypes = immutableSet(allowedRelationshipTypes);
            provenanceMetadataKeys = immutableSet(provenanceMetadataKeys);
        }

        public static ScoringProfile defaults() {
            return new ScoringProfile(0.30, 0.35, 0.20, 0.15, 0.80,
                    Set.of(), Set.of(), Set.of());
        }
    }

    public record Metric(int truePositives, int falsePositives, int falseNegatives,
            double precision, double recall, double f1) {}

    public enum EntityMissFacet { ABSENT_ENTITY, UNEXPECTED_ENTITY, WRONG_TYPE }

    public enum RelationMissFacet {
        MISSING_SOURCE_ENDPOINT, MISSING_TARGET_ENDPOINT, WRONG_TYPE,
        REVERSED_DIRECTION, ABSENT_RELATION
    }

    public record EntityMatch(String expectedKey, String actualId, String actualLabel,
            double similarity, boolean exact, boolean typeMatches) {}

    public record EntityMiss(String expectedKey, EntityMissFacet facet,
            String actualId, String detail) {}

    public record RelationMatch(ExpectedRelation expected, int actualRelationIndex) {}

    public record RelationMiss(ExpectedRelation expected, RelationMissFacet facet, String detail) {}

    public record CountFacet(int failures, int opportunities) {
        public double score() {
            return cleanRatio(failures, opportunities);
        }
    }

    public record CleanlinessReport(double score, int duplicateEntities,
            int danglingRelationships, int isolatedEntities, Optional<CountFacet> outOfVocabulary,
            Optional<CountFacet> missingProvenance) {
        public CleanlinessReport {
            outOfVocabulary = outOfVocabulary == null ? Optional.empty() : outOfVocabulary;
            missingProvenance = missingProvenance == null ? Optional.empty() : missingProvenance;
        }
    }

    public record Report(double overallScore, Metric entities, Metric relationships, Metric types,
            CleanlinessReport cleanliness, List<EntityMatch> entityMatches,
            List<EntityMiss> entityMisses, List<RelationMatch> relationMatches,
            List<RelationMiss> relationMisses) {
        public Report {
            entityMatches = List.copyOf(entityMatches);
            entityMisses = List.copyOf(entityMisses);
            relationMatches = List.copyOf(relationMatches);
            relationMisses = List.copyOf(relationMisses);
        }
    }

    private record Similarity(double score, boolean exact) {}
    private record Candidate(int expectedIndex, int actualIndex, double score,
            boolean exact, boolean typeMatches, String actualKey) {}
    private record ResolvedMatch(int expectedIndex, int actualIndex,
            double similarity, boolean exact) {}
    private record EntityResolution(List<ResolvedMatch> matches,
            Map<String, Integer> byExpectedKey) {}
    private record RelationEvaluation(Metric metric, List<RelationMatch> matches,
            List<RelationMiss> misses) {}

    private static Set<String> immutableSet(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
