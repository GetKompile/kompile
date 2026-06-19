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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of a fact sheet exported to the project's local format.
 * Written to {@code data/fact-sheets/project-fact-sheets.json}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KompileProjectFactSheet {
    private Long id;
    private String name;
    private String description;
    private boolean active;
    private String color;
    private String icon;
    private String vectorStorePath;
    private String keywordIndexPath;
    private String embeddingModel;
    private String embeddingModelSource;
    private boolean rerankingEnabled;
    private String rerankerType;
    private boolean enableGraphBuilding;
    private String graphBuilderType;
    private String graphStorageType;
    private int factCount;
    private String indexedAt;
    private String createdAt;
    private String updatedAt;
}
