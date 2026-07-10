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
package ai.kompile.graph.reasoning.e2e;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.fol.ReasoningGraphKnowledgeBase;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.learning.MebnWeightLearner;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import ai.kompile.graph.reasoning.mebn.SSBNGenerator;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive end-to-end test covering the MEBN, FOL, and TYPE subsystems of
 * {@code kompile-graph-reasoning}, driven from {@link UnifiedGraph} / {@link MutableReasoningGraph}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>{@link TypeRegistry} / {@link TypeHierarchy}: declaration, isA transitivity, subtype
 *       entity inclusion, cycle safety.</li>
 *   <li>{@link RelationalMTheoryBuilder}: fragment structure, entity types, context constraints,
 *       edge-strength wiring.</li>
 *   <li>{@link SSBNGenerator}: relational grounding against a
 *       {@link ReasoningGraphKnowledgeBase}, BN node creation.</li>
 *   <li>{@link InferredFactMaterializer}: binary and unary atom materialization into
 *       {@link MutableReasoningGraph}.</li>
 *   <li>{@link RecursiveQueryEngine}: semi-naive fixpoint transitive-closure derivation.</li>
 *   <li>{@link DerivationTree}: record construction and structural accessors.</li>
 *   <li>{@link MebnWeightLearner}: noisy-OR edge-strength learning reduces SSE.</li>
 *   <li>{@link UnifiedGraph} round-trip: {@link MTheory} + {@link TypeRegistry} survive
 *       save → load, and the reasoning results (isA queries) are identical before and after.</li>
 * </ul>
 *
 * <h2>Out-of-scope (noted)</h2>
 * <ul>
 *   <li>{@link DerivationTree#build(String, ai.kompile.graph.reasoning.fol.InferredFactStore,
 *       ai.kompile.graph.reasoning.tms.JustificationIndex, int)} — requires a live
 *       {@code JustificationIndex} populated from a MAP solve; no lightweight stub path exists
 *       in the lib; the record constructor path covers structure/accessors adequately.</li>
 * </ul>
 */
class MebnFolTypeE2ETest {

    // ─── shared helpers ────────────────────────────────────────────────────────────

    /** Build a small graph: alice + bob (Person), carol (Org), alice→bob KNOWS edge. */
    private static MutableReasoningGraph smallGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").weight(0.9).build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").weight(0.7).build());
        g.addEntity(GraphEntity.builder("carol").type("Org").label("Acme Corp").weight(0.5).build());
        g.addRelation("r1", "alice", "bob", "KNOWS", 0.8);
        return g;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 1. TypeHierarchy.fromGraph — computed membership from UnifiedGraph
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TypeHierarchy.fromGraph groups entities by type() — computed from UnifiedGraph")
    void typeHierarchyMembershipFromUnifiedGraph() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "Person", "Alice");
        g.addEntity("bob",   "Person", "Bob");
        g.addEntity("carol", "Org",    "Acme Corp");

        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(g);

        Set<String> personIds = hierarchy.membersOf("Person");
        assertEquals(2, personIds.size(), "Person membership must include both alice and bob");
        assertTrue(personIds.contains("alice"), "alice must be in Person membership");
        assertTrue(personIds.contains("bob"),   "bob must be in Person membership");

        Set<String> orgIds = hierarchy.membersOf("Org");
        assertEquals(Set.of("carol"), orgIds, "Org membership must contain only carol");

        assertTrue(hierarchy.membersOf("Unknown").isEmpty(),
                "Unknown type must return an empty membership set");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 2. TypeRegistry isA — transitivity, reflexivity, asymmetry
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TypeRegistry isA: reflexive, transitive (Entity⊃Person⊃Employee), asymmetric")
    void typeRegistryIsATransitiveAndAsymmetric() {
        // Hierarchy: Entity ← Person ← Employee
        TypeRegistry registry = new TypeRegistry()
                .declare("Entity")
                .declare("Person")
                .subtype("Person", "Entity")      // Person isA Entity
                .declare("Employee")
                .subtype("Employee", "Person");   // Employee isA Person (and transitively Entity)

        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Employee").label("Bob").build());

        TypeHierarchy hierarchy = registry.buildFor(g);

        // Reflexive
        assertTrue(hierarchy.isA("Person", "Person"),   "isA must be reflexive: Person isA Person");
        assertTrue(hierarchy.isA("Employee", "Employee"), "isA must be reflexive: Employee isA Employee");
        assertTrue(hierarchy.isA("Entity", "Entity"),    "isA must be reflexive: Entity isA Entity");

        // Direct links
        assertTrue(hierarchy.isA("Person", "Entity"),   "Person isA Entity (direct)");
        assertTrue(hierarchy.isA("Employee", "Person"), "Employee isA Person (direct)");

        // Transitive: Employee isA Entity (two hops via Person)
        assertTrue(hierarchy.isA("Employee", "Entity"), "Employee isA Entity (transitive via Person)");

        // Asymmetric — reverse direction must be false
        assertFalse(hierarchy.isA("Entity", "Person"),    "Entity is NOT a subtype of Person");
        assertFalse(hierarchy.isA("Person", "Employee"),  "Person is NOT a subtype of Employee");
        assertFalse(hierarchy.isA("Entity", "Employee"),  "Entity is NOT a subtype of Employee");

        // Unrelated types
        assertFalse(hierarchy.isA("Person", "Org"),  "Person and Org are unrelated");
        assertFalse(hierarchy.isA("Org", "Person"),  "Org and Person are unrelated");

        // Unknown
        assertFalse(hierarchy.isA("Ghost", "Entity"), "Unknown child must return false");

        // ancestors / descendants sanity
        List<String> ancs = hierarchy.ancestors("Employee");
        assertTrue(ancs.contains("Person"), "ancestors of Employee must include Person");
        assertTrue(ancs.contains("Entity"), "ancestors of Employee must include Entity");

        List<String> descs = hierarchy.descendants("Entity");
        assertTrue(descs.contains("Person"),   "descendants of Entity must include Person");
        assertTrue(descs.contains("Employee"), "descendants of Entity must include Employee");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 3. entitiesOfType with includeSubtypes — inherited membership
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("entitiesOfType(type, true) includes subtype members; false returns only exact-type members")
    void typeHierarchySubtypeEntityInclusion() {
        TypeRegistry registry = new TypeRegistry()
                .declare("Entity")
                .declare("Person")
                .subtype("Person", "Entity")
                .declare("Employee")
                .subtype("Employee", "Person");

        // alice has type Person, bob has type Employee — no entity has exact type Entity
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Employee").label("Bob").build());

        TypeHierarchy hierarchy = registry.buildFor(g);

        // Without subtypes: Entity has no members (no entity has type = "Entity" exactly)
        assertTrue(hierarchy.entitiesOfType("Entity", false).isEmpty(),
                "exact-type lookup for Entity must be empty — no entity has type 'Entity' directly");

        // With subtypes: Entity inherits alice (via Person) and bob (via Employee)
        Set<String> allEntities = hierarchy.entitiesOfType("Entity", true);
        assertEquals(2, allEntities.size(),
                "entitiesOfType(Entity, includeSubtypes=true) must return alice and bob");
        assertTrue(allEntities.contains("alice"), "alice (Person) must appear under Entity via subtype");
        assertTrue(allEntities.contains("bob"),   "bob (Employee) must appear under Entity via subtype");

        // Person with subtypes: alice (Person) + bob (Employee isA Person)
        Set<String> personAndBelow = hierarchy.entitiesOfType("Person", true);
        assertTrue(personAndBelow.contains("alice"), "alice is directly a Person");
        assertTrue(personAndBelow.contains("bob"),   "bob (Employee isA Person) included with subtypes");

        // Person without subtypes: only alice
        Set<String> exactPerson = hierarchy.entitiesOfType("Person", false);
        assertEquals(Set.of("alice"), exactPerson,
                "exact Person membership must contain only alice");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 4. RelationalMTheoryBuilder — fragment structure, context constraints, edge weights
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RelationalMTheoryBuilder produces correct MFrag structure for one relation")
    void relationalMTheoryBuilderFragmentStructure() {
        List<RelationDescriptor> rels = List.of(
                new RelationDescriptor("KNOWS", "Person", "Person", 0.65,
                        List.of("alice", "bob"), List.of("alice", "bob")));

        MTheory theory = RelationalMTheoryBuilder.build("e2e-theory", rels);

        // Overall shape
        assertEquals("e2e-theory", theory.getName());
        // EntityRelevance + KNOWS = 2 MFrags
        assertEquals(2, theory.getMFrags().size(),
                "Must have EntityRelevance + KNOWS = 2 MFrags");

        // Entity types: AllNodes + Person (de-duplicated since both src and tgt are Person)
        assertEquals(2, theory.getEntityTypes().size(),
                "Must have AllNodes supertype + Person type only (src/tgt are the same type)");

        // Foundational EntityRelevance fragment
        MFrag relevanceFrag = theory.getMFrag("EntityRelevance");
        assertNotNull(relevanceFrag, "EntityRelevance MFrag must be present");
        assertEquals(1, relevanceFrag.getResidentNodes().size(),
                "EntityRelevance must have exactly one resident RV");
        assertEquals(RelationalMTheoryBuilder.RELEVANCE_RV,
                relevanceFrag.getResidentNodes().get(0).getName(),
                "Resident RV of EntityRelevance must be 'isRelevant'");

        // KNOWS MFrag
        MFrag knowsFrag = theory.getMFrag("KNOWS");
        assertNotNull(knowsFrag, "KNOWS MFrag must be present");

        // Resident: binary KNOWS RV
        assertEquals(1, knowsFrag.getResidentNodes().size());
        assertEquals("KNOWS", knowsFrag.getResidentNodes().get(0).getName());
        assertEquals(2, knowsFrag.getResidentNodes().get(0).getArity(),
                "KNOWS resident RV must be binary (src, tgt)");

        // Input: isRelevant
        assertEquals(1, knowsFrag.getInputNodes().size());
        assertEquals(RelationalMTheoryBuilder.RELEVANCE_RV,
                knowsFrag.getInputNodes().get(0).getName(),
                "Input node must be 'isRelevant'");

        // Edge strength = the supplied activationProbability
        assertEquals(0.65, knowsFrag.getEdgeStrength("isRelevant", "KNOWS"), 1e-9,
                "Edge strength must match the supplied activationProbability");

        // Context constraints: hasType(src), hasType(tgt), notEqual, edgeExists = 4
        assertEquals(4, knowsFrag.getContextConstraints().size(),
                "KNOWS MFrag must have exactly 4 context constraints");

        // validate() should report no errors (no input RVs without a home MFrag)
        List<String> errors = theory.validate();
        assertTrue(errors.isEmpty(), "Validated theory must have no structural errors: " + errors);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 5. SSBN grounding via RelationalMTheoryBuilder + ReasoningGraphKnowledgeBase
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SSBNGenerator produces BN nodes for entities and edges present in the graph")
    void ssbNGroundingWithRelationalMTheoryYieldsBnNodes() {
        // Graph: alice (Person) → bob (Person) via KNOWS
        MutableReasoningGraph g = smallGraph();

        // Theory: "KNOWS" relation over Person entities, activation 0.7
        List<RelationDescriptor> rels = List.of(
                new RelationDescriptor("KNOWS", "Person", "Person", 0.7,
                        List.of("alice", "bob"), List.of("alice", "bob")));
        MTheory theory = RelationalMTheoryBuilder.build("ssbn-e2e", rels);

        // KB backed by the actual graph so edgeExists/entityExists/hasType resolve correctly
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(g);

        BayesianNetwork ssbn = new SSBNGenerator(theory, kb).generate();

        // EntityRelevance grounds over AllNodes entities (alice, bob are in AllNodes)
        // KNOWS grounds over Person×Person pairs that pass all 4 constraints:
        //   hasType(alice, Person) ✓, hasType(bob, Person) ✓,
        //   notEqual(alice, bob) ✓, edgeExists(alice, bob) ✓  → KNOWS(alice, bob)
        //   notEqual(bob, alice) ✓, edgeExists(bob, alice) ✗  → filtered out
        //   (alice,alice) and (bob,bob) filtered by notEqual

        assertTrue(ssbn.size() >= 2,
                "SSBN must contain at least the isRelevant nodes for alice and bob");

        boolean hasIsRelevant = ssbn.getNodes().stream()
                .anyMatch(n -> n.getVariableName().contains("isRelevant"));
        assertTrue(hasIsRelevant,
                "EntityRelevance fragment must produce at least one isRelevant BN node");

        boolean hasKnowsNode = ssbn.getNodes().stream()
                .anyMatch(n -> n.getVariableName().contains("KNOWS"));
        assertTrue(hasKnowsNode,
                "KNOWS fragment must produce a grounded BN node for the alice→bob edge");

        // All nodes must have a valid variable name
        for (var node : ssbn.getNodes()) {
            assertNotNull(node.getVariableName(), "BN node must have a non-null variable name");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 6. InferredFactMaterializer — binary and unary atom materialization
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("InferredFactMaterializer: binary atoms → relations, unary atoms → attributes; INFERRED tag applied")
    void inferredFactMaterializationBinaryAndUnary() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        // pre-existing entity to verify non-clobber on upsert
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice")
                .attribute("score", 0.5).build());

        // Binary: Causes(alice, bob) with soft-truth 0.85
        InferredFact binary = InferredFact.of("Causes(alice, bob)", 0.85,
                List.of(), List.of("transitivity-rule"), "run-1", 1L);

        // Unary: Fraudulent(alice) with soft-truth 0.9
        InferredFact unary = InferredFact.of("Fraudulent(alice)", 0.9,
                List.of(), List.of(), "run-1", 2L);

        // Non-atom: should be skipped
        InferredFact nonAtom = InferredFact.of("bare-text-not-an-atom", 0.5,
                List.of(), List.of(), "run-1", 3L);

        var result = InferredFactMaterializer.materialize(List.of(binary, unary, nonAtom), g);

        // binary → 1 relation added; unary → 1 attribute set; non-atom → 1 skipped
        assertEquals(1, result.relationsAdded(), "binary atom must produce one inferred relation");
        assertEquals(1, result.attributesSet(),  "unary atom must set one inferred attribute");
        assertEquals(1, result.skipped(),        "non-atom must be counted as skipped");

        // Binary: alice → bob CAUSES relation
        List<GraphRelation> outgoing = g.outgoing("alice");
        assertTrue(outgoing.stream().anyMatch(r -> r.type().equals("Causes") && r.targetId().equals("bob")),
                "Causes relation from alice to bob must be present after materialization");
        GraphRelation causesRel = outgoing.stream()
                .filter(r -> r.type().equals("Causes")).findFirst().orElseThrow();
        assertEquals(0.85, causesRel.weight(), 1e-9, "soft-truth must be stored as relation weight");
        assertTrue(causesRel.tags().contains(InferredFactMaterializer.INFERRED_TAG),
                "inferred relation must carry the INFERRED tag");

        // Unary: alice's inferred.Fraudulent attribute
        GraphEntity alice = g.entity("alice").orElseThrow();
        assertEquals("Person", alice.type(), "alice's original type must not be clobbered on upsert");
        assertEquals(0.5, ((Number) alice.attributes().get("score")).doubleValue(), 1e-9,
                "alice's pre-existing score attribute must not be overwritten");
        Object fraudVal = alice.attributes().get("inferred.Fraudulent");
        assertNotNull(fraudVal, "inferred.Fraudulent attribute must be set on alice");
        assertEquals(0.9, ((Number) fraudVal).doubleValue(), 1e-9,
                "inferred.Fraudulent must carry the soft-truth value");
        assertTrue(alice.tags().contains(InferredFactMaterializer.INFERRED_TAG),
                "alice must carry the INFERRED tag after unary materialization");

        // parseAtom round-trip
        var parsed = InferredFactMaterializer.parseAtom("Causes(alice, bob)");
        assertNotNull(parsed);
        assertEquals("Causes", parsed.predicate());
        assertEquals(List.of("alice", "bob"), parsed.args());
        assertNotNull(InferredFactMaterializer.parseAtom("State(x)"));
        // Non-atom and null return null
        assertNull(InferredFactMaterializer.parseAtom("not-an-atom"),
                "parseAtom must return null for a bare non-atom string");
        assertNull(InferredFactMaterializer.parseAtom(null),
                "parseAtom must return null for null input");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 7. RecursiveQueryEngine — transitive closure on a 3-hop chain
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RecursiveQueryEngine derives full transitive closure for a 3-hop chain a→b→c→d")
    void recursiveQueryEngineTransitiveClosureThreeHop() {
        // EDB: edge(a,b), edge(b,c), edge(c,d)
        Map<String, List<List<String>>> edb = new HashMap<>();
        edb.put("edge", List.of(
                List.of("a", "b"),
                List.of("b", "c"),
                List.of("c", "d")));

        // Rules: path(X,Y) :- edge(X,Y)
        //        path(X,Z) :- path(X,Y), edge(Y,Z)
        List<RecursiveQueryEngine.DatalogRule> rules = List.of(
                new RecursiveQueryEngine.DatalogRule("path",
                        List.of("?X", "?Y"),
                        List.of(RecursiveQueryEngine.RuleAtom.pos("edge", "?X", "?Y"))),
                new RecursiveQueryEngine.DatalogRule("path",
                        List.of("?X", "?Z"),
                        List.of(RecursiveQueryEngine.RuleAtom.pos("path", "?X", "?Y"),
                                RecursiveQueryEngine.RuleAtom.pos("edge", "?Y", "?Z")))
        );

        RecursiveQueryEngine.EdbProvider edbProvider = pred -> edb.getOrDefault(pred, List.of());

        RecursiveQueryEngine.FixpointResult result = RecursiveQueryEngine.evaluate(
                rules, edbProvider,
                RecursiveQueryEngine.DEFAULT_MAX_ROUNDS,
                RecursiveQueryEngine.DEFAULT_MAX_DERIVED_FACTS);

        assertTrue(result.isComplete(), "Evaluation must reach a natural fixpoint for a finite chain");

        Set<List<String>> paths = result.derivedFacts().get("path");
        assertNotNull(paths, "Derived 'path' facts must be present");

        // All 6 pairs in the 4-node chain (a,b), (a,c), (a,d), (b,c), (b,d), (c,d)
        assertTrue(paths.contains(List.of("a", "b")), "path(a,b) must be derived (direct edge)");
        assertTrue(paths.contains(List.of("a", "c")), "path(a,c) must be derived (2-hop)");
        assertTrue(paths.contains(List.of("a", "d")), "path(a,d) must be derived (3-hop)");
        assertTrue(paths.contains(List.of("b", "c")), "path(b,c) must be derived");
        assertTrue(paths.contains(List.of("b", "d")), "path(b,d) must be derived (2-hop)");
        assertTrue(paths.contains(List.of("c", "d")), "path(c,d) must be derived (direct edge)");

        // No non-entailed pair
        assertFalse(paths.contains(List.of("d", "a")),
                "path(d,a) must NOT be derived (no back-edge in the chain)");
        assertFalse(paths.contains(List.of("b", "a")),
                "path(b,a) must NOT be derived");

        // toInferredFacts wraps derivations as InferredFacts with confidence 1.0
        List<InferredFact> facts = result.toInferredFacts("tc-run-1");
        assertFalse(facts.isEmpty(), "toInferredFacts must produce at least one InferredFact");
        facts.forEach(f -> assertEquals(1.0, f.value(), 1e-9,
                "All derived crisp facts must have value=1.0"));
        assertTrue(facts.stream().anyMatch(f -> f.atomKey().contains("path")),
                "At least one InferredFact must carry the 'path' predicate");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 8. DerivationTree — direct record construction and structural accessors
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DerivationTree record: isLeaf, allAtomKeys, JSON serialization structure")
    void derivationTreeConstructionAndAccessors() {
        // Leaf nodes (observed facts, no children)
        DerivationTree leaf1 = new DerivationTree("Causes(a, b)", 0.9, null, "run-1", List.of());
        DerivationTree leaf2 = new DerivationTree("Causes(b, c)", 0.8, null, "run-1", List.of());

        // Derived node: transitivity applied to produce Causes(a, c)
        DerivationTree derived = new DerivationTree(
                "Causes(a, c)", 0.72,
                "transitivity: Causes(X,Y) ∧ Causes(Y,Z) → Causes(X,Z)",
                "run-1",
                List.of(leaf1, leaf2));

        // Leaf predicate
        assertTrue(leaf1.isLeaf(),   "leaf1 with no children must report isLeaf() = true");
        assertTrue(leaf2.isLeaf(),   "leaf2 with no children must report isLeaf() = true");
        assertFalse(derived.isLeaf(), "derived node with two children must report isLeaf() = false");

        // Children
        assertEquals(2, derived.children().size(), "derived node must have exactly 2 children");

        // allAtomKeys (DFS)
        List<String> keys = derived.allAtomKeys();
        assertTrue(keys.contains("Causes(a, c)"), "allAtomKeys must include the root atom");
        assertTrue(keys.contains("Causes(a, b)"), "allAtomKeys must include leaf atom Causes(a,b)");
        assertTrue(keys.contains("Causes(b, c)"), "allAtomKeys must include leaf atom Causes(b,c)");

        // confidence and rule
        assertEquals(0.72, derived.confidence(), 1e-9);
        assertEquals("transitivity: Causes(X,Y) ∧ Causes(Y,Z) → Causes(X,Z)",
                derived.ruleApplied());

        // JSON serialization must not throw and must include the atom key
        String json = derived.toJson();
        assertNotNull(json, "toJson must produce a non-null string");
        assertTrue(json.contains("Causes(a, c)"), "JSON must include the root atom key");

        // DEFAULT_MAX_DEPTH constant is accessible
        assertEquals(5, DerivationTree.DEFAULT_MAX_DEPTH);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 9. MebnWeightLearner — noisy-OR edge-strength learning reduces SSE
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MebnWeightLearner.learn reduces SSE toward target observations over 5 epochs")
    void mebnWeightLearnerReducesSSE() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").build());

        // Theory: cause(X) → effect(X), initial strength = 0.1 (far below observations)
        MTheory theory = MebnInferenceService.buildCausalTheory(g, "Person", "cause", "effect", 0.1);
        MebnInferenceService svc = new MebnInferenceService();

        // Prior inference — weak initial effect posteriors
        Map<String, Double> before = svc.infer(g, theory, Map.of());
        assertFalse(before.isEmpty(), "Inference must yield at least some posterior values");

        // Target observations: every effect(X) should be highly active
        Map<String, Double> observations = new HashMap<>();
        before.keySet().stream()
                .filter(v -> v.startsWith("effect"))
                .forEach(v -> observations.put(v, 1.0));
        assertFalse(observations.isEmpty(), "effect RVs must be surfaced and targetable");

        // SSE before learning
        double sseBefore = sse(before, observations);

        // Run learner for 5 epochs
        MebnWeightLearner learner = new MebnWeightLearner();
        MTheory learned = learner.learn(theory, g, observations, 5);
        assertNotNull(learned, "learn must return a non-null updated MTheory");

        // SSE after learning
        Map<String, Double> after = svc.infer(g, learned, Map.of());
        double sseAfter = sse(after, observations);

        assertTrue(sseAfter < sseBefore,
                "SSE must decrease after learning: before=" + sseBefore + ", after=" + sseAfter);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 10. Model persistence round-trip: MTheory + TypeRegistry bundled in UnifiedGraph
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("UnifiedGraph round-trip: MTheory + TypeRegistry survive save→load; isA results match")
    void modelPersistenceRoundTripPreservesReasoningFidelity() throws IOException {
        // ── Build the MTheory ─────────────────────────────────────────────────────
        List<RelationDescriptor> rels = List.of(
                new RelationDescriptor("EMPLOYS", "Org", "Person", 0.8,
                        List.of("acme"), List.of("alice", "bob")));
        MTheory theory = RelationalMTheoryBuilder.build("rt-theory", rels);

        // ── Build the TypeRegistry ────────────────────────────────────────────────
        TypeRegistry registry = new TypeRegistry()
                .declare("Entity")
                .declare("Person")
                .subtype("Person", "Entity")
                .declare("Org")
                .subtype("Org", "Entity");

        // ── Bundle into UnifiedGraph ──────────────────────────────────────────────
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "Person", "Alice");
        g.addEntity("acme",  "Org",    "Acme Corp");
        g.addRelation("r1", "acme", "alice", "EMPLOYS", 0.9);
        g.putModel("mebn.mtheory",      theory);
        g.putModel("mebn.typeRegistry", registry);

        // ── Save → Load ───────────────────────────────────────────────────────────
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        // ── Topology survived ─────────────────────────────────────────────────────
        assertEquals(2, back.entityCount(), "Entity count must survive round-trip");
        assertEquals(1, back.relationCount(), "Relation count must survive round-trip");

        // ── MTheory structure survived ─────────────────────────────────────────────
        MTheory restoredTheory = back.model("mebn.mtheory");
        assertNotNull(restoredTheory, "MTheory must be retrievable after round-trip");
        assertEquals("rt-theory", restoredTheory.getName());
        assertEquals(theory.getMFrags().size(), restoredTheory.getMFrags().size(),
                "MFrag count must survive round-trip");
        assertEquals(theory.getEntityTypes().size(), restoredTheory.getEntityTypes().size(),
                "EntityType count must survive round-trip");
        // findHomeMFrag works on restored theory
        assertTrue(restoredTheory.findHomeMFrag("EMPLOYS").isPresent(),
                "findHomeMFrag('EMPLOYS') must resolve after round-trip");
        assertTrue(restoredTheory.findHomeMFrag(RelationalMTheoryBuilder.RELEVANCE_RV).isPresent(),
                "findHomeMFrag('isRelevant') must resolve after round-trip");

        // ── TypeRegistry structure survived ───────────────────────────────────────
        TypeRegistry restoredRegistry = back.model("mebn.typeRegistry");
        assertNotNull(restoredRegistry, "TypeRegistry must be retrievable after round-trip");

        // Re-build TypeHierarchy from the restored graph + restored registry
        TypeHierarchy hierarchyBefore = registry.buildFor(g);
        TypeHierarchy hierarchyAfter  = restoredRegistry.buildFor(back);

        // isA results must be identical before and after round-trip
        boolean personIsEntityBefore = hierarchyBefore.isA("Person", "Entity");
        boolean personIsEntityAfter  = hierarchyAfter.isA("Person", "Entity");
        assertEquals(personIsEntityBefore, personIsEntityAfter,
                "isA(Person, Entity) must be the same before and after round-trip");
        assertTrue(personIsEntityAfter, "Person must still be a subtype of Entity after round-trip");

        boolean orgIsEntityAfter = hierarchyAfter.isA("Org", "Entity");
        assertTrue(orgIsEntityAfter, "Org must still be a subtype of Entity after round-trip");

        boolean entityIsPersonAfter = hierarchyAfter.isA("Entity", "Person");
        assertFalse(entityIsPersonAfter,
                "Entity must NOT be a subtype of Person — asymmetry must survive round-trip");

        // Membership is computed from the restored graph topology
        Set<String> persons = hierarchyAfter.membersOf("Person");
        assertTrue(persons.contains("alice"),
                "alice's Person membership must be recoverable from restored graph");
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static double sse(Map<String, Double> predicted, Map<String, Double> target) {
        double sum = 0.0;
        for (Map.Entry<String, Double> t : target.entrySet()) {
            Double p = predicted.get(t.getKey());
            if (p != null) {
                double d = p - t.getValue();
                sum += d * d;
            }
        }
        return sum;
    }
}
