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

/**
 * Snapshot of a fact sheet exported to the project's local format.
 * Written to {@code data/fact-sheets/project-fact-sheets.json}.
 */
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getVectorStorePath() { return vectorStorePath; }
    public void setVectorStorePath(String vectorStorePath) { this.vectorStorePath = vectorStorePath; }

    public String getKeywordIndexPath() { return keywordIndexPath; }
    public void setKeywordIndexPath(String keywordIndexPath) { this.keywordIndexPath = keywordIndexPath; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getEmbeddingModelSource() { return embeddingModelSource; }
    public void setEmbeddingModelSource(String embeddingModelSource) { this.embeddingModelSource = embeddingModelSource; }

    public boolean isRerankingEnabled() { return rerankingEnabled; }
    public void setRerankingEnabled(boolean rerankingEnabled) { this.rerankingEnabled = rerankingEnabled; }

    public String getRerankerType() { return rerankerType; }
    public void setRerankerType(String rerankerType) { this.rerankerType = rerankerType; }

    public boolean isEnableGraphBuilding() { return enableGraphBuilding; }
    public void setEnableGraphBuilding(boolean enableGraphBuilding) { this.enableGraphBuilding = enableGraphBuilding; }

    public String getGraphBuilderType() { return graphBuilderType; }
    public void setGraphBuilderType(String graphBuilderType) { this.graphBuilderType = graphBuilderType; }

    public String getGraphStorageType() { return graphStorageType; }
    public void setGraphStorageType(String graphStorageType) { this.graphStorageType = graphStorageType; }

    public int getFactCount() { return factCount; }
    public void setFactCount(int factCount) { this.factCount = factCount; }

    public String getIndexedAt() { return indexedAt; }
    public void setIndexedAt(String indexedAt) { this.indexedAt = indexedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
