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

package ai.kompile.core.crawler;

import ai.kompile.core.loaders.DocumentSourceDescriptor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A Crawler discovers documents from a source and yields them for indexing.
 *
 * <p>Unlike {@link ai.kompile.core.loaders.DocumentLoader} which parses a single
 * known document, a Crawler proactively explores a source (website, filesystem,
 * inbox, SharePoint, etc.) to <em>find</em> documents. Each discovered item is
 * represented as a {@link CrawlItem} and fed into the existing ingest pipeline.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   validate(config)  →  start(config, listener)  →  CrawlJob
 *                                                       ├── pause() / resume()
 *                                                       ├── cancel()
 *                                                       └── checkpoint() → CrawlState
 * </pre>
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li><b>WebCrawler</b> — recursive HTTP crawling with link following</li>
 *   <li><b>FileSystemCrawler</b> — directory tree scanning with glob filters</li>
 * </ul>
 *
 * <h3>Custom crawlers</h3>
 * <p>To add a new crawler (e.g., SharePoint, IMAP inbox), implement this interface
 * and register as a Spring {@code @Component}. The {@link ai.kompile.core.crawler.CrawlConfig#getProperties()}
 * map carries any connector-specific configuration.</p>
 */
public interface Crawler {

    /** Unique identifier (e.g., "web", "filesystem", "sharepoint") */
    String getId();

    /** Human-readable name (e.g., "Web Crawler", "File System Crawler") */
    String getName();

    /** Brief description of what this crawler does */
    String getDescription();

    /** Source types this crawler can handle */
    Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes();

    /**
     * Starts a crawl job with the given configuration.
     * The crawl runs asynchronously; use the returned {@link CrawlJob} to monitor it
     * and the {@link CrawlEventListener} to receive events.
     *
     * @param config   Crawl configuration (seed, depth, filters, etc.)
     * @param listener Callback for crawl events (may be a no-op listener)
     * @return A handle to the running crawl job
     */
    CrawlJob start(CrawlConfig config, CrawlEventListener listener);

    /**
     * Validates a configuration before starting a crawl.
     *
     * @param config The configuration to validate
     * @return List of validation error messages. Empty list means valid.
     */
    default List<String> validate(CrawlConfig config) {
        return Collections.emptyList();
    }

    /**
     * Checks whether this crawler handles the given source type.
     */
    default boolean supports(DocumentSourceDescriptor.SourceType sourceType) {
        return getSupportedSourceTypes().contains(sourceType);
    }
}
