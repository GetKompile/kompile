/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;

/** Durable at-most-once Telegram update checkpoint for one named connection. */
public final class TelegramRuntimeStateStore {

    private final Path path;
    private final ObjectMapper mapper;
    private State state;

    public TelegramRuntimeStateStore(Path path, ObjectMapper mapper) throws IOException {
        this.path = path;
        this.mapper = mapper;
        this.state = Files.isRegularFile(path) ? mapper.readValue(path.toFile(), State.class) : null;
    }

    public synchronized State forBot(long botId) {
        if (state == null) {
            return new State(1, botId, false, 0L, null, null);
        }
        if (state.botId() != botId) {
            throw new IllegalStateException(
                    "Telegram checkpoint belongs to a different bot; disconnect before rotating bot identity");
        }
        return state;
    }

    public synchronized State initialize(long botId, long nextOffset) {
        state = new State(1, botId, true, nextOffset, null, Instant.now());
        persist();
        return state;
    }

    /** Persist before dispatching the associated update to guarantee no replay after a crash. */
    public synchronized State advance(long botId, long nextOffset, Instant updateAt) {
        State current = forBot(botId);
        long monotonic = Math.max(current.nextOffset(), nextOffset);
        state = new State(1, botId, true, monotonic, updateAt, Instant.now());
        persist();
        return state;
    }

    public synchronized State current() {
        return state;
    }

    public synchronized void delete() {
        state = null;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete Telegram checkpoint", e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".telegram-state-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
                restrict(temporary);
                try {
                    Files.move(temporary, path,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                restrict(path);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Telegram checkpoint", e);
        }
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    @RegisterReflectionForBinding
    public record State(
            int version,
            long botId,
            boolean initialized,
            long nextOffset,
            Instant lastUpdateAt,
            Instant persistedAt) {
    }
}
