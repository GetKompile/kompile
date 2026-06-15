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

package ai.kompile.app.services;

import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.repository.FactRepository;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.crawl.graph.CrawlFactRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bridges the crawl pipeline's fact registration callback to FactSheetService.
 * When a unified crawl job completes source loading, this callback registers
 * each crawled source as a Fact in the target fact sheet.
 */
@Component
public class CrawlFactRegistrationCallbackImpl implements CrawlFactRegistrationCallback {

    private static final Logger log = LoggerFactory.getLogger(CrawlFactRegistrationCallbackImpl.class);

    @Autowired
    private FactSheetService factSheetService;
    @Autowired
    private FactRepository factRepository;

    @Autowired
    public CrawlFactRegistrationCallbackImpl(FactSheetService factSheetService,
                                              FactRepository factRepository) {
        this.factSheetService = factSheetService;
        this.factRepository = factRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected CrawlFactRegistrationCallbackImpl() {}


    @Override
    @Transactional
    public int registerCrawledSources(Long factSheetId, List<CrawledSourceInfo> sources) {
        if (factSheetId == null || sources == null || sources.isEmpty()) {
            return 0;
        }

        // Verify the fact sheet exists
        if (factSheetService.getSheetById(factSheetId).isEmpty()) {
            log.warn("Cannot register crawled facts: fact sheet {} not found", factSheetId);
            return 0;
        }

        int created = 0;
        for (CrawledSourceInfo source : sources) {
            if (source.documentsLoaded() <= 0) {
                log.debug("Skipping source '{}' with 0 documents loaded", source.label());
                continue;
            }

            try {
                String fileName = buildFactFileName(source);
                String sourceUrl = source.pathOrUrl();

                // Deduplicate: skip if a fact with the same sourceUrl already exists in this sheet
                if (sourceUrl != null && factRepository.existsByFactSheetIdAndSourceUrl(factSheetId, sourceUrl)) {
                    log.debug("Fact already exists for sourceUrl '{}' in sheet {}, skipping",
                            sourceUrl, factSheetId);
                    continue;
                }

                // Also check by fileName as fallback
                if (factRepository.existsByFactSheetIdAndFileName(factSheetId, fileName)) {
                    log.debug("Fact already exists for fileName '{}' in sheet {}, skipping",
                            fileName, factSheetId);
                    continue;
                }

                Fact.SourceType factSourceType = mapSourceType(source.sourceType());
                Fact.ViewMode viewMode = isWebSource(source.sourceType())
                        ? Fact.ViewMode.TEXT : Fact.ViewMode.DOWNLOAD_ONLY;

                factSheetService.addFact(
                        factSheetId,
                        fileName,
                        sourceUrl != null ? sourceUrl : fileName,
                        null, // no checksum for crawled sources
                        factSourceType,
                        null, // no extension for crawl sources
                        null, // no mime type
                        null, // no size
                        viewMode,
                        false, // not previewable
                        sourceUrl
                );

                created++;
                log.debug("Registered crawled source '{}' as fact in sheet {}",
                        source.label(), factSheetId);

            } catch (Exception e) {
                log.warn("Failed to register crawled source '{}' as fact: {}",
                        source.label(), e.getMessage());
            }
        }

        if (created > 0) {
            log.info("Registered {} crawled source(s) as facts in fact sheet {}", created, factSheetId);
        }
        return created;
    }

    private String buildFactFileName(CrawledSourceInfo source) {
        String label = source.label();
        if (label != null && !label.isBlank()) {
            return "[Crawl] " + label;
        }
        String path = source.pathOrUrl();
        if (path != null && !path.isBlank()) {
            return "[Crawl] " + path;
        }
        return "[Crawl] " + (source.sourceType() != null ? source.sourceType() : "Unknown source");
    }

    private Fact.SourceType mapSourceType(String crawlSourceType) {
        if (crawlSourceType == null) {
            return Fact.SourceType.IMPORT;
        }
        return switch (crawlSourceType) {
            case "URL", "WEB_CRAWL" -> Fact.SourceType.URL;
            case "FILE", "DIRECTORY" -> Fact.SourceType.STORED;
            default -> Fact.SourceType.IMPORT;
        };
    }

    private boolean isWebSource(String sourceType) {
        if (sourceType == null) return false;
        return switch (sourceType) {
            case "URL", "WEB_CRAWL", "CONFLUENCE", "SLACK", "SLACK_HISTORY",
                 "DISCORD", "DISCORD_HISTORY", "GMAIL", "GDOCS", "GDRIVE",
                 "GOOGLE_WORKSPACE" -> true;
            default -> false;
        };
    }
}
