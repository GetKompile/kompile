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

package ai.kompile.loader.email.inbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EmailBodyValueExtractorTest {

    private EmailBodyValueExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new EmailBodyValueExtractor();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private Document doc(String body) {
        return new Document(body);
    }

    private Document docWithMeta(String body, Map<String, Object> meta) {
        Document d = new Document(body);
        d.getMetadata().putAll(meta);
        return d;
    }

    // ── Currency ─────────────────────────────────────────────────────────────

    @Test
    void extract_detectsCurrencyValues() {
        Document d = doc("The budget is $50,000 and we also received USD 1,234.56 plus €500 and £1,000.00 in other currencies.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> currencies = values.stream()
                .filter(v -> "CURRENCY".equals(v.type))
                .toList();

        // Expect at least 4 distinct currency matches
        assertTrue(currencies.size() >= 4,
                "Expected at least 4 currency values, found: " + currencies.size() + " — " +
                        currencies.stream().map(v -> v.rawText).toList());

        // Verify specific parsed amounts
        boolean found50k = currencies.stream().anyMatch(v -> v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 50000.0) < 0.01);
        boolean found1234 = currencies.stream().anyMatch(v -> v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 1234.56) < 0.01);
        boolean found500  = currencies.stream().anyMatch(v -> v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 500.0) < 0.01);
        boolean found1000 = currencies.stream().anyMatch(v -> v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 1000.0) < 0.01);

        assertTrue(found50k,  "Should parse $50,000 as 50000.0");
        assertTrue(found1234, "Should parse USD 1,234.56 as 1234.56");
        assertTrue(found500,  "Should parse €500 as 500.0");
        assertTrue(found1000, "Should parse £1,000.00 as 1000.0");
    }

    // ── Percentage ───────────────────────────────────────────────────────────

    @Test
    void extract_detectsPercentages() {
        Document d = doc("The discount rate is 45% and the growth is 12.5 percent.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> pcts = values.stream()
                .filter(v -> "PERCENTAGE".equals(v.type))
                .toList();

        assertTrue(pcts.size() >= 2,
                "Expected at least 2 percentage values, found: " + pcts.size());

        // Percentages are stored as fractions
        boolean found45 = pcts.stream().anyMatch(v ->
                v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 0.45) < 0.001);
        boolean found12_5 = pcts.stream().anyMatch(v ->
                v.parsedValue instanceof Double d2 && Math.abs((Double) d2 - 0.125) < 0.001);

        assertTrue(found45,   "Should parse '45%' as 0.45");
        assertTrue(found12_5, "Should parse '12.5 percent' as 0.125");
    }

    // ── ISO dates ────────────────────────────────────────────────────────────

    @Test
    void extract_detectsIsoDates() {
        Document d = doc("The fiscal year ends on 2024-03-15 for reporting purposes.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> dates = values.stream()
                .filter(v -> "DATE".equals(v.type))
                .toList();

        assertFalse(dates.isEmpty(), "Expected at least one DATE value");

        boolean foundIso = dates.stream().anyMatch(v -> "2024-03-15".equals(v.rawText));
        assertTrue(foundIso, "Should detect ISO date '2024-03-15'");
    }

    // ── US dates ─────────────────────────────────────────────────────────────

    @Test
    void extract_detectsUsDates() {
        Document d = doc("Please submit expenses by 03/15/2024 at the latest.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> dates = values.stream()
                .filter(v -> "DATE".equals(v.type))
                .toList();

        assertFalse(dates.isEmpty(), "Expected at least one DATE value");

        boolean foundUs = dates.stream().anyMatch(v -> "03/15/2024".equals(v.rawText));
        assertTrue(foundUs, "Should detect US date '03/15/2024'");
    }

    // ── Cell references ──────────────────────────────────────────────────────

    @Test
    void extract_detectsCellReferences() {
        Document d = doc("Please update cell A1 with the new value and also check Sheet1!C3 for totals.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> refs = values.stream()
                .filter(v -> "CELL_REFERENCE".equals(v.type))
                .toList();

        assertFalse(refs.isEmpty(), "Expected at least one CELL_REFERENCE value");

        boolean foundA1 = refs.stream().anyMatch(v -> {
            Object pv = v.parsedValue;
            return pv instanceof String s && (s.contains("A1") || s.equals("A1"));
        });
        assertTrue(foundA1, "Should detect cell reference A1; found: " +
                refs.stream().map(v -> v.parsedValue.toString()).toList());

        boolean foundC3 = refs.stream().anyMatch(v -> {
            Object pv = v.parsedValue;
            return pv instanceof String s && s.contains("C3");
        });
        assertTrue(foundC3, "Should detect cell reference Sheet1!C3; found: " +
                refs.stream().map(v -> v.parsedValue.toString()).toList());
    }

    // ── Key-value pairs ──────────────────────────────────────────────────────

    @Test
    void extract_detectsKeyValuePairs() {
        Document d = doc("For Q3: amount: $5,000 and revenue = 1000000 are expected.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        // Key-value pairs produce NUMERIC or CURRENCY-type entries with a fieldName
        List<EmailBodyValueExtractor.ExtractedValue> kvPairs = values.stream()
                .filter(v -> v.fieldName != null)
                .toList();

        assertFalse(kvPairs.isEmpty(),
                "Expected at least one key-value extracted value with a fieldName");

        boolean hasAmount = kvPairs.stream().anyMatch(v ->
                "amount".equals(v.fieldName));
        boolean hasRevenue = kvPairs.stream().anyMatch(v ->
                "revenue".equals(v.fieldName));

        assertTrue(hasAmount,  "Should extract key 'amount' from 'amount: $5,000'");
        assertTrue(hasRevenue, "Should extract key 'revenue' from 'revenue = 1000000'");
    }

    // ── Mixed content ────────────────────────────────────────────────────────

    @Test
    void extract_multipleValuesInOneEmail() {
        String body =
                "Hi team,\n" +
                "Q3 revenue came in at $2,500,000 which is 15% above forecast.\n" +
                "The report date is 2024-09-30 and costs were £750,000.\n" +
                "Please update cell B5 with the final number.\n";
        Document d = doc(body);
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        assertTrue(values.size() >= 4,
                "Expected at least 4 extracted values, found: " + values.size());

        long currencyCount = values.stream().filter(v -> "CURRENCY".equals(v.type)).count();
        long pctCount      = values.stream().filter(v -> "PERCENTAGE".equals(v.type)).count();
        long dateCount     = values.stream().filter(v -> "DATE".equals(v.type)).count();
        long refCount      = values.stream().filter(v -> "CELL_REFERENCE".equals(v.type)).count();

        assertTrue(currencyCount >= 2, "Expected at least 2 currencies");
        assertTrue(pctCount      >= 1, "Expected at least 1 percentage");
        assertTrue(dateCount     >= 1, "Expected at least 1 date");
        assertTrue(refCount      >= 1, "Expected at least 1 cell reference");
    }

    // ── Empty body ───────────────────────────────────────────────────────────

    @Test
    void extract_emptyBody_returnsEmptyList() {
        assertEquals(List.of(), extractor.extract(doc("")),
                "Empty body should return empty list");
        assertEquals(List.of(), extractor.extract(doc("   ")),
                "Blank body should return empty list");
    }

    // ── Email metadata propagation ───────────────────────────────────────────

    @Test
    void extract_preservesEmailMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.subject", "Budget Review");
        meta.put("email.messageId", "<msg@example.com>");
        Document d = docWithMeta("The total is $10,000 for Q4.", meta);

        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);
        assertFalse(values.isEmpty(), "Should have extracted at least one value");

        for (EmailBodyValueExtractor.ExtractedValue v : values) {
            assertEquals("Budget Review", v.emailSubject,
                    "emailSubject should be propagated from metadata");
            assertEquals("<msg@example.com>", v.emailMessageId,
                    "emailMessageId should be propagated from metadata");
        }
    }

    // ── Batch extraction ────────────────────────────────────────────────────

    @Test
    void extractBatch_combinesResultsFromMultipleDocs() {
        Document d1 = doc("The budget is $50,000.");
        Document d2 = doc("Growth rate is 12%.");
        Document d3 = doc("Deadline is 2024-06-15.");

        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extractBatch(List.of(d1, d2, d3));

        long currencyCount = values.stream().filter(v -> "CURRENCY".equals(v.type)).count();
        long pctCount = values.stream().filter(v -> "PERCENTAGE".equals(v.type)).count();
        long dateCount = values.stream().filter(v -> "DATE".equals(v.type)).count();

        assertTrue(currencyCount >= 1, "Should have currency from doc1");
        assertTrue(pctCount >= 1, "Should have percentage from doc2");
        assertTrue(dateCount >= 1, "Should have date from doc3");
    }

    @Test
    void extractBatch_emptyListReturnsEmpty() {
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extractBatch(List.of());
        assertTrue(values.isEmpty());
    }

    // ── toMap ────────────────────────────────────────────────────────────────

    @Test
    void extractedValue_toMap_containsAllFields() {
        EmailBodyValueExtractor.ExtractedValue v =
                new EmailBodyValueExtractor.ExtractedValue("CURRENCY", "$500", 500.0, "budget is $500", 0.9);
        v.fieldName = "budget";
        v.emailSubject = "Q3 Review";
        v.emailMessageId = "<msg1@test.com>";

        Map<String, Object> map = v.toMap();

        assertEquals("CURRENCY", map.get("type"));
        assertEquals("$500", map.get("rawText"));
        assertEquals(500.0, map.get("parsedValue"));
        assertEquals("budget is $500", map.get("context"));
        assertEquals(0.9, map.get("confidence"));
        assertEquals("budget", map.get("fieldName"));
        assertEquals("Q3 Review", map.get("emailSubject"));
        assertEquals("<msg1@test.com>", map.get("emailMessageId"));
    }

    @Test
    void extractedValue_toMap_omitsNullOptionalFields() {
        EmailBodyValueExtractor.ExtractedValue v =
                new EmailBodyValueExtractor.ExtractedValue("DATE", "2024-01-01", "2024-01-01", "on 2024-01-01", 0.8);

        Map<String, Object> map = v.toMap();

        assertFalse(map.containsKey("fieldName"), "fieldName should be omitted when null");
        assertFalse(map.containsKey("emailSubject"), "emailSubject should be omitted when null");
        assertFalse(map.containsKey("emailMessageId"), "emailMessageId should be omitted when null");
    }

    // ── gmail.subject fallback ──────────────────────────────────────────────

    @Test
    void extract_usesGmailSubjectFallback() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.subject", "Quarterly Budget");
        Document d = docWithMeta("The amount is $1,000.", meta);

        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);
        assertFalse(values.isEmpty());

        for (EmailBodyValueExtractor.ExtractedValue v : values) {
            assertEquals("Quarterly Budget", v.emailSubject,
                    "Should fall back to gmail.subject when email.subject is absent");
        }
    }

    // ── Deduplication ────────────────────────────────────────────────────────

    @Test
    void extract_deduplicatesSameRawTextAndType() {
        // Same currency value appears twice in text
        Document d = doc("Total: $5,000. Please confirm: $5,000.");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        long count = values.stream()
                .filter(v -> "CURRENCY".equals(v.type) && "$5,000".equals(v.rawText))
                .count();
        assertEquals(1, count, "Duplicate currency '$5,000' should be deduplicated to one");
    }

    // ── Key-value with percentage ────────────────────────────────────────────

    @Test
    void extract_keyValuePairWithPercentage() {
        Document d = doc("rate: 15%");
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        List<EmailBodyValueExtractor.ExtractedValue> kvPct = values.stream()
                .filter(v -> v.fieldName != null && "rate".equals(v.fieldName))
                .toList();

        assertFalse(kvPct.isEmpty(), "Should extract key-value pair 'rate: 15%'");
        EmailBodyValueExtractor.ExtractedValue v = kvPct.get(0);
        assertEquals("PERCENTAGE", v.type, "KV pair with % should have type PERCENTAGE");
        assertTrue(v.parsedValue instanceof Double,
                "Parsed value should be Double, got: " + v.parsedValue.getClass());
        assertEquals(0.15, (Double) v.parsedValue, 0.001, "rate: 15% should parse to 0.15");
    }

    // ── Confidence levels ────────────────────────────────────────────────────

    @Test
    void extract_confidenceLevels() {
        // Cell references have the highest confidence (0.95),
        // followed by currency/percentage (0.9), dates (0.8), key-value (0.85)
        String body =
                "Revenue: $5,000 (rate: 10%) on 2024-01-01, see cell D7 for details.";
        Document d = doc(body);
        List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(d);

        EmailBodyValueExtractor.ExtractedValue cellRef = values.stream()
                .filter(v -> "CELL_REFERENCE".equals(v.type))
                .findFirst().orElse(null);
        assertNotNull(cellRef, "Should have a CELL_REFERENCE value");
        assertTrue(cellRef.confidence >= 0.9,
                "Cell references should have confidence >= 0.9, got: " + cellRef.confidence);

        EmailBodyValueExtractor.ExtractedValue date = values.stream()
                .filter(v -> "DATE".equals(v.type))
                .findFirst().orElse(null);
        assertNotNull(date, "Should have a DATE value");
        assertTrue(date.confidence >= 0.7,
                "Dates should have confidence >= 0.7, got: " + date.confidence);

        // Currency or numeric values have confidence >= 0.8 (directly or via key-value)
        boolean highConfidenceNumeric = values.stream()
                .filter(v -> "CURRENCY".equals(v.type) || "NUMERIC".equals(v.type))
                .anyMatch(v -> v.confidence >= 0.8);
        assertTrue(highConfidenceNumeric,
                "Currency/numeric values should have confidence >= 0.8");
    }
}
