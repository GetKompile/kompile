/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.mebn.type.owl;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphEntityBuilder;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Connects inferred-table typing to the OWL schema machinery.
 *
 * <p>Typed table members (rows of master tables, typed by their key column header — SKU, CHANNEL,
 * REGION) arrive as plain entity types. This bridge makes those types first-class OWL classes so
 * the existing schema layer applies to them:</p>
 *
 * <ol>
 *   <li>{@link #ontologyFromTableMembers(ReasoningGraph, OwlOntology)} declares an
 *       {@code owl:Class} for every member type found in the graph, merged UNDER a declared
 *       TBox — so an ontology stating {@code table:SKU rdfs:subClassOf schema:Product} (or
 *       equivalence, disjointness, property axioms) composes with what the tables surfaced.</li>
 *   <li>{@link #materializeInferredTypes(MutableReasoningGraph, OwlOntology)} runs the OWL 2 RL
 *       reasoner and writes every inferred class membership back onto entities under the
 *       established {@code owlInferredTypes} attribute, which
 *       {@link GraphEntity#typeMemberships()} already reads. A member typed {@code SKU} whose
 *       ontology declares {@code SKU &#8849; Product} then answers for both.</li>
 * </ol>
 *
 * <p>Inferred relations and inconsistencies are returned in the {@link OwlRlResult} untouched;
 * only class memberships are materialized here.</p>
 */
public final class TableMemberOntologyBridge {

    /** IRI prefix for classes declared from table member types; hash keeps the local name. */
    public static final String TABLE_CLASS_IRI_PREFIX = "urn:kompile:table#";

    /** UnifiedGraph artifact name carrying a declared TBox as Turtle. */
    public static final String ONTOLOGY_ARTIFACT = "ontology.ttl";

    /** Entity attribute carrying a declared TBox as Turtle, for graphs without artifacts. */
    public static final String ONTOLOGY_TURTLE_ATTRIBUTE = "ontologyTurtle";

    private TableMemberOntologyBridge() {
    }

    /**
     * Loads a TBox the graph itself carries: the {@code ontology.ttl} artifact on a
     * {@link UnifiedGraph}, or any entity's {@code ontologyTurtle} attribute. This keeps the
     * schema graph-resident and portable rather than a code-level declaration.
     */
    public static Optional<OwlOntology> declaredOntology(ReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (graph instanceof UnifiedGraph unified) {
            String turtle = unified.artifactText(ONTOLOGY_ARTIFACT);
            if (turtle != null && !turtle.isBlank()) {
                return Optional.of(new OwlTurtleReader().read(turtle));
            }
        }
        for (GraphEntity entity : graph.entities()) {
            Object turtle = entity.attributes().get(ONTOLOGY_TURTLE_ATTRIBUTE);
            if (turtle != null && !String.valueOf(turtle).isBlank()) {
                return Optional.of(new OwlTurtleReader().read(String.valueOf(turtle)));
            }
        }
        return Optional.empty();
    }

    /** One-stop: graph-resident TBox (if any) merged with table-derived classes and properties. */
    public static OwlOntology ontologyFromGraph(ReasoningGraph graph) {
        return ontologyFromTableMembers(graph, declaredOntology(graph).orElse(null));
    }

    /**
     * Declares an OWL class per table-member type present in the graph, merged with a declared
     * TBox. Declared axioms win: a class the TBox already defines (matched by local name,
     * case-insensitively) is kept as declared rather than re-declared. Member-to-member reference
     * relations (emitted by table projection when a column's values resolve to another master's
     * members, marked {@code memberReference}) become {@code owl:ObjectProperty} declarations
     * with domain and range set to the referencing and referenced member classes.
     */
    public static OwlOntology ontologyFromTableMembers(ReasoningGraph graph, OwlOntology declared) {
        Objects.requireNonNull(graph, "graph");
        OwlOntology.Builder builder = declared == null
                ? OwlOntology.of(TABLE_CLASS_IRI_PREFIX.substring(0, TABLE_CLASS_IRI_PREFIX.length() - 1))
                : OwlOntology.of(declared.ontologyIri());
        Map<String, String> classIriByLocalName = new LinkedHashMap<>();
        if (declared != null) {
            for (OwlClass owlClass : declared.classes().values()) {
                builder.addClass(owlClass);
                classIriByLocalName.putIfAbsent(
                        owlClass.localName().toLowerCase(Locale.ROOT), owlClass.classIri());
            }
            declared.objectProperties().values().forEach(builder::addObjectProperty);
            declared.dataProperties().values().forEach(builder::addDataProperty);
            declared.sameAs().forEach(builder::sameAs);
        }
        for (String memberType : memberTypes(graph)) {
            String localName = memberType.toLowerCase(Locale.ROOT);
            if (!classIriByLocalName.containsKey(localName)) {
                String iri = TABLE_CLASS_IRI_PREFIX + memberType;
                builder.addClass(OwlClass.of(iri).build());
                classIriByLocalName.put(localName, iri);
            }
        }
        // Derive classes from plain graph entity types as well (PERSON, ORGANIZATION, ...).
        // Table-member types above remain IRI-prefixed for provenance; plain types get the
        // ontology namespace so type assertions resolve cleanly during OWL 2 RL inference.
        for (GraphEntity entity : graph.entities()) {
            String entityType = entity.type();
            if (entityType == null || entityType.isBlank()) {
                continue;
            }
            String localName = entityType.toLowerCase(Locale.ROOT);
            if (!classIriByLocalName.containsKey(localName)) {
                String iri = builder.ontologyIri() + "#" + entityType;
                builder.addClass(OwlClass.of(iri).build());
                classIriByLocalName.put(localName, iri);
            }
        }

        Set<String> declaredProperties = new LinkedHashSet<>();
        if (declared != null) {
            for (OwlObjectProperty property : declared.objectProperties().values()) {
                declaredProperties.add(property.localName().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, OwlObjectProperty> referenceProperties = new LinkedHashMap<>();
        for (GraphRelation relation : graph.relations()) {
            if (!Boolean.TRUE.equals(relation.attributes().get("memberReference"))
                    && !"true".equalsIgnoreCase(String.valueOf(
                            relation.attributes().get("memberReference")))) {
                continue;
            }
            String propertyName = relation.type();
            if (propertyName == null || propertyName.isBlank()
                    || declaredProperties.contains(propertyName.toLowerCase(Locale.ROOT))
                    || referenceProperties.containsKey(propertyName)) {
                continue;
            }
            GraphEntity source = graph.entity(relation.sourceId()).orElse(null);
            GraphEntity target = graph.entity(relation.targetId()).orElse(null);
            if (source == null || target == null) {
                continue;
            }
            String domain = classIriByLocalName.get(source.type().toLowerCase(Locale.ROOT));
            String range = classIriByLocalName.get(target.type().toLowerCase(Locale.ROOT));
            if (domain == null || range == null) {
                continue;
            }
            referenceProperties.put(propertyName, OwlObjectProperty
                    .of(TABLE_CLASS_IRI_PREFIX + propertyName)
                    .domain(domain)
                    .range(range)
                    .build());
        }
        referenceProperties.values().forEach(builder::addObjectProperty);
        return builder.build();
    }

    /**
     * Runs OWL 2 RL over the graph and materializes every inferred class membership onto its
     * entity as {@code owlInferredTypes} (local names, merged, no duplicates). Returns the raw
     * reasoning result so callers can also inspect inferred relations and inconsistencies.
     */
    public static OwlRlResult materializeInferredTypes(
            MutableReasoningGraph graph, OwlOntology ontology) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(ontology, "ontology");
        OwlRlResult result = new OwlRlReasoner().reason(graph, ontology);
        for (Map.Entry<String, List<String>> inferred
                : result.inferredTypeCandidates().entrySet()) {
            GraphEntity entity = graph.entity(inferred.getKey()).orElse(null);
            if (entity == null) {
                continue;
            }
            Set<String> memberships = new LinkedHashSet<>();
            for (String membership : entity.typeMemberships()) {
                memberships.add(membership.toLowerCase(Locale.ROOT));
            }
            Set<String> added = new LinkedHashSet<>(existingInferred(entity));
            for (String classIri : inferred.getValue()) {
                String localName = OwlClass.of(classIri).build().localName();
                if (!memberships.contains(localName.toLowerCase(Locale.ROOT))) {
                    added.add(localName);
                }
            }
            if (added.equals(new LinkedHashSet<>(existingInferred(entity)))) {
                continue;
            }
            graph.addEntity(copy(entity)
                    .attribute("owlInferredTypes", List.copyOf(added))
                    .build());
        }
        return result;
    }

    private static Set<String> memberTypes(ReasoningGraph graph) {
        Set<String> types = new LinkedHashSet<>();
        for (GraphEntity entity : graph.entities()) {
            Object marker = entity.attributes().get("tableMember");
            boolean member = Boolean.TRUE.equals(marker)
                    || "true".equalsIgnoreCase(String.valueOf(marker));
            if (!member) {
                continue;
            }
            Object memberType = entity.attributes().get("memberType");
            String type = memberType == null ? entity.type() : String.valueOf(memberType);
            if (type != null && !type.isBlank()) {
                types.add(type.trim());
            }
        }
        return types;
    }

    private static List<String> existingInferred(GraphEntity entity) {
        Object raw = entity.attributes().get("owlInferredTypes");
        if (raw instanceof java.util.Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static GraphEntityBuilder copy(GraphEntity entity) {
        GraphEntityBuilder builder = GraphEntity.builder(entity.id())
                .type(entity.type())
                .label(entity.label())
                .weight(entity.weight())
                .confidence(entity.confidence())
                .tags(entity.tags())
                .attributes(entity.attributes());
        if (entity.hasEmbedding()) {
            builder.embedding(entity.embedding());
        }
        Instant timestamp = entity.timestamp();
        if (timestamp != null) {
            builder.timestamp(timestamp);
        }
        return builder;
    }
}
