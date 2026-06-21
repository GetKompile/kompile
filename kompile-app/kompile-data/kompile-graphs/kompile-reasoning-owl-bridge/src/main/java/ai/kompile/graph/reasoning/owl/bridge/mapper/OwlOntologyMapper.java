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

import ai.kompile.graph.reasoning.mebn.type.owl.OwlClass;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlDataProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlObjectProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRestriction;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bidirectional translation between the lib's {@link OwlOntology} TBox representation
 * and an OWL API {@link OWLOntology}.
 *
 * <p><strong>Direction 1:</strong> {@link #toOwlApi(OwlOntology)} — used by
 * {@link ai.kompile.graph.reasoning.owl.bridge.reasoner.OwlDlReasoningBridge} to build a
 * full in-memory {@code OWLOntology} for the DL reasoner from a lib TBox.</p>
 *
 * <p><strong>Direction 2:</strong> {@link #fromOwlApi(OWLOntology)} — used by
 * {@link ai.kompile.graph.reasoning.owl.bridge.importer.ExternalOwlImporter} to project
 * the TBox axioms of an externally loaded ontology back into the lib model.</p>
 *
 * <p>Complex class expressions ({@code OWLObjectComplementOf}, {@code OWLObjectUnionOf}) cannot be
 * represented in the lib model and are logged as skipped. The DL reasoner still reasons over them
 * via the in-memory {@code OWLOntology} — any inconsistency they cause surfaces in the returned
 * {@link ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult}.</p>
 *
 * <p>ABox assertions ({@code OWLClassAssertionAxiom}, property assertions) are handled separately
 * by {@link ReasoningGraphABoxLoader}; {@link #fromOwlApi(OWLOntology)} ignores them.</p>
 *
 * <p>This class is stateless; all methods are static utility methods.</p>
 */
public final class OwlOntologyMapper {

    private static final Logger LOG = LoggerFactory.getLogger(OwlOntologyMapper.class);

    private OwlOntologyMapper() { /* utility class */ }

    // ─── Direction 1: lib OwlOntology → OWL API OWLOntology ────────────────────

    /**
     * Translate a lib {@link OwlOntology} into an OWL API {@link OWLOntology} whose axioms are
     * suitable for passing to an {@link org.semanticweb.owlapi.reasoner.OWLReasoner}.
     *
     * <p>The returned ontology is owned by a fresh {@link OWLOntologyManager}; callers that need
     * the manager (e.g. to add ABox axioms) should call {@code owlOnt.getOWLOntologyManager()}.</p>
     *
     * @param lib the lib TBox (never {@code null})
     * @return a fresh, in-memory {@code OWLOntology} with all TBox axioms from {@code lib}
     * @throws OWLOntologyCreationException if the OWL API cannot create the backing ontology
     */
    public static OWLOntology toOwlApi(OwlOntology lib) throws OWLOntologyCreationException {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();

        OWLOntology owlOnt = lib.ontologyIri() != null
                ? mgr.createOntology(IRI.create(lib.ontologyIri()))
                : mgr.createOntology();

        List<OWLAxiom> axioms = new ArrayList<>();

        // ── Classes ──────────────────────────────────────────────────────────────
        for (OwlClass owlClass : lib.classes().values()) {
            OWLClass cls = df.getOWLClass(IRI.create(owlClass.classIri()));
            axioms.add(df.getOWLDeclarationAxiom(cls));

            for (String superIri : owlClass.subClassOfIris()) {
                axioms.add(df.getOWLSubClassOfAxiom(cls, df.getOWLClass(IRI.create(superIri))));
            }
            for (String eqIri : owlClass.equivalentClassIris()) {
                axioms.add(df.getOWLEquivalentClassesAxiom(cls, df.getOWLClass(IRI.create(eqIri))));
            }
            for (String djIri : owlClass.disjointWithIris()) {
                axioms.add(df.getOWLDisjointClassesAxiom(cls, df.getOWLClass(IRI.create(djIri))));
            }
            for (OwlRestriction restriction : owlClass.restrictions()) {
                OWLClassExpression expr = toClassExpression(restriction, df);
                if (expr != null) {
                    axioms.add(df.getOWLSubClassOfAxiom(cls, expr));
                }
            }
        }

        // ── Object Properties ─────────────────────────────────────────────────────
        for (OwlObjectProperty op : lib.objectProperties().values()) {
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(op.propertyIri()));
            axioms.add(df.getOWLDeclarationAxiom(prop));

            if (op.domainClassIri() != null) {
                axioms.add(df.getOWLObjectPropertyDomainAxiom(prop,
                        df.getOWLClass(IRI.create(op.domainClassIri()))));
            }
            if (op.rangeClassIri() != null) {
                axioms.add(df.getOWLObjectPropertyRangeAxiom(prop,
                        df.getOWLClass(IRI.create(op.rangeClassIri()))));
            }
            if (op.isFunctional())        axioms.add(df.getOWLFunctionalObjectPropertyAxiom(prop));
            if (op.isInverseFunctional()) axioms.add(df.getOWLInverseFunctionalObjectPropertyAxiom(prop));
            if (op.isTransitive())        axioms.add(df.getOWLTransitiveObjectPropertyAxiom(prop));
            if (op.isSymmetric())         axioms.add(df.getOWLSymmetricObjectPropertyAxiom(prop));
            if (op.isReflexive())         axioms.add(df.getOWLReflexiveObjectPropertyAxiom(prop));
            if (op.isAsymmetric())        axioms.add(df.getOWLAsymmetricObjectPropertyAxiom(prop));
            if (op.isIrreflexive())       axioms.add(df.getOWLIrreflexiveObjectPropertyAxiom(prop));

            if (op.inverseOfIri() != null) {
                axioms.add(df.getOWLInverseObjectPropertiesAxiom(prop,
                        df.getOWLObjectProperty(IRI.create(op.inverseOfIri()))));
            }
            for (String spIri : op.subPropertyOfIris()) {
                axioms.add(df.getOWLSubObjectPropertyOfAxiom(prop,
                        df.getOWLObjectProperty(IRI.create(spIri))));
            }
            for (String eqIri : op.equivalentPropertyIris()) {
                axioms.add(df.getOWLEquivalentObjectPropertiesAxiom(prop,
                        df.getOWLObjectProperty(IRI.create(eqIri))));
            }
        }

        // ── Data Properties ───────────────────────────────────────────────────────
        for (OwlDataProperty dp : lib.dataProperties().values()) {
            OWLDataProperty prop = df.getOWLDataProperty(IRI.create(dp.propertyIri()));
            axioms.add(df.getOWLDeclarationAxiom(prop));

            if (dp.domainClassIri() != null) {
                axioms.add(df.getOWLDataPropertyDomainAxiom(prop,
                        df.getOWLClass(IRI.create(dp.domainClassIri()))));
            }
            if (dp.rangeDatatype() != null) {
                axioms.add(df.getOWLDataPropertyRangeAxiom(prop,
                        df.getOWLDatatype(IRI.create(dp.rangeDatatype()))));
            }
            if (dp.isFunctional()) {
                axioms.add(df.getOWLFunctionalDataPropertyAxiom(prop));
            }
            for (String spIri : dp.subPropertyOfIris()) {
                axioms.add(df.getOWLSubDataPropertyOfAxiom(prop,
                        df.getOWLDataProperty(IRI.create(spIri))));
            }
            for (String eqIri : dp.equivalentPropertyIris()) {
                axioms.add(df.getOWLEquivalentDataPropertiesAxiom(prop,
                        df.getOWLDataProperty(IRI.create(eqIri))));
            }
        }

        // ── sameAs individuals ────────────────────────────────────────────────────
        for (Map.Entry<String, String> entry : lib.sameAs().entrySet()) {
            axioms.add(df.getOWLSameIndividualAxiom(
                    df.getOWLNamedIndividual(IRI.create(entry.getKey())),
                    df.getOWLNamedIndividual(IRI.create(entry.getValue()))));
        }

        mgr.addAxioms(owlOnt, axioms.stream());
        return owlOnt;
    }

    /**
     * Convert a lib {@link OwlRestriction} to an OWL API class expression suitable for use as
     * a superclass in a {@code SubClassOf} axiom.
     *
     * @return the class expression (never {@code null} for any lib-supported restriction type)
     */
    private static OWLClassExpression toClassExpression(OwlRestriction restriction, OWLDataFactory df) {
        if (restriction instanceof OwlRestriction.SomeValuesFrom svf) {
            return df.getOWLObjectSomeValuesFrom(
                    df.getOWLObjectProperty(IRI.create(svf.onPropertyIri())),
                    df.getOWLClass(IRI.create(svf.fillerClassIri())));
        }
        if (restriction instanceof OwlRestriction.AllValuesFrom avf) {
            return df.getOWLObjectAllValuesFrom(
                    df.getOWLObjectProperty(IRI.create(avf.onPropertyIri())),
                    df.getOWLClass(IRI.create(avf.fillerClassIri())));
        }
        if (restriction instanceof OwlRestriction.HasValue hv) {
            return df.getOWLObjectHasValue(
                    df.getOWLObjectProperty(IRI.create(hv.onPropertyIri())),
                    df.getOWLNamedIndividual(IRI.create(hv.individualId())));
        }
        if (restriction instanceof OwlRestriction.MinCardinality mc) {
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(mc.onPropertyIri()));
            return mc.qualifiedOnClassIri() != null
                    ? df.getOWLObjectMinCardinality(mc.n(), prop,
                            df.getOWLClass(IRI.create(mc.qualifiedOnClassIri())))
                    : df.getOWLObjectMinCardinality(mc.n(), prop);
        }
        if (restriction instanceof OwlRestriction.MaxCardinality mc) {
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(mc.onPropertyIri()));
            return mc.qualifiedOnClassIri() != null
                    ? df.getOWLObjectMaxCardinality(mc.n(), prop,
                            df.getOWLClass(IRI.create(mc.qualifiedOnClassIri())))
                    : df.getOWLObjectMaxCardinality(mc.n(), prop);
        }
        if (restriction instanceof OwlRestriction.ExactCardinality ec) {
            OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(ec.onPropertyIri()));
            return ec.qualifiedOnClassIri() != null
                    ? df.getOWLObjectExactCardinality(ec.n(), prop,
                            df.getOWLClass(IRI.create(ec.qualifiedOnClassIri())))
                    : df.getOWLObjectExactCardinality(ec.n(), prop);
        }
        throw new IllegalArgumentException("Unknown OwlRestriction type: " + restriction.getClass());
    }

    // ─── Direction 2: OWL API OWLOntology → lib OwlOntology ────────────────────

    /**
     * Project the TBox axioms of an OWL API {@link OWLOntology} into the lib's {@link OwlOntology}.
     *
     * <p>Complex class expressions ({@code OWLObjectComplementOf}, {@code OWLObjectUnionOf})
     * cannot be represented in the lib model — they are logged and skipped at this projection
     * step, but remain in the in-memory {@code OWLOntology} for DL reasoning. Any inconsistency
     * they cause will surface in the returned
     * {@link ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult}.</p>
     *
     * <p>ABox assertions ({@code OWLClassAssertionAxiom}, property assertions) are extracted by
     * {@link ReasoningGraphABoxLoader}; this method ignores them.</p>
     *
     * @param owlOnt the merged (import-closure-flattened) OWL API ontology (never {@code null})
     * @return the lib TBox (never {@code null})
     */
    public static OwlOntology fromOwlApi(OWLOntology owlOnt) {
        String ontIri = owlOnt.getOntologyID().getOntologyIRI()
                .map(IRI::toString).orElse(null);

        // Accumulate builders locally — avoids any shared mutable state
        Map<String, OwlClass.Builder>          classBuilders = new LinkedHashMap<>();
        Map<String, OwlObjectProperty.Builder> opBuilders    = new LinkedHashMap<>();
        Map<String, OwlDataProperty.Builder>   dpBuilders    = new LinkedHashMap<>();
        OwlOntology.Builder ontBuilder = OwlOntology.of(ontIri);

        for (OWLAxiom axiom : owlOnt.getAxioms()) {
            if (axiom instanceof OWLDeclarationAxiom decl) {
                OWLEntity entity = decl.getEntity();
                if (entity.isOWLClass()
                        && !entity.asOWLClass().isOWLThing()
                        && !entity.asOWLClass().isOWLNothing()) {
                    String iri = entity.getIRI().toString();
                    classBuilders.computeIfAbsent(iri, OwlClass::of);
                }

            } else if (axiom instanceof OWLSubClassOfAxiom sca) {
                OWLClassExpression sub = sca.getSubClass();
                OWLClassExpression sup = sca.getSuperClass();
                if (sub.isNamed() && sup.isNamed()) {
                    String subIri = sub.asOWLClass().getIRI().toString();
                    String supIri = sup.asOWLClass().getIRI().toString();
                    classBuilders.computeIfAbsent(subIri, OwlClass::of).subClassOf(supIri);
                } else {
                    LOG.debug("[OwlOntologyMapper] Skipped OWLSubClassOfAxiom with anonymous expression");
                }

            } else if (axiom instanceof OWLEquivalentClassesAxiom eca) {
                List<OWLClassExpression> ops = eca.getOperandsAsList();
                boolean allNamed = ops.stream().allMatch(OWLClassExpression::isNamed);
                if (allNamed && ops.size() >= 2) {
                    for (int i = 0; i < ops.size(); i++) {
                        String iriI = ops.get(i).asOWLClass().getIRI().toString();
                        OwlClass.Builder b = classBuilders.computeIfAbsent(iriI, OwlClass::of);
                        for (int j = 0; j < ops.size(); j++) {
                            if (i != j) {
                                b.equivalentClass(ops.get(j).asOWLClass().getIRI().toString());
                            }
                        }
                    }
                } else {
                    LOG.debug("[OwlOntologyMapper] Skipped OWLEquivalentClassesAxiom with complex expressions");
                }

            } else if (axiom instanceof OWLDisjointClassesAxiom dca) {
                List<OWLClassExpression> ops = dca.getOperandsAsList();
                boolean allNamed = ops.stream().allMatch(OWLClassExpression::isNamed);
                if (allNamed) {
                    for (int i = 0; i < ops.size(); i++) {
                        String iriI = ops.get(i).asOWLClass().getIRI().toString();
                        OwlClass.Builder b = classBuilders.computeIfAbsent(iriI, OwlClass::of);
                        for (int j = 0; j < ops.size(); j++) {
                            if (i != j) {
                                b.disjointWith(ops.get(j).asOWLClass().getIRI().toString());
                            }
                        }
                    }
                } else {
                    LOG.debug("[OwlOntologyMapper] Skipped OWLDisjointClassesAxiom with complex expressions");
                }

            } else if (axiom instanceof OWLObjectPropertyDomainAxiom opda) {
                if (opda.getProperty().isNamed() && opda.getDomain().isNamed()) {
                    opBuilders.computeIfAbsent(
                            opda.getProperty().getNamedProperty().getIRI().toString(),
                            OwlObjectProperty::of)
                            .domain(opda.getDomain().asOWLClass().getIRI().toString());
                }

            } else if (axiom instanceof OWLObjectPropertyRangeAxiom opra) {
                if (opra.getProperty().isNamed() && opra.getRange().isNamed()) {
                    opBuilders.computeIfAbsent(
                            opra.getProperty().getNamedProperty().getIRI().toString(),
                            OwlObjectProperty::of)
                            .range(opra.getRange().asOWLClass().getIRI().toString());
                }

            } else if (axiom instanceof OWLFunctionalObjectPropertyAxiom fopa && fopa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        fopa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).functional(true);

            } else if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom ifopa && ifopa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        ifopa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).inverseFunctional(true);

            } else if (axiom instanceof OWLTransitiveObjectPropertyAxiom topa && topa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        topa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).transitive(true);

            } else if (axiom instanceof OWLSymmetricObjectPropertyAxiom sopa && sopa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        sopa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).symmetric(true);

            } else if (axiom instanceof OWLReflexiveObjectPropertyAxiom ropa && ropa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        ropa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).reflexive(true);

            } else if (axiom instanceof OWLAsymmetricObjectPropertyAxiom asopa && asopa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        asopa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).asymmetric(true);

            } else if (axiom instanceof OWLIrreflexiveObjectPropertyAxiom iropa && iropa.getProperty().isNamed()) {
                opBuilders.computeIfAbsent(
                        iropa.getProperty().getNamedProperty().getIRI().toString(),
                        OwlObjectProperty::of).irreflexive(true);

            } else if (axiom instanceof OWLInverseObjectPropertiesAxiom iopa) {
                if (iopa.getFirstProperty().isNamed() && iopa.getSecondProperty().isNamed()) {
                    String p1 = iopa.getFirstProperty().getNamedProperty().getIRI().toString();
                    String p2 = iopa.getSecondProperty().getNamedProperty().getIRI().toString();
                    opBuilders.computeIfAbsent(p1, OwlObjectProperty::of).inverseOf(p2);
                }

            } else if (axiom instanceof OWLSubObjectPropertyOfAxiom sopoa) {
                if (sopoa.getSubProperty().isNamed() && sopoa.getSuperProperty().isNamed()) {
                    opBuilders.computeIfAbsent(
                            sopoa.getSubProperty().getNamedProperty().getIRI().toString(),
                            OwlObjectProperty::of)
                            .subPropertyOf(sopoa.getSuperProperty().getNamedProperty().getIRI().toString());
                }

            } else if (axiom instanceof OWLEquivalentObjectPropertiesAxiom eqopa) {
                List<OWLObjectPropertyExpression> props = eqopa.getOperandsAsList();
                if (props.stream().allMatch(OWLObjectPropertyExpression::isNamed)) {
                    for (OWLObjectPropertyExpression pe : props) {
                        String iri = pe.getNamedProperty().getIRI().toString();
                        OwlObjectProperty.Builder b = opBuilders.computeIfAbsent(iri, OwlObjectProperty::of);
                        for (OWLObjectPropertyExpression pe2 : props) {
                            if (!pe2.equals(pe)) {
                                b.equivalentProperty(pe2.getNamedProperty().getIRI().toString());
                            }
                        }
                    }
                }

            } else if (axiom instanceof OWLDataPropertyDomainAxiom dpda) {
                if (dpda.getProperty().isNamed() && dpda.getDomain().isNamed()) {
                    dpBuilders.computeIfAbsent(
                            dpda.getProperty().asOWLDataProperty().getIRI().toString(),
                            OwlDataProperty::of)
                            .domain(dpda.getDomain().asOWLClass().getIRI().toString());
                }

            } else if (axiom instanceof OWLDataPropertyRangeAxiom dpra) {
                if (dpra.getProperty().isNamed() && dpra.getRange() instanceof OWLDatatype dt) {
                    dpBuilders.computeIfAbsent(
                            dpra.getProperty().asOWLDataProperty().getIRI().toString(),
                            OwlDataProperty::of)
                            .range(dt.getIRI().toString());
                }

            } else if (axiom instanceof OWLFunctionalDataPropertyAxiom fdpa && fdpa.getProperty().isNamed()) {
                dpBuilders.computeIfAbsent(
                        fdpa.getProperty().asOWLDataProperty().getIRI().toString(),
                        OwlDataProperty::of).functional(true);

            } else if (axiom instanceof OWLSubDataPropertyOfAxiom sdpoa) {
                if (sdpoa.getSubProperty().isNamed() && sdpoa.getSuperProperty().isNamed()) {
                    dpBuilders.computeIfAbsent(
                            sdpoa.getSubProperty().asOWLDataProperty().getIRI().toString(),
                            OwlDataProperty::of)
                            .subPropertyOf(sdpoa.getSuperProperty().asOWLDataProperty().getIRI().toString());
                }

            } else if (axiom instanceof OWLSameIndividualAxiom sia) {
                List<OWLIndividual> inds = sia.getIndividualsAsList();
                if (inds.size() == 2 && inds.get(0).isNamed() && inds.get(1).isNamed()) {
                    ontBuilder.sameAs(
                            inds.get(0).asOWLNamedIndividual().getIRI().toString(),
                            inds.get(1).asOWLNamedIndividual().getIRI().toString());
                }

            }
            // ABox assertions (ClassAssertion, ObjectPropertyAssertion, DataPropertyAssertion)
            // and annotation axioms are silently skipped — handled by ReasoningGraphABoxLoader.
        }

        // Register all collected builders
        for (OwlClass.Builder cb : classBuilders.values()) {
            ontBuilder.addClass(cb.build());
        }
        for (OwlObjectProperty.Builder b : opBuilders.values()) {
            ontBuilder.addObjectProperty(b.build());
        }
        for (OwlDataProperty.Builder b : dpBuilders.values()) {
            ontBuilder.addDataProperty(b.build());
        }

        return ontBuilder.build();
    }
}
