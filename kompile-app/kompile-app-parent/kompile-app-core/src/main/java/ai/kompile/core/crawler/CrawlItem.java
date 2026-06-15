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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single item discovered during a crawl.
 * Each CrawlItem maps to one document that should be loaded and indexed.
 * The crawler produces these; the ingest pipeline consumes them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlItem {

    /** The URL, file path, or identifier of the discovered item */
    private String url;

    /** Where this item was discovered from (parent page URL, directory, etc.) */
    private String parentUrl;

    /** Crawl depth from the seed (0 = seed itself) */
    private int depth;

    /**
     * Pre-built source descriptor for feeding into the DocumentLoader pipeline.
     * The crawler populates this with the appropriate SourceType, pathOrUrl, and metadata.
     */
    private DocumentSourceDescriptor sourceDescriptor;

    /** Additional metadata discovered during crawling (headers, link text, etc.) */
    private Map<String, Object> metadata;

    /** Content hash for change detection in incremental crawls */
    private String contentHash;

    /** When this item was discovered */
    private Instant discoveredAt;

    /** MIME type if known from HTTP headers or file extension */
    private String contentType;

    /** Content length in bytes if known */
    private Long contentLength;

    /** ISO 639-1 language code detected for this item's content, e.g. "en", "de" */
    private String language;

    /** Confidence score from the language detector (0.0-1.0) */
    private Double languageConfidence;

    /** How language was assigned: "detected", "header", "config", "default" */
    private String languageSource;

    /** Optional tags for categorization and routing */
    @Builder.Default
    private Set<String> tags = new HashSet<>();
}
