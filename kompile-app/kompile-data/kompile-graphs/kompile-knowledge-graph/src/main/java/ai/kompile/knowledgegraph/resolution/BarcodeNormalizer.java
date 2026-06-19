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

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Canonicalizes retail product barcodes (UPC / EAN / GTIN) to a single
 * comparable form, so the same physical product is recognized across the many
 * ways a code can be observed in messy source data.
 *
 * <p>A UPC is supposed to be universal, but in practice it arrives broken in
 * predictable ways. This class handles each:
 * <ul>
 *   <li><b>Garbled / extra leading zeros</b> — {@code "012345678905"},
 *       {@code "12345678905"} and {@code "00012345678905"} all canonicalize to
 *       the same GTIN-14.</li>
 *   <li><b>Symbology differences (physical vs information)</b> — UPC-A (12),
 *       EAN-13 (13), GTIN-14 carton codes and zero-suppressed UPC-E (8) for the
 *       same item all reduce to one GTIN-14.</li>
 *   <li><b>Truncation</b> — a code one digit short of a valid length is treated
 *       as data missing its trailing check digit; the check digit is recomputed
 *       and the code is flagged {@code checkDigitRepaired}.</li>
 *   <li><b>Garbled digits</b> — the GS1 mod-10 check digit validates the code; a
 *       wrong check digit is surfaced via {@code checkDigitValid = false} so a
 *       corrupt code is not trusted as a strong identity.</li>
 *   <li><b>Non-universal / recycled codes</b> — store-local (UPC number system
 *       2/4), coupon (5 / EAN 99) and restricted-distribution (EAN 20–29)
 *       prefixes are flagged {@code restrictedPrefix} so they are NOT treated as
 *       a globally unique identity. These are exactly the codes most prone to
 *       being recycled across different products.</li>
 *   <li><b>Scan / OCR noise</b> — {@link #within1Edit} reports a near-miss (one
 *       substitution, insertion, deletion, or adjacent transposition) for use as
 *       a <em>candidate</em> (never an authoritative) match.</li>
 * </ul>
 *
 * <p>This class is pure — no I/O, no Spring — and therefore unit-testable in
 * isolation. It does not decide merges; it produces a normalized, classified
 * {@link BarcodeId} that the resolution services consume as a strong, but
 * corroborated, identity signal.
 */
public final class BarcodeNormalizer {

    private BarcodeNormalizer() {
    }

    /**
     * Attribute keys (in an entity's {@code properties} map) that carry a retail
     * product barcode. Comparison is case-insensitive.
     */
    private static final Set<String> BARCODE_KEYS = Set.of(
            "upc", "upc_a", "upca", "upc_e", "upce",
            "ean", "ean13", "ean_13", "ean8", "ean_8",
            "gtin", "gtin12", "gtin13", "gtin14",
            "barcode", "bar_code", "product_barcode", "global_trade_item_number");

    /** The symbology of a parsed code, before canonicalization to GTIN-14. */
    public enum Symbology {UPC_E, EAN_8, UPC_A, EAN_13, GTIN_14, UNKNOWN}

    /**
     * A parsed, classified barcode. {@code gtin14} is the canonical comparison
     * key: two observations of the same product share one {@code gtin14}
     * regardless of the symbology they were captured in.
     *
     * @param raw                the original input string, verbatim
     * @param digits             the canonical un-padded digit string actually used
     *                           (UPC-E inputs are expanded to their 12-digit UPC-A)
     * @param gtin14             14-digit canonical form, or {@code null} if the input
     *                           could not be interpreted as a GTIN
     * @param symbology          the detected input symbology
     * @param checkDigitValid    whether the GS1 mod-10 check digit verifies
     * @param checkDigitRepaired whether a missing check digit was recomputed
     *                           (truncation recovery)
     * @param restrictedPrefix   whether the code uses a store-local / coupon /
     *                           restricted-distribution prefix and so is not a
     *                           globally unique product identity
     */
    public record BarcodeId(
            String raw,
            String digits,
            String gtin14,
            Symbology symbology,
            boolean checkDigitValid,
            boolean checkDigitRepaired,
            boolean restrictedPrefix) {

        /** A sentinel for empty / unparseable input. */
        public static BarcodeId invalid(String raw) {
            return new BarcodeId(raw, "", null, Symbology.UNKNOWN, false, false, false);
        }

        /**
         * True when this code can stand on its own as a globally unique product
         * identity: it canonicalized to a GTIN-14, its check digit verifies, and
         * it does not use a restricted (store-local / coupon) prefix. Only such
         * codes should be allowed to drive a merge.
         */
        public boolean usableAsGlobalIdentity() {
            return gtin14 != null && checkDigitValid && !restrictedPrefix;
        }
    }

    /** Whether {@code key} (an entity property key) names a barcode field. */
    public static boolean isBarcodeAttributeKey(String key) {
        return key != null && BARCODE_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    /** Strip everything but ASCII digits from {@code raw}. */
    public static String digitsOnly(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Convenience: the canonical GTIN-14 for a raw code, if one is recoverable. */
    public static Optional<String> toGtin14(String raw) {
        return Optional.ofNullable(parse(raw).gtin14());
    }

    /**
     * Two raw codes denote the same global product: both parse to a usable
     * (valid, non-restricted) GTIN-14 and those GTIN-14s are equal. This is the
     * predicate that may drive a merge.
     */
    public static boolean sameGlobalProduct(String rawA, String rawB) {
        BarcodeId a = parse(rawA);
        BarcodeId b = parse(rawB);
        return a.usableAsGlobalIdentity()
                && b.usableAsGlobalIdentity()
                && a.gtin14().equals(b.gtin14());
    }

    /**
     * Two raw codes share a canonical form regardless of validity or restriction.
     * Suitable for blocking (candidate grouping) but not for an authoritative
     * merge — use {@link #sameGlobalProduct} for that.
     */
    public static boolean sameCanonical(String rawA, String rawB) {
        String a = parse(rawA).gtin14();
        String b = parse(rawB).gtin14();
        return a != null && a.equals(b);
    }

    /**
     * Parse and classify a raw barcode string into a canonical {@link BarcodeId}.
     */
    public static BarcodeId parse(String raw) {
        String digits = digitsOnly(raw);
        if (digits.isEmpty()) {
            return BarcodeId.invalid(raw);
        }

        // UPC-E (zero-suppressed) 8-digit form: number-system + 6 core + check.
        // Expand to its 12-digit UPC-A twin so both share one canonical GTIN-14.
        // UPC-E and its UPC-A twin share the same check digit, so we accept the
        // UPC-E reading only when that check digit verifies; otherwise the 8
        // digits are treated as an EAN-8 below.
        if (digits.length() == 8 && (digits.charAt(0) == '0' || digits.charAt(0) == '1')) {
            String upcA = expandUpcE(digits);
            if (upcA != null && upcA.charAt(11) == digits.charAt(7)) {
                return classify(raw, upcA, Symbology.UPC_E, true, false);
            }
        }

        // Truncation recovery: a code exactly one digit short of a valid GTIN
        // length is interpreted as data whose trailing check digit was dropped.
        // (12 and 13 are themselves valid lengths and are validated, not repaired.)
        boolean repaired = false;
        if (digits.length() == 7 || digits.length() == 11) {
            digits = digits + computeCheckDigit(digits);
            repaired = true;
        }

        Symbology symbology = switch (digits.length()) {
            case 8 -> Symbology.EAN_8;
            case 12 -> Symbology.UPC_A;
            case 13 -> Symbology.EAN_13;
            case 14 -> Symbology.GTIN_14;
            default -> Symbology.UNKNOWN;
        };

        return classify(raw, digits, symbology, repaired, repaired);
    }

    private static BarcodeId classify(String raw, String base, Symbology symbology,
                                      boolean knownValid, boolean repaired) {
        boolean valid = knownValid || validateCheckDigit(base);
        // Only emit a canonical GTIN for a recognized symbology; an unrecognized
        // length (e.g. 9–10 digits) is left without a gtin14 so it cannot be
        // falsely grouped with a real product.
        String gtin14 = symbology == Symbology.UNKNOWN ? null : leftPad14(base);
        boolean restricted = gtin14 != null && detectRestricted(base, symbology);
        return new BarcodeId(raw, base, gtin14, symbology, valid, repaired, restricted);
    }

    /**
     * Compute the GS1 mod-10 check digit over {@code dataDigits} (the code
     * <em>without</em> its check digit). The rightmost data digit is weighted ×3,
     * then alternating ×1/×3 leftward — correct for GTIN-8/12/13/14.
     */
    public static int computeCheckDigit(String dataDigits) {
        int sum = 0;
        boolean weightThree = true;
        for (int i = dataDigits.length() - 1; i >= 0; i--) {
            int d = dataDigits.charAt(i) - '0';
            sum += weightThree ? d * 3 : d;
            weightThree = !weightThree;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** Whether {@code code}'s trailing digit is a correct GS1 check digit. */
    public static boolean validateCheckDigit(String code) {
        if (code == null || code.length() < 2) {
            return false;
        }
        String data = code.substring(0, code.length() - 1);
        int check = code.charAt(code.length() - 1) - '0';
        return computeCheckDigit(data) == check;
    }

    /**
     * Expand a zero-suppressed 8-digit UPC-E ({@code NS + 6 core + check}) to its
     * 12-digit UPC-A form, recomputing the check digit. Returns {@code null} if
     * the input is malformed.
     */
    static String expandUpcE(String e) {
        if (e.length() != 8) {
            return null;
        }
        char ns = e.charAt(0);
        char[] c = e.substring(1, 7).toCharArray();
        char last = c[5];
        String mfgAndProduct = switch (last) {
            case '0', '1', '2' -> "" + c[0] + c[1] + last + "00" + "00" + c[2] + c[3] + c[4];
            case '3' -> "" + c[0] + c[1] + c[2] + "00" + "000" + c[3] + c[4];
            case '4' -> "" + c[0] + c[1] + c[2] + c[3] + "0" + "0000" + c[4];
            default -> "" + c[0] + c[1] + c[2] + c[3] + c[4] + "0000" + last; // 5-9
        };
        String dataNoCheck = ns + mfgAndProduct; // 11 digits
        if (dataNoCheck.length() != 11) {
            return null;
        }
        return dataNoCheck + computeCheckDigit(dataNoCheck);
    }

    private static String leftPad14(String digits) {
        if (digits.length() > 14 || digits.length() < 8) {
            return null; // not a recoverable GTIN length
        }
        StringBuilder sb = new StringBuilder(14);
        for (int i = digits.length(); i < 14; i++) {
            sb.append('0');
        }
        return sb.append(digits).toString();
    }

    /**
     * Detect prefixes that are NOT globally unique and so are prone to being
     * recycled across different products: in-store / variable-weight, coupon, and
     * restricted-distribution codes.
     */
    private static boolean detectRestricted(String base, Symbology symbology) {
        switch (symbology) {
            case UPC_A, UPC_E -> {
                char ns = base.charAt(0); // UPC number system
                return ns == '2' || ns == '4' || ns == '5';
            }
            case EAN_13 -> {
                if (base.charAt(0) == '0') {
                    // Leading 0 ⇒ a UPC-A twin; apply the UPC number-system rule.
                    char ns = base.charAt(1);
                    return ns == '2' || ns == '4' || ns == '5';
                }
                return base.charAt(0) == '2'   // GS1 prefix 20–29: restricted distribution / in-store
                        || base.startsWith("99"); // coupons
            }
            case GTIN_14 -> {
                // Strip the packaging-indicator digit and apply EAN-13 logic to the base unit.
                return detectRestricted(base.substring(1), Symbology.EAN_13);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Whether two digit strings are within a single edit — one substitution,
     * insertion, deletion, or adjacent transposition. Intended for surfacing
     * scan/OCR near-misses as <em>candidates</em> for review, never as an
     * authoritative match.
     */
    public static boolean within1Edit(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        if (la == lb) {
            int first = -1;
            int second = -1;
            int diffs = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i)) {
                    diffs++;
                    if (first < 0) {
                        first = i;
                    } else if (second < 0) {
                        second = i;
                    }
                }
            }
            if (diffs == 1) {
                return true; // single substitution
            }
            // single adjacent transposition
            return diffs == 2 && second == first + 1
                    && a.charAt(first) == b.charAt(second)
                    && a.charAt(second) == b.charAt(first);
        }
        // Lengths differ by one: allow a single insertion / deletion.
        String longer = la > lb ? a : b;
        String shorter = la > lb ? b : a;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < longer.length() && j < shorter.length()) {
            if (longer.charAt(i) == shorter.charAt(j)) {
                i++;
                j++;
            } else {
                if (skipped) {
                    return false;
                }
                skipped = true;
                i++;
            }
        }
        return true;
    }
}
