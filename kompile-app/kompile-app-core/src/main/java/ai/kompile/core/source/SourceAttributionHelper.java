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

package ai.kompile.core.source;

import ai.kompile.core.loaders.DocumentSourceDescriptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Helper class for managing source attribution metadata.
 *
 * This class provides utility methods to:
 * - Create source metadata from DocumentSourceDescriptor
 * - Merge storage results into existing metadata
 * - Extract source information from metadata maps
 * - Ensure source attribution is preserved during document transformations
 */
public final class SourceAttributionHelper {

    private SourceAttributionHelper() {
        // Prevent instantiation
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA CREATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates source metadata from a DocumentSourceDescriptor.
     *
     * @param descriptor The source descriptor
     * @return Map of source metadata
     */
    public static Map<String, Object> createSourceMetadata(DocumentSourceDescriptor descriptor) {
        Map<String, Object> metadata = new HashMap<>();

        if (descriptor == null) {
            return metadata;
        }

        // Source type
        if (descriptor.getType() != null) {
            metadata.put(SourceMetadataConstants.SOURCE_TYPE, descriptor.getType().name());
        }

        // Path or URL based on type
        // For URL types, check if a source_url was explicitly provided in descriptor metadata
        // This handles the case where the content was downloaded and pathOrUrl points to local file
        if (descriptor.getType() == DocumentSourceDescriptor.SourceType.URL) {
            // First check if descriptor's metadata has an explicit source_url
            String explicitUrl = null;
            if (descriptor.getMetadata() != null && descriptor.getMetadata().containsKey(SourceMetadataConstants.SOURCE_URL)) {
                Object urlValue = descriptor.getMetadata().get(SourceMetadataConstants.SOURCE_URL);
                if (urlValue != null) {
                    explicitUrl = urlValue.toString();
                }
            }

            if (explicitUrl != null && !explicitUrl.isEmpty()) {
                // Use the explicitly provided URL
                metadata.put(SourceMetadataConstants.SOURCE_URL, explicitUrl);
                // Also store the local path if available
                if (descriptor.getPathOrUrl() != null) {
                    metadata.put(SourceMetadataConstants.SOURCE_PATH, descriptor.getPathOrUrl());
                }
            } else if (descriptor.getPathOrUrl() != null) {
                // Fall back to pathOrUrl as the URL
                metadata.put(SourceMetadataConstants.SOURCE_URL, descriptor.getPathOrUrl());
            }
        } else if (descriptor.getPathOrUrl() != null) {
            metadata.put(SourceMetadataConstants.SOURCE_PATH, descriptor.getPathOrUrl());
        }

        // Filename
        if (descriptor.getOriginalFileName() != null) {
            metadata.put(SourceMetadataConstants.SOURCE_FILENAME, descriptor.getOriginalFileName());

            // Extract extension
            String ext = extractExtension(descriptor.getOriginalFileName());
            if (ext != null && !ext.isEmpty()) {
                metadata.put(SourceMetadataConstants.FILE_EXTENSION, ext);
            }
        }

        // Source ID
        if (descriptor.getSourceId() != null) {
            metadata.put(SourceMetadataConstants.SOURCE_ID, descriptor.getSourceId());
        } else if (descriptor.getPathOrUrl() != null) {
            // Generate source ID from path/URL if not provided
            metadata.put(SourceMetadataConstants.SOURCE_ID, descriptor.getPathOrUrl());
        }

        // Collection name
        if (descriptor.getCollectionName() != null) {
            metadata.put(SourceMetadataConstants.COLLECTION_NAME, descriptor.getCollectionName());
        }

        // Merge any additional metadata from descriptor
        if (descriptor.getMetadata() != null) {
            // Don't overwrite our standardized keys
            for (Map.Entry<String, Object> entry : descriptor.getMetadata().entrySet()) {
                if (!metadata.containsKey(entry.getKey())) {
                    metadata.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Add indexing timestamp
        metadata.put(SourceMetadataConstants.INDEXED_AT, Instant.now().toString());

        return metadata;
    }

    /**
     * Adds storage information to existing metadata.
     *
     * @param metadata      Existing metadata map (will be modified)
     * @param storageResult Result from SourceDocumentStorageService
     */
    public static void addStorageMetadata(Map<String, Object> metadata,
                                          SourceDocumentStorageService.StorageResult storageResult) {
        if (metadata == null || storageResult == null) {
            return;
        }

        if (storageResult.storedPath() != null) {
            metadata.put(SourceMetadataConstants.STORED_COPY_PATH, storageResult.getStoredPathString());
        }

        if (storageResult.checksum() != null) {
            metadata.put(SourceMetadataConstants.SOURCE_CHECKSUM, storageResult.checksum());
        }

        metadata.put(SourceMetadataConstants.SOURCE_SIZE_BYTES, storageResult.sizeBytes());
        metadata.put(SourceMetadataConstants.STORED_AT, storageResult.getStoredAt().toString());
    }

    /**
     * Adds chunk information to metadata.
     *
     * @param metadata    Existing metadata map (will be modified)
     * @param chunkIndex  Index of this chunk (0-indexed)
     * @param totalChunks Total number of chunks
     * @param charStart   Character offset start (optional, use -1 to skip)
     * @param charEnd     Character offset end (optional, use -1 to skip)
     */
    public static void addChunkMetadata(Map<String, Object> metadata,
                                        int chunkIndex, int totalChunks,
                                        int charStart, int charEnd) {
        if (metadata == null) {
            return;
        }

        metadata.put(SourceMetadataConstants.CHUNK_INDEX, chunkIndex);
        metadata.put(SourceMetadataConstants.TOTAL_CHUNKS, totalChunks);

        if (charStart >= 0) {
            metadata.put(SourceMetadataConstants.CHAR_OFFSET_START, charStart);
        }
        if (charEnd >= 0) {
            metadata.put(SourceMetadataConstants.CHAR_OFFSET_END, charEnd);
        }
    }

    /**
     * Adds page information to metadata.
     *
     * @param metadata   Existing metadata map (will be modified)
     * @param pageNumber Page number (1-indexed)
     * @param totalPages Total number of pages
     */
    public static void addPageMetadata(Map<String, Object> metadata, int pageNumber, int totalPages) {
        if (metadata == null) {
            return;
        }

        if (pageNumber > 0) {
            metadata.put(SourceMetadataConstants.PAGE_NUMBER, pageNumber);
        }
        if (totalPages > 0) {
            metadata.put(SourceMetadataConstants.TOTAL_PAGES, totalPages);
        }
    }

    /**
     * Adds loader information to metadata.
     *
     * @param metadata   Existing metadata map (will be modified)
     * @param loaderName Name of the loader
     */
    public static void addLoaderMetadata(Map<String, Object> metadata, String loaderName) {
        if (metadata == null || loaderName == null) {
            return;
        }
        metadata.put(SourceMetadataConstants.LOADER_NAME, loaderName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts the source path from metadata.
     */
    public static Optional<String> getSourcePath(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.SOURCE_PATH);
    }

    /**
     * Extracts the source URL from metadata.
     */
    public static Optional<String> getSourceUrl(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.SOURCE_URL);
    }

    /**
     * Extracts the source ID from metadata.
     */
    public static Optional<String> getSourceId(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.SOURCE_ID);
    }

    /**
     * Extracts the stored copy path from metadata.
     */
    public static Optional<Path> getStoredCopyPath(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.STORED_COPY_PATH)
                .map(Paths::get);
    }

    /**
     * Extracts the checksum from metadata.
     */
    public static Optional<String> getChecksum(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.SOURCE_CHECKSUM);
    }

    /**
     * Extracts the filename from metadata.
     */
    public static Optional<String> getFilename(Map<String, Object> metadata) {
        return getStringValue(metadata, SourceMetadataConstants.SOURCE_FILENAME);
    }

    /**
     * Gets the source location (prefers source_path, falls back to source_url).
     */
    public static Optional<String> getSourceLocation(Map<String, Object> metadata) {
        Optional<String> path = getSourcePath(metadata);
        if (path.isPresent()) {
            return path;
        }
        return getSourceUrl(metadata);
    }

    /**
     * Gets the best available reference for citing the source.
     * Priority: stored_copy_path > source_path > source_url > source_id
     */
    public static Optional<String> getCitationReference(Map<String, Object> metadata) {
        Optional<String> stored = getStringValue(metadata, SourceMetadataConstants.STORED_COPY_PATH);
        if (stored.isPresent()) {
            return stored;
        }

        Optional<String> path = getSourcePath(metadata);
        if (path.isPresent()) {
            return path;
        }

        Optional<String> url = getSourceUrl(metadata);
        if (url.isPresent()) {
            return url;
        }

        return getSourceId(metadata);
    }

    /**
     * Gets page number from metadata.
     */
    public static Optional<Integer> getPageNumber(Map<String, Object> metadata) {
        return getIntValue(metadata, SourceMetadataConstants.PAGE_NUMBER);
    }

    /**
     * Gets chunk index from metadata.
     */
    public static Optional<Integer> getChunkIndex(Map<String, Object> metadata) {
        return getIntValue(metadata, SourceMetadataConstants.CHUNK_INDEX);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA PRESERVATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copies core source metadata from source to target.
     * This ensures source attribution is preserved during document transformations.
     *
     * @param source Source metadata map
     * @param target Target metadata map (will be modified)
     */
    public static void preserveSourceMetadata(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }

        // Copy all core source keys
        for (String key : SourceMetadataConstants.CORE_SOURCE_KEYS) {
            if (source.containsKey(key) && !target.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    /**
     * Merges source metadata from multiple sources, preferring earlier values.
     *
     * @param sources Metadata maps in priority order (first has highest priority)
     * @return Merged metadata map
     */
    public static Map<String, Object> mergeSourceMetadata(Map<String, Object>... sources) {
        Map<String, Object> result = new HashMap<>();

        // Process in reverse order so higher priority sources override
        for (int i = sources.length - 1; i >= 0; i--) {
            if (sources[i] != null) {
                result.putAll(sources[i]);
            }
        }

        return result;
    }

    /**
     * Checks if metadata contains valid source attribution.
     *
     * @param metadata Metadata to check
     * @return true if at least source_id or source_path/source_url is present
     */
    public static boolean hasSourceAttribution(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        return metadata.containsKey(SourceMetadataConstants.SOURCE_ID) ||
                metadata.containsKey(SourceMetadataConstants.SOURCE_PATH) ||
                metadata.containsKey(SourceMetadataConstants.SOURCE_URL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private static Optional<String> getStringValue(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return Optional.empty();
        }
        Object value = metadata.get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    private static Optional<Integer> getIntValue(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return Optional.empty();
        }
        Object value = metadata.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number) {
            return Optional.of(((Number) value).intValue());
        }
        try {
            return Optional.of(Integer.parseInt(value.toString()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String extractExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return null;
        }
        return filename.substring(lastDot + 1).toLowerCase();
    }
}
