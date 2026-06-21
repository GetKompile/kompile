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
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.declare.DeclareMiner;
import ai.kompile.process.discovery.mining.declare.DeclareTemplate;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the declarative (Declare/MINERful) miner: it recovers the ordering constraints of a clean
 * log, detects mutually-exclusive activities, and its PSL encodings parse on the real HL-MRF engine.
 */
class DeclareMinerTest {

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

    private static boolean has(List<DeclareConstraint> cs, DeclareTemplate tpl, String a, String b) {
        return cs.stream().anyMatch(c -> c.template() == tpl
                && Objects.equals(c.activityA(), a) && Objects.equals(c.activityB(), b));
    }

    private static DeclareConstraint find(List<DeclareConstraint> cs, DeclareTemplate tpl, String a, String b) {
        return cs.stream().filter(c -> c.template() == tpl
                && Objects.equals(c.activityA(), a) && Objects.equals(c.activityB(), b)).findFirst().orElse(null);
    }

    @Test
    void minesOrderingConstraintsAndPslRulesParse() {
        List<DeclareConstraint> cs = DeclareMiner.mine(log("a b c", "a b c", "a b c", "a b c", "a b c"), 0.0, 1.0);

        assertTrue(has(cs, DeclareTemplate.RESPONSE, "a", "b"));
        assertTrue(has(cs, DeclareTemplate.RESPONSE, "b", "c"));
        assertTrue(has(cs, DeclareTemplate.CHAIN_RESPONSE, "a", "b"));
        assertTrue(has(cs, DeclareTemplate.INIT, "a", null));
        assertTrue(has(cs, DeclareTemplate.END, "c", null));
        assertFalse(has(cs, DeclareTemplate.CHAIN_RESPONSE, "a", "c"), "a is not immediately followed by c");

        for (DeclareConstraint c : cs) {
            c.toPslRule().ifPresent(rule ->
                    assertDoesNotThrow(() -> PslRule.parse(rule), () -> "invalid PSL: " + rule));
        }

        DeclareConstraint resp = find(cs, DeclareTemplate.RESPONSE, "a", "b");
        assertNotNull(resp);
        assertEquals(1.0, resp.confidence(), 1e-9);
        assertTrue(resp.toPslRule().isPresent());
    }

    @Test
    void detectsMutuallyExclusiveActivities() {
        // x and y never appear together.
        List<DeclareConstraint> cs = DeclareMiner.mine(log("a x b", "a x b", "a y b", "a y b"), 0.0, 1.0);

        assertTrue(has(cs, DeclareTemplate.NOT_CO_EXISTENCE, "x", "y"),
                () -> "expected NotCoExistence(x, y): " + cs);
    }
}
