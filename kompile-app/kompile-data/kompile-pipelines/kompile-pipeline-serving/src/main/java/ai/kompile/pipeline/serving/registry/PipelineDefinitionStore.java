/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.registry;

import ai.kompile.pipeline.serving.definition.PipelineDefinitionIdentity;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Project-scoped immutable store for executable pipeline definitions.
 *
 * <p>Each update writes a new version. Promotion changes only the atomic active pointer; definitions
 * are never overwritten in place.</p>
 */
public final class PipelineDefinitionStore {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private final Path unifiedRoot;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock;

    public PipelineDefinitionStore(Path pipelinesRoot) {
        this(pipelinesRoot, ObjectMappers.getJsonMapper());
    }

    public PipelineDefinitionStore(Path pipelinesRoot, ObjectMapper mapper) {
        this.unifiedRoot = Objects.requireNonNull(pipelinesRoot, "pipelinesRoot")
                .toAbsolutePath().normalize().resolve("unified");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.lock = LOCKS.computeIfAbsent(unifiedRoot, ignored -> new ReentrantReadWriteLock());
    }

    public UnifiedPipelineDefinition save(UnifiedPipelineDefinition requested,
                                          Long expectedActiveVersion,
                                          String actor) throws IOException {
        Objects.requireNonNull(requested, "definition");
        String id = requireId(requested.getPipelineId());
        lock.writeLock().lock();
        try {
            Files.createDirectories(versionsDirectory(id));
            ActivePointer pointer = readPointer(id).orElse(null);
            long activeVersion = pointer == null ? 0L : pointer.activeVersion();
            if (expectedActiveVersion != null && expectedActiveVersion != activeVersion) {
                throw new java.util.ConcurrentModificationException(
                        "Pipeline '" + id + "' active version changed: expected "
                                + expectedActiveVersion + ", found " + activeVersion);
            }

            long version = latestVersion(id) + 1L;
            UnifiedPipelineDefinition definition = copy(requested);
            definition.setSchemaVersion(UnifiedPipelineDefinition.CURRENT_SCHEMA_VERSION);
            definition.setDefinitionVersion(version);
            definition.setLifecycleState(pointer == null
                    ? UnifiedPipelineDefinition.LifecycleState.ACTIVE
                    : UnifiedPipelineDefinition.LifecycleState.DRAFT);
            String now = Instant.now().toString();
            if (definition.getCreatedAt() == null) definition.setCreatedAt(now);
            definition.setUpdatedAt(now);
            if (definition.getCreatedBy() == null) definition.setCreatedBy(actor);
            definition.setUpdatedBy(actor);
            definition.setContentDigest(PipelineDefinitionIdentity.contentDigest(mapper, definition));
            atomicWrite(versionFile(id, version), definition);
            if (pointer == null) {
                writePointer(id, new ActivePointer(id, version, definition.getContentDigest(), now, actor));
            }
            return definition;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UnifiedPipelineDefinition promote(String pipelineId,
                                             long version,
                                             Long expectedActiveVersion,
                                             String actor) throws IOException {
        String id = requireId(pipelineId);
        lock.writeLock().lock();
        try {
            ActivePointer current = readPointer(id).orElse(null);
            long activeVersion = current == null ? 0L : current.activeVersion();
            if (expectedActiveVersion != null && expectedActiveVersion != activeVersion) {
                throw new java.util.ConcurrentModificationException(
                        "Pipeline '" + id + "' active version changed: expected "
                                + expectedActiveVersion + ", found " + activeVersion);
            }
            UnifiedPipelineDefinition selected = readVersion(id, version)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown pipeline version " + id + "@" + version));
            String now = Instant.now().toString();
            writePointer(id, new ActivePointer(id, version, selected.getContentDigest(), now, actor));
            selected.setLifecycleState(UnifiedPipelineDefinition.LifecycleState.ACTIVE);
            return selected;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<UnifiedPipelineDefinition> active(String pipelineId) throws IOException {
        String id = requireId(pipelineId);
        lock.readLock().lock();
        try {
            Optional<ActivePointer> pointer = readPointer(id);
            if (pointer.isEmpty()) return Optional.empty();
            Optional<UnifiedPipelineDefinition> value = readVersion(id, pointer.get().activeVersion());
            value.ifPresent(definition -> definition.setLifecycleState(
                    UnifiedPipelineDefinition.LifecycleState.ACTIVE));
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<UnifiedPipelineDefinition> version(String pipelineId, long version) throws IOException {
        String id = requireId(pipelineId);
        lock.readLock().lock();
        try {
            return readVersion(id, version);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<UnifiedPipelineDefinition> versions(String pipelineId) throws IOException {
        String id = requireId(pipelineId);
        lock.readLock().lock();
        try {
            List<UnifiedPipelineDefinition> result = new ArrayList<>();
            Path versions = versionsDirectory(id);
            if (!Files.isDirectory(versions)) return result;
            try (Stream<Path> files = Files.list(versions)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                    result.add(mapper.readValue(file.toFile(), UnifiedPipelineDefinition.class));
                }
            }
            result.sort(Comparator.comparingLong(UnifiedPipelineDefinition::getDefinitionVersion));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<UnifiedPipelineDefinition> listActive() throws IOException {
        lock.readLock().lock();
        try {
            if (!Files.isDirectory(unifiedRoot)) return List.of();
            List<UnifiedPipelineDefinition> result = new ArrayList<>();
            try (Stream<Path> children = Files.list(unifiedRoot)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    active(child.getFileName().toString()).ifPresent(result::add);
                }
            }
            result.sort(Comparator.comparing(UnifiedPipelineDefinition::getPipelineId));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean archive(String pipelineId, Long expectedActiveVersion, String actor) throws IOException {
        String id = requireId(pipelineId);
        lock.writeLock().lock();
        try {
            ActivePointer pointer = readPointer(id).orElse(null);
            if (pointer == null) return false;
            if (expectedActiveVersion != null && expectedActiveVersion != pointer.activeVersion()) {
                throw new java.util.ConcurrentModificationException(
                        "Pipeline '" + id + "' active version changed: expected "
                                + expectedActiveVersion + ", found " + pointer.activeVersion());
            }
            Path archived = pipelineDirectory(id).resolve("archived.json");
            atomicWrite(archived, new ActivePointer(id, pointer.activeVersion(), pointer.contentDigest(),
                    Instant.now().toString(), actor));
            Files.deleteIfExists(pointerFile(id));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Optional<UnifiedPipelineDefinition> readVersion(String id, long version) throws IOException {
        Path file = versionFile(id, version);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), UnifiedPipelineDefinition.class));
    }

    private Optional<ActivePointer> readPointer(String id) throws IOException {
        Path file = pointerFile(id);
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(mapper.readValue(file.toFile(), ActivePointer.class));
    }

    private void writePointer(String id, ActivePointer pointer) throws IOException {
        atomicWrite(pointerFile(id), pointer);
    }

    private long latestVersion(String id) throws IOException {
        Path versions = versionsDirectory(id);
        if (!Files.isDirectory(versions)) return 0L;
        try (Stream<Path> files = Files.list(versions)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .mapToLong(name -> {
                        try {
                            return Long.parseLong(name);
                        } catch (NumberFormatException ignored) {
                            return 0L;
                        }
                    }).max().orElse(0L);
        }
    }

    private UnifiedPipelineDefinition copy(UnifiedPipelineDefinition definition) {
        return mapper.convertValue(mapper.valueToTree(definition), UnifiedPipelineDefinition.class);
    }

    private void atomicWrite(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String requireId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid pipelineId: " + id);
        }
        return id;
    }

    private Path pipelineDirectory(String id) {
        return unifiedRoot.resolve(id);
    }

    private Path versionsDirectory(String id) {
        return pipelineDirectory(id).resolve("versions");
    }

    private Path versionFile(String id, long version) {
        return versionsDirectory(id).resolve(String.format("%020d.json", version));
    }

    private Path pointerFile(String id) {
        return pipelineDirectory(id).resolve("active.json");
    }

    private record ActivePointer(String pipelineId,
                                 long activeVersion,
                                 String contentDigest,
                                 String updatedAt,
                                 String updatedBy) {
    }
}
