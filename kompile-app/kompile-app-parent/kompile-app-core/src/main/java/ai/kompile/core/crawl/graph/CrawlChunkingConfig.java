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

package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-crawl document chunking override shared by graph extraction and vector indexing.
 *
 * <p>Size units follow the selected chunker's contract (characters for character chunkers, tokens
 * for token chunkers). Null fields inherit project configuration and then the chunker's defaults.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlChunkingConfig {

    private String chunkerName;
    private Integer chunkSize;
    private Integer chunkOverlap;

    /** Additional implementation-specific options applied after the chunker's defaults. */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();
}
