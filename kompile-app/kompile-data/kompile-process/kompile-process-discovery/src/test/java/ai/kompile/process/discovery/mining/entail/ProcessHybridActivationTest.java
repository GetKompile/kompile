/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.process.discovery.mining.declare.DeclareMiner;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HybridReasoner orchestration over a discovered process: every activity gets a PSL and a
 * Bayesian structural score from ONE ReasoningGraph whose relation weights carry the mined
 * metadata, and the hybrid consensus stays on the simplex.
 */
class ProcessHybridActivationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 8, 1, 9, 0);

    private static EventLog sequenceLog(int n) {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String c = "case-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusHours(i), "a" + i),
                    Event.of(c, "Notify", BASE.plusHours(i).plusMinutes(5), "b" + i),
                    Event.of(c, "Close", BASE.plusHours(i).plusMinutes(10), "c" + i))));
        }
        return new EventLog(traces);
    }

    @Test
    void activatesEveryActivity_underBothEngines_withConsensusInUnitRange() {
        EventLog log = sequenceLog(4);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        ProcessEntailmentResult entailment =
                ProcessEntailment.entail(log, dfg, DeclareMiner.mine(log, 0.2, 0.66));

        ProcessHybridActivation.Result result = ProcessHybridActivation.activate(log, dfg, entailment);

        assertEquals(3, result.byActivity().size(), "every activity is ranked");
        for (ProcessHybridActivation.ActivityActivation a : result.byActivity().values()) {
            assertTrue(a.psl() >= 0.0 && a.psl() <= 1.0, "PSL score on the simplex: " + a);
            assertTrue(a.bayesian() >= 0.0 && a.bayesian() <= 1.0, "Bayesian score on the simplex: " + a);
            assertEquals((a.psl() + a.bayesian()) / 2.0, a.hybrid(), 1e-9,
                    "hybrid = engine consensus mean");
        }
        assertTrue(result.meanHybrid() > 0.0, "a real flow must activate: " + result.meanHybrid());
        assertFalse(result.semanticEngaged());
        assertEquals(1.0, result.structuralWeight(), 1e-9);
        assertEquals(0.0, result.semanticWeight(), 1e-9);
        assertEquals(0.0, result.meanSemantic(), 1e-9);
        assertTrue(result.pslAvailable());
        assertTrue(result.bayesianAvailable());
        assertTrue(result.warnings().isEmpty());
        for (ProcessHybridActivation.ActivityActivation a : result.byActivity().values()) {
            assertEquals(a.pslStructural(), a.psl(), 1e-9);
            assertEquals(a.bayesianStructural(), a.bayesian(), 1e-9);
        }
        // The chain's start feeds the downstream steps: activation must reach the end activity.
        assertTrue(result.byActivity().get("Close").hybrid() > 0.0,
                "structure propagates activation to the terminal activity");
    }

    @Test
    void semanticCentroid_preservesNativeStructuralSemanticAndBlendedComponents() {
        EventLog log = sequenceLog(4);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        ProcessEntailmentResult entailment =
                ProcessEntailment.entail(log, dfg, DeclareMiner.mine(log, 0.2, 0.66));
        Map<String, double[]> embeddings = Map.of(
                "Approve", new double[] {1.0, 0.0},
                "Notify", new double[] {0.9, 0.1},
                "Close", new double[] {0.0, 1.0});

        ProcessHybridActivation.Result result = ProcessHybridActivation.activate(
                log, dfg, entailment, embeddings, 0.4);

        assertTrue(result.semanticEngaged());
        assertEquals(3, result.embeddedActivityCount());
        assertEquals(0.6, result.structuralWeight(), 1e-9);
        assertEquals(0.4, result.semanticWeight(), 1e-9);
        assertTrue(result.meanSemantic() > 0.0);
        for (ProcessHybridActivation.ActivityActivation activity : result.byActivity().values()) {
            assertTrue(activity.embedded());
            assertEquals(0.6 * activity.pslStructural() + 0.4 * activity.semantic(),
                    activity.psl(), 1e-9);
            assertEquals(0.6 * activity.bayesianStructural() + 0.4 * activity.semantic(),
                    activity.bayesian(), 1e-9);
        }

        var details = ProcessHybridActivation.toSuggestionDetails(result);
        assertEquals("ACTIVITY_ACTIVATION_CONSENSUS", details.getInterpretation());
        assertEquals("ACTIVITY_CENTROID", details.getSemanticMode());
        assertEquals(result.meanHybrid(), details.getScore(), 1e-9);
        assertEquals(3, details.getActivities().size());
        assertTrue(details.getActivities().get(0).getScore()
                >= details.getActivities().get(1).getScore());
    }

    @Test
    void degenerateProcess_returnsEmpty() {
        EventLog single = new EventLog(List.of(new Trace("c", List.of(
                Event.of("c", "Approve", BASE, "a0")))));
        DirectlyFollowsGraph dfg = DfgBuilder.build(single);
        assertTrue(ProcessHybridActivation.activate(single, dfg, ProcessEntailmentResult.empty()).isEmpty());
    }
}
