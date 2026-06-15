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

package ai.kompile.staging.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for model source selection.
 * Allows choosing between archive-based models, remote registry URLs, or both.
 *
 * <p>Model sources are checked in priority order:
 * <ol>
 *   <li>Embedded archive (if configured)</li>
 *   <li>Local archive file (if configured)</li>
 *   <li>Remote registry URLs (if configured)</li>
 *   <li>Default model downloads</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "kompile.models")
public class ModelSourceConfiguration {

    /**
     * The model source type to use.
     * Options: ARCHIVE, REGISTRY, HYBRID (try archive first, then registry)
     */
    private SourceType sourceType = SourceType.HYBRID;

    /**
     * Path to a local .karch archive file.
     * If set, models will be loaded from this archive.
     */
    private String archivePath;

    /**
     * Classpath location of an embedded .karch archive.
     * Use this to bundle models directly in the application JAR.
     * Example: "classpath:models/default-models.karch"
     */
    private String embeddedArchive;

    /**
     * Whether to auto-extract the embedded archive on startup.
     */
    private boolean autoExtractEmbedded = true;

    /**
     * Remote model registry URLs.
     * These are used to download models not found in the local registry/archive.
     */
    private List<String> registryUrls;

    /**
     * Whether to cache downloaded models locally.
     */
    private boolean cacheDownloads = true;

    /**
     * Local directory for model cache.
     * Defaults to ~/.kompile/models
     */
    private String cacheDir;

    /**
     * Whether to verify checksums when loading models.
     */
    private boolean verifyChecksums = true;

    /**
     * Whether to allow fallback to default model downloads if archive/registry fails.
     */
    private boolean allowFallback = true;

    /**
     * Specific model IDs to load from the archive (empty means all).
     */
    private List<String> selectedModels;

    /**
     * Model source type enumeration.
     */
    public enum SourceType {
        /**
         * Use only archive-based models.
         * No remote downloads allowed.
         */
        ARCHIVE,

        /**
         * Use only remote registry for model downloads.
         */
        REGISTRY,

        /**
         * Try archive first, fall back to registry.
         */
        HYBRID
    }

    /**
     * Check if archive source is configured.
     */
    public boolean hasArchiveSource() {
        return (archivePath != null && !archivePath.isBlank()) ||
               (embeddedArchive != null && !embeddedArchive.isBlank());
    }

    /**
     * Check if registry source is configured.
     */
    public boolean hasRegistrySource() {
        return registryUrls != null && !registryUrls.isEmpty();
    }

    /**
     * Check if this is an archive-only configuration (air-gapped mode).
     */
    public boolean isArchiveOnly() {
        return sourceType == SourceType.ARCHIVE && hasArchiveSource();
    }

    /**
     * Get the effective cache directory.
     */
    public String getEffectiveCacheDir() {
        if (cacheDir != null && !cacheDir.isBlank()) {
            return cacheDir;
        }
        return System.getProperty("user.home") + "/.kompile/models";
    }
}
