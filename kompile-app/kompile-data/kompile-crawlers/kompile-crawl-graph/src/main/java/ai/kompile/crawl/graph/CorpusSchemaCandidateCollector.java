package ai.kompile.crawl.graph;

import ai.kompile.knowledgegraph.service.ConceptExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic collector for corpus schema candidates based on existing concept extraction output.
 */
final class CorpusSchemaCandidateCollector {

    private static final int MAX_CANDIDATES = 180;
    private static final int MAX_PASSAGE_IDS_PER_CANDIDATE = 16;
    private static final double MIN_CONCEPT_CONFIDENCE = 0.45d;
    private static final double MIN_RELATION_STRENGTH = 0.22d;

    private CorpusSchemaCandidateCollector() {
    }

    static CorpusSchemaCandidates.Inventory collect(
            Map<String, ConceptExtractor.ExtractionResult> results) {
        if (results == null || results.isEmpty()) {
            return new CorpusSchemaCandidates.Inventory(List.of(), List.of());
        }

        record NodeAccumulator(
                String candidateKey,
                Set<String> surfaceForms,
                Set<String> categories,
                int supportCount,
                double maximumConfidence,
                Set<String> passageIds,
                Set<String> exampleContexts) {
        }

        record RelationshipAccumulator(
                String candidateKey,
                Set<String> surfaceForms,
                Set<String> sourceConcepts,
                Set<String> targetConcepts,
                int supportCount,
                double maximumStrength,
                Set<String> passageIds) {
        }

        Map<String, NodeAccumulator> nodeCandidates = new LinkedHashMap<>();
        Map<String, RelationshipAccumulator> relationshipCandidates = new LinkedHashMap<>();

        List<Map.Entry<String, ConceptExtractor.ExtractionResult>> orderedEntries = results.entrySet().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(entry ->
                        entry.getKey() == null ? "" : entry.getKey()))
                .toList();

        for (Map.Entry<String, ConceptExtractor.ExtractionResult> entry : orderedEntries) {
            String passageId = entry.getKey();
            ConceptExtractor.ExtractionResult extraction = entry.getValue();
            if (extraction == null) {
                continue;
            }
            if (extraction.concepts() != null) {
                for (ConceptExtractor.ExtractedConcept concept : extraction.concepts()) {
                    if (concept == null) {
                        continue;
                    }

                    String category = normalizedCategory(concept.category());
                    // Preserve extractor-provided ontology categories (for example PERSON or
                    // APPROVAL_ROLE) instead of rigidly accepting only generic ENTITY/TOPIC/THEME.
                    if (category.isBlank()
                            || !Double.isFinite(concept.confidence())
                            || concept.confidence() < MIN_CONCEPT_CONFIDENCE) {
                        continue;
                    }

                    String preferredName = hasText(concept.normalizedName())
                            ? concept.normalizedName()
                            : concept.name();
                    if (!hasText(preferredName)) {
                        continue;
                    }
                    String groupingKey = groupingKey(preferredName);

                    NodeAccumulator existing = nodeCandidates.get(groupingKey);
                    if (existing == null) {
                        existing = new NodeAccumulator(preferredName.trim(),
                                new LinkedHashSet<>(),
                                new LinkedHashSet<>(),
                                0,
                                0.0d,
                                new LinkedHashSet<>(),
                                new LinkedHashSet<>());
                        nodeCandidates.put(groupingKey, existing);
                    }

                    LinkedHashSet<String> surfaceForms = new LinkedHashSet<>(existing.surfaceForms());
                    addDistinctText(surfaceForms, concept.name());
                    addDistinctText(surfaceForms, concept.normalizedName());

                    LinkedHashSet<String> categories = new LinkedHashSet<>(existing.categories());
                    categories.add(category);

                    LinkedHashSet<String> observedPassages = new LinkedHashSet<>(existing.passageIds());
                    addDistinctLimited(observedPassages, passageId, MAX_PASSAGE_IDS_PER_CANDIDATE);

                    LinkedHashSet<String> contexts = new LinkedHashSet<>(existing.exampleContexts());
                    addDistinctLimited(contexts, concept.context(), 3);

                    double confidence = concept.confidence();
                    double maxConfidence = existing.maximumConfidence();
                    if (Double.isFinite(confidence) && confidence > maxConfidence) {
                        maxConfidence = confidence;
                    }

                    int frequency = Math.max(1, concept.frequency());
                    int supportCount = existing.supportCount() + frequency;

                    nodeCandidates.put(groupingKey, new NodeAccumulator(
                            existing.candidateKey(),
                            surfaceForms,
                            categories,
                            supportCount,
                            maxConfidence,
                            observedPassages,
                            contexts
                    ));
                }
            }

            if (extraction.relationships() != null) {
                for (ConceptExtractor.ConceptRelationship relation : extraction.relationships()) {
                    if (relation == null) {
                        continue;
                    }
                    String type = relation.relationshipType();
                    if (!hasText(type)
                            || !Double.isFinite(relation.strength())
                            || relation.strength() < MIN_RELATION_STRENGTH) {
                        continue;
                    }

                    String groupingKey = groupingKey(type);
                    RelationshipAccumulator existing = relationshipCandidates.get(groupingKey);
                    if (existing == null) {
                        existing = new RelationshipAccumulator(type.trim(),
                                new LinkedHashSet<>(),
                                new LinkedHashSet<>(),
                                new LinkedHashSet<>(),
                                0,
                                0.0d,
                                new LinkedHashSet<>());
                        relationshipCandidates.put(groupingKey, existing);
                    }

                    LinkedHashSet<String> surfaceForms = new LinkedHashSet<>(existing.surfaceForms());
                    addDistinctText(surfaceForms, type);

                    LinkedHashSet<String> sourceConcepts = new LinkedHashSet<>(existing.sourceConcepts());
                    addDistinctText(sourceConcepts, relation.sourceConcept());

                    LinkedHashSet<String> targetConcepts = new LinkedHashSet<>(existing.targetConcepts());
                    addDistinctText(targetConcepts, relation.targetConcept());

                    LinkedHashSet<String> observedPassages = new LinkedHashSet<>(existing.passageIds());
                    addDistinctLimited(observedPassages, passageId, MAX_PASSAGE_IDS_PER_CANDIDATE);

                    double strength = relation.strength();
                    double maxStrength = existing.maximumStrength();
                    if (Double.isFinite(strength) && strength > maxStrength) {
                        maxStrength = strength;
                    }

                    relationshipCandidates.put(groupingKey, new RelationshipAccumulator(
                            existing.candidateKey(),
                            surfaceForms,
                            sourceConcepts,
                            targetConcepts,
                            existing.supportCount() + 1,
                            maxStrength,
                            observedPassages
                    ));
                }
            }
        }

        List<CorpusSchemaCandidates.NodeCandidate> nodes = nodeCandidates.values().stream()
                .map(acc -> new CorpusSchemaCandidates.NodeCandidate(
                        acc.candidateKey(),
                        new ArrayList<>(acc.surfaceForms()),
                        new ArrayList<>(acc.categories()),
                        acc.supportCount(),
                        acc.maximumConfidence(),
                        new ArrayList<>(acc.passageIds()),
                        new ArrayList<>(acc.exampleContexts())
                ))
                .sorted((left, right) -> {
                    int bySupport = Integer.compare(right.supportCount(), left.supportCount());
                    if (bySupport != 0) {
                        return bySupport;
                    }
                    return left.candidateKey().compareTo(right.candidateKey());
                })
                .limit(MAX_CANDIDATES)
                .toList();

        List<CorpusSchemaCandidates.RelationshipCandidate> relations = relationshipCandidates.values().stream()
                .map(acc -> new CorpusSchemaCandidates.RelationshipCandidate(
                        acc.candidateKey(),
                        new ArrayList<>(acc.surfaceForms()),
                        new ArrayList<>(acc.sourceConcepts()),
                        new ArrayList<>(acc.targetConcepts()),
                        acc.supportCount(),
                        acc.maximumStrength(),
                        new ArrayList<>(acc.passageIds())
                ))
                .sorted((left, right) -> {
                    int bySupport = Integer.compare(right.supportCount(), left.supportCount());
                    if (bySupport != 0) {
                        return bySupport;
                    }
                    return left.candidateKey().compareTo(right.candidateKey());
                })
                .limit(MAX_CANDIDATES)
                .toList();

        return new CorpusSchemaCandidates.Inventory(nodes, relations);
    }

    private static String groupingKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedCategory(String category) {
        return hasText(category) ? category.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static void addDistinctText(Set<String> values, String value) {
        if (hasText(value)) {
            values.add(value.trim());
        }
    }

    private static void addDistinctLimited(Set<String> values, String value, int maxSize) {
        if (!hasText(value)) {
            return;
        }
        if (values.size() >= maxSize) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty()) {
            values.add(normalized);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
