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

import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.domain.CausalEdgeType;
import ai.kompile.process.discovery.mining.causal.CausalDependency;
import ai.kompile.process.discovery.mining.causal.DependencyMeasures;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer.ProcessCausalModel;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Phase-2 causal coupling: the dependency + χ² measures distinguish causation from
 * concurrency, the classifier produces the right {@link CausalEdgeType}, and — crucially — the
 * auto-generated PSL rules are accepted by the <em>real</em> {@link PslRule} parser of the HL-MRF engine.
 */
class ProcessCausalAnalyzerTest {

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

    private static CausalDependency dependency(ProcessCausalModel model, String from, String to) {
        return model.dependencies().stream()
                .filter(d -> d.from().equals(from) && d.to().equals(to))
                .findFirst().orElse(null);
    }

    @Test
    void measuresDistinguishCausalFromParallel() {
        DirectlyFollowsGraph seq = DfgBuilder.build(log("a b c", "a b c", "a b c", "a b c", "a b c"));
        assertTrue(DependencyMeasures.dependency(seq, "a", "b") > 0.7);
        assertTrue(DependencyMeasures.chiSquare(seq, "a", "b") > DependencyMeasures.CHI2_CRITICAL_0_05);

        DirectlyFollowsGraph par = DfgBuilder.build(log("a b", "b a"));
        assertEquals(0.0, DependencyMeasures.dependency(par, "a", "b"), 1e-9);
        assertFalse(DependencyMeasures.chiSquare(par, "a", "b") > DependencyMeasures.CHI2_CRITICAL_0_05);
    }

    @Test
    void strongSequenceIsCausalAndYieldsParsablePslRules() {
        ProcessCausalModel model = ProcessCausalAnalyzer.analyze(
                log("a b c", "a b c", "a b c", "a b c", "a b c"));

        CausalDependency ab = dependency(model, "a", "b");
        assertNotNull(ab);
        assertTrue(ab.significant(), "a→b should be statistically significant");
        assertEquals(CausalEdgeType.CAUSES, ab.type());

        assertFalse(model.pslRules().isEmpty(), "strong dependencies should generate PSL rules");
        for (String rule : model.pslRules()) {
            assertDoesNotThrow(() -> PslRule.parse(rule),
                    () -> "generated rule must be valid PSL for the HL-MRF engine: " + rule);
        }
    }

    @Test
    void parallelActivitiesAreCorrelatedNotCausal() {
        ProcessCausalModel model = ProcessCausalAnalyzer.analyze(log("a b", "b a", "a b", "b a"));

        CausalDependency ab = dependency(model, "a", "b");
        assertNotNull(ab);
        assertEquals(CausalEdgeType.CORRELATES_WITH, ab.type());
        assertTrue(model.pslRules().isEmpty(), "symmetric concurrency must not be reported as causal");
    }
}
