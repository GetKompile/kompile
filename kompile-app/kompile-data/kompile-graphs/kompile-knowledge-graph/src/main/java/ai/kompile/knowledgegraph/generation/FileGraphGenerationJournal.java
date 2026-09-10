/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** OS-file-locked, fsync-before-publish graph generation journal. */
@Service
public class FileGraphGenerationJournal implements GraphGenerationJournal {

    private static final String DIRECTORY = "state/graph-generations";
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path root;

    @Autowired
    public FileGraphGenerationJournal(
            ObjectMapper mapper,
            @Value("${kompile.data.dir:}") String dataDir) {
        this(mapper, resolveRoot(dataDir));
    }

    /** Test/embedded constructor. */
    public FileGraphGenerationJournal(ObjectMapper mapper, Path root) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public Optional<Entry> get(String logicalGraphId) {
        String key = key(logicalGraphId);
        return withLock(key, () -> readEntry(entryPath(key), logicalGraphId));
    }

    @Override
    public List<Entry> list() {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            List<Path> entries = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            List<Entry> result = new ArrayList<>(entries.size());
            for (Path path : entries) {
                String fileName = path.getFileName().toString();
                String key = fileName.substring(0, fileName.length() - ".json".length());
                withLock(key, () -> {
                    readEntry(path, null).ifPresent(result::add);
                    return null;
                });
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("Could not list graph generation journals", e);
        }
    }

    @Override
    public Entry update(String logicalGraphId,
                        Function<Optional<Entry>, Entry> updateFunction) {
        Objects.requireNonNull(updateFunction, "updateFunction");
        String key = key(logicalGraphId);
        return withLock(key, () -> {
            Path target = entryPath(key);
            Optional<Entry> current = readEntry(target, logicalGraphId);
            Entry updated = Objects.requireNonNull(updateFunction.apply(current),
                    "Graph generation journal update returned null");
            if (!logicalGraphId.equals(updated.generation().logicalGraphId())
                    || !logicalGraphId.equals(updated.pointer().logicalGraphId())) {
                throw new IllegalArgumentException("Journal update changed the logical graph scope");
            }
            if (current.isEmpty() || !current.get().equals(updated)) {
                writeEntry(target, updated);
            }
            return updated;
        });
    }

    Path root() {
        return root;
    }

    private <T> T withLock(String key, IoSupplier<T> work) {
        try {
            Files.createDirectories(root);
            Path lockPath = root.resolve(key + ".lock");
            ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
            jvmLock.lock();
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return work.get();
            } finally {
                jvmLock.unlock();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not lock graph generation journal", e);
        }
    }

    private Optional<Entry> readEntry(Path path, String expectedLogicalGraphId) throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        Entry entry = mapper.readValue(path.toFile(), Entry.class);
        if (expectedLogicalGraphId != null
                && !expectedLogicalGraphId.equals(entry.generation().logicalGraphId())) {
            throw new IllegalStateException("Graph generation journal hash collision or corruption");
        }
        return Optional.of(entry);
    }

    private void writeEntry(Path target, Entry entry) throws IOException {
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, target.getFileName().toString(), ".tmp");
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entry);
            Files.write(temporary, json, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void forceDirectory() {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort on filesystems/JDKs that do not permit opening a directory as a channel.
        }
    }

    private Path entryPath(String key) {
        return root.resolve(key + ".json");
    }

    private static Path resolveRoot(String dataDir) {
        Path base = dataDir == null || dataDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDir);
        return base.toAbsolutePath().normalize().resolve(DIRECTORY);
    }

    private static String key(String logicalGraphId) {
        if (logicalGraphId == null || logicalGraphId.isBlank()) {
            throw new IllegalArgumentException("logicalGraphId must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(logicalGraphId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
