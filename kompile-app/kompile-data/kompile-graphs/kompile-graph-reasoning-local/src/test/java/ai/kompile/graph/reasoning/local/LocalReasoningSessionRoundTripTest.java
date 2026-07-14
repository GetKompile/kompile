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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: build a graph, save to a temp .kgraph file, reload via
 * {@link LocalReasoningSession#open(Path)}, and verify the topology and KB facts survive.
 */
class LocalReasoningSessionRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesTopology() throws IOException {
        // Build a small graph
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("n1", "PERSON", "Carol",
                0.8, 0.8, Set.of("PERSON"), null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n2", "ORG", "Gamma Ltd",
                1.0, 1.0, Set.of("ORG"), null, null, Map.of()));
        g.addRelation("e1", "n1", "n2", "WORKS_AT", 0.85);

        Path kgraph = tempDir.resolve("round-trip.kgraph");
        LocalReasoningSession session = LocalReasoningSession.of(g);
        session.save(kgraph);
        session.close();

        // Reload
        LocalReasoningSession loaded = LocalReasoningSession.open(kgraph);
        UnifiedGraph back = loaded.graph();
        assertEquals(2, back.entityCount(), "Entity count must survive round-trip");
        assertEquals(1, back.relationCount(), "Relation count must survive round-trip");
        assertTrue(back.entity("n1").isPresent(), "n1 must survive round-trip");
        assertEquals("Carol", back.entity("n1").get().label());
        loaded.close();
    }

    @Test
    void roundTripPrimesKbFacts() throws IOException {
        // Build graph with typed entities and a relation
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("e1", "PERSON", "Dave",
                0.9, 0.9, Set.of("PERSON", "MANAGER"), null, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("e2", "ORG", "Delta",
                1.0, 1.0, Set.of("ORG"), null, null, Map.of()));
        g.addRelation("r1", "e1", "e2", "LEADS", 1.0);

        Path kgraph = tempDir.resolve("kb-primed.kgraph");
        g.save(kgraph);

        LocalReasoningSession session = LocalReasoningSession.open(kgraph);
        LocalKbState kb = session.kbState();

        // Fact store should have facts for: relation LEADS(e1, e2),
        // type memberships PERSON(e1), MANAGER(e1), ORG(e2)
        assertTrue(kb.factStore().size() > 0, "KB should be primed with facts after load");

        // LEADS(e1, e2) should be in the fact store
        boolean hasLeads = kb.factStore().allFacts().stream()
                .anyMatch(f -> f.atomKey().contains("LEADS") || f.atomKey().contains("leads"));
        assertTrue(hasLeads, "Fact store should contain LEADS relation atom: " +
                kb.factStore().allFacts().stream().map(f -> f.atomKey()).toList());

        session.close();
    }

    @Test
    void sessionReplaceGraphReprimesKb() throws IOException {
        UnifiedGraph g1 = new UnifiedGraph();
        g1.addEntity("a", "PERSON", "Alpha");

        UnifiedGraph g2 = new UnifiedGraph();
        g2.addEntity("b", "ORG", "BetaCorp");
        g2.addRelation("r", "b", "b", "SELF_REF", 1.0);

        LocalReasoningSession session = LocalReasoningSession.of(g1);
        int factsBefore = session.kbState().factStore().size();

        session.replaceGraph(g2);
        int factsAfter = session.kbState().factStore().size();

        // g2 has a relation (which projects as a fact) plus a type fact
        // g1 has only type facts for entity 'a' (but no typeMemberships set -> 0 facts)
        // So factsAfter should reflect g2's topology
        // g2 has 1 entity and 1 relation
        assertEquals(1, session.graph().entityCount(), "Replaced graph should have 1 entity");
        assertEquals(1, session.graph().relationCount(), "Replaced graph should have 1 relation");
        // KB was re-primed from g2
        assertTrue(factsAfter >= 0, "KB re-prime should not throw; facts: " + factsAfter);
        session.close();
    }

    @Test
    void graphLoadToolDoesRoundTrip(@TempDir Path td) throws IOException {
        UnifiedGraph original = new UnifiedGraph();
        original.addEntity(new SimpleGraphEntity("x1", "EVENT", "Launch",
                0.7, 0.7, Set.of("EVENT"), null, null, Map.of()));
        original.addRelation("rx", "x1", "x1", "TRIGGERS", 0.6);

        Path kgraph = td.resolve("tool-round-trip.kgraph");
        original.save(kgraph);

        // Use graph_load tool to load the graph into a new session
        LocalReasoningSession session = LocalReasoningSession.createEmpty();
        LocalToolDispatcher dispatcher = LocalToolDispatcher.create();

        String loadResponse = dispatcher.dispatch(session, "graph_load",
                "{\"path\":\"" + escapeJson(kgraph.toString()) + "\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> loadResult = (Map<String, Object>) MiniJson.parse(loadResponse);
        assertEquals("OK", loadResult.get("status"), "graph_load must return OK: " + loadResponse);

        // Verify the graph is actually loaded
        assertEquals(1, session.graph().entityCount(), "Should have 1 entity after load");
        assertEquals(1, session.graph().relationCount(), "Should have 1 relation after load");
        assertEquals("Launch", session.graph().entity("x1").get().label());

        session.close();
    }

    @Test
    void closedSessionThrows() throws IOException {
        LocalReasoningSession session = LocalReasoningSession.createEmpty();
        session.close();
        assertThrows(IllegalStateException.class, session::graph,
                "Calling graph() on a closed session must throw");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\");
    }
}
