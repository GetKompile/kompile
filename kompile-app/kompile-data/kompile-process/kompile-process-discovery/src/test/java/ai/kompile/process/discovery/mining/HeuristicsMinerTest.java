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

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.HeuristicsMiner;
import ai.kompile.process.discovery.mining.miner.HeuristicsNet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit tests for {@link HeuristicsMiner} — no Spring context required.
 *
 * <ul>
 *   <li><b>Sequential log</b> ({@code a b c} x5): {@code a→b} and {@code b→c} have dependency ~0.83;
 *       both should survive the default 0.5 threshold.</li>
 *   <li><b>Parallel log</b> ({@code a b} / {@code b a} alternating): {@code dep(a,b) = 0} (symmetric),
 *       so neither direction crosses the threshold.</li>
 *   <li><b>Self-loop log</b> ({@code a a b} x4): {@code a} self-loops; the length-1 loop measure
 *       {@code 4/(4+1) = 0.8} should exceed a 0.5 threshold.</li>
 * </ul>
 */
class HeuristicsMinerTest {

    // -----------------------------------------------------------------------
    // Helpers (same style as ProcessCausalAnalyzerTest)
    // -----------------------------------------------------------------------

    private static EventLog log(String... traces) {
        List<Trace> ts = new ArrayList<>();
        int caseId = 0;
        for (String tr : traces) {
            String cid = "c" + (caseId++);
            List<Event> events = new ArrayList<>();
            for (String a : tr.trim().split("\\s+")) {
                events.add(Event.of(cid, a, null, null));
            }
            ts.add(new Trace(cid, events));
        }
        return new EventLog(ts);
    }

    private static Set<String> arcKeys(HeuristicsNet net) {
        return net.arcs().stream()
                .map(a -> a.from() + "->" + a.to())
                .collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Sequential log: a→b→c repeated five times.
     * dep(a,b) = (5-0)/(5+0+1) ≈ 0.833 ≥ 0.5 → kept.
     * dep(b,c) = (5-0)/(5+0+1) ≈ 0.833 ≥ 0.5 → kept.
     * dep(b,a) = (0-5)/(0+5+1) ≈ -0.833 < 0.5 → pruned (correct: no back-arc).
     */
    @Test
    void sequentialLogKeepsForwardArcs() {
        EventLog seqLog = log("a b c", "a b c", "a b c", "a b c", "a b c");
        HeuristicsNet net = HeuristicsMiner.mine(seqLog, 0.5);

        Set<String> arcs = arcKeys(net);
        assertTrue(arcs.contains("a->b"), "a→b must be kept in sequential log");
        assertTrue(arcs.contains("b->c"), "b→c must be kept in sequential log");

        // No back-arcs expected
        assertFalse(arcs.contains("b->a"), "b→a must be pruned (backward arc)");
        assertFalse(arcs.contains("c->b"), "c→b must be pruned (backward arc)");
    }

    /**
     * Parallel log: "a b" and "b a" alternate — perfectly symmetric.
     * dep(a,b) = (1-1)/(1+1+1) = 0 < 0.5 → pruned.
     * dep(b,a) = (1-1)/(1+1+1) = 0 < 0.5 → pruned.
     * The net has no kept dependency arcs.
     */
    @Test
    void parallelLogKeepsNoArcs() {
        EventLog parLog = log("a b", "b a");
        HeuristicsNet net = HeuristicsMiner.mine(parLog, 0.5);

        assertTrue(net.arcs().isEmpty(),
                "symmetric parallel activities must produce no kept dependency arcs, got: " + arcKeys(net));
    }

    /**
     * The DFG overload produces the same net as the EventLog overload.
     */
    @Test
    void dfgOverloadEqualsLogOverload() {
        EventLog seqLog = log("a b c", "a b c", "a b c");
        DirectlyFollowsGraph dfg = DfgBuilder.build(seqLog);

        HeuristicsNet fromLog = HeuristicsMiner.mine(seqLog, 0.5);
        HeuristicsNet fromDfg = HeuristicsMiner.mine(dfg, 0.5);

        assertEquals(arcKeys(fromLog), arcKeys(fromDfg),
                "EventLog and DFG overloads must produce identical arc sets");
        assertEquals(fromLog.selfLoopActivities(), fromDfg.selfLoopActivities());
    }

    /**
     * Self-loop detection: "a a b" repeated four times.
     * |a→a| = 4, so loop measure = 4/(4+1) = 0.8 ≥ 0.5 → a is a self-loop activity.
     */
    @Test
    void selfLoopActivityIsDetected() {
        EventLog loopLog = log("a a b", "a a b", "a a b", "a a b");
        HeuristicsNet net = HeuristicsMiner.mine(loopLog, 0.5);

        assertTrue(net.selfLoopActivities().contains("a"),
                "activity 'a' must be recognised as a self-loop; got: " + net.selfLoopActivities());
    }

    /**
     * Start and end activities are propagated from the DFG.
     */
    @Test
    void startEndActivitiesPreserved() {
        EventLog seqLog = log("a b c", "a b c", "a b c");
        HeuristicsNet net = HeuristicsMiner.mine(seqLog, 0.5);

        assertTrue(net.startActivities().containsKey("a"), "a must be a start activity");
        assertTrue(net.endActivities().containsKey("c"), "c must be an end activity");
    }

    /**
     * The activity set in the net always equals the DFG activity set, regardless of which arcs survive.
     */
    @Test
    void activitySetIsComplete() {
        EventLog parLog = log("a b", "b a", "a b", "b a");
        HeuristicsNet net = HeuristicsMiner.mine(parLog, 0.5);

        // Even though no arcs survive, both activities must be present
        assertEquals(Set.of("a", "b"), net.activities());
    }
}
