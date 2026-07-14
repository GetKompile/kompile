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
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.model.GraphRelation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UnifiedGraph#removeRelation} and {@link UnifiedGraph#removeRelationById}.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>Removed relations no longer appear in {@link UnifiedGraph#relations()}</li>
 *   <li>{@link UnifiedGraph#facts()} no longer emits the removed relation's atom</li>
 *   <li>Save/load round-trips the removal (serialized file contains no trace)</li>
 *   <li>Orphaned stub entities are left in place (documented)</li>
 *   <li>Removing a non-existent relation is a clean no-op</li>
 *   <li>Removing by id works and honours the id-keyed lookup</li>
 * </ul>
 */
class UnifiedGraphRemoveRelationTest {

    @TempDir
    Path tmp;

    /** Tiny social-org graph: alice -WORKS_AT-> acme, bob -WORKS_AT-> acme, alice -KNOWS-> bob. */
    private UnifiedGraph buildGraph() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "PERSON", "Alice");
        g.addEntity("bob",   "PERSON", "Bob");
        g.addEntity("acme",  "ORG",    "Acme Corp");
        g.addRelation("r1", "alice", "acme", "WORKS_AT", 1.0);
        g.addRelation("r2", "bob",   "acme", "WORKS_AT", 1.0);
        g.addRelation("r3", "alice", "bob",  "KNOWS",    0.9);
        return g;
    }

    // ── removeRelation(src, type, tgt) ────────────────────────────────────────

    @Test
    void removeRelation_relationNotInCollectionAfterRemoval() {
        UnifiedGraph g = buildGraph();
        assertEquals(3, g.relationCount());

        g.removeRelation("alice", "WORKS_AT", "acme");

        assertEquals(2, g.relationCount());
        Set<String> ids = g.relations().stream().map(GraphRelation::id).collect(Collectors.toSet());
        assertFalse(ids.contains("r1"), "r1 should be removed");
        assertTrue(ids.contains("r2"),  "r2 must remain");
        assertTrue(ids.contains("r3"),  "r3 must remain");
    }

    @Test
    void removeRelation_factsNoLongerEmitRemovedAtom() {
        UnifiedGraph g = buildGraph();
        g.removeRelation("alice", "WORKS_AT", "acme");

        List<String> atomKeys = g.facts().stream().map(Fact::atomKey).collect(Collectors.toList());
        assertFalse(atomKeys.contains("WORKS_AT(alice, acme)"),
                "removed relation must not appear in facts()");
        assertTrue(atomKeys.contains("WORKS_AT(bob, acme)"),
                "other WORKS_AT relation must still be in facts()");
        assertTrue(atomKeys.contains("KNOWS(alice, bob)"),
                "unrelated KNOWS relation must still be in facts()");
    }

    @Test
    void removeRelation_orphanedEntitiesRemainInPlace() {
        UnifiedGraph g = buildGraph();
        // Remove the only relation involving alice→acme; entities must stay
        g.removeRelation("alice", "WORKS_AT", "acme");

        assertTrue(g.entity("alice").isPresent(), "alice entity must stay (orphaned endpoint)");
        assertTrue(g.entity("acme").isPresent(),  "acme entity must stay (still has other edge)");
    }

    @Test
    void removeRelation_nonExistentIsNoOp() {
        UnifiedGraph g = buildGraph();
        int before = g.relationCount();
        // Completely absent triple
        g.removeRelation("alice", "HATES", "bob");
        assertEquals(before, g.relationCount(), "no-op on missing triple");
        // Wrong direction
        g.removeRelation("acme", "WORKS_AT", "alice");
        assertEquals(before, g.relationCount(), "no-op on reversed triple");
    }

    @Test
    void removeRelation_removesAllMatchingEdgesWhenDuplicate() {
        UnifiedGraph g = buildGraph();
        // Add a second WORKS_AT(alice, acme) with a different id
        g.addRelation("r1b", "alice", "acme", "WORKS_AT", 0.5);
        assertEquals(4, g.relationCount());

        g.removeRelation("alice", "WORKS_AT", "acme");

        assertEquals(2, g.relationCount(), "both r1 and r1b should be removed");
    }

    // ── removeRelationById ───────────────────────────────────────────────────

    @Test
    void removeRelationById_removesCorrectRelation() {
        UnifiedGraph g = buildGraph();
        g.removeRelationById("r2");

        assertEquals(2, g.relationCount());
        Set<String> ids = g.relations().stream().map(GraphRelation::id).collect(Collectors.toSet());
        assertFalse(ids.contains("r2"), "r2 should be gone");
        assertTrue(ids.contains("r1"),  "r1 must remain");
        assertTrue(ids.contains("r3"),  "r3 must remain");
    }

    @Test
    void removeRelationById_factsExcludeRemovedRelation() {
        UnifiedGraph g = buildGraph();
        g.removeRelationById("r2"); // bob -WORKS_AT-> acme

        List<String> atomKeys = g.facts().stream().map(Fact::atomKey).collect(Collectors.toList());
        assertFalse(atomKeys.contains("WORKS_AT(bob, acme)"));
        assertTrue(atomKeys.contains("WORKS_AT(alice, acme)"));
    }

    @Test
    void removeRelationById_nonExistentIsNoOp() {
        UnifiedGraph g = buildGraph();
        int before = g.relationCount();
        g.removeRelationById("no_such_id");
        assertEquals(before, g.relationCount());
    }

    // ── Save/load round-trip ─────────────────────────────────────────────────

    @Test
    void removeRelation_survivesFileSaveLoad() throws IOException {
        Path file = tmp.resolve("retracted.kgraph");
        UnifiedGraph g = buildGraph();
        g.removeRelation("alice", "WORKS_AT", "acme");
        g.save(file);

        UnifiedGraph loaded = UnifiedGraph.load(file);

        assertEquals(2, loaded.relationCount(), "loaded graph must have 2 relations");
        List<String> atomKeys = loaded.facts().stream().map(Fact::atomKey).collect(Collectors.toList());
        assertFalse(atomKeys.contains("WORKS_AT(alice, acme)"),
                "retracted atom must not reappear after save/load");
        assertTrue(atomKeys.contains("WORKS_AT(bob, acme)"),
                "other atom must survive save/load");
    }

    @Test
    void removeRelation_survivesStreamSaveLoad() throws IOException {
        UnifiedGraph g = buildGraph();
        g.removeRelation("alice", "KNOWS", "bob");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        g.save(baos);

        UnifiedGraph loaded = UnifiedGraph.load(new ByteArrayInputStream(baos.toByteArray()));

        assertEquals(2, loaded.relationCount());
        List<String> atomKeys = loaded.facts().stream().map(Fact::atomKey).collect(Collectors.toList());
        assertFalse(atomKeys.contains("KNOWS(alice, bob)"), "KNOWS must be gone after stream round-trip");
    }

    // ── Adjacency consistency ────────────────────────────────────────────────

    @Test
    void removeRelation_adjacencyIndexUpdated() {
        UnifiedGraph g = buildGraph();
        g.removeRelation("alice", "WORKS_AT", "acme");

        // outgoing from alice should no longer contain WORKS_AT->acme
        boolean found = g.outgoing("alice").stream()
                .anyMatch(r -> "WORKS_AT".equals(r.type()) && "acme".equals(r.targetId()));
        assertFalse(found, "outgoing adjacency must be updated");

        // incoming to acme from alice should also be gone
        boolean foundIncoming = g.incoming("acme").stream()
                .anyMatch(r -> "alice".equals(r.sourceId()) && "WORKS_AT".equals(r.type()));
        assertFalse(foundIncoming, "incoming adjacency must be updated");

        // acme's other incoming (bob->acme) must still be present
        boolean bobWorks = g.incoming("acme").stream()
                .anyMatch(r -> "bob".equals(r.sourceId()) && "WORKS_AT".equals(r.type()));
        assertTrue(bobWorks, "bob->acme WORKS_AT must survive");
    }

    // ── Chaining ─────────────────────────────────────────────────────────────

    @Test
    void removeRelation_returnsThisForChaining() {
        UnifiedGraph g = buildGraph();
        UnifiedGraph result = g.removeRelation("alice", "WORKS_AT", "acme")
                               .removeRelationById("r3");
        assertSame(g, result, "removeRelation must return this");
        assertEquals(1, g.relationCount(), "only bob->acme WORKS_AT remains");
    }
}
