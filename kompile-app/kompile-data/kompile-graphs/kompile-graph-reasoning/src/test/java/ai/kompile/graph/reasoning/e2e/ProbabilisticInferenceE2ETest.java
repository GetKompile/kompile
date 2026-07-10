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
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.bayesian.Factor;
import ai.kompile.graph.reasoning.bayesian.GraphBayesianNetworkBuilder;
import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.bayesian.VariableElimination;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslMarginalInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.SgdHlMrfInference;
import ai.kompile.graph.reasoning.simulation.OrgNetworkScenario;
import ai.kompile.graph.reasoning.simulation.ScenarioRun;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end probabilistic inference tests covering the {@code bayesian} and {@code psl} packages.
 *
 * <p>Tests are driven from {@link UnifiedGraph} where possible and assert ROBUST properties:
 * monotonicity, probability bounds, belief propagation direction, and serialization fidelity.
 * They intentionally avoid brittle floating-point magic numbers for inference results.</p>
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>Factor algebra: product, marginalize, reduce, normalize</li>
 *   <li>NoisyOr CPT construction: one parent (monotonicity), two parents (super-additivity)</li>
 *   <li>VariableElimination over a hand-wired chain n1→n2→n3: belief propagation + monotonicity</li>
 *   <li>VariableElimination {@code queryAll}: all marginals in [0,1]</li>
 *   <li>GraphBayesianNetworkBuilder from a UnifiedGraph: node count + inference</li>
 *   <li>HlMrfMapInference: simple propagation program, values in [0,1]</li>
 *   <li>PslMarginalInference: marginals valid, samplesUsed > 0</li>
 *   <li>SgdHlMrfInference (direct): after explicit grounding, values in [0,1]</li>
 *   <li>GraphPslProgramBuilder from a UnifiedGraph: rules emitted, solved values in [0,1]</li>
 *   <li>Serialization round-trip: save/load UnifiedGraph, re-run Bayesian inference, identical result</li>
 *   <li>OrgNetworkScenario fixture: sanity — correct scenarioId, nodes &gt; 0, edges &gt; 0</li>
 * </ol>
 */
class ProbabilisticInferenceE2ETest {

    // ── shared fixtures ───────────────────────────────────────────────────────────

    /**
     * Builds a hand-wired three-node directed chain Bayesian network:
     * <pre>  n1 → n2 → n3 </pre>
     * Each CPT uses the noisy-OR model with causalStrength=0.8, leak=0.05.
     * P(n1=TRUE) prior = 0.5.
     *
     * <p>Expected behaviour (noisy-OR math):<br>
     * P(n2=TRUE | n1=TRUE) ≈ 0.81,  P(n2=TRUE | n1=FALSE) ≈ 0.05<br>
     * P(n3=TRUE) prior ≈ 0.38,  P(n3=TRUE | n1=TRUE) ≈ 0.67</p>
     */
    private static BayesianNetwork buildChainNetwork() {
        BayesianNode n1 = new BayesianNode("n1", "e1", "Node1");
        BayesianNode n2 = new BayesianNode("n2", "e2", "Node2");
        BayesianNode n3 = new BayesianNode("n3", "e3", "Node3");

        n1.setCpt(NoisyOrCpt.buildPrior("n1", 0.5));

        n2.addParent(n1);
        n2.setCpt(NoisyOrCpt.buildCpt("n2", List.of("n1"), new double[]{0.8}, 0.05));

        n3.addParent(n2);
        n3.setCpt(NoisyOrCpt.buildCpt("n3", List.of("n2"), new double[]{0.8}, 0.05));

        BayesianNetwork bn = new BayesianNetwork();
        bn.addNode(n1);
        bn.addNode(n2);
        bn.addNode(n3);
        bn.addEdge("n1", "n2");
        bn.addEdge("n2", "n3");
        return bn;
    }

    /**
     * Minimal PSL program encoding: {@code 1.0: Link(A,B) & State(A) -> State(B) ^2}
     * with alice→bob observed and {@code State(bob)} as the single optimisation target.
     */
    private static PslProgram buildSimplePslProgram() {
        PslProgram program = new PslProgram();
        program.declareClosed("Link", 2);
        program.declareOpen("State", 1);
        program.addRule("1.0: Link(A,B) & State(A) -> State(B) ^2");
        program.observe("Link",  1.0, "alice", "bob");
        program.observe("State", 0.9, "alice");
        program.target("State", "bob");
        return program;
    }

    // ── 1. Factor algebra ─────────────────────────────────────────────────────────

    @Test
    void factorBinaryProductAndMarginalize() {
        // P(A=0)=0.3, P(A=1)=0.7  and  P(B=0)=0.4, P(B=1)=0.6 (independent)
        Factor fA = new Factor(List.of("A"), new int[]{2}, new double[]{0.3, 0.7});
        Factor fB = new Factor(List.of("B"), new int[]{2}, new double[]{0.4, 0.6});

        Factor joint = Factor.product(fA, fB);
        assertEquals(4, joint.size(), "Binary × binary joint must have 4 entries");
        assertTrue(joint.getVariables().contains("A") && joint.getVariables().contains("B"),
                "Joint must include both variables");

        // Marginalise out B → recovers P(A)
        Factor margA = joint.marginalize("B").normalize();
        assertEquals(1, margA.getVariables().size(), "After marginalising B only A remains");
        assertEquals("A", margA.getVariables().get(0));

        double[] vA = margA.getValues();
        assertEquals(2, vA.length);
        assertEquals(0.3, vA[0], 1e-9, "P(A=0) after marginalize+normalize must recover original");
        assertEquals(0.7, vA[1], 1e-9, "P(A=1) after marginalize+normalize must recover original");
        assertEquals(1.0, Arrays.stream(vA).sum(), 1e-9, "Normalized values must sum to 1");
    }

    @Test
    void factorReducePicksCorrectSlice() {
        // P(A=0)=0.3, P(A=1)=0.7 — reduce to state index 1 (TRUE)
        Factor fA = new Factor(List.of("A"), new int[]{2}, new double[]{0.3, 0.7});
        Factor reduced = fA.reduce("A", 1);
        // The reduction keeps only the entry for A=TRUE; getValue(0) = 0.7
        assertEquals(0.7, reduced.getValue(0), 1e-9,
                "reduce(\"A\",1) must return the slice for A=TRUE");
    }

    // ── 2. NoisyOr CPT: one parent ───────────────────────────────────────────────

    @Test
    void noisyOrCptMonotonicityWithOneParent() {
        // causalStrength=0.8, leak=0.05
        Factor cpt = NoisyOrCpt.buildCpt("child", List.of("parent"),
                new double[]{0.8}, 0.05);

        assertNotNull(cpt, "NoisyOrCpt.buildCpt must not return null");
        assertTrue(cpt.getVariables().contains("parent"), "CPT must include parent");
        assertTrue(cpt.getVariables().contains("child"),  "CPT must include child");

        // Every raw CPT entry in [0,1]
        for (double v : cpt.getValues()) {
            assertTrue(v >= 0.0 && v <= 1.0, "CPT entry out of [0,1]: " + v);
        }

        // P(child=TRUE | parent=FALSE)  vs  P(child=TRUE | parent=TRUE)
        double pTrueGivenFalse = cpt.reduce("parent", 0).normalize().getValues()[1];
        double pTrueGivenTrue  = cpt.reduce("parent", 1).normalize().getValues()[1];

        assertTrue(pTrueGivenFalse >= 0.0 && pTrueGivenFalse <= 1.0);
        assertTrue(pTrueGivenTrue  >= 0.0 && pTrueGivenTrue  <= 1.0);
        // Monotonicity: activating the parent must raise the child's probability
        assertTrue(pTrueGivenTrue > pTrueGivenFalse,
                "Monotonicity violated: P(child=T|parent=T)=" + pTrueGivenTrue +
                        " should exceed P(child=T|parent=F)=" + pTrueGivenFalse);
    }

    // ── 3. NoisyOr CPT: two parents ──────────────────────────────────────────────

    @Test
    void noisyOrCptTwoParentsSuperAdditivity() {
        // Both parents with strength 0.7, leak 0.05
        Factor cpt = NoisyOrCpt.buildCpt("C", List.of("A", "B"),
                new double[]{0.7, 0.7}, 0.05);

        assertTrue(cpt.getVariables().containsAll(List.of("A", "B", "C")),
                "CPT must list both parents and the child");
        assertEquals(8, cpt.size(), "Two binary parents + binary child → 8 CPT entries");

        for (double v : cpt.getValues()) {
            assertTrue(v >= 0.0 && v <= 1.0, "CPT entry out of [0,1]: " + v);
        }

        // Both false → lowest; both true → highest
        double pBothFalse = cpt.reduce("A", 0).reduce("B", 0).normalize().getValues()[1];
        double pBothTrue  = cpt.reduce("A", 1).reduce("B", 1).normalize().getValues()[1];
        assertTrue(pBothTrue > pBothFalse,
                "Both causes active must yield higher P(C=T) than no causes; got " +
                        pBothTrue + " vs " + pBothFalse);
    }

    // ── 4. VariableElimination: chain belief propagation ─────────────────────────

    @Test
    void variableEliminationChainBeliefPropagation() {
        BayesianNetwork bn = buildChainNetwork();
        assertEquals(3, bn.size(), "Chain network must have exactly 3 nodes");

        // Unconditional marginal of n3
        double pN3Prior = VariableElimination
                .query(bn, "n3", Map.of())
                .normalize().getValues()[1];

        // With n1=TRUE (state index 1)
        double pN3GivenN1True = VariableElimination
                .query(bn, "n3", Map.of("n1", 1))
                .normalize().getValues()[1];

        // With n1=FALSE (state index 0)
        double pN3GivenN1False = VariableElimination
                .query(bn, "n3", Map.of("n1", 0))
                .normalize().getValues()[1];

        // Propagation: evidence at root changes leaf belief
        assertTrue(pN3GivenN1True > pN3Prior,
                "Observing n1=TRUE must increase P(n3=TRUE); prior=" + pN3Prior +
                        " posterior=" + pN3GivenN1True);
        assertTrue(pN3GivenN1False < pN3Prior,
                "Observing n1=FALSE must decrease P(n3=TRUE); prior=" + pN3Prior +
                        " posterior=" + pN3GivenN1False);

        // All results in [0,1]
        assertTrue(pN3Prior        >= 0.0 && pN3Prior        <= 1.0);
        assertTrue(pN3GivenN1True  >= 0.0 && pN3GivenN1True  <= 1.0);
        assertTrue(pN3GivenN1False >= 0.0 && pN3GivenN1False <= 1.0);
    }

    // ── 5. VariableElimination: monotonicity with stacked evidence ────────────────

    @Test
    void variableEliminationMonotonicity() {
        BayesianNetwork bn = buildChainNetwork();

        double pPrior    = VariableElimination.query(bn, "n3", Map.of())
                .normalize().getValues()[1];
        double pGivenN1  = VariableElimination.query(bn, "n3", Map.of("n1", 1))
                .normalize().getValues()[1];
        double pGivenN1N2 = VariableElimination.query(bn, "n3", Map.of("n1", 1, "n2", 1))
                .normalize().getValues()[1];

        // Observing more true-ancestors should (weakly) raise P(n3=TRUE)
        assertTrue(pGivenN1 > pPrior,
                "n1=T must raise P(n3): prior=" + pPrior + " pGivenN1=" + pGivenN1);
        assertTrue(pGivenN1N2 >= pGivenN1,
                "Adding n2=T on top of n1=T must not lower P(n3): " +
                        pGivenN1N2 + " vs " + pGivenN1);
    }

    // ── 6. VariableElimination: queryAll bounds ───────────────────────────────────

    @Test
    void variableEliminationQueryAllProbabilitiesInRange() {
        BayesianNetwork bn = buildChainNetwork();

        Map<String, Double> marginals = VariableElimination.queryAll(bn, Map.of("n1", 1));

        assertFalse(marginals.isEmpty(), "queryAll must return at least one entry");
        for (Map.Entry<String, Double> e : marginals.entrySet()) {
            double v = e.getValue();
            assertTrue(v >= 0.0 && v <= 1.0,
                    "Marginal for " + e.getKey() + " out of [0,1]: " + v);
        }
    }

    @Test
    void variableEliminationQuerySubsetIndependentRootsUsesLocalCpts() {
        BayesianNetwork bn = new BayesianNetwork();
        BayesianNode a = new BayesianNode("a", "a", "A");
        a.setCpt(Factor.binary(List.of("a"), new double[]{0.8, 0.2}));
        BayesianNode b = new BayesianNode("b", "b", "B");
        b.setCpt(Factor.binary(List.of("b"), new double[]{0.3, 0.7}));
        bn.addNode(a);
        bn.addNode(b);

        Map<String, Double> marginals = VariableElimination.querySubset(
                bn, List.of("b", "missing", "a"), Map.of("a", 1));

        assertEquals(2, marginals.size(), "unknown query variables should be ignored");
        assertEquals(0.7, marginals.get("b"), 1e-9,
                "independent root posterior should come directly from b's local CPT");
        assertEquals(1.0, marginals.get("a"), 1e-9,
                "evidence variable should remain deterministic");
    }

    // ── 7. GraphBayesianNetworkBuilder from UnifiedGraph ─────────────────────────

    @Test
    void bayesianBuilderFromUnifiedGraph() {
        UnifiedGraph g = new UnifiedGraph();
        // Root prior = clamp01(entity.weight()); use 0.5 so it is non-degenerate and evidence can move it.
        g.addEntity(ai.kompile.graph.reasoning.model.SimpleGraphEntity.of("e1", "CONCEPT", "Alpha", 0.5));
        g.addEntity("e2", "CONCEPT", "Beta");
        g.addEntity("e3", "CONCEPT", "Gamma");
        g.addRelation("r1", "e1", "e2", "CAUSES", 0.9);
        g.addRelation("r2", "e2", "e3", "CAUSES", 0.85);

        BayesianNetwork bn = new GraphBayesianNetworkBuilder()
                .defaultStrength(0.8)
                .leak(0.05)
                .build(g);

        assertTrue(bn.size() >= 3, "BayesianNetwork must contain at least 3 nodes; got " + bn.size());

        String varE1 = bn.getVariableForKgNodeId("e1");
        String varE3 = bn.getVariableForKgNodeId("e3");
        assertNotNull(varE1, "KG id e1 must map to a variable");
        assertNotNull(varE3, "KG id e3 must map to a variable");

        // Topological order must cover every node and be of the expected size
        List<String> topo = bn.topologicalOrder();
        assertEquals(bn.size(), topo.size(), "Topological order must include every node");

        // Observing the root (e1=TRUE) must raise P(e3=TRUE)
        double pPrior = VariableElimination.query(bn, varE3, Map.of())
                .normalize().getValues()[1];
        double pPost  = VariableElimination.query(bn, varE3, Map.of(varE1, 1))
                .normalize().getValues()[1];
        assertTrue(pPost > pPrior,
                "Observing e1=TRUE must propagate to raise P(e3=TRUE); prior=" +
                        pPrior + " posterior=" + pPost);
    }

    // ── 8. HlMrfMapInference: simple propagation program ─────────────────────────

    @Test
    void hlMrfMapInferenceSimpleProgram() {
        PslProgram program = buildSimplePslProgram();
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

        assertNotNull(result, "solve() must return a non-null Result");
        assertFalse(result.values().isEmpty(), "Solved MAP must contain at least one value");

        // All truth values in [0,1]
        for (Map.Entry<String, Double> e : result.values().entrySet()) {
            double v = e.getValue();
            assertTrue(v >= 0.0 && v <= 1.0,
                    "MAP value for " + e.getKey() + " out of [0,1]: " + v);
        }

        // State(bob) must have been solved with belief propagated from alice's 0.9
        List<String> targets = program.targetKeys();
        assertFalse(targets.isEmpty(), "Program must expose at least one target atom");
        String bobKey = targets.stream()
                .filter(k -> k.contains("bob"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No target atom key for 'bob' found among: " + targets));

        double bobState = result.values().getOrDefault(bobKey, Double.NaN);
        assertFalse(Double.isNaN(bobState), "State(bob) must appear in solved MAP values");
        // alice's State=0.9 should push bob's belief meaningfully above zero
        assertTrue(bobState > 0.1,
                "State(bob) should propagate from alice's 0.9; got " + bobState);
    }

    // ── 9. PslMarginalInference: valid marginals ──────────────────────────────────

    @Test
    void pslMarginalInferenceGivesValidMarginals() {
        PslProgram program = buildSimplePslProgram();
        PslMarginalInference.Result result = PslMarginalInference.solve(program);

        assertNotNull(result, "PslMarginalInference.solve() must not return null");
        assertTrue(result.samplesUsed() > 0, "At least one sample must be used");

        Map<String, Double> mapVals = result.mapValues();
        assertNotNull(mapVals, "mapValues() must not be null");
        for (Map.Entry<String, Double> e : mapVals.entrySet()) {
            double v = e.getValue();
            assertTrue(v >= 0.0 && v <= 1.0,
                    "Marginal MAP value for " + e.getKey() + " out of [0,1]: " + v);
        }
    }

    // ── 10. SgdHlMrfInference: direct solver call ────────────────────────────────

    @Test
    void sgdHlMrfSolverOnSimpleProgram() {
        PslProgram program = buildSimplePslProgram();
        List<GroundRule> groundRules = program.ground();
        assertFalse(groundRules.isEmpty(), "Grounding must produce at least one rule");

        SgdHlMrfInference sgd = new SgdHlMrfInference();
        HlMrfMapInference.Result result = sgd.solve(
                program, groundRules,
                /* maxIterations */ 500,
                /* tolerance     */ 1e-4,
                /* hardWeight    */ HlMrfMapInference.DEFAULT_HARD_WEIGHT);

        assertNotNull(result, "SgdHlMrfInference.solve() must not return null");
        assertFalse(result.values().isEmpty(), "SGD result must contain at least one value");
        assertTrue(result.iterations() > 0, "SGD must run for at least one iteration");

        for (Map.Entry<String, Double> e : result.values().entrySet()) {
            double v = e.getValue();
            assertTrue(v >= 0.0 && v <= 1.0,
                    "SGD MAP value for " + e.getKey() + " out of [0,1]: " + v);
        }
    }

    // ── 11. GraphPslProgramBuilder from UnifiedGraph ──────────────────────────────

    @Test
    void graphPslBuilderFromUnifiedGraph() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("a", "ENTITY", "Alice");
        g.addEntity("b", "ENTITY", "Bob");
        g.addEntity("c", "ENTITY", "Carol");
        g.addRelation("r1", "a", "b", "KNOWS", 0.9);
        g.addRelation("r2", "b", "c", "KNOWS", 0.8);
        // Opinions give the builder prior weights for State atoms
        g.putEntityOpinion("a", Opinion.fromSoftTruth(0.85));
        g.putEntityOpinion("b", Opinion.fromSoftTruth(0.5));

        PslProgram program = new GraphPslProgramBuilder()
                .propagationWeight(1.0)
                .priorWeight(0.5)
                .build(g);

        assertNotNull(program, "GraphPslProgramBuilder.build() must not return null");
        assertFalse(program.rules().isEmpty(),
                "Builder must emit at least one PSL rule for a graph with relations");

        // Solve and verify all results are valid truth values
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        assertNotNull(result);
        for (Map.Entry<String, Double> e : result.values().entrySet()) {
            double v = e.getValue();
            assertTrue(v >= 0.0 && v <= 1.0,
                    "HL-MRF MAP value out of [0,1]: " + e.getKey() + " = " + v);
        }
    }

    // ── 12. Serialization round-trip + inference fidelity ────────────────────────

    @Test
    void serializationAndInferenceFidelity() throws Exception {
        // Build original graph: chain x1 → x2 → x3
        UnifiedGraph original = new UnifiedGraph();
        original.addEntity("x1", "CONCEPT", "Source");
        original.addEntity("x2", "CONCEPT", "Relay");
        original.addEntity("x3", "CONCEPT", "Sink");
        original.addRelation("rx1", "x1", "x2", "CAUSES", 0.9);
        original.addRelation("rx2", "x2", "x3", "CAUSES", 0.85);
        original.putEntityOpinion("x1", Opinion.fromSoftTruth(0.8));

        // Run Bayesian inference on original
        BayesianNetwork bnOrig = new GraphBayesianNetworkBuilder()
                .defaultStrength(0.8).leak(0.05).build(original);
        String varX1 = bnOrig.getVariableForKgNodeId("x1");
        String varX3 = bnOrig.getVariableForKgNodeId("x3");
        assertNotNull(varX1, "x1 must map to a Bayesian variable");
        assertNotNull(varX3, "x3 must map to a Bayesian variable");

        double pX3Orig = VariableElimination
                .query(bnOrig, varX3, Map.of(varX1, 1))
                .normalize().getValues()[1];

        // Serialise → deserialise
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.save(baos);
        UnifiedGraph reloaded = UnifiedGraph.load(new ByteArrayInputStream(baos.toByteArray()));

        // Structural integrity
        assertEquals(original.entityCount(), reloaded.entityCount(),
                "Entity count must survive serialization round-trip");
        assertEquals(original.relationCount(), reloaded.relationCount(),
                "Relation count must survive serialization round-trip");

        // Confirm opinion survived round-trip
        Opinion reloadedOpinion = reloaded.entityOpinion("x1");
        assertNotNull(reloadedOpinion, "Opinion on x1 must survive round-trip");
        assertEquals(original.entityOpinion("x1").expectation(),
                reloadedOpinion.expectation(), 1e-9,
                "Opinion expectation must be identical after round-trip");

        // Re-run Bayesian inference on reloaded graph
        BayesianNetwork bnReloaded = new GraphBayesianNetworkBuilder()
                .defaultStrength(0.8).leak(0.05).build(reloaded);
        String varX1R = bnReloaded.getVariableForKgNodeId("x1");
        String varX3R = bnReloaded.getVariableForKgNodeId("x3");
        assertNotNull(varX1R, "x1 must map to a variable in the reloaded network");
        assertNotNull(varX3R, "x3 must map to a variable in the reloaded network");

        double pX3Reloaded = VariableElimination
                .query(bnReloaded, varX3R, Map.of(varX1R, 1))
                .normalize().getValues()[1];

        // Inference fidelity: result must be bit-identical after round-trip
        assertEquals(pX3Orig, pX3Reloaded, 1e-9,
                "Inference on reloaded graph must match original; orig=" +
                        pX3Orig + " reloaded=" + pX3Reloaded);
    }

    // ── 13. OrgNetworkScenario fixture sanity ────────────────────────────────────

    @Test
    void orgNetworkScenarioGeneratesValidGraph() {
        OrgNetworkScenario scenario = new OrgNetworkScenario();

        // Use a small configuration so the test is fast
        ScenarioRun run = scenario.generate(42L, Map.of(
                "ORGS",           2,
                "DEPTS_PER_ORG",  2,
                "TEAMS_PER_DEPT", 2,
                "PEOPLE_PER_TEAM", 3,
                "TICKS",          1));

        assertNotNull(run, "ScenarioRun must not be null");
        assertEquals("org-network", run.scenarioId(),
                "ScenarioRun must carry the correct scenarioId");
        assertTrue(run.totalNodes() > 0,
                "Scenario must generate at least one node");
        assertTrue(run.totalEdges() > 0,
                "Scenario must generate at least one edge (PART_OF / collab)");
        assertNotNull(run.groundTruth(),
                "GroundTruthManifest must be non-null (may be empty for tiny params)");
    }
}
