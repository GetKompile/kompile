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

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and redacts personally identifiable information (PII) from documents
 * before indexing. Uses regex-based detection for structured PII patterns.
 *
 * <p>Supported entity types:</p>
 * <ul>
 *   <li>{@code EMAIL} — email addresses</li>
 *   <li>{@code PHONE} — phone numbers (US/international formats)</li>
 *   <li>{@code SSN} — US Social Security numbers</li>
 *   <li>{@code CREDIT_CARD} — credit card numbers</li>
 *   <li>{@code IP_ADDRESS} — IPv4 addresses</li>
 *   <li>{@code URL} — URLs (optional, often not PII)</li>
 * </ul>
 *
 * <p>Order: 300 (content filtering phase).</p>
 */
@Component
public class PiiRedactionPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionPreprocessor.class);

    private static final Map<String, Pattern> PII_PATTERNS = new HashMap<>();
    static {
        PII_PATTERNS.put("EMAIL", Pattern.compile(
                "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"));
        PII_PATTERNS.put("PHONE", Pattern.compile(
                "(?:\\+?1[\\s.-]?)?(?:\\(?\\d{3}\\)?[\\s.-]?)?\\d{3}[\\s.-]?\\d{4}"));
        PII_PATTERNS.put("SSN", Pattern.compile(
                "\\b\\d{3}[\\s.-]\\d{2}[\\s.-]\\d{4}\\b"));
        PII_PATTERNS.put("CREDIT_CARD", Pattern.compile(
                "\\b(?:\\d{4}[\\s.-]?){3}\\d{4}\\b"));
        PII_PATTERNS.put("IP_ADDRESS", Pattern.compile(
                "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"));
        PII_PATTERNS.put("URL", Pattern.compile(
                "https?://[^\\s<>\"']+"));
    }

    @Override
    public String id() {
        return "pii-redaction";
    }

    @Override
    public String displayName() {
        return "PII Redaction";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getPiiRedaction() == null || !config.getPiiRedaction().isEnabled()) {
            return false;
        }
        return document.getText() != null && !document.getText().isBlank();
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        PreprocessingConfig.PiiRedactionConfig piiConfig = config.getPiiRedaction();
        String strategy = piiConfig.getReplacementStrategy();
        boolean logCounts = piiConfig.isLogCounts();

        Set<String> activeTypes = new HashSet<>();
        if (piiConfig.getEntityTypes() != null && !piiConfig.getEntityTypes().isEmpty()) {
            for (String t : piiConfig.getEntityTypes()) {
                activeTypes.add(t.toUpperCase());
            }
        } else {
            // Default: redact all known types except URL
            activeTypes.addAll(PII_PATTERNS.keySet());
            activeTypes.remove("URL");
        }

        Map<String, Pattern> activePatterns = new HashMap<>();
        for (String type : activeTypes) {
            Pattern p = PII_PATTERNS.get(type);
            if (p != null) {
                activePatterns.put(type, p);
            }
        }

        int totalRedactions = 0;

        for (int i = 0; i < documents.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;

            Document doc = documents.get(i);
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            String redacted = text;
            Map<String, Integer> entityCounts = new HashMap<>();

            for (Map.Entry<String, Pattern> entry : activePatterns.entrySet()) {
                String type = entry.getKey();
                Pattern pattern = entry.getValue();
                Matcher matcher = pattern.matcher(redacted);

                int count = 0;
                StringBuilder sb = new StringBuilder();
                while (matcher.find()) {
                    String replacement = buildReplacement(type, strategy);
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    count++;
                }
                matcher.appendTail(sb);
                redacted = sb.toString();

                if (count > 0) {
                    entityCounts.put(type, count);
                    totalRedactions += count;
                }
            }

            if (!entityCounts.isEmpty()) {
                Document redactedDoc = new Document(redacted);
                redactedDoc.getMetadata().putAll(doc.getMetadata());
                redactedDoc.getMetadata().put("pii_redacted", true);
                if (logCounts) {
                    redactedDoc.getMetadata().put("pii_entity_counts", entityCounts);
                }
                documents.set(i, redactedDoc);
            }
        }

        log.info("PII redaction complete: {} redactions across {} documents",
                totalRedactions, documents.size());
        return documents;
    }

    private String buildReplacement(String entityType, String strategy) {
        if (strategy == null) strategy = "TYPE_TAG";
        return switch (strategy.toUpperCase()) {
            case "MASK" -> "[REDACTED]";
            case "REMOVE" -> "";
            case "HASH" -> "[###]";
            case "TYPE_TAG" -> "[" + entityType + "]";
            default -> "[REDACTED]";
        };
    }
}
