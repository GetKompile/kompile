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

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies OWL-closure consumption: sibling activities roll up to their shared concept via the
 * SAME {@code owlInferredTypes}/{@code typeClosure} node metadata retrieval already reads,
 * multi-level hierarchies converge to fixpoint, leaf labels survive as the {@code category}
 * attribute, and logs without closure metadata pass through untouched.
 */
class TaxonomyRollupTest {

    private static final LocalDateTime T = LocalDateTime.of(2025, 5, 5, 9, 0);

    private final List<GraphNode> nodes = new ArrayList<>();

    private GraphNode node(String id, String entityType, String closureJson) {
        GraphNode n = GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).title(id)
                .metadataJson("{\"entity_type\":\"" + entityType + "\""
                        + (closureJson != null ? ",\"owlInferredTypes\":" + closureJson : "") + "}")
                .build();
        nodes.add(n);
        return n;
    }

    private static EventLog log(String... traceActivities) {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < traceActivities.length; i++) {
            List<Event> events = new ArrayList<>();
            int j = 0;
            for (String activity : traceActivities[i].split(",")) {
                events.add(Event.of("c" + i, activity.trim(), T.plusMinutes(i * 60L + 5L * j), "n" + i + "-" + j++));
            }
            traces.add(new Trace("c" + i, events));
        }
        return new EventLog(traces);
    }

    @Test
    void siblingSubtypes_rollUpToTheSharedConcept_keepingLeafAsCategory() {
        node("w1", "CHIANTI", "[\"Chianti\",\"Wine\"]");
        node("w2", "RIESLING", "[\"Riesling\",\"Wine\"]");
        EventLog log = log("Order Placed, Chianti, Shipment", "Order Placed, Riesling, Shipment");

        TaxonomyRollup.Result result = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 2);

        assertEquals(Map.of("Chianti", "Wine", "Riesling", "Wine"), result.rollups());
        LinkedHashSet<String> activities = new LinkedHashSet<>(result.log().activityNames());
        assertTrue(activities.contains("Wine") && !activities.contains("Chianti"), String.valueOf(activities));
        // The pre-rollup leaf survives as the category attribute — guard mining routes on it.
        Event wineEvent = result.log().traces().get(0).ordered().get(1);
        assertEquals("Wine", wineEvent.activity());
        assertEquals("Chianti", wineEvent.attributes().get(TaxonomyRollup.CATEGORY_ATTRIBUTE));
        // Activities without closure metadata are untouched.
        assertTrue(activities.contains("Order Placed") && activities.contains("Shipment"));
        assertEquals(1, result.groups().size());
        assertTrue(result.groups().get(0).contains("→ 'Wine' (OWL is-a closure)"), result.groups().get(0));
    }

    @Test
    void multiLevelHierarchy_convergesToTheHighestSharedConcept() {
        // Chianti/Malbec share RedWine (most specific) — pass 1 rolls them there; RedWine then
        // meets Wine (Riesling already rolled to it) — pass 2 converges everything onto Wine.
        node("w1", "CHIANTI", "[\"Chianti\",\"RedWine\",\"Wine\"]");
        node("w2", "MALBEC", "[\"Malbec\",\"RedWine\",\"Wine\"]");
        node("w3", "RIESLING", "[\"Riesling\",\"Wine\"]");
        EventLog log = log("Chianti", "Malbec", "Riesling");

        TaxonomyRollup.Result result = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 2);

        assertEquals("Wine", result.rollups().get("Chianti"));
        assertEquals("Wine", result.rollups().get("Malbec"));
        assertEquals("Wine", result.rollups().get("Riesling"));
        assertEquals(List.of("Wine"), List.copyOf(new LinkedHashSet<>(result.log().activityNames())));
    }

    @Test
    void loneSubtype_orNoClosure_neverRollsUp() {
        node("w1", "CHIANTI", "[\"Chianti\",\"Wine\"]");   // only ONE wine subtype in the log
        node("d1", "INVOICE", null);                          // no closure metadata at all
        EventLog log = log("Chianti, Invoice");

        TaxonomyRollup.Result lone = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 2);
        assertTrue(lone.isEmpty(), "a lone subtype is already its concept's best name");
        assertSame(log, lone.log(), "no roll-up ⇒ the original log, untouched");

        TaxonomyRollup.Result disabled = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 0);
        assertTrue(disabled.isEmpty(), "minSiblings < 2 disables the pass");
    }

    @Test
    void crawlNativeHierarchy_rollsUpWithoutOwlEverRunning() {
        // No owlInferredTypes anywhere — the taxonomy is the crawl's own entity_category, read
        // through GraphNodeTypes.resolveTypeHierarchy. An ungoverned crawl still gets concepts.
        GraphNode chianti = GraphNode.builder()
                .nodeId("w1").nodeType(NodeLevel.ENTITY).title("w1")
                .metadataJson("{\"entity_type\":\"CHIANTI\",\"entity_category\":\"Wine\"}")
                .build();
        GraphNode riesling = GraphNode.builder()
                .nodeId("w2").nodeType(NodeLevel.ENTITY).title("w2")
                .metadataJson("{\"entity_type\":\"RIESLING\",\"entity_category\":\"Wine\"}")
                .build();
        nodes.add(chianti);
        nodes.add(riesling);
        EventLog log = log("Order Placed, Chianti", "Order Placed, Riesling");

        TaxonomyRollup.Result result = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 2);

        assertEquals(Map.of("Chianti", "Wine", "Riesling", "Wine"), result.rollups(),
                "the crawl-native entity_category hierarchy must roll up without OWL");
    }

    @Test
    void typeClosureKeyVariant_andExistingParentActivity_bothWork() {
        // typeClosure is one of the alternate keys matchesEntityType honors; and an activity
        // already LABELED as the concept counts as a member (it IS the concept).
        GraphNode chianti = GraphNode.builder()
                .nodeId("w1").nodeType(NodeLevel.ENTITY).title("w1")
                .metadataJson("{\"entity_type\":\"CHIANTI\",\"typeClosure\":[\"Chianti\",\"Wine\"]}")
                .build();
        nodes.add(chianti);
        node("w2", "WINE", null); // generic wine node, no closure needed — it IS Wine
        EventLog log = log("Chianti", "Wine");

        TaxonomyRollup.Result result = TaxonomyRollup.apply(
                log, nodes, ActivityClassifier.byEntityType(), 2);

        assertEquals(Map.of("Chianti", "Wine"), result.rollups(),
                "the subtype joins the concept already present as an activity");
    }
}
