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

public class KompileProjectStatus {
    private String root;
    private String manifestPath;
    private boolean manifestPresent;
    private String metadataPath;
    private boolean metadataPresent;
    private String openStatePath;
    private boolean open;
    private String openProjectId;
    private Instant openedAt;
    private Instant openStateUpdatedAt;
    private boolean gitRepository;
    private boolean gitDirty;
    private String branch;
    private String remoteUrl;
    private boolean gitXetAvailable;
    private boolean gitXetEnabled;
    private int componentCount;
    private int codingProjectCount;
    private int modelCount;
    private int pipelineCount;
    private int scriptCount;
    private int crawlProfileCount;
    private int workflowCount;
    private int markdownCount;
    private int crawlResultCount;
    private int sourceDocumentCount;
    private int promptTemplateCount;
    private int factSheetCount;
    private int chatSessionCount;
    private int noteSyncConnectionCount;
    private int indexedDocumentCount;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getManifestPath() {
        return manifestPath;
    }

    public void setManifestPath(String manifestPath) {
        this.manifestPath = manifestPath;
    }

    public boolean isManifestPresent() {
        return manifestPresent;
    }

    public void setManifestPresent(boolean manifestPresent) {
        this.manifestPresent = manifestPresent;
    }

    public String getMetadataPath() {
        return metadataPath;
    }

    public void setMetadataPath(String metadataPath) {
        this.metadataPath = metadataPath;
    }

    public boolean isMetadataPresent() {
        return metadataPresent;
    }

    public void setMetadataPresent(boolean metadataPresent) {
        this.metadataPresent = metadataPresent;
    }

    public String getOpenStatePath() {
        return openStatePath;
    }

    public void setOpenStatePath(String openStatePath) {
        this.openStatePath = openStatePath;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public String getOpenProjectId() {
        return openProjectId;
    }

    public void setOpenProjectId(String openProjectId) {
        this.openProjectId = openProjectId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getOpenStateUpdatedAt() {
        return openStateUpdatedAt;
    }

    public void setOpenStateUpdatedAt(Instant openStateUpdatedAt) {
        this.openStateUpdatedAt = openStateUpdatedAt;
    }

    public boolean isGitRepository() {
        return gitRepository;
    }

    public void setGitRepository(boolean gitRepository) {
        this.gitRepository = gitRepository;
    }

    public boolean isGitDirty() {
        return gitDirty;
    }

    public void setGitDirty(boolean gitDirty) {
        this.gitDirty = gitDirty;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public boolean isGitXetAvailable() {
        return gitXetAvailable;
    }

    public void setGitXetAvailable(boolean gitXetAvailable) {
        this.gitXetAvailable = gitXetAvailable;
    }

    public boolean isGitXetEnabled() {
        return gitXetEnabled;
    }

    public void setGitXetEnabled(boolean gitXetEnabled) {
        this.gitXetEnabled = gitXetEnabled;
    }

    public int getComponentCount() {
        return componentCount;
    }

    public void setComponentCount(int componentCount) {
        this.componentCount = componentCount;
    }

    public int getCodingProjectCount() {
        return codingProjectCount;
    }

    public void setCodingProjectCount(int codingProjectCount) {
        this.codingProjectCount = codingProjectCount;
    }

    public int getModelCount() {
        return modelCount;
    }

    public void setModelCount(int modelCount) {
        this.modelCount = modelCount;
    }

    public int getPipelineCount() {
        return pipelineCount;
    }

    public void setPipelineCount(int pipelineCount) {
        this.pipelineCount = pipelineCount;
    }

    public int getScriptCount() {
        return scriptCount;
    }

    public void setScriptCount(int scriptCount) {
        this.scriptCount = scriptCount;
    }

    public int getCrawlProfileCount() {
        return crawlProfileCount;
    }

    public void setCrawlProfileCount(int crawlProfileCount) {
        this.crawlProfileCount = crawlProfileCount;
    }

    public int getWorkflowCount() {
        return workflowCount;
    }

    public void setWorkflowCount(int workflowCount) {
        this.workflowCount = workflowCount;
    }

    public int getMarkdownCount() {
        return markdownCount;
    }

    public void setMarkdownCount(int markdownCount) {
        this.markdownCount = markdownCount;
    }

    public int getCrawlResultCount() {
        return crawlResultCount;
    }

    public void setCrawlResultCount(int crawlResultCount) {
        this.crawlResultCount = crawlResultCount;
    }

    public int getSourceDocumentCount() {
        return sourceDocumentCount;
    }

    public void setSourceDocumentCount(int sourceDocumentCount) {
        this.sourceDocumentCount = sourceDocumentCount;
    }

    public int getPromptTemplateCount() {
        return promptTemplateCount;
    }

    public void setPromptTemplateCount(int promptTemplateCount) {
        this.promptTemplateCount = promptTemplateCount;
    }

    public int getFactSheetCount() {
        return factSheetCount;
    }

    public void setFactSheetCount(int factSheetCount) {
        this.factSheetCount = factSheetCount;
    }

    public int getChatSessionCount() {
        return chatSessionCount;
    }

    public void setChatSessionCount(int chatSessionCount) {
        this.chatSessionCount = chatSessionCount;
    }

    public int getNoteSyncConnectionCount() {
        return noteSyncConnectionCount;
    }

    public void setNoteSyncConnectionCount(int noteSyncConnectionCount) {
        this.noteSyncConnectionCount = noteSyncConnectionCount;
    }

    public int getIndexedDocumentCount() { return indexedDocumentCount; }
    public void setIndexedDocumentCount(int indexedDocumentCount) { this.indexedDocumentCount = indexedDocumentCount; }
}
