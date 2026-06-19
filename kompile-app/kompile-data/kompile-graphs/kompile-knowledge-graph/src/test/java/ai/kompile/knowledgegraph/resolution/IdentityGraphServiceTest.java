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

package ai.kompile.knowledgegraph.resolution;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.resolution.IdentityGraphService.IdentifierLink;
import ai.kompile.knowledgegraph.resolution.IdentityGraphService.IdentifierLinkPlan;
import ai.kompile.knowledgegraph.resolution.IdentityGraphService.IdentifierObservation;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for the pure planning core of {@link IdentityGraphService}:
 * grouping observations into many-to-many identifier→entity links, collision
 * detection, and extracting observations from graph-node metadata via scheme delegation.
 */
class IdentityGraphServiceTest {

    private GraphNode node(String id, String title, String metadataJson) {
        return GraphNode.builder().nodeId(id).title(title).metadataJson(metadataJson).build();
    }

    /** Build a GTIN observation (kind="GTIN") directly — the value must already be canonical. */
    private IdentifierObservation obs(String entityId, String gtin) {
        return new IdentifierObservation(entityId, entityId, "GTIN", gtin, "test");
    }

    // ─── planLinks ───────────────────────────────────────────────────────

    @Test
    void planLinks_manyCodesOneProduct() {
        // Multi-vendor: one entity, three distinct valid codes → three links, no collision.
        IdentifierLinkPlan plan = IdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P1", "04006381333931"),
                obs("P1", "00012345678905")));

        assertEquals(3, plan.links().size());
        assertTrue(plan.collisions().isEmpty());
        assertTrue(plan.links().stream().allMatch(l -> l.entityNodeId().equals("P1")));
        assertTrue(plan.links().stream().allMatch(l -> "GTIN".equals(l.kind())));
    }

    @Test
    void planLinks_oneCodeManyProductsIsCollision() {
        // Recycled code: one identifier resolves to two entities → a collision for review.
        IdentifierLinkPlan plan = IdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P2", "00036000291452")));

        assertEquals(1, plan.collisions().size());
        assertEquals("00036000291452", plan.collisions().get(0).value());
        assertEquals("GTIN", plan.collisions().get(0).kind());
        assertEquals(List.of("P1", "P2"), plan.collisions().get(0).entityNodeIds());
    }

    @Test
    void planLinks_votesCountObservations() {
        IdentifierLinkPlan plan = IdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P1", "00036000291452"),
                obs("P1", "00036000291452")));

        assertEquals(1, plan.links().size());
        assertEquals(3, plan.links().get(0).votes());
    }

    // ─── collectObservations (instance method, uses BarcodeIdentifierScheme) ──

    private IdentityGraphService serviceWithBarcodeScheme() {
        return new IdentityGraphService(
                mock(KnowledgeGraphService.class),
                List.of(new BarcodeIdentifierScheme()));
    }

    @Test
    void collectObservations_fromObservedGtins() {
        IdentityGraphService svc = serviceWithBarcodeScheme();
        GraphNode n = node("P1", "Acme Widget",
                "{\"observedGtins\":\"00036000291452,04006381333931\"}");
        List<IdentifierObservation> obs = svc.collectObservations(n);
        assertEquals(2, obs.size());
        assertTrue(obs.stream().anyMatch(o -> o.value().equals("00036000291452") && "GTIN".equals(o.kind())));
        assertTrue(obs.stream().anyMatch(o -> o.value().equals("04006381333931") && "GTIN".equals(o.kind())));
    }

    @Test
    void collectObservations_fromRawBarcodeKey() {
        IdentityGraphService svc = serviceWithBarcodeScheme();
        GraphNode n = node("P1", "Acme Widget", "{\"upc\":\"036000291452\"}");
        List<IdentifierObservation> obs = svc.collectObservations(n);
        assertEquals(1, obs.size());
        assertEquals("00036000291452", obs.get(0).value());
        assertEquals("GTIN", obs.get(0).kind());
    }

    @Test
    void collectObservations_skipsRestrictedAndInvalid() {
        IdentityGraphService svc = serviceWithBarcodeScheme();
        String inStore = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        GraphNode n = node("P1", "Deli Item",
                "{\"upc\":\"" + inStore + "\",\"ean\":\"036000291453\"}");
        // in-store code is restricted; the EAN has a bad check digit — neither is usable.
        assertTrue(svc.collectObservations(n).isEmpty());
    }

    @Test
    void collectObservations_emptyWhenNoBarcode() {
        IdentityGraphService svc = serviceWithBarcodeScheme();
        GraphNode n = node("P1", "Acme Widget", "{\"color\":\"blue\"}");
        assertTrue(svc.collectObservations(n).isEmpty());
    }

    @Test
    void collectObservations_emptyWhenNoMetadata() {
        IdentityGraphService svc = serviceWithBarcodeScheme();
        assertTrue(svc.collectObservations(
                GraphNode.builder().nodeId("P1").title("x").build()).isEmpty());
    }
}
