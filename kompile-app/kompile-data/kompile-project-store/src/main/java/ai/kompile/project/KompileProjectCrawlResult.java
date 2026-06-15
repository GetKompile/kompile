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
 * Summary of a completed crawl stored under {@code data/crawls/<id>/crawl-result.json}.
 * Used by the crawl catalog ({@code data/crawls/project-crawls.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getLoader() {
        return loader;
    }

    public void setLoader(String loader) {
        this.loader = loader;
    }

    public String getChunker() {
        return chunker;
    }

    public void setChunker(String chunker) {
        this.chunker = chunker;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getFactSheetName() {
        return factSheetName;
    }

    public void setFactSheetName(String factSheetName) {
        this.factSheetName = factSheetName;
    }

    public String getMarkdownPath() {
        return markdownPath;
    }

    public void setMarkdownPath(String markdownPath) {
        this.markdownPath = markdownPath;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(int documentCount) {
        this.documentCount = documentCount;
    }

    public int getMarkdownCount() {
        return markdownCount;
    }

    public void setMarkdownCount(int markdownCount) {
        this.markdownCount = markdownCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }
}
