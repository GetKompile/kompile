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

package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the document pre-processing pipeline that runs between
 * document loading and text normalization/chunking.
 *
 * <p>Pre-processing standardizes documents before they enter the embedding and
 * graph extraction phases. This ensures consistent language, encoding, and
 * content quality regardless of source diversity.</p>
 *
 * <p>Steps run in a defined order (see {@link DocumentPreprocessor#order()}).
 * Each step can be independently enabled/disabled and configured.</p>
 *
 * <h3>Example JSON in a crawl request:</h3>
 * <pre>{@code
 * {
 *   "preprocessing": {
 *     "enabled": true,
 *     "translation": {
 *       "enabled": true,
 *       "targetLanguage": "en",
 *       "preserveOriginal": true
 *     },
 *     "languageDetection": { "enabled": true },
 *     "unicodeNormalization": { "enabled": true, "form": "NFC" },
 *     "piiRedaction": {
 *       "enabled": true,
 *       "entityTypes": ["PERSON", "EMAIL", "PHONE", "SSN"]
 *     }
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PreprocessingConfig {

    /** Master switch — when false, the entire preprocessing phase is skipped */
    @Builder.Default
    private boolean enabled = false;

    /** Translation configuration */
    private TranslationConfig translation;

    /** Language detection configuration */
    private LanguageDetectionConfig languageDetection;

    /** Unicode/encoding normalization configuration */
    private UnicodeNormalizationConfig unicodeNormalization;

    /** Script transliteration configuration */
    private ScriptTransliterationConfig scriptTransliteration;

    /** PII redaction configuration */
    private PiiRedactionConfig piiRedaction;

    /** Boilerplate removal configuration */
    private BoilerplateRemovalConfig boilerplateRemoval;

    /** Content deduplication configuration */
    private DeduplicationConfig deduplication;

    /** Date/number format normalization configuration */
    private DateNumberNormalizationConfig dateNumberNormalization;

    /** Terminology standardization configuration */
    private TerminologyStandardizationConfig terminologyStandardization;

    /** LLM provider to use for LLM-backed steps (e.g., "openai", "anthropic", "default") */
    @Builder.Default
    private String llmProvider = "default";

    /** LLM model name for LLM-backed steps (null = provider default) */
    private String llmModelName;

    /** Parallelism for preprocessing steps (null = use system default) */
    private Integer parallelism;

    // ────────────────────────────────────────────────────────────────────────
    // Step-specific configuration classes
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Translates documents from their detected or specified source language
     * into a single target language. Ensures monolingual embeddings and
     * uniform search across multilingual source corpora.
     *
     * <p>Uses LLM-based translation to preserve semantic meaning, domain
     * terminology, and document structure. Rule-based fallbacks are available
     * for high-volume/low-cost scenarios.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TranslationConfig {
        /** Whether translation is enabled */
        @Builder.Default
        private boolean enabled = false;

        /** Target language (ISO 639-1 code, e.g., "en", "de", "ja", "zh") */
        @Builder.Default
        private String targetLanguage = "en";

        /**
         * Source language hint (ISO 639-1). When null, source language is
         * auto-detected from document content or metadata.
         */
        private String sourceLanguage;

        /**
         * Whether to preserve the original text in document metadata
         * under the key {@code original_text}. Useful for bilingual search.
         */
        @Builder.Default
        private boolean preserveOriginal = true;

        /**
         * Whether to preserve the original text as a separate document
         * (creates both original and translated versions in the index).
         */
        @Builder.Default
        private boolean dualIndex = false;

        /**
         * Minimum confidence threshold for language detection before
         * triggering translation. Documents already in the target language
         * or with uncertain detection are skipped. Range: 0.0–1.0.
         */
        @Builder.Default
        private double detectionConfidenceThreshold = 0.7;

        /**
         * Maximum characters per translation request. Longer documents
         * are split into segments, translated independently, and reassembled.
         */
        @Builder.Default
        private int maxCharsPerRequest = 8000;

        /** Domain hint for translation quality (e.g., "legal", "medical", "technical") */
        private String domainHint;

        /** Glossary of terms to preserve untranslated (e.g., brand names, product names) */
        @Builder.Default
        private List<String> preserveTerms = new ArrayList<>();

        /** Additional LLM instructions appended to the translation prompt */
        private String customInstructions;
    }

    /**
     * Detects the language of each document and stores the result in metadata.
     * Runs early in the pipeline (order 10) so downstream steps like translation
     * can use the detected language.
     *
     * <p>Uses a lightweight statistical detector (no LLM required) with optional
     * LLM fallback for short or ambiguous texts.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LanguageDetectionConfig {
        @Builder.Default
        private boolean enabled = false;

        /**
         * Minimum text length (chars) required for reliable detection.
         * Shorter texts use LLM fallback if available.
         */
        @Builder.Default
        private int minTextLength = 50;

        /** Whether to use LLM as fallback for ambiguous detections */
        @Builder.Default
        private boolean llmFallback = false;

        /** Override: force all documents to this language (skip detection) */
        private String forceLanguage;
    }

    /**
     * Normalizes Unicode encoding to a canonical form, fixes mojibake,
     * and standardizes typographic variants (curly quotes → straight,
     * em-dash → hyphen, etc.).
     *
     * <p>Runs at order 100 (content normalization phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnicodeNormalizationConfig {
        @Builder.Default
        private boolean enabled = false;

        /**
         * Unicode normalization form: NFC, NFD, NFKC, or NFKD.
         * NFC is recommended for most use cases.
         */
        @Builder.Default
        private String form = "NFC";

        /** Whether to fix common mojibake patterns (UTF-8 decoded as Latin-1, etc.) */
        @Builder.Default
        private boolean fixMojibake = true;

        /** Whether to standardize typographic variants (smart quotes, em-dashes, etc.) */
        @Builder.Default
        private boolean standardizeTypography = true;
    }

    /**
     * Transliterates text between writing scripts without changing the language.
     * For example: Cyrillic → Latin (for Russian text searchable in Latin characters),
     * Traditional Chinese → Simplified Chinese, Arabic → Latin romanization.
     *
     * <p>Runs at order 110 (content normalization phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScriptTransliterationConfig {
        @Builder.Default
        private boolean enabled = false;

        /**
         * Target script (e.g., "Latin", "Simplified", "Cyrillic").
         */
        @Builder.Default
        private String targetScript = "Latin";

        /**
         * Source script hint. When null, auto-detected from text.
         */
        private String sourceScript;

        /** Whether to preserve the original script text in metadata */
        @Builder.Default
        private boolean preserveOriginal = true;
    }

    /**
     * Detects and redacts personally identifiable information (PII) from
     * documents before indexing. Uses pattern matching for structured PII
     * (email, phone, SSN) and LLM for unstructured PII (names, addresses).
     *
     * <p>Runs at order 300 (content filtering phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PiiRedactionConfig {
        @Builder.Default
        private boolean enabled = false;

        /** PII entity types to detect and redact */
        @Builder.Default
        private List<String> entityTypes = new ArrayList<>();

        /** Replacement strategy: MASK ([REDACTED]), HASH, TYPE_TAG ([PERSON], [EMAIL]), REMOVE */
        @Builder.Default
        private String replacementStrategy = "TYPE_TAG";

        /** Whether to use LLM for unstructured PII detection (names, addresses in free text) */
        @Builder.Default
        private boolean useLlm = true;

        /** Whether to log redacted entity counts in job metadata (no PII values logged) */
        @Builder.Default
        private boolean logCounts = true;
    }

    /**
     * Removes boilerplate content that adds noise to embeddings and search:
     * cookie consent banners, navigation menus, legal disclaimers,
     * repeated headers/footers, email signatures, etc.
     *
     * <p>Runs at order 310 (content filtering phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BoilerplateRemovalConfig {
        @Builder.Default
        private boolean enabled = false;

        /** Remove common web boilerplate (nav, footer, cookie banners) */
        @Builder.Default
        private boolean removeWebBoilerplate = true;

        /** Remove email signatures and disclaimers */
        @Builder.Default
        private boolean removeEmailSignatures = true;

        /** Remove legal/compliance footer text */
        @Builder.Default
        private boolean removeLegalDisclaimers = true;

        /** Custom regex patterns to remove */
        @Builder.Default
        private List<String> customPatterns = new ArrayList<>();

        /**
         * Minimum content length after removal. If the document shrinks below
         * this threshold, the original is kept (prevents over-removal).
         */
        @Builder.Default
        private int minRemainingChars = 50;
    }

    /**
     * Detects and handles near-duplicate or exact-duplicate documents
     * within the crawl batch. Prevents redundant embeddings and
     * inflated search results.
     *
     * <p>Runs at order 400 (deduplication phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeduplicationConfig {
        @Builder.Default
        private boolean enabled = false;

        /** Similarity threshold for near-duplicate detection (0.0–1.0). 1.0 = exact only. */
        @Builder.Default
        private double similarityThreshold = 0.95;

        /** Strategy: KEEP_FIRST, KEEP_LONGEST, KEEP_NEWEST, MERGE_METADATA */
        @Builder.Default
        private String strategy = "KEEP_FIRST";

        /** Algorithm: SIMHASH, MINHASH, EXACT_HASH */
        @Builder.Default
        private String algorithm = "SIMHASH";

        /** Whether to record duplicate relationships in metadata for graph construction */
        @Builder.Default
        private boolean trackDuplicateRelations = true;
    }

    /**
     * Normalizes date formats, number formats, and currency representations
     * to a canonical form. Improves entity extraction accuracy and
     * cross-document consistency.
     *
     * <p>Runs at order 120 (content normalization phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DateNumberNormalizationConfig {
        @Builder.Default
        private boolean enabled = false;

        /** Target date format (e.g., "ISO8601", "US", "EU") */
        @Builder.Default
        private String dateFormat = "ISO8601";

        /** Target number locale for thousand/decimal separators */
        @Builder.Default
        private String numberLocale = "en-US";

        /** Whether to normalize currency symbols and amounts */
        @Builder.Default
        private boolean normalizeCurrency = true;

        /** Whether to normalize measurement units */
        @Builder.Default
        private boolean normalizeUnits = false;
    }

    /**
     * Maps domain-specific synonyms, abbreviations, and variant spellings
     * to canonical terms. Improves entity resolution and search recall
     * by standardizing vocabulary before indexing.
     *
     * <p>Uses a glossary (term → canonical form) that can be loaded from
     * a JSON file or specified inline. Runs at order 210 (content
     * transformation phase).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TerminologyStandardizationConfig {
        @Builder.Default
        private boolean enabled = false;

        /**
         * Inline glossary: variant → canonical form.
         * Example: {"ML": "machine learning", "DL": "deep learning"}
         */
        @Builder.Default
        private Map<String, String> glossary = new HashMap<>();

        /** Path to a glossary JSON file (merged with inline glossary) */
        private String glossaryFile;

        /** Whether replacements are case-sensitive */
        @Builder.Default
        private boolean caseSensitive = false;

        /** Whether to expand abbreviations only or also standardize full synonyms */
        @Builder.Default
        private boolean expandAbbreviations = true;

        /** Whether to use LLM for context-aware disambiguation of ambiguous terms */
        @Builder.Default
        private boolean contextAwareDisambiguation = false;
    }

    /**
     * Check if any preprocessing step is enabled.
     */
    public boolean hasAnyStepEnabled() {
        if (!enabled) return false;
        return (translation != null && translation.isEnabled())
                || (languageDetection != null && languageDetection.isEnabled())
                || (unicodeNormalization != null && unicodeNormalization.isEnabled())
                || (scriptTransliteration != null && scriptTransliteration.isEnabled())
                || (piiRedaction != null && piiRedaction.isEnabled())
                || (boilerplateRemoval != null && boilerplateRemoval.isEnabled())
                || (deduplication != null && deduplication.isEnabled())
                || (dateNumberNormalization != null && dateNumberNormalization.isEnabled())
                || (terminologyStandardization != null && terminologyStandardization.isEnabled());
    }
}
