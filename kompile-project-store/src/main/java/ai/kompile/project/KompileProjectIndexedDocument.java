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

@JsonIgnoreProperties(ignoreUnknown = true)
public class KompileProjectIndexedDocument {
    private Long id;
    private String sourceId;
    private String fileName;
    private String checksum;
    private String factSheetName;
    private String keywordIndexStatus;
    private int keywordPassageCount;
    private String vectorStoreStatus;
    private int vectorPassageCount;
    private String graphStatus;
    private int graphNodeCount;
    private String overallStatus;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getFactSheetName() { return factSheetName; }
    public void setFactSheetName(String factSheetName) { this.factSheetName = factSheetName; }

    public String getKeywordIndexStatus() { return keywordIndexStatus; }
    public void setKeywordIndexStatus(String keywordIndexStatus) { this.keywordIndexStatus = keywordIndexStatus; }

    public int getKeywordPassageCount() { return keywordPassageCount; }
    public void setKeywordPassageCount(int keywordPassageCount) { this.keywordPassageCount = keywordPassageCount; }

    public String getVectorStoreStatus() { return vectorStoreStatus; }
    public void setVectorStoreStatus(String vectorStoreStatus) { this.vectorStoreStatus = vectorStoreStatus; }

    public int getVectorPassageCount() { return vectorPassageCount; }
    public void setVectorPassageCount(int vectorPassageCount) { this.vectorPassageCount = vectorPassageCount; }

    public String getGraphStatus() { return graphStatus; }
    public void setGraphStatus(String graphStatus) { this.graphStatus = graphStatus; }

    public int getGraphNodeCount() { return graphNodeCount; }
    public void setGraphNodeCount(int graphNodeCount) { this.graphNodeCount = graphNodeCount; }

    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
