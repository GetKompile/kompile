/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Durable, cross-process session state for web-JSON command runs
 * ({@code ~/.kompile/web-session-state/<sessionId>.json}).
 *
 * <p>Currently the durable fields are the explicit {@code /model} and {@code /role}
 * selections, but the schema is intentionally flat so future durable-session
 * commands can extend it. A stored record is trusted only when its
 * {@code workingDirectory} still matches
 * the invoking run's directory; any mismatch (or an unreadable/corrupt file) is
 * reported as absent so stale state is never applied to a different project.
 *
 * <p>Safety properties: updates take an exclusive {@link FileChannel} lock on a
 * sidecar {@code .lock} file (serializing concurrent writers across processes),
 * write to a temporary file in the same directory, and replace the state file
 * with an atomic move where the filesystem supports it. No credentials are ever
 * stored here; model selection is a non-secret string.
 */
public final class ChatSessionStateStore {

    /** Persisted record schema version; readers accept this version (and legacy 1/2). */
    public static final int SCHEMA_VERSION = 3;
    /** Legacy schema versions (model-only, then model+role) still readable. */
    public static final int LEGACY_SCHEMA_VERSION = 2;
    public static final int INITIAL_SCHEMA_VERSION = 1;

    /**
     * A durable session state record; fields are null when never set. {@code model}
     * is only meaningful together with its {@code provider} (a stored model without
     * a provider predates vendor switching and applies to the configured provider).
     */
    public record SessionState(int schemaVersion, String sessionId, String workingDirectory,
                               String model, String role, String updatedAt, String provider) {
    }

    /** Result of a save: whether the mutation was applied and the resulting state. */
    public record SaveResult(boolean applied, SessionState state) {
    }

    private static final String STATE_DIR = "web-session-state";
    /** Bounded wait for the cross-process state lock before giving up. */
    private static final java.time.Duration LOCK_WAIT = java.time.Duration.ofSeconds(10);
    private final Path stateDirectory;

    /** Default store rooted at the CLI conversation-state root under Kompile home. */
    public ChatSessionStateStore() {
        this(defaultStateDirectory());
    }

    /** Test/store seam: root the store at an explicit directory. */
    public ChatSessionStateStore(Path stateDirectory) {
        this.stateDirectory = stateDirectory == null ? defaultStateDirectory() : stateDirectory;
    }

    private static Path defaultStateDirectory() {
        return KompileHome.homeDirectory().toPath().resolve(STATE_DIR);
    }

    /**
     * Load the stored state for {@code sessionId}. Returns {@code null} when no
     * state exists, is unreadable, has an unknown schema version, or was recorded
     * for a different working directory (stale — ignored, never applied).
     */
    public SessionState load(String sessionId, Path workingDirectory) {
        Path file = stateFile(sessionId);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        JsonNode root;
        try {
            root = JsonUtils.standardMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null; // corrupt or partially written state behaves as absent
        }
        if (root == null || !root.isObject()
                || !isKnownSchemaVersion(root.path("schemaVersion").asInt(-1))
                || !root.path("sessionId").isTextual()) {
            return null;
        }
        String recordedDirectory = root.path("workingDirectory").isTextual()
                ? root.path("workingDirectory").asText() : "";
        if (!Objects.equals(normalize(workingDirectory), normalize(Path.of(recordedDirectory)))) {
            return null; // mismatch → ignore stale state
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : null;
        String role = root.path("role").isTextual() ? root.path("role").asText() : null;
        String updatedAt = root.path("updatedAt").isTextual()
                ? root.path("updatedAt").asText() : null;
        String provider = root.path("provider").isTextual()
                ? root.path("provider").asText() : null;
        return new SessionState(root.path("schemaVersion").asInt(), root.path("sessionId").asText(),
                recordedDirectory, model, role, updatedAt, provider);
    }

    private static boolean isKnownSchemaVersion(int version) {
        return version == SCHEMA_VERSION || version == LEGACY_SCHEMA_VERSION
                || version == INITIAL_SCHEMA_VERSION;
    }

    /**
     * Convenience variant that reports the stored provider for this session.
     * Null when no usable stored state (or provider) exists.
     */
    public String loadProvider(String sessionId, Path workingDirectory) {
        SessionState state = load(sessionId, workingDirectory);
        return state == null || state.provider() == null || state.provider().isBlank()
                ? null : state.provider();
    }

    /**
     * Convenience variant that reports whether a usable stored model exists.
     */
    public String loadModel(String sessionId, Path workingDirectory) {
        SessionState state = load(sessionId, workingDirectory);
        return state == null || state.model() == null || state.model().isBlank()
                ? null : state.model();
    }

    /**
     * Convenience variant that reports whether a usable stored role exists.
     * An explicit empty string means "selection cleared" and reads as absent.
     */
    public String loadRole(String sessionId, Path workingDirectory) {
        SessionState state = load(sessionId, workingDirectory);
        return state == null || state.role() == null || state.role().isBlank()
                ? null : state.role();
    }

    /**
     * Atomically update the state for {@code sessionId}, preserving any fields
     * not supplied by this call. Concurrent writers serialize on a lock file;
     * each caller re-reads and re-writes inside the lock, so the last writer
     * wins per field rather than losing whole records.
     */
    public SaveResult updateModel(String sessionId, Path workingDirectory, String model) {
        return updateModel(sessionId, workingDirectory, null, model);
    }

    /**
     * Atomically persist the model selection together with its provider
     * (vendor switching). A {@code null}/{@code blank} {@code provider} keeps
     * any stored provider (same-vendor model change); a concrete value records
     * the vendor the model belongs to so the next turn can restore both.
     */
    public SaveResult updateModel(String sessionId, Path workingDirectory,
                                  String provider, String model) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Path file = stateFile(sessionId);
        if (file == null) {
            return new SaveResult(false, null); // reject path-traversing/invalid ids before any I/O
        }
        if (model == null || model.isBlank()) {
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
        try {
            Files.createDirectories(stateDirectory);
        } catch (IOException e) {
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
        Path lockFile = stateDirectory.resolve(sessionId + ".lock");
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            FileLock lock = tryLockWithRetry(lockChannel);
            if (lock == null) {
                return new SaveResult(false, load(sessionId, workingDirectory));
            }
            try {
                SessionState current = load(sessionId, workingDirectory);
                String preservedDirectory = current != null
                        ? current.workingDirectory()
                        : normalize(workingDirectory).toString();
                String preservedProvider = provider != null && !provider.isBlank()
                        ? provider.trim()
                        : current == null ? null : current.provider();
                SessionState next = new SessionState(SCHEMA_VERSION, sessionId,
                        preservedDirectory, model.trim(),
                        current == null ? null : current.role(),
                        java.time.Instant.now().toString(),
                        preservedProvider);
                writeAtomically(file, next);
                return new SaveResult(true, next);
            } finally {
                lock.release();
            }
        } catch (Exception ignored) {
            // Persistence must never break a command run; report the current view.
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
    }

    /**
     * Atomically persist the role selection, preserving the stored model.
     * Mirrors {@link #updateModel(String, Path, String)} locking semantics.
     */
    public SaveResult updateRole(String sessionId, Path workingDirectory, String role) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Path file = stateFile(sessionId);
        if (file == null) {
            return new SaveResult(false, null); // reject path-traversing/invalid ids before any I/O
        }
        if (role == null) {
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
        try {
            Files.createDirectories(stateDirectory);
        } catch (IOException e) {
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
        Path lockFile = stateDirectory.resolve(sessionId + ".lock");
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            FileLock lock = tryLockWithRetry(lockChannel);
            if (lock == null) {
                return new SaveResult(false, load(sessionId, workingDirectory));
            }
            try {
                SessionState current = load(sessionId, workingDirectory);
                String preservedDirectory = current != null
                        ? current.workingDirectory()
                        : normalize(workingDirectory).toString();
                String preservedModel = current == null ? null : current.model();
                // An explicit empty string clears the role selection.
                String storedRole = role.isBlank() ? "" : role.trim();
                SessionState next = new SessionState(SCHEMA_VERSION, sessionId,
                        preservedDirectory, preservedModel, storedRole,
                        java.time.Instant.now().toString(),
                        current == null ? null : current.provider());
                writeAtomically(file, next);
                return new SaveResult(true, next);
            } finally {
                lock.release();
            }
        } catch (Exception ignored) {
            return new SaveResult(false, load(sessionId, workingDirectory));
        }
    }

    /** Serialize one durable record; null fields are written as empty strings. */
    private void writeAtomically(Path file, SessionState next) throws IOException {
        ObjectNode root = JsonUtils.standardMapper().createObjectNode();
        root.put("schemaVersion", next.schemaVersion());
        root.put("sessionId", next.sessionId());
        root.put("workingDirectory", next.workingDirectory());
        root.put("model", next.model() == null ? "" : next.model());
        root.put("role", next.role() == null ? "" : next.role());
        root.put("updatedAt", next.updatedAt());
        root.put("provider", next.provider() == null ? "" : next.provider());
        writeAtomically(file, root);
    }

    /**
     * Acquire the exclusive lock, retrying briefly. Within one JVM a contended
     * {@code lock()} throws {@link java.nio.file.OverlappingFileLockException}
     * instead of blocking (across processes it blocks); the retry loop covers
     * both behaviors with the same bounded wait.
     */
    private static FileLock tryLockWithRetry(FileChannel channel) throws java.io.IOException {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (true) {
            try {
                return channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException contended) {
                if (System.nanoTime() - deadline >= 0) {
                    return null;
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private Path stateFile(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._-]+") || sessionId.startsWith(".")) {
            return null;
        }
        return stateDirectory.resolve(sessionId + ".json");
    }

    private void writeAtomically(Path file, ObjectNode root) throws IOException {
        Path temporary = Files.createTempFile(stateDirectory, sessionIdPrefix(file), ".tmp");
        try {
            Files.writeString(temporary,
                    JsonUtils.standardMapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sessionIdPrefix(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 4) : name;
    }

    /** Canonical string form used for working-directory comparison. */
    private static String normalize(Path directory) {
        return directory == null ? "" : directory.toAbsolutePath().normalize().toString();
    }
}
