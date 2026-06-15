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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.modelmanager.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A cached pipeline output entry.
 *
 * <p>Stores the serialized output from a pipeline stage or complete pipeline execution,
 * along with metadata for cache management (timestamps, hit counts, size).</p>
 *
 * <h2>Entry Types</h2>
 * <ul>
 *   <li><b>FINAL_OUTPUT</b> - Complete pipeline result, keyed by content hash + pipeline ID</li>
 *   <li><b>STAGE_CHECKPOINT</b> - Intermediate stage result for crash recovery</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineCacheEntry {

    /**
     * Type of cache entry.
     */
    public enum EntryType {
        /** Complete pipeline output */
        FINAL_OUTPUT,
        /** Intermediate stage checkpoint for crash recovery */
        STAGE_CHECKPOINT
    }

    private final String cacheKey;
    private final EntryType entryType;
    private final String pipelineId;
    private final String stageId;
    private final String contentHash;
    private final String serializedOutput;
    private final String outputClassName;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private final Instant lastAccessedAt;
    private final long hitCount;
    private final long sizeBytes;

    @JsonCreator
    public PipelineCacheEntry(
            @JsonProperty("cacheKey") String cacheKey,
            @JsonProperty("entryType") EntryType entryType,
            @JsonProperty("pipelineId") String pipelineId,
            @JsonProperty("stageId") String stageId,
            @JsonProperty("contentHash") String contentHash,
            @JsonProperty("serializedOutput") String serializedOutput,
            @JsonProperty("outputClassName") String outputClassName,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("lastAccessedAt") Instant lastAccessedAt,
            @JsonProperty("hitCount") long hitCount,
            @JsonProperty("sizeBytes") long sizeBytes) {
        this.cacheKey = cacheKey;
        this.entryType = entryType;
        this.pipelineId = pipelineId;
        this.stageId = stageId;
        this.contentHash = contentHash;
        this.serializedOutput = serializedOutput;
        this.outputClassName = outputClassName;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastAccessedAt = lastAccessedAt != null ? lastAccessedAt : Instant.now();
        this.hitCount = hitCount;
        this.sizeBytes = sizeBytes;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new entry recording a cache hit (incremented hit count, updated access time).
     */
    public PipelineCacheEntry withHit() {
        return new PipelineCacheEntry(
                cacheKey, entryType, pipelineId, stageId, contentHash,
                serializedOutput, outputClassName, metadata,
                createdAt, Instant.now(), hitCount + 1, sizeBytes);
    }

    // ========== Accessors ==========

    public String getCacheKey() { return cacheKey; }
    public EntryType getEntryType() { return entryType; }
    public String getPipelineId() { return pipelineId; }
    public String getStageId() { return stageId; }
    public String getContentHash() { return contentHash; }
    public String getSerializedOutput() { return serializedOutput; }
    public String getOutputClassName() { return outputClassName; }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public long getHitCount() { return hitCount; }
    public long getSizeBytes() { return sizeBytes; }

    public boolean isFinalOutput() { return entryType == EntryType.FINAL_OUTPUT; }
    public boolean isStageCheckpoint() { return entryType == EntryType.STAGE_CHECKPOINT; }

    // ========== Builder class ==========

    public static class Builder {
        private String cacheKey;
        private EntryType entryType = EntryType.FINAL_OUTPUT;
        private String pipelineId;
        private String stageId;
        private String contentHash;
        private String serializedOutput;
        private String outputClassName;
        private Map<String, Object> metadata = new HashMap<>();
        private long sizeBytes;

        public Builder cacheKey(String cacheKey) { this.cacheKey = cacheKey; return this; }
        public Builder entryType(EntryType entryType) { this.entryType = entryType; return this; }
        public Builder pipelineId(String pipelineId) { this.pipelineId = pipelineId; return this; }
        public Builder stageId(String stageId) { this.stageId = stageId; return this; }
        public Builder contentHash(String contentHash) { this.contentHash = contentHash; return this; }
        public Builder serializedOutput(String serializedOutput) { this.serializedOutput = serializedOutput; return this; }
        public Builder outputClassName(String outputClassName) { this.outputClassName = outputClassName; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>(); return this; }
        public Builder metadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder sizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; return this; }

        public PipelineCacheEntry build() {
            if (serializedOutput != null && sizeBytes == 0) {
                sizeBytes = serializedOutput.length();
            }
            return new PipelineCacheEntry(
                    cacheKey, entryType, pipelineId, stageId, contentHash,
                    serializedOutput, outputClassName, metadata,
                    Instant.now(), Instant.now(), 0, sizeBytes);
        }
    }

    @Override
    public String toString() {
        return "PipelineCacheEntry{" +
                "key=" + cacheKey +
                ", type=" + entryType +
                ", pipeline=" + pipelineId +
                (stageId != null ? ", stage=" + stageId : "") +
                ", hits=" + hitCount +
                ", size=" + sizeBytes + "B" +
                '}';
    }
}
