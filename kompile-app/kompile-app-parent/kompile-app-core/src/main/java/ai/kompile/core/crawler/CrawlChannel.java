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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A pluggable crawl channel that extends the built-in source types.
 *
 * <p>Implement this interface and register as a Spring {@code @Component} to add
 * a custom data source connector. The channel is automatically discovered by the
 * {@code CrawlChannelRegistry} and becomes available as a crawl source with
 * {@code sourceType=CUSTOM} and {@code channelType="your-channel-id"}.</p>
 *
 * <h3>Example: RSS Feed Channel</h3>
 * <pre>{@code
 * @Component
 * public class RssFeedChannel implements CrawlChannel {
 *
 *     @Override
 *     public String getChannelType() { return "rss"; }
 *
 *     @Override
 *     public String getDisplayName() { return "RSS Feed"; }
 *
 *     @Override
 *     public String getDescription() { return "Crawl articles from RSS/Atom feeds"; }
 *
 *     @Override
 *     public List<String> getRequiredProperties() { return List.of("feedUrl"); }
 *
 *     @Override
 *     public Crawler getCrawler() { return new RssFeedCrawler(); }
 * }
 * }</pre>
 *
 * <h3>Using a custom channel in a crawl request</h3>
 * <pre>{@code
 * {
 *   "sources": [{
 *     "label": "Engineering Blog",
 *     "sourceType": "CUSTOM",
 *     "channelType": "rss",
 *     "pathOrUrl": "https://engineering.example.com/feed.xml",
 *     "locations": [
 *       {"locationId": "backend", "displayName": "Backend Posts",
 *        "properties": {"category": "backend"}},
 *       {"locationId": "infra", "displayName": "Infrastructure Posts",
 *        "properties": {"category": "infrastructure"}}
 *     ]
 *   }]
 * }
 * }</pre>
 */
public interface CrawlChannel {

    /**
     * Unique channel type identifier (e.g., "rss", "jira", "notion", "custom-wiki").
     * Used as the {@code channelType} field in {@link ai.kompile.core.crawl.graph.UnifiedCrawlSource}.
     */
    String getChannelType();

    /**
     * Human-readable display name (e.g., "RSS Feed", "Jira Issues").
     */
    String getDisplayName();

    /**
     * Brief description of what this channel crawls.
     */
    String getDescription();

    /**
     * Properties that must be provided in the source's {@code properties} map
     * for this channel to work (e.g., "apiToken", "projectKey").
     */
    List<String> getRequiredProperties();

    /**
     * Properties that can optionally be provided for additional configuration.
     */
    default List<String> getOptionalProperties() {
        return Collections.emptyList();
    }

    /**
     * Discovers available locations within this channel given connection properties.
     *
     * <p>For example, a Jira channel might return all projects the user has access to,
     * or a Notion channel might return all databases in a workspace. This enables
     * the UI to present a location picker after the user configures the channel.</p>
     *
     * @param connectionProperties properties needed to connect (API keys, tokens, etc.)
     * @return list of available locations, or empty if the channel doesn't support location discovery
     */
    default List<CrawlLocation> discoverLocations(Map<String, Object> connectionProperties) {
        return Collections.emptyList();
    }

    /**
     * Validates connection properties before starting a crawl.
     *
     * @param connectionProperties the properties to validate
     * @return list of validation error messages; empty list means valid
     */
    default List<String> validate(Map<String, Object> connectionProperties) {
        return Collections.emptyList();
    }

    /**
     * Returns the {@link Crawler} implementation that handles crawling for this channel.
     * The returned crawler is used with {@code sourceType=CUSTOM} and receives the
     * channel's properties via {@link CrawlConfig#getProperties()}.
     */
    Crawler getCrawler();
}
