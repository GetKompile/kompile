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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.dto.confluence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to ingest Confluence pages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceIngestRequest {

    /**
     * Specific page IDs to ingest.
     */
    private List<String> pageIds;

    /**
     * Space keys to ingest all pages from.
     */
    private List<String> spaceKeys;

    /**
     * Whether to include child pages recursively.
     */
    @Builder.Default
    private Boolean includeChildren = true;

    /**
     * Whether to include page attachments.
     */
    @Builder.Default
    private Boolean includeAttachments = false;

    /**
     * Whether to include page comments.
     */
    @Builder.Default
    private Boolean includeComments = false;

    /**
     * Maximum depth for child page traversal (0 = no limit).
     */
    @Builder.Default
    private Integer maxDepth = 0;

    /**
     * The chunker strategy to use.
     */
    private String chunkerName;

    /**
     * Processing mode: auto, subprocess, or inprocess.
     */
    @Builder.Default
    private String processingMode = "auto";
}
