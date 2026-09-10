/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned JSON codec for canonical theories produced by {@link RelationalMTheoryBuilder}. */
public final class RelationalMTheoryArtifactCodec {

    public static final String FORMAT = "kompile-relational-mtheory";
    public static final int VERSION = 1;
    private static final int MAX_RELATIONS = 10_000;
    private static final int MAX_ENTITY_IDS = 1_000_000;

    private RelationalMTheoryArtifactCodec() { }

    public static String toJson(MTheory theory) {
        List<Map<String, Object>> relations = canonicalRelationRows(theory);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FORMAT);
        payload.put("version", VERSION);
        payload.put("builder", "relational-v1");
        payload.put("theoryName", theory.getName());
        payload.put("relations", relations);
        return MiniJson.write(payload);
    }

    private static List<Map<String, Object>> canonicalRelationRows(MTheory theory) {
        if (theory == null) throw new IllegalArgumentException("theory is required");
        MFrag relevance = theory.getMFrag("EntityRelevance");
        RandomVariable relevanceRv = relevance == null || relevance.getResidentNodes().isEmpty()
                ? null : relevance.getResidentNodes().get(0);
        if (relevance == null || relevance.getResidentNodes().size() != 1
                || relevanceRv == null
                || !RelationalMTheoryBuilder.RELEVANCE_RV.equals(relevanceRv.getName())
                || relevanceRv.getArity() != 1
                || relevanceRv.getArgumentTypes().size() != 1
                || !RelationalMTheoryBuilder.ALL_NODES_TYPE.equals(
                relevanceRv.getArgumentTypes().get(0).getTypeName())
                || !relevanceRv.getArgVars().equals(
                List.of(RelationalMTheoryBuilder.ALL_NODES_TYPE + "_0"))
                || !relevanceRv.getStates().equals(List.of("FALSE", "TRUE"))
                || relevance.getContextConstraints().size() != 1
                || !Constraints.entityExists(RelationalMTheoryBuilder.ALL_NODES_TYPE + "_0").describe()
                .equals(relevance.getContextConstraints().get(0).describe())
                || relevance.getLocalDistribution() != null
                || relevance.getDefaultDistribution() != null) {
            throw new IllegalArgumentException("Theory is not canonical relational-v1");
        }
        List<Map<String, Object>> relations = new ArrayList<>();
        theory.getMFrags().stream()
                .filter(frag -> !"EntityRelevance".equals(frag.getName()))
                .sorted(Comparator.comparing(MFrag::getName))
                .forEach(frag -> relations.add(relationRow(frag)));
        if (relations.size() > MAX_RELATIONS) {
            throw new IllegalArgumentException("Too many MEBN relations: " + relations.size());
        }
        validateEntityDomains(theory, relations);
        return relations;
    }

    /**
     * Rebuild a detached canonical theory whose entity domains are restricted to the supplied IDs.
     * Relation fragments and their learned strengths are preserved; the source theory is never
     * mutated. This is the query-time projection used to bound SSBN grounding for portable theories.
     */
    public static MTheory restrictToEntityIds(MTheory theory, Collection<String> allowedEntityIds) {
        Objects.requireNonNull(theory, "theory");
        Set<String> allowed = new LinkedHashSet<>();
        if (allowedEntityIds != null) {
            allowedEntityIds.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !id.isBlank())
                    .forEach(allowed::add);
        }

        List<RelationalMTheoryBuilder.RelationDescriptor> descriptors = canonicalRelationRows(theory).stream()
                .map(row -> restrictedDescriptor(row, allowed))
                .toList();
        MTheory restricted = RelationalMTheoryBuilder.build(theory.getName(), descriptors);
        MTheoryValidator.validateOrThrow(restricted);
        return restricted;
    }

    public static MTheory fromJson(String json) {
        Object decoded = MiniJson.parse(json);
        if (!(decoded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("MEBN artifact must be a JSON object");
        }
        if (!FORMAT.equals(string(root.get("format")))) {
            throw new IllegalArgumentException("Unsupported MEBN artifact format");
        }
        if (integer(root.get("version")) != VERSION) {
            throw new IllegalArgumentException("Unsupported MEBN artifact version");
        }
        if (!"relational-v1".equals(string(root.get("builder")))) {
            throw new IllegalArgumentException("Unsupported MEBN builder contract");
        }
        String theoryName = required(root.get("theoryName"), "theoryName");
        Object relationValue = root.get("relations");
        if (!(relationValue instanceof List<?> rows) || rows.size() > MAX_RELATIONS) {
            throw new IllegalArgumentException("Invalid MEBN relation list");
        }
        List<RelationalMTheoryBuilder.RelationDescriptor> descriptors = new ArrayList<>();
        Set<String> relationNames = new LinkedHashSet<>();
        int entityCount = 0;
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("MEBN relation must be an object");
            }
            List<String> sources = strings(row.get("sourceEntityIds"));
            List<String> targets = strings(row.get("targetEntityIds"));
            entityCount += sources.size() + targets.size();
            if (entityCount > MAX_ENTITY_IDS) throw new IllegalArgumentException("Too many MEBN entity IDs");
            double strength = number(row.get("strength"));
            if (!Double.isFinite(strength) || strength < 0.0 || strength > 1.0) {
                throw new IllegalArgumentException("Invalid MEBN relation strength");
            }
            String relationName = required(row.get("name"), "name");
            if (!relationNames.add(relationName)) {
                throw new IllegalArgumentException("Duplicate MEBN relation name: " + relationName);
            }
            descriptors.add(new RelationalMTheoryBuilder.RelationDescriptor(
                    relationName,
                    required(row.get("sourceType"), "sourceType"),
                    required(row.get("targetType"), "targetType"),
                    strength, sources, targets));
        }
        MTheory theory = RelationalMTheoryBuilder.build(theoryName, descriptors);
        MTheoryValidator.validateOrThrow(theory);
        return theory;
    }

    private static Map<String, Object> relationRow(MFrag frag) {
        if (frag.getResidentNodes().size() != 1) {
            throw new IllegalArgumentException("Noncanonical MFrag: " + frag.getName());
        }
        RandomVariable resident = frag.getResidentNodes().get(0);
        if (resident.getArity() != 2 || resident.getArgumentTypes().size() != 2
                || frag.getParentsOf(resident.getName()).size() != 1
                || !frag.getParentsOf(resident.getName()).contains(RelationalMTheoryBuilder.RELEVANCE_RV)
                || frag.getInputNodes().size() != 1
                || !RelationalMTheoryBuilder.RELEVANCE_RV.equals(frag.getInputNodes().get(0).getName())
                || frag.getContextConstraints().size() != 4
                || !frag.getName().equals(resident.getName())
                || !resident.getStates().equals(List.of("FALSE", "TRUE"))
                || !frag.getInputNodes().get(0).getStates().equals(List.of("FALSE", "TRUE"))
                || frag.getLocalDistribution() != null || frag.getDefaultDistribution() != null) {
            throw new IllegalArgumentException("Noncanonical relational MFrag: " + frag.getName());
        }
        EntityType source = resident.getArgumentTypes().get(0);
        EntityType target = resident.getArgumentTypes().get(1);
        if (source.getSuperType() == null || target.getSuperType() == null
                || !RelationalMTheoryBuilder.ALL_NODES_TYPE.equals(source.getSuperType().getTypeName())
                || !RelationalMTheoryBuilder.ALL_NODES_TYPE.equals(target.getSuperType().getTypeName())
                || !resident.getArgVars().equals(List.of(source.getTypeName() + "_0", target.getTypeName() + "_1"))
                || !frag.getInputNodes().get(0).getArgVars().equals(List.of(source.getTypeName() + "_0"))) {
            throw new IllegalArgumentException("Noncanonical relational types: " + frag.getName());
        }
        List<String> expectedContexts = List.of(
                Constraints.hasType(source.getTypeName() + "_0", source.getTypeName()).describe(),
                Constraints.hasType(target.getTypeName() + "_1", target.getTypeName()).describe(),
                Constraints.notEqual(source.getTypeName() + "_0", target.getTypeName() + "_1").describe(),
                Constraints.edgeExists(source.getTypeName() + "_0", target.getTypeName() + "_1").describe());
        if (!frag.getContextConstraints().stream().map(c -> c.describe()).toList().equals(expectedContexts)) {
            throw new IllegalArgumentException("Noncanonical relational constraints: " + frag.getName());
        }
        double strength = frag.getEdgeStrength(RelationalMTheoryBuilder.RELEVANCE_RV, resident.getName());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", resident.getName());
        row.put("sourceType", source.getTypeName());
        row.put("targetType", target.getTypeName());
        row.put("strength", strength);
        row.put("sourceEntityIds", source.getEntityIds().stream().sorted().toList());
        row.put("targetEntityIds", target.getEntityIds().stream().sorted().toList());
        return row;
    }

    private static RelationalMTheoryBuilder.RelationDescriptor restrictedDescriptor(
            Map<String, Object> row, Set<String> allowed) {
        List<String> sources = strings(row.get("sourceEntityIds")).stream()
                .filter(allowed::contains)
                .toList();
        List<String> targets = strings(row.get("targetEntityIds")).stream()
                .filter(allowed::contains)
                .toList();
        return new RelationalMTheoryBuilder.RelationDescriptor(
                required(row.get("name"), "name"),
                required(row.get("sourceType"), "sourceType"),
                required(row.get("targetType"), "targetType"),
                number(row.get("strength")), sources, targets);
    }

    private static void validateEntityDomains(MTheory theory, List<Map<String, Object>> relations) {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put(RelationalMTheoryBuilder.ALL_NODES_TYPE, new LinkedHashSet<>());
        for (Map<String, Object> row : relations) {
            String sourceType = required(row.get("sourceType"), "sourceType");
            String targetType = required(row.get("targetType"), "targetType");
            List<String> sources = strings(row.get("sourceEntityIds"));
            List<String> targets = strings(row.get("targetEntityIds"));
            expected.computeIfAbsent(sourceType, ignored -> new LinkedHashSet<>()).addAll(sources);
            expected.computeIfAbsent(targetType, ignored -> new LinkedHashSet<>()).addAll(targets);
            expected.get(RelationalMTheoryBuilder.ALL_NODES_TYPE).addAll(sources);
            expected.get(RelationalMTheoryBuilder.ALL_NODES_TYPE).addAll(targets);
        }
        Set<String> actualNames = theory.getEntityTypes().stream()
                .map(EntityType::getTypeName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!actualNames.equals(expected.keySet())) {
            throw new IllegalArgumentException("Noncanonical relational entity type inventory");
        }
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            EntityType actual = theory.getEntityType(entry.getKey());
            if (actual == null || !actual.getEntityIds().equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Noncanonical relational entity domain: " + entry.getKey());
            }
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected string array");
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) result.add(required(item, "entityId"));
        return result;
    }

    private static String required(Object value, String name) {
        String text = string(value);
        if (text == null || text.isBlank()) throw new IllegalArgumentException(name + " is required");
        return text;
    }

    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
