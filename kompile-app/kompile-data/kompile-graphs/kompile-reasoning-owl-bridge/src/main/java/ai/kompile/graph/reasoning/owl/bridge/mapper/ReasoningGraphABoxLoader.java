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
package ai.kompile.graph.reasoning.owl.bridge.mapper;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlIri;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Loads ABox assertions from a {@link ReasoningGraph} into an existing
 * {@link OWLOntology}, and conversely extracts ABox assertions from an
 * {@link OWLOntology} back into a {@link MutableReasoningGraph}.
 *
 * <p><strong>Direction 1 — Graph → OWL:</strong>
 * {@link #load(ReasoningGraph, OWLOntology, OWLOntologyManager)} converts each
 * {@link GraphEntity} into an {@link OWLNamedIndividual} with a
 * {@link OWLClassAssertionAxiom}, and each {@link GraphRelation} into an
 * {@link OWLObjectPropertyAssertionAxiom}. Used by
 * {@link ai.kompile.graph.reasoning.owl.bridge.reasoner.OwlDlReasoningBridge} before
 * running the DL reasoner.</p>
 *
 * <p><strong>Direction 2 — OWL → Graph:</strong>
 * {@link #extractAbox(OWLOntology)} projects
 * {@code OWLClassAssertionAxiom} / {@code OWLObjectPropertyAssertionAxiom} /
 * {@code OWLDataPropertyAssertionAxiom} back into a {@link MutableReasoningGraph}.
 * Used by
 * {@link ai.kompile.graph.reasoning.owl.bridge.importer.ExternalOwlImporter}
 * to capture the ABox of externally loaded ontologies.</p>
 *
 * <p>IRI minting uses {@link OwlIri} — the same helper the lib's RL reasoner uses,
 * so IDs remain consistent between RL and DL paths.</p>
 *
 * <p>This class is stateless; all methods are static.</p>
 */
public final class ReasoningGraphABoxLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ReasoningGraphABoxLoader.class);

    private ReasoningGraphABoxLoader() { /* utility class */ }

    // ─── Direction 1: ReasoningGraph → OWLOntology ABox ─────────────────────────

    /**
     * Load {@link ReasoningGraph} entities and relations as OWL ABox assertions into
     * {@code target}.
     *
     * <p>Each {@link GraphEntity} with a non-empty {@link GraphEntity#type()} becomes a
     * {@code ClassAssertion(classIri(type), indIri(id))}. Each {@link GraphRelation} becomes an
     * {@code ObjectPropertyAssertion(propIri(type), indIri(sourceId), indIri(targetId))}.</p>
     *
     * @param graph  the ABox source (never {@code null})
     * @param target the ontology to add assertions into (never {@code null})
     * @param mgr    the manager that owns {@code target} (never {@code null})
     */
    public static void load(ReasoningGraph graph, OWLOntology target, OWLOntologyManager mgr) {
        OWLDataFactory df = mgr.getOWLDataFactory();

        for (GraphEntity entity : graph.entities()) {
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(entity.id())));
            mgr.addAxiom(target, df.getOWLDeclarationAxiom(ind));

            String type = entity.type();
            if (type != null && !type.isEmpty()) {
                OWLClass cls = df.getOWLClass(IRI.create(OwlIri.classIri(type)));
                mgr.addAxiom(target, df.getOWLClassAssertionAxiom(cls, ind));
            }
        }

        for (GraphRelation relation : graph.relations()) {
            OWLNamedIndividual src = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(relation.sourceId())));
            OWLNamedIndividual tgt = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(relation.targetId())));
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(OwlIri.propIri(relation.type())));
            mgr.addAxiom(target, df.getOWLObjectPropertyAssertionAxiom(prop, src, tgt));
        }
    }

    // ─── Direction 2: OWLOntology ABox → ReasoningGraph ─────────────────────────

    /**
     * Extract ABox assertions from an {@link OWLOntology} into a fresh
     * {@link MutableReasoningGraph}.
     *
     * <ul>
     *   <li>{@code OWLClassAssertionAxiom(C, i)} → entity with {@code id=localName(i)},
     *       {@code type=localName(C)}</li>
     *   <li>{@code OWLObjectPropertyAssertionAxiom(P, i, j)} → directed relation with
     *       {@code type=localName(P)}, {@code sourceId=localName(i)},
     *       {@code targetId=localName(j)}</li>
     *   <li>{@code OWLDataPropertyAssertionAxiom} — skipped (no GraphRelation analogue for
     *       literal values; callers can inspect the ontology directly if needed)</li>
     * </ul>
     *
     * @param owlOnt the ontology to extract ABox from (never {@code null})
     * @return a new {@link MutableReasoningGraph} containing the ABox individuals and relations
     */
    public static MutableReasoningGraph extractAbox(OWLOntology owlOnt) {
        MutableReasoningGraph graph = new MutableReasoningGraph();

        for (OWLAxiom axiom : owlOnt.getAxioms()) {
            if (axiom instanceof OWLClassAssertionAxiom caa) {
                if (caa.getIndividual().isNamed() && caa.getClassExpression().isNamed()) {
                    String indId  = localName(caa.getIndividual().asOWLNamedIndividual().getIRI());
                    String clsName = localName(caa.getClassExpression().asOWLClass().getIRI());
                    // Add entity if not already present (class assertion may come after a previous one)
                    if (graph.entity(indId).isEmpty()) {
                        graph.addEntity(SimpleGraphEntity.of(indId, clsName, indId));
                    }
                    // If entity was added with empty type, update via re-add with type
                    // (MutableReasoningGraph.addEntity replaces by id)
                    else {
                        String existingType = graph.entity(indId).get().type();
                        if (existingType == null || existingType.isEmpty()) {
                            graph.addEntity(SimpleGraphEntity.of(indId, clsName, indId));
                        }
                    }
                }

            } else if (axiom instanceof OWLObjectPropertyAssertionAxiom opaa) {
                if (opaa.getSubject().isNamed()
                        && opaa.getObject().isNamed()
                        && opaa.getProperty().isNamed()) {
                    String srcId   = localName(opaa.getSubject().asOWLNamedIndividual().getIRI());
                    String tgtId   = localName(opaa.getObject().asOWLNamedIndividual().getIRI());
                    String relType = localName(opaa.getProperty().getNamedProperty().getIRI());
                    String relId   = "abox-" + relType + "-" + srcId + "-" + tgtId;
                    // Ensure source/target entities exist
                    if (graph.entity(srcId).isEmpty()) graph.addEntity(SimpleGraphEntity.of(srcId));
                    if (graph.entity(tgtId).isEmpty()) graph.addEntity(SimpleGraphEntity.of(tgtId));
                    graph.addRelation(SimpleGraphRelation.directed(relId, srcId, tgtId, relType, 1.0));
                }

            } else if (axiom instanceof OWLDataPropertyAssertionAxiom) {
                LOG.debug("[ReasoningGraphABoxLoader] Skipping OWLDataPropertyAssertionAxiom (no literal-value analogue in ReasoningGraph)");
            }
        }

        return graph;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Derive local name from an IRI (fragment after {@code #}, or last path segment after {@code /}). */
    private static String localName(IRI iri) {
        String str = iri.toString();
        int hash = str.lastIndexOf('#');
        if (hash >= 0 && hash < str.length() - 1) return str.substring(hash + 1);
        int slash = str.lastIndexOf('/');
        if (slash >= 0 && slash < str.length() - 1) return str.substring(slash + 1);
        return str;
    }
}
