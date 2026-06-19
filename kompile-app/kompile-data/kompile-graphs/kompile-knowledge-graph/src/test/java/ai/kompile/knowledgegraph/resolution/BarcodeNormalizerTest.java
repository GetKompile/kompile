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

import ai.kompile.knowledgegraph.resolution.BarcodeNormalizer.BarcodeId;
import ai.kompile.knowledgegraph.resolution.BarcodeNormalizer.Symbology;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BarcodeNormalizer} — GTIN-14 canonicalization, check-digit
 * validation/repair, UPC-E expansion, restricted-prefix detection, and
 * single-edit near-miss detection. Each test maps to a real-world way a UPC
 * arrives broken.
 */
class BarcodeNormalizerTest {

    // ─── Check-digit arithmetic (anchored on published codes) ───────────────

    @Test
    void computeCheckDigit_knownUpcA() {
        // 036000291452 is a canonical UPC-A; check digit is 2.
        assertEquals(2, BarcodeNormalizer.computeCheckDigit("03600029145"));
        assertTrue(BarcodeNormalizer.validateCheckDigit("036000291452"));
    }

    @Test
    void computeCheckDigit_knownEan13() {
        // 4006381333931 is a canonical EAN-13; check digit is 1.
        assertEquals(1, BarcodeNormalizer.computeCheckDigit("400638133393"));
        assertTrue(BarcodeNormalizer.validateCheckDigit("4006381333931"));
    }

    @Test
    void validateCheckDigit_rejectsGarbled() {
        assertFalse(BarcodeNormalizer.validateCheckDigit("036000291453"));
    }

    // ─── Garbled / extra leading zeros (physical vs information) ─────────────

    @Test
    void extraLeadingZeros_canonicalizeToSameGtin14() {
        // Same product seen as UPC-A (12), EAN-13 (13) and GTIN-14 (14).
        BarcodeId upcA = BarcodeNormalizer.parse("036000291452");
        BarcodeId ean13 = BarcodeNormalizer.parse("0036000291452");
        BarcodeId gtin14 = BarcodeNormalizer.parse("00036000291452");

        assertEquals("00036000291452", upcA.gtin14());
        assertEquals(upcA.gtin14(), ean13.gtin14());
        assertEquals(upcA.gtin14(), gtin14.gtin14());
        assertTrue(upcA.usableAsGlobalIdentity());
    }

    @Test
    void noiseCharacters_areStripped() {
        assertEquals("036000291452", BarcodeNormalizer.digitsOnly(" 0-3600 0291452 "));
        assertEquals("00036000291452", BarcodeNormalizer.parse("UPC: 0 36000 29145 2").gtin14());
    }

    // ─── Truncation (dropped trailing check digit) ──────────────────────────

    @Test
    void truncatedCode_recoversCheckDigit() {
        BarcodeId truncated = BarcodeNormalizer.parse("03600029145"); // 11 digits, check dropped
        assertTrue(truncated.checkDigitRepaired());
        assertTrue(truncated.checkDigitValid());
        assertEquals("00036000291452", truncated.gtin14());
        // ...and resolves to the same product as the full 12-digit form.
        assertEquals(BarcodeNormalizer.parse("036000291452").gtin14(), truncated.gtin14());
    }

    // ─── Garbled digit (bad check digit) ────────────────────────────────────

    @Test
    void garbledCode_stillCanonicalizesButIsNotTrusted() {
        BarcodeId garbled = BarcodeNormalizer.parse("036000291453"); // wrong check digit
        assertNotNull(garbled.gtin14());                 // still groupable for blocking
        assertFalse(garbled.checkDigitValid());          // but flagged invalid
        assertFalse(garbled.usableAsGlobalIdentity());   // so it cannot drive a merge
    }

    // ─── UPC-E ↔ UPC-A (zero-suppressed symbology) ──────────────────────────

    @Test
    void upcE_expandsToUpcA_sharingGtin14() {
        // Build a UPC-E and its UPC-A twin from the same 11 data digits.
        String upcA11 = "04210000526"; // NS 0, mfg 42100, product 00526
        int check = BarcodeNormalizer.computeCheckDigit(upcA11);
        String upcA = upcA11 + check;             // 12-digit UPC-A
        String upcE = "0" + "425261" + check;     // 8-digit UPC-E twin (shares check digit)

        BarcodeId e = BarcodeNormalizer.parse(upcE);
        BarcodeId a = BarcodeNormalizer.parse(upcA);

        assertEquals(Symbology.UPC_E, e.symbology());
        assertTrue(e.checkDigitValid());
        assertEquals(a.gtin14(), e.gtin14(), "UPC-E must canonicalize to its UPC-A twin");
    }

    // ─── Restricted / recycled-prone prefixes ───────────────────────────────

    @Test
    void inStoreNumberSystem2_isRestricted() {
        String code = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        BarcodeId id = BarcodeNormalizer.parse(code);
        assertTrue(id.checkDigitValid());
        assertTrue(id.restrictedPrefix(), "UPC number system 2 is in-store / variable weight");
        assertFalse(id.usableAsGlobalIdentity(), "store-local codes must not drive a global merge");
    }

    @Test
    void couponNumberSystem5_isRestricted() {
        String code = "50000000000" + BarcodeNormalizer.computeCheckDigit("50000000000");
        assertTrue(BarcodeNormalizer.parse(code).restrictedPrefix());
    }

    @Test
    void normalNumberSystem0_isNotRestricted() {
        assertFalse(BarcodeNormalizer.parse("036000291452").restrictedPrefix());
    }

    @Test
    void couponEanPrefix99_isRestricted() {
        String code = "990000000000" + BarcodeNormalizer.computeCheckDigit("990000000000");
        BarcodeId id = BarcodeNormalizer.parse(code); // 13-digit EAN
        assertEquals(Symbology.EAN_13, id.symbology());
        assertTrue(id.restrictedPrefix());
    }

    // ─── sameGlobalProduct / sameCanonical predicates ───────────────────────

    @Test
    void sameGlobalProduct_acrossSymbologies() {
        assertTrue(BarcodeNormalizer.sameGlobalProduct("036000291452", "0036000291452"));
        assertTrue(BarcodeNormalizer.sameGlobalProduct("036000291452", "00036000291452"));
    }

    @Test
    void sameGlobalProduct_falseForDifferentProducts() {
        assertFalse(BarcodeNormalizer.sameGlobalProduct("036000291452", "4006381333931"));
    }

    @Test
    void sameGlobalProduct_falseWhenEitherRestricted() {
        String inStore = "20000000000" + BarcodeNormalizer.computeCheckDigit("20000000000");
        // Even if two in-store codes are byte-identical, they are not a global identity.
        assertFalse(BarcodeNormalizer.sameGlobalProduct(inStore, inStore));
        // ...but they do share a canonical form for blocking purposes.
        assertTrue(BarcodeNormalizer.sameCanonical(inStore, inStore));
    }

    // ─── Scan / OCR near-miss (candidate only) ──────────────────────────────

    @Test
    void within1Edit_substitution() {
        assertTrue(BarcodeNormalizer.within1Edit("036000291452", "036000291453"));
    }

    @Test
    void within1Edit_adjacentTransposition() {
        assertTrue(BarcodeNormalizer.within1Edit("036000291452", "036000291425"));
    }

    @Test
    void within1Edit_insertionAndDeletion() {
        assertTrue(BarcodeNormalizer.within1Edit("03600029145", "036000291452")); // insertion
        assertTrue(BarcodeNormalizer.within1Edit("036000291452", "03600029145")); // deletion
    }

    @Test
    void within1Edit_falseForTwoEdits() {
        assertFalse(BarcodeNormalizer.within1Edit("036000291452", "036000291543"));
        assertFalse(BarcodeNormalizer.within1Edit("12345", "123"));
    }

    // ─── Degenerate input ───────────────────────────────────────────────────

    @Test
    void emptyInput_isInvalid() {
        BarcodeId id = BarcodeNormalizer.parse("   ");
        assertNull(id.gtin14());
        assertEquals(Symbology.UNKNOWN, id.symbology());
        assertFalse(id.usableAsGlobalIdentity());
    }

    @Test
    void barcodeAttributeKeyDetection() {
        assertTrue(BarcodeNormalizer.isBarcodeAttributeKey("upc"));
        assertTrue(BarcodeNormalizer.isBarcodeAttributeKey("UPC"));
        assertTrue(BarcodeNormalizer.isBarcodeAttributeKey("ean13"));
        assertTrue(BarcodeNormalizer.isBarcodeAttributeKey("gtin"));
        assertTrue(BarcodeNormalizer.isBarcodeAttributeKey("barcode"));
        assertFalse(BarcodeNormalizer.isBarcodeAttributeKey("name"));
        assertFalse(BarcodeNormalizer.isBarcodeAttributeKey(null));
    }
}
