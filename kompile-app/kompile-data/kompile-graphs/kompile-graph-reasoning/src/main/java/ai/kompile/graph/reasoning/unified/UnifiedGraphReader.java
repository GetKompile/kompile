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
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reconstructs a {@link UnifiedGraph} from the single-file form written by {@link UnifiedGraphWriter}.
 * Internal to {@link UnifiedGraph#load(java.nio.file.Path)}.
 *
 * <p>Reading buffers only bounded non-structural ZIP entries. Entity and relation JSONL entries are
 * streamed row-by-row after vector layers are read, so primary embeddings can be attached while the
 * graph is rebuilt without an entry-sized byte array or line list. Every aspect written round-trips: topology,
 * scalar/temporal properties, tags, the full {@code attributes} bag, subjective-logic opinions,
 * additional vector layers, named weight maps, and graph-level meta.</p>
 */
final class UnifiedGraphReader {

    private static final Limits DEFAULT_LIMITS = Limits.systemDefaults();

    private UnifiedGraphReader() { }

    /**
     * Resource limits are explicit so tests and callers can exercise the streaming boundary without
     * allocating hundreds of megabytes. {@code maxEntryBytes} applies only to entries that must be
     * buffered (manifest, vectors, models, weights, opinions). Structural JSONL is streamed and is
     * governed by {@code maxTotalBytes} plus {@code maxJsonlRowChars} instead.
     */
    record Limits(
            int maxEntryCount,
            long maxEntryBytes,
            long maxTotalBytes,
            long maxManifestBytes,
            int maxEntryNameLength,
            long maxCentralDirectoryBytes,
            long maxTotalDecodedVectorValues,
            long maxArchiveBytes,
            int maxJsonlRowChars,
            int maxEntityCount,
            int maxRelationCount) {

        Limits {
            if (maxEntryCount < 1 || maxEntryBytes < 1 || maxTotalBytes < maxEntryBytes
                    || maxManifestBytes < 1 || maxManifestBytes > maxEntryBytes
                    || maxEntryNameLength < 1 || maxCentralDirectoryBytes < 1
                    || maxTotalDecodedVectorValues < 1 || maxArchiveBytes < 1
                    || maxJsonlRowChars < 1 || maxEntityCount < 1 || maxRelationCount < 1) {
                throw new IllegalArgumentException("Unified-graph reader limits must be positive and consistent");
            }
        }

        static Limits systemDefaults() {
            long maxEntryBytes = Math.max(
                    1L, Long.getLong("kompile.graph.maxEntryBytes", 512L * 1024 * 1024));
            long maxTotalBytes = Math.max(maxEntryBytes,
                    Long.getLong("kompile.graph.maxTotalBytes", 2L * 1024 * 1024 * 1024));
            return new Limits(
                    Math.max(1, Integer.getInteger("kompile.graph.maxEntries", 10_000)),
                    maxEntryBytes,
                    maxTotalBytes,
                    Math.min(maxEntryBytes, 16L * 1024 * 1024),
                    4_096,
                    Math.max(1L, Long.getLong(
                            "kompile.graph.maxCentralDirectoryBytes", 64L * 1024 * 1024)),
                    Math.max(1L, Long.getLong(
                            "kompile.graph.maxTotalDecodedVectorValues", 128_000_000L)),
                    Math.max(1L, Long.getLong("kompile.graph.maxArchiveBytes", maxTotalBytes)),
                    Math.max(1, Integer.getInteger(
                            "kompile.graph.maxJsonlRowChars", 16 * 1024 * 1024)),
                    Math.max(1, Integer.getInteger("kompile.graph.maxEntities", 5_000_000)),
                    Math.max(1, Integer.getInteger("kompile.graph.maxRelations", 20_000_000)));
        }
    }

    private static final int ZIP_EOCD_SIGNATURE = 0x06054b50;
    private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP_CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int ZIP_CENTRAL_HEADER_BYTES = 46;
    private static final int ZIP_EOCD_MIN_BYTES = 22;
    private static final int ZIP_MAX_COMMENT_BYTES = 65_535;

    /**
     * Bound central-directory allocation before {@link ZipFile} indexes it. The standard API applies
     * entry checks only after construction, which is too late for an archive containing millions of
     * tiny entries. Reading the EOCD/ZIP64 metadata directly keeps this preflight constant-memory.
     */
    private static void preflightCentralDirectory(Path file, Limits limits) throws IOException {
        long archiveBytes = Files.size(file);
        int tailLength = (int) Math.min(
                archiveBytes, (long) ZIP_EOCD_MIN_BYTES + ZIP_MAX_COMMENT_BYTES);
        if (tailLength < ZIP_EOCD_MIN_BYTES) {
            throw new IOException("Not a unified-graph ZIP file: end-of-central-directory is missing");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer tail = readAt(channel, archiveBytes - tailLength, tailLength);
            int eocd = findEocd(tail);
            if (eocd < 0) {
                throw new IOException("Not a unified-graph ZIP file: end-of-central-directory is missing");
            }

            long eocdAbsolute = archiveBytes - tailLength + eocd;
            long physicalDirectoryEnd = eocdAbsolute;
            int disk = unsignedShort(tail, eocd + 4);
            int directoryDisk = unsignedShort(tail, eocd + 6);
            long entriesOnDisk = unsignedShort(tail, eocd + 8);
            long entryCount = unsignedShort(tail, eocd + 10);
            long directoryBytes = unsignedInt(tail, eocd + 12);
            long directoryOffset = unsignedInt(tail, eocd + 16);
            if (disk != 0 || directoryDisk != 0 || entriesOnDisk != entryCount) {
                throw new IOException("Multi-disk unified-graph ZIP archives are not supported");
            }

            boolean zip64 = entryCount == 0xffffL || directoryBytes == 0xffff_ffffL
                    || directoryOffset == 0xffff_ffffL;
            if (zip64) {
                if (eocdAbsolute < 20) {
                    throw new IOException("Invalid ZIP64 unified-graph directory locator");
                }
                ByteBuffer locator = readAt(channel, eocdAbsolute - 20, 20);
                if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE
                        || locator.getInt(4) != 0 || locator.getInt(16) != 1) {
                    throw new IOException("Invalid or multi-disk ZIP64 unified-graph archive");
                }
                long zip64Offset = locator.getLong(8);
                if (zip64Offset < 0 || zip64Offset > archiveBytes - 56) {
                    throw new IOException("Invalid ZIP64 end-of-central-directory offset");
                }
                ByteBuffer zip64Eocd = readAt(channel, zip64Offset, 56);
                physicalDirectoryEnd = zip64Offset;
                if (zip64Eocd.getInt(0) != ZIP64_EOCD_SIGNATURE
                        || zip64Eocd.getInt(16) != 0 || zip64Eocd.getInt(20) != 0) {
                    throw new IOException("Invalid or multi-disk ZIP64 unified-graph archive");
                }
                entriesOnDisk = positiveZip64Value(zip64Eocd.getLong(24), "entry count");
                entryCount = positiveZip64Value(zip64Eocd.getLong(32), "entry count");
                directoryBytes = positiveZip64Value(zip64Eocd.getLong(40), "directory size");
                directoryOffset = positiveZip64Value(zip64Eocd.getLong(48), "directory offset");
                if (entriesOnDisk != entryCount) {
                    throw new IOException("Multi-disk ZIP64 unified-graph archives are not supported");
                }
            }

            if (entryCount > limits.maxEntryCount()) {
                throw new IOException("Unified graph has too many entries (limit "
                        + limits.maxEntryCount() + ")");
            }
            if (directoryBytes > limits.maxCentralDirectoryBytes()) {
                throw new IOException("Unified-graph central directory exceeds size limit of "
                        + limits.maxCentralDirectoryBytes());
            }
            if (directoryOffset < 0 || directoryBytes > archiveBytes
                    || directoryOffset > archiveBytes - directoryBytes) {
                throw new IOException("Invalid unified-graph central-directory bounds");
            }
            long physicalDirectoryOffset = physicalDirectoryEnd - directoryBytes;
            if (physicalDirectoryOffset < 0 || directoryOffset > physicalDirectoryOffset) {
                throw new IOException("Invalid unified-graph physical central-directory bounds");
            }
            validateCentralDirectory(
                    channel, physicalDirectoryOffset, directoryBytes, entryCount, limits);
        }
    }

    private static void validateCentralDirectory(
            FileChannel channel,
            long directoryOffset,
            long directoryBytes,
            long declaredEntryCount,
            Limits limits) throws IOException {
        long cursor = directoryOffset;
        long end = directoryOffset + directoryBytes;
        long actualEntryCount = 0;
        while (cursor < end) {
            if (end - cursor < ZIP_CENTRAL_HEADER_BYTES) {
                throw new IOException("Truncated unified-graph central-directory record");
            }
            ByteBuffer header = readAt(channel, cursor, ZIP_CENTRAL_HEADER_BYTES);
            if (header.getInt(0) != ZIP_CENTRAL_HEADER_SIGNATURE) {
                throw new IOException("Invalid unified-graph central-directory signature");
            }
            int nameBytes = unsignedShort(header, 28);
            int extraBytes = unsignedShort(header, 30);
            int commentBytes = unsignedShort(header, 32);
            if (nameBytes < 1 || nameBytes > (long) limits.maxEntryNameLength() * 4L) {
                throw new IOException("Unsafe unified-graph entry-name byte length: " + nameBytes);
            }
            long recordBytes = ZIP_CENTRAL_HEADER_BYTES
                    + (long) nameBytes + extraBytes + commentBytes;
            if (recordBytes > end - cursor) {
                throw new IOException("Unified-graph central-directory record exceeds declared bounds");
            }
            actualEntryCount++;
            if (actualEntryCount > limits.maxEntryCount()) {
                throw new IOException("Unified graph has too many entries (limit "
                        + limits.maxEntryCount() + ")");
            }
            cursor += recordBytes;
        }
        if (cursor != end || actualEntryCount != declaredEntryCount) {
            throw new IOException("Unified-graph central-directory entry count mismatch: declared="
                    + declaredEntryCount + ", actual=" + actualEntryCount);
        }
    }

    private static ByteBuffer readAt(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Truncated unified-graph ZIP metadata");
            }
        }
        return buffer.flip().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int findEocd(ByteBuffer tail) {
        for (int offset = tail.limit() - ZIP_EOCD_MIN_BYTES; offset >= 0; offset--) {
            if (tail.getInt(offset) == ZIP_EOCD_SIGNATURE) {
                int commentBytes = unsignedShort(tail, offset + 20);
                if (offset + ZIP_EOCD_MIN_BYTES + commentBytes == tail.limit()) return offset;
            }
        }
        return -1;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static long positiveZip64Value(long value, String field) throws IOException {
        if (value < 0) throw new IOException("ZIP64 unified-graph " + field + " exceeds supported range");
        return value;
    }

    /** Load from a file (uses {@link ZipFile} for efficient per-entry access). */
    static UnifiedGraph read(Path file) throws IOException {
        return read(file, DEFAULT_LIMITS);
    }

    /** Validate archive structure and return its manifest without loading graph payloads. */
    static Map<String, Object> inspectManifest(Path file) throws IOException {
        try (StableArchiveReference stable = stableArchiveReference(file, DEFAULT_LIMITS)) {
            Map<String, Object> manifest = inspectManifestStable(stable.path(), DEFAULT_LIMITS);
            stable.requireUnchanged();
            return manifest;
        }
    }

    static Map<String, Object> inspectManifestStable(Path file, Limits limits) throws IOException {
        if (Files.size(file) > limits.maxArchiveBytes()) {
            throw new IOException("Unified graph exceeds compressed archive size limit of "
                    + limits.maxArchiveBytes());
        }
        preflightCentralDirectory(file, limits);
        ExpansionBudget budget = new ExpansionBudget(limits.maxTotalBytes());
        EntryAccumulator accumulator = new EntryAccumulator(limits, budget);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry manifest = null;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                validateEntryName(entry.getName(), entry.isDirectory(), limits);
                if (entry.isDirectory()) {
                    throw new IOException("Directory entries are not allowed in unified graphs: "
                            + entry.getName());
                }
                if (isStructuralEntry(entry.getName())) accumulator.registerStructural(entry);
                else accumulator.registerBuffered(entry);
                if (UnifiedGraphFormat.ENTRY_MANIFEST.equals(entry.getName())) manifest = entry;
            }
            accumulator.bufferRegistered(zip, manifest);
            return Map.copyOf(parseGraphHeader(
                    accumulator.entries, accumulator.names, limits).manifest());
        }
    }

    private static boolean isStructuralEntry(String name) {
        return UnifiedGraphFormat.ENTRY_ENTITIES.equals(name)
                || UnifiedGraphFormat.ENTRY_RELATIONS.equals(name)
                || UnifiedGraphFormat.ENTRY_COMPACT_LINKS.equals(name)
                || UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(name)
                || UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES.equals(name);
    }

    static UnifiedGraph read(Path file, Limits limits) throws IOException {
        try (StableArchiveReference stable = stableArchiveReference(file, limits)) {
            UnifiedGraph graph = readStable(stable.path(), limits);
            stable.requireUnchanged();
            return graph;
        }
    }

    private static UnifiedGraph readStable(Path file, Limits limits) throws IOException {
        long archiveBytes = Files.size(file);
        if (archiveBytes > limits.maxArchiveBytes()) {
            throw new IOException("Unified graph exceeds compressed archive size limit of "
                    + limits.maxArchiveBytes());
        }
        preflightCentralDirectory(file, limits);
        ExpansionBudget budget = new ExpansionBudget(limits.maxTotalBytes());
        EntryAccumulator accumulator = new EntryAccumulator(limits, budget);
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Map<String, ZipEntry> catalog = new LinkedHashMap<>();
            ZipEntry entities = null;
            ZipEntry relations = null;
            ZipEntry compactLinks = null;
            ZipEntry compactAdjacency = null;
            ZipEntry relationProperties = null;
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                validateEntryName(entry.getName(), entry.isDirectory(), limits);
                if (entry.isDirectory()) {
                    throw new IOException("Directory entries are not allowed in unified graphs: "
                            + entry.getName());
                }
                if (UnifiedGraphFormat.ENTRY_ENTITIES.equals(entry.getName())) {
                    accumulator.registerStructural(entry);
                    entities = entry;
                } else if (UnifiedGraphFormat.ENTRY_RELATIONS.equals(entry.getName())) {
                    accumulator.registerStructural(entry);
                    relations = entry;
                } else if (UnifiedGraphFormat.ENTRY_COMPACT_LINKS.equals(entry.getName())) {
                    accumulator.registerStructural(entry);
                    compactLinks = entry;
                } else if (UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(entry.getName())) {
                    accumulator.registerStructural(entry);
                    compactAdjacency = entry;
                } else if (UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES.equals(entry.getName())) {
                    accumulator.registerStructural(entry);
                    relationProperties = entry;
                } else {
                    accumulator.registerBuffered(entry);
                }
                catalog.put(entry.getName(), entry);
            }

            // The manifest is validated before any other payload is allocated. The schema is then
            // separately bounded and fully shape-checked before vectors/models/artifacts are read.
            accumulator.bufferRegistered(zf, catalog.get(UnifiedGraphFormat.ENTRY_MANIFEST));
            GraphHeader header = parseGraphHeader(accumulator.entries, accumulator.names, limits);
            if (header.layout().formatVersion() >= 3
                    && (compactLinks == null || compactLinks.getMethod() != ZipEntry.STORED)) {
                throw new IOException("Compact topology entry must use ZIP STORED mode");
            }
            if (compactAdjacency != null && compactAdjacency.getMethod() != ZipEntry.STORED) {
                throw new IOException("Compact adjacency entry must use ZIP STORED mode");
            }
            accumulator.bufferRegistered(zf, catalog.get(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX));
            byte[] schemaBytes = accumulator.entries.get(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX);
            if (schemaBytes != null) {
                header = new GraphHeader(
                        header.manifest(), header.layout(), parseSchemaIndex(schemaBytes, header.layout()));
            }

            for (Map.Entry<String, ZipEntry> item : catalog.entrySet()) {
                String name = item.getKey();
                if (UnifiedGraphFormat.ENTRY_ENTITIES.equals(name)
                        || UnifiedGraphFormat.ENTRY_RELATIONS.equals(name)
                        || UnifiedGraphFormat.ENTRY_COMPACT_LINKS.equals(name)
                        || UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(name)
                        || UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES.equals(name)
                        || accumulator.entries.get(name) != null) {
                    continue;
                }
                accumulator.bufferRegistered(zf, item.getValue());
            }
            ZipEntry entityEntry = entities;
            ZipEntry relationEntry = relations;
            RowSource entityRows = rowsFromZip(zf, entityEntry, budget, limits);
            RowSource relationRows = rowsFromZip(zf, relationEntry, budget, limits);
            CompactLinkSource compactSource = linksFromZip(
                    zf, compactLinks, relationProperties, budget, limits, header.layout());
            return reconstruct(
                    accumulator.entries, entityRows, relationRows, compactSource, limits, header);
        }
    }

    static StableArchiveReference stableArchiveReference(Path file, Limits limits) throws IOException {
        Path source = file.toAbsolutePath().normalize().toRealPath();
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > limits.maxArchiveBytes()) {
            throw new IOException("Unified graph is not a regular file or exceeds compressed archive size limit");
        }
        Path temporaryDirectory = createStableReadDirectory(source);
        Path reference = temporaryDirectory.resolve("archive.kgraph");
        boolean retained = false;
        try {
            try {
                Files.createLink(reference, source);
            } catch (IOException | UnsupportedOperationException linkFailure) {
                Files.copy(source, reference, StandardCopyOption.COPY_ATTRIBUTES);
            }
            BasicFileAttributes after = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFileState(before, after) || Files.size(reference) != before.size()) {
                throw new IOException("Unified graph changed while creating a stable read reference");
            }
            BasicFileAttributes referenceState = Files.readAttributes(
                    reference, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            retained = true;
            return new StableArchiveReference(reference, temporaryDirectory, referenceState);
        } finally {
            if (!retained) {
                Files.deleteIfExists(reference);
                Files.deleteIfExists(temporaryDirectory);
            }
        }
    }

    private static Path createStableReadDirectory(Path source) throws IOException {
        IOException adjacentFailure = null;
        Path parent = source.getParent();
        if (parent != null) {
            try {
                // Android native images default java.io.tmpdir to /tmp, which is not writable by
                // applications. Keeping the transient reference beside an app-owned graph also
                // lets the hard-link fast path remain on the same filesystem.
                return Files.createTempDirectory(parent, ".kompile-kgraph-read-");
            } catch (IOException failure) {
                adjacentFailure = failure;
            }
        }
        try {
            return Files.createTempDirectory("kompile-kgraph-read-");
        } catch (IOException fallbackFailure) {
            if (adjacentFailure != null) fallbackFailure.addSuppressed(adjacentFailure);
            throw fallbackFailure;
        }
    }

    private static boolean sameFileState(BasicFileAttributes left, BasicFileAttributes right) {
        Object leftKey = left.fileKey();
        Object rightKey = right.fileKey();
        return (leftKey == null && rightKey == null || java.util.Objects.equals(leftKey, rightKey))
                && left.size() == right.size()
                && left.lastModifiedTime().equals(right.lastModifiedTime());
    }

    static final class StableArchiveReference implements AutoCloseable {
        private final Path path;
        private final Path temporaryDirectory;
        private final BasicFileAttributes state;

        private StableArchiveReference(
                Path path, Path temporaryDirectory, BasicFileAttributes state) {
            this.path = path;
            this.temporaryDirectory = temporaryDirectory;
            this.state = state;
        }

        Path path() { return path; }

        void requireUnchanged() throws IOException {
            BasicFileAttributes current = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFileState(state, current)) {
                throw new IOException("Unified graph changed while it was being read");
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    /**
     * Load from a stream without buffering the expanded archive in heap. The compressed stream is
     * spooled to a bounded temporary file and then processed through the random-access path above;
     * on success the caller's stream is read to end but not closed.
     */
    static UnifiedGraph read(InputStream in) throws IOException {
        return read(in, DEFAULT_LIMITS);
    }

    static UnifiedGraph read(InputStream in, Limits limits) throws IOException {
        Path temporary = Files.createTempFile("kompile-unified-graph-", ".kgraph");
        Throwable failure = null;
        try {
            try (OutputStream out = Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8_192];
                long count = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read == 0) continue;
                    if (count > limits.maxArchiveBytes() - read) {
                        throw new IOException("Unified graph exceeds compressed archive size limit of "
                                + limits.maxArchiveBytes());
                    }
                    out.write(buffer, 0, read);
                    count += read;
                }
            }
            return read(temporary, limits);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static final class EntryAccumulator {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final Set<String> caseFoldedNames = new LinkedHashSet<>();
        private final Limits limits;
        private final ExpansionBudget budget;

        private EntryAccumulator(Limits limits, ExpansionBudget budget) {
            this.limits = limits;
            this.budget = budget;
        }

        private void registerStructural(ZipEntry entry) throws IOException {
            register(entry);
            long declaredSize = entry.getSize();
            if (declaredSize > limits.maxTotalBytes()) {
                throw new IOException("Unified graph exceeds total size limit");
            }
        }

        private void registerBuffered(ZipEntry entry) throws IOException {
            register(entry);
            String name = entry.getName();
            long entryLimit = UnifiedGraphFormat.ENTRY_MANIFEST.equals(name)
                    || UnifiedGraphFormat.ENTRY_SCHEMA_INDEX.equals(name)
                    ? limits.maxManifestBytes()
                    : limits.maxEntryBytes();
            long declaredSize = entry.getSize();
            if (declaredSize > entryLimit) {
                throw new IOException("Unified-graph entry exceeds size limit: " + name);
            }
        }

        private void bufferRegistered(ZipFile zip, ZipEntry entry) throws IOException {
            if (entry == null || entries.get(entry.getName()) != null) return;
            String name = entry.getName();
            long entryLimit = UnifiedGraphFormat.ENTRY_MANIFEST.equals(name)
                    || UnifiedGraphFormat.ENTRY_SCHEMA_INDEX.equals(name)
                    ? limits.maxManifestBytes()
                    : limits.maxEntryBytes();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] data = readBounded(in, Math.min(entryLimit, budget.remaining()), name);
                budget.consume(data.length, name);
                entries.put(name, data);
            }
        }

        private void register(ZipEntry entry) throws IOException {
            String name = entry.getName();
            if (entries.size() >= limits.maxEntryCount()) {
                throw new IOException("Unified graph has too many entries (limit "
                        + limits.maxEntryCount() + ")");
            }
            if (!names.add(name)) {
                throw new IOException("Duplicate unified-graph entry: " + name);
            }
            if (!caseFoldedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-colliding unified-graph entry: " + name);
            }
            entries.put(name, null);
        }
    }

    private static byte[] readBounded(InputStream in, long limit, String entryName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(limit, 8_192L));
        byte[] buffer = new byte[8_192];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            if (count > limit - read) {
                throw new IOException("Unified-graph entry exceeds size limit: " + entryName);
            }
            out.write(buffer, 0, read);
            count += read;
        }
        return out.toByteArray();
    }

    private static void validateEntryName(String name, boolean directory, Limits limits) throws IOException {
        if (name == null || name.isBlank() || name.length() > limits.maxEntryNameLength()
                || name.startsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe unified-graph entry name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IOException("Unsafe unified-graph entry name");
            }
        }
        String candidate = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        if (candidate.isBlank()) {
            throw new IOException("Unsafe unified-graph entry name: " + name);
        }
        String[] segments = candidate.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || (i == 0 && segment.matches("[A-Za-z]:"))) {
                throw new IOException("Unsafe unified-graph entry name: " + name);
            }
        }
    }

    private static RowSource rowsFromZip(
            ZipFile zip, ZipEntry entry, ExpansionBudget budget, Limits limits) {
        if (entry == null) {
            return consumer -> { };
        }
        return consumer -> {
            try (InputStream in = zip.getInputStream(entry)) {
                readJsonlRows(in, entry.getName(), budget, limits, consumer);
            }
        };
    }

    private static CompactLinkSource linksFromZip(
            ZipFile zip,
            ZipEntry links,
            ZipEntry properties,
            ExpansionBudget budget,
            Limits limits,
            GraphLayout layout) {
        if (links == null) return consumer -> { };
        return consumer -> {
            try (InputStream linkInput = new BudgetInputStream(
                         zip.getInputStream(links), budget, links.getName());
                 InputStream propertyInput = properties == null ? null : new BudgetInputStream(
                         zip.getInputStream(properties), budget, properties.getName())) {
                CompactTopologyCodec.readMaterialized(
                        linkInput,
                        propertyInput,
                        layout.topologyNodeCount(),
                        layout.topologyTypeCount(),
                        layout.relationCount(),
                        limits.maxJsonlRowChars(),
                        limits.maxJsonlRowChars(),
                        consumer::accept);
            }
        };
    }

    private static void readJsonlRows(
            InputStream in,
            String entryName,
            ExpansionBudget budget,
            Limits limits,
            RowConsumer consumer) throws IOException {
        InputStreamReader reader = new InputStreamReader(
                new BudgetInputStream(in, budget, entryName), StandardCharsets.UTF_8);
        char[] chars = new char[8_192];
        StringBuilder row = new StringBuilder(512);
        long lineNumber = 1;
        int read;
        while ((read = reader.read(chars)) != -1) {
            for (int i = 0; i < read; i++) {
                char value = chars[i];
                if (value == '\n') {
                    emitRow(entryName, row, lineNumber++, consumer);
                    row.setLength(0);
                } else {
                    if (row.length() >= limits.maxJsonlRowChars()) {
                        throw new IOException("Unified-graph JSONL row exceeds character limit of "
                                + limits.maxJsonlRowChars() + " in " + entryName
                                + " at line " + lineNumber);
                    }
                    row.append(value);
                }
            }
        }
        if (!row.isEmpty()) {
            emitRow(entryName, row, lineNumber, consumer);
        }
    }

    private static void emitRow(
            String entryName, StringBuilder row, long lineNumber, RowConsumer consumer) throws IOException {
        String value = row.toString();
        if (!value.isBlank()) {
            try {
                consumer.accept(value, lineNumber);
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw malformedRow(entryName, lineNumber, e);
            }
        }
    }

    private static IOException malformedRow(String entryName, long lineNumber, RuntimeException cause) {
        return new IOException("Invalid JSON row in " + entryName + " at line " + lineNumber, cause);
    }

    @FunctionalInterface
    private interface RowSource {
        void forEach(RowConsumer consumer) throws IOException;
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(String row, long lineNumber) throws IOException;
    }

    @FunctionalInterface
    private interface CompactLinkSource {
        void forEach(CompactLinkConsumer consumer) throws IOException;
    }

    @FunctionalInterface
    private interface CompactLinkConsumer {
        void accept(CompactTopologyCodec.LinkRecord link) throws IOException;
    }

    static final class ExpansionBudget {
        private long remaining;

        ExpansionBudget(long limit) {
            this.remaining = limit;
        }

        private long remaining() {
            return remaining;
        }

        private void consume(long bytes, String entryName) throws IOException {
            if (bytes < 0 || bytes > remaining) {
                throw new IOException("Unified graph exceeds total size limit while reading " + entryName);
            }
            remaining -= bytes;
        }
    }

    static final class BudgetInputStream extends InputStream {
        private final InputStream delegate;
        private final ExpansionBudget budget;
        private final String entryName;

        BudgetInputStream(InputStream delegate, ExpansionBudget budget, String entryName) {
            this.delegate = delegate;
            this.budget = budget;
            this.entryName = entryName;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) budget.consume(1, entryName);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            long remaining = budget.remaining();
            int allowed = remaining >= length
                    ? length
                    : (int) Math.min((long) length, remaining + 1L);
            int count = delegate.read(bytes, offset, Math.max(1, allowed));
            if (count > 0) budget.consume(count, entryName);
            return count;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class SchemaFacts {
        private final Set<String> entityTypes = new LinkedHashSet<>();
        private final Set<String> relationTypes = new LinkedHashSet<>();
        private final Set<String> entityAttributeKeys = new LinkedHashSet<>();
        private final Set<String> relationAttributeKeys = new LinkedHashSet<>();

        private void addEntity(String type, Map<String, Object> row) {
            add(type, row, entityTypes, entityAttributeKeys);
        }

        private void addRelation(String type, Map<String, Object> row) {
            add(type, row, relationTypes, relationAttributeKeys);
        }

        private static void add(
                String type, Map<String, Object> row,
                Set<String> types, Set<String> attributeKeys) {
            if (type != null) types.add(type);
            Object attributes = row.get("attributes");
            if (attributes instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key != null) attributeKeys.add(String.valueOf(key));
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reconstruction
    // ═════════════════════════════════════════════════════════════════════════

    private static GraphHeader parseGraphHeader(
            Map<String, byte[]> entries, Set<String> entryNames, Limits limits) throws IOException {
        byte[] manifestBytes = entries.get(UnifiedGraphFormat.ENTRY_MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("Not a unified-graph file: missing " + UnifiedGraphFormat.ENTRY_MANIFEST);
        }
        Map<String, Object> manifest;
        try {
            manifest = MiniJson.parseObject(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid unified-graph manifest JSON", malformed);
        }
        Object format = manifest.get("format");
        if (!UnifiedGraphFormat.FORMAT.equals(format)) {
            throw new IOException("Unexpected graph file format: " + format);
        }
        int version = requiredNonNegativeInt(manifest.get("formatVersion"), "formatVersion");
        if (!UnifiedGraphFormat.supportsRead(version)) {
            throw new IOException("Unsupported unified-graph formatVersion " + version
                    + " (reader supports " + UnifiedGraphFormat.MIN_READABLE_VERSION + ".."
                    + UnifiedGraphFormat.CURRENT_VERSION + ")");
        }
        GraphLayout layout = validateManifestLayout(manifest, entryNames, version, limits);
        if (layout.entityCount() > limits.maxEntityCount()) {
            throw new IOException("Unified graph exceeds entity count limit of " + limits.maxEntityCount());
        }
        if (layout.relationCount() > limits.maxRelationCount()) {
            throw new IOException("Unified graph exceeds relation count limit of " + limits.maxRelationCount());
        }
        return new GraphHeader(manifest, layout, null);
    }

    private static UnifiedGraph reconstruct(
            Map<String, byte[]> entries,
            RowSource entityRows,
            RowSource relationRows,
            CompactLinkSource compactLinks,
            Limits limits,
            GraphHeader header) throws IOException {
        Map<String, Object> manifest = header.manifest();
        GraphLayout layout = header.layout();
        Map<String, Object> schema = header.schema();

        UnifiedGraph graph = new UnifiedGraph();
        SchemaFacts schemaFacts = new SchemaFacts();

        // 1. Vector layers first (needed to attach embeddings while building nodes).
        VectorLayer entityEmbeddings = null;
        VectorLayer relationEmbeddings = null;
        long decodedVectorValues = 0;
        int vectorLayerCount = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(UnifiedGraphFormat.VECTOR_DIR) || !name.endsWith(UnifiedGraphFormat.VECTOR_SUFFIX)) {
                continue;
            }
            long layerValues = VectorBlobCodec.decodedValueCount(e.getValue());
            if (layerValues > limits.maxTotalDecodedVectorValues() - decodedVectorValues) {
                throw new IOException("Unified graph exceeds decoded vector value limit of "
                        + limits.maxTotalDecodedVectorValues());
            }
            VectorLayer layer = readVectorLayer(e.getValue());
            String expectedLayerName = name.substring(
                    UnifiedGraphFormat.VECTOR_DIR.length(),
                    name.length() - UnifiedGraphFormat.VECTOR_SUFFIX.length());
            if (!expectedLayerName.equals(layer.name())) {
                throw new IOException("Vector entry name does not match embedded layer name: " + name);
            }
            VectorSpec spec = layout.vectors().get(name);
            validateVectorSpec(name, layer, spec);
            decodedVectorValues += layerValues;
            vectorLayerCount++;
            if (UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS.equals(layer.name())) {
                entityEmbeddings = layer;
            } else if (UnifiedGraphFormat.PRIMARY_RELATION_VECTORS.equals(layer.name())) {
                relationEmbeddings = layer;
            } else {
                graph.putVectorLayer(layer);
            }
        }

        // 2. Entities. Structural rows are consumed one at a time; no entry-sized byte[]/String/list.
        VectorLayer finalEntityEmbeddings = entityEmbeddings;
        int[] entityRowCount = {0};
        entityRows.forEach((line, lineNumber) -> {
            if (entityRowCount[0] >= layout.entityCount()) {
                throw new IOException("entities.jsonl contains more rows than declared in the manifest");
            }
            try {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                String type = optionalString(m.get("type"), "type",
                        UnifiedGraphFormat.ENTRY_ENTITIES, lineNumber);
                if (id == null || id.isBlank() || graph.entity(id).isPresent()) {
                    throw new IOException("Invalid or duplicate entity id in entities.jsonl: " + id);
                }
                double[] emb = finalEntityEmbeddings == null ? null : finalEntityEmbeddings.get(id);
                Map<String, Object> entityAttributes = attributes(
                        m, UnifiedGraphFormat.ENTRY_ENTITIES, lineNumber);
                mergeExplicitTypeMemberships(entityAttributes, m.get("typeMemberships"), type);
                SimpleGraphEntity entity = new SimpleGraphEntity(
                        id,
                        type,
                        str(m.get("label")),
                        asDouble(m.get("weight"), 1.0),
                        asDouble(m.get("confidence"), 1.0),
                        tags(m.get("tags")),
                        emb,
                        timestamp(m.get("timestamp")),
                        entityAttributes);
                graph.addEntity(entity);
                Opinion op = opinion(m.get("opinion"));
                if (op != null) graph.putEntityOpinion(id, op);
                schemaFacts.addEntity(entity.type(), m);
                entityRowCount[0]++;
            } catch (IllegalArgumentException malformed) {
                throw malformedRow(UnifiedGraphFormat.ENTRY_ENTITIES, lineNumber, malformed);
            }
        });

        // 3. Relations. Duplicate ids are checked against the graph's existing O(1) id index rather
        // than retaining a second million-entry Set solely for validation.
        VectorLayer finalRelationEmbeddings = relationEmbeddings;
        int[] relationRowCount = {0};
        relationRows.forEach((line, lineNumber) -> {
            if (relationRowCount[0] >= layout.relationCount()) {
                throw new IOException("relations.jsonl contains more rows than declared in the manifest");
            }
            try {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                String sourceId = str(m.get("sourceId"));
                String targetId = str(m.get("targetId"));
                String type = optionalString(m.get("type"), "type",
                        UnifiedGraphFormat.ENTRY_RELATIONS, lineNumber);
                if (id == null || id.isBlank() || sourceId == null || targetId == null
                        || graph.relation(id).isPresent()) {
                    throw new IOException("Invalid or duplicate relation row in relations.jsonl");
                }
                double[] emb = finalRelationEmbeddings == null ? null : finalRelationEmbeddings.get(id);
                SimpleGraphRelation relation = new SimpleGraphRelation(
                        id,
                        sourceId,
                        targetId,
                        type,
                        asDouble(m.get("weight"), 1.0),
                        asDouble(m.get("confidence"), 1.0),
                        asBoolean(m.get("directed"), true),
                        tags(m.get("tags")),
                        emb,
                        timestamp(m.get("timestamp")),
                        attributes(m, UnifiedGraphFormat.ENTRY_RELATIONS, lineNumber));
                graph.addRelation(relation);
                Opinion op = opinion(m.get("opinion"));
                if (op != null) graph.putRelationOpinion(id, op);
                schemaFacts.addRelation(relation.type(), m);
                relationRowCount[0]++;
            } catch (IllegalArgumentException malformed) {
                throw malformedRow(UnifiedGraphFormat.ENTRY_RELATIONS, lineNumber, malformed);
            }
        });
        compactLinks.forEach(link -> {
            if (relationRowCount[0] >= layout.relationCount()) {
                throw new IOException("Compact topology contains more links than declared in the manifest");
            }
            CompactTopologyCodec.CoreLink core = link.core();
            CompactTopologyCodec.PropertyData property = link.property();
            if (graph.relation(core.id()).isPresent()) {
                throw new IOException("Duplicate relation id in compact topology: " + core.id());
            }
            double[] embedding = finalRelationEmbeddings == null
                    ? null : finalRelationEmbeddings.get(core.id());
            SimpleGraphRelation relation = new SimpleGraphRelation(
                    core.id(), core.sourceId(), core.targetId(), core.type(), core.weight(),
                    core.confidence(), core.directed(), property.tags(), embedding,
                    property.timestamp(), property.attributes());
            graph.addRelation(relation);
            if (property.opinion() != null) graph.putRelationOpinion(core.id(), property.opinion());
            Map<String, Object> schemaRow = new LinkedHashMap<>();
            if (!property.attributes().isEmpty()) schemaRow.put("attributes", property.attributes());
            schemaFacts.addRelation(relation.type(), schemaRow);
            relationRowCount[0]++;
        });

        // 4. Optional orphan opinions keyed to ids not present in topology rows.
        byte[] opinionBytes = entries.get(UnifiedGraphFormat.ENTRY_OPINIONS);
        if (opinionBytes != null) {
            for (String line : lines(opinionBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String kind = str(m.get("kind"));
                String id = str(m.get("id"));
                Opinion op = opinion(m.get("opinion"));
                if (id == null || op == null) continue;
                if ("entity".equals(kind)) {
                    graph.putEntityOpinion(id, op);
                } else if ("relation".equals(kind)) {
                    graph.putRelationOpinion(id, op);
                }
            }
        }

        // 5. Named weight maps.
        byte[] weightBytes = entries.get(UnifiedGraphFormat.ENTRY_WEIGHTS);
        if (weightBytes != null) {
            Map<String, Object> weights = MiniJson.parseObject(new String(weightBytes, StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : weights.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> inner) {
                    Map<String, Double> map = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> w : inner.entrySet()) {
                        map.put(String.valueOf(w.getKey()), asDouble(w.getValue(), 0.0));
                    }
                    graph.putWeightMap(e.getKey(), map);
                }
            }
        }

        // 6. Graph-level meta.
        Object meta = manifest.get("meta");
        if (meta instanceof Map<?, ?> metaMap) {
            for (Map.Entry<?, ?> e : metaMap.entrySet()) {
                graph.meta(String.valueOf(e.getKey()), e.getValue());
            }
        }

        // 7. Bundled model artifacts (any serialized model attached under models/).
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.startsWith(UnifiedGraphFormat.MODELS_DIR) && !name.endsWith("/")) {
                graph.putArtifact(name.substring(UnifiedGraphFormat.MODELS_DIR.length()), e.getValue());
            }
        }

        if (graph.entityCount() != layout.entityCount()) {
            throw new IOException("Unified-graph entity count mismatch: manifest="
                    + layout.entityCount() + ", loaded=" + graph.entityCount());
        }
        if (graph.relationCount() != layout.relationCount()) {
            throw new IOException("Unified-graph relation count mismatch: manifest="
                    + layout.relationCount() + ", loaded=" + graph.relationCount());
        }
        if (vectorLayerCount != layout.vectorCount()) {
            throw new IOException("Unified-graph vector-layer count mismatch: manifest="
                    + layout.vectorCount() + ", loaded=" + vectorLayerCount);
        }
        int loadedEmbeddingDim = entityEmbeddings == null ? 0 : entityEmbeddings.dim();
        if (loadedEmbeddingDim != layout.embeddingDim()) {
            throw new IOException("Unified-graph embedding dimension mismatch: manifest="
                    + layout.embeddingDim() + ", loaded=" + loadedEmbeddingDim);
        }

        if (schema != null) {
            validateSchemaIndex(schema, graph, schemaFacts);
        }

        return graph;
    }

    private static GraphLayout validateManifestLayout(
            Map<String, Object> manifest, Set<String> entryNames, int formatVersion, Limits limits)
            throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(UnifiedGraphFormat.ENTRY_MANIFEST);

        Object rawSections = manifest.get("sections");
        if (!(rawSections instanceof List<?> sections)) {
            throw new IOException("Unified-graph manifest sections must be an array");
        }
        Set<String> structural = new LinkedHashSet<>();
        Set<String> allowedSections = Set.of(
                UnifiedGraphFormat.ENTRY_SCHEMA_INDEX,
                UnifiedGraphFormat.ENTRY_ENTITIES,
                UnifiedGraphFormat.ENTRY_RELATIONS,
                UnifiedGraphFormat.ENTRY_COMPACT_LINKS,
                UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY,
                UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES,
                UnifiedGraphFormat.ENTRY_WEIGHTS,
                UnifiedGraphFormat.ENTRY_OPINIONS);
        for (Object raw : sections) {
            String section = requiredString(raw, "sections[]");
            if (!allowedSections.contains(section) || !structural.add(section)) {
                throw new IOException("Invalid or duplicate unified-graph section: " + section);
            }
            expected.add(section);
        }
        if (!structural.contains(UnifiedGraphFormat.ENTRY_ENTITIES)) {
            throw new IOException("Unified-graph manifest must declare entities.jsonl");
        }
        boolean compact = formatVersion >= 3;
        if (compact) {
            if (!structural.contains(UnifiedGraphFormat.ENTRY_COMPACT_LINKS)
                    || structural.contains(UnifiedGraphFormat.ENTRY_RELATIONS)) {
                throw new IOException("Unified-graph v3 must declare compact links and omit relations.jsonl");
            }
        } else if (!structural.contains(UnifiedGraphFormat.ENTRY_RELATIONS)
                || structural.contains(UnifiedGraphFormat.ENTRY_COMPACT_LINKS)
                || structural.contains(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES)) {
            throw new IOException("Unified-graph v1/v2 must declare entities.jsonl and relations.jsonl");
        }
        if (formatVersion >= 2 && !structural.contains(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX)) {
            throw new IOException("Unified-graph v2+ manifest must declare schemas/index.json");
        }

        Object rawArtifacts = manifest.get("artifacts");
        if (rawArtifacts != null) {
            if (!(rawArtifacts instanceof List<?> artifacts)) {
                throw new IOException("Unified-graph manifest artifacts must be an array");
            }
            for (Object raw : artifacts) {
                String artifact = requiredString(raw, "artifacts[]");
                String entry = UnifiedGraphFormat.modelEntry(artifact);
                validateEntryName(entry, false, limits);
                if (!expected.add(entry)) {
                    throw new IOException("Duplicate unified-graph artifact: " + artifact);
                }
            }
        }

        Object rawLayers = manifest.get("vectorLayers");
        if (!(rawLayers instanceof List<?> layers)) {
            throw new IOException("Unified-graph manifest vectorLayers must be an array");
        }
        Map<String, VectorSpec> vectors = new LinkedHashMap<>();
        for (Object raw : layers) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new IOException("Unified-graph vector layer descriptor must be an object");
            }
            String name = requiredString(item.get("name"), "vectorLayers[].name");
            String entry = requiredString(item.get("entry"), "vectorLayers[].entry");
            String expectedEntry = UnifiedGraphFormat.vectorEntry(name);
            if (!expectedEntry.equals(entry)) {
                throw new IOException("Vector layer entry does not match its name: " + name);
            }
            validateEntryName(entry, false, limits);
            VectorLayer.Target target;
            Dtype dtype;
            try {
                target = VectorLayer.Target.valueOf(requiredString(
                        item.get("target"), "vectorLayers[].target"));
                dtype = Dtype.valueOf(requiredString(item.get("dtype"), "vectorLayers[].dtype"));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid vector layer target or dtype for " + name, e);
            }
            int dim = requiredNonNegativeInt(item.get("dim"), "vectorLayers[].dim");
            int count = requiredNonNegativeInt(item.get("count"), "vectorLayers[].count");
            if (vectors.putIfAbsent(entry, new VectorSpec(name, target, dtype, dim, count)) != null
                    || !expected.add(entry)) {
                throw new IOException("Duplicate vector layer entry: " + entry);
            }
        }

        if (!entryNames.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(entryNames);
            Set<String> unexpected = new LinkedHashSet<>(entryNames);
            unexpected.removeAll(expected);
            throw new IOException("Unified-graph manifest inventory mismatch; missing="
                    + missing + ", unexpected=" + unexpected);
        }

        Object rawCounts = manifest.get("counts");
        if (!(rawCounts instanceof Map<?, ?> counts)) {
            throw new IOException("Unified-graph manifest counts must be an object");
        }
        int entityCount = requiredNonNegativeInt(counts.get("entities"), "counts.entities");
        int relationCount = requiredNonNegativeInt(counts.get("relations"), "counts.relations");
        int vectorCount = requiredNonNegativeInt(counts.get("vectorLayers"), "counts.vectorLayers");
        if (vectorCount != vectors.size()) {
            throw new IOException("Manifest vector-layer count does not match its inventory");
        }
        int embeddingDim = requiredNonNegativeInt(manifest.get("embeddingDim"), "embeddingDim");
        int topologyNodeCount = 0;
        int topologyTypeCount = 0;
        if (compact) {
            Object rawTopology = manifest.get("topology");
            if (!(rawTopology instanceof Map<?, ?> topology)) {
                throw new IOException("Unified-graph v3 manifest topology must be an object");
            }
            if (!UnifiedGraphFormat.COMPACT_TOPOLOGY_ENCODING.equals(
                    requiredString(topology.get("encoding"), "topology.encoding"))
                    || requiredNonNegativeInt(topology.get("encodingVersion"),
                    "topology.encodingVersion") != UnifiedGraphFormat.COMPACT_TOPOLOGY_VERSION
                    || !UnifiedGraphFormat.ENTRY_COMPACT_LINKS.equals(
                    requiredString(topology.get("entry"), "topology.entry"))) {
                throw new IOException("Unsupported unified-graph compact topology descriptor");
            }
            topologyNodeCount = requiredNonNegativeInt(topology.get("nodeCount"), "topology.nodeCount");
            topologyTypeCount = requiredNonNegativeInt(
                    topology.get("relationTypeCount"), "topology.relationTypeCount");
            int linkCount = requiredNonNegativeInt(topology.get("linkCount"), "topology.linkCount");
            if (linkCount != relationCount) {
                throw new IOException("Compact topology link count does not match counts.relations");
            }
            int maxTopologyNodes = Math.max(1, Integer.getInteger(
                    "kompile.graph.maxTopologyNodes", limits.maxEntityCount()));
            int maxTopologyTypes = Math.max(1, Integer.getInteger(
                    "kompile.graph.maxTopologyTypes", 100_000));
            long maximumEndpointCount = Math.min((long) maxTopologyNodes, 2L * relationCount);
            if (topologyNodeCount > maximumEndpointCount) {
                throw new IOException("Compact topology node dictionary exceeds its resource limit");
            }
            if (topologyTypeCount > Math.min(maxTopologyTypes, relationCount)) {
                throw new IOException("Compact topology relation-type dictionary exceeds its resource limit");
            }
            Object propertiesEntry = topology.get("propertiesEntry");
            boolean hasProperties = structural.contains(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
            if (hasProperties != (propertiesEntry != null)
                    || (propertiesEntry != null && !UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES.equals(
                    requiredString(propertiesEntry, "topology.propertiesEntry")))) {
                throw new IOException("Compact topology property inventory mismatch");
            }
            Object adjacencyEntry = topology.get("adjacencyEntry");
            boolean hasAdjacency = structural.contains(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
            if (hasAdjacency != (adjacencyEntry != null)) {
                throw new IOException("Compact topology adjacency inventory mismatch");
            }
            if (adjacencyEntry != null
                    && (!UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(
                    requiredString(adjacencyEntry, "topology.adjacencyEntry"))
                    || requiredNonNegativeInt(topology.get("adjacencyEncodingVersion"),
                    "topology.adjacencyEncodingVersion") != CompactAdjacencyCodec.VERSION
                    || requiredNonNegativeInt(topology.get("adjacencyEntries"),
                    "topology.adjacencyEntries") < relationCount)) {
                throw new IOException("Unsupported compact adjacency descriptor");
            }
        } else if (manifest.get("topology") != null) {
            throw new IOException("Legacy unified-graph manifest must not declare compact topology");
        }
        return new GraphLayout(entityCount, relationCount, vectorCount, embeddingDim, vectors,
                formatVersion, topologyNodeCount, topologyTypeCount);
    }

    private static Map<String, Object> parseSchemaIndex(byte[] bytes, GraphLayout layout) throws IOException {
        Map<String, Object> schema;
        try {
            schema = MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid unified-graph schema index JSON", malformed);
        }
        if (!"kompile-unified-schema".equals(schema.get("format"))) {
            throw new IOException("Invalid unified-graph schema index format");
        }
        if (requiredNonNegativeInt(schema.get("version"), "schemas/index.json.version") != 1) {
            throw new IOException("Unsupported unified-graph schema index version");
        }
        if (requiredNonNegativeInt(schema.get("entityCount"), "schemas/index.json.entityCount")
                != layout.entityCount()
                || requiredNonNegativeInt(schema.get("relationCount"), "schemas/index.json.relationCount")
                != layout.relationCount()) {
            throw new IOException("Unified-graph schema index count mismatch");
        }
        declaredSchemaSet(schema, "entityTypes");
        declaredSchemaSet(schema, "relationTypes");
        declaredSchemaSet(schema, "entityAttributeKeys");
        declaredSchemaSet(schema, "relationAttributeKeys");
        declaredSchemaSet(schema, "declaredSchemaArtifacts");
        return schema;
    }

    private static void validateSchemaIndex(
            Map<String, Object> schema, UnifiedGraph graph, SchemaFacts facts) throws IOException {

        Set<String> schemaArtifacts = graph.artifacts().keySet().stream()
                .filter(name -> name.startsWith("schema/"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        validateSchemaSet(schema, "entityTypes", facts.entityTypes);
        validateSchemaSet(schema, "relationTypes", facts.relationTypes);
        validateSchemaSet(schema, "entityAttributeKeys", facts.entityAttributeKeys);
        validateSchemaSet(schema, "relationAttributeKeys", facts.relationAttributeKeys);
        validateSchemaSet(schema, "declaredSchemaArtifacts", schemaArtifacts);
    }

    private static void validateSchemaSet(
            Map<String, Object> schema, String field, Set<String> actual) throws IOException {
        Set<String> declared = declaredSchemaSet(schema, field);
        if (!declared.equals(actual)) {
            throw new IOException("Unified-graph schema index mismatch for " + field);
        }
    }

    private static Set<String> declaredSchemaSet(
            Map<String, Object> schema, String field) throws IOException {
        Object value = schema.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IOException("Unified-graph schema index field " + field + " must be an array");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String string) || !declared.add(string)) {
                throw new IOException("Invalid or duplicate unified-graph schema index value in " + field);
            }
        }
        return declared;
    }

    private static void validateVectorSpec(String entry, VectorLayer layer, VectorSpec spec)
            throws IOException {
        if (spec == null || !spec.name().equals(layer.name()) || spec.target() != layer.target()
                || spec.dtype() != layer.dtype() || spec.dim() != layer.dim()
                || spec.count() != layer.size()) {
            throw new IOException("Vector layer metadata mismatch for " + entry);
        }
    }

    private static String requiredString(Object value, String field) throws IOException {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException("Unified-graph manifest field " + field + " must be a non-empty string");
        }
        return string;
    }

    private static int requiredNonNegativeInt(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("Unified-graph manifest field " + field + " must be an integer");
        }
        long result = number.longValue();
        double exact = number.doubleValue();
        if (!Double.isFinite(exact) || exact != result || result < 0 || result > Integer.MAX_VALUE) {
            throw new IOException("Unified-graph manifest field " + field
                    + " must be a non-negative integer");
        }
        return (int) result;
    }

    private record VectorSpec(
            String name, VectorLayer.Target target, Dtype dtype, int dim, int count) {}

    private record GraphLayout(
            int entityCount, int relationCount, int vectorCount, int embeddingDim,
            Map<String, VectorSpec> vectors, int formatVersion,
            int topologyNodeCount, int topologyTypeCount) {}

    private record GraphHeader(
            Map<String, Object> manifest, GraphLayout layout, Map<String, Object> schema) {}

    private static VectorLayer readVectorLayer(byte[] blob) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            VectorLayer layer = VectorBlobCodec.read(in);
            if (in.available() != 0) {
                throw new IOException("Trailing data in vector layer " + layer.name());
            }
            return layer;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Field decoders
    // ═════════════════════════════════════════════════════════════════════════

    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String optionalString(
            Object value, String field, String entryName, long lineNumber) throws IOException {
        if (value == null) return null;
        if (value instanceof String string) return string;
        throw new IOException("Unified-graph field " + field + " must be a string in "
                + entryName + " at line " + lineNumber);
    }

    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            switch (s) {
                case "NaN": return Double.NaN;
                case "Infinity": return Double.POSITIVE_INFINITY;
                case "-Infinity": return Double.NEGATIVE_INFINITY;
                default:
                    try { return Double.parseDouble(s); }
                    catch (NumberFormatException e) { return dflt; }
            }
        }
        return dflt;
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }

    private static boolean asBoolean(Object v, boolean dflt) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return dflt;
    }

    private static Set<String> tags(Object v) {
        if (!(v instanceof List<?> list)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** Build a non-null attributes map without silently discarding malformed archive data. */
    private static Map<String, Object> attributes(
            Map<String, Object> row, String entryName, long lineNumber) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!row.containsKey("attributes")) return out;
        Object value = row.get("attributes");
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException("Unified-graph attributes must be an object in "
                    + entryName + " at line " + lineNumber);
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IOException("Unified-graph attributes must not contain null keys or values in "
                        + entryName + " at line " + lineNumber);
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static void mergeExplicitTypeMemberships(
            Map<String, Object> attributes, Object rawMemberships, String primaryType) throws IOException {
        if (rawMemberships == null) {
            return;
        }
        if (!(rawMemberships instanceof List<?> memberships)) {
            throw new IOException("Entity typeMemberships must be an array");
        }
        Set<String> additional = new LinkedHashSet<>();
        Object existing = attributes.get("additionalTypes");
        if (existing instanceof List<?> existingTypes) {
            for (Object item : existingTypes) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    additional.add(String.valueOf(item));
                }
            }
        }
        for (Object item : memberships) {
            if (!(item instanceof String membership) || membership.isBlank()) {
                throw new IOException("Entity typeMemberships must contain non-empty strings");
            }
            if (!membership.equals(primaryType)) {
                additional.add(membership);
            }
        }
        if (!additional.isEmpty()) {
            attributes.put("additionalTypes", List.copyOf(additional));
        }
    }

    private static Instant timestamp(Object v) {
        if (!(v instanceof String s) || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Opinion opinion(Object v) {
        if (!(v instanceof Map<?, ?> map)) return null;
        double b = asDouble(map.get("b"), Double.NaN);
        double d = asDouble(map.get("d"), Double.NaN);
        double u = asDouble(map.get("u"), Double.NaN);
        double a = asDouble(map.get("a"), 0.5);
        if (Double.isNaN(b) || Double.isNaN(d) || Double.isNaN(u)) return null;
        return new Opinion(b, d, u, a);
    }
}
