/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.ontology;

import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.List;

/**
 * Bridges a declarative {@link OntologySchema} onto the graph-reasoning {@link TypeRegistry} /
 * {@link TypeHierarchy} so MEBN/SSBN grounding can navigate is-a (subsumption) using the schema's
 * declared {@link EntityTypeDefinition#getParentType() parent types}.
 *
 * <p>This is an infra-light <em>adapter</em>: it reads the app-side schema and produces the
 * generic, infra-free reasoning primitives. The schema supplies the is-a structure; entity
 * membership is computed by {@link TypeRegistry#buildFor(ReasoningGraph)} from the supplied graph.
 * Hand the resulting hierarchy to {@code SSBNGenerator.typeHierarchy(...)} to ground an RV declared
 * over a supertype across all of its subtypes' instances.</p>
 */
public final class OntologySchemaTypeRegistry {

    private OntologySchemaTypeRegistry() {
    }

    /**
     * Build a {@link TypeRegistry} from the schema's entity types and their declared
     * {@code parentType} (subClassOf) links. Types with no parent are still declared so they
     * appear as roots; self-referential parents are ignored.
     *
     * @param schema the ontology schema ({@code null} → empty registry)
     * @return a registry carrying the schema's is-a declarations (membership is derived later)
     */
    public static TypeRegistry toRegistry(OntologySchema schema) {
        TypeRegistry registry = new TypeRegistry();
        if (schema == null || schema.getEntityTypes() == null) {
            return registry;
        }
        List<EntityTypeDefinition> types = schema.getEntityTypes();
        // First pass: declare every type, so a parent referenced before its own definition resolves.
        for (EntityTypeDefinition etd : types) {
            if (etd != null && etd.getName() != null) {
                registry.declare(etd.getName());
            }
        }
        // Second pass: wire is-a links.
        for (EntityTypeDefinition etd : types) {
            if (etd == null || etd.getName() == null) {
                continue;
            }
            String parent = etd.getParentType();
            if (parent != null && !parent.isBlank() && !parent.equalsIgnoreCase(etd.getName())) {
                registry.subtype(etd.getName(), parent);
            }
        }
        return registry;
    }

    /**
     * Build a {@link TypeHierarchy} from the schema's is-a declarations merged with entity
     * membership computed from {@code graph}.
     *
     * @param schema the ontology schema ({@code null} → hierarchy with computed membership only)
     * @param graph  the source graph providing entity membership (never {@code null})
     * @return a fully merged hierarchy, ready for {@code SSBNGenerator.typeHierarchy(...)}
     */
    public static TypeHierarchy toHierarchy(OntologySchema schema, ReasoningGraph graph) {
        return toRegistry(schema).buildFor(graph);
    }
}
