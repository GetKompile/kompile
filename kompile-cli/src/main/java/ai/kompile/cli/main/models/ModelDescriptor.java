package ai.kompile.cli.main.models;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a downloadable model artifact, its location, and metadata.
 */
public class ModelDescriptor {
    private final String modelId;
    private final ModelType modelType;
    private final String downloadUrl;
    private final String expectedCacheSubpath; // Relative to the base model cache directory
    private final String version;
    private final String checksum; // Optional, for integrity check (e.g., SHA256)
    private final Map<String, Object> metadata;

    public ModelDescriptor(String modelId, ModelType modelType, String downloadUrl, String expectedCacheSubpath,
                           String version, String checksum, Map<String, Object> metadata) {
        this.modelId = Objects.requireNonNull(modelId, "modelId cannot be null");
        this.modelType = Objects.requireNonNull(modelType, "modelType cannot be null");
        this.downloadUrl = Objects.requireNonNull(downloadUrl, "downloadUrl cannot be null");
        this.expectedCacheSubpath = Objects.requireNonNull(expectedCacheSubpath, "expectedCacheSubpath cannot be null");
        this.version = version;
        this.checksum = checksum;
        this.metadata = metadata == null ? Collections.emptyMap() : metadata;
    }

    public String getModelId() {
        return modelId;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getExpectedCacheSubpath() {
        return expectedCacheSubpath;
    }

    public String getVersion() {
        return version;
    }

    public String getChecksum() {
        return checksum;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getMetadataString(String key) {
        return metadata.get(key) != null ? metadata.get(key).toString() : null;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelDescriptor that = (ModelDescriptor) o;
        return modelId.equals(that.modelId) &&
                modelType == that.modelType &&
                Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, modelType, version);
    }

    @Override
    public String toString() {
        return "ModelDescriptor{" +
                "modelId='" + modelId + '\'' +
                ", modelType=" + modelType +
                ", downloadUrl='" + downloadUrl + '\'' +
                ", expectedCacheSubpath='" + expectedCacheSubpath + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}