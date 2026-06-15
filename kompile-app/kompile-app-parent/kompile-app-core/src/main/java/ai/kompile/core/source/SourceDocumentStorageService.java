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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for storing and retrieving original source documents.
 *
 * This service manages a local copy of all indexed documents in a standardized
 * directory structure, enabling:
 * - Source attribution for retrieved snippets
 * - Original document retrieval for RAG citations
 * - Deduplication via content checksums
 *
 * Default storage location: ~/.kompile/documents/
 *
 * Directory structure:
 * <pre>
 * ~/.kompile/documents/
 *   ├── ab/                  # First 2 chars of hash
 *   │   └── ab123def...pdf   # Full hash + original extension
 *   ├── cd/
 *   │   └── cd456abc...docx
 *   └── index.json           # Optional metadata index
 * </pre>
 */
public class SourceDocumentStorageService {

    private static final Logger logger = LoggerFactory.getLogger(SourceDocumentStorageService.class);
    private static final int BUFFER_SIZE = 8192; // SHA-256 read buffer

    private final Path storageRoot;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    // Cache of checksum -> stored path for quick lookups
    private final Map<String, Path> checksumCache = new ConcurrentHashMap<>();
    // Cache of checksum -> metadata for quick lookups
    private final Map<String, Map<String, Object>> metadataCache = new ConcurrentHashMap<>();

    /**
     * Creates a SourceDocumentStorageService with default storage location.
     */
    public SourceDocumentStorageService() {
        this(getDefaultStorageRoot(), true);
    }

    /**
     * Creates a SourceDocumentStorageService with custom storage location.
     *
     * @param storageRoot Path to the root storage directory
     * @param enabled     Whether document storage is enabled
     */
    public SourceDocumentStorageService(Path storageRoot, boolean enabled) {
        this.storageRoot = storageRoot;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();

        if (enabled) {
            try {
                Files.createDirectories(storageRoot);
                logger.info("SourceDocumentStorageService initialized at: {}", storageRoot);
            } catch (IOException e) {
                logger.error("Failed to create document storage directory: {}", storageRoot, e);
            }
        } else {
            logger.info("SourceDocumentStorageService is DISABLED - documents will not be stored");
        }
    }

    /**
     * Gets the default storage root (~/.kompile/documents/).
     */
    public static Path getDefaultStorageRoot() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".kompile", "documents");
    }

    /**
     * Stores a copy of a source document from a file path.
     *
     * @param sourcePath Path to the original document
     * @return StorageResult containing the stored path and checksum, or empty if storage failed/disabled
     */
    public Optional<StorageResult> storeDocument(Path sourcePath) {
        if (!enabled) {
            logger.debug("Document storage disabled, skipping: {}", sourcePath);
            return Optional.empty();
        }

        if (sourcePath == null || !Files.exists(sourcePath)) {
            logger.warn("Cannot store non-existent document: {}", sourcePath);
            return Optional.empty();
        }

        try {
            // Calculate checksum
            String checksum = calculateChecksum(sourcePath);

            // Check if already stored
            Optional<Path> existing = getStoredPath(checksum);
            if (existing.isPresent()) {
                logger.debug("Document already stored (dedup): {} -> {}", sourcePath.getFileName(), existing.get());
                return Optional.of(new StorageResult(
                        existing.get(),
                        checksum,
                        Files.size(sourcePath),
                        true // wasAlreadyStored
                ));
            }

            // Determine file extension
            String extension = getFileExtension(sourcePath.getFileName().toString());

            // Create storage path
            Path storedPath = createStoragePath(checksum, extension);
            Files.createDirectories(storedPath.getParent());

            // Copy file
            Files.copy(sourcePath, storedPath, StandardCopyOption.REPLACE_EXISTING);

            // Update cache
            checksumCache.put(checksum, storedPath);

            logger.info("Stored document: {} -> {} (checksum: {})",
                    sourcePath.getFileName(), storedPath, checksum.substring(0, 16) + "...");

            return Optional.of(new StorageResult(
                    storedPath,
                    checksum,
                    Files.size(storedPath),
                    false // wasAlreadyStored
            ));

        } catch (IOException | NoSuchAlgorithmException e) {
            logger.error("Failed to store document: {}", sourcePath, e);
            return Optional.empty();
        }
    }

    /**
     * Stores a copy of a source document from an InputStream.
     *
     * @param inputStream Stream containing document content
     * @param filename    Original filename (used for extension)
     * @return StorageResult containing the stored path and checksum
     */
    public Optional<StorageResult> storeDocument(InputStream inputStream, String filename) {
        if (!enabled) {
            return Optional.empty();
        }

        if (inputStream == null) {
            logger.warn("Cannot store null input stream");
            return Optional.empty();
        }

        try {
            // Create temporary file to calculate checksum and store
            String extension = getFileExtension(filename);
            Path tempFile = Files.createTempFile("kompile-upload-", "." + extension);

            try {
                // Copy to temp file
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                // Calculate checksum
                String checksum = calculateChecksum(tempFile);

                // Check if already stored
                Optional<Path> existing = getStoredPath(checksum);
                if (existing.isPresent()) {
                    Files.deleteIfExists(tempFile);
                    return Optional.of(new StorageResult(
                            existing.get(),
                            checksum,
                            Files.size(existing.get()),
                            true
                    ));
                }

                // Move to final location
                Path storedPath = createStoragePath(checksum, extension);
                Files.createDirectories(storedPath.getParent());
                Files.move(tempFile, storedPath, StandardCopyOption.REPLACE_EXISTING);

                // Update cache
                checksumCache.put(checksum, storedPath);

                logger.info("Stored document from stream: {} -> {} (checksum: {})",
                        filename, storedPath, checksum.substring(0, 16) + "...");

                return Optional.of(new StorageResult(
                        storedPath,
                        checksum,
                        Files.size(storedPath),
                        false
                ));

            } finally {
                // Clean up temp file if it still exists
                Files.deleteIfExists(tempFile);
            }

        } catch (IOException | NoSuchAlgorithmException e) {
            logger.error("Failed to store document from stream: {}", filename, e);
            return Optional.empty();
        }
    }

    /**
     * Stores a copy of a source document from a URL.
     *
     * @param url URL to fetch the document from
     * @return StorageResult containing the stored path and checksum
     */
    public Optional<StorageResult> storeDocument(URL url) {
        if (!enabled || url == null) {
            return Optional.empty();
        }

        try (InputStream is = url.openStream()) {
            String filename = extractFilenameFromUrl(url);
            return storeDocument(is, filename);
        } catch (IOException e) {
            logger.error("Failed to store document from URL: {}", url, e);
            return Optional.empty();
        }
    }

    /**
     * Gets the stored path for a document by its checksum.
     *
     * @param checksum SHA-256 checksum of the document
     * @return Path to the stored document, or empty if not found
     */
    public Optional<Path> getStoredPath(String checksum) {
        if (checksum == null || checksum.length() < 2) {
            return Optional.empty();
        }

        // Check cache first
        Path cached = checksumCache.get(checksum);
        if (cached != null && Files.exists(cached)) {
            return Optional.of(cached);
        }

        // Search in storage directory
        String prefix = checksum.substring(0, 2);
        Path prefixDir = storageRoot.resolve(prefix);

        if (!Files.exists(prefixDir)) {
            return Optional.empty();
        }

        try {
            return Files.list(prefixDir)
                    .filter(p -> p.getFileName().toString().startsWith(checksum))
                    .findFirst()
                    .map(p -> {
                        // Update cache
                        checksumCache.put(checksum, p);
                        return p;
                    });
        } catch (IOException e) {
            logger.warn("Error searching for stored document with checksum: {}", checksum, e);
            return Optional.empty();
        }
    }

    /**
     * Retrieves the content of a stored document.
     *
     * @param checksum SHA-256 checksum of the document
     * @return InputStream of the document content, or empty if not found
     */
    public Optional<InputStream> getDocumentContent(String checksum) {
        return getStoredPath(checksum)
                .flatMap(path -> {
                    try {
                        return Optional.of(Files.newInputStream(path));
                    } catch (IOException e) {
                        logger.error("Failed to read stored document: {}", path, e);
                        return Optional.empty();
                    }
                });
    }

    /**
     * Checks if a document with the given checksum is already stored.
     *
     * @param checksum SHA-256 checksum
     * @return true if already stored
     */
    public boolean isStored(String checksum) {
        return getStoredPath(checksum).isPresent();
    }

    /**
     * Deletes a stored document by checksum.
     *
     * @param checksum SHA-256 checksum
     * @return true if deleted successfully
     */
    public boolean deleteDocument(String checksum) {
        Optional<Path> storedPath = getStoredPath(checksum);
        if (storedPath.isEmpty()) {
            return false;
        }

        try {
            Files.deleteIfExists(storedPath.get());
            checksumCache.remove(checksum);
            logger.info("Deleted stored document: {}", storedPath.get());
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete stored document: {}", storedPath.get(), e);
            return false;
        }
    }

    /**
     * Gets the storage root path.
     */
    public Path getStorageRoot() {
        return storageRoot;
    }

    /**
     * Checks if document storage is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Calculates the approximate storage size in bytes.
     *
     * @return Total size of all stored documents
     */
    public long getStorageSize() {
        if (!enabled || !Files.exists(storageRoot)) {
            return 0L;
        }

        try {
            return Files.walk(storageRoot)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            logger.warn("Error calculating storage size", e);
            return 0L;
        }
    }

    /**
     * Counts the number of stored documents.
     *
     * @return Number of stored documents
     */
    public long getDocumentCount() {
        if (!enabled || !Files.exists(storageRoot)) {
            return 0L;
        }

        try {
            return Files.walk(storageRoot)
                    .filter(Files::isRegularFile)
                    .count();
        } catch (IOException e) {
            logger.warn("Error counting stored documents", e);
            return 0L;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA STORAGE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stores metadata for a document identified by checksum.
     * The metadata is stored in a JSON file alongside the document.
     *
     * @param checksum SHA-256 checksum of the document
     * @param metadata Metadata to store
     */
    public void storeMetadata(String checksum, Map<String, Object> metadata) {
        if (!enabled || checksum == null || checksum.length() < 2 || metadata == null) {
            return;
        }

        try {
            Path metadataPath = getMetadataPath(checksum);
            Files.createDirectories(metadataPath.getParent());

            // Merge with existing metadata if any
            Map<String, Object> existingMetadata = getMetadata(checksum).orElse(new HashMap<>());
            existingMetadata.putAll(metadata);

            objectMapper.writeValue(metadataPath.toFile(), existingMetadata);
            metadataCache.put(checksum, existingMetadata);

            logger.debug("Stored metadata for document: {} (keys: {})",
                    checksum.substring(0, 16) + "...", metadata.keySet());
        } catch (IOException e) {
            logger.warn("Failed to store metadata for document {}: {}", checksum, e.getMessage());
        }
    }

    /**
     * Retrieves metadata for a document identified by checksum.
     *
     * @param checksum SHA-256 checksum of the document
     * @return Optional containing the metadata map, or empty if not found
     */
    public Optional<Map<String, Object>> getMetadata(String checksum) {
        if (checksum == null || checksum.length() < 2) {
            return Optional.empty();
        }

        // Check cache first
        Map<String, Object> cached = metadataCache.get(checksum);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Try to load from file
        Path metadataPath = getMetadataPath(checksum);
        if (!Files.exists(metadataPath)) {
            return Optional.empty();
        }

        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    metadataPath.toFile(),
                    new TypeReference<Map<String, Object>>() {}
            );
            metadataCache.put(checksum, metadata);
            return Optional.of(metadata);
        } catch (IOException e) {
            logger.warn("Failed to read metadata for document {}: {}", checksum, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gets the source URL for a document by checksum.
     * Convenience method that extracts the source_url from metadata.
     *
     * @param checksum SHA-256 checksum of the document
     * @return Optional containing the source URL, or empty if not found
     */
    public Optional<String> getSourceUrl(String checksum) {
        return getMetadata(checksum)
                .map(metadata -> metadata.get(SourceMetadataConstants.SOURCE_URL))
                .filter(url -> url != null)
                .map(Object::toString);
    }

    /**
     * Gets the path to the metadata file for a document.
     */
    private Path getMetadataPath(String checksum) {
        String prefix = checksum.substring(0, 2);
        return storageRoot.resolve(prefix).resolve(checksum + ".meta.json");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculates SHA-256 checksum of a file.
     */
    public static String calculateChecksum(Path filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;

        try (InputStream is = Files.newInputStream(filePath)) {
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Creates the storage path for a document based on its checksum.
     */
    private Path createStoragePath(String checksum, String extension) {
        String prefix = checksum.substring(0, 2);
        String filename = checksum + (extension.isEmpty() ? "" : "." + extension);
        return storageRoot.resolve(prefix).resolve(filename);
    }

    /**
     * Extracts file extension from a filename.
     */
    private static String getFileExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase();
    }

    /**
     * Extracts filename from a URL.
     */
    private static String extractFilenameFromUrl(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            return "document";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return "document";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of a document storage operation.
     */
    public record StorageResult(
            Path storedPath,
            String checksum,
            long sizeBytes,
            boolean wasAlreadyStored
    ) {
        /**
         * Gets the stored path as a string.
         */
        public String getStoredPathString() {
            return storedPath != null ? storedPath.toString() : null;
        }

        /**
         * Gets the timestamp when stored (current time for new, or file modification time for existing).
         */
        public Instant getStoredAt() {
            if (storedPath == null) {
                return Instant.now();
            }
            try {
                return Files.getLastModifiedTime(storedPath).toInstant();
            } catch (IOException e) {
                return Instant.now();
            }
        }
    }
}
