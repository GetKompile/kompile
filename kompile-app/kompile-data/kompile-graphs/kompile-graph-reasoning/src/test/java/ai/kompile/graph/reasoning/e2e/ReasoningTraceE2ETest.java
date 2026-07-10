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

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.Step;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@link ReasoningTrace} API — building a trace, adapting a FOL derivation,
 * enumerating it, rendering it, and round-tripping it inside a {@code .kgraph}.
 */
class ReasoningTraceE2ETest {

    /** The residency derivation from the FOL scenario: basedIn(alice,nyc) from two graph facts. */
    private ReasoningTrace residencyTrace() {
        Step worksFor = Step.fact("worksFor(alice, acme)", 1.0, "graph");
        Step locatedIn = Step.fact("locatedIn(acme, nyc)", 1.0, "graph");
        Step basedIn = Step.derived(StepKind.RULE, "basedIn(alice, nyc)",
                "basedIn(P,C) :- worksFor(P,O), locatedIn(O,C)", 1.0, worksFor, locatedIn);
        return ReasoningTrace.of(basedIn);
    }

    @Test
    void buildAndEnumerateADerivationTrace() {
        ReasoningTrace trace = residencyTrace();

        assertEquals("basedIn(alice, nyc)", trace.conclusion().conclusion());
        assertEquals(StepKind.RULE, trace.conclusion().kind());
        assertEquals(3, trace.size(), "1 conclusion + 2 premises");
        assertEquals(2, trace.leaves().size());
        assertEquals(2, trace.depth());
        assertTrue(trace.contains("worksFor(alice, acme)"));
        assertTrue(trace.contains("locatedIn(acme, nyc)"));
        assertEquals(2, trace.premisesOf("basedIn(alice, nyc)").size());
        assertTrue(trace.leaves().stream().allMatch(s -> s.kind() == StepKind.FACT),
                "the trace bottoms out in observed facts");
    }

    @Test
    void nestedTraceReportsDepthAndLeaves() {
        // Transitive management: managesTransitively(alice,carol) from a base step and a graph fact.
        Step ab = Step.fact("manages(alice, bob)", 1.0, "graph");
        Step bc = Step.fact("manages(bob, carol)", 1.0, "graph");
        Step baseAb = Step.derived(StepKind.RULE, "managesTransitively(alice, bob)", "base", 1.0, ab);
        Step transitive = Step.derived(StepKind.RULE, "managesTransitively(alice, carol)", "transitive", 1.0, baseAb, bc);
        ReasoningTrace trace = ReasoningTrace.of(transitive);

        assertEquals(4, trace.size());
        assertEquals(3, trace.depth(), "transitive → base → manages(alice,bob)");
        assertEquals(2, trace.leaves().size());
    }

    @Test
    void adaptsAFolDerivationTree() {
        DerivationTree l1 = new DerivationTree("worksFor(alice, acme)", 1.0, null, "graph", List.of());
        DerivationTree l2 = new DerivationTree("locatedIn(acme, nyc)", 1.0, null, "graph", List.of());
        DerivationTree dt = new DerivationTree("basedIn(alice, nyc)", 0.95, "basedIn-rule", "fol", List.of(l1, l2));

        ReasoningTrace trace = ReasoningTrace.fromDerivation(dt);

        assertEquals("basedIn(alice, nyc)", trace.conclusion().conclusion());
        assertEquals(StepKind.RULE, trace.conclusion().kind());
        assertEquals("basedIn-rule", trace.conclusion().operation());
        assertEquals(0.95, trace.conclusion().confidence(), 1e-9);
        assertEquals(3, trace.size());
        assertEquals(2, trace.leaves().size());
        assertTrue(trace.leaves().stream().allMatch(s -> s.kind() == StepKind.FACT),
                "derivation-tree leaves become FACT steps");
    }

    @Test
    void rendersToJson() {
        String json = residencyTrace().toJson();
        assertTrue(json.contains("\"conclusion\":\"basedIn(alice, nyc)\""));
        assertTrue(json.contains("\"kind\":\"RULE\""));
        assertTrue(json.contains("\"premises\":["));
        assertTrue(json.contains("worksFor(alice, acme)"));
    }

    @Test
    void traceBundlesIntoAUnifiedGraphAndRoundTrips() throws IOException {
        ReasoningTrace trace = residencyTrace();

        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "Person", "Alice");
        g.putModel("trace:basedIn(alice,nyc)", trace); // serializable → rides in the .kgraph

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos);
        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));

        ReasoningTrace restored = back.model("trace:basedIn(alice,nyc)");
        assertNotNull(restored);
        assertEquals(trace.size(), restored.size());
        assertEquals(trace.leaves().size(), restored.leaves().size());
        assertEquals(trace.toJson(), restored.toJson(), "the whole trace structure survives round-trip");
        assertTrue(restored.contains("locatedIn(acme, nyc)"));
    }
}
