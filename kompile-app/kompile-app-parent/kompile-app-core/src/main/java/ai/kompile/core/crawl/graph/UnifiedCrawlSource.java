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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
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
 * Describes a single source within a unified crawl request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnifiedCrawlSource {

    /** Human-readable label for this source (e.g., "Work emails", "Project docs") */
    private String label;

    /** Source type — maps to a DocumentSourceDescriptor.SourceType */
    private DocumentSourceDescriptor.SourceType sourceType;

    /** The seed URL, directory path, or connection string */
    private String pathOrUrl;

    /** Maximum crawl depth for this source (default: 3) */
    @Builder.Default
    private int maxDepth = 3;

    /** Maximum documents to crawl from this source (0 = unlimited) */
    @Builder.Default
    private int maxDocuments = 0;

    /** URL/path patterns to include */
    @Builder.Default
    private List<String> includePatterns = new ArrayList<>();

    /** URL/path patterns to exclude */
    @Builder.Default
    private List<String> excludePatterns = new ArrayList<>();

    /** MIME types to accept */
    @Builder.Default
    private List<String> allowedContentTypes = new ArrayList<>();

    /** Source-specific properties (e.g., IMAP host/port, OAuth tokens) */
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    /**
     * Optional override for the document loader to use for this source
     * (null = auto-detect from content type / global config).
     */
    private String loaderName;

    /**
     * Optional override for the text chunker to use for this source
     * (null = use pipeline or global default).
     */
    private String chunkerName;
}
