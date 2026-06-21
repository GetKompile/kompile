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
package ai.kompile.graph.reasoning.mebn.type.owl;

import ai.kompile.graph.reasoning.fol.FolRule;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;

/**
 * Compiles an {@link OwlOntology} (TBox) into a {@link FolRuleSet} containing OWL 2 RL
 * entailment rules expressed as {@link FolRule}s over the {@link ai.kompile.graph.reasoning.model.ReasoningGraph}
 * ABox.
 *
 * <h2>Design</h2>
 * <p>OWL 2 RL entailment rules are crisp Horn clauses (Table 8 of the W3C OWL 2 RL spec).
 * Each rule takes the form <em>IF &lt;ABox + TBox pattern&gt; THEN &lt;new ABox fact&gt;</em>,
 * which maps directly onto a {@link FolRule} antecedent/consequent pair. Rules are emitted
 * with weight {@link Double#POSITIVE_INFINITY} (hard/crisp), matching the spec's semantics.
 * The {@link ai.kompile.graph.reasoning.fol.FolInferenceService} evaluates them against the
 * graph via pairwise grounding and PSL MAP inference.</p>
 *
 * <h2>Implemented rules (Phase O2)</h2>
 * <ul>
 *   <li><b>cax-sco</b> — {@code C rdfs:subClassOf D}, {@code a: C} → {@code a: D}</li>
 *   <li><b>prp-dom</b> — {@code P rdfs:domain C}, {@code P(a,b)} → {@code a: C}</li>
 *   <li><b>prp-rng</b> — {@code P rdfs:range C}, {@code P(a,b)} → {@code b: C}</li>
 *   <li><b>prp-symp</b> — {@code P owl:SymmetricProperty}, {@code P(a,b)} → {@code P(b,a)}</li>
 *   <li><b>prp-inv1</b> — {@code P owl:inverseOf Q}, {@code P(a,b)} → {@code Q(b,a)}</li>
 *   <li><b>prp-inv2</b> — {@code P owl:inverseOf Q}, {@code Q(a,b)} → {@code P(b,a)}</li>
 *   <li><b>prp-fp</b> — {@code P owl:FunctionalProperty}, {@code P(a,b)}, {@code P(a,c)},
 *       {@code b ≠ c} → grounded "b sameAs c" (recorded by the reasoner, not a graph edge)</li>
 *   <li><b>prp-ifp</b> — {@code P owl:InverseFunctionalProperty}, {@code P(b,a)}, {@code P(c,a)},
 *       {@code b ≠ c} → grounded "b sameAs c"</li>
 *   <li><b>cls-oo / cls-oo-sym</b> — {@code C owl:equivalentClass D}, {@code a: C} → {@code a: D}
 *       (bidirectional)</li>
 *   <li><b>cax-dw</b> — {@code C owl:disjointWith D}, {@code a: C}, {@code a: D}
 *       → {@link Constraints#falsehood()} (signals inconsistency)</li>
 * </ul>
 *
 * <h2>Deferred rules (not implementable in the current {@link FolRule}/{@link Constraints} API)</h2>
 * <ul>
 *   <li><b>prp-trp</b> (transitivity) — requires triple-grounding (X→Y, Y→Z → X→Z); the current
 *       {@link ai.kompile.graph.reasoning.fol.FolInferenceService} grounds only pairs (X,Y) and
 *       cannot bind three variables simultaneously. Per the Phase O2 design decision, transitivity
 *       is handled by a dedicated BFS pass in {@link OwlRlReasoner} instead.</li>
 *   <li><b>scm-sco / scm-spo</b> (TBox-level subClass/subProperty transitivity) — these derive new
 *       TBox axioms ({@code C ⊑ E} from {@code C ⊑ D}, {@code D ⊑ E}), which requires mutating or
 *       iterating the ontology itself. ABox-level subclass propagation is handled by {@code cax-sco}.</li>
 *   <li><b>cls-hv1</b> — {@code C ⊑ [P hasValue v]}, {@code a: C} → {@code P(a, v)}: the consequent
 *       asserts a specific (a, v) relation where v is an ontology constant. The current
 *       {@link Constraints} API can express the antecedent but not a consequent that mixes a variable
 *       with a TBox constant. Deferred to Phase O3+.</li>
 *   <li><b>cls-hv2</b> — {@code P(a,v)} → {@code a: C}: implementable in principle but requires the
 *       filler individual ({@code v}) to be a named entity in the ABox. Deferred.</li>
 *   <li><b>cls-avf</b> ({@code allValuesFrom}), <b>cls-svf1</b> ({@code someValuesFrom}) — require
 *       three-variable grounding for the filler class check. Deferred.</li>
 *   <li><b>prp-fp / prp-ifp sameAs materialisation</b> — the functional/inverse-functional rules
 *       fire but their consequent (b sameAs c) is not currently materialised as a graph relation;
 *       the reasoner maps the grounding degree to {@link OwlRlResult#inferredTypes()} by convention.
 *       Full sameAs merging is a Phase O3+ concern.</li>
 * </ul>
 *
 * <p>This class is stateless and pure: {@link #compile(OwlOntology)} produces a new
 * {@link FolRuleSet} without mutating any argument.</p>
 */
public final class OwlRlRuleCompiler {

    /**
     * Weight used for all OWL RL rules — crisp (hard) as per the W3C spec.
     * {@link ai.kompile.graph.reasoning.fol.FolInferenceService} honours
     * {@code POSITIVE_INFINITY} weights as hard constraints in the PSL energy.
     */
    private static final double HARD = Double.POSITIVE_INFINITY;

    /**
     * Compile an {@link OwlOntology} into a {@link FolRuleSet} containing all expressible
     * OWL 2 RL entailment rules for the given TBox.
     *
     * <p>Rules that require triple grounding or TBox mutation (see class javadoc) are omitted
     * and logged at DEBUG. The returned rule set is safe to pass directly to
     * {@link ai.kompile.graph.reasoning.fol.FolInferenceService#inferFacts(
     * ai.kompile.graph.reasoning.model.ReasoningGraph,
     * ai.kompile.graph.reasoning.fol.FolRuleSet)}.</p>
     *
     * @param ontology the TBox to compile (never {@code null})
     * @return a non-{@code null} rule set; may be empty if the ontology has no applicable axioms
     */
    public FolRuleSet compile(OwlOntology ontology) {
        FolRuleSet.Builder builder = FolRuleSet.named("owl-rl-" + sanitize(ontology.ontologyIri()));

        compileSubClassRules(ontology, builder);
        compileDomainRangeRules(ontology, builder);
        compileSymmetryRules(ontology, builder);
        compileInverseRules(ontology, builder);
        compileFunctionalRules(ontology, builder);
        compileEquivalenceRules(ontology, builder);
        compileDisjointnessRules(ontology, builder);

        return builder.build();
    }

    // ─── cax-sco: subClassOf → type propagation ──────────────────────────────────

    /**
     * {@code cax-sco}: for each {@code C rdfs:subClassOf D}, emit a rule that propagates type
     * membership: any entity typed as {@code C} must also be typed as {@code D}.
     *
     * <p>Multiple superclasses per class each produce a separate rule.</p>
     */
    private void compileSubClassRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlClass owlClass : ontology.classes().values()) {
            String childName = owlClass.localName();
            for (String parentIri : owlClass.subClassOfIris()) {
                String parentName = localName(parentIri, ontology);
                builder.add(FolRule.builder("cax-sco-" + childName + "-" + parentName)
                        .weight(HARD)
                        .antecedent(Constraints.hasType("X", childName))
                        .consequent(Constraints.hasType("X", parentName))
                        .build());
            }
        }
    }

    // ─── prp-dom / prp-rng: domain and range → type assertions ──────────────────

    /**
     * {@code prp-dom}: {@code P rdfs:domain C}, {@code P(a,b)} → {@code a: C}.
     * {@code prp-rng}: {@code P rdfs:range C}, {@code P(a,b)} → {@code b: C}.
     */
    private void compileDomainRangeRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            String pName = prop.localName();

            if (prop.domainClassIri() != null) {
                String domainName = localName(prop.domainClassIri(), ontology);
                builder.add(FolRule.builder("prp-dom-" + pName)
                        .weight(HARD)
                        .antecedent(Constraints.edgeOfType("X", "Y", pName))
                        .consequent(Constraints.hasType("X", domainName))
                        .build());
            }

            if (prop.rangeClassIri() != null) {
                String rangeName = localName(prop.rangeClassIri(), ontology);
                builder.add(FolRule.builder("prp-rng-" + pName)
                        .weight(HARD)
                        .antecedent(Constraints.edgeOfType("X", "Y", pName))
                        .consequent(Constraints.hasType("Y", rangeName))
                        .build());
            }
        }
    }

    // ─── prp-symp: symmetric property → reverse edge ────────────────────────────

    /**
     * {@code prp-symp}: {@code P owl:SymmetricProperty}, {@code P(a,b)} → {@code P(b,a)}.
     *
     * <p>The consequent checks for the reverse edge — if it is missing the rule is violated,
     * and the PSL energy pushes the grounding's consequent atom toward 1.0. In practice the
     * reasoner reads the consequent atom value and emits a new {@link ai.kompile.graph.reasoning.model.GraphRelation}
     * for the reverse direction.</p>
     */
    private void compileSymmetryRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            if (!prop.isSymmetric()) continue;
            String pName = prop.localName();
            builder.add(FolRule.builder("prp-symp-" + pName)
                    .weight(HARD)
                    .antecedent(Constraints.edgeOfType("X", "Y", pName))
                    .consequent(Constraints.edgeOfType("Y", "X", pName))
                    .build());
        }
    }

    // ─── prp-inv1 / prp-inv2: inverse properties ─────────────────────────────────

    /**
     * {@code prp-inv1}: {@code P owl:inverseOf Q}, {@code P(a,b)} → {@code Q(b,a)}.
     * {@code prp-inv2}: {@code P owl:inverseOf Q}, {@code Q(a,b)} → {@code P(b,a)}.
     */
    private void compileInverseRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            if (prop.inverseOfIri() == null) continue;
            String pName = prop.localName();
            String qName = localName(prop.inverseOfIri(), ontology);

            builder.add(FolRule.builder("prp-inv1-" + pName + "-" + qName)
                    .weight(HARD)
                    .antecedent(Constraints.edgeOfType("X", "Y", pName))
                    .consequent(Constraints.edgeOfType("Y", "X", qName))
                    .build());

            builder.add(FolRule.builder("prp-inv2-" + pName + "-" + qName)
                    .weight(HARD)
                    .antecedent(Constraints.edgeOfType("X", "Y", qName))
                    .consequent(Constraints.edgeOfType("Y", "X", pName))
                    .build());
        }
    }

    // ─── prp-fp / prp-ifp: functional and inverse-functional ─────────────────────

    /**
     * {@code prp-fp}: {@code P owl:FunctionalProperty}, {@code P(a,b)}, {@code P(a,c)},
     * {@code b ≠ c} → {@code b owl:sameAs c}.
     *
     * <p>The current grounding strategy pairs (X=a, Y=b) and (X=a, Y=c) separately.
     * The functional rule is encoded as: "for any pair (X,Y) where a P-edge X→Y exists,
     * there should not also exist another P-edge X→Z for a different Z". We approximate
     * this as: if P(X,Y) holds, then the source X has type consistent with functionality.
     * The full sameAs consequent requires triple-grounding and is deferred; the rule here
     * fires with a sentinel consequent that the reasoner interprets as a sameAs hint.</p>
     *
     * <p>Concretely: we emit a rule whose antecedent is {@code P(X,Y)} and whose consequent
     * is {@code hasType(X, "_fp_" + pName)} — a synthetic type marker that the reasoner
     * uses to detect duplicate values (sameAs candidates) in its post-processing pass.
     * This is a pragmatic approximation; full sameAs materialisation is a Phase O3+ concern.</p>
     */
    private void compileFunctionalRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlObjectProperty prop : ontology.objectProperties().values()) {
            String pName = prop.localName();

            if (prop.isFunctional()) {
                // prp-fp: if P(X,Y) exists, X is a "functional source" for P
                // (sameAs merging of two Y values is deferred to Phase O3+)
                builder.add(FolRule.builder("prp-fp-" + pName)
                        .weight(HARD)
                        .antecedent(Constraints.edgeOfType("X", "Y", pName))
                        .consequent(Constraints.hasType("X", "_fp_" + pName))
                        .build());
            }

            if (prop.isInverseFunctional()) {
                // prp-ifp: if P(Y,X) exists, X is a "functional target" for P
                builder.add(FolRule.builder("prp-ifp-" + pName)
                        .weight(HARD)
                        .antecedent(Constraints.edgeOfType("Y", "X", pName))
                        .consequent(Constraints.hasType("X", "_ifp_" + pName))
                        .build());
            }
        }
    }

    // ─── cls-oo / cls-oo-sym: equivalentClass ────────────────────────────────────

    /**
     * {@code cls-oo}: {@code C owl:equivalentClass D}, {@code a: C} → {@code a: D}.
     * {@code cls-oo-sym}: {@code C owl:equivalentClass D}, {@code a: D} → {@code a: C}.
     *
     * <p>Both directions are emitted for each equivalentClass pair, making equivalence
     * bidirectional as per OWL semantics.</p>
     */
    private void compileEquivalenceRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlClass owlClass : ontology.classes().values()) {
            String cName = owlClass.localName();
            for (String eqIri : owlClass.equivalentClassIris()) {
                String dName = localName(eqIri, ontology);
                builder.add(FolRule.builder("cls-oo-" + cName + "-" + dName)
                        .weight(HARD)
                        .antecedent(Constraints.hasType("X", cName))
                        .consequent(Constraints.hasType("X", dName))
                        .build());
                builder.add(FolRule.builder("cls-oo-sym-" + cName + "-" + dName)
                        .weight(HARD)
                        .antecedent(Constraints.hasType("X", dName))
                        .consequent(Constraints.hasType("X", cName))
                        .build());
            }
        }
    }

    // ─── cax-dw: disjointWith → inconsistency ────────────────────────────────────

    /**
     * {@code cax-dw}: {@code C owl:disjointWith D}, {@code a: C}, {@code a: D}
     * → {@link Constraints#falsehood()} (inconsistency signal).
     *
     * <p>The antecedent is the conjunction of both type memberships. The consequent is
     * {@link Constraints#falsehood()}, which always evaluates to {@code false}: when the
     * antecedent holds the rule is violated, and {@link OwlRlReasoner} detects violated
     * falsehood-consequent rules and records them as {@link OwlInconsistency} entries.</p>
     *
     * <p>Weight: {@link Double#POSITIVE_INFINITY} (hard) — per user decision O2, disjointness
     * violations are crisp inconsistencies, not soft penalties. The PSL engine would normally
     * use a high finite weight for soft semantics; here we use the hard weight because the
     * reasoner reads violated rules directly rather than relying on the PSL energy's value.</p>
     */
    private void compileDisjointnessRules(OwlOntology ontology, FolRuleSet.Builder builder) {
        for (OwlClass owlClass : ontology.classes().values()) {
            String cName = owlClass.localName();
            for (String disjIri : owlClass.disjointWithIris()) {
                String dName = localName(disjIri, ontology);
                // Guard: avoid emitting the same rule twice for C-D and D-C
                // (since disjointWith is symmetric in OWL, both classes may declare each other)
                if (cName.compareTo(dName) > 0) continue;

                builder.add(FolRule.builder("cax-dw-" + cName + "-" + dName)
                        .weight(HARD)
                        .antecedent(Constraints.and(
                                Constraints.hasType("X", cName),
                                Constraints.hasType("X", dName)))
                        .consequent(Constraints.falsehood())
                        .build());
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Resolve the local name of an IRI, looking up the class/property registry first
     * (so declared names are used verbatim) and falling back to the IRI's last-path-segment.
     */
    private static String localName(String iri, OwlOntology ontology) {
        if (iri == null) return "";
        OwlClass cls = ontology.classes().get(iri);
        if (cls != null) return cls.localName();
        OwlObjectProperty prop = ontology.objectProperties().get(iri);
        if (prop != null) return prop.localName();
        // Fallback: derive from IRI
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) return iri.substring(slash + 1);
        return iri;
    }

    /** Sanitize a name for use in rule identifiers (alphanumeric + underscore). */
    private static String sanitize(String name) {
        if (name == null) return "anon";
        return name.replaceAll("[^A-Za-z0-9]", "_");
    }
}
