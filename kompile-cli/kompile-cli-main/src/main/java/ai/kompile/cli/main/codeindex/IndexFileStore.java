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

package ai.kompile.cli.main.codeindex;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Manages on-disk storage for a single project's code index.
 * Handles fingerprint tracking, per-file entity shards, and metadata.
 * All writes use atomic rename to prevent corruption during concurrent reads.
 *
 * <p>Layout under {@code ~/.kompile/code-index/<projectId>/}:
 * <pre>
 *   metadata.json         — project-level stats
 *   fingerprints.json     — Map&lt;relativePath, FileFingerprint&gt;
 *   files/&lt;hash&gt;.json     — per-file entity shard
 * </pre>
 */
public class IndexFileStore {

    private final Path indexDir;
    private final ObjectMapper objectMapper;

    public IndexFileStore(Path indexDir, ObjectMapper objectMapper) {
        this.indexDir = indexDir;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Fingerprints
    // -----------------------------------------------------------------------

    /**
     * A file's identity for change detection.
     * mtime+size is checked first (cheap); SHA-256 only when those differ.
     */
    public record FileFingerprint(long lastModified, long size, String sha256) {}

    /**
     * Load stored fingerprints. Returns empty map if no fingerprints file exists.
     */
    public Map<String, FileFingerprint> loadFingerprints() throws IOException {
        Path fp = indexDir.resolve("fingerprints.json");
        if (!Files.exists(fp)) return new LinkedHashMap<>();
        return objectMapper.readValue(fp.toFile(),
                new TypeReference<LinkedHashMap<String, FileFingerprint>>() {});
    }

    /**
     * Save fingerprints atomically.
     */
    public void saveFingerprints(Map<String, FileFingerprint> fingerprints) throws IOException {
        atomicWrite(indexDir.resolve("fingerprints.json"),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(fingerprints));
    }

    // -----------------------------------------------------------------------
    // Per-file entity shards
    // -----------------------------------------------------------------------

    /**
     * On-disk representation of a single source file's entities.
     */
    public record FileShard(
            String relativePath,
            FileFingerprint fingerprint,
            String indexedAt,
            List<Map<String, Object>> entities
    ) {}

    /**
     * Ensure the files directory exists (call once before batch writes).
     */
    public void ensureFilesDir() throws IOException {
        Files.createDirectories(indexDir.resolve("files"));
    }

    /**
     * Write a per-file shard atomically.
     * Call {@link #ensureFilesDir()} once before a batch of writes.
     */
    public void writeFileShard(String relativePath, FileFingerprint fp,
                               List<Map<String, Object>> entities) throws IOException {
        Path filesDir = indexDir.resolve("files");
        FileShard shard = new FileShard(relativePath, fp, Instant.now().toString(), entities);
        Path target = filesDir.resolve(shardName(relativePath));
        atomicWrite(target, objectMapper.writeValueAsBytes(shard));
    }

    /**
     * Read a per-file shard. Returns null if it doesn't exist.
     */
    public FileShard readFileShard(String relativePath) throws IOException {
        Path target = indexDir.resolve("files").resolve(shardName(relativePath));
        if (!Files.exists(target)) return null;
        return objectMapper.readValue(target.toFile(), FileShard.class);
    }

    /**
     * Delete a per-file shard.
     */
    public void deleteFileShard(String relativePath) throws IOException {
        Path target = indexDir.resolve("files").resolve(shardName(relativePath));
        Files.deleteIfExists(target);
    }

    /**
     * List all shard files and read their entities. Used for DB rebuild.
     */
    public List<FileShard> readAllShards() throws IOException {
        Path filesDir = indexDir.resolve("files");
        if (!Files.isDirectory(filesDir)) return List.of();

        List<FileShard> shards = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(filesDir, "*.json")) {
            for (Path file : stream) {
                try {
                    shards.add(objectMapper.readValue(file.toFile(), FileShard.class));
                } catch (IOException e) {
                    // Skip corrupt shards
                }
            }
        }
        return shards;
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    /**
     * Save project metadata atomically.
     */
    public void saveMetadata(Map<String, Object> metadata) throws IOException {
        atomicWrite(indexDir.resolve("metadata.json"),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(metadata));
    }

    /**
     * Load project metadata. Returns empty map if not found.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadMetadata() throws IOException {
        Path meta = indexDir.resolve("metadata.json");
        if (!Files.exists(meta)) return new LinkedHashMap<>();
        return objectMapper.readValue(meta.toFile(), Map.class);
    }

    // -----------------------------------------------------------------------
    // Legacy support
    // -----------------------------------------------------------------------

    /**
     * Check if a legacy (pre-incremental) entities.json exists.
     */
    public boolean hasLegacyIndex() {
        return Files.exists(indexDir.resolve("entities.json"))
                && !Files.exists(indexDir.resolve("fingerprints.json"));
    }

    /**
     * Read the legacy flat entities list.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readLegacyEntities() throws IOException {
        Path entitiesFile = indexDir.resolve("entities.json");
        if (!Files.exists(entitiesFile)) return List.of();
        return objectMapper.readValue(entitiesFile.toFile(), List.class);
    }

    /**
     * Rename legacy entities.json after migration.
     */
    public void archiveLegacyEntities() throws IOException {
        Path src = indexDir.resolve("entities.json");
        if (Files.exists(src)) {
            Files.move(src, indexDir.resolve("entities.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public Path getIndexDir() {
        return indexDir;
    }

    /**
     * Stable, filesystem-safe shard filename from a relative path.
     */
    static String shardName(String relativePath) {
        return sha256String(relativePath) + ".json";
    }

    /**
     * SHA-256 of a file's contents.
     */
    public static String sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
            }
            return hexEncode(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * SHA-256 of a string (used for shard naming).
     */
    static String sha256String(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return hexEncode(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Write bytes to a target path atomically via temp file + rename.
     */
    private void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Fallback for filesystems that don't support atomic move
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
