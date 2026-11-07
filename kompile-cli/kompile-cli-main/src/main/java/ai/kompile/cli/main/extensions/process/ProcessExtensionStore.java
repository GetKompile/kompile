/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.extensions.process;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Immutable payloads in an explicitly supplied trusted USER store. Never discovers packages in cwd.
 * Activation is an atomic installed.json replacement; immutable payload publication precedes it.
 * Failed activations may leave an unselected payload, which can safely be selected by a later install.
 * Deactivation intentionally retains payloads: an already running process may still be using them.
 * This is not a sandbox against the user who owns the store, nor a readiness/permission check.
 */
public final class ProcessExtensionStore {
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;
    // FileChannel locks coordinate processes, but overlapping locks in one JVM throw instead of waiting.
    private static final ReentrantLock JVM_WRITER = new ReentrantLock();
    private final Path root;
    private final ObjectMapper mapper;

    public record InstalledExtension(ProcessExtensionManifest manifest, Path directory) { }

    public ProcessExtensionStore(Path root, ObjectMapper mapper) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Path root() { return root; }

    public List<InstalledExtension> installed() throws IOException {
        List<InstalledExtension> result = new ArrayList<>();
        for (var entry : registry().entrySet()) result.add(selected(entry.getKey(), entry.getValue()));
        return List.copyOf(result);
    }

    public Optional<InstalledExtension> find(String id) throws IOException {
        ProcessExtensionManifest.extensionId(id);
        String version = registry().get(id);
        return version == null ? Optional.empty() : Optional.of(selected(id, version));
    }

    /** Copies a local directory without ever executing it. Replacement switches selection, not payload bytes. */
    public InstalledExtension install(Path source, boolean replace) throws IOException {
        return install(source, replace, List.of(), java.util.Set.of());
    }

    /** Host metadata is revalidated against the actual selected state under the writer lock. */
    public InstalledExtension install(Path source, boolean replace,
                                      java.util.Collection<ai.kompile.cli.plugin.api.CliExtensionDescriptor> hostDescriptors,
                                      java.util.Set<String> reservedCommands) throws IOException {
        Path from = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        ProcessExtensionManifest.noSymlinks(from);
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source must be a local directory");
        if (root.startsWith(from) || from.startsWith(root)) throw new IOException("Source and package store must be separate");
        return withWriterLock(() -> {
            Path staging = Files.createTempDirectory(root, ".stage-");
            try {
                copyPackage(from, staging);
                ProcessExtensionManifest manifest = manifest(staging);
                validateLaunches(manifest, staging);
                Map<String, String> selected = registry();
                validateSelections(selected);
                List<ai.kompile.cli.plugin.api.CliExtension> proposed = new ArrayList<>();
                for (var descriptor : List.copyOf(hostDescriptors)) proposed.add(() -> descriptor);
                for (var entry : selected.entrySet()) {
                    if (entry.getKey().equals(manifest.id())) continue;
                    var descriptor = selected(entry.getKey(), entry.getValue()).manifest().descriptor();
                    proposed.add(() -> descriptor);
                }
                proposed.add(manifest::descriptor);
                try {
                    new ai.kompile.cli.plugin.api.CliExtensionCatalog(proposed, java.util.Set.of());
                    for (String command : manifest.commands().keySet()) {
                        if (reservedCommands.contains(command)) throw new IllegalArgumentException("Reserved host command: " + command);
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid selected extension catalog: " + invalid.getMessage(), invalid);
                }
                if (selected.containsKey(manifest.id()) && !replace) {
                    throw new IOException("Extension already selected; replacement must be explicit: " + manifest.id());
                }
                Path parent = root.resolve("packages").resolve(manifest.id());
                createDirectory(parent);
                Path destination = parent.resolve(manifest.version());
                ProcessExtensionManifest.noSymlinks(destination);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    selected(manifest.id(), manifest.version());
                    if (!samePackage(staging, destination)) throw new IOException("Version payload is immutable: " + destination);
                } else {
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                }
                selected.put(manifest.id(), manifest.version());
                publishRegistry(selected);
                return new InstalledExtension(manifest, destination);
            } finally {
                deleteStaging(staging);
            }
        });
    }

    /** Deactivates only. Payloads are deliberately retained for processes already using them. */
    public boolean remove(String id) throws IOException {
        ProcessExtensionManifest.extensionId(id);
        return withWriterLock(() -> {
            Map<String, String> selected = registry();
            // Recovery must not require loading the broken payload being deactivated.
            if (selected.remove(id) == null) return false;
            publishRegistry(selected);
            return true;
        });
    }

    private InstalledExtension selected(String id, String version) throws IOException {
        Path directory = root.resolve("packages").resolve(id).resolve(version);
        ProcessExtensionManifest.noSymlinks(directory);
        inventory(directory);
        ProcessExtensionManifest manifest = manifest(directory);
        if (!id.equals(manifest.id()) || !version.equals(manifest.version())) {
            throw new IOException("Selected package identity does not match registry: " + directory);
        }
        validateLaunches(manifest, directory);
        return new InstalledExtension(manifest, directory);
    }

    private void validateSelections(Map<String, String> values) throws IOException {
        for (var entry : values.entrySet()) selected(entry.getKey(), entry.getValue());
    }

    private Map<String, String> registry() throws IOException {
        ProcessExtensionManifest.noSymlinks(root);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Store root is not a directory: " + root);
        }
        Path file = root.resolve("installed.json");
        ProcessExtensionManifest.noSymlinks(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new TreeMap<>();
        JsonNode node = readJson(file);
        if (node == null || !node.isObject()) throw new IOException("installed.json must map extension IDs to versions");
        Map<String, String> result = new TreeMap<>();
        var entries = node.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            try {
                ProcessExtensionManifest.extensionId(entry.getKey());
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Version must be a string");
                ProcessExtensionManifest.extensionVersion(entry.getValue().textValue());
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid installed.json entry: " + entry.getKey(), e);
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        }
        return result;
    }

    private ProcessExtensionManifest manifest(Path directory) throws IOException {
        try {
            return ProcessExtensionManifest.parse(readJson(ProcessExtensionManifest.checkedFile(directory, "extension.json")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid extension.json in " + directory + ": " + e.getMessage(), e);
        }
    }

    private JsonNode readJson(Path file) throws IOException {
        ProcessExtensionManifest.noSymlinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_METADATA_BYTES) {
            throw new IOException("Metadata must be a regular file of at most 1 MiB: " + file);
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Metadata exceeds 1 MiB: " + file);
            return mapper.readTree(bytes);
        }
    }

    private static void validateLaunches(ProcessExtensionManifest manifest, Path directory) throws IOException {
        for (var launch : manifest.commands().values()) launch.command(directory, "java");
        if (manifest.mcp().isPresent()) manifest.mcp().orElseThrow().command(directory, "java");
    }

    private void publishRegistry(Map<String, String> values) throws IOException {
        ObjectNode object = mapper.createObjectNode();
        values.forEach(object::put);
        byte[] bytes = mapper.writeValueAsBytes(object);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Registry exceeds 1 MiB");
        Path temporary = Files.createTempFile(root, ".installed-", ".json");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            ProcessExtensionManifest.noSymlinks(root.resolve("installed.json"));
            Files.move(temporary, root.resolve("installed.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <T> T withWriterLock(IOAction<T> action) throws IOException {
        JVM_WRITER.lock();
        try {
            createDirectory(root);
            Path lockPath = root.resolve(".store.lock");
            ProcessExtensionManifest.noSymlinks(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        } finally {
            JVM_WRITER.unlock();
        }
    }

    private static void createDirectory(Path directory) throws IOException {
        ProcessExtensionManifest.noSymlinks(directory);
        Files.createDirectories(directory);
        ProcessExtensionManifest.noSymlinks(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory: " + directory);
    }

    private static Map<Path, BasicFileAttributes> inventory(Path directory) throws IOException {
        ProcessExtensionManifest.noSymlinks(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing package directory: " + directory);
        Map<Path, BasicFileAttributes> result = new LinkedHashMap<>();
        long[] bytes = {0};
        Files.walkFileTree(directory, Set.of(), 32, new SimpleFileVisitor<>() {
            private void add(Path path, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || (!attrs.isRegularFile() && !attrs.isDirectory())) {
                    throw new IOException("Only regular files/directories are allowed in packages: " + path);
                }
                if (result.size() >= MAX_ENTRIES) throw new IOException("Package has too many entries");
                bytes[0] += attrs.isRegularFile() ? attrs.size() : 0;
                if (bytes[0] > MAX_PACKAGE_BYTES) throw new IOException("Package exceeds 512 MiB");
                result.put(directory.relativize(path), attrs);
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                add(dir, attrs);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isDirectory()) throw new IOException("Package exceeds maximum directory depth");
                add(file, attrs);
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static void copyPackage(Path source, Path target) throws IOException {
        var entries = inventory(source);
        BasicFileAttributes metadata = entries.get(Path.of("extension.json"));
        if (metadata == null || !metadata.isRegularFile() || metadata.size() > MAX_METADATA_BYTES) {
            throw new IOException("Package requires a regular extension.json of at most 1 MiB");
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        for (var entry : entries.entrySet()) {
            Path from = source.resolve(entry.getKey());
            Path to = target.resolve(entry.getKey());
            ProcessExtensionManifest.noSymlinks(from);
            if (entry.getValue().isDirectory()) {
                createDirectory(to);
                continue;
            }
            try (InputStream in = Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS);
                 var out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_PACKAGE_BYTES) throw new IOException("Package exceeds 512 MiB during copy");
                    out.write(buffer, 0, count);
                }
            }
            BasicFileAttributes after = Files.readAttributes(from, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !Objects.equals(after.fileKey(), entry.getValue().fileKey())
                    || after.size() != entry.getValue().size() || Files.size(to) != after.size()
                    || !after.lastModifiedTime().equals(entry.getValue().lastModifiedTime())) {
                throw new IOException("Package source changed during copy: " + from);
            }
            if (Files.getFileStore(from).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS));
            } else if (Files.isExecutable(from) != Files.isExecutable(to)) {
                throw new IOException("Filesystem cannot preserve executable attributes: " + from);
            }
        }
    }

    private static boolean samePackage(Path left, Path right) throws IOException {
        var a = inventory(left);
        var b = inventory(right);
        if (!a.keySet().equals(b.keySet())) return false;
        for (Path path : a.keySet()) {
            if (a.get(path).isDirectory() != b.get(path).isDirectory()) return false;
            if (a.get(path).isRegularFile()) {
                Path first = left.resolve(path);
                Path second = right.resolve(path);
                if (Files.mismatch(first, second) != -1 || Files.isExecutable(first) != Files.isExecutable(second)) return false;
                if (Files.getFileStore(first).supportsFileAttributeView("posix")
                        && !Files.getPosixFilePermissions(first, LinkOption.NOFOLLOW_LINKS)
                        .equals(Files.getPosixFilePermissions(second, LinkOption.NOFOLLOW_LINKS))) return false;
            }
        }
        return true;
    }

    /** Deletes only our unique staging tree; traversal never follows symlinks. */
    private static void deleteStaging(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    private interface IOAction<T> { T run() throws IOException; }
}
