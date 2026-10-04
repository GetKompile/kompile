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

package ai.kompile.cli.insights;

import ai.kompile.cli.insights.Neighborhood.Edge;
import ai.kompile.cli.insights.Neighborhood.Node;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.kompile.cli.insights.Reports.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeighborhoodTest {

    private static final Node ALICE = new Node("alice", "Alice", "PERSON");
    private static final Map<String, Node> GRAPH = Map.of(
            "alice", ALICE,
            "acme", new Node("acme", "Acme", "ORGANIZATION"),
            "carol", new Node("carol", "Carol", "PERSON"),
            "bob", new Node("bob", "Bob", null));

    private static Neighborhood aroundAlice(int maxEdges, List<Edge> relations) {
        return new Neighborhood(ALICE, relations, GRAPH::get, maxEdges);
    }

    @Test
    void outgoingRelationsComeFirstThenIncomingThenUndirected() {
        Neighborhood around = aroundAlice(10, List.of(
                new Edge("carol", "alice", "manages", true),
                new Edge("alice", "bob", "knows", false),
                new Edge("alice", "acme", "worksFor", true),
                new Edge("alice", "ghost", null, true)));

        // A relation without a label reads "related"; a node the graph lacks shows its id.
        assertEquals("Alice (PERSON)\n"
                + "├─ related → ghost\n"
                + "├─ worksFor → Acme (ORGANIZATION)\n"
                + "├─ ← manages ─ Carol (PERSON)\n"
                + "└─ knows ─ Bob\n", around.render());
        assertEquals(0, around.omitted());
        assertEquals(List.of("alice", "ghost", "acme", "carol", "bob"),
                around.nodes().stream().map(Node::id).toList());
    }

    @Test
    void aFullViewKeepsOneLineOfEveryKindAndCountsTheRest() {
        List<Edge> relations = new ArrayList<>();
        for (int i = 5; i >= 1; i--) {
            relations.add(new Edge("alice", "doc" + i, "mentions", true));
        }
        relations.add(new Edge("alice", "acme", "worksFor", true));

        Neighborhood around = aroundAlice(3, relations);

        // Five mentions do not crowd out the one worksFor.
        assertEquals("Alice (PERSON)\n"
                + "├─ mentions → doc1\n"
                + "├─ mentions → doc2\n"
                + "├─ worksFor → Acme (ORGANIZATION)\n"
                + "└─ (+3 more relations)\n", around.render());
        assertEquals(3, around.omitted());
        assertEquals(3, around.edges().size());
    }

    @Test
    void aSelfLoopPointsBackAtTheFocusAndALoneNodeSaysSo() {
        assertEquals("Alice (PERSON)\n└─ cites → Alice (PERSON)\n",
                aroundAlice(5, List.of(new Edge("alice", "alice", "cites", true))).render());
        assertEquals("Alice (PERSON)\n└─ (no relations)\n", aroundAlice(5, List.of()).render());
        assertEquals("Alice (PERSON)\n└─ (+1 more relation)\n",
                aroundAlice(0, List.of(new Edge("alice", "bob", "knows", true))).render());
    }

    @Test
    void labelsAreKeptToOneShortLine() {
        Node noisy = new Node("n1", "line one\nline\u001b[31m two " + "x".repeat(80), "TYPE");

        Neighborhood around = new Neighborhood(noisy, List.of(), id -> null, 5);

        String first = around.render().lines().findFirst().orElseThrow();
        assertEquals("line one line [31m two " + "x".repeat(36) + "… (TYPE)", first);
    }

    @Test
    void aRelationThatMissesTheFocusIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> aroundAlice(5, List.of(new Edge("bob", "carol", "knows", true))));

        assertEquals("Relation bob -> carol does not touch alice", e.getMessage());
    }

    @Test
    void theGraphChartCarriesTheDrawnNodesAndEdges() {
        Neighborhood around = aroundAlice(2, List.of(
                new Edge("alice", "acme", "worksFor", true),
                new Edge("carol", "alice", "manages", true),
                new Edge("alice", "bob", null, false)));

        ObjectNode chart = Charts.graph("Around Alice", "kb-1", "People", around, "#/graph?focusNode=alice");

        assertEquals(json("{'v':1,'kind':'graph','title':'Around Alice',"
                + "'factSheet':{'id':'kb-1','name':'People'},'focus':'alice',"
                + "'nodes':[{'id':'alice','label':'Alice','type':'PERSON'},"
                + "{'id':'acme','label':'Acme','type':'ORGANIZATION'},"
                + "{'id':'carol','label':'Carol','type':'PERSON'}],"
                + "'edges':[{'source':'alice','target':'acme','label':'worksFor','directed':true},"
                + "{'source':'carol','target':'alice','label':'manages','directed':true}],"
                + "'omitted':1,'link':'#/graph?focusNode=alice'}"), chart.toString());
        assertEquals(json("{'v':1,'kind':'graph','title':'t','factSheet':{'id':'kb-1','name':'People'},"
                        + "'focus':'alice','nodes':[{'id':'alice','label':'Alice','type':'PERSON'}],"
                        + "'edges':[],'omitted':0}"),
                Charts.graph("t", "kb-1", "People", aroundAlice(5, List.of()), null).toString());
    }
}
