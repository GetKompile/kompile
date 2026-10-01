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

import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Manages on-disk storage for a single project's code index.
 * Handles fingerprint tracking, per-file entity shards, and metadata.
 * All writes use atomic rename to prevent corruption during concurrent reads.
 * The project state files ({@code metadata.json}, {@code fingerprints.json} and the
 * {@code update.pending} marker) are also forced to disk around the rename: the rename
 * can reach the disk before the data, and a crash in between leaves an empty file.
 * Shards are not forced; they are rewritten whenever their source changes.
 *
 * <p>Layout under {@code ~/.kompile/code-index/<projectId>/}:
 * <pre>
 *   metadata.json         — project-level stats
 *   fingerprints.json     — Map&lt;relativePath, FileFingerprint&gt;
 *   files/&lt;hash&gt;.json     — per-file entity shard
 *   update.pending        — present until an update has published its metadata
 * </pre>
 */
public class IndexFileStore {

    static final String METADATA_FILE = "metadata.json";
    static final String FINGERPRINTS_FILE = "fingerprints.json";
    static final String UPDATE_PENDING_FILE = "update.pending";

    /**
     * A state file that exists but does not parse, typically one a crash left empty.
     * Indexing treats it as missing and rebuilds the index from source.
     */
    public static final class UnreadableIndexStateException extends IOException {
        private final transient Path file;

        UnreadableIndexStateException(Path file, Throwable cause) {
            super("Code index state file " + file + " is empty or unreadable; run local_code_index "
                    + "action=index from the project root to rebuild it from source", cause);
            this.file = file;
        }

        public Path file() {
            return file;
        }
    }

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
     *
     * @throws UnreadableIndexStateException if the file exists but does not parse
     */
    public Map<String, FileFingerprint> loadFingerprints() throws IOException {
        return readState(indexDir.resolve(FINGERPRINTS_FILE),
                new TypeReference<LinkedHashMap<String, FileFingerprint>>() {});
    }

    /**
     * Save fingerprints atomically. Written compact — with one entry per
     * indexed file this is the largest index-side JSON, rewritten on every
     * changed pass, and nothing human reads it.
     */
    public void saveFingerprints(Map<String, FileFingerprint> fingerprints) throws IOException {
        atomicWrite(indexDir.resolve(FINGERPRINTS_FILE),
                objectMapper.writeValueAsBytes(fingerprints), true);
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
        atomicWrite(target, objectMapper.writeValueAsBytes(shard), false);
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
        return readAllShards(false);
    }

    public List<FileShard> readAllShardsStrict() throws IOException {
        return readAllShards(true);
    }

    private List<FileShard> readAllShards(boolean strict) throws IOException {
        Path filesDir = indexDir.resolve("files");
        if (!Files.isDirectory(filesDir)) {
            if (strict) throw new IOException("Cannot rebuild index: missing shard directory " + filesDir);
            return List.of();
        }

        List<FileShard> shards = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(filesDir, "*.json")) {
            for (Path file : stream) {
                try {
                    shards.add(objectMapper.readValue(file.toFile(), FileShard.class));
                } catch (IOException e) {
                    if (strict) throw new IOException("Cannot rebuild index: unreadable shard " + file, e);
                    // Search fallback is best-effort; recovery is not.
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
        atomicWrite(indexDir.resolve(METADATA_FILE),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(metadata), true);
    }

    /**
     * Load project metadata. Returns empty map if not found.
     *
     * @throws UnreadableIndexStateException if the file exists but does not parse
     */
    public Map<String, Object> loadMetadata() throws IOException {
        return readState(indexDir.resolve(METADATA_FILE),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** An absent file is an empty map; one that exists must parse to a JSON object. */
    private <V> Map<String, V> readState(Path file, TypeReference<LinkedHashMap<String, V>> type)
            throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        byte[] content = Files.readAllBytes(file);
        Map<String, V> state;
        try {
            state = objectMapper.readValue(content, type);
        } catch (IOException unparseable) {
            // Parsing bytes in memory does no I/O, so this is the content: empty, zeroed or cut short.
            throw new UnreadableIndexStateException(file, unparseable);
        }
        if (state == null) throw new UnreadableIndexStateException(file, null);
        return state;
    }

    // -----------------------------------------------------------------------
    // Update marker
    // -----------------------------------------------------------------------

    /**
     * Record, durably, that an update is about to change {@code index.db}. Until
     * {@link #clearUpdatePending()} runs, the next pass rebuilds instead of trusting
     * the JSON state.
     */
    public void markUpdatePending(String generation) throws IOException {
        atomicWrite(indexDir.resolve(UPDATE_PENDING_FILE),
                generation.getBytes(StandardCharsets.UTF_8), true);
    }

    /** Not forced: a delete lost in a crash costs one extra rebuild. */
    public void clearUpdatePending() throws IOException {
        Files.deleteIfExists(indexDir.resolve(UPDATE_PENDING_FILE));
    }

    public boolean hasPendingUpdate() {
        return Files.exists(indexDir.resolve(UPDATE_PENDING_FILE));
    }

    // -----------------------------------------------------------------------
    // Legacy support
    // -----------------------------------------------------------------------

    /**
     * Check if a legacy (pre-incremental) entities.json exists.
     */
    public boolean hasLegacyIndex() {
        return Files.exists(indexDir.resolve("entities.json"))
                && !Files.exists(indexDir.resolve(FINGERPRINTS_FILE));
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
        return HashUtils.sha256Hex(file);
    }

    /**
     * SHA-256 of a string (used for shard naming).
     */
    static String sha256String(String input) {
        return HashUtils.sha256Hex(input);
    }

    /**
     * Write bytes to a target path atomically via temp file + rename. A durable write
     * forces the data to disk before the rename and the directory entry after it, so
     * a crash leaves the old content or the new, never an empty file. The fixed temp
     * name is safe because writers hold the project write lock.
     */
    private void atomicWrite(Path target, byte[] content, boolean durable) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                if (durable) channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Fallback for filesystems that don't support atomic move
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (durable) forceDirectory(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Persist a rename. Best effort: Windows cannot open a directory as a channel. */
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // The data was forced before the rename; only the rename itself may be lost.
        }
    }
}
