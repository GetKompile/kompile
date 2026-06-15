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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KompileProjectCrawlProfile {
    private String id;
    private String name;
    private String description;
    private List<String> sources = new ArrayList<>();
    private int maxDepth = 3;
    private int maxDocuments;
    private boolean sameDomain = true;
    private boolean robots = true;
    private int delayMs = 500;
    private int timeoutMin = 60;
    private List<String> includePatterns = new ArrayList<>();
    private List<String> excludePatterns = new ArrayList<>();
    private List<String> contentTypes = new ArrayList<>();
    private String chunker;
    private String loader;
    private String collection;
    private boolean multimodal;
    private String vlmModel;
    private boolean graphExtraction;
    private List<String> graphEntityTypes = new ArrayList<>();
    private List<String> graphRelationTypes = new ArrayList<>();
    private String graphModelProvider;
    private String graphModelName;
    private Double graphTemperature;
    private Double graphMinConfidence;
    private Boolean graphAutoAccept;
    private Double graphAutoAcceptThreshold;
    private String graphSchemaMode;
    private String schemaPresetId;
    private String graphCustomPrompt;
    private boolean graphLocal;
    private boolean graphAutoStart;
    private boolean followLinks;
    private boolean includeHidden;
    private String sourceType;
    private boolean watch;
    private String factSheetName;
    private KompileProjectLifecycleState lifecycle = KompileProjectLifecycleState.ACTIVE;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxDocuments() {
        return maxDocuments;
    }

    public void setMaxDocuments(int maxDocuments) {
        this.maxDocuments = maxDocuments;
    }

    public boolean isSameDomain() {
        return sameDomain;
    }

    public void setSameDomain(boolean sameDomain) {
        this.sameDomain = sameDomain;
    }

    public boolean isRobots() {
        return robots;
    }

    public void setRobots(boolean robots) {
        this.robots = robots;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public int getTimeoutMin() {
        return timeoutMin;
    }

    public void setTimeoutMin(int timeoutMin) {
        this.timeoutMin = timeoutMin;
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public void setIncludePatterns(List<String> includePatterns) {
        this.includePatterns = includePatterns == null ? new ArrayList<>() : new ArrayList<>(includePatterns);
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = excludePatterns == null ? new ArrayList<>() : new ArrayList<>(excludePatterns);
    }

    public List<String> getContentTypes() {
        return contentTypes;
    }

    public void setContentTypes(List<String> contentTypes) {
        this.contentTypes = contentTypes == null ? new ArrayList<>() : new ArrayList<>(contentTypes);
    }

    public String getChunker() {
        return chunker;
    }

    public void setChunker(String chunker) {
        this.chunker = chunker;
    }

    public String getLoader() {
        return loader;
    }

    public void setLoader(String loader) {
        this.loader = loader;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public boolean isMultimodal() {
        return multimodal;
    }

    public void setMultimodal(boolean multimodal) {
        this.multimodal = multimodal;
    }

    public String getVlmModel() {
        return vlmModel;
    }

    public void setVlmModel(String vlmModel) {
        this.vlmModel = vlmModel;
    }

    public boolean isGraphExtraction() {
        return graphExtraction;
    }

    public void setGraphExtraction(boolean graphExtraction) {
        this.graphExtraction = graphExtraction;
    }

    public List<String> getGraphEntityTypes() {
        return graphEntityTypes;
    }

    public void setGraphEntityTypes(List<String> graphEntityTypes) {
        this.graphEntityTypes = graphEntityTypes == null ? new ArrayList<>() : new ArrayList<>(graphEntityTypes);
    }

    public List<String> getGraphRelationTypes() {
        return graphRelationTypes;
    }

    public void setGraphRelationTypes(List<String> graphRelationTypes) {
        this.graphRelationTypes = graphRelationTypes == null ? new ArrayList<>() : new ArrayList<>(graphRelationTypes);
    }

    public String getGraphModelProvider() {
        return graphModelProvider;
    }

    public void setGraphModelProvider(String graphModelProvider) {
        this.graphModelProvider = graphModelProvider;
    }

    public String getGraphModelName() {
        return graphModelName;
    }

    public void setGraphModelName(String graphModelName) {
        this.graphModelName = graphModelName;
    }

    public Double getGraphTemperature() {
        return graphTemperature;
    }

    public void setGraphTemperature(Double graphTemperature) {
        this.graphTemperature = graphTemperature;
    }

    public Double getGraphMinConfidence() {
        return graphMinConfidence;
    }

    public void setGraphMinConfidence(Double graphMinConfidence) {
        this.graphMinConfidence = graphMinConfidence;
    }

    public Boolean getGraphAutoAccept() {
        return graphAutoAccept;
    }

    public void setGraphAutoAccept(Boolean graphAutoAccept) {
        this.graphAutoAccept = graphAutoAccept;
    }

    public Double getGraphAutoAcceptThreshold() {
        return graphAutoAcceptThreshold;
    }

    public void setGraphAutoAcceptThreshold(Double graphAutoAcceptThreshold) {
        this.graphAutoAcceptThreshold = graphAutoAcceptThreshold;
    }

    public String getGraphSchemaMode() {
        return graphSchemaMode;
    }

    public void setGraphSchemaMode(String graphSchemaMode) {
        this.graphSchemaMode = graphSchemaMode;
    }

    public String getSchemaPresetId() {
        return schemaPresetId;
    }

    public void setSchemaPresetId(String schemaPresetId) {
        this.schemaPresetId = schemaPresetId;
    }

    public String getGraphCustomPrompt() {
        return graphCustomPrompt;
    }

    public void setGraphCustomPrompt(String graphCustomPrompt) {
        this.graphCustomPrompt = graphCustomPrompt;
    }

    public boolean isGraphLocal() {
        return graphLocal;
    }

    public void setGraphLocal(boolean graphLocal) {
        this.graphLocal = graphLocal;
    }

    public boolean isGraphAutoStart() {
        return graphAutoStart;
    }

    public void setGraphAutoStart(boolean graphAutoStart) {
        this.graphAutoStart = graphAutoStart;
    }

    public boolean isFollowLinks() {
        return followLinks;
    }

    public void setFollowLinks(boolean followLinks) {
        this.followLinks = followLinks;
    }

    public boolean isIncludeHidden() {
        return includeHidden;
    }

    public void setIncludeHidden(boolean includeHidden) {
        this.includeHidden = includeHidden;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public boolean isWatch() {
        return watch;
    }

    public void setWatch(boolean watch) {
        this.watch = watch;
    }

    public String getFactSheetName() {
        return factSheetName;
    }

    public void setFactSheetName(String factSheetName) {
        this.factSheetName = factSheetName;
    }

    public KompileProjectLifecycleState getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(KompileProjectLifecycleState lifecycle) {
        this.lifecycle = lifecycle == null ? KompileProjectLifecycleState.ACTIVE : lifecycle;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
