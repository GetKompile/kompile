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
import ai.kompile.knowledgegraph.resolution.BarcodeIdentityGraphService.IdentifierLink;
import ai.kompile.knowledgegraph.resolution.BarcodeIdentityGraphService.IdentifierLinkPlan;
import ai.kompile.knowledgegraph.resolution.BarcodeIdentityGraphService.ProductObservation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure planning core of {@link BarcodeIdentityGraphService}:
 * grouping observations into many-to-many identifier→product links, recycled-code
 * collision detection, and extracting observations from graph-node metadata.
 */
class BarcodeIdentityGraphServiceTest {

    private GraphNode node(String id, String title, String metadataJson) {
        return GraphNode.builder().nodeId(id).title(title).metadataJson(metadataJson).build();
    }

    private ProductObservation obs(String productId, String gtin) {
        return new ProductObservation(productId, productId, gtin, "test");
    }

    // ─── planLinks ───────────────────────────────────────────────────────

    @Test
    void planLinks_manyCodesOneProduct() {
        // Multi-vendor: one product, three distinct valid codes → three links, no collision.
        IdentifierLinkPlan plan = BarcodeIdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P1", "04006381333931"),
                obs("P1", "00012345678905")));

        assertEquals(3, plan.gtins().size());
        assertEquals(3, plan.links().size());
        assertTrue(plan.collisions().isEmpty());
        assertTrue(plan.links().stream().allMatch(l -> l.productNodeId().equals("P1")));
    }

    @Test
    void planLinks_oneCodeManyProductsIsCollision() {
        // Recycled code: one GTIN resolves to two products → a collision for review.
        IdentifierLinkPlan plan = BarcodeIdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P2", "00036000291452")));

        assertEquals(1, plan.collisions().size());
        assertEquals("00036000291452", plan.collisions().get(0).gtin14());
        assertEquals(List.of("P1", "P2"), plan.collisions().get(0).productNodeIds());
    }

    @Test
    void planLinks_votesCountObservations() {
        IdentifierLinkPlan plan = BarcodeIdentityGraphService.planLinks(List.of(
                obs("P1", "00036000291452"),
                obs("P1", "00036000291452"),
                obs("P1", "00036000291452")));

        assertEquals(1, plan.links().size());
        assertEquals(3, plan.links().get(0).votes());
    }

    // ─── collectObservations ─────────────────────────────────────────────

    @Test
    void collectObservations_fromObservedGtins() {
        GraphNode n = node("P1", "Acme Widget",
                "{\"observedGtins\":\"00036000291452,04006381333931\"}");
        List<ProductObservation> obs = BarcodeIdentityGraphService.collectObservations(n);
        assertEquals(2, obs.size());
        assertTrue(obs.stream().anyMatch(o -> o.gtin14().equals("00036000291452")));
        assertTrue(obs.stream().anyMatch(o -> o.gtin14().equals("04006381333931")));
    }

    @Test
    void collectObservations_fromRawBarcodeKey() {
        GraphNode n = node("P1", "Acme Widget", "{\"upc\":\"036000291452\"}");
        List<ProductObservation> obs = BarcodeIdentityGraphService.collectObservations(n);
        assertEquals(1, obs.size());
        assertEquals("00036000291452", obs.get(0).gtin14());
    }

    @Test
    void collectObservations_skipsRestrictedAndInvalid() {
        String inStore = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        GraphNode n = node("P1", "Deli Item",
                "{\"upc\":\"" + inStore + "\",\"ean\":\"036000291453\"}");
        // in-store code is restricted; the EAN has a bad check digit — neither is usable.
        assertTrue(BarcodeIdentityGraphService.collectObservations(n).isEmpty());
    }

    @Test
    void collectObservations_emptyWhenNoBarcode() {
        GraphNode n = node("P1", "Acme Widget", "{\"color\":\"blue\"}");
        assertTrue(BarcodeIdentityGraphService.collectObservations(n).isEmpty());
    }

    @Test
    void collectObservations_emptyWhenNoMetadata() {
        assertTrue(BarcodeIdentityGraphService.collectObservations(
                GraphNode.builder().nodeId("P1").title("x").build()).isEmpty());
    }
}
