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

package ai.kompile.langdetect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for language detection behavior during crawl and ingest.
 * Persisted as {@code ~/.kompile/config/language-detection-config.json}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LanguageDetectionConfig {

    /** Global enable/disable for language detection */
    @Builder.Default
    private boolean enabled = true;

    /** Minimum confidence threshold (0.0-1.0). Below this, fallback language is used. */
    @Builder.Default
    private double minConfidenceThreshold = 0.50;

    /** Whether to detect language during crawl (on CrawlItem) */
    @Builder.Default
    private boolean detectOnCrawl = true;

    /** Whether to detect (or re-detect) language during ingest */
    @Builder.Default
    private boolean detectOnIngest = true;

    /** Maximum characters to use for language detection (performance guard) */
    @Builder.Default
    private int maxCharsForDetection = 2000;

    /** Language code to use when detection fails or is disabled */
    @Builder.Default
    private String fallbackLanguage = "en";

    /** Embedding model ID to use for non-English content */
    @Builder.Default
    private String multilingualEmbeddingModel = "arctic-embed-l";

    /** Embedding model ID to use for English content */
    @Builder.Default
    private String englishEmbeddingModel = "bge-base-en-v1.5";

    /** Whether to automatically switch embedding model based on detected language */
    @Builder.Default
    private boolean autoSwitchEmbeddingModel = false;

    /** Creates default configuration */
    public static LanguageDetectionConfig defaults() {
        return LanguageDetectionConfig.builder().build();
    }
}
