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

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalizes Unicode encoding, fixes mojibake, and standardizes typographic
 * variants to ensure consistent text representation before embedding.
 *
 * <p>Order: 100 (content normalization phase — runs after language detection
 * but before translation and other content transforms).</p>
 */
@Component
public class UnicodeNormalizationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(UnicodeNormalizationPreprocessor.class);

    // Common mojibake patterns (UTF-8 decoded as Latin-1)
    private static final Map<String, String> MOJIBAKE_FIXES;
    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("\u00C3\u00A9", "\u00E9"); // é
        m.put("\u00C3\u00A8", "\u00E8"); // è
        m.put("\u00C3\u00A0", "\u00E0"); // à
        m.put("\u00C3\u00A2", "\u00E2"); // â
        m.put("\u00C3\u00A7", "\u00E7"); // ç
        m.put("\u00C3\u00B4", "\u00F4"); // ô
        m.put("\u00C3\u00BC", "\u00FC"); // ü
        m.put("\u00C3\u00B6", "\u00F6"); // ö
        m.put("\u00C3\u00A4", "\u00E4"); // ä
        m.put("\u00C3\u00B1", "\u00F1"); // ñ
        m.put("\u00C3\u00A1", "\u00E1"); // á
        m.put("\u00C3\u00AD", "\u00ED"); // í
        m.put("\u00C3\u00BA", "\u00FA"); // ú
        m.put("\u00C3\u00B3", "\u00F3"); // ó
        m.put("\u00C2\u00A0", " ");      // non-breaking space
        m.put("\u00C2\u00A9", "\u00A9"); // ©
        m.put("\u00C2\u00AE", "\u00AE"); // ®
        MOJIBAKE_FIXES = Map.copyOf(m);
    }

    // Typographic standardization
    private static final Pattern SMART_SINGLE_QUOTES = Pattern.compile("[\u2018\u2019\u201A\u201B]");
    private static final Pattern SMART_DOUBLE_QUOTES = Pattern.compile("[\u201C\u201D\u201E\u201F]");
    private static final Pattern EM_DASH = Pattern.compile("[\u2014]");
    private static final Pattern EN_DASH = Pattern.compile("[\u2013]");
    private static final Pattern ELLIPSIS = Pattern.compile("[\u2026]");
    private static final Pattern NON_BREAKING_SPACE = Pattern.compile("[\u00A0]");
    private static final Pattern BULLET = Pattern.compile("[\u2022\u2023\u2043]");

    @Override
    public String id() {
        return "unicode-normalization";
    }

    @Override
    public String displayName() {
        return "Unicode Normalization";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getUnicodeNormalization() == null || !config.getUnicodeNormalization().isEnabled()) {
            return false;
        }
        return document.getText() != null && !document.getText().isEmpty();
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        PreprocessingConfig.UnicodeNormalizationConfig normConfig = config.getUnicodeNormalization();
        Normalizer.Form form = parseForm(normConfig.getForm());
        boolean fixMojibake = normConfig.isFixMojibake();
        boolean standardizeTypo = normConfig.isStandardizeTypography();

        int modified = 0;
        for (Document doc : documents) {
            if (Thread.currentThread().isInterrupted()) break;

            String text = doc.getText();
            if (text == null || text.isEmpty()) continue;

            String result = text;

            // Fix mojibake first (before normalization can make it worse)
            if (fixMojibake) {
                result = fixMojibake(result);
            }

            // Unicode normalization
            if (!Normalizer.isNormalized(result, form)) {
                result = Normalizer.normalize(result, form);
            }

            // Standardize typography
            if (standardizeTypo) {
                result = standardizeTypography(result);
            }

            if (!result.equals(text)) {
                // Create new Document with normalized text, preserving metadata
                Document normalized = new Document(result);
                normalized.getMetadata().putAll(doc.getMetadata());
                normalized.getMetadata().put("unicode_normalized", true);
                documents.set(documents.indexOf(doc), normalized);
                modified++;
            }
        }

        log.debug("Unicode normalization modified {}/{} documents", modified, documents.size());
        return documents;
    }

    private String fixMojibake(String text) {
        String result = text;
        for (Map.Entry<String, String> fix : MOJIBAKE_FIXES.entrySet()) {
            result = result.replace(fix.getKey(), fix.getValue());
        }
        return result;
    }

    private String standardizeTypography(String text) {
        String result = SMART_SINGLE_QUOTES.matcher(text).replaceAll("'");
        result = SMART_DOUBLE_QUOTES.matcher(result).replaceAll("\"");
        result = EM_DASH.matcher(result).replaceAll(" - ");
        result = EN_DASH.matcher(result).replaceAll("-");
        result = ELLIPSIS.matcher(result).replaceAll("...");
        result = NON_BREAKING_SPACE.matcher(result).replaceAll(" ");
        result = BULLET.matcher(result).replaceAll("-");
        return result;
    }

    private Normalizer.Form parseForm(String form) {
        if (form == null) return Normalizer.Form.NFC;
        return switch (form.toUpperCase()) {
            case "NFD" -> Normalizer.Form.NFD;
            case "NFKC" -> Normalizer.Form.NFKC;
            case "NFKD" -> Normalizer.Form.NFKD;
            default -> Normalizer.Form.NFC;
        };
    }
}
