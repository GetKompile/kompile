/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Atomic JSON store for encrypted channel connection records. */
public final class ChannelConnectionStore {

    private volatile Map<String, StoredChannelConnection> connections = Map.of();
    private final ObjectMapper mapper;
    private final Path storePath;

    public ChannelConnectionStore(Path storePath, ObjectMapper mapper) throws IOException {
        this.storePath = storePath;
        this.mapper = mapper;
        load();
    }

    public synchronized StoredChannelConnection save(StoredChannelConnection connection) {
        Map<String, StoredChannelConnection> replacement = new LinkedHashMap<>(connections);
        replacement.put(normalize(connection.name()), connection);
        persist(replacement);
        connections = Map.copyOf(replacement);
        return connection;
    }

    public Optional<StoredChannelConnection> get(String name) {
        return Optional.ofNullable(connections.get(normalize(name)));
    }

    public List<StoredChannelConnection> list() {
        return connections.values().stream()
                .sorted(Comparator.comparing(StoredChannelConnection::name))
                .toList();
    }

    public synchronized boolean delete(String name) {
        String normalized = normalize(name);
        if (!connections.containsKey(normalized)) {
            return false;
        }
        Map<String, StoredChannelConnection> replacement = new LinkedHashMap<>(connections);
        replacement.remove(normalized);
        persist(replacement);
        connections = Map.copyOf(replacement);
        return true;
    }

    Path storePath() {
        return storePath;
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(storePath)) {
            return;
        }
        List<StoredChannelConnection> loaded = mapper.readValue(
                storePath.toFile(),
                new TypeReference<List<StoredChannelConnection>>() { });
        Map<String, StoredChannelConnection> restored = new LinkedHashMap<>();
        for (StoredChannelConnection connection : loaded) {
            restored.put(normalize(connection.name()), connection);
        }
        connections = Map.copyOf(restored);
    }

    private void persist(Map<String, StoredChannelConnection> snapshot) {
        try {
            Files.createDirectories(storePath.getParent());
            Path temporary = Files.createTempFile(storePath.getParent(), ".channel-connections-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(temporary.toFile(), snapshot.values().stream()
                                .sorted(Comparator.comparing(StoredChannelConnection::name))
                                .toList());
                restrictPermissions(temporary);
                try {
                    Files.move(temporary, storePath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(storePath);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist channel connections", e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Connection name is required");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
