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

import ai.kompile.process.discovery.mining.causal.ProcessBayesianInference;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Bayesian coupling runs on the project's exact variable-elimination engine and behaves
 * sensibly: observing the first step raises the downstream posteriors, and concurrent activities (no
 * directed dependency) produce no edges — i.e. they are encoded as independent.
 */
class ProcessBayesianInferenceTest {

    private static DirectlyFollowsGraph dfg(String... traces) {
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
        return DfgBuilder.build(new EventLog(ts));
    }

    @Test
    void evidenceRaisesDownstreamPosteriors() {
        DirectlyFollowsGraph dfg = dfg("a b c", "a b c", "a b c", "a b c", "a b c");

        ProcessBayesianInference.Result r = ProcessBayesianInference.infer(dfg, List.of("a"));

        for (double v : r.posteriors().values()) {
            assertTrue(v >= 0.0 && v <= 1.0, () -> "posterior out of range: " + r.posteriors());
        }
        assertEquals(3, r.nodes());
        assertEquals(2, r.edges(), "a→b and b→c");
        assertEquals(1.0, r.posteriors().get("a"), 1e-9);                        // evidence
        assertTrue(r.posteriors().get("b") > r.priors().get("b"),               // noisy-OR lift
                () -> "b should rise above prior: " + r.posteriors());
        assertTrue(r.posteriors().get("c") > r.priors().get("c"),
                () -> "c should rise above prior: " + r.posteriors());
    }

    @Test
    void concurrentActivitiesAreIndependent() {
        DirectlyFollowsGraph dfg = dfg("a b", "b a", "a b", "b a");

        ProcessBayesianInference.Result r = ProcessBayesianInference.infer(dfg, null);

        assertEquals(0, r.edges(), "symmetric concurrency ⇒ no directed dependency ⇒ no edges");
        for (double v : r.posteriors().values()) {
            assertTrue(v >= 0.0 && v <= 1.0);
        }
    }
}
