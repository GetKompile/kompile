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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured values from email body text using regex patterns.
 * Finds monetary amounts, percentages, dates, and explicit cell/field references
 * that may correspond to Excel spreadsheet inputs.
 *
 * <p>This is a deterministic, rule-based extractor — no LLM needed.
 * Each extracted value includes its type, raw text, parsed value, and
 * surrounding context for downstream mapping to spreadsheet cells.</p>
 */
public class EmailBodyValueExtractor {

    private static final Logger log = LoggerFactory.getLogger(EmailBodyValueExtractor.class);

    // Currency amounts: $1,234.56 or USD 1234.56 or €100 etc.
    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "(?i)(?:\\$|USD|EUR|GBP|€|£|¥)\\s*([\\d,]+(?:\\.\\d{1,2})?)");

    // Percentages: 10.5%, 3.2 percent
    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)");

    // Dates: 2025-01-15, 01/15/2025, Jan 15 2025, etc.
    private static final Pattern DATE_ISO_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DATE_US_PATTERN = Pattern.compile(
            "(\\d{1,2}/\\d{1,2}/\\d{2,4})");

    // Explicit cell references: "cell A1", "Cell B2", "Sheet1!C3"
    private static final Pattern CELL_REF_PATTERN = Pattern.compile(
            "(?i)(?:cell\\s+|([A-Za-z][A-Za-z0-9]*!)?)([A-Z]{1,3}\\d{1,7})(?=\\s|[,;:.]|$)");

    // Key-value patterns: "Amount: 1234", "Rate = 5.5%", "Quantity: 100"
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)(amount|rate|price|cost|total|quantity|count|revenue|profit|margin|tax|discount|fee|salary|budget|volume|units?)\\s*[:=]\\s*\\$?([\\d,]+(?:\\.\\d+)?%?)");

    // Numeric values in context of field names
    private static final Pattern PLAIN_NUMBER_PATTERN = Pattern.compile(
            "(?<=\\s|^)([\\d,]+(?:\\.\\d+)?)(?=\\s|$)");

    /**
     * Extracts all identifiable values from an email document's body text.
     *
     * @param doc the email document
     * @return list of extracted values with type, value, context, and confidence
     */
    public List<ExtractedValue> extract(Document doc) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<ExtractedValue> values = new ArrayList<>();
        values.addAll(extractCurrencyValues(text));
        values.addAll(extractPercentages(text));
        values.addAll(extractDates(text));
        values.addAll(extractCellReferences(text));
        values.addAll(extractKeyValuePairs(text));

        // Deduplicate by raw text + type
        Set<String> seen = new HashSet<>();
        List<ExtractedValue> deduplicated = new ArrayList<>();
        for (ExtractedValue v : values) {
            String key = v.type + ":" + v.rawText;
            if (seen.add(key)) {
                deduplicated.add(v);
            }
        }

        // Add email metadata context
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            String subject = meta.get("email.subject") instanceof String s ? s : null;
            if (subject == null) subject = meta.get("gmail.subject") instanceof String s ? s : null;
            for (ExtractedValue v : deduplicated) {
                v.emailSubject = subject;
                v.emailMessageId = meta.get("email.messageId") instanceof String s ? s : null;
            }
        }

        return deduplicated;
    }

    /**
     * Extracts values from multiple documents.
     */
    public List<ExtractedValue> extractBatch(List<Document> docs) {
        List<ExtractedValue> all = new ArrayList<>();
        for (Document doc : docs) {
            all.addAll(extract(doc));
        }
        return all;
    }

    private List<ExtractedValue> extractCurrencyValues(String text) {
        List<ExtractedValue> values = new ArrayList<>();
        Matcher m = CURRENCY_PATTERN.matcher(text);
        while (m.find()) {
            String rawNum = m.group(1).replace(",", "");
            try {
                double amount = Double.parseDouble(rawNum);
                values.add(new ExtractedValue(
                        "CURRENCY", m.group(), amount,
                        getContext(text, m.start(), m.end()), 0.9));
            } catch (NumberFormatException e) {
                log.trace("Could not parse currency amount '{}': {}", rawNum, e.getMessage());
            }
        }
        return values;
    }

    private List<ExtractedValue> extractPercentages(String text) {
        List<ExtractedValue> values = new ArrayList<>();
        Matcher m = PERCENTAGE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double pct = Double.parseDouble(m.group(1));
                values.add(new ExtractedValue(
                        "PERCENTAGE", m.group(), pct / 100.0,
                        getContext(text, m.start(), m.end()), 0.9));
            } catch (NumberFormatException e) {
                log.trace("Could not parse percentage '{}': {}", m.group(1), e.getMessage());
            }
        }
        return values;
    }

    private List<ExtractedValue> extractDates(String text) {
        List<ExtractedValue> values = new ArrayList<>();
        for (Pattern p : List.of(DATE_ISO_PATTERN, DATE_US_PATTERN)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                values.add(new ExtractedValue(
                        "DATE", m.group(1), m.group(1),
                        getContext(text, m.start(), m.end()), 0.8));
            }
        }
        return values;
    }

    private List<ExtractedValue> extractCellReferences(String text) {
        List<ExtractedValue> values = new ArrayList<>();
        Matcher m = CELL_REF_PATTERN.matcher(text);
        while (m.find()) {
            String sheetPrefix = m.group(1) != null ? m.group(1) : "";
            String cellRef = sheetPrefix + m.group(2);
            values.add(new ExtractedValue(
                    "CELL_REFERENCE", m.group(), cellRef,
                    getContext(text, m.start(), m.end()), 0.95));
        }
        return values;
    }

    private List<ExtractedValue> extractKeyValuePairs(String text) {
        List<ExtractedValue> values = new ArrayList<>();
        Matcher m = KEY_VALUE_PATTERN.matcher(text);
        while (m.find()) {
            String fieldName = m.group(1).toLowerCase();
            String rawValue = m.group(2).replace(",", "");
            Object parsed;
            String type;
            if (rawValue.endsWith("%")) {
                type = "PERCENTAGE";
                try {
                    parsed = Double.parseDouble(rawValue.replace("%", "")) / 100.0;
                } catch (NumberFormatException e) {
                    parsed = rawValue;
                }
            } else {
                type = "NUMERIC";
                try {
                    if (rawValue.contains(".")) {
                        parsed = Double.parseDouble(rawValue);
                    } else {
                        parsed = Long.parseLong(rawValue);
                    }
                } catch (NumberFormatException e) {
                    parsed = rawValue;
                }
            }
            ExtractedValue ev = new ExtractedValue(
                    type, m.group(), parsed,
                    getContext(text, m.start(), m.end()), 0.85);
            ev.fieldName = fieldName;
            values.add(ev);
        }
        return values;
    }

    private String getContext(String text, int start, int end) {
        int ctxStart = Math.max(0, start - 50);
        int ctxEnd = Math.min(text.length(), end + 50);
        return text.substring(ctxStart, ctxEnd).trim();
    }

    /**
     * Represents a value extracted from email body text.
     */
    public static class ExtractedValue {
        /** Value type: CURRENCY, PERCENTAGE, DATE, NUMERIC, CELL_REFERENCE */
        public String type;
        /** The raw matched text from the email body */
        public String rawText;
        /** The parsed value (Double, Long, or String depending on type) */
        public Object parsedValue;
        /** Surrounding text context (~50 chars on each side) */
        public String context;
        /** Extraction confidence (0.0 to 1.0) */
        public double confidence;
        /** Inferred field name from key-value pattern, or null */
        public String fieldName;
        /** Email subject for provenance tracking */
        public String emailSubject;
        /** Email message ID for provenance tracking */
        public String emailMessageId;

        public ExtractedValue(String type, String rawText, Object parsedValue,
                              String context, double confidence) {
            this.type = type;
            this.rawText = rawText;
            this.parsedValue = parsedValue;
            this.context = context;
            this.confidence = confidence;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("rawText", rawText);
            map.put("parsedValue", parsedValue);
            map.put("context", context);
            map.put("confidence", confidence);
            if (fieldName != null) map.put("fieldName", fieldName);
            if (emailSubject != null) map.put("emailSubject", emailSubject);
            if (emailMessageId != null) map.put("emailMessageId", emailMessageId);
            return map;
        }
    }
}
