package ai.kompile.chat.local.android.graph;

import ai.kompile.graph.reasoning.unified.MiniJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Performs bounded, non-native validation of a selected .kgraph container.
 *
 * <p>Full graph deserialization remains the responsibility of the AOT graph runtime after a
 * SameDiff model is active. This preflight deliberately avoids loading JavaCPP or opening a native
 * graph session during first-run setup. It reuses the graph library's strict {@link MiniJson}
 * parser so the manifest grammar stays aligned with {@code UnifiedGraph.load}.</p>
 */
public final class KgraphArtifactValidator {
    public static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    static final int MAX_ENTRIES = 4096;
    private static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final String MANIFEST = "manifest.json";
    private static final String SCHEMA_INDEX = "schemas/index.json";
    private static final String ENTITIES = "entities.jsonl";
    private static final String RELATIONS = "relations.jsonl";
    private static final String[] REQUIRED_ENTRIES = {MANIFEST, ENTITIES, RELATIONS};

    private KgraphArtifactValidator() {
    }

    public static void validate(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("The selected graph is unavailable");
        }
        long archiveBytes = Files.size(path);
        if (archiveBytes == 0L || archiveBytes > MAX_ARCHIVE_BYTES) {
            throw new IOException("Invalid .kgraph: archive must be between 1 byte and 512 MiB");
        }

        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<ZipEntry> entries = inspectEntries(zip);
            int formatVersion = validateManifest(zip.getEntry(MANIFEST), zip);
            if (formatVersion >= 2) {
                ZipEntry schema = zip.getEntry(SCHEMA_INDEX);
                if (schema == null || schema.isDirectory()) {
                    throw new IOException("Invalid .kgraph v2: missing " + SCHEMA_INDEX);
                }
            }
            validatePayloadBounds(entries, zip);
        } catch (ZipException malformed) {
            throw new IOException("Invalid .kgraph: not a readable graph container", malformed);
        }
    }

    private static List<ZipEntry> inspectEntries(ZipFile zip) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> required = new HashSet<>();
        long totalDeclaredBytes = 0L;

        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (entries.size() >= MAX_ENTRIES) {
                throw new IOException("Invalid .kgraph: archive contains more than "
                        + MAX_ENTRIES + " entries");
            }
            entries.add(entry);

            String name = entry.getName();
            validateEntryName(name, entry.isDirectory());
            if (!names.add(name)) {
                throw new IOException("Invalid .kgraph: duplicate entry " + name);
            }
            for (String expected : REQUIRED_ENTRIES) {
                if (expected.equals(name)) {
                    required.add(expected);
                }
            }

            if (!entry.isDirectory()) {
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size < 0L || compressedSize < 0L) {
                    throw new IOException("Invalid .kgraph: entry size is unavailable for " + name);
                }
                if (size > MAX_ENTRY_UNCOMPRESSED_BYTES) {
                    throw new IOException("Invalid .kgraph: entry exceeds 512 MiB: " + name);
                }
                if (size > MAX_TOTAL_UNCOMPRESSED_BYTES - totalDeclaredBytes) {
                    throw new IOException("Invalid .kgraph: expanded archive exceeds 1 GiB");
                }
                totalDeclaredBytes += size;
            }
        }

        for (String expected : REQUIRED_ENTRIES) {
            if (!required.contains(expected)) {
                throw new IOException("Invalid .kgraph: missing " + expected);
            }
            ZipEntry entry = zip.getEntry(expected);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Invalid .kgraph: missing " + expected);
            }
        }
        return entries;
    }

    private static void validateEntryName(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid .kgraph: unsafe ZIP entry name");
        }
        String[] segments = name.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectorySeparator =
                    directory && index == segments.length - 1 && segment.isEmpty();
            if (!trailingDirectorySeparator
                    && (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))) {
                throw new IOException("Invalid .kgraph: unsafe ZIP entry name " + name);
            }
        }
    }

    private static int validateManifest(ZipEntry manifest, ZipFile zip) throws IOException {
        String manifestJson;
        try (InputStream input = zip.getInputStream(manifest)) {
            manifestJson = new String(readBounded(input, MAX_MANIFEST_BYTES, MANIFEST),
                    StandardCharsets.UTF_8);
        }

        final Map<String, Object> root;
        try {
            root = MiniJson.parseObject(manifestJson);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid .kgraph: malformed manifest.json", malformed);
        }

        Object format = root.get("format");
        if (!(format instanceof String) || !"kompile-graph".equals(format)) {
            throw new IOException("Invalid .kgraph: unsupported manifest format");
        }
        Object formatVersion = root.get("formatVersion");
        if (!(formatVersion instanceof Long)
                || (((Long) formatVersion).longValue() != 1L
                && ((Long) formatVersion).longValue() != 2L)) {
            throw new IOException("Invalid .kgraph: unsupported formatVersion");
        }
        return ((Long) formatVersion).intValue();
    }

    private static void validatePayloadBounds(List<ZipEntry> entries, ZipFile zip)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long totalActualBytes = 0L;

        for (ZipEntry entry : entries) {
            if (entry.isDirectory()) {
                continue;
            }
            long entryActualBytes = 0L;
            try (InputStream input = zip.getInputStream(entry)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > MAX_ENTRY_UNCOMPRESSED_BYTES - entryActualBytes) {
                        throw new IOException("Invalid .kgraph: expanded entry exceeds 512 MiB: "
                                + entry.getName());
                    }
                    if (count > MAX_TOTAL_UNCOMPRESSED_BYTES - totalActualBytes) {
                        throw new IOException("Invalid .kgraph: expanded archive exceeds 1 GiB");
                    }
                    entryActualBytes += count;
                    totalActualBytes += count;
                }
            }
            if (entryActualBytes != entry.getSize()) {
                throw new IOException("Invalid .kgraph: entry size mismatch for " + entry.getName());
            }
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes, String name)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maxBytes - total) {
                throw new IOException("Invalid .kgraph: " + name + " exceeds " + maxBytes
                        + " bytes");
            }
            total += count;
            output.write(buffer, 0, count);
        }
        if (total == 0) {
            throw new IOException("Invalid .kgraph: " + name + " is empty");
        }
        return output.toByteArray();
    }
}
