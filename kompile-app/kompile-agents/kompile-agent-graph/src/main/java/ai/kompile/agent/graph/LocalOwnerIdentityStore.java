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
package ai.kompile.agent.graph;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable local-owner identity with strict filesystem publication semantics.
 *
 * <p>The configured directory is a control directory, not the agent-instance root. The identity is
 * stored as exactly one canonical lowercase UUID plus a newline in {@value #IDENTITY_FILE_NAME}.
 * A same-directory staging file is fsynced and published only with an atomic move. A persistent
 * file lock plus a JVM lock stripe makes concurrent first startup converge on the same UUID across
 * threads and cooperating processes.</p>
 *
 * <p>On POSIX filesystems the control directory is forced to {@code 0700} and all files to
 * {@code 0600}. Every access uses {@link LinkOption#NOFOLLOW_LINKS} and rejects any existing
 * symbolic link in the configured path. As with {@link AgentInstanceStore}, a malicious process
 * already running as the same OS user remains outside this portable filesystem boundary.</p>
 */
public final class LocalOwnerIdentityStore {

    static final String IDENTITY_FILE_NAME = "local-owner.id";
    static final String LOCK_FILE_NAME = ".local-owner.lock";
    private static final int IDENTITY_BYTES = 37;
    private static final int JVM_LOCK_STRIPE_COUNT = 64;
    private static final ReentrantLock[] JVM_LOCKS = createLockStripes();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path root;
    private final Path identityFile;
    private final Path lockFile;
    private final DirectoryFsync directoryFsync;
    private final AtomicMover atomicMover;

    public LocalOwnerIdentityStore(Path configuredControlDirectory) throws IOException {
        this(configuredControlDirectory, LocalOwnerIdentityStore::forceDirectory,
                LocalOwnerIdentityStore::atomicMove);
    }

    LocalOwnerIdentityStore(
            Path configuredControlDirectory,
            DirectoryFsync directoryFsync) throws IOException {
        this(configuredControlDirectory, directoryFsync, LocalOwnerIdentityStore::atomicMove);
    }

    LocalOwnerIdentityStore(
            Path configuredControlDirectory,
            DirectoryFsync directoryFsync,
            AtomicMover atomicMover) throws IOException {
        this.root = prepareRoot(configuredControlDirectory);
        this.identityFile = insideRoot(root.resolve(IDENTITY_FILE_NAME));
        this.lockFile = insideRoot(root.resolve(LOCK_FILE_NAME));
        this.directoryFsync = Objects.requireNonNull(directoryFsync, "directoryFsync");
        this.atomicMover = Objects.requireNonNull(atomicMover, "atomicMover");
    }

    /** Load the durable identity, generating and atomically publishing it on first use. */
    public LocalOwnerIdentity loadOrCreate() throws IOException {
        rejectSymlinkChain(identityFile);
        rejectSymlinkChain(lockFile);
        ReentrantLock jvmLock = lockStripe(lockFile);
        jvmLock.lock();
        try (FileChannel channel = openPrivateLockFile(); FileLock fileLock = channel.lock()) {
            if (!fileLock.isValid()) {
                throw new IOException("Unable to acquire the local owner identity lock");
            }
            requireSafeDirectory(root);
            rejectSymlinkChain(identityFile);
            if (Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)) {
                return readIdentity();
            }
            UUID generated = UUID.randomUUID();
            publishIdentity(generated);
            return readIdentity();
        } finally {
            jvmLock.unlock();
        }
    }

    private LocalOwnerIdentity readIdentity() throws IOException {
        rejectSymlinkChain(identityFile);
        requireSafeRegularFile(identityFile);
        byte[] encoded;
        try (FileChannel input = FileChannel.open(
                identityFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            if (size != IDENTITY_BYTES) {
                throw new IOException("Local owner identity must contain exactly one canonical UUID");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(IDENTITY_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate(IDENTITY_BYTES);
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                out.write(buffer.array(), 0, read);
                buffer.clear();
            }
            encoded = out.toByteArray();
        }
        if (encoded.length != IDENTITY_BYTES || encoded[IDENTITY_BYTES - 1] != '\n') {
            throw new IOException("Local owner identity must contain exactly one canonical UUID");
        }
        String value = new String(encoded, 0, IDENTITY_BYTES - 1, StandardCharsets.US_ASCII);
        try {
            UUID ownerId = UUID.fromString(value);
            if (!ownerId.toString().equals(value)) {
                throw new IOException("Local owner identity UUID is not canonical: " + value);
            }
            return new LocalOwnerIdentity(ownerId);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Local owner identity is not a valid canonical UUID", invalid);
        }
    }

    private void publishIdentity(UUID ownerId) throws IOException {
        byte[] encoded = (ownerId + "\n").getBytes(StandardCharsets.US_ASCII);
        Path staging = insideRoot(root.resolve(
                "." + IDENTITY_FILE_NAME + "." + UUID.randomUUID() + ".staging"));
        boolean published = false;
        try {
            try (FileChannel output = createPrivateFileChannel(staging)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            directoryFsync.force(root);
            rejectSymlinkChain(identityFile);
            if (Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(identityFile.toString());
            }
            try {
                atomicMover.move(staging, identityFile);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "Atomic publication is required for the local owner identity", unsupported);
            }
            published = true;
            try {
                requireSafeRegularFile(identityFile);
                directoryFsync.force(root);
            } catch (IOException postMoveFailure) {
                throw new IOException(
                        "Local owner identity may have been published; reread before retrying",
                        postMoveFailure);
            }
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private FileChannel openPrivateLockFile() throws IOException {
        try {
            FileChannel created = createPrivateFileChannel(lockFile);
            try {
                created.force(true);
                forceDirectory(root);
                return created;
            } catch (IOException failure) {
                created.close();
                throw failure;
            }
        } catch (FileAlreadyExistsException existing) {
            requireSafeRegularFile(lockFile);
            return FileChannel.open(
                    lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static FileChannel createPrivateFileChannel(Path file) throws IOException {
        rejectTargetSymlink(file);
        FileChannel channel = FileChannel.open(
                file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                privateFileAttributes(file.getParent()));
        try {
            enforcePrivatePermissions(file, PRIVATE_FILE_PERMISSIONS);
            return channel;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private void rejectSymlinkChain(Path candidate) throws IOException {
        requireSafeDirectory(root);
        Path cursor = root;
        for (Path segment : root.relativize(insideRoot(candidate))) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IOException("Symbolic links are forbidden in local owner storage: " + cursor);
            }
        }
    }

    private static void rejectTargetSymlink(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IOException("Symbolic links are forbidden in local owner storage: " + target);
        }
    }

    private static Path prepareRoot(Path configuredRoot) throws IOException {
        Objects.requireNonNull(configuredRoot, "configuredControlDirectory");
        Path requested = configuredRoot.toAbsolutePath().normalize();

        List<Path> missing = new ArrayList<>();
        Path existing = requested;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing);
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("Local owner control directory has no existing ancestor");
            }
        }
        if (Files.isSymbolicLink(existing)
                || !Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local owner control ancestor is not a safe directory: " + existing);
        }

        Path cursor = existing.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Collections.reverse(missing);
        for (Path missingPath : missing) {
            cursor = cursor.resolve(missingPath.getFileName().toString());
            try {
                createPrivateDirectory(cursor);
            } catch (FileAlreadyExistsException concurrentCreate) {
                // A concurrent creator is accepted only after the same no-link/type checks below.
            }
            requireSafeDirectory(cursor);
            forceDirectory(cursor);
            forceDirectory(cursor.getParent());
        }
        requireSafeDirectory(cursor);
        return cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private Path insideRoot(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Local owner storage path escapes the configured control directory");
        }
        return normalized;
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectory(directory, privateDirectoryAttributes(directory.getParent()));
        requireSafeDirectory(directory);
    }

    private static void requireSafeDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local owner storage path is not a safe directory: " + directory);
        }
        enforcePrivatePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private static void requireSafeRegularFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local owner storage path is not a safe regular file: " + file);
        }
        enforcePrivatePermissions(file, PRIVATE_FILE_PERMISSIONS);
    }

    private static FileAttribute<?>[] privateDirectoryAttributes(Path parent) throws IOException {
        if (!supportsPosix(parent)) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS)
        };
    }

    private static FileAttribute<?>[] privateFileAttributes(Path parent) throws IOException {
        if (!supportsPosix(parent)) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
        };
    }

    private static boolean supportsPosix(Path existingPath) throws IOException {
        return Files.getFileStore(existingPath).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static void enforcePrivatePermissions(
            Path path,
            Set<PosixFilePermission> required) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        view.setPermissions(required);
        Set<PosixFilePermission> actual = view.readAttributes().permissions();
        if (!actual.equals(required)) {
            throw new IOException("Unable to enforce private POSIX permissions on " + path);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static ReentrantLock lockStripe(Path path) {
        return JVM_LOCKS[Math.floorMod(path.hashCode(), JVM_LOCKS.length)];
    }

    @FunctionalInterface
    interface DirectoryFsync {
        void force(Path directory) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }
}
