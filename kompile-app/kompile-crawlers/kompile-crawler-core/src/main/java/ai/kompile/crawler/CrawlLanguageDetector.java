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

package ai.kompile.crawler;

import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.langdetect.LanguageDetectionConfigService;
import ai.kompile.langdetect.OpenNLPLanguageDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * Enriches {@link CrawlItem}s with detected language metadata.
 *
 * <p>Integrates {@link OpenNLPLanguageDetector} into the crawl pipeline.
 * Skips detection if disabled in config, or if the item already has a language
 * assigned (e.g., from HTTP Content-Language header).</p>
 */
@Component
@ConditionalOnClass(name = "ai.kompile.langdetect.OpenNLPLanguageDetector")
public class CrawlLanguageDetector {

    private static final Logger log = LoggerFactory.getLogger(CrawlLanguageDetector.class);

    private final OpenNLPLanguageDetector languageDetector;
    private final LanguageDetectionConfigService configService;

    @Autowired
    public CrawlLanguageDetector(
            @Autowired(required = false) OpenNLPLanguageDetector languageDetector,
            @Autowired(required = false) LanguageDetectionConfigService configService) {
        this.languageDetector = languageDetector;
        this.configService = configService;
    }

    /**
     * Enriches the given crawl item with language detection results.
     *
     * <p>If the item already has a language set (e.g., from HTTP headers),
     * only the {@code languageSource} is updated to "header" and detection is skipped.</p>
     *
     * @param item       the crawl item to enrich
     * @param textSample a sample of the item's text content for detection
     */
    public void enrichWithLanguage(CrawlItem item, String textSample) {
        if (languageDetector == null || configService == null) {
            return;
        }
        if (!configService.getConfig().isEnabled() || !configService.getConfig().isDetectOnCrawl()) {
            return;
        }

        // If language already set (e.g., from HTTP Content-Language header), preserve it
        if (item.getLanguage() != null && !item.getLanguage().isBlank()) {
            if (item.getLanguageSource() == null) {
                item.setLanguageSource("header");
            }
            propagateToMetadata(item);
            return;
        }

        if (textSample == null || textSample.isBlank()) {
            return;
        }

        String detected = languageDetector.detectLanguage(textSample);
        double confidence = languageDetector.detectLanguageConfidence(textSample);

        item.setLanguage(detected);
        item.setLanguageConfidence(confidence);
        item.setLanguageSource("detected");

        propagateToMetadata(item);

        log.debug("Detected language '{}' (confidence={}) for crawl item: {}",
                detected, String.format("%.3f", confidence), item.getUrl());
    }

    /**
     * Returns true if language detection is available and enabled.
     */
    public boolean isAvailable() {
        return languageDetector != null && languageDetector.isReady()
                && configService != null && configService.isEnabled();
    }

    private void propagateToMetadata(CrawlItem item) {
        if (item.getMetadata() == null) {
            item.setMetadata(new HashMap<>());
        }
        if (item.getLanguage() != null) {
            item.getMetadata().put(SourceMetadataConstants.LANGUAGE, item.getLanguage());
        }
        if (item.getLanguageConfidence() != null) {
            item.getMetadata().put(SourceMetadataConstants.LANGUAGE_CONFIDENCE,
                    item.getLanguageConfidence().toString());
        }
        if (item.getLanguageSource() != null) {
            item.getMetadata().put(SourceMetadataConstants.LANGUAGE_DETECTION_METHOD,
                    item.getLanguageSource());
        }
    }
}
