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
import ai.kompile.core.llm.chat.LLMChat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Translates documents from their detected source language into a target language
 * using LLM-based translation. Ensures monolingual embeddings and uniform search
 * across multilingual source corpora.
 *
 * <p>Depends on {@link LanguageDetectionPreprocessor} having run first (order 10)
 * to populate {@code detected_language} metadata. Documents already in the target
 * language are passed through unchanged.</p>
 *
 * <p>Order: 200 (content transformation phase).</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>LLM-based translation preserving document structure and formatting</li>
 *   <li>Domain-aware translation with glossary support</li>
 *   <li>Segment-based processing for long documents</li>
 *   <li>Original text preservation in metadata for bilingual retrieval</li>
 *   <li>Dual-index mode: creates both original and translated documents</li>
 * </ul>
 */
@Component
public class TranslationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(TranslationPreprocessor.class);

    public static final String META_TRANSLATED = "translated";
    public static final String META_TRANSLATION_SOURCE_LANG = "translation_source_language";
    public static final String META_TRANSLATION_TARGET_LANG = "translation_target_language";
    public static final String META_ORIGINAL_TEXT = "original_text";
    public static final String META_IS_TRANSLATION_COPY = "is_translation_copy";

    @Autowired(required = false)
    private LLMChat llmChat;

    @Override
    public String id() {
        return "translation";
    }

    @Override
    public String displayName() {
        return "Translation";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }

    @Override
    public double costMultiplier() {
        return 10.0;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getTranslation() == null || !config.getTranslation().isEnabled()) {
            return false;
        }
        String text = document.getText();
        if (text == null || text.isBlank()) return false;

        // Skip if already translated
        if (Boolean.TRUE.equals(document.getMetadata().get(META_TRANSLATED))) return false;

        // Skip if already in target language
        String targetLang = config.getTranslation().getTargetLanguage();
        String detectedLang = (String) document.getMetadata().get(
                LanguageDetectionPreprocessor.META_DETECTED_LANGUAGE);
        if (targetLang != null && targetLang.equalsIgnoreCase(detectedLang)) return false;

        // Skip if detection confidence is too low (uncertain language)
        Object confObj = document.getMetadata().get(
                LanguageDetectionPreprocessor.META_DETECTED_LANGUAGE_CONFIDENCE);
        if (confObj instanceof Number conf) {
            if (conf.doubleValue() < config.getTranslation().getDetectionConfidenceThreshold()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        if (llmChat == null) {
            log.warn("Translation preprocessor enabled but no LLMChat available — skipping");
            return documents;
        }

        PreprocessingConfig.TranslationConfig transConfig = config.getTranslation();
        String targetLang = transConfig.getTargetLanguage();
        boolean preserveOriginal = transConfig.isPreserveOriginal();
        boolean dualIndex = transConfig.isDualIndex();
        int maxCharsPerRequest = transConfig.getMaxCharsPerRequest();

        List<Document> result = new ArrayList<>(documents.size() + (dualIndex ? documents.size() : 0));
        AtomicInteger translated = new AtomicInteger(0);

        for (Document doc : documents) {
            if (Thread.currentThread().isInterrupted()) break;

            String text = doc.getText();
            String sourceLang = (String) doc.getMetadata().get(
                    LanguageDetectionPreprocessor.META_DETECTED_LANGUAGE);
            if (sourceLang == null) sourceLang = transConfig.getSourceLanguage();

            // Dual-index: add original document first
            if (dualIndex) {
                Document originalCopy = new Document(text);
                originalCopy.getMetadata().putAll(doc.getMetadata());
                originalCopy.getMetadata().put(META_IS_TRANSLATION_COPY, false);
                result.add(originalCopy);
            }

            // Translate in segments if too long
            String translatedText;
            if (text.length() <= maxCharsPerRequest) {
                translatedText = translateSegment(text, sourceLang, targetLang, transConfig);
            } else {
                translatedText = translateLongDocument(text, sourceLang, targetLang,
                        maxCharsPerRequest, transConfig);
            }

            if (translatedText != null && !translatedText.isBlank()) {
                Document translatedDoc = new Document(translatedText);
                translatedDoc.getMetadata().putAll(doc.getMetadata());
                translatedDoc.getMetadata().put(META_TRANSLATED, true);
                translatedDoc.getMetadata().put(META_TRANSLATION_SOURCE_LANG, sourceLang);
                translatedDoc.getMetadata().put(META_TRANSLATION_TARGET_LANG, targetLang);
                if (preserveOriginal) {
                    translatedDoc.getMetadata().put(META_ORIGINAL_TEXT, text);
                }
                if (dualIndex) {
                    translatedDoc.getMetadata().put(META_IS_TRANSLATION_COPY, true);
                }
                result.add(translatedDoc);
                translated.incrementAndGet();
            } else {
                // Translation failed — keep original
                result.add(doc);
            }
        }

        log.info("Translation complete: {}/{} documents translated to {}",
                translated.get(), documents.size(), targetLang);
        return result;
    }

    private String translateSegment(String text, String sourceLang, String targetLang,
                                     PreprocessingConfig.TranslationConfig config) {
        try {
            String prompt = buildTranslationPrompt(text, sourceLang, targetLang, config);
            String response = llmChat.prompt(prompt).call().content();
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            log.warn("Translation failed for segment ({}→{}): {}", sourceLang, targetLang, e.getMessage());
            return null;
        }
    }

    private String translateLongDocument(String text, String sourceLang, String targetLang,
                                          int maxChars, PreprocessingConfig.TranslationConfig config) {
        List<String> segments = splitIntoSegments(text, maxChars);
        StringBuilder translated = new StringBuilder();

        for (int i = 0; i < segments.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;

            String segment = segments.get(i);
            String result = translateSegment(segment, sourceLang, targetLang, config);
            if (result == null) {
                // On segment failure, include original segment
                translated.append(segment);
            } else {
                translated.append(result);
            }

            if (i < segments.size() - 1) {
                translated.append("\n\n");
            }
        }

        return translated.toString();
    }

    private String buildTranslationPrompt(String text, String sourceLang, String targetLang,
                                            PreprocessingConfig.TranslationConfig config) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Translate the following text");

        if (sourceLang != null && !sourceLang.equals("und")) {
            prompt.append(" from ").append(languageName(sourceLang));
        }
        prompt.append(" to ").append(languageName(targetLang)).append(".\n\n");

        prompt.append("Instructions:\n");
        prompt.append("- Preserve the original document structure, formatting, and paragraph breaks\n");
        prompt.append("- Maintain technical terms, proper nouns, and acronyms appropriately\n");
        prompt.append("- Do not add explanations, notes, or commentary\n");
        prompt.append("- Return ONLY the translated text\n");

        if (config.getDomainHint() != null) {
            prompt.append("- This is a ").append(config.getDomainHint())
                    .append(" document — use appropriate domain terminology\n");
        }

        if (config.getPreserveTerms() != null && !config.getPreserveTerms().isEmpty()) {
            prompt.append("- Keep these terms untranslated: ")
                    .append(String.join(", ", config.getPreserveTerms())).append("\n");
        }

        if (config.getCustomInstructions() != null) {
            prompt.append("- ").append(config.getCustomInstructions()).append("\n");
        }

        prompt.append("\n---\n\n");
        prompt.append(text);

        return prompt.toString();
    }

    private List<String> splitIntoSegments(String text, int maxChars) {
        List<String> segments = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());

            // Try to split at a paragraph boundary
            if (end < text.length()) {
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > start + maxChars / 2) {
                    end = paraBreak;
                } else {
                    // Fall back to sentence boundary
                    int sentEnd = text.lastIndexOf(". ", end);
                    if (sentEnd > start + maxChars / 2) {
                        end = sentEnd + 1;
                    }
                }
            }

            segments.add(text.substring(start, end).trim());
            start = end;
            // Skip whitespace between segments
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }

        return segments;
    }

    private String languageName(String code) {
        if (code == null) return "the detected language";
        return switch (code.toLowerCase()) {
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "pt" -> "Portuguese";
            case "it" -> "Italian";
            case "nl" -> "Dutch";
            case "sv" -> "Swedish";
            case "pl" -> "Polish";
            case "tr" -> "Turkish";
            case "ru" -> "Russian";
            case "zh" -> "Chinese";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "ar" -> "Arabic";
            case "hi" -> "Hindi";
            case "th" -> "Thai";
            case "vi" -> "Vietnamese";
            case "id" -> "Indonesian";
            case "uk" -> "Ukrainian";
            case "cs" -> "Czech";
            case "ro" -> "Romanian";
            case "el" -> "Greek";
            case "hu" -> "Hungarian";
            case "da" -> "Danish";
            case "fi" -> "Finnish";
            case "no" -> "Norwegian";
            default -> code;
        };
    }
}
