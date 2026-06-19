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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Detects the language of each document and stores the result in metadata
 * as {@code detected_language} (ISO 639-1 code) and {@code detected_language_confidence}.
 *
 * <p>Uses a lightweight statistical approach based on character n-gram frequency
 * profiles. No external dependencies or LLM calls required for the primary path.</p>
 *
 * <p>Order: 10 (metadata enrichment — runs first so downstream steps like
 * translation can use the detected language).</p>
 */
@Component
public class LanguageDetectionPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(LanguageDetectionPreprocessor.class);

    public static final String META_DETECTED_LANGUAGE = "detected_language";
    public static final String META_DETECTED_LANGUAGE_CONFIDENCE = "detected_language_confidence";

    // Character-class patterns for script-based fast classification
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf]");
    private static final Pattern HANGUL_PATTERN = Pattern.compile("[\\uac00-\\ud7af\\u1100-\\u11ff]");
    private static final Pattern HIRAGANA_PATTERN = Pattern.compile("[\\u3040-\\u309f]");
    private static final Pattern KATAKANA_PATTERN = Pattern.compile("[\\u30a0-\\u30ff]");
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[\\u0400-\\u04ff]");
    private static final Pattern ARABIC_PATTERN = Pattern.compile("[\\u0600-\\u06ff]");
    private static final Pattern DEVANAGARI_PATTERN = Pattern.compile("[\\u0900-\\u097f]");
    private static final Pattern THAI_PATTERN = Pattern.compile("[\\u0e00-\\u0e7f]");

    // Common word lists for Latin-script language disambiguation
    private static final Map<String, List<String>> LANGUAGE_MARKERS = new HashMap<>();
    static {
        LANGUAGE_MARKERS.put("en", List.of("the", "and", "is", "was", "are", "for", "that", "with", "this", "from", "have", "been"));
        LANGUAGE_MARKERS.put("de", List.of("und", "der", "die", "das", "ist", "ein", "eine", "für", "mit", "auf", "nicht", "sich"));
        LANGUAGE_MARKERS.put("fr", List.of("les", "des", "est", "une", "que", "dans", "pour", "pas", "sur", "sont", "avec", "cette"));
        LANGUAGE_MARKERS.put("es", List.of("los", "las", "del", "una", "que", "por", "con", "para", "como", "más", "pero", "sus"));
        LANGUAGE_MARKERS.put("pt", List.of("uma", "que", "dos", "para", "com", "não", "por", "mais", "como", "são", "seu", "tem"));
        LANGUAGE_MARKERS.put("it", List.of("che", "del", "della", "per", "con", "una", "sono", "non", "anche", "più", "questo", "stato"));
        LANGUAGE_MARKERS.put("nl", List.of("het", "een", "van", "dat", "zijn", "voor", "met", "niet", "ook", "maar", "deze", "werd"));
        LANGUAGE_MARKERS.put("sv", List.of("och", "att", "det", "som", "för", "med", "den", "har", "var", "inte", "till", "från"));
        LANGUAGE_MARKERS.put("pl", List.of("nie", "się", "jest", "jak", "tak", "ale", "czy", "jego", "aby", "tego", "będzie", "może"));
        LANGUAGE_MARKERS.put("tr", List.of("bir", "olan", "için", "gibi", "daha", "ile", "ama", "çok", "kadar", "sonra", "olarak", "ancak"));
    }

    @Override
    public String id() {
        return "language-detection";
    }

    @Override
    public String displayName() {
        return "Language Detection";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getLanguageDetection() == null || !config.getLanguageDetection().isEnabled()) {
            return false;
        }
        // Skip if language is already set and not overridable
        if (document.getMetadata().containsKey(META_DETECTED_LANGUAGE)) {
            return false;
        }
        String text = document.getText();
        return text != null && !text.isBlank();
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        PreprocessingConfig.LanguageDetectionConfig langConfig = config.getLanguageDetection();
        String forceLanguage = langConfig != null ? langConfig.getForceLanguage() : null;
        int minLength = langConfig != null ? langConfig.getMinTextLength() : 50;

        for (Document doc : documents) {
            if (Thread.currentThread().isInterrupted()) break;

            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            if (forceLanguage != null && !forceLanguage.isBlank()) {
                doc.getMetadata().put(META_DETECTED_LANGUAGE, forceLanguage);
                doc.getMetadata().put(META_DETECTED_LANGUAGE_CONFIDENCE, 1.0);
                continue;
            }

            // Use existing metadata language if present
            Object existingLang = doc.getMetadata().get("language");
            if (existingLang instanceof String lang && !lang.isBlank()) {
                doc.getMetadata().put(META_DETECTED_LANGUAGE, lang.toLowerCase().substring(0, Math.min(2, lang.length())));
                doc.getMetadata().put(META_DETECTED_LANGUAGE_CONFIDENCE, 0.9);
                continue;
            }

            String sample = text.length() > 2000 ? text.substring(0, 2000) : text;

            if (sample.length() < minLength) {
                doc.getMetadata().put(META_DETECTED_LANGUAGE, "und"); // undetermined
                doc.getMetadata().put(META_DETECTED_LANGUAGE_CONFIDENCE, 0.0);
                continue;
            }

            DetectionResult result = detectLanguage(sample);
            doc.getMetadata().put(META_DETECTED_LANGUAGE, result.language);
            doc.getMetadata().put(META_DETECTED_LANGUAGE_CONFIDENCE, result.confidence);
        }

        log.debug("Language detection complete for {} documents", documents.size());
        return documents;
    }

    private DetectionResult detectLanguage(String text) {
        // Phase 1: Script-based detection for non-Latin scripts
        DetectionResult scriptResult = detectByScript(text);
        if (scriptResult != null) return scriptResult;

        // Phase 2: Word frequency analysis for Latin-script languages
        return detectLatinScript(text);
    }

    private DetectionResult detectByScript(String text) {
        int totalChars = text.length();
        int cjk = countMatches(CJK_PATTERN, text);
        int hangul = countMatches(HANGUL_PATTERN, text);
        int hiragana = countMatches(HIRAGANA_PATTERN, text);
        int katakana = countMatches(KATAKANA_PATTERN, text);
        int cyrillic = countMatches(CYRILLIC_PATTERN, text);
        int arabic = countMatches(ARABIC_PATTERN, text);
        int devanagari = countMatches(DEVANAGARI_PATTERN, text);
        int thai = countMatches(THAI_PATTERN, text);

        double threshold = 0.1;

        if ((double) hangul / totalChars > threshold) return new DetectionResult("ko", 0.95);
        if ((double) (hiragana + katakana) / totalChars > threshold) return new DetectionResult("ja", 0.95);
        if ((double) cjk / totalChars > threshold) return new DetectionResult("zh", 0.85);
        if ((double) cyrillic / totalChars > threshold) return new DetectionResult("ru", 0.80);
        if ((double) arabic / totalChars > threshold) return new DetectionResult("ar", 0.85);
        if ((double) devanagari / totalChars > threshold) return new DetectionResult("hi", 0.85);
        if ((double) thai / totalChars > threshold) return new DetectionResult("th", 0.90);

        return null;
    }

    private DetectionResult detectLatinScript(String text) {
        String lower = text.toLowerCase();
        String[] words = lower.split("\\s+");

        Map<String, Integer> scores = new HashMap<>();
        int totalWords = words.length;

        for (Map.Entry<String, List<String>> entry : LANGUAGE_MARKERS.entrySet()) {
            int count = 0;
            for (String word : words) {
                if (entry.getValue().contains(word)) count++;
            }
            scores.put(entry.getKey(), count);
        }

        String bestLang = "en";
        int bestScore = 0;
        int secondBest = 0;

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                secondBest = bestScore;
                bestScore = entry.getValue();
                bestLang = entry.getKey();
            } else if (entry.getValue() > secondBest) {
                secondBest = entry.getValue();
            }
        }

        double confidence;
        if (totalWords == 0 || bestScore == 0) {
            confidence = 0.3;
        } else {
            double ratio = (double) bestScore / totalWords;
            double separation = bestScore > 0 ? (double) (bestScore - secondBest) / bestScore : 0;
            confidence = Math.min(0.95, 0.4 + ratio * 2.0 + separation * 0.3);
        }

        return new DetectionResult(bestLang, confidence);
    }

    private int countMatches(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }

    private record DetectionResult(String language, double confidence) {}
}
