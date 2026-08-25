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

import ai.kompile.modelmanager.registry.AudioSynthesisConfig;
import ai.kompile.modelmanager.registry.ModelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * Source type: "huggingface", "https-components", "github", "http".
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
     * Typed serving ABI copied into the registry when an audio model is promoted.
     */
    private AudioSynthesisConfig audioSynthesis;

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
     * Typed SDX text-model asset declaration. This is authoritative when present;
     * {@link #files} remains available for non-text and legacy callers.
     */
    private TextModelAssetMap textAssets;

    /**
     * Explicit public HTTPS locations for individual text-model assets. These
     * either override repository discovery or form the complete input for the
     * repository-free {@code https-components} source while preserving the same
     * canonical SDZ staging path.
     */
    private TextModelAssetUrlMap textAssetUrls;

    /**
     * Optional revision/branch/tag. Hugging Face staging replaces this with the
     * immutable commit SHA before any model asset is downloaded.
     */
    private String revision;

    /**
     * Original branch/tag requested before Hugging Face commit resolution.
     */
    private String requestedRevision;

    /**
     * Canonical, credential-free source reference retained for project provenance.
     */
    private String sourceReference;

    /**
     * Credential-free resolved asset provenance keyed by TextModelAssetMap keys.
     */
    @Builder.Default
    private Map<String, String> sourceAssetProvenance = new LinkedHashMap<>();

    /**
     * Optional authentication token.
     */
    private String authToken;

    /**
     * Expected SHA256 checksum for verification.
     */
    private String expectedChecksum;

    /** Per-component SHA-256 checksums keyed by the stable asset key. */
    @Builder.Default
    private Map<String, String> expectedChecksums = new LinkedHashMap<>();

    /** Per-component exact byte sizes keyed by the stable asset key. */
    @Builder.Default
    private Map<String, Long> expectedSizes = new LinkedHashMap<>();

    /**
     * Optional URL to download a tokenizer.json for models (e.g. GGUF) that
     * don't bundle a HuggingFace-format tokenizer file.
     */
    private String tokenizerUrl;

    /**
     * Requested staged output: "model" for the legacy verified model directory,
     * or "kproject" for an offline graph-chat project.
     */
    @Builder.Default
    private String outputFormat = "model";

    /**
     * Exact {@code SdxTargetProfile.id()} used for target compilation.
     */
    private String targetProfile;

    /**
     * SDX quantization intent: "none" or target-optimized "int8".
     */
    @Builder.Default
    private String quantizationProfile = "none";

    /**
     * Exact target SoC used by the device-only compiler contract.
     */
    private String targetSoc;

    /**
     * Add a file to download.
     */
    public DownloadRequest addFile(String key, String path) {
        files.put(key, path);
        return this;
    }

    /**
     * Resolve the typed text assets over the legacy generic map.
     */
    public TextModelAssetMap effectiveTextAssets() {
        return textAssets != null ? textAssets : TextModelAssetMap.fromFileMap(files);
    }

    /**
     * Resolve all requested files while giving canonical text assets precedence.
     */
    public Map<String, String> effectiveFiles() {
        Map<String, String> effective = new LinkedHashMap<>();
        if (files != null) {
            effective.putAll(files);
        }
        if (textAssets != null) {
            effective.putAll(textAssets.toFileMap());
        }
        return effective;
    }

    /**
     * Return credential-free asset provenance, including for callers that
     * deserialize an explicit null instead of using the Lombok builder default.
     */
    public Map<String, String> effectiveSourceAssetProvenance() {
        return sourceAssetProvenance == null ? Map.of() : sourceAssetProvenance;
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
