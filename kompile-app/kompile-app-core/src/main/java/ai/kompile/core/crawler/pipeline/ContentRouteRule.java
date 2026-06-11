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

package ai.kompile.core.crawler.pipeline;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A rule that maps discovered content to a specific {@link IngestPipelineDefinition}.
 *
 * <p>Rules are evaluated in priority order (lower = higher priority). The first
 * matching rule determines which pipeline processes the item. If no rule matches,
 * the default pipeline is used.</p>
 *
 * <h3>Matching logic:</h3>
 * <p>A rule matches if ALL specified conditions are met (AND logic).
 * Unset conditions (null/empty) are treated as "match anything".</p>
 *
 * <h3>Example rules:</h3>
 * <pre>
 * // Route PDFs to VLM pipeline
 * ContentRouteRule.builder()
 *     .pipelineId("pdf-vlm")
 *     .contentTypes(List.of("application/pdf"))
 *     .priority(10)
 *     .build();
 *
 * // Route images to image-vlm pipeline
 * ContentRouteRule.builder()
 *     .pipelineId("image-vlm")
 *     .contentTypes(List.of("image/png", "image/jpeg", "image/tiff"))
 *     .priority(10)
 *     .build();
 *
 * // Route docs URLs to code pipeline
 * ContentRouteRule.builder()
 *     .pipelineId("code-docs")
 *     .urlPatterns(List.of("..api.docs.."))
 *     .priority(20)
 *     .build();
 *
 * // Route .py/.java files to code pipeline
 * ContentRouteRule.builder()
 *     .pipelineId("code")
 *     .fileExtensions(List.of(".py", ".java", ".ts", ".go", ".rs"))
 *     .priority(10)
 *     .build();
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentRouteRule {

    /** ID of the target pipeline definition this rule routes to */
    private String pipelineId;

    /** Rule priority — lower numbers are evaluated first. Default: 100 */
    @Builder.Default
    private int priority = 100;

    // ---- Match conditions (all null/empty = match everything) ----

    /** MIME content types to match (e.g., "application/pdf", "text/html", "image/*") */
    private List<String> contentTypes;

    /** File extensions to match (e.g., ".pdf", ".docx", ".py"). Include the dot. */
    private List<String> fileExtensions;

    /** URL/path regex patterns to match */
    private List<String> urlPatterns;

    /** Source types to match */
    private List<DocumentSourceDescriptor.SourceType> sourceTypes;

    /** Minimum file size in bytes (null = no minimum) */
    private Long minSizeBytes;

    /** Maximum file size in bytes (null = no maximum) */
    private Long maxSizeBytes;

    /**
     * Language codes to match (ISO 639-1, e.g. "de", "fr", "zh").
     * Matches if the CrawlItem's detected language is in this list.
     * Null/empty = match any language.
     *
     * <p>Example: Route German content to a multilingual pipeline:</p>
     * <pre>
     * ContentRouteRule.builder()
     *     .pipelineId("german-multilingual")
     *     .languages(List.of("de"))
     *     .priority(5)
     *     .build();
     * </pre>
     */
    private List<String> languages;

    /**
     * Minimum language detection confidence (0.0-1.0) for language matching.
     * Items with lower confidence are treated as unmatched for the language condition.
     * Null = no minimum confidence required.
     */
    private Double minLanguageConfidence;
}
