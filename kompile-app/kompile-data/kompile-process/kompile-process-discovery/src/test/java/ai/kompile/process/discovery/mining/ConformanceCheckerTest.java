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

import ai.kompile.process.discovery.mining.conformance.ConformanceChecker;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates footprint-based conformance: the classic Inductive Miner must replay perfectly (fitness 1),
 * a well-structured log must also be precise, and an unstructured log's flower model must betray itself
 * with low precision despite perfect fitness.
 */
class ConformanceCheckerTest {

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

    @Test
    void inductiveMinerHasPerfectFitness() {
        EventLog log = log("a b c d", "a c b d", "a e d");
        ProcessTree tree = new InductiveMiner().mine(log);

        ConformanceResult conf = ConformanceChecker.check(tree, log);

        assertTrue(conf.perfectFit(), () -> "IM(0) must replay everything; fitness was " + conf.fitness());
        assertEquals(1.0, conf.fitness(), 1e-9);
        assertTrue(conf.precision() > 0.8, () -> "structured log should be precise; was " + conf.precision());
    }

    @Test
    void simpleSequenceIsPerfectlyPreciseAndFitting() {
        EventLog log = log("a b c", "a b c");
        ConformanceResult conf = ConformanceChecker.check(new InductiveMiner().mine(log), log);

        assertEquals(1.0, conf.fitness(), 1e-9);
        assertEquals(1.0, conf.precision(), 1e-9);
    }

    @Test
    void flowerModelFitsButIsImprecise() {
        // No consistent ordering ⇒ the miner falls back to a flower model.
        EventLog log = log("a b c", "c a b", "b c a");
        ProcessTree tree = new InductiveMiner().mine(log);

        ConformanceResult conf = ConformanceChecker.check(tree, log);

        assertEquals(1.0, conf.fitness(), 1e-9, "a flower permits all behaviour, so it always fits");
        assertTrue(conf.precision() < 0.6,
                () -> "a flower over-generalises, so precision must be low; was " + conf.precision());
    }
}
