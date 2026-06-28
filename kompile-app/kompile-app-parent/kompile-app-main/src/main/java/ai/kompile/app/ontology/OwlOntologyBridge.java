/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ontology;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlClass;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlDataProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlIri;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlObjectProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bridges a {@link OntologySchema} (the process-engine schema model) to an {@link OwlOntology}
 * (the reasoning-lib TBox model) so that the OWL 2 RL reasoner can operate over it.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>{@link EntityTypeDefinition} → {@link OwlClass} (IRI via {@link OwlIri#classIri})</li>
 *   <li>{@link RelationshipTypeDefinition} → {@link OwlObjectProperty} with domain + range
 *       taken from {@code sourceEntityType} / {@code targetEntityType}</li>
 *   <li>{@link FieldDefinition fields} on each entity type → {@link OwlDataProperty} with the
 *       entity type as domain; XSD range derived from {@link FieldDefinition#getType()}</li>
 *   <li>No sub-class axioms are present in the current schema model; they are left to the OWL RL
 *       reasoner to infer transitively if cardinality/domain-range rules produce them.</li>
 * </ul>
 *
 * <p>This class is infra-free on the reasoning-lib side — it only builds the TBox; the ABox
 * (graph entity/relation instances) is an empty graph, making the reasoner run purely over the
 * TBox axioms. For the REST surface this is the correct mode: we want to expose what the ontology
 * <em>implies</em> structurally, not what any specific graph instance looks like.</p>
 */
@Component
public class OwlOntologyBridge {

    private static final Logger log = LoggerFactory.getLogger(OwlOntologyBridge.class);

    /**
     * Convert {@code schema} to an {@link OwlOntology} TBox.
     *
     * @param schema the process-engine OntologySchema (never {@code null})
     * @return a populated TBox ready for {@link ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner}
     */
    public OwlOntology toOwlOntology(OntologySchema schema) {
        String ontIri = OwlIri.ontologyIri(schema.getId() != null ? schema.getId() : "unnamed");
        OwlOntology.Builder builder = OwlOntology.of(ontIri);

        // ── Entity types → OWL classes ────────────────────────────────────────────
        List<EntityTypeDefinition> entityTypes = schema.getEntityTypes();
        if (entityTypes != null) {
            for (EntityTypeDefinition et : entityTypes) {
                if (et.getName() == null || et.getName().isBlank()) continue;
                String classIri = OwlIri.classIri(et.getName());
                OwlClass.Builder classBuilder = OwlClass.of(classIri);
                builder.addClass(classBuilder.build());

                // Fields → data properties scoped to this entity-type class
                List<FieldDefinition> fields = et.getFields();
                if (fields != null) {
                    for (FieldDefinition field : fields) {
                        if (field.getName() == null || field.getName().isBlank()) continue;
                        String propIri = OwlIri.propIri(et.getName() + "_" + field.getName());
                        OwlDataProperty.Builder dpBuilder = OwlDataProperty.of(propIri)
                                .domain(classIri)
                                .range(xsdRangeFor(field));
                        if (field.isRequired()) {
                            // functional: required fields appear at most once
                            dpBuilder.functional(true);
                        }
                        builder.addDataProperty(dpBuilder.build());
                    }
                }
            }
        }

        // ── Relationship types → OWL object properties ────────────────────────────
        List<RelationshipTypeDefinition> relTypes = schema.getRelationshipTypes();
        if (relTypes != null) {
            for (RelationshipTypeDefinition rt : relTypes) {
                if (rt.getType() == null || rt.getType().isBlank()) continue;
                String propIri = OwlIri.propIri(rt.getType());
                OwlObjectProperty.Builder propBuilder = OwlObjectProperty.of(propIri);

                // Domain: source entity type → OwlClass IRI
                if (rt.getSourceEntityType() != null && !rt.getSourceEntityType().isBlank()) {
                    propBuilder.domain(OwlIri.classIri(rt.getSourceEntityType()));
                }
                // Range: target entity type → OwlClass IRI
                if (rt.getTargetEntityType() != null && !rt.getTargetEntityType().isBlank()) {
                    propBuilder.range(OwlIri.classIri(rt.getTargetEntityType()));
                }
                // Part-of / composition / chain relations → owl:TransitiveProperty, so the OWL-RL
                // reasoner computes their transitive closure (has-a navigation + derived PSL rules).
                if (rt.isTransitive()) {
                    propBuilder.transitive(true);
                }

                builder.addObjectProperty(propBuilder.build());
            }
        }

        log.debug("OwlOntologyBridge: built TBox from schema '{}' — {} classes, {} object properties",
                schema.getId(),
                entityTypes  == null ? 0 : entityTypes.size(),
                relTypes == null ? 0 : relTypes.size());

        return builder.build();
    }

    /**
     * Map a {@link FieldDefinition} field type to an XSD datatype IRI string.
     * Uses the XSD constants on {@link OwlIri}.
     * Covers the {@link ai.kompile.process.ontology.FieldType} enum values:
     * STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, ENUM_ARRAY, MAP.
     */
    private static String xsdRangeFor(FieldDefinition field) {
        if (field.getType() == null) return OwlIri.XSD_STRING;
        return switch (field.getType()) {
            case INTEGER  -> OwlIri.XSD_INTEGER;
            case DECIMAL  -> OwlIri.XSD_DECIMAL;
            case BOOLEAN  -> OwlIri.XSD_BOOLEAN;
            case DATE     -> OwlIri.XSD_DATE;
            case DATETIME -> OwlIri.XSD_DATETIME;
            // STRING, ENUM, ENUM_ARRAY, MAP → xsd:string (all text-like)
            default       -> OwlIri.XSD_STRING;
        };
    }
}
