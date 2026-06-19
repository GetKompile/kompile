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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BarcodeIdentifierScheme}: kind label, key handling, and GTIN-14 canonicalization.
 */
class BarcodeIdentifierSchemeTest {

    private final BarcodeIdentifierScheme scheme = new BarcodeIdentifierScheme();

    @Test
    void kind_isGtin() {
        assertEquals("GTIN", scheme.kind());
    }

    @Test
    void handlesKey_trueForBarcodeKeys() {
        assertTrue(scheme.handlesKey("upc"));
        assertTrue(scheme.handlesKey("ean13"));
        assertTrue(scheme.handlesKey("gtin14"));
        assertTrue(scheme.handlesKey("barcode"));
        assertTrue(scheme.handlesKey("observedGtins"));
    }

    @Test
    void handlesKey_falseForNonBarcodeKeys() {
        assertFalse(scheme.handlesKey("name"));
        assertFalse(scheme.handlesKey("email"));
        assertFalse(scheme.handlesKey(null));
    }

    @Test
    void canonicalize_validUpcProduces14DigitGtin() {
        // UPC-A: 036000291452 → padded to GTIN-14: 00036000291452
        Optional<String> result = scheme.canonicalize("036000291452");
        assertTrue(result.isPresent());
        assertEquals(14, result.get().length());
        assertEquals("00036000291452", result.get());
    }

    @Test
    void canonicalize_validEan13ProducesGtin14() {
        // EAN-13: 4006381333931 → GTIN-14: 04006381333931
        Optional<String> result = scheme.canonicalize("4006381333931");
        assertTrue(result.isPresent());
        assertEquals("04006381333931", result.get());
    }

    @Test
    void canonicalize_restrictedCodeReturnsEmpty() {
        // In-store restricted prefix (2xxxxx...) with valid check digit must be rejected.
        String inStore = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        assertTrue(scheme.canonicalize(inStore).isEmpty());
    }

    @Test
    void canonicalize_badCheckDigitReturnsEmpty() {
        // EAN-13 with last digit deliberately wrong.
        assertTrue(scheme.canonicalize("036000291453").isEmpty());
    }

    @Test
    void canonicalize_blankReturnsEmpty() {
        assertTrue(scheme.canonicalize("").isEmpty());
        assertTrue(scheme.canonicalize("   ").isEmpty());
        assertTrue(scheme.canonicalize(null).isEmpty());
    }
}
