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

package ai.kompile.crawl.graph;

import java.util.List;

/**
 * Callback for registering crawled sources as facts in a fact sheet.
 * Implemented in kompile-app-main where FactSheetService is available.
 * Called after source loading completes so that each crawled source
 * automatically appears in the fact sheet's fact list.
 */
public interface CrawlFactRegistrationCallback {

    /**
     * Register crawled sources as facts in the given fact sheet.
     *
     * @param factSheetId the fact sheet to add facts to
     * @param sources     the crawled sources to register
     * @return the number of facts created (excludes duplicates)
     */
    int registerCrawledSources(Long factSheetId, List<CrawledSourceInfo> sources);

    /**
     * Describes a crawled source to be registered as a fact.
     */
    record CrawledSourceInfo(
            /** Human-readable label for this source */
            String label,
            /** Source type name (e.g., "WEB_CRAWL", "DIRECTORY", "URL") */
            String sourceType,
            /** The seed URL, directory path, or connection string */
            String pathOrUrl,
            /** Number of documents loaded from this source */
            int documentsLoaded
    ) {}
}
