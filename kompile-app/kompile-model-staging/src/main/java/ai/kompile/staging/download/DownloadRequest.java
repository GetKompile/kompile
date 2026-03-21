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

package ai.kompile.staging.download;

import ai.kompile.modelmanager.registry.ModelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request for downloading a model from an external source.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRequest {

    /**
     * Source type: "huggingface", "github", "http".
     */
    private String source;

    /**
     * Repository or URL for the model.
     * For HuggingFace: "BAAI/bge-base-en-v1.5"
     * For GitHub: "owner/repo/release/tag"
     * For HTTP: direct URL
     */
    private String repository;

    /**
     * Original format of the model: "onnx", "tensorflow", "keras".
     */
    @Builder.Default
    private String format = "onnx";

    /**
     * Model type: encoder, cross_encoder, reranker.
     */
    private ModelType modelType;

    /**
     * Model ID to use in the registry.
     */
    private String modelId;

    /**
     * Specific files to download (path within repository).
     */
    @Builder.Default
    private Map<String, String> files = new HashMap<>();

    /**
     * Optional revision/branch/tag.
     */
    private String revision;

    /**
     * Optional authentication token.
     */
    private String authToken;

    /**
     * Expected SHA256 checksum for verification.
     */
    private String expectedChecksum;

    /**
     * Add a file to download.
     */
    public DownloadRequest addFile(String key, String path) {
        files.put(key, path);
        return this;
    }

    /**
     * Create a HuggingFace download request.
     */
    public static DownloadRequest huggingFace(String repo, String modelId, ModelType type) {
        return DownloadRequest.builder()
                .source("huggingface")
                .repository(repo)
                .modelId(modelId)
                .modelType(type)
                .format("onnx")
                .build();
    }

    /**
     * Create a GitHub download request.
     */
    public static DownloadRequest github(String repo, String tag, String modelId, ModelType type) {
        return DownloadRequest.builder()
                .source("github")
                .repository(repo)
                .revision(tag)
                .modelId(modelId)
                .modelType(type)
                .build();
    }

    /**
     * Create an HTTP download request.
     */
    public static DownloadRequest http(String url, String modelId, ModelType type) {
        return DownloadRequest.builder()
                .source("http")
                .repository(url)
                .modelId(modelId)
                .modelType(type)
                .build();
    }
}
