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

import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase O2 — verifies the OWL 2 RL reasoner ({@link OwlRlReasoner}) and the
 * rule compiler ({@link OwlRlRuleCompiler}).
 *
 * <h2>Test scenarios</h2>
 * <ul>
 *   <li>T1 {@code cax-sco} — entity typed Dog infers Animal via subclass rule</li>
 *   <li>T2 {@code prp-dom} — {@code P(x,y)} with domain C infers {@code x: C}</li>
 *   <li>T3 {@code prp-rng} — {@code P(x,y)} with range C infers {@code y: C}</li>
 *   <li>T4 {@code prp-trp} (BFS) — transitive {@code ancestorOf}: a→b→c infers a→c</li>
 *   <li>T5 {@code prp-trp} 4-node chain — a→b→c→d infers a→c, a→d, b→d</li>
 *   <li>T6 {@code prp-symp} — symmetric {@code knows}: a→b infers b→a rule compiled</li>
 *   <li>T7 {@code prp-inv} — {@code hasParent} / {@code hasChild} inverse pair</li>
 *   <li>T8 {@code cax-dw} — entity in two disjoint classes → inconsistency</li>
 *   <li>T9 {@code prp-fp} — functional property rule compiled (smoke test)</li>
 * </ul>
 */
class OwlRlReasonerTest {

    // ─── Shared IRI helpers ───────────────────────────────────────────────────────

    private static final String ANIMAL_IRI  = OwlIri.classIri("Animal");
    private static final String MAMMAL_IRI  = OwlIri.classIri("Mammal");
    private static final String DOG_IRI     = OwlIri.classIri("Dog");
    private static final String PERSON_IRI  = OwlIri.classIri("Person");
    private static final String ROBOT_IRI   = OwlIri.classIri("Robot");
    private static final String HUMAN_IRI   = OwlIri.classIri("Human");
    private static final String EMPLOYEE_IRI = OwlIri.classIri("Employee");

    private static final String ANCESTOR_OF_IRI = OwlIri.propIri("ancestorOf");
    private static final String KNOWS_IRI       = OwlIri.propIri("knows");
    private static final String HAS_PARENT_IRI  = OwlIri.propIri("hasParent");
    private static final String HAS_CHILD_IRI   = OwlIri.propIri("hasChild");
    private static final String WORKS_WITH_IRI  = OwlIri.propIri("worksWith");
    private static final String MANAGES_IRI     = OwlIri.propIri("manages");
    private static final String DIRECTLY_MANAGES_IRI = OwlIri.propIri("directlyManages");

    private final OwlRlReasoner reasoner = new OwlRlReasoner();

    @Test
    @DisplayName("OwlRlResult preserves all inferred classes with a legacy first-type view")
    void resultPreservesMultipleInferredTypes() {
        OwlRlResult result = OwlRlResult.ofMultiTypes(
                List.of(),
                Map.of("rex", List.of(MAMMAL_IRI, ANIMAL_IRI)),
                List.of());

        assertEquals(2, result.inferredTypeCount());
        assertEquals(MAMMAL_IRI, result.inferredTypes().get("rex"),
                "Legacy inferredTypes() should expose the first candidate for existing callers");
        assertEquals(List.of(MAMMAL_IRI, ANIMAL_IRI), result.inferredTypeCandidates().get("rex"),
                "Full candidate view must retain every inferred class for the entity");
    }

    // ─── T1: cax-sco — subClassOf type propagation ───────────────────────────────

    /**
     * {@code cax-sco}: entity typed {@code Dog} (Dog ⊑ Animal) is inferred to also be an Animal.
     *
     * <p>The reasoner materializes the crisp OWL-RL type closure into
     * {@link OwlRlResult#inferredTypeCandidates()} without depending on PSL soft-truth extraction.</p>
     */
    @Test
    @DisplayName("cax-sco: Dog ⊑ Animal → entity typed Dog inferred as Animal")
    void t1_caxSco_dogSubclassAnimal() {
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(OwlClass.of(ANIMAL_IRI).build())
                .addClass(OwlClass.of(MAMMAL_IRI).subClassOf(ANIMAL_IRI).build())
                .addClass(OwlClass.of(DOG_IRI).subClassOf(MAMMAL_IRI).build())
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("fido", "Dog", "Fido");

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        // Verify the rule was compiled
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().contains("cax-sco")),
                "Compiler must emit a cax-sco rule for Dog ⊑ Animal");

        OwlRlResult result = reasoner.reason(graph, ontology);
        List<String> fidoTypes = result.inferredTypeCandidates().getOrDefault("fido", List.of());
        assertTrue(fidoTypes.contains(MAMMAL_IRI),
                "Dog membership must infer the direct superclass Mammal");
        assertTrue(fidoTypes.contains(ANIMAL_IRI),
                "Dog membership must infer the transitive superclass Animal");
        assertFalse(fidoTypes.contains(DOG_IRI),
                "The asserted Dog membership should not be re-emitted as an inferred candidate");
    }

    // ─── T2: prp-dom — domain typing ─────────────────────────────────────────────

    /**
     * {@code prp-dom}: property {@code manages} has domain {@code Employee}.
     * An entity {@code alice} that has an outgoing {@code manages} edge must be inferred as
     * an {@code Employee}.
     *
     * <p>The compiler still emits the corresponding rule, but the result assertion verifies the
     * deterministic OWL-RL ABox type materialization path.</p>
     */
    @Test
    @DisplayName("prp-dom: manages property domain=Employee → source inferred as Employee")
    void t2_prpDom_domainTypeRule() {
        OwlObjectProperty manages = OwlObjectProperty.of(MANAGES_IRI)
                .domain(EMPLOYEE_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(OwlClass.of(EMPLOYEE_IRI).build())
                .addObjectProperty(manages)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Unknown", "Alice");
        graph.addEntity("bob",   "Unknown", "Bob");
        graph.addRelation("r1", "alice", "bob", "manages", 1.0);

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().startsWith("prp-dom-manages")),
                "Compiler must emit prp-dom-manages rule");

        // Run the full reasoner — result should have no errors
        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result.inferredTypeCandidates().getOrDefault("alice", List.of()).contains(EMPLOYEE_IRI),
                "A manages source must be inferred as the property's Employee domain");
    }

    // ─── T3: prp-rng — range typing ──────────────────────────────────────────────

    /**
     * {@code prp-rng}: property {@code worksWith} has range {@code Person}.
     * An entity that is the target of a {@code worksWith} edge must be inferred as a
     * {@code Person}.
     */
    @Test
    @DisplayName("prp-rng: worksWith property range=Person → target inferred as Person")
    void t3_prpRng_rangeTypeRule() {
        OwlObjectProperty worksWith = OwlObjectProperty.of(WORKS_WITH_IRI)
                .range(PERSON_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(OwlClass.of(PERSON_IRI).build())
                .addObjectProperty(worksWith)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Unknown", "Alice");
        graph.addEntity("bob",   "Unknown", "Bob");
        graph.addRelation("r1", "alice", "bob", "worksWith", 1.0);

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().startsWith("prp-rng-worksWith")),
                "Compiler must emit prp-rng-worksWith rule");

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result.inferredTypeCandidates().getOrDefault("bob", List.of()).contains(PERSON_IRI),
                "A worksWith target must be inferred as the property's Person range");
    }

    @Test
    @DisplayName("cls-oo: Human equivalentClass Person → type propagation is bidirectional")
    void clsOo_equivalentClassBidirectionalTypePropagation() {
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(OwlClass.of(HUMAN_IRI).equivalentClass(PERSON_IRI).build())
                .addClass(OwlClass.of(PERSON_IRI).build())
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Human", "Alice");
        graph.addEntity("bob", "Person", "Bob");

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result.inferredTypeCandidates().getOrDefault("alice", List.of()).contains(PERSON_IRI),
                "Human membership must infer equivalent Person membership");
        assertTrue(result.inferredTypeCandidates().getOrDefault("bob", List.of()).contains(HUMAN_IRI),
                "Person membership must infer equivalent Human membership");
    }

    @Test
    @DisplayName("prp-dom/rng: subPropertyOf inherits super-property domain and range typing")
    void prpDomRng_subPropertyInheritsDomainAndRangeTyping() {
        OwlObjectProperty manages = OwlObjectProperty.of(MANAGES_IRI)
                .domain(EMPLOYEE_IRI)
                .range(PERSON_IRI)
                .build();
        OwlObjectProperty directlyManages = OwlObjectProperty.of(DIRECTLY_MANAGES_IRI)
                .subPropertyOf(MANAGES_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(OwlClass.of(EMPLOYEE_IRI).build())
                .addClass(OwlClass.of(PERSON_IRI).build())
                .addObjectProperty(manages)
                .addObjectProperty(directlyManages)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Unknown", "Alice");
        graph.addEntity("bob", "Unknown", "Bob");
        graph.addRelation("r1", "alice", "bob", "directlyManages", 1.0);

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result.inferredTypeCandidates().getOrDefault("alice", List.of()).contains(EMPLOYEE_IRI),
                "A directlyManages source must inherit Employee typing from manages domain");
        assertTrue(result.inferredTypeCandidates().getOrDefault("bob", List.of()).contains(PERSON_IRI),
                "A directlyManages target must inherit Person typing from manages range");
    }

    // ─── T4: prp-trp BFS — 3-node chain a→b→c ───────────────────────────────────

    /**
     * {@code prp-trp} via BFS: transitive property {@code ancestorOf}, chain a→b→c.
     * The BFS pass must infer a→c as a new {@link GraphRelation}.
     *
     * <p>This verifies option (c) from the design decision: BFS produces the closure edge
     * directly, NOT through pair-grounding in {@link ai.kompile.graph.reasoning.fol.FolInferenceService}.</p>
     */
    @Test
    @DisplayName("prp-trp BFS: ancestorOf a→b→c infers a→c as new GraphRelation")
    void t4_prpTrp_bfs_threeNodeChain() {
        OwlObjectProperty ancestorOf = OwlObjectProperty.of(ANCESTOR_OF_IRI)
                .transitive(true)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addObjectProperty(ancestorOf)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "Person", "A");
        graph.addEntity("b", "Person", "B");
        graph.addEntity("c", "Person", "C");
        graph.addRelation(SimpleGraphRelation.directed("r-ab", "a", "b", "ancestorOf", 1.0));
        graph.addRelation(SimpleGraphRelation.directed("r-bc", "b", "c", "ancestorOf", 1.0));

        OwlRlResult result = reasoner.reason(graph, ontology);

        List<GraphRelation> inferred = result.inferredRelations();
        assertFalse(inferred.isEmpty(),
                "BFS must infer at least one transitive relation (a→c)");

        Set<String> inferredPairs = inferred.stream()
                .map(r -> r.sourceId() + "→" + r.targetId())
                .collect(Collectors.toSet());

        assertTrue(inferredPairs.contains("a→c"),
                "BFS must infer a→c via the chain a→b→c for transitive ancestorOf");

        // Verify all inferred relations have the correct type
        inferred.forEach(r -> assertEquals("ancestorOf", r.type(),
                "All BFS-inferred relations must have type 'ancestorOf'"));
    }

    // ─── T5: prp-trp BFS — 4-node chain, full closure ────────────────────────────

    /**
     * {@code prp-trp} BFS: 4-node chain a→b→c→d.
     * The closure must include: a→c, a→d, b→d (and not a→b, b→c, c→d which already exist).
     *
     * <p>This test verifies that the BFS pass correctly computes the FULL transitive closure
     * and does not hit the 10,000-pair grounding cap (the cap would affect the PSL grounder
     * but not this BFS implementation).</p>
     */
    @Test
    @DisplayName("prp-trp BFS: 4-node chain a→b→c→d produces full closure {a→c, a→d, b→d}")
    void t5_prpTrp_bfs_fourNodeChain_fullClosure() {
        OwlObjectProperty ancestorOf = OwlObjectProperty.of(ANCESTOR_OF_IRI)
                .transitive(true)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addObjectProperty(ancestorOf)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "Person", "A");
        graph.addEntity("b", "Person", "B");
        graph.addEntity("c", "Person", "C");
        graph.addEntity("d", "Person", "D");
        graph.addRelation(SimpleGraphRelation.directed("r-ab", "a", "b", "ancestorOf", 1.0));
        graph.addRelation(SimpleGraphRelation.directed("r-bc", "b", "c", "ancestorOf", 1.0));
        graph.addRelation(SimpleGraphRelation.directed("r-cd", "c", "d", "ancestorOf", 1.0));

        OwlRlResult result = reasoner.reason(graph, ontology);
        List<GraphRelation> inferred = result.inferredRelations();

        Set<String> inferredPairs = inferred.stream()
                .map(r -> r.sourceId() + "→" + r.targetId())
                .collect(Collectors.toSet());

        assertTrue(inferredPairs.contains("a→c"),
                "4-node chain: a→c must be in the BFS transitive closure");
        assertTrue(inferredPairs.contains("a→d"),
                "4-node chain: a→d must be in the BFS transitive closure");
        assertTrue(inferredPairs.contains("b→d"),
                "4-node chain: b→d must be in the BFS transitive closure");

        // Direct edges must NOT be duplicated in the inferred set
        assertFalse(inferredPairs.contains("a→b"),
                "Direct edge a→b must not be re-emitted by BFS");
        assertFalse(inferredPairs.contains("b→c"),
                "Direct edge b→c must not be re-emitted by BFS");
        assertFalse(inferredPairs.contains("c→d"),
                "Direct edge c→d must not be re-emitted by BFS");
    }

    // ─── T6: prp-symp — symmetric property rule compiled ────────────────────────

    /**
     * {@code prp-symp}: symmetric property {@code knows}.
     * The rule {@code knows(a,b) → knows(b,a)} must be compiled and the result must
     * be non-null (the FOL engine evaluates the constraint over pairs).
     */
    @Test
    @DisplayName("prp-symp: symmetric knows property → rule compiled and reasoner runs")
    void t6_prpSymp_symmetricKnows() {
        OwlObjectProperty knows = OwlObjectProperty.of(KNOWS_IRI)
                .symmetric(true)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addObjectProperty(knows)
                .build();

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().startsWith("prp-symp-knows")),
                "Compiler must emit prp-symp-knows rule");

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "Person", "Alice");
        graph.addEntity("b", "Person", "Bob");
        graph.addRelation(SimpleGraphRelation.directed("r-ab", "a", "b", "knows", 1.0));

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result != null, "Reasoner must return a non-null result");
        assertTrue(result.isConsistent(), "A graph with symmetric knows only must be consistent");
    }

    // ─── T7: prp-inv — inverse property pair ─────────────────────────────────────

    /**
     * {@code prp-inv1/2}: {@code hasParent owl:inverseOf hasChild}.
     * Both rules must be compiled: one for each direction of the inverse pair.
     */
    @Test
    @DisplayName("prp-inv: hasParent/hasChild inverse pair → both rules compiled")
    void t7_prpInv_hasParentHasChild() {
        // hasParent inverseOf hasChild
        OwlObjectProperty hasParent = OwlObjectProperty.of(HAS_PARENT_IRI)
                .inverseOf(HAS_CHILD_IRI)
                .build();
        OwlObjectProperty hasChild = OwlObjectProperty.of(HAS_CHILD_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addObjectProperty(hasParent)
                .addObjectProperty(hasChild)
                .build();

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        List<String> ruleNames = rules.rules().stream()
                .map(r -> r.name())
                .collect(Collectors.toList());

        // prp-inv1: hasParent(X,Y) → hasChild(Y,X)
        assertTrue(ruleNames.stream().anyMatch(n -> n.startsWith("prp-inv1-hasParent")),
                "Compiler must emit prp-inv1-hasParent rule");
        // prp-inv2: hasChild(X,Y) → hasParent(Y,X)
        assertTrue(ruleNames.stream().anyMatch(n -> n.startsWith("prp-inv2-hasParent")),
                "Compiler must emit prp-inv2-hasParent rule");

        // Run reasoner on a small graph: alice hasParent bob should imply bob hasChild alice
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Person", "Alice");
        graph.addEntity("bob",   "Person", "Bob");
        graph.addRelation(SimpleGraphRelation.directed("r1", "alice", "bob", "hasParent", 1.0));

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result != null, "Reasoner must produce a result");
    }

    // ─── T8: cax-dw — disjoint classes → inconsistency ──────────────────────────

    /**
     * {@code cax-dw}: {@code Person owl:disjointWith Robot}.
     * An entity typed as {@code Person} but also carrying an {@code additionalType=Robot}
     * attribute must appear in {@link OwlRlResult#inconsistencies()}.
     *
     * <p>The {@code GraphEntity} model has a single {@link ai.kompile.graph.reasoning.model.GraphEntity#type()}
     * field; the second type is expressed via the {@code "additionalType"} attribute, which the
     * reasoner's direct disjointness scan reads (see {@link OwlRlReasoner#detectDisjointViolations}).
     * </p>
     */
    @Test
    @DisplayName("cax-dw: entity typed Person with additionalType=Robot (disjointWith) → OwlInconsistency")
    void t8_caxDw_disjointClassViolation() {
        OwlClass person = OwlClass.of(PERSON_IRI)
                .disjointWith(ROBOT_IRI)
                .build();
        OwlClass robot = OwlClass.of(ROBOT_IRI)
                .disjointWith(PERSON_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(person)
                .addClass(robot)
                .build();

        // Verify the rule is compiled
        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().startsWith("cax-dw")),
                "Compiler must emit cax-dw rule for Person disjointWith Robot");

        // Entity typed Person but also carrying additionalType=Robot
        MutableReasoningGraph graph = new MutableReasoningGraph();
        var entityBuilder = ai.kompile.graph.reasoning.model.GraphEntity.builder("cyborg-1")
                .type("Person")
                .label("Cyborg")
                .attribute("additionalType", "Robot");
        graph.addEntity(entityBuilder.build());

        OwlRlResult result = reasoner.reason(graph, ontology);

        assertFalse(result.inconsistencies().isEmpty(),
                "An entity typed as both Person and Robot (disjoint) must produce an OwlInconsistency");

        OwlInconsistency violation = result.inconsistencies().get(0);
        assertEquals("cax-dw", violation.ruleId(),
                "Inconsistency rule id must be 'cax-dw'");
        assertEquals("cyborg-1", violation.entityId(),
                "Inconsistency must identify the violating entity");
        assertEquals(1.0, violation.violationDegree(), 0.001,
                "cax-dw violation must be crisp (degree=1.0)");
    }

    @Test
    @DisplayName("cax-dw: inferred candidate disjoint with declared type → OwlInconsistency")
    void caxDw_inferredTypeCandidateDisjointWithDeclaredType() {
        OwlClass person = OwlClass.of(PERSON_IRI)
                .disjointWith(ROBOT_IRI)
                .build();
        OwlClass robot = OwlClass.of(ROBOT_IRI)
                .disjointWith(PERSON_IRI)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(person)
                .addClass(robot)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("cyborg-2", "Person", "Cyborg");

        List<OwlInconsistency> inconsistencies = new ArrayList<>();
        reasoner.detectDisjointViolations(
                graph,
                ontology,
                Map.of("cyborg-2", List.of(ROBOT_IRI)),
                inconsistencies);

        assertFalse(inconsistencies.isEmpty(),
                "A declared Person with inferred Robot membership must violate Person disjointWith Robot");
        OwlInconsistency violation = inconsistencies.get(0);
        assertEquals("cax-dw", violation.ruleId(),
                "Inconsistency rule id must be 'cax-dw'");
        assertEquals("cyborg-2", violation.entityId(),
                "Inconsistency must identify the violating entity");
    }

    // ─── T9: prp-fp — functional property smoke test ─────────────────────────────

    /**
     * {@code prp-fp}: functional property {@code manages} (at most one value per source entity).
     * The rule must be compiled and the reasoner must run without error.
     */
    @Test
    @DisplayName("prp-fp: functional manages property → rule compiled and reasoner runs cleanly")
    void t9_prpFp_functionalProperty() {
        OwlObjectProperty manages = OwlObjectProperty.of(MANAGES_IRI)
                .functional(true)
                .build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addObjectProperty(manages)
                .build();

        FolRuleSet rules = new OwlRlRuleCompiler().compile(ontology);
        assertTrue(rules.rules().stream().anyMatch(r -> r.name().startsWith("prp-fp-manages")),
                "Compiler must emit prp-fp-manages rule");

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("mgr", "Employee", "Manager");
        graph.addEntity("emp", "Employee", "Employee");
        graph.addRelation(SimpleGraphRelation.directed("r1", "mgr", "emp", "manages", 1.0));

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result != null, "Reasoner must return a result for a functional property ontology");
        assertTrue(result.isConsistent(),
                "Graph with functional manages (one edge, no conflict) must be consistent");
    }

    // ─── T10: no inconsistency for consistent graph ───────────────────────────────

    /**
     * Regression: a graph with no OWL violations must return an empty inconsistencies list.
     */
    @Test
    @DisplayName("clean graph: no violations → isConsistent() = true")
    void t10_cleanGraph_noViolations() {
        OwlClass person = OwlClass.of(PERSON_IRI)
                .disjointWith(ROBOT_IRI)
                .build();
        OwlClass robot = OwlClass.of(ROBOT_IRI).build();
        OwlOntology ontology = OwlOntology.anonymous()
                .addClass(person)
                .addClass(robot)
                .build();

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "Person", "Alice");  // only Person, not Robot
        graph.addEntity("r2d2", "Robot", "R2D2");     // only Robot, not Person

        OwlRlResult result = reasoner.reason(graph, ontology);
        assertTrue(result.isConsistent(),
                "Graph with non-overlapping disjoint types must be consistent");
        assertTrue(result.inconsistencies().isEmpty(),
                "No OwlInconsistency entries must be present for a consistent graph");
    }
}
