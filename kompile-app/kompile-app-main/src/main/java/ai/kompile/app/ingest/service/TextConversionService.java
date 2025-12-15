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

package ai.kompile.app.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for converting rich format documents to plain text.
 *
 * This is a fast pre-processing step that normalizes document content before
 * the more expensive chunking and embedding steps. The goal is to:
 * 1. Extract pure text content from any document format
 * 2. Normalize whitespace and remove formatting artifacts
 * 3. Preserve meaningful structure (paragraphs, sections)
 * 4. Remove binary/non-text content indicators
 *
 * This step runs BEFORE chunking to ensure consistent text quality.
 */
@Service
public class TextConversionService {

    private static final Logger logger = LoggerFactory.getLogger(TextConversionService.class);

    // Patterns for text normalization
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern TAB_TO_SPACE = Pattern.compile("\t");
    private static final Pattern CARRIAGE_RETURN = Pattern.compile("\r\n?");
    private static final Pattern FORM_FEED = Pattern.compile("\f");
    private static final Pattern NULL_CHARS = Pattern.compile("\u0000");
    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\u200B-\u200D\uFEFF]");

    // Patterns for detecting non-text content markers
    private static final Pattern BINARY_INDICATOR = Pattern.compile("(?i)\\[?(?:binary|image|figure|table|chart|graph)(?:\\s*\\d+)?\\]?");
    private static final Pattern PAGE_HEADER_FOOTER = Pattern.compile("(?m)^\\s*(?:Page\\s+\\d+|\\d+\\s*of\\s*\\d+|^-\\s*\\d+\\s*-)\\s*$");

    /**
     * Result of a text conversion operation.
     */
    public record ConversionResult(
            /** The converted documents */
            List<Document> documents,
            /** Total characters in input */
            long inputChars,
            /** Total characters after conversion */
            long outputChars,
            /** Time taken in milliseconds */
            long durationMs,
            /** Number of documents processed */
            int documentsProcessed,
            /** Any warnings during conversion */
            List<String> warnings
    ) {
        public double getCompressionRatio() {
            return inputChars > 0 ? (double) outputChars / inputChars : 1.0;
        }
    }

    /**
     * Convert a list of documents to plain text.
     *
     * @param documents The documents to convert
     * @return ConversionResult with converted documents and statistics
     */
    public ConversionResult convert(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ConversionResult(List.of(), 0, 0, 0, 0, List.of());
        }

        long startTime = System.currentTimeMillis();
        List<Document> converted = new ArrayList<>(documents.size());
        List<String> warnings = new ArrayList<>();
        long inputChars = 0;
        long outputChars = 0;

        for (Document doc : documents) {
            String originalText = doc.getText();
            if (originalText == null) {
                warnings.add("Document with null text content skipped");
                continue;
            }

            inputChars += originalText.length();

            // Convert to plain text
            String plainText = convertToPlainText(originalText);
            outputChars += plainText.length();

            // Skip empty results
            if (plainText.isBlank()) {
                warnings.add("Document converted to empty text: " + getDocumentIdentifier(doc));
                continue;
            }

            // Create new document with converted text, preserving metadata
            Document convertedDoc = new Document(plainText);
            convertedDoc.getMetadata().putAll(doc.getMetadata());
            convertedDoc.getMetadata().put("converted", true);
            convertedDoc.getMetadata().put("originalLength", originalText.length());
            convertedDoc.getMetadata().put("convertedLength", plainText.length());

            converted.add(convertedDoc);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        logger.info("Text conversion complete: {} docs, {}→{} chars ({}% of original) in {}ms",
                converted.size(), inputChars, outputChars,
                inputChars > 0 ? (outputChars * 100.0 / inputChars) : 100.0,
                durationMs);

        return new ConversionResult(converted, inputChars, outputChars, durationMs, converted.size(), warnings);
    }

    /**
     * Convert a single document's text to plain text.
     *
     * @param text The text to convert
     * @return Normalized plain text
     */
    public String convertToPlainText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        // Step 1: Remove null characters and zero-width chars
        result = NULL_CHARS.matcher(result).replaceAll("");
        result = ZERO_WIDTH_CHARS.matcher(result).replaceAll("");

        // Step 2: Normalize line endings
        result = CARRIAGE_RETURN.matcher(result).replaceAll("\n");
        result = FORM_FEED.matcher(result).replaceAll("\n\n");

        // Step 3: Remove control characters (except newline)
        result = CONTROL_CHARS.matcher(result).replaceAll("");

        // Step 4: Convert tabs to spaces
        result = TAB_TO_SPACE.matcher(result).replaceAll("    ");

        // Step 5: Remove binary/non-text content markers
        result = BINARY_INDICATOR.matcher(result).replaceAll("");

        // Step 6: Remove page headers/footers
        result = PAGE_HEADER_FOOTER.matcher(result).replaceAll("");

        // Step 7: Normalize whitespace
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");

        // Step 8: Trim lines and overall text
        result = trimLines(result);
        result = result.trim();

        return result;
    }

    /**
     * Trim whitespace from the end of each line.
     */
    private String trimLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines[i].stripTrailing());
        }
        return sb.toString();
    }

    /**
     * Get a document identifier for logging.
     */
    private String getDocumentIdentifier(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata.containsKey("fileName")) {
            return metadata.get("fileName").toString();
        }
        if (metadata.containsKey("source")) {
            return metadata.get("source").toString();
        }
        if (doc.getId() != null) {
            return doc.getId();
        }
        return "unknown";
    }

    /**
     * Check if text appears to be primarily binary/non-text content.
     *
     * @param text Text to check
     * @return true if text appears to be non-text content
     */
    public boolean isPrimarilyBinaryContent(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }

        // Count printable vs non-printable characters
        int printable = 0;
        int nonPrintable = 0;

        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) ||
                Character.getType(c) == Character.DASH_PUNCTUATION ||
                Character.getType(c) == Character.OTHER_PUNCTUATION) {
                printable++;
            } else if (c < 32 || c == 127) {
                nonPrintable++;
            }
        }

        // If more than 10% is non-printable, consider it binary
        return nonPrintable > 0 && (double) nonPrintable / (printable + nonPrintable) > 0.1;
    }

    /**
     * Estimate the quality of the converted text (0.0 to 1.0).
     *
     * @param text The text to evaluate
     * @return Quality score between 0.0 (poor) and 1.0 (excellent)
     */
    public double estimateTextQuality(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        double score = 1.0;

        // Penalize very short text
        if (text.length() < 100) {
            score *= 0.5;
        }

        // Penalize text with high ratio of special characters
        long alphanumeric = text.chars().filter(Character::isLetterOrDigit).count();
        double alphaRatio = (double) alphanumeric / text.length();
        if (alphaRatio < 0.5) {
            score *= alphaRatio + 0.5;
        }

        // Penalize text with no spaces (likely garbled)
        if (!text.contains(" ")) {
            score *= 0.3;
        }

        // Penalize text with very long "words" (likely concatenated/garbled)
        String[] words = text.split("\\s+");
        long veryLongWords = java.util.Arrays.stream(words).filter(w -> w.length() > 50).count();
        if (veryLongWords > words.length * 0.1) {
            score *= 0.5;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }
}
