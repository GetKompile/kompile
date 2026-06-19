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

import ai.kompile.knowledgegraph.resolution.GraphCompactionService.BarcodeSignal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the persisted-graph (Stage-2) barcode identity logic in
 * {@link GraphCompactionService}: GTIN-aware {@code barcodeSignal} classification
 * with the recycled-code corroboration guard, and the exclusion of barcode keys
 * from exact-equality attribute scoring.
 */
class GraphCompactionServiceBarcodeTest {

    private static final Set<String> NO_ALIASES = Set.of();

    private BarcodeSignal signal(Map<String, String> a, String nameA,
                                 Map<String, String> b, String nameB) {
        return GraphCompactionService.barcodeSignal(a, b, nameA, NO_ALIASES, nameB, NO_ALIASES);
    }

    // ─── barcodeSignal: MATCH ────────────────────────────────────────────

    @Test
    void match_acrossFormatsWhenNamesAgree() {
        // Same code as UPC-A and EAN-13, identical names → strong identity MATCH.
        BarcodeSignal s = signal(
                Map.of("upc", "036000291452"), "acme widget",
                Map.of("ean", "0036000291452"), "acme widget");
        assertEquals(BarcodeSignal.MATCH, s);
    }

    @Test
    void match_whenNamesDifferButShareToken() {
        BarcodeSignal s = signal(
                Map.of("upc", "036000291452"), "acme widget standard",
                Map.of("upc", "036000291452"), "acme widget deluxe edition");
        assertEquals(BarcodeSignal.MATCH, s);
    }

    // ─── barcodeSignal: COLLISION (recycled-code guard) ──────────────────

    @Test
    void collision_sharedCodeButConflictingNames() {
        BarcodeSignal s = signal(
                Map.of("upc", "036000291452"), "vintage vinyl record",
                Map.of("upc", "036000291452"), "bluetooth speaker");
        assertEquals(BarcodeSignal.COLLISION, s);
    }

    // ─── barcodeSignal: NONE ─────────────────────────────────────────────

    @Test
    void none_whenDifferentValidCodes() {
        BarcodeSignal s = signal(
                Map.of("upc", "036000291452"), "acme widget",
                Map.of("ean", "4006381333931"), "acme widget");
        assertEquals(BarcodeSignal.NONE, s);
    }

    @Test
    void none_whenSharedCodeIsRestricted() {
        String inStore = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        BarcodeSignal s = signal(
                Map.of("upc", inStore), "deli ham",
                Map.of("upc", inStore), "deli ham");
        assertEquals(BarcodeSignal.NONE, s, "store-local code is not a global identity");
    }

    @Test
    void none_whenOneSideHasNoBarcode() {
        BarcodeSignal s = signal(
                Map.of("upc", "036000291452"), "acme widget",
                Map.of("color", "blue"), "acme widget");
        assertEquals(BarcodeSignal.NONE, s);
    }

    // ─── namesStronglyConflict ───────────────────────────────────────────

    @Test
    void namesStronglyConflict_classification() {
        assertFalse(GraphCompactionService.namesStronglyConflict("acme widget", NO_ALIASES, "acme widget", NO_ALIASES));
        assertFalse(GraphCompactionService.namesStronglyConflict("acme widget blue", NO_ALIASES, "acme widget red", NO_ALIASES));
        assertFalse(GraphCompactionService.namesStronglyConflict("ibm", Set.of("big blue"), "international business machines", Set.of("big blue")));
        assertTrue(GraphCompactionService.namesStronglyConflict("vintage vinyl record", NO_ALIASES, "bluetooth speaker", NO_ALIASES));
    }

    // ─── computeAttributeScore excludes barcodes ─────────────────────────

    @Test
    void computeAttributeScore_ignoresBarcodeKeys() {
        GraphCompactionService svc = new GraphCompactionService(null);
        // A barcode-only overlap must not score here — it is handled by barcodeSignal.
        assertEquals(0.0, svc.computeAttributeScore(
                Map.of("upc", "036000291452"), Map.of("upc", "036000291452")));
        // Non-barcode exclusive attributes still score as before.
        assertEquals(0.95, svc.computeAttributeScore(
                Map.of("email", "a@b.com"), Map.of("email", "a@b.com")));
    }
}
