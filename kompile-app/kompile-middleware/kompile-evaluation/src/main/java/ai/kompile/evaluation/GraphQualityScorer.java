/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.evaluation;

import ai.kompile.core.evaluation.graph.GraphDecisionTraceEvent;
import ai.kompile.core.evaluation.graph.GraphMissAttribution;
import ai.kompile.core.evaluation.graph.GraphMissAttributor;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.core.evaluation.graph.GraphMissStage;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.RelationSignature;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.EntityMatch;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.EntityMiss;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.EntityMissFacet;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedEntity;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedGraph;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ExpectedRelation;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.RelationMatch;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.RelationMiss;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.RelationMissFacet;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.Report;
import ai.kompile.evaluation.CompositeGraphQualityEvaluator.ScoringProfile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Production graph-evaluation facade that combines quality metrics, miss facets, and decision-trace causes.
 *
 * <p>The scorer is domain-neutral and read-only. Its primary result compares expected and extracted
 * entity and relation facts. It then attributes each missed or incorrectly typed expected fact against
 * optional production {@link GraphDecisionTraceEvent decision traces}. Extra extracted facts remain
 * visible as false positives and are never mislabeled as missed expected facts.</p>
 */
public final class GraphQualityScorer {

    private final CompositeGraphQualityEvaluator qualityEvaluator;
    private final GraphMissAttributor missAttributor;

    public GraphQualityScorer() {
        this(new CompositeGraphQualityEvaluator(), new GraphMissAttributor());
    }

    public GraphQualityScorer(CompositeGraphQualityEvaluator qualityEvaluator,
                              GraphMissAttributor missAttributor) {
        this.qualityEvaluator = Objects.requireNonNull(qualityEvaluator, "qualityEvaluator");
        this.missAttributor = Objects.requireNonNull(missAttributor, "missAttributor");
    }

    public Evaluation score(ExpectedGraph expected, Graph actual, ScoringProfile profile) {
        return score(expected, actual, profile, null, List.of(),
                TraceCorrelation.defaultCorrelation());
    }

    public Evaluation score(ExpectedGraph expected,
                            Graph actual,
                            ScoringProfile profile,
                            Collection<GraphDecisionTraceEvent> decisionTrace) {
        return score(expected, actual, profile, null, decisionTrace,
                TraceCorrelation.defaultCorrelation());
    }

    /**
     * Scores expected and extracted facts against the exact standardized schema used for extraction.
     * The schema replaces any type sets carried by the scoring profile and also validates declared
     * source-entity/relation/target-entity patterns.
     */
    public Evaluation score(ExpectedGraph expected,
                            Graph actual,
                            ScoringProfile profile,
                            GraphSchema schema,
                            Collection<GraphDecisionTraceEvent> decisionTrace) {
        return score(expected, actual, profile, schema, decisionTrace,
                TraceCorrelation.defaultCorrelation());
    }

    public Evaluation score(ExpectedGraph expected,
                            Graph actual,
                            ScoringProfile profile,
                            Collection<GraphDecisionTraceEvent> decisionTrace,
                            TraceCorrelation correlation) {
        return score(expected, actual, profile, null, decisionTrace, correlation);
    }

    public Evaluation score(ExpectedGraph expected,
                            Graph actual,
                            ScoringProfile profile,
                            GraphSchema schema,
                            Collection<GraphDecisionTraceEvent> decisionTrace,
                            TraceCorrelation correlation) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(correlation, "correlation");
        List<GraphDecisionTraceEvent> trace = decisionTrace == null
                ? List.of() : decisionTrace.stream().filter(Objects::nonNull).toList();
        ScoringProfile effectiveProfile = profileWithSchema(profile, schema);

        Report quality = qualityEvaluator.evaluate(expected, actual, effectiveProfile);
        List<GoldMiss> seeds = goldMisses(quality);
        List<AttributedMiss> attributed = new ArrayList<>(seeds.size());
        for (GoldMiss seed : seeds) {
            Collection<GraphDecisionTraceEvent> relevant = correlation.relevantEvents(
                    seed, expected, quality, trace);
            List<GraphDecisionTraceEvent> canonicalEvents = canonicalEvents(
                    seed.atom(), relevant == null ? List.of() : relevant);
            GraphMissAttribution attribution = missAttributor.attribute(
                    List.of(seed.atom()), canonicalEvents).get(0);
            attributed.add(new AttributedMiss(seed, attribution));
        }
        return new Evaluation(compareFacts(expected, actual, effectiveProfile, schema, quality), quality,
                List.copyOf(attributed), summarize(quality, attributed));
    }

    private static ScoringProfile profileWithSchema(ScoringProfile profile, GraphSchema schema) {
        if (schema == null) {
            return profile;
        }
        Set<String> entityTypes = schema.getNodeTypes() == null ? Set.of()
                : schema.getNodeTypes().stream()
                        .filter(Objects::nonNull)
                        .map(NodeType::getLabel)
                        .filter(GraphQualityScorer::hasText)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> relationTypes = schema.getRelationshipTypes() == null ? Set.of()
                : schema.getRelationshipTypes().stream()
                        .filter(Objects::nonNull)
                        .map(RelationshipType::getType)
                        .filter(GraphQualityScorer::hasText)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new ScoringProfile(profile.entityWeight(), profile.relationshipWeight(),
                profile.typeWeight(), profile.cleanlinessWeight(),
                profile.aliasSimilarityThreshold(), entityTypes, relationTypes,
                profile.provenanceMetadataKeys());
    }

    private static FactComparison compareFacts(ExpectedGraph expected, Graph actual,
                                               ScoringProfile profile, GraphSchema schema,
                                               Report quality) {
        Map<String, ExpectedEntity> expectedByKey = new LinkedHashMap<>();
        List<EntityFact> expectedEntities = new ArrayList<>();
        for (ExpectedEntity entity : expected.entities()) {
            expectedByKey.put(entity.key(), entity);
            expectedEntities.add(expectedEntityFact(entity));
        }

        List<Entity> actualEntityModels = actual.getEntities() == null ? List.of() : actual.getEntities();
        List<EntityFact> extractedEntities = actualEntityModels.stream()
                .map(GraphQualityScorer::extractedEntityFact).toList();
        Set<String> matchedExpectedEntityKeys = new LinkedHashSet<>();
        Map<EntityFact, Integer> matchedExtractedEntityCounts = new LinkedHashMap<>();
        List<EntityFactMatch> matchedEntities = new ArrayList<>();
        for (EntityMatch match : quality.entityMatches()) {
            ExpectedEntity expectedEntity = expectedByKey.get(match.expectedKey());
            if (expectedEntity == null || !match.typeMatches()) {
                continue;
            }
            EntityFact extracted = extractedEntities.stream()
                    .filter(candidate -> Objects.equals(candidate.id(), match.actualId()))
                    .filter(candidate -> Objects.equals(candidate.name(), match.actualLabel()))
                    .filter(candidate -> normalizeType(candidate.type())
                            .equals(normalizeType(expectedEntity.type())))
                    .findFirst()
                    .orElseGet(() -> new EntityFact(match.actualId(), match.actualLabel(),
                            expectedEntity.type()));
            EntityFact wanted = expectedEntityFact(expectedEntity);
            matchedExpectedEntityKeys.add(expectedEntity.key());
            matchedExtractedEntityCounts.merge(extracted, 1, Integer::sum);
            matchedEntities.add(new EntityFactMatch(wanted, extracted));
        }
        List<EntityFact> missedEntities = expected.entities().stream()
                .filter(entity -> !matchedExpectedEntityKeys.contains(entity.key()))
                .map(GraphQualityScorer::expectedEntityFact).toList();
        List<EntityFact> extraEntities = unmatchedFacts(
                extractedEntities, matchedExtractedEntityCounts);

        List<Relationship> actualRelationModels = actual.getRelationships() == null
                ? List.of() : actual.getRelationships();
        List<RelationFact> extractedRelations = actualRelationModels.stream()
                .map(GraphQualityScorer::extractedRelationFact).toList();
        Set<ExpectedRelation> matchedExpectedRelations = new LinkedHashSet<>();
        Set<Integer> matchedExtractedRelationIndexes = new LinkedHashSet<>();
        List<RelationFactMatch> matchedRelations = new ArrayList<>();
        for (RelationMatch match : quality.relationMatches()) {
            int index = match.actualRelationIndex();
            if (index < 0 || index >= extractedRelations.size()) {
                continue;
            }
            matchedExpectedRelations.add(match.expected());
            matchedExtractedRelationIndexes.add(index);
            matchedRelations.add(new RelationFactMatch(expectedRelationFact(match.expected()),
                    extractedRelations.get(index)));
        }
        List<RelationFact> expectedRelations = expected.relations().stream()
                .map(GraphQualityScorer::expectedRelationFact).toList();
        List<RelationFact> missedRelations = expected.relations().stream()
                .filter(relation -> !matchedExpectedRelations.contains(relation))
                .map(GraphQualityScorer::expectedRelationFact).toList();
        List<RelationFact> extraRelations = new ArrayList<>();
        for (int index = 0; index < extractedRelations.size(); index++) {
            if (!matchedExtractedRelationIndexes.contains(index)) {
                extraRelations.add(extractedRelations.get(index));
            }
        }

        return new FactComparison(expectedEntities, extractedEntities, matchedEntities,
                missedEntities, extraEntities,
                expectedRelations, extractedRelations, matchedRelations,
                missedRelations, List.copyOf(extraRelations),
                FactCounts.of(expectedEntities.size(), extractedEntities.size(), matchedEntities.size()),
                FactCounts.of(expectedRelations.size(), extractedRelations.size(), matchedRelations.size()),
                schemaAdherence(expected.entities(), expected.relations(),
                        actualEntityModels, actualRelationModels, profile, schema));
    }

    private static List<EntityFact> unmatchedFacts(List<EntityFact> extracted,
                                                   Map<EntityFact, Integer> matchedCounts) {
        Map<EntityFact, Integer> remaining = new LinkedHashMap<>(matchedCounts);
        List<EntityFact> extras = new ArrayList<>();
        for (EntityFact fact : extracted) {
            int count = remaining.getOrDefault(fact, 0);
            if (count > 0) {
                remaining.put(fact, count - 1);
            } else {
                extras.add(fact);
            }
        }
        return List.copyOf(extras);
    }

    private static SchemaAdherence schemaAdherence(
            List<ExpectedEntity> expectedEntities,
            List<ExpectedRelation> expectedRelations,
            List<Entity> extractedEntities,
            List<Relationship> extractedRelations,
            ScoringProfile profile,
            GraphSchema schema) {
        Set<String> allowedEntityTypes = normalizedTypes(profile.allowedEntityTypes());
        Set<String> allowedRelationTypes = normalizedTypes(profile.allowedRelationshipTypes());
        List<String> violations = new ArrayList<>();
        Map<String, List<RelationSignature>> relationPatterns = relationPatterns(
                schema, allowedEntityTypes, allowedRelationTypes, violations);

        Set<String> expectedEntityIds = new LinkedHashSet<>();
        Map<String, String> expectedEntityTypesById = new LinkedHashMap<>();
        for (ExpectedEntity entity : expectedEntities) {
            String id = entity == null ? null : entity.key();
            String name = entity == null ? null : entity.label();
            String type = entity == null ? null : entity.type();
            if (!hasText(id) || !hasText(name) || !hasText(type)) {
                violations.add("expected entity requires id, name, and type: "
                        + new EntityFact(id, name, type));
                continue;
            }
            if (!expectedEntityIds.add(id)) {
                violations.add("duplicate expected entity id: " + id);
            } else {
                expectedEntityTypesById.put(id, normalizeType(type));
            }
            if (!allowedEntityTypes.isEmpty() && !allowedEntityTypes.contains(normalizeType(type))) {
                violations.add("expected entity " + id + " uses undeclared type " + type);
            }
        }
        for (ExpectedRelation relation : expectedRelations) {
            String source = relation == null ? null : relation.sourceKey();
            String target = relation == null ? null : relation.targetKey();
            String type = relation == null ? null : relation.type();
            RelationFact fact = new RelationFact(source, type, target);
            if (!hasText(source) || !hasText(target) || !hasText(type)) {
                violations.add("expected relation requires source, type, and target: " + fact);
                continue;
            }
            if (!expectedEntityIds.contains(source) || !expectedEntityIds.contains(target)) {
                violations.add("expected relation references an entity not present in expected facts: " + fact);
            }
            if (!allowedRelationTypes.isEmpty() && !allowedRelationTypes.contains(normalizeType(type))) {
                violations.add("expected relation uses undeclared type " + type + ": " + fact);
            }
            validateRelationPattern("expected", fact, expectedEntityTypesById,
                    relationPatterns, violations);
        }

        Set<String> extractedEntityIds = new LinkedHashSet<>();
        Map<String, String> extractedEntityTypesById = new LinkedHashMap<>();
        for (Entity entity : extractedEntities) {
            String id = entity == null ? null : entity.getId();
            String name = entity == null ? null : entity.getTitle();
            String type = entity == null ? null : entity.getType();
            if (!hasText(id) || !hasText(name) || !hasText(type)) {
                violations.add("extracted entity requires id, name, and type: "
                        + new EntityFact(id, name, type));
                continue;
            }
            if (!extractedEntityIds.add(id)) {
                violations.add("duplicate extracted entity id: " + id);
            } else {
                extractedEntityTypesById.put(id, normalizeType(type));
            }
            if (!allowedEntityTypes.isEmpty() && !allowedEntityTypes.contains(normalizeType(type))) {
                violations.add("extracted entity " + id + " uses undeclared type " + type);
            }
        }
        for (Relationship relation : extractedRelations) {
            String source = relation == null ? null : relation.getSource();
            String target = relation == null ? null : relation.getTarget();
            String type = relation == null ? null : relation.getType();
            RelationFact fact = new RelationFact(source, type, target);
            if (!hasText(source) || !hasText(target) || !hasText(type)) {
                violations.add("extracted relation requires source, type, and target: " + fact);
                continue;
            }
            if (!extractedEntityIds.contains(source) || !extractedEntityIds.contains(target)) {
                violations.add("extracted relation references an entity not present in extracted facts: " + fact);
            }
            if (!allowedRelationTypes.isEmpty() && !allowedRelationTypes.contains(normalizeType(type))) {
                violations.add("extracted relation uses undeclared type " + type + ": " + fact);
            }
            validateRelationPattern("extracted", fact, extractedEntityTypesById,
                    relationPatterns, violations);
        }
        return new SchemaAdherence(violations.isEmpty(), List.copyOf(violations));
    }

    private static Map<String, List<RelationSignature>> relationPatterns(
            GraphSchema schema,
            Set<String> allowedEntityTypes,
            Set<String> allowedRelationTypes,
            List<String> violations) {
        if (schema == null || schema.getPatterns() == null || schema.getPatterns().isEmpty()) {
            return Map.of();
        }
        Map<String, List<RelationSignature>> patternsByRelation = new LinkedHashMap<>();
        for (String expression : schema.getPatterns()) {
            java.util.Optional<RelationSignature> parsed =
                    GraphExtractionValidator.parseRelationPattern(expression);
            if (parsed.isEmpty()) {
                violations.add("schema contains malformed entity-relation-entity pattern: " + expression);
                continue;
            }
            RelationSignature pattern = parsed.get();
            if (!allowedEntityTypes.isEmpty()
                    && !allowedEntityTypes.contains(pattern.sourceType())) {
                violations.add("schema relation pattern uses undeclared source entity type "
                        + pattern.sourceType() + ": " + pattern.expression());
            }
            if (!allowedEntityTypes.isEmpty()
                    && !allowedEntityTypes.contains(pattern.targetType())) {
                violations.add("schema relation pattern uses undeclared target entity type "
                        + pattern.targetType() + ": " + pattern.expression());
            }
            if (!allowedRelationTypes.isEmpty()
                    && !allowedRelationTypes.contains(pattern.relationType())) {
                violations.add("schema relation pattern uses undeclared relation type "
                        + pattern.relationType() + ": " + pattern.expression());
            }
            patternsByRelation.computeIfAbsent(pattern.relationType(), ignored -> new ArrayList<>())
                    .add(pattern);
        }
        Map<String, List<RelationSignature>> immutable = new LinkedHashMap<>();
        patternsByRelation.forEach((type, patterns) ->
                immutable.put(type, List.copyOf(patterns)));
        return Collections.unmodifiableMap(immutable);
    }

    private static void validateRelationPattern(
            String origin,
            RelationFact fact,
            Map<String, String> entityTypesById,
            Map<String, List<RelationSignature>> patternsByRelation,
            List<String> violations) {
        if (fact == null || !hasText(fact.source()) || !hasText(fact.target())
                || !hasText(fact.type())) {
            return;
        }
        List<RelationSignature> allowed = patternsByRelation.getOrDefault(
                normalizeType(fact.type()), List.of());
        if (allowed.isEmpty()) {
            return;
        }
        String sourceType = entityTypesById.get(fact.source());
        String targetType = entityTypesById.get(fact.target());
        if (!hasText(sourceType) || !hasText(targetType)) {
            return;
        }
        boolean valid = allowed.stream().anyMatch(pattern ->
                pattern.sourceType().equals(sourceType)
                        && pattern.targetType().equals(targetType));
        if (!valid) {
            String accepted = allowed.stream().map(RelationSignature::expression)
                    .distinct().reduce((left, right) -> left + " or " + right)
                    .orElse(normalizeType(fact.type()));
            violations.add(origin + " relation " + fact
                    + " connects source entity type " + sourceType
                    + " to target entity type " + targetType
                    + ", but schema allows " + accepted);
        }
    }

    private static Set<String> normalizedTypes(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.stream().filter(GraphQualityScorer::hasText)
                .map(GraphQualityScorer::normalizeType).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private static EntityFact expectedEntityFact(ExpectedEntity entity) {
        return new EntityFact(entity.key(), entity.label(), normalizeType(entity.type()));
    }

    private static EntityFact extractedEntityFact(Entity entity) {
        return entity == null ? new EntityFact(null, null, null)
                : new EntityFact(entity.getId(), entity.getTitle(), normalizeType(entity.getType()));
    }

    private static RelationFact expectedRelationFact(ExpectedRelation relation) {
        return new RelationFact(relation.sourceKey(), normalizeType(relation.type()), relation.targetKey());
    }

    private static RelationFact extractedRelationFact(Relationship relation) {
        return relation == null ? new RelationFact(null, null, null)
                : new RelationFact(relation.getSource(), normalizeType(relation.getType()), relation.getTarget());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<GoldMiss> goldMisses(Report quality) {
        List<GoldMiss> misses = new ArrayList<>();
        for (EntityMiss miss : quality.entityMisses()) {
            if (miss.expectedKey() == null || miss.facet() == EntityMissFacet.UNEXPECTED_ENTITY) {
                continue;
            }
            misses.add(new GoldMiss(entityAtom(miss.expectedKey()), AtomKind.ENTITY,
                    miss.expectedKey(), null, miss.facet(), null, miss.detail()));
        }
        for (RelationMiss miss : quality.relationMisses()) {
            ExpectedRelation relation = miss.expected();
            misses.add(new GoldMiss(relationAtom(relation), AtomKind.RELATION,
                    null, relation, null, miss.facet(), miss.detail()));
        }
        return List.copyOf(misses);
    }

    private static List<GraphDecisionTraceEvent> canonicalEvents(
            String atom, Collection<GraphDecisionTraceEvent> events) {
        List<GraphDecisionTraceEvent> result = new ArrayList<>();
        for (GraphDecisionTraceEvent event : events) {
            if (event == null) {
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(event.metadata());
            if (event.atom() != null && !event.atom().equals(atom)) {
                metadata.putIfAbsent("observedAtom", event.atom());
            }
            result.add(new GraphDecisionTraceEvent(event.eventId(), event.sourceId(), event.documentId(),
                    event.chunkId(), event.shardId(), atom, event.stage(), event.reason(),
                    event.disposition(), event.candidateIds(), event.candidateScores(), metadata));
        }
        return List.copyOf(result);
    }

    private static Summary summarize(Report quality, List<AttributedMiss> misses) {
        EnumMap<EntityMissFacet, Integer> entityFacets = new EnumMap<>(EntityMissFacet.class);
        EnumMap<RelationMissFacet, Integer> relationFacets = new EnumMap<>(RelationMissFacet.class);
        EnumMap<GraphMissStage, Integer> stages = new EnumMap<>(GraphMissStage.class);
        EnumMap<GraphMissReason, Integer> reasons = new EnumMap<>(GraphMissReason.class);
        for (EntityMiss miss : quality.entityMisses()) {
            entityFacets.merge(miss.facet(), 1, Integer::sum);
        }
        for (RelationMiss miss : quality.relationMisses()) {
            relationFacets.merge(miss.facet(), 1, Integer::sum);
        }
        int attributed = 0;
        for (AttributedMiss miss : misses) {
            GraphMissAttribution attribution = miss.attribution();
            reasons.merge(attribution.reason(), 1, Integer::sum);
            if (attribution.stage() != null && attribution.reason() != GraphMissReason.UNATTRIBUTED) {
                attributed++;
                stages.merge(attribution.stage(), 1, Integer::sum);
            }
        }
        int total = misses.size();
        return new Summary(immutableEnumMap(entityFacets), immutableEnumMap(relationFacets),
                immutableEnumMap(stages), immutableEnumMap(reasons), total, attributed,
                total - attributed, total == 0 ? 1.0d : (double) attributed / total);
    }

    private static <K extends Enum<K>> Map<K, Integer> immutableEnumMap(EnumMap<K, Integer> values) {
        return Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public static String entityAtom(String expectedKey) {
        return "entity:" + Objects.requireNonNull(expectedKey, "expectedKey");
    }

    public static String relationAtom(ExpectedRelation relation) {
        Objects.requireNonNull(relation, "relation");
        return "relation:" + relation.sourceKey() + "|" + normalizeType(relation.type())
                + "|" + relation.targetKey();
    }

    public enum AtomKind { ENTITY, RELATION }

    public record GoldMiss(String atom,
                           AtomKind kind,
                           String expectedEntityKey,
                           ExpectedRelation expectedRelation,
                           EntityMissFacet entityFacet,
                           RelationMissFacet relationFacet,
                           String detail) {
        public GoldMiss {
            Objects.requireNonNull(atom, "atom");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record AttributedMiss(GoldMiss miss, GraphMissAttribution attribution) {
        public AttributedMiss {
            Objects.requireNonNull(miss, "miss");
            Objects.requireNonNull(attribution, "attribution");
        }
    }

    public record Summary(Map<EntityMissFacet, Integer> entityMissFacets,
                          Map<RelationMissFacet, Integer> relationMissFacets,
                          Map<GraphMissStage, Integer> attributedStages,
                          Map<GraphMissReason, Integer> attributedReasons,
                          int goldMisses,
                          int attributedGoldMisses,
                          int unattributedGoldMisses,
                          double attributionCoverage) {
        public Summary {
            entityMissFacets = Map.copyOf(entityMissFacets);
            relationMissFacets = Map.copyOf(relationMissFacets);
            attributedStages = Map.copyOf(attributedStages);
            attributedReasons = Map.copyOf(attributedReasons);
        }
    }

    /** Primary user-facing comparison of expected and extracted graph facts. */
    public record FactComparison(List<EntityFact> expectedEntities,
                                 List<EntityFact> extractedEntities,
                                 List<EntityFactMatch> matchedEntities,
                                 List<EntityFact> missedEntities,
                                 List<EntityFact> extraEntities,
                                 List<RelationFact> expectedRelations,
                                 List<RelationFact> extractedRelations,
                                 List<RelationFactMatch> matchedRelations,
                                 List<RelationFact> missedRelations,
                                 List<RelationFact> extraRelations,
                                 FactCounts entityCounts,
                                 FactCounts relationCounts,
                                 SchemaAdherence schemaAdherence) {
        public FactComparison {
            expectedEntities = List.copyOf(expectedEntities);
            extractedEntities = List.copyOf(extractedEntities);
            matchedEntities = List.copyOf(matchedEntities);
            missedEntities = List.copyOf(missedEntities);
            extraEntities = List.copyOf(extraEntities);
            expectedRelations = List.copyOf(expectedRelations);
            extractedRelations = List.copyOf(extractedRelations);
            matchedRelations = List.copyOf(matchedRelations);
            missedRelations = List.copyOf(missedRelations);
            extraRelations = List.copyOf(extraRelations);
            Objects.requireNonNull(entityCounts, "entityCounts");
            Objects.requireNonNull(relationCounts, "relationCounts");
            Objects.requireNonNull(schemaAdherence, "schemaAdherence");
        }

        public String summary() {
            return "entities " + entityCounts.summary() + "; relations " + relationCounts.summary()
                    + "; schema=" + (schemaAdherence.valid() ? "valid" : "invalid")
                    + " violations=" + schemaAdherence.violations().size();
        }
    }

    public record EntityFact(String id, String name, String type) {}

    public record RelationFact(String source, String type, String target) {}

    public record EntityFactMatch(EntityFact expected, EntityFact extracted) {
        public EntityFactMatch {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(extracted, "extracted");
        }
    }

    public record RelationFactMatch(RelationFact expected, RelationFact extracted) {
        public RelationFactMatch {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(extracted, "extracted");
        }
    }

    public record FactCounts(int expected, int extracted, int matched, int missed, int extra,
                             double precision, double recall, double f1) {
        static FactCounts of(int expected, int extracted, int matched) {
            int missed = Math.max(0, expected - matched);
            int extra = Math.max(0, extracted - matched);
            double precision = extracted == 0 ? (expected == 0 ? 1.0d : 0.0d)
                    : (double) matched / extracted;
            double recall = expected == 0 ? 1.0d : (double) matched / expected;
            double f1 = precision + recall == 0.0d
                    ? 0.0d : 2.0d * precision * recall / (precision + recall);
            return new FactCounts(expected, extracted, matched, missed, extra,
                    precision, recall, f1);
        }

        String summary() {
            return "expected=" + expected + " extracted=" + extracted + " matched=" + matched
                    + " missed=" + missed + " extra=" + extra;
        }
    }

    public record SchemaAdherence(boolean valid, List<String> violations) {
        public SchemaAdherence {
            violations = List.copyOf(violations);
        }
    }

    public record Evaluation(FactComparison facts,
                             Report quality,
                             List<AttributedMiss> misses,
                             Summary summary) {
        public Evaluation {
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(quality, "quality");
            misses = List.copyOf(Objects.requireNonNull(misses, "misses"));
            Objects.requireNonNull(summary, "summary");
        }
    }

    /** Maps one gold miss to the production decision events that can explain it. */
    @FunctionalInterface
    public interface TraceCorrelation {
        Collection<GraphDecisionTraceEvent> relevantEvents(
                GoldMiss miss,
                ExpectedGraph expected,
                Report quality,
                Collection<GraphDecisionTraceEvent> allEvents);

        static TraceCorrelation defaultCorrelation() {
            return GraphQualityScorer::defaultRelevantEvents;
        }
    }

    private static Collection<GraphDecisionTraceEvent> defaultRelevantEvents(
            GoldMiss miss,
            ExpectedGraph expected,
            Report quality,
            Collection<GraphDecisionTraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        if (miss.kind() == AtomKind.ENTITY) {
            ExpectedEntity entity = expected.entities().stream()
                    .filter(candidate -> candidate.key().equals(miss.expectedEntityKey()))
                    .findFirst().orElse(null);
            Set<String> identities = entityIdentities(entity, quality);
            return events.stream().filter(event -> eventMatchesAnyIdentity(event, identities)).toList();
        }

        ExpectedRelation relation = miss.expectedRelation();
        Set<String> sources = endpointIdentities(relation.sourceKey(), expected, quality);
        Set<String> targets = endpointIdentities(relation.targetKey(), expected, quality);
        String type = normalizeIdentity(normalizeType(relation.type()));
        boolean allowReverse = miss.relationFacet() == RelationMissFacet.REVERSED_DIRECTION;
        return events.stream().filter(event -> relationEventMatches(
                event, sources, targets, type, allowReverse, miss.relationFacet())).toList();
    }

    private static Set<String> entityIdentities(ExpectedEntity expected, Report quality) {
        if (expected == null) {
            return Set.of();
        }
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        addIdentity(identities, expected.key());
        addIdentity(identities, entityAtom(expected.key()));
        addIdentity(identities, expected.label());
        expected.aliases().forEach(alias -> addIdentity(identities, alias));
        quality.entityMatches().stream()
                .filter(match -> expected.key().equals(match.expectedKey()))
                .forEach(match -> {
                    addIdentity(identities, match.actualId());
                    addIdentity(identities, match.actualLabel());
                });
        return Set.copyOf(identities);
    }

    private static Set<String> endpointIdentities(
            String key, ExpectedGraph expected, Report quality) {
        ExpectedEntity endpoint = expected.entities().stream()
                .filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
        return entityIdentities(endpoint, quality);
    }

    private static boolean eventMatchesAnyIdentity(
            GraphDecisionTraceEvent event, Set<String> identities) {
        if (event == null || identities.isEmpty()) {
            return false;
        }
        String observedAtom = normalizeIdentity(event.atom());
        if (identities.contains(observedAtom)) {
            return true;
        }
        // Candidate ids on a relation event name its endpoints and must not be mistaken for an
        // entity-loss decision. Fall back to candidate/metadata identity only when the producer did
        // not provide an atom at all.
        if (!observedAtom.isBlank()) {
            return false;
        }
        for (String candidate : event.candidateIds()) {
            if (identities.contains(normalizeIdentity(candidate))) {
                return true;
            }
        }
        for (Map.Entry<String, String> metadata : event.metadata().entrySet()) {
            if (identities.contains(normalizeIdentity(metadata.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean relationEventMatches(
            GraphDecisionTraceEvent event,
            Set<String> sources,
            Set<String> targets,
            String relationType,
            boolean allowReverse,
            RelationMissFacet facet) {
        if (event == null) {
            return false;
        }
        String atom = normalizeIdentity(event.atom());
        Set<String> candidates = new LinkedHashSet<>();
        event.candidateIds().forEach(value -> candidates.add(normalizeIdentity(value)));
        boolean source = containsIdentity(atom, candidates, sources);
        boolean target = containsIdentity(atom, candidates, targets);
        boolean endpoints = source && target;
        if (!endpoints && allowReverse) {
            endpoints = containsIdentity(atom, candidates, targets)
                    && containsIdentity(atom, candidates, sources);
        }
        if (!endpoints) {
            return false;
        }
        boolean type = containsToken(atom, relationType)
                || event.metadata().values().stream()
                .map(GraphQualityScorer::normalizeIdentity)
                .anyMatch(value -> value.equals(relationType));
        if (facet == RelationMissFacet.WRONG_TYPE
                && event.reason() == GraphMissReason.RELATION_WRONG_TYPE) {
            return true;
        }
        return type;
    }

    private static boolean containsIdentity(
            String atom, Set<String> candidates, Set<String> identities) {
        for (String identity : identities) {
            if (identity.isBlank()) {
                continue;
            }
            if (candidates.contains(identity) || containsToken(atom, identity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsToken(String value, String token) {
        if (value == null || token == null || token.isBlank()) {
            return false;
        }
        return (" " + value + " ").contains(" " + token + " ");
    }

    private static void addIdentity(Set<String> identities, String value) {
        String normalized = normalizeIdentity(value);
        if (!normalized.isBlank()) {
            identities.add(normalized);
        }
    }

    private static String normalizeIdentity(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}