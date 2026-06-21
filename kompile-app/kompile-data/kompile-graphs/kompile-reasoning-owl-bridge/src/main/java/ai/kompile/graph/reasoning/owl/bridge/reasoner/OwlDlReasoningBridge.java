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
package ai.kompile.graph.reasoning.owl.bridge.reasoner;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlInconsistency;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlIri;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.owl.bridge.mapper.OwlOntologyMapper;
import ai.kompile.graph.reasoning.owl.bridge.mapper.ReasoningGraphABoxLoader;

import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OWL DL reasoning bridge — implements {@link OwlReasoner} using a full OWL DL tableau reasoner
 * (default: Openllet, the maintained successor to Pellet) rather than the lib's forward-chaining
 * OWL RL engine.
 *
 * <h2>What this adds beyond OwlRlReasoner (the lib's RL implementation)</h2>
 * <ul>
 *   <li>Full TBox classification and subsumption (not just ABox-level cax-sco propagation)</li>
 *   <li>Satisfiability / consistency checking for all SROIQ axioms ({@code ComplementOf},
 *       {@code UnionOf}, role chains, nominals, qualified cardinalities)</li>
 *   <li>Complex class expression reasoning retained in-memory even when the lib model cannot
 *       represent them — inconsistencies still surface in {@link OwlRlResult}</li>
 * </ul>
 *
 * <h2>Known limitation — multi-class membership</h2>
 * <p>{@link OwlRlResult#inferredTypes()} is {@code Map<String, String>} (entity ID → single class
 * IRI). OWL DL reasoning can infer membership in multiple classes per individual; only the
 * <em>first</em> inferred class not already asserted is stored per entity. The full set of
 * inferred types is available via the OWL API reasoner directly (not through this interface).
 * Broadening the result type to {@code Map<String, Set<String>>} is a lib-API change that is
 * out of scope for this module.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Each call to {@link #reason(ReasoningGraph, OwlOntology)} creates a fresh
 * {@link OWLOntologyManager} and {@link OWLReasoner} instance. No shared mutable state.</p>
 *
 * <h2>GraalVM native image</h2>
 * <p>This module is NOT suitable for GraalVM native-image compilation. The OWL API and Openllet
 * use extensive reflection and OSGI metadata. Use the lib's {@code OwlRlReasoner} for native
 * deployments.</p>
 */
public final class OwlDlReasoningBridge implements OwlReasoner {

    private static final Logger LOG = LoggerFactory.getLogger(OwlDlReasoningBridge.class);

    /**
     * Prefix used for inferred relation IDs minted by the DL bridge.
     * Distinct from {@code "owl-trp-"} used by the lib's RL reasoner so callers that
     * deduplicate by ID can distinguish the two sources.
     */
    public static final String DL_RELATION_PREFIX = "owl-dl-";

    private final OWLReasonerFactory reasonerFactory;

    /**
     * Create a bridge using the default Openllet reasoner factory.
     */
    public OwlDlReasoningBridge() {
        this(OpenlletReasonerFactory.getInstance());
    }

    /**
     * Create a bridge with an explicit reasoner factory (e.g. HermiT's {@code ReasonerFactory}).
     *
     * @param reasonerFactory the factory to create the DL reasoner from (never {@code null})
     */
    public OwlDlReasoningBridge(OWLReasonerFactory reasonerFactory) {
        this.reasonerFactory = Objects.requireNonNull(reasonerFactory, "reasonerFactory");
    }

    /**
     * Run OWL DL inference over {@code graph} (ABox) given {@code ontology} (TBox).
     *
     * <p>Steps:
     * <ol>
     *   <li>Translate the lib TBox ({@code ontology}) to an OWL API {@link OWLOntology} via
     *       {@link OwlOntologyMapper#toOwlApi(OwlOntology)}.</li>
     *   <li>Load ABox assertions from {@code graph} into the same ontology via
     *       {@link ReasoningGraphABoxLoader#load(ReasoningGraph, OWLOntology, OWLOntologyManager)}.</li>
     *   <li>Create and pre-compute the DL reasoner
     *       ({@code CLASS_HIERARCHY, CLASS_ASSERTIONS, OBJECT_PROPERTY_ASSERTIONS}).</li>
     *   <li>Collect inferred types (one per entity — see limitation note above).</li>
     *   <li>Collect inferred object property assertions not present in the original graph.</li>
     *   <li>Check consistency; enumerate unsatisfiable classes and disjointness violations.</li>
     *   <li>Dispose the reasoner and return {@link OwlRlResult#of(...)}.</li>
     * </ol>
     * </p>
     *
     * @param graph    the ABox (never {@code null})
     * @param ontology the TBox (never {@code null})
     * @return a non-{@code null} result with inferred relations, inferred types, and inconsistencies
     * @throws RuntimeException wrapping {@link OWLOntologyCreationException} if ontology creation fails
     */
    @Override
    public OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology) {
        Objects.requireNonNull(graph,    "graph");
        Objects.requireNonNull(ontology, "ontology");

        // ── Step 1: TBox translation ──────────────────────────────────────────────
        OWLOntology owlOnt;
        try {
            owlOnt = OwlOntologyMapper.toOwlApi(ontology);
        } catch (OWLOntologyCreationException e) {
            throw new RuntimeException("Failed to create OWLOntology from lib TBox", e);
        }
        OWLOntologyManager mgr = owlOnt.getOWLOntologyManager();

        // ── Step 2: ABox injection ────────────────────────────────────────────────
        ReasoningGraphABoxLoader.load(graph, owlOnt, mgr);

        // Collect the property IRIs and entity individual IRIs present in the original graph
        // so we can identify truly *new* inferred relations / types.
        Set<String> existingRelationKeys = buildExistingRelationKeys(graph);
        Set<String> assertedEntityTypes  = buildAssertedEntityTypes(graph);

        // ── Step 3: Create the DL reasoner and check consistency FIRST ──────────
        // Openllet throws InconsistentOntologyException on many operations when the ontology
        // is inconsistent. We must call isConsistent() before precomputing inferences.
        OWLReasoner reasoner = reasonerFactory.createReasoner(owlOnt);
        try {
            List<GraphRelation>      inferredRelations = new ArrayList<>();
            Map<String, String>      inferredTypes     = new HashMap<>();
            List<OwlInconsistency>   inconsistencies   = new ArrayList<>();

            // ── Step 6 (early): Consistency check ────────────────────────────────
            boolean consistent;
            try {
                consistent = reasoner.isConsistent();
            } catch (InconsistentOntologyException e) {
                consistent = false;
            }

            if (!consistent) {
                LOG.warn("[OwlDlReasoningBridge] Ontology is inconsistent");
                // Collect unsatisfiable class info before the reasoner fully fails
                collectInconsistencies(reasoner, graph, inconsistencies);
                return OwlRlResult.of(inferredRelations, inferredTypes, inconsistencies);
            }

            // ── Step 3b: Pre-compute inferences (only when consistent) ────────────
            try {
                reasoner.precomputeInferences(
                        InferenceType.CLASS_HIERARCHY,
                        InferenceType.CLASS_ASSERTIONS,
                        InferenceType.OBJECT_PROPERTY_ASSERTIONS);
            } catch (InconsistentOntologyException e) {
                // Rare: became inconsistent during precompute
                LOG.warn("[OwlDlReasoningBridge] Ontology became inconsistent during precompute");
                collectInconsistencies(reasoner, graph, inconsistencies);
                return OwlRlResult.of(inferredRelations, inferredTypes, inconsistencies);
            }

            OWLDataFactory df = mgr.getOWLDataFactory();

            // Collect individuals corresponding to graph entities
            Set<OWLNamedIndividual> individuals = new HashSet<>();
            for (var entity : graph.entities()) {
                individuals.add(df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(entity.id()))));
            }

            // ── Step 4: Inferred type assertions ─────────────────────────────────
            for (var entity : graph.entities()) {
                String entityId = entity.id();
                OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(entityId)));

                try {
                    NodeSet<OWLClass> types = reasoner.getTypes(ind, false);
                    for (Node<OWLClass> typeNode : types) {
                        OWLClass inferredCls = typeNode.getRepresentativeElement();
                        if (inferredCls.isOWLThing() || inferredCls.isOWLNothing()) continue;

                        String clsIri = inferredCls.getIRI().toString();
                        // Only record if this type was not already asserted in the graph
                        String assertedKey = entityId + ":" + clsIri;
                        if (!assertedEntityTypes.contains(assertedKey)) {
                            // Multi-class limitation: first inferred (non-asserted) type wins per entity
                            inferredTypes.putIfAbsent(entityId, clsIri);
                            break;
                        }
                    }
                } catch (InconsistentOntologyException e) {
                    LOG.debug("[OwlDlReasoningBridge] Inconsistency detected while getting types for {}", entityId);
                }
            }

            // ── Step 5: Inferred object property assertions ───────────────────────
            Set<OWLObjectPropertyExpression> allProps = owlOnt.getObjectPropertiesInSignature(Imports.INCLUDED)
                    .stream()
                    .map(p -> (OWLObjectPropertyExpression) p)
                    .collect(java.util.stream.Collectors.toSet());

            for (OWLNamedIndividual ind : individuals) {
                String srcId = entityIdFromInd(ind.getIRI().toString());
                for (OWLObjectPropertyExpression propExpr : allProps) {
                    if (!propExpr.isNamed()) continue;
                    OWLObjectProperty prop = propExpr.getNamedProperty();
                    String propName = localName(prop.getIRI().toString());

                    try {
                        NodeSet<OWLNamedIndividual> values = reasoner.getObjectPropertyValues(ind, prop);
                        for (Node<OWLNamedIndividual> valueNode : values) {
                            OWLNamedIndividual tgtInd = valueNode.getRepresentativeElement();
                            String tgtId = entityIdFromInd(tgtInd.getIRI().toString());

                            String relKey = srcId + ":" + propName + ":" + tgtId;
                            if (!existingRelationKeys.contains(relKey)) {
                                String relId = DL_RELATION_PREFIX + propName + "-" + srcId + "-" + tgtId;
                                inferredRelations.add(SimpleGraphRelation.directed(relId, srcId, tgtId, propName, 1.0));
                                existingRelationKeys.add(relKey); // avoid duplicate inferred entries
                            }
                        }
                    } catch (InconsistentOntologyException e) {
                        LOG.debug("[OwlDlReasoningBridge] Inconsistency during property value retrieval for {} {}", srcId, propName);
                    }
                }
            }

            // ── Step 7: Disjointness violations from inferred types ───────────────
            // After DL inference, find individuals typed as two disjoint classes.
            detectDisjointViolations(reasoner, graph, owlOnt, mgr, inconsistencies);

            return OwlRlResult.of(inferredRelations, inferredTypes, inconsistencies);

        } finally {
            // ── Step 8: Dispose the reasoner ─────────────────────────────────────
            reasoner.dispose();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Collect unsatisfiable-class inconsistencies from a (potentially) inconsistent reasoner.
     * Called when {@link OWLReasoner#isConsistent()} returns false.
     */
    private static void collectInconsistencies(
            OWLReasoner reasoner,
            ReasoningGraph graph,
            List<OwlInconsistency> inconsistencies) {
        try {
            Node<OWLClass> unsatClasses = reasoner.getUnsatisfiableClasses();
            for (OWLClass cls : unsatClasses) {
                if (cls.isOWLNothing()) continue;
                String clsIri = cls.getIRI().toString();

                boolean foundEntity = false;
                for (var entity : graph.entities()) {
                    String entityType = entity.type();
                    if (entityType != null && OwlIri.classIri(entityType).equals(clsIri)) {
                        inconsistencies.add(OwlInconsistency.crisp(
                                "dl-unsat",
                                entity.id(),
                                "Individual typed as unsatisfiable class: " + clsIri));
                        foundEntity = true;
                    }
                }
                if (!foundEntity) {
                    inconsistencies.add(OwlInconsistency.crisp(
                            "dl-unsat",
                            "class:" + clsIri,
                            "Unsatisfiable class in ontology: " + clsIri));
                }
            }
        } catch (Exception ex) {
            // Reasoner is in bad state; add a generic inconsistency
            LOG.warn("[OwlDlReasoningBridge] Could not enumerate unsatisfiable classes: {}", ex.getMessage());
            inconsistencies.add(OwlInconsistency.crisp(
                    "dl-inconsistent",
                    "ontology",
                    "Ontology is inconsistent: " + ex.getMessage()));
        }
    }

    /**
     * Build a set of {@code "sourceId:propName:targetId"} keys from the original graph
     * so we can skip already-present relations when collecting inferred ones.
     */
    private static Set<String> buildExistingRelationKeys(ReasoningGraph graph) {
        Set<String> keys = new HashSet<>();
        for (GraphRelation r : graph.relations()) {
            keys.add(r.sourceId() + ":" + r.type() + ":" + r.targetId());
        }
        return keys;
    }

    /**
     * Build a set of {@code "entityId:classIri"} pairs for class assertions already in the graph,
     * so we skip types that were already asserted when collecting inferred types.
     */
    private static Set<String> buildAssertedEntityTypes(ReasoningGraph graph) {
        Set<String> keys = new HashSet<>();
        for (var entity : graph.entities()) {
            if (entity.type() != null && !entity.type().isEmpty()) {
                keys.add(entity.id() + ":" + OwlIri.classIri(entity.type()));
            }
        }
        return keys;
    }

    /**
     * Detect disjointness violations: entities (including inferred types) that are typed as
     * two classes declared {@code owl:disjointWith} each other.
     */
    private static void detectDisjointViolations(
            OWLReasoner reasoner,
            ReasoningGraph graph,
            OWLOntology owlOnt,
            OWLOntologyManager mgr,
            List<OwlInconsistency> inconsistencies) {

        OWLDataFactory df = mgr.getOWLDataFactory();

        // Collect disjoint pairs from the ontology
        Set<String[]> disjointPairs = new HashSet<>();
        for (OWLAxiom axiom : owlOnt.getAxioms()) {
            if (axiom instanceof OWLDisjointClassesAxiom dca) {
                List<OWLClassExpression> ops = dca.getOperandsAsList();
                for (int i = 0; i < ops.size(); i++) {
                    for (int j = i + 1; j < ops.size(); j++) {
                        if (ops.get(i).isNamed() && ops.get(j).isNamed()) {
                            disjointPairs.add(new String[]{
                                    ops.get(i).asOWLClass().getIRI().toString(),
                                    ops.get(j).asOWLClass().getIRI().toString()
                            });
                        }
                    }
                }
            }
        }

        if (disjointPairs.isEmpty()) return;

        for (var entity : graph.entities()) {
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri(entity.id())));

            // Collect all inferred types for this individual
            Set<String> allTypeIris = new HashSet<>();
            try {
                NodeSet<OWLClass> types = reasoner.getTypes(ind, false);
                for (Node<OWLClass> typeNode : types) {
                    OWLClass cls = typeNode.getRepresentativeElement();
                    if (!cls.isOWLThing() && !cls.isOWLNothing()) {
                        allTypeIris.add(cls.getIRI().toString());
                    }
                }
            } catch (Exception e) {
                LOG.debug("[OwlDlReasoningBridge] Could not get types for {}: {}", entity.id(), e.getMessage());
                continue;
            }

            for (String[] pair : disjointPairs) {
                if (allTypeIris.contains(pair[0]) && allTypeIris.contains(pair[1])) {
                    inconsistencies.add(OwlInconsistency.crisp(
                            "cax-dw",
                            entity.id(),
                            "Individual typed as disjoint classes: " + pair[0] + " and " + pair[1]));
                }
            }
        }
    }

    /** Extract entity id from a kompile individual IRI ({@code https://kompile.ai/kg/node/<id>}). */
    private static String entityIdFromInd(String indIri) {
        // OwlIri.indIri(id) = BASE + "node/" + encode(id)
        // Reverse: strip the prefix
        String prefix = OwlIri.BASE + "node/";
        if (indIri.startsWith(prefix)) {
            return indIri.substring(prefix.length());
        }
        // Fallback: use local name
        return localName(indIri);
    }

    /** Derive local name from an IRI string. */
    private static String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) return iri.substring(slash + 1);
        return iri;
    }
}
