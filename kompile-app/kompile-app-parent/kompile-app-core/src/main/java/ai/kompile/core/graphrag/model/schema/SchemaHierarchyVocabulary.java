/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.core.graphrag.model.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stable, domain-neutral types used to classify corpus-derived graph schema types.
 *
 * <p>Baseline entities are valid extraction types and may also be parents of more specific domain types.
 * Connection families are classification metadata for specific predicates; they are never emitted as
 * graph relationship types themselves.</p>
 */
public final class SchemaHierarchyVocabulary {

    private static final Map<String, String> ENTITY_DESCRIPTIONS = orderedMap(
            "PERSON", "A named human or human-like individual, including a fictional person.",
            "ORGANIZATION", "A formal collective actor such as a company, agency, institution, department, or team.",
            "GROUP", "A named social, national, religious, political, ethnic, or demographic collective.",
            "SOFTWARE_AGENT", "Software or an automated system acting as an accountable agent.",
            "LOCATION", "A named geographic, geopolitical, natural, built, administrative, or virtual place.",
            "EVENT", "A named or otherwise identifiable bounded occurrence.",
            "ACTIVITY", "An action or process occurring over time with possible agents, inputs, outputs, or participants.",
            "PRODUCT", "A named designed, manufactured, packaged, or marketed artifact.",
            "SERVICE", "A named standing capability or offering provided by an agent.",
            "CREATIVE_WORK", "An identifiable intellectual or creative work such as a book, film, dataset, standard, or software work.",
            "DOCUMENT", "A specific documentary or information artifact such as a report, article, email, contract, record, or file.",
            "LAW", "A named law, regulation, treaty, bill, decree, or other legal instrument.",
            "LANGUAGE", "A named natural, sign, programming, query, or other formal language.",
            "CONCEPT", "A named abstract notion, method, discipline, role, category, or formally defined term."
    );

    private static final Map<String, String> CONNECTION_DESCRIPTIONS = orderedMap(
            "IDENTITY", "Exact identity or semantic equivalence between two referents.",
            "HIERARCHY", "Taxonomic, conceptual, organizational, or reporting hierarchy.",
            "PART_WHOLE", "Physical or logical component-to-whole structure.",
            "AFFILIATION", "Membership, employment, enrollment, or other organizational affiliation.",
            "SOCIAL", "A standing interpersonal or inter-agent relationship.",
            "PARTICIPATION", "Participation by an agent or entity in an event, activity, process, or project.",
            "ATTRIBUTION", "Authorship, creation, publication, production, or responsibility attribution.",
            "OWNERSHIP", "Ownership, custody, possession, or control of a thing or right.",
            "SPATIAL", "Location, hosting, origin, destination, or other spatial relationship.",
            "COMMUNICATION", "Intentional conveyance of information from a sender to a recipient or audience.",
            "TRANSFER", "Transfer of an object, value, right, or resource between parties or places.",
            "REFERENCE", "Citation, mention, quotation, linking, or another explicit reference.",
            "DERIVATION", "Revision, adaptation, transformation, specialization, or derivation from a source.",
            "DEPENDENCY", "Use, requirement, reliance, input, support, or another dependency.",
            "CAUSATION", "A supported causal relationship from a cause to an effect.",
            "TEMPORAL", "Ordering, containment, overlap, or another semantic temporal relationship."
    );

    private static final Map<String, String> BASE_ENTITY_PARENTS = Map.of(
            "DOCUMENT", "CREATIVE_WORK",
            "LAW", "DOCUMENT");

    public static final List<String> BASE_ENTITY_TYPES = List.copyOf(ENTITY_DESCRIPTIONS.keySet());
    public static final List<String> CONNECTION_FAMILIES = List.copyOf(CONNECTION_DESCRIPTIONS.keySet());
    private static final Set<String> BASE_ENTITY_TYPE_SET = Set.copyOf(BASE_ENTITY_TYPES);
    private static final Set<String> CONNECTION_FAMILY_SET = Set.copyOf(CONNECTION_FAMILIES);

    private SchemaHierarchyVocabulary() {
    }

    public static GraphSchema baselineSchema() {
        List<NodeType> roots = ENTITY_DESCRIPTIONS.entrySet().stream()
                .map(entry -> new NodeType(entry.getKey(), entry.getValue(), null,
                        BASE_ENTITY_PARENTS.get(entry.getKey())))
                .toList();
        return new GraphSchema(roots, null, null);
    }

    /**
     * Adds missing baseline roots while preserving configured definitions as authoritative.
     */
    public static GraphSchema withBaseline(GraphSchema schema) {
        Map<String, NodeType> nodes = new LinkedHashMap<>();
        for (NodeType root : baselineSchema().getNodeTypes()) {
            nodes.put(canonical(root.getLabel()), root);
        }
        if (schema != null && schema.getNodeTypes() != null) {
            for (NodeType type : schema.getNodeTypes()) {
                if (type != null && hasText(type.getLabel())) {
                    String key = canonical(type.getLabel());
                    NodeType baseline = nodes.get(key);
                    String baselineParent = baseline == null ? null : baseline.getParentType();
                    String configuredParent = type.getParentType();
                    if (baseline != null && hasText(configuredParent)
                            && !java.util.Objects.equals(canonical(configuredParent),
                            canonical(baselineParent))) {
                        throw new IllegalArgumentException("Baseline entity type " + key
                                + " cannot change parentType from " + baselineParent
                                + " to " + configuredParent);
                    }
                    NodeType configured = copy(type);
                    if (!hasText(configured.getParentType()) && hasText(baselineParent)) {
                        configured.setParentType(baselineParent);
                    }
                    nodes.put(key, configured);
                }
            }
        }

        List<RelationshipType> relationships = null;
        if (schema != null && schema.getRelationshipTypes() != null) {
            relationships = schema.getRelationshipTypes().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(SchemaHierarchyVocabulary::copy)
                    .toList();
        }
        List<String> patterns = schema == null || schema.getPatterns() == null
                ? null
                : schema.getPatterns().stream().filter(java.util.Objects::nonNull).toList();
        return new GraphSchema(List.copyOf(nodes.values()), relationships, patterns);
    }

    public static boolean isBaseEntityType(String value) {
        return hasText(value) && BASE_ENTITY_TYPE_SET.contains(canonical(value));
    }

    public static boolean isConnectionFamily(String value) {
        return hasText(value) && CONNECTION_FAMILY_SET.contains(canonical(value));
    }

    public static Map<String, String> entityDescriptions() {
        return ENTITY_DESCRIPTIONS;
    }

    public static Map<String, String> baseEntityParents() {
        return BASE_ENTITY_PARENTS;
    }

    public static String baselineParent(String value) {
        return hasText(value) ? BASE_ENTITY_PARENTS.get(canonical(value)) : null;
    }

    /** Coarse deterministic parent used only for source-native categories that predate the LLM pass. */
    public static String parentForGeneratedType(String value) {
        if (!hasText(value)) return null;
        String type = canonical(value);
        if (isBaseEntityType(type)) return baselineParent(type);
        if (containsAny(type, "PERSON", "EMPLOYEE", "CUSTOMER", "USER", "AUTHOR",
                "REVIEWER", "APPROVER")) return "PERSON";
        if (containsAny(type, "ORGANIZATION", "COMPANY", "AGENCY", "INSTITUTION",
                "DEPARTMENT", "TEAM", "BUSINESS_UNIT")) return "ORGANIZATION";
        if (containsAny(type, "GROUP", "COMMUNITY", "DEMOGRAPHIC")) return "GROUP";
        if (containsAny(type, "SOFTWARE_AGENT", "BOT", "AUTOMATED_AGENT")) return "SOFTWARE_AGENT";
        if (containsAny(type, "LOCATION", "PLACE", "FACILITY", "GPE", "CITY", "COUNTRY",
                "REGION")) return "LOCATION";
        if (containsAny(type, "EVENT", "MEETING", "CONFERENCE", "INCIDENT")) return "EVENT";
        if (containsAny(type, "ACTIVITY", "PROCESS", "WORKFLOW", "TASK", "OPERATION")) return "ACTIVITY";
        if (containsAny(type, "PRODUCT", "DEVICE", "APPLICATION", "SOFTWARE", "MODEL")) return "PRODUCT";
        if (containsAny(type, "SERVICE", "OFFERING")) return "SERVICE";
        if (containsAny(type, "DOCUMENT", "MESSAGE", "EMAIL", "REPORT", "ARTICLE", "CONTRACT",
                "FILE", "RECORD", "PUBLICATION", "FORECAST")) return "DOCUMENT";
        if (containsAny(type, "LAW", "REGULATION", "TREATY", "BILL")) return "LAW";
        if (containsAny(type, "LANGUAGE")) return "LANGUAGE";
        return "CONCEPT";
    }

    /** Returns null when a deterministic predicate is too ambiguous to classify safely. */
    public static String connectionFamilyForPredicate(String value) {
        if (!hasText(value)) return null;
        String type = canonical(value);
        if (isConnectionFamily(type) || "HIERARCHICAL".equals(type)) return null;
        if (containsAny(type, "SAME_AS", "ALIAS", "IDENTIF")) return "IDENTITY";
        if (containsAny(type, "KNOWS", "FRIEND", "SPOUSE", "PARENT_OF", "CHILD_OF")) return "SOCIAL";
        if (containsAny(type, "IS_A", "SUBCLASS", "PARENT_ORGANIZATION", "REPORTS_TO", "SUPERVIS")) return "HIERARCHY";
        if (containsAny(type, "PART_OF", "HAS_PART", "COMPONENT", "CONTAINS")) return "PART_WHOLE";
        if (containsAny(type, "WORKS_", "EMPLOY", "MEMBER", "AFFILIAT", "HAS_ROLE")) return "AFFILIATION";
        if (containsAny(type, "PARTICIPAT", "ATTEND", "INVOLVED_IN")) return "PARTICIPATION";
        if (containsAny(type, "AUTHOR", "CREAT", "PRODUC", "PUBLISH", "EMIT")) return "ATTRIBUTION";
        if (containsAny(type, "OWN", "CONTROL", "POSSESS")) return "OWNERSHIP";
        if (containsAny(type, "LOCATED", "OCCURRED_AT", "HOSTED", "ORIGIN", "DESTINATION")) return "SPATIAL";
        if (containsAny(type, "EMAIL", "MESSAGE", "CALL", "REPLY", "SENT_", "ADDRESSED_TO")) return "COMMUNICATION";
        if (containsAny(type, "PAID", "PAYMENT", "TRANSFER", "SOLD_TO", "GAVE")) return "TRANSFER";
        if (containsAny(type, "CIT", "MENTION", "REFER", "LINK")) return "REFERENCE";
        if (containsAny(type, "DERIV", "REVISION", "VERSION_OF", "ADAPT")) return "DERIVATION";
        if (containsAny(type, "USES", "REQUIRE", "DEPENDS", "INPUT", "SUPPORT")) return "DEPENDENCY";
        if (containsAny(type, "CAUS", "RESULTED_IN")) return "CAUSATION";
        if (containsAny(type, "BEFORE", "AFTER", "DURING", "OVERLAP", "PRECED")) return "TEMPORAL";
        return null;
    }

    public static Map<String, String> connectionDescriptions() {
        return CONNECTION_DESCRIPTIONS;
    }

    public static String canonical(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static NodeType copy(NodeType type) {
        return new NodeType(type.getLabel(), type.getDescription(), copyProperties(type.getProperties()),
                type.getParentType());
    }

    private static RelationshipType copy(RelationshipType type) {
        return new RelationshipType(type.getType(), type.getDescription(),
                copyProperties(type.getProperties()), type.getAliases(), type.getConnectionFamily());
    }

    private static List<PropertyType> copyProperties(List<PropertyType> properties) {
        if (properties == null) return null;
        List<PropertyType> copy = new ArrayList<>();
        for (PropertyType property : properties) {
            if (property != null) {
                copy.add(new PropertyType(property.getName(), property.getType()));
            }
        }
        return List.copyOf(copy);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    private static Map<String, String> orderedMap(String... values) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(values[index], values[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
