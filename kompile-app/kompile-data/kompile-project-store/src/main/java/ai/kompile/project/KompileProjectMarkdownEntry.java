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

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a markdown file entry within a Kompile project's {@code data/markdown/} directory.
 * Title, tags, and timestamps are extracted from YAML frontmatter when present.
 */
@Data
@NoArgsConstructor
public class KompileProjectMarkdownEntry {
    private String path;
    private String title;
    private String tags;
    private String createdAt;
    private String updatedAt;
    private String body;
    private String source;
    private String sourcePath;
    private String contentType;
    private String converter;
    private String crawlProfile;
    private String factSheet;
    private String collection;
    private String project;
}
