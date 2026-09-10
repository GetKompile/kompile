/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Deterministic and traversal-safe {@code .kspin} archive support. */
public final class SpinArchive {
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_ENTRIES = Integer.getInteger(
            "kompile.spin.maxArchiveEntries", 100_000);
    private static final long MAX_EXTRACTED_BYTES = Long.getLong(
            "kompile.spin.maxExtractedBytes", 128L * 1024L * 1024L * 1024L);
    private static final long MAX_CHECKSUM_MANIFEST_BYTES = 32L * 1024L * 1024L;
    private static final Set<String> STORED_EXTENSIONS = Set.of(
            ".gguf", ".ggml", ".sdz", ".jar", ".zip", ".gz", ".xz", ".zst",
            ".png", ".jpg", ".jpeg", ".mp3", ".mp4", ".onnx", ".safetensors");

    private SpinArchive() { }

    /**
     * Build a deterministic archive. An embedded runtime is copied under
     * {@code runtime/}; thin spins leave it null and resolve the user's Kompile.
     */
    public static BuildResult build(Path source, Path output, Path embeddedRuntime)
            throws IOException {
        Path root = requireDirectory(source, "spin source");
        Path target = output.toAbsolutePath().normalize();
        if (target.startsWith(root)) {
            throw new IOException("Output archive must not be inside the spin source: " + target);
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());

        Path runtime = embeddedRuntime == null ? null
                : requireDirectory(embeddedRuntime, "embedded runtime");
        SpinDefinition definition = SpinDefinition.load(root, runtime != null);
        if (runtime != null && !"embedded".equals(definition.delivery())) {
            throw new IOException("--runtime-dir requires runtime.delivery: embedded");
        }
        if (runtime != null && !SpinDefinition.hasRuntimeDistribution(
                runtime, definition.requiresLocalModelRuntime())) {
            throw new IOException(definition.requiresLocalModelRuntime()
                    ? "Embedded runtime is missing Kompile, kompile-agent, or model-serving"
                    : "Embedded runtime must contain Kompile and kompile-agent launchers");
        }

        Map<String, Path> payload = payloadFiles(root, "");
        if (runtime != null) {
            if (payload.keySet().stream().anyMatch(name -> name.startsWith("runtime/"))) {
                throw new IOException("Spin source already contains runtime/; do not also pass --runtime-dir");
            }
            payload.putAll(payloadFiles(runtime, "runtime/"));
        }
        Map<String, PayloadFile> inspectedPayload = inspectPayload(payload);
        String checksums = checksumContent(inspectedPayload);
        Path temporary = target.resolveSibling("." + target.getFileName() + ".tmp-"
                + UUID.randomUUID());
        Files.deleteIfExists(temporary);
        try {
            writeZip(inspectedPayload, checksums, temporary);
            moveAtomic(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new BuildResult(target, definition.id(), definition.version(),
                HashUtils.sha256Hex(target), Files.size(target));
    }

    /** Read only enough trusted metadata to choose an installation root. */
    public static Identity readIdentity(Path archive) throws IOException {
        Path source = requireFile(archive, "spin archive");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(SpinDefinition.MANIFEST);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Spin archive is missing " + SpinDefinition.MANIFEST);
            }
            if (entry.getSize() > 1024L * 1024L) {
                throw new IOException(SpinDefinition.MANIFEST + " exceeds 1048576 bytes");
            }
            JsonNode manifest;
            try (InputStream input = limited(zip.getInputStream(entry), 1024L * 1024L)) {
                manifest = new ObjectMapper(new YAMLFactory()).readTree(input);
            }
            if (manifest == null || !manifest.isObject()) {
                throw new IOException(SpinDefinition.MANIFEST + " must contain an object");
            }
            String id = manifest.path("metadata").path("id").asText(
                    manifest.path("metadata").path("name").asText("")).trim();
            String version = manifest.path("metadata").path("version").asText("").trim();
            if (!id.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IOException("Invalid spin metadata.id: " + id);
            }
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")) {
                throw new IOException("Invalid spin metadata.version: " + version);
            }
            ZipEntry inventoryEntry = zip.getEntry(SpinDefinition.CHECKSUM_MANIFEST);
            if (inventoryEntry == null || inventoryEntry.isDirectory()) {
                throw new IOException("Spin archive is missing " + SpinDefinition.CHECKSUM_MANIFEST);
            }
            byte[] inventory;
            try (InputStream input = limited(zip.getInputStream(inventoryEntry),
                    MAX_CHECKSUM_MANIFEST_BYTES)) {
                inventory = input.readAllBytes();
            }
            return new Identity(id, version, HashUtils.sha256Hex(inventory));
        }
    }

    /** Extract and verify a complete archive inventory into a new directory. */
    public static SpinDefinition extractVerified(Path archive, Path target) throws IOException {
        Path source = requireFile(archive, "spin archive");
        Path destination = target.toAbsolutePath().normalize();
        rejectSymlinkComponents(destination, "spin extraction target");
        if (Files.exists(destination)) {
            try (var existing = Files.list(destination)) {
                if (existing.findAny().isPresent()) {
                    throw new IOException("Extraction target is not empty: " + destination);
                }
            }
        }
        Files.createDirectories(destination);

        long total = 0L;
        int count = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("Spin archive exceeds " + MAX_ENTRIES + " entries");
                }
                String name = safeEntryName(entry.getName());
                if (!seen.add(name)) throw new IOException("Duplicate spin archive entry: " + name);
                Path output = destination.resolve(name).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("Unsafe spin archive entry: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                long declared = entry.getSize();
                if (declared > 0 && (total > MAX_EXTRACTED_BYTES - declared)) {
                    throw new IOException("Spin archive exceeds extracted byte limit " + MAX_EXTRACTED_BYTES);
                }
                Files.createDirectories(output.getParent());
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     var sink = new BufferedOutputStream(Files.newOutputStream(output))) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        total += read;
                        if (total > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Spin archive exceeds extracted byte limit "
                                    + MAX_EXTRACTED_BYTES);
                        }
                        sink.write(buffer, 0, read);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            deleteTree(destination);
            throw failure;
        }

        verifyDirectory(destination);
        return SpinDefinition.load(destination);
    }

    /** Verify checksums and complete inventory for an extracted installed release. */
    public static void verifyDirectory(Path root) throws IOException {
        Path directory = requireDirectory(root, "spin release");
        Path manifest = directory.resolve(SpinDefinition.CHECKSUM_MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Spin release is missing " + SpinDefinition.CHECKSUM_MANIFEST);
        }
        Map<String, String> expected = parseChecksumManifest(manifest);
        List<Path> actualFiles = listFiles(directory).stream()
                .filter(path -> !path.equals(manifest))
                .toList();
        Set<String> actualNames = new HashSet<>();
        for (Path file : actualFiles) {
            String name = relativeName(directory, file);
            actualNames.add(name);
            String expectedHash = expected.get(name);
            if (expectedHash == null) {
                throw new IOException("Unlisted file in spin release: " + name);
            }
            String actualHash = HashUtils.sha256Hex(file);
            if (!actualHash.equals(expectedHash)) {
                throw new IOException("Checksum mismatch for spin file: " + name);
            }
        }
        Set<String> missing = new HashSet<>(expected.keySet());
        missing.removeAll(actualNames);
        if (!missing.isEmpty()) {
            throw new IOException("Spin release is missing checksummed files: " + missing);
        }
    }

    private static String checksumContent(Map<String, PayloadFile> payload) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, PayloadFile> entry : payload.entrySet()) {
            content.append(entry.getValue().sha256()).append("  ")
                    .append(entry.getKey()).append('\n');
        }
        return content.toString();
    }

    private static Map<String, String> parseChecksumManifest(Path manifest) throws IOException {
        if (Files.size(manifest) > MAX_CHECKSUM_MANIFEST_BYTES) {
            throw new IOException("Spin checksum manifest exceeds "
                    + MAX_CHECKSUM_MANIFEST_BYTES + " bytes");
        }
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (++lines > MAX_ENTRIES) {
                    throw new IOException("Spin checksum manifest exceeds "
                            + MAX_ENTRIES + " entries");
                }
                if (line.length() < 67 || line.charAt(64) != ' ' || line.charAt(65) != ' ') {
                    throw new IOException("Invalid checksum manifest line: " + line);
                }
                String hash = line.substring(0, 64).toLowerCase(Locale.ROOT);
                String name = safeEntryName(line.substring(66));
                if (!hash.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid checksum for " + name);
                }
                if (SpinDefinition.CHECKSUM_MANIFEST.equals(name)
                        || result.putIfAbsent(name, hash) != null) {
                    throw new IOException("Duplicate or recursive checksum entry: " + name);
                }
            }
        }
        if (result.isEmpty()) throw new IOException("Spin checksum manifest is empty");
        return result;
    }

    private static void writeZip(Map<String, PayloadFile> payload, String checksums, Path output)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output)))) {
            zip.setLevel(1);
            for (Map.Entry<String, PayloadFile> entry : payload.entrySet()) {
                putFile(zip, entry.getKey(), entry.getValue());
            }
            putBytes(zip, SpinDefinition.CHECKSUM_MANIFEST,
                    checksums.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void putFile(ZipOutputStream zip, String name, PayloadFile file)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        if (file.stored()) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(file.size());
            entry.setCompressedSize(file.size());
            entry.setCrc(file.crc());
        }
        zip.putNextEntry(entry);
        MessageDigest digest = HashUtils.newSha256Digest();
        long copied = 0L;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file.path()))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
                copied += read;
            }
        }
        zip.closeEntry();
        String copiedHash = HashUtils.toHex(digest.digest());
        if (copied != file.size() || !copiedHash.equals(file.sha256())) {
            throw new IOException("Spin payload changed while it was being packaged: " + file.path());
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static boolean shouldStore(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : STORED_EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    private static Map<String, PayloadFile> inspectPayload(Map<String, Path> payload)
            throws IOException {
        Map<String, PayloadFile> result = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : payload.entrySet()) {
            result.put(entry.getKey(), inspectFile(entry.getValue()));
        }
        return result;
    }

    private static PayloadFile inspectFile(Path file) throws IOException {
        MessageDigest digest = HashUtils.newSha256Digest();
        CRC32 crc = new CRC32();
        long size = 0L;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                crc.update(buffer, 0, read);
                size += read;
            }
        }
        return new PayloadFile(file, HashUtils.toHex(digest.digest()),
                size, crc.getValue(), shouldStore(file));
    }

    private static Map<String, Path> payloadFiles(Path root, String prefix)
            throws IOException {
        Map<String, Path> payload = new LinkedHashMap<>();
        for (Path file : listFiles(root)) {
            String relative = relativeName(root, file);
            if (SpinDefinition.CHECKSUM_MANIFEST.equals(relative)) continue;
            String name = prefix + relative;
            if (payload.putIfAbsent(name, file) != null) {
                throw new IOException("Duplicate spin payload path: " + name);
            }
        }
        return payload;
    }

    private static List<Path> listFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    throw new IOException("Symbolic links are not allowed in spins: " + dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    throw new IOException("Only regular non-symlink files are allowed in spins: " + file);
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> relativeName(root, path)));
        return files;
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; the primary failure remains authoritative.
        }
    }

    static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path requireDirectory(Path value, String label) throws IOException {
        if (value == null) throw new IOException(label + " is required");
        Path normalized = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular directory: " + normalized);
        }
        return normalized;
    }

    private static Path requireFile(Path value, String label) throws IOException {
        if (value == null) throw new IOException(label + " is required");
        Path normalized = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + normalized);
        }
        return normalized;
    }

    private static void rejectSymlinkComponents(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException(label + " contains a symbolic link: " + current);
            }
        }
    }

    private static String safeEntryName(String raw) throws IOException {
        if (raw == null) throw new IOException("Spin archive entry name is null");
        String name = raw.replace('\\', '/');
        if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe spin archive entry: " + raw);
        }
        Path normalized = Path.of(name).normalize();
        String safe = normalized.toString().replace('\\', '/');
        if (safe.equals("..") || safe.startsWith("../") || !safe.equals(name)) {
            throw new IOException("Unsafe spin archive entry: " + raw);
        }
        return safe;
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static InputStream limited(InputStream delegate, long limit) {
        return new java.io.FilterInputStream(delegate) {
            private long read;
            private void account(long count) throws IOException {
                if (count > 0 && (read += count) > limit) {
                    throw new IOException("Input exceeds " + limit + " bytes");
                }
            }
            @Override public int read() throws IOException {
                int value = super.read();
                if (value >= 0) account(1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                account(count);
                return count;
            }
        };
    }

    public record Identity(String id, String version, String contentSha256) { }
    public record BuildResult(Path archive, String id, String version,
                              String sha256, long bytes) { }
    private record PayloadFile(Path path, String sha256, long size, long crc,
                               boolean stored) { }
}
