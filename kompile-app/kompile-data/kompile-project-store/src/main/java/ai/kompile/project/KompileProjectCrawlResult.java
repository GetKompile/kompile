/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of a completed crawl stored under {@code data/crawls/<id>/crawl-result.json}.
 * Used by the crawl catalog ({@code data/crawls/project-crawls.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@NoArgsConstructor
public class KompileProjectCrawlResult {
    private String profileId;
    private String name;
    private String status;
    private String finishedAt;
    private String loader;
    private String chunker;
    private String collection;
    private String factSheetName;
    private String markdownPath;
    private int documentCount;
    private int markdownCount;
    private int chunkCount;
}
