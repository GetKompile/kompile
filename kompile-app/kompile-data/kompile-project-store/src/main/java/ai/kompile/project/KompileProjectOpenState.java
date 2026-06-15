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
import java.util.LinkedHashMap;
import java.util.Map;

public class KompileProjectOpenState {
    private int schemaVersion = 1;
    private String projectId;
    private String name;
    private String root;
    private String manifestPath;
    private KompileProjectLifecycleState lifecycle = KompileProjectLifecycleState.ACTIVE;
    private Instant openedAt;
    private Instant updatedAt;
    private int markdownCount;
    private int crawlResultCount;
    private int sourceDocumentCount;
    private int promptTemplateCount;
    private int factSheetCount;
    private int chatSessionCount;
    private int noteSyncConnectionCount;
    private int indexedDocumentCount;
    private Instant lastCrawlAt;
    private Map<String, String> metadata = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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

    public KompileProjectLifecycleState getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(KompileProjectLifecycleState lifecycle) {
        this.lifecycle = lifecycle == null ? KompileProjectLifecycleState.ACTIVE : lifecycle;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
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

    public Instant getLastCrawlAt() {
        return lastCrawlAt;
    }

    public void setLastCrawlAt(Instant lastCrawlAt) {
        this.lastCrawlAt = lastCrawlAt;
    }

    public int getSourceDocumentCount() { return sourceDocumentCount; }
    public void setSourceDocumentCount(int sourceDocumentCount) { this.sourceDocumentCount = sourceDocumentCount; }

    public int getPromptTemplateCount() { return promptTemplateCount; }
    public void setPromptTemplateCount(int promptTemplateCount) { this.promptTemplateCount = promptTemplateCount; }

    public int getFactSheetCount() { return factSheetCount; }
    public void setFactSheetCount(int factSheetCount) { this.factSheetCount = factSheetCount; }

    public int getChatSessionCount() { return chatSessionCount; }
    public void setChatSessionCount(int chatSessionCount) { this.chatSessionCount = chatSessionCount; }

    public int getNoteSyncConnectionCount() { return noteSyncConnectionCount; }
    public void setNoteSyncConnectionCount(int noteSyncConnectionCount) { this.noteSyncConnectionCount = noteSyncConnectionCount; }

    public int getIndexedDocumentCount() { return indexedDocumentCount; }
    public void setIndexedDocumentCount(int indexedDocumentCount) { this.indexedDocumentCount = indexedDocumentCount; }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
