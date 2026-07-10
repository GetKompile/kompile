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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the observed-actor tally: direct attribution, one-hop carrier propagation,
 * performer-vs-involvement selection, the majority-share gate, and role-neighbor naming.
 * Fixture shapes mirror the email lane (person —SENT_BY→ email —MENTIONS→ business entity).
 */
class ActorResourceObservationsTest {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    private GraphNode node(String id, String entityType, String title) {
        GraphNode n = GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
        nodes.add(n);
        return n;
    }

    private void edge(String from, String to, String label) {
        edge(from, to, label, EdgeProvenance.EXTRACTED);
    }

    private void edge(String from, String to, String label, EdgeProvenance provenance) {
        edges.add(GraphEdge.builder()
                .edgeId(from + "::" + to + "::" + label)
                .sourceNodeId(from).targetNodeId(to)
                .relationType(label)
                .provenanceType(provenance)
                .build());
    }

    private Map<String, ActorResourceObservations.ObservedRole> tally() {
        return ActorResourceObservations.tally(nodes, edges, ActivityClassifier.byEntityType());
    }

    @Test
    void directPerformerEdge_bindsActorToActivity() {
        node("p-bob", "PERSON", "bob");
        node("appr-1", "APPROVAL", "Approval #1");
        edge("appr-1", "p-bob", "APPROVED_BY");

        Map<String, ActorResourceObservations.ObservedRole> roles = tally();

        ActorResourceObservations.ObservedRole role = roles.get("Approval");
        assertNotNull(role);
        assertEquals("bob", role.roleLabel());
        assertEquals("bob", role.actorTitle());
        assertEquals("PERSON", role.actorType());
        assertEquals(1, role.performerInstances());
        assertTrue(role.performerEvidence());
    }

    @Test
    void carrierPropagation_senderWinsOverRecipients() {
        node("p-bob", "PERSON", "bob");
        node("p-carol", "PERSON", "carol");
        for (int k = 0; k < 3; k++) {
            node("m-" + k, "EMAIL_MESSAGE", "Re: approved #" + k);
            node("appr-" + k, "APPROVAL", "Approval #" + k);
            edge("p-bob", "m-" + k, "SENT_BY");
            edge("m-" + k, "p-carol", "SENT_TO");
            edge("m-" + k, "appr-" + k, "MENTIONS");
        }

        Map<String, ActorResourceObservations.ObservedRole> roles = tally();

        ActorResourceObservations.ObservedRole role = roles.get("Approval");
        assertNotNull(role);
        assertEquals("bob", role.actorTitle());
        assertEquals(3, role.performerInstances());
        assertEquals(3, role.involvedInstances());
        assertEquals(3, role.activityInstances());
        assertEquals(1.0, role.share(), 1e-9);
        // The communication carrier itself is never a bindable activity.
        assertFalse(roles.containsKey("Email Message"));
    }

    @Test
    void performerEvidence_beatsHigherInvolvement() {
        node("p-alice", "PERSON", "alice");
        node("p-bob", "PERSON", "bob");
        for (int k = 0; k < 3; k++) {
            node("m-" + k, "EMAIL_MESSAGE", "msg " + k);
            node("inv-" + k, "INVOICE", "Invoice #" + k);
            edge("m-" + k, "p-alice", "SENT_TO");     // alice involved on ALL 3
            edge("m-" + k, "inv-" + k, "MENTIONS");
        }
        edge("p-bob", "m-0", "SENT_BY");              // bob authored only 2
        edge("p-bob", "m-1", "SENT_BY");

        ActorResourceObservations.ObservedRole role = tally().get("Invoice");

        assertNotNull(role);
        assertEquals("bob", role.actorTitle());
        assertEquals(2, role.performerInstances());
    }

    @Test
    void involvementOnly_needsMajorityShare() {
        node("p-carol", "PERSON", "carol");
        node("m-0", "EMAIL_MESSAGE", "msg 0");
        edge("m-0", "p-carol", "SENT_TO");
        edge("m-0", "req-0", "MENTIONS");
        for (int k = 0; k < 3; k++) {
            node("req-" + k, "PURCHASE_REQUEST", "Request #" + k);
        }

        // carol touches 1 of 3 instances with no authorship — below the 0.5 share gate.
        assertNull(tally().get("Purchase Request"));

        // A second touched instance (2 of 3 ≥ 0.5) qualifies her, flagged as involvement-only.
        node("m-1", "EMAIL_MESSAGE", "msg 1");
        edge("m-1", "p-carol", "SENT_TO");
        edge("m-1", "req-1", "MENTIONS");
        ActorResourceObservations.ObservedRole role = tally().get("Purchase Request");
        assertNotNull(role);
        assertEquals("carol", role.actorTitle());
        assertFalse(role.performerEvidence());
        assertEquals(2, role.involvedInstances());
    }

    @Test
    void actorToActorAndDerivedEdges_contributeNothing() {
        node("p-bob", "PERSON", "bob");
        node("org", "ORGANIZATION", "acme.com");
        node("appr-1", "APPROVAL", "Approval #1");
        edge("p-bob", "org", "BELONGS_TO");                              // actor–actor
        edge("appr-1", "p-bob", "APPROVED_BY", EdgeProvenance.INFERRED); // reasoning product

        assertTrue(tally().isEmpty());
    }

    @Test
    void roleNeighbor_namesTheRoleOverTheActor() {
        node("p-bob", "PERSON", "bob");
        node("role-appr", "ROLE", "Procurement Approver");
        node("appr-1", "APPROVAL", "Approval #1");
        edge("p-bob", "role-appr", "HAS_ROLE");
        edge("appr-1", "p-bob", "APPROVED_BY");

        ActorResourceObservations.ObservedRole role = tally().get("Approval");

        assertNotNull(role);
        assertEquals("Procurement Approver", role.roleLabel());
        assertEquals("bob", role.actorTitle());
    }

    @Test
    void performerLabelConvention_isTheBySuffix() {
        assertTrue(ActorResourceObservations.isPerformerLabel("SENT_BY"));
        assertTrue(ActorResourceObservations.isPerformerLabel("approved_by"));
        assertTrue(ActorResourceObservations.isPerformerLabel("FROM"));
        assertFalse(ActorResourceObservations.isPerformerLabel("SENT_TO"));
        assertFalse(ActorResourceObservations.isPerformerLabel("MENTIONS"));
        assertFalse(ActorResourceObservations.isPerformerLabel(null));
    }
}
