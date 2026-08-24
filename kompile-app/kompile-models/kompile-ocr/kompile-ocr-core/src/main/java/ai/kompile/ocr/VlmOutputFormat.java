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

package ai.kompile.ocr;

/**
 * Requested rendering for VLM document processing. The model-native grammar, prompt, parser,
 * and termination rules are selected independently by the output-protocol registry.
 */
public enum VlmOutputFormat {
    /** Preserve the selected protocol's model-native output without rendering. */
    RAW,

    /**
     * Preserve a DocTags native representation when the selected protocol supports it.
     */
    DOCTAGS,

    /**
     * Render as Markdown, either natively or through the selected protocol renderer.
     */
    MARKDOWN,

    /** Backward-compatible hint preserving a Florence task-tag protocol's native output. */
    FLORENCE2,

    /** Backward-compatible hint preserving a Donut task/field-tag protocol's native output. */
    DONUT,

    /**
     * Render as plain text.
     */
    PLAIN_TEXT,

    /** Render through the selected protocol's HTML renderer. */
    HTML,

    /**
     * Render as JSON when the selected protocol supports it.
     */
    JSON,

    /**
     * Plain text alias.
     * @deprecated Use PLAIN_TEXT instead for consistency with DL4J OutputFormat.
     */
    @Deprecated
    TEXT
}
