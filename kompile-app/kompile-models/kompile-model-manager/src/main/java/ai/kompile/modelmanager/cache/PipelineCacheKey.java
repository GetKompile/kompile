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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Content-addressable cache key for pipeline outputs.
 *
 * <p>Keys are computed from the SHA-256 hash of the input content combined
 * with pipeline configuration, ensuring that identical inputs with the same
 * pipeline produce the same cache key (deduplication), while any change
 * to input or configuration produces a different key (correctness).</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li><b>contentHash</b> - SHA-256 of the input content (e.g., image bytes)</li>
 *   <li><b>pipelineId</b> - The pipeline configuration used for processing</li>
 *   <li><b>stageId</b> - Optional stage identifier for intermediate result caching</li>
 *   <li><b>parameters</b> - Pipeline/stage parameters that affect output</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Final output cache key
 * PipelineCacheKey key = PipelineCacheKey.forPipelineOutput(
 *     imageBytes, "scanned-documents");
 *
 * // Intermediate stage cache key
 * PipelineCacheKey stageKey = PipelineCacheKey.forStageOutput(
 *     imageBytes, "scanned-documents", "VISION_ENCODING", params);
 * }</pre>
 */
public final class PipelineCacheKey {

    private final String contentHash;
    private final String pipelineId;
    private final String stageId;
    private final String parametersHash;
    private final String compositeKey;

    private PipelineCacheKey(String contentHash, String pipelineId, String stageId,
                             String parametersHash) {
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.pipelineId = Objects.requireNonNull(pipelineId, "pipelineId");
        this.stageId = stageId; // null for final output
        this.parametersHash = parametersHash != null ? parametersHash : "";
        this.compositeKey = computeCompositeKey();
    }

    /**
     * Create a cache key for a complete pipeline output.
     *
     * @param inputBytes raw input content bytes
     * @param pipelineId pipeline identifier
     * @return cache key
     */
    public static PipelineCacheKey forPipelineOutput(byte[] inputBytes, String pipelineId) {
        return new PipelineCacheKey(sha256(inputBytes), pipelineId, null, null);
    }

    /**
     * Create a cache key for a complete pipeline output with parameters.
     *
     * @param inputBytes raw input content bytes
     * @param pipelineId pipeline identifier
     * @param parameters pipeline parameters that affect output
     * @return cache key
     */
    public static PipelineCacheKey forPipelineOutput(byte[] inputBytes, String pipelineId,
                                                      Map<String, Object> parameters) {
        return new PipelineCacheKey(
                sha256(inputBytes), pipelineId, null, hashParameters(parameters));
    }

    /**
     * Create a cache key for an intermediate stage output.
     *
     * @param inputBytes raw input content bytes
     * @param pipelineId pipeline identifier
     * @param stageId    stage identifier
     * @param parameters stage parameters
     * @return cache key
     */
    public static PipelineCacheKey forStageOutput(byte[] inputBytes, String pipelineId,
                                                   String stageId, Map<String, Object> parameters) {
        return new PipelineCacheKey(
                sha256(inputBytes), pipelineId,
                Objects.requireNonNull(stageId, "stageId"),
                hashParameters(parameters));
    }

    /**
     * Create a cache key from a pre-computed content hash (e.g., from source_checksum metadata).
     *
     * @param contentHash pre-computed SHA-256 hex string
     * @param pipelineId  pipeline identifier
     * @return cache key
     */
    public static PipelineCacheKey fromContentHash(String contentHash, String pipelineId) {
        return new PipelineCacheKey(contentHash, pipelineId, null, null);
    }

    /**
     * Create a cache key from a pre-computed content hash with a stage.
     *
     * @param contentHash pre-computed SHA-256 hex string
     * @param pipelineId  pipeline identifier
     * @param stageId     stage identifier
     * @param parameters  stage parameters
     * @return cache key
     */
    public static PipelineCacheKey fromContentHash(String contentHash, String pipelineId,
                                                    String stageId, Map<String, Object> parameters) {
        return new PipelineCacheKey(contentHash, pipelineId, stageId, hashParameters(parameters));
    }

    // ========== Accessors ==========

    public String getContentHash() {
        return contentHash;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getStageId() {
        return stageId;
    }

    public boolean isStageKey() {
        return stageId != null;
    }

    public boolean isFinalOutputKey() {
        return stageId == null;
    }

    /**
     * Returns the composite key string used for storage lookups.
     * Format: {@code contentHash:pipelineId[:stageId][:paramsHash]}
     */
    public String toKeyString() {
        return compositeKey;
    }

    /**
     * Returns a filesystem-safe version of the key for file-based caches.
     */
    public String toFileSystemKey() {
        return compositeKey.replace(':', '_');
    }

    // ========== Hashing Utilities ==========

    /**
     * Compute SHA-256 hash of byte array.
     */
    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Compute SHA-256 hash of a string.
     */
    public static String sha256(String data) {
        return sha256(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        // Sort keys for deterministic hashing
        TreeMap<String, Object> sorted = new TreeMap<>(parameters);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=');
            sb.append(entry.getValue() != null ? entry.getValue().toString() : "null");
            sb.append(';');
        }
        return sha256(sb.toString()).substring(0, 16); // Short hash for params
    }

    private String computeCompositeKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(contentHash).append(':').append(pipelineId);
        if (stageId != null) {
            sb.append(':').append(stageId);
        }
        if (!parametersHash.isEmpty()) {
            sb.append(':').append(parametersHash);
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ========== Object methods ==========

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineCacheKey that)) return false;
        return compositeKey.equals(that.compositeKey);
    }

    @Override
    public int hashCode() {
        return compositeKey.hashCode();
    }

    @Override
    public String toString() {
        return "PipelineCacheKey{" + compositeKey + '}';
    }
}
