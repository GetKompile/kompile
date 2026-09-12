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
 * <p>Currently the only field is the explicit {@code /model} selection, but the
 * schema is intentionally flat so future durable-session commands can extend it.
 * A stored record is trusted only when its {@code workingDirectory} still matches
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

    /** Persisted record schema version; readers reject any other version. */
    public static final int SCHEMA_VERSION = 1;

    /** A durable session state record. {@code model} is null when never set. */
    public record SessionState(int schemaVersion, String sessionId, String workingDirectory,
                               String model, String updatedAt) {
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
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !root.path("sessionId").isTextual()) {
            return null;
        }
        String recordedDirectory = root.path("workingDirectory").isTextual()
                ? root.path("workingDirectory").asText() : "";
        if (!Objects.equals(normalize(workingDirectory), normalize(Path.of(recordedDirectory)))) {
            return null; // mismatch → ignore stale state
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : null;
        String updatedAt = root.path("updatedAt").isTextual()
                ? root.path("updatedAt").asText() : null;
        return new SessionState(SCHEMA_VERSION, root.path("sessionId").asText(),
                recordedDirectory, model, updatedAt);
    }

    /** Convenience variant that reports whether a usable stored model exists. */
    public String loadModel(String sessionId, Path workingDirectory) {
        SessionState state = load(sessionId, workingDirectory);
        return state == null || state.model() == null || state.model().isBlank()
                ? null : state.model();
    }

    /**
     * Atomically update the state for {@code sessionId}, preserving any fields
     * not supplied by this call. Concurrent writers serialize on a lock file;
     * each caller re-reads and re-writes inside the lock, so the last writer
     * wins per field rather than losing whole records.
     */
    public SaveResult updateModel(String sessionId, Path workingDirectory, String model) {
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
                SessionState next = new SessionState(SCHEMA_VERSION, sessionId,
                        preservedDirectory, model.trim(), java.time.Instant.now().toString());
                ObjectNode root = JsonUtils.standardMapper().createObjectNode();
                root.put("schemaVersion", next.schemaVersion());
                root.put("sessionId", next.sessionId());
                root.put("workingDirectory", next.workingDirectory());
                root.put("model", next.model());
                root.put("updatedAt", next.updatedAt());
                writeAtomically(file, root);
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
