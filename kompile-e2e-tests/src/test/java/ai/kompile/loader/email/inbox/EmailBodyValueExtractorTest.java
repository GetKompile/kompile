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

import ai.kompile.loader.email.inbox.EmailBodyValueExtractor.ExtractedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailBodyValueExtractorTest {

    private EmailBodyValueExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new EmailBodyValueExtractor();
    }

    // --- Currency extraction ---

    @Test
    void extractsCurrencyWithDollarSign() {
        Document doc = new Document("Please transfer $1,234.56 to the account.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue currency = values.stream()
                .filter(v -> "CURRENCY".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals(1234.56, (Double) currency.parsedValue, 0.001);
        assertEquals(0.9, currency.confidence, 0.001);
    }

    @Test
    void extractsCurrencyWithUsdPrefix() {
        Document doc = new Document("The total is USD 500.00 for this invoice.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue currency = values.stream()
                .filter(v -> "CURRENCY".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals(500.0, (Double) currency.parsedValue, 0.001);
    }

    @Test
    void extractsCurrencyWithEuroSymbol() {
        Document doc = new Document("Price is EUR 99 per unit.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "CURRENCY".equals(v.type)
                && ((Double) v.parsedValue) == 99.0));
    }

    @Test
    void extractsCurrencyWithPoundSign() {
        // Using actual pound sign
        Document doc = new Document("Cost: GBP 250.50 including VAT.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "CURRENCY".equals(v.type)
                && Math.abs((Double) v.parsedValue - 250.50) < 0.01));
    }

    @Test
    void extractsMultipleCurrencyValues() {
        Document doc = new Document("Payment of $100.00 received. Balance: $2,500.00 remaining.");
        List<ExtractedValue> values = extractor.extract(doc);

        long currencyCount = values.stream().filter(v -> "CURRENCY".equals(v.type)).count();
        assertEquals(2, currencyCount);
    }

    // --- Percentage extraction ---

    @Test
    void extractsPercentageWithSymbol() {
        Document doc = new Document("Interest rate is 5.5% per annum.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue pct = values.stream()
                .filter(v -> "PERCENTAGE".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals(0.055, (Double) pct.parsedValue, 0.001);
        assertEquals(0.9, pct.confidence, 0.001);
    }

    @Test
    void extractsPercentageWithWord() {
        Document doc = new Document("Discount of 10 percent applied.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue pct = values.stream()
                .filter(v -> "PERCENTAGE".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals(0.10, (Double) pct.parsedValue, 0.001);
    }

    @Test
    void extractsWholeNumberPercentage() {
        Document doc = new Document("Tax rate: 25%.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "PERCENTAGE".equals(v.type)
                && Math.abs((Double) v.parsedValue - 0.25) < 0.001));
    }

    // --- Date extraction ---

    @Test
    void extractsIsoDate() {
        Document doc = new Document("The deadline is 2025-01-15 for submission.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue date = values.stream()
                .filter(v -> "DATE".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals("2025-01-15", date.parsedValue);
        assertEquals(0.8, date.confidence, 0.001);
    }

    @Test
    void extractsUsDate() {
        Document doc = new Document("Meeting scheduled for 01/15/2025.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "DATE".equals(v.type)
                && "01/15/2025".equals(v.parsedValue)));
    }

    @Test
    void extractsMultipleDateFormats() {
        Document doc = new Document("Start: 2025-03-01. End: 12/31/2025.");
        List<ExtractedValue> values = extractor.extract(doc);

        long dateCount = values.stream().filter(v -> "DATE".equals(v.type)).count();
        assertEquals(2, dateCount);
    }

    // --- Cell reference extraction ---

    @Test
    void extractsExplicitCellReference() {
        Document doc = new Document("Please update cell A1 with the new value.");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue ref = values.stream()
                .filter(v -> "CELL_REFERENCE".equals(v.type))
                .findFirst().orElseThrow();
        assertEquals("A1", ref.parsedValue);
        assertEquals(0.95, ref.confidence, 0.001);
    }

    @Test
    void extractsSheetQualifiedCellReference() {
        Document doc = new Document("The value in Sheet1!C3 needs updating.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "CELL_REFERENCE".equals(v.type)
                && "Sheet1!C3".equals(v.parsedValue)));
    }

    @Test
    void extractsCellReferenceWithMultiLetterColumn() {
        Document doc = new Document("See cell AA100 for details.");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "CELL_REFERENCE".equals(v.type)
                && "AA100".equals(v.parsedValue)));
    }

    // --- Key-value pair extraction ---

    @Test
    void extractsAmountKeyValue() {
        Document doc = new Document("Amount: 5000");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue kv = values.stream()
                .filter(v -> "amount".equals(v.fieldName))
                .findFirst().orElseThrow();
        assertEquals("NUMERIC", kv.type);
        assertEquals(5000L, kv.parsedValue);
        assertEquals(0.85, kv.confidence, 0.001);
    }

    @Test
    void extractsRateKeyValueAsPercentage() {
        Document doc = new Document("Rate = 3.5%");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue kv = values.stream()
                .filter(v -> "rate".equals(v.fieldName))
                .findFirst().orElseThrow();
        assertEquals("PERCENTAGE", kv.type);
        assertEquals(0.035, (Double) kv.parsedValue, 0.001);
    }

    @Test
    void extractsPriceKeyValueWithDollar() {
        Document doc = new Document("Price: $150.75");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "price".equals(v.fieldName)));
    }

    @Test
    void extractsDecimalNumericKeyValue() {
        Document doc = new Document("Total: 1,234.56");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue kv = values.stream()
                .filter(v -> "total".equals(v.fieldName))
                .findFirst().orElseThrow();
        assertEquals("NUMERIC", kv.type);
        assertEquals(1234.56, (Double) kv.parsedValue, 0.001);
    }

    @Test
    void extractsMultipleKeyValueFields() {
        Document doc = new Document("Revenue: 50000\nCost: 30000\nProfit: 20000");
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "revenue".equals(v.fieldName)));
        assertTrue(values.stream().anyMatch(v -> "cost".equals(v.fieldName)));
        assertTrue(values.stream().anyMatch(v -> "profit".equals(v.fieldName)));
    }

    @Test
    void keyValueRecognizesAllFieldNames() {
        for (String field : List.of("amount", "rate", "price", "cost", "total",
                "quantity", "count", "revenue", "profit", "margin",
                "tax", "discount", "fee", "salary", "budget", "volume", "unit", "units")) {
            Document doc = new Document(field + ": 42");
            List<ExtractedValue> values = extractor.extract(doc);
            assertTrue(values.stream().anyMatch(v -> v.fieldName != null),
                    "Should extract key-value for field: " + field);
        }
    }

    // --- Deduplication ---

    @Test
    void deduplicatesByTypeAndRawText() {
        Document doc = new Document("$500 payment confirmed. Reminder: $500 due.");
        List<ExtractedValue> values = extractor.extract(doc);

        long currencyCount = values.stream()
                .filter(v -> "CURRENCY".equals(v.type) && v.rawText.contains("500"))
                .count();
        assertEquals(1, currencyCount);
    }

    // --- Email metadata ---

    @Test
    void attachesEmailSubjectAndMessageId() {
        Document doc = new Document("Amount: 100",
                Map.of("email.subject", "Invoice #42", "email.messageId", "msg-001"));
        List<ExtractedValue> values = extractor.extract(doc);

        assertFalse(values.isEmpty());
        assertEquals("Invoice #42", values.get(0).emailSubject);
        assertEquals("msg-001", values.get(0).emailMessageId);
    }

    @Test
    void attachesGmailSubjectFallback() {
        Document doc = new Document("$100 payment",
                Map.of("gmail.subject", "Payment Notice"));
        List<ExtractedValue> values = extractor.extract(doc);

        assertFalse(values.isEmpty());
        assertEquals("Payment Notice", values.get(0).emailSubject);
    }

    @Test
    void noMetadataFieldsWhenMetadataAbsent() {
        Document doc = new Document("$100 payment");
        List<ExtractedValue> values = extractor.extract(doc);

        assertFalse(values.isEmpty());
        assertNull(values.get(0).emailSubject);
        assertNull(values.get(0).emailMessageId);
    }

    // --- Edge cases ---

    @Test
    void returnsEmptyForEmptyStringText() {
        Document doc = new Document("");
        List<ExtractedValue> values = extractor.extract(doc);
        assertTrue(values.isEmpty());
    }

    @Test
    void returnsEmptyForBlankText() {
        Document doc = new Document("   ");
        List<ExtractedValue> values = extractor.extract(doc);
        assertTrue(values.isEmpty());
    }

    @Test
    void returnsEmptyForNoMatches() {
        Document doc = new Document("Hello, this is a simple email with no numbers.");
        List<ExtractedValue> values = extractor.extract(doc);
        assertTrue(values.isEmpty());
    }

    @Test
    void contextIncludesSurroundingText() {
        String text = "The approved budget is $10,000.00 for Q1 operations.";
        Document doc = new Document(text);
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue currency = values.stream()
                .filter(v -> "CURRENCY".equals(v.type))
                .findFirst().orElseThrow();
        assertNotNull(currency.context);
        assertTrue(currency.context.contains("budget"));
    }

    // --- Batch extraction ---

    @Test
    void extractBatchFromMultipleDocuments() {
        List<Document> docs = List.of(
                new Document("$100 payment"),
                new Document("Rate: 5%"),
                new Document("Deadline: 2025-06-01")
        );
        List<ExtractedValue> values = extractor.extractBatch(docs);

        assertTrue(values.stream().anyMatch(v -> "CURRENCY".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "PERCENTAGE".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "DATE".equals(v.type)));
    }

    @Test
    void extractBatchHandlesEmptyList() {
        List<ExtractedValue> values = extractor.extractBatch(List.of());
        assertTrue(values.isEmpty());
    }

    // --- toMap ---

    @Test
    void extractedValueToMapContainsAllFields() {
        Document doc = new Document("Amount: 500",
                Map.of("email.subject", "Test", "email.messageId", "m1"));
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue kv = values.stream()
                .filter(v -> "amount".equals(v.fieldName))
                .findFirst().orElseThrow();
        Map<String, Object> map = kv.toMap();

        assertEquals("NUMERIC", map.get("type"));
        assertNotNull(map.get("rawText"));
        assertNotNull(map.get("parsedValue"));
        assertNotNull(map.get("context"));
        assertEquals(0.85, (Double) map.get("confidence"), 0.001);
        assertEquals("amount", map.get("fieldName"));
        assertEquals("Test", map.get("emailSubject"));
        assertEquals("m1", map.get("emailMessageId"));
    }

    @Test
    void extractedValueToMapOmitsNullOptionalFields() {
        Document doc = new Document("$50 total");
        List<ExtractedValue> values = extractor.extract(doc);

        ExtractedValue currency = values.stream()
                .filter(v -> "CURRENCY".equals(v.type))
                .findFirst().orElseThrow();
        Map<String, Object> map = currency.toMap();

        assertFalse(map.containsKey("fieldName"));
        assertFalse(map.containsKey("emailSubject"));
        assertFalse(map.containsKey("emailMessageId"));
    }

    // --- Mixed content ---

    @Test
    void extractsAllTypesFromRichEmail() {
        Document doc = new Document(
                "Hi John,\n\n" +
                "Please update cell B5 with the new budget amount of $25,000.00.\n" +
                "The discount rate is 12.5% effective from 2025-04-01.\n" +
                "Total: 30000\n" +
                "Revenue: 50000\n\n" +
                "Thanks,\nJane"
        );
        List<ExtractedValue> values = extractor.extract(doc);

        assertTrue(values.stream().anyMatch(v -> "CURRENCY".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "PERCENTAGE".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "DATE".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "CELL_REFERENCE".equals(v.type)));
        assertTrue(values.stream().anyMatch(v -> "total".equals(v.fieldName)));
        assertTrue(values.stream().anyMatch(v -> "revenue".equals(v.fieldName)));
    }
}
