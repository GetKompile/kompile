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

package ai.kompile.staging.archive;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable representation of a Kompile Archive (.karch file).
 * Provides access to the archive manifest and metadata.
 */
@Getter
@Builder
public class KompileArchive {

    /**
     * File extension for Kompile archives.
     */
    public static final String EXTENSION = ".karch";

    /**
     * Manifest filename within the archive.
     */
    public static final String MANIFEST_FILENAME = "manifest.karch.json";

    /**
     * Checksums filename within the archive.
     */
    public static final String CHECKSUMS_FILENAME = "checksums.sha256";

    /**
     * Path to the archive file.
     */
    private final Path archivePath;

    /**
     * Archive manifest containing metadata and model information.
     */
    private final ArchiveManifest manifest;

    /**
     * Size of the archive file in bytes.
     */
    private final long fileSizeBytes;

    /**
     * SHA256 checksum of the archive file itself.
     */
    private final String archiveChecksum;

    /**
     * Source URL if the archive was downloaded.
     */
    private final String sourceUrl;

    /**
     * Whether the archive has been verified (checksums validated).
     */
    private final boolean verified;

    /**
     * Returns the archive ID.
     */
    public String getArchiveId() {
        return manifest != null ? manifest.getArchiveId() : null;
    }

    /**
     * Returns the content version.
     */
    public String getContentVersion() {
        return manifest != null ? manifest.getContentVersion() : null;
    }

    /**
     * Returns the parsed content version.
     */
    public ArchiveVersion getParsedVersion() {
        return manifest != null ? manifest.getParsedContentVersion() : null;
    }

    /**
     * Returns the list of models in the archive.
     */
    public List<ArchiveModelEntry> getModels() {
        return manifest != null ? manifest.getModels() : List.of();
    }

    /**
     * Returns the number of models in the archive.
     */
    public int getModelCount() {
        return manifest != null ? manifest.getModelCount() : 0;
    }

    /**
     * Returns the checksums map.
     */
    public Map<String, String> getChecksums() {
        return manifest != null ? manifest.getChecksums() : Map.of();
    }

    /**
     * Finds a model by ID.
     */
    public Optional<ArchiveModelEntry> findModel(String modelId) {
        return manifest != null ? manifest.findModel(modelId) : Optional.empty();
    }

    /**
     * Returns true if this archive contains a specific model.
     */
    public boolean containsModel(String modelId) {
        return findModel(modelId).isPresent();
    }

    /**
     * Returns the archive filename.
     */
    public String getFilename() {
        return archivePath != null ? archivePath.getFileName().toString() : null;
    }

    /**
     * Returns true if this is a valid .karch file.
     */
    public boolean isValid() {
        return manifest != null && manifest.getArchiveId() != null;
    }

    /**
     * Returns true if this archive is compatible with the given Kompile version.
     */
    public boolean isCompatible(String kompileVersion) {
        return manifest == null || manifest.isCompatible(kompileVersion);
    }

    /**
     * Returns true if this archive is newer than another archive.
     */
    public boolean isNewerThan(KompileArchive other) {
        if (other == null) {
            return true;
        }
        return manifest != null && manifest.isNewerThan(other.getManifest());
    }

    /**
     * Returns a short summary of the archive.
     */
    public String toSummary() {
        if (manifest != null) {
            return manifest.toSummary();
        }
        return archivePath != null ? archivePath.toString() : "unknown archive";
    }

    /**
     * Generates a standard archive filename from archive ID and version.
     */
    public static String generateFilename(String archiveId, String version) {
        return archiveId + "-" + version + EXTENSION;
    }

    /**
     * Generates a standard archive filename from the manifest.
     */
    public static String generateFilename(ArchiveManifest manifest) {
        return generateFilename(manifest.getArchiveId(), manifest.getContentVersion());
    }

    /**
     * Checks if a filename has the .karch extension.
     */
    public static boolean isKarchFile(String filename) {
        return filename != null && filename.toLowerCase().endsWith(EXTENSION);
    }

    /**
     * Checks if a path points to a .karch file.
     */
    public static boolean isKarchFile(Path path) {
        return path != null && isKarchFile(path.getFileName().toString());
    }

    /**
     * Builder helper for creating an archive from just a manifest.
     */
    public static KompileArchive fromManifest(ArchiveManifest manifest) {
        return KompileArchive.builder()
                .manifest(manifest)
                .build();
    }

    /**
     * Builder helper for creating an archive from a path and manifest.
     */
    public static KompileArchive fromPath(Path path, ArchiveManifest manifest) {
        return KompileArchive.builder()
                .archivePath(path)
                .manifest(manifest)
                .build();
    }

    @Override
    public String toString() {
        return "KompileArchive{" +
                "id=" + getArchiveId() +
                ", version=" + getContentVersion() +
                ", models=" + getModelCount() +
                ", path=" + archivePath +
                '}';
    }
}
