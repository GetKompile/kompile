/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers ontology candidates only from explicit grammatical assertions in corpus text.
 *
 * <p>This is deliberately domain-neutral. It recognizes a named subject explicitly classified by
 * an {@code X is a Y} assertion and a short predicate explicitly written between two previously
 * classified names. The source supplies every emitted label; this class owns no graph vocabulary.</p>
 */
final class ExplicitAssertionSchemaInferencer {

    private static final String NAME =
            "[\\p{Lu}][\\p{L}\\p{N}'’\\-]*(?:\\s+[\\p{Lu}][\\p{L}\\p{N}'’\\-]*){0,7}";
    private static final Pattern TYPE_ASSERTION = Pattern.compile(
            "(?<name>" + NAME + ")\\s+(?:is|are)\\s+(?:(?:a|an|the)\\s+)?"
                    + "(?<type>[\\p{L}][\\p{L}\\p{N}_-]{1,47})\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern APPOSITIVE_TYPE_ASSERTION = Pattern.compile(
            "(?<name>" + NAME + ")\\s*,\\s*(?:(?:a|an|the)\\s+)"
                    + "(?<type>[\\p{L}][\\p{L}\\p{N}_-]{1,47})\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SENTENCE = Pattern.compile("[^.!?\\n]+[.!?]?");
    private static final Pattern SHORT_PREDICATE = Pattern.compile(
            "[\\p{L}][\\p{L}\\p{N}'’_-]*(?:\\s+[\\p{L}][\\p{L}\\p{N}'’_-]*){0,2}",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
    private static final Set<String> NON_RELATION_PREDICATES = Set.of(
            "AND", "OR", "WITH", "TO", "FROM", "IS", "ARE", "A", "AN", "THE");

    private ExplicitAssertionSchemaInferencer() {
    }

    static GraphSchema infer(Map<String, String> passages) {
        return analyze(passages).schema();
    }

    static Analysis analyze(Map<String, String> passages) {
        if (passages == null || passages.isEmpty()) {
            return new Analysis(null, List.of(), List.of());
        }

        Map<String, String> typeByName = new LinkedHashMap<>();
        Map<String, NodeType> nodeTypes = new TreeMap<>();
        List<String> sentences = new ArrayList<>();
        passages.values().stream()
                .filter(ExplicitAssertionSchemaInferencer::hasText)
                .forEach(text -> {
                    Matcher sentenceMatcher = SENTENCE.matcher(text);
                    while (sentenceMatcher.find()) {
                        String sentence = sentenceMatcher.group().trim();
                        if (!sentence.isEmpty()) {
                            sentences.add(sentence);
                        }
                    }
                    collectTypeAssertions(TYPE_ASSERTION, text, typeByName, nodeTypes);
                    collectTypeAssertions(APPOSITIVE_TYPE_ASSERTION, text, typeByName, nodeTypes);
                });

        if (nodeTypes.isEmpty()) {
            return new Analysis(null, List.of(), List.of());
        }

        Map<String, RelationshipType> relationshipTypes = new TreeMap<>();
        Set<String> patterns = new TreeSet<>();
        Set<NamedRelationAssertion> relationAssertions = new LinkedHashSet<>();
        for (String sentence : sentences) {
            if (TYPE_ASSERTION.matcher(sentence).find()) {
                continue;
            }
            List<NameOccurrence> names = typeByName.keySet().stream()
                    .map(name -> new NameOccurrence(name, sentence.indexOf(name)))
                    .filter(occurrence -> occurrence.offset() >= 0)
                    .sorted(Comparator.comparingInt(NameOccurrence::offset))
                    .toList();
            for (int index = 0; index + 1 < names.size(); index++) {
                NameOccurrence source = names.get(index);
                NameOccurrence target = names.get(index + 1);
                int predicateStart = source.offset() + source.name().length();
                if (predicateStart >= target.offset()) {
                    continue;
                }
                String predicateText = sentence.substring(predicateStart, target.offset()).trim();
                if (!SHORT_PREDICATE.matcher(predicateText).matches()) {
                    continue;
                }
                String relationType = schemaName(predicateText);
                if (relationType == null || NON_RELATION_PREDICATES.contains(relationType)) {
                    continue;
                }
                String sourceType = typeByName.get(source.name());
                String targetType = typeByName.get(target.name());
                relationshipTypes.putIfAbsent(relationType, new RelationshipType(
                        relationType,
                        "Directed relationship stated explicitly between classified corpus names.",
                        null));
                patterns.add("(" + sourceType + ")-[:" + relationType + "]->(" + targetType + ")");
                relationAssertions.add(new NamedRelationAssertion(
                        source.name(), target.name(), relationType));
            }
        }

        GraphSchema schema = new GraphSchema(
                List.copyOf(nodeTypes.values()),
                relationshipTypes.isEmpty() ? null : List.copyOf(relationshipTypes.values()),
                patterns.isEmpty() ? null : List.copyOf(patterns));
        List<EntityAssertion> entities = typeByName.entrySet().stream()
                .map(entry -> new EntityAssertion(entry.getKey(), entry.getValue()))
                .toList();
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int index = 0; index < entities.size(); index++) {
            indexByName.put(entities.get(index).name(), index);
        }
        List<RelationAssertion> relations = relationAssertions.stream()
                .map(relation -> new RelationAssertion(
                        indexByName.get(relation.sourceName()),
                        indexByName.get(relation.targetName()),
                        relation.type()))
                .toList();
        return new Analysis(schema, entities, relations);
    }

    private static void collectTypeAssertions(
            Pattern pattern,
            String text,
            Map<String, String> typeByName,
            Map<String, NodeType> nodeTypes) {
        Matcher assertion = pattern.matcher(text);
        while (assertion.find()) {
            String name = assertion.group("name").trim();
            String type = schemaName(assertion.group("type"));
            if (type == null) {
                continue;
            }
            typeByName.putIfAbsent(name, type);
            nodeTypes.putIfAbsent(type, new NodeType(
                    type,
                    "Type stated explicitly by a corpus classification assertion.",
                    null));
        }
    }

    private static String schemaName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
        return SCHEMA_NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Analysis(
            GraphSchema schema,
            List<EntityAssertion> entityAssertions,
            List<RelationAssertion> relationAssertions) {

        Analysis {
            entityAssertions = entityAssertions == null ? List.of() : List.copyOf(entityAssertions);
            relationAssertions =
                    relationAssertions == null ? List.of() : List.copyOf(relationAssertions);
        }

        int explicitEntityCount() {
            return entityAssertions.size();
        }

        int explicitRelationCount() {
            return relationAssertions.size();
        }
    }

    record EntityAssertion(String name, String type) {
    }

    record RelationAssertion(int source, int target, String type) {
    }

    private record NamedRelationAssertion(String sourceName, String targetName, String type) {
    }

    private record NameOccurrence(String name, int offset) {
    }
}
