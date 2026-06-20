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

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode.Operator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the {@link InductiveMiner} against canonical process-mining examples. Assertions are
 * structural (operator + activity sets) rather than string-based, so they are insensitive to the
 * incidental ordering of commutative ({@code ×}, {@code ∧}) children.
 */
class InductiveMinerTest {

    // ── helpers ────────────────────────────────────────────────────────────

    /** Builds a log from space-separated activity strings, e.g. {@code log("a b c d", "a c b d")}. */
    private static EventLog log(String... traces) {
        List<Trace> ts = new ArrayList<>();
        int caseId = 0;
        for (String tr : traces) {
            String cid = "c" + (caseId++);
            List<Event> events = new ArrayList<>();
            String trimmed = tr.trim();
            if (!trimmed.isEmpty()) {
                for (String a : trimmed.split("\\s+")) {
                    events.add(Event.of(cid, a, null, null));
                }
            }
            ts.add(new Trace(cid, events));
        }
        return new EventLog(ts);
    }

    private static ProcessTreeNode childWith(ProcessTreeNode node, Operator op) {
        for (ProcessTreeNode c : node.children()) {
            if (c.operator() == op) {
                return c;
            }
        }
        return null;
    }

    private static Set<String> activitiesOf(ProcessTreeNode node) {
        return new ProcessTree(node).activities();
    }

    private static void assertActivity(ProcessTreeNode node, String label) {
        assertEquals(Operator.ACTIVITY, node.operator(), "expected an activity leaf");
        assertEquals(label, node.activity());
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void minesSequenceOfChoiceAndParallel() {
        // The textbook log: a, then ({b,c in parallel} or e), then d  →  →(a, ×(∧(b,c), e), d)
        ProcessTree tree = new InductiveMiner().mine(log("a b c d", "a c b d", "a e d"));
        ProcessTreeNode root = tree.root();

        assertEquals(Operator.SEQUENCE, root.operator(), tree::toString);
        assertEquals(3, root.children().size(), tree::toString);
        assertActivity(root.children().get(0), "a");
        assertActivity(root.children().get(2), "d");

        ProcessTreeNode choice = root.children().get(1);
        assertEquals(Operator.XOR, choice.operator(), tree::toString);
        assertActivity(childWith(choice, Operator.ACTIVITY), "e");

        ProcessTreeNode parallel = childWith(choice, Operator.AND);
        assertNotNull(parallel, () -> "expected a parallel branch in " + tree);
        assertEquals(Set.of("b", "c"), activitiesOf(parallel));
    }

    @Test
    void minesExclusiveChoice() {
        // a then (b or c)  →  →(a, ×(b, c))
        ProcessTree tree = new InductiveMiner().mine(log("a b", "a c"));
        ProcessTreeNode root = tree.root();

        assertEquals(Operator.SEQUENCE, root.operator(), tree::toString);
        assertActivity(root.children().get(0), "a");
        ProcessTreeNode choice = root.children().get(1);
        assertEquals(Operator.XOR, choice.operator(), tree::toString);
        assertEquals(Set.of("b", "c"), activitiesOf(choice));
    }

    @Test
    void minesParallel() {
        // a and b concurrently, observed in both orders  →  ∧(a, b)
        ProcessTree tree = new InductiveMiner().mine(log("a b", "b a"));
        ProcessTreeNode root = tree.root();

        assertEquals(Operator.AND, root.operator(), tree::toString);
        assertEquals(Set.of("a", "b"), activitiesOf(root));
    }

    @Test
    void minesLoop() {
        // a, then b repeated via redo d, then c  →  →(a, ↺(b, d), c)
        ProcessTree tree = new InductiveMiner().mine(log("a b c", "a b d b c"));
        ProcessTreeNode root = tree.root();

        assertEquals(Operator.SEQUENCE, root.operator(), tree::toString);
        assertEquals(3, root.children().size(), tree::toString);
        assertActivity(root.children().get(0), "a");
        assertActivity(root.children().get(2), "c");

        ProcessTreeNode loop = root.children().get(1);
        assertEquals(Operator.LOOP, loop.operator(), tree::toString);
        // Body is the first child (executed at least once), redo paths follow.
        assertActivity(loop.children().get(0), "b");
        assertActivity(loop.children().get(1), "d");
    }

    @Test
    void minesSimpleSequence() {
        ProcessTree tree = new InductiveMiner().mine(log("a b c", "a b c"));
        ProcessTreeNode root = tree.root();

        assertEquals(Operator.SEQUENCE, root.operator(), tree::toString);
        assertEquals(List.of("a", "b", "c"),
                root.children().stream().map(ProcessTreeNode::activity).toList());
    }

    @Test
    void alwaysProducesSoundModelOnNoise() {
        // Unstructured/noisy log must still yield a (flower) model, never an exception.
        ProcessTree tree = new InductiveMiner().mine(log("a b c", "c a b", "b c a", "a c"));
        assertNotNull(tree.root());
        assertTrue(activitiesOf(tree.root()).containsAll(Set.of("a", "b", "c")), tree::toString);
    }
}
