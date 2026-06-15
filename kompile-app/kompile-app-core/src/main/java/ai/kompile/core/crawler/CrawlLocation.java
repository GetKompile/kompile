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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a specific target location within a {@link CrawlChannel}.
 *
 * <p>A location narrows the scope of a crawl to a specific area within the channel.
 * For example:</p>
 * <ul>
 *   <li>In a Jira channel: a specific project ("BACKEND", "FRONTEND")</li>
 *   <li>In a Notion channel: a specific database or page tree</li>
 *   <li>In an RSS channel: a specific category or tag filter</li>
 *   <li>In a Slack channel: a specific set of channels ("#engineering", "#incidents")</li>
 *   <li>In a custom wiki: a specific space or section</li>
 * </ul>
 *
 * <p>Locations can be discovered dynamically via
 * {@link CrawlChannel#discoverLocations(Map)} or specified directly in crawl requests.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlLocation {

    /**
     * Unique identifier for this location within its channel
     * (e.g., "BACKEND", "engineering-db", "feed-category-infra").
     */
    private String locationId;

    /**
     * Human-readable display name (e.g., "Backend Project", "Engineering Database").
     */
    private String displayName;

    /**
     * Optional description of what this location contains.
     */
    private String description;

    /**
     * The channel type this location belongs to.
     * Back-reference to {@link CrawlChannel#getChannelType()}.
     */
    private String channelType;

    /**
     * Location-specific properties that are merged into the crawl config's
     * properties map when crawling this location. These override or augment
     * the channel-level properties.
     *
     * <p>For example, a Jira location might set {@code {"projectKey": "BACKEND"}},
     * while the channel-level properties provide the API token.</p>
     */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();
}
