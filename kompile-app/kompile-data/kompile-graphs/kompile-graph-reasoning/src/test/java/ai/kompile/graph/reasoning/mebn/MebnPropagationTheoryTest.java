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
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that {@link MebnInferenceService#buildPropagationTheory} now correctly grounds
 * relational MFrags: the {@code isActiveProp(Y)} child is instantiated whenever an
 * {@code ACTIVATES} edge X→Y exists in the graph, and its posterior responds to evidence
 * set on the activating-parent root node {@code isActive(X)}.
 *
 * <p>Before the fix, the context {@code edgeOfType("X","Y",edgeType)} could never be
 * satisfied because neither {@code "X"} nor {@code "Y"} were ever bound in the grounding
 * map — only the positional default key ({@code "Person_0"}) was bound, and both the
 * resident and input RV collapsed to the same key.  The Cartesian product therefore
 * ranged over a single Person dimension, making it impossible to represent a distinct
 * (X, Y) pair.</p>
 *
 * <p>After the fix, {@link RandomVariable#getArgVars()} returns the explicit logical-variable
 * names ({@code "X"} and {@code "Y"}) for the propagation MFrag's RVs, so the Cartesian
 * product expands over (X, Y) pairs, and the context constraint evaluates correctly.</p>
 */
class MebnPropagationTheoryTest {

    /**
     * Graph: alice → bob → carol (ACTIVATES chain); eve is isolated (no incoming ACTIVATES).
     *
     * <pre>
     *   alice ──ACTIVATES──▶ bob ──ACTIVATES──▶ carol
     *   eve   (isolated — no ACTIVATES edge reaching it)
     * </pre>
     */
    MutableReasoningGraph graph;
    MebnInferenceService svc;

    @BeforeEach
    void buildGraph() {
        graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").weight(1.0).build());
        graph.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").weight(0.5).build());
        graph.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").weight(0.5).build());
        graph.addEntity(GraphEntity.builder("eve").type("Person").label("Eve").weight(0.5).build());
        // Chain: alice activates bob, bob activates carol
        graph.addRelation("r1", "alice", "bob",   "ACTIVATES", 0.9);
        graph.addRelation("r2", "bob",   "carol", "ACTIVATES", 0.9);
        // eve has NO incoming ACTIVATES edge
        svc = new MebnInferenceService();
    }

    // ── Core fix: propagation children are now grounded ──────────────────────

    @Test
    @DisplayName("Propagation children (isActiveProp) are grounded for nodes with an activating parent")
    void propagationChildrenExist() {
        MTheory theory = MebnInferenceService.buildPropagationTheory(
                graph, "Person", "ACTIVATES", "isActive", 0.9);

        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());

        // Print all keys so we can see what the SSBN produced (useful during development)
        System.out.println("=== SSBN posterior keys after fix ===");
        posteriors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  " + e.getKey() + " = " + e.getValue()));

        // Before the fix: no "isActiveProp" key existed at all.
        // After the fix: bob and carol are downstream targets of ACTIVATES edges,
        // so isActiveProp(bob) and isActiveProp(carol) must be in the posterior map.
        assertTrue(posteriors.containsKey("isActiveProp(bob)"),
                "isActiveProp(bob) should be grounded — bob is the target of alice ACTIVATES bob; "
                        + "actual keys: " + posteriors.keySet());
        assertTrue(posteriors.containsKey("isActiveProp(carol)"),
                "isActiveProp(carol) should be grounded — carol is the target of bob ACTIVATES carol; "
                        + "actual keys: " + posteriors.keySet());
    }

    @Test
    @DisplayName("Node with no incoming ACTIVATES edge does NOT get a propagation child")
    void isolatedNodeHasNoPropagationChild() {
        MTheory theory = MebnInferenceService.buildPropagationTheory(
                graph, "Person", "ACTIVATES", "isActive", 0.9);

        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());

        // eve has no ACTIVATES edge pointing to it, so the context edgeOfType(X,Y,ACTIVATES)
        // can never be satisfied with Y=eve.  No isActiveProp(eve) node should be created.
        assertFalse(posteriors.containsKey("isActiveProp(eve)"),
                "isActiveProp(eve) must NOT be grounded — no ACTIVATES edge reaches eve; "
                        + "actual keys: " + posteriors.keySet());
    }

    @Test
    @DisplayName("Propagation child posterior rises when activating root is set to TRUE vs FALSE")
    void propagationChildRespondsToEvidence() {
        MTheory theory = MebnInferenceService.buildPropagationTheory(
                graph, "Person", "ACTIVATES", "isActive", 0.9);

        // With alice's root set to TRUE (state index 1)
        Map<String, Double> withAliceOn = svc.infer(graph, theory, Map.of("isActive(alice)", 1));

        // With alice's root set to FALSE (state index 0)
        Map<String, Double> withAliceOff = svc.infer(graph, theory, Map.of("isActive(alice)", 0));

        // Both must produce valid posteriors
        withAliceOn.values().forEach(p ->
                assertTrue(p >= 0.0 && p <= 1.0, "posterior out of [0,1]: " + p));
        withAliceOff.values().forEach(p ->
                assertTrue(p >= 0.0 && p <= 1.0, "posterior out of [0,1]: " + p));

        // isActiveProp(bob) must exist (it was verified in the previous test)
        assertTrue(withAliceOn.containsKey("isActiveProp(bob)"),
                "isActiveProp(bob) should be present with alice on");
        assertTrue(withAliceOff.containsKey("isActiveProp(bob)"),
                "isActiveProp(bob) should be present with alice off");

        double bobPropWithAliceOn  = withAliceOn.getOrDefault("isActiveProp(bob)", 0.0);
        double bobPropWithAliceOff = withAliceOff.getOrDefault("isActiveProp(bob)", 0.0);

        System.out.println("isActiveProp(bob) | alice=TRUE  : " + bobPropWithAliceOn);
        System.out.println("isActiveProp(bob) | alice=FALSE : " + bobPropWithAliceOff);

        // When alice (the activator of bob) is TRUE, bob's propagation child should be
        // strictly more likely than when alice is FALSE — this is the basic causal direction.
        assertTrue(bobPropWithAliceOn > bobPropWithAliceOff,
                "isActiveProp(bob) should be higher when alice is on ("
                        + bobPropWithAliceOn + ") than when alice is off ("
                        + bobPropWithAliceOff + ")");
    }

    @Test
    @DisplayName("All posteriors are valid probabilities in [0,1] for the propagation theory")
    void allPosteriorsValid() {
        MTheory theory = MebnInferenceService.buildPropagationTheory(
                graph, "Person", "ACTIVATES", "isActive", 0.9);

        Map<String, Double> posteriors = svc.infer(graph, theory, Map.of());

        assertFalse(posteriors.isEmpty(), "posteriors should not be empty");
        posteriors.forEach((var, p) ->
                assertTrue(p >= 0.0 && p <= 1.0,
                        "posterior out of [0,1] for " + var + ": " + p));
    }
}
