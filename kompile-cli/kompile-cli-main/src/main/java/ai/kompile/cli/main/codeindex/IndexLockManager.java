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

package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages concurrency for code index operations.
 *
 * <p>Within a single JVM: uses {@link ReentrantReadWriteLock} per project.
 * <p>Across processes: acquires a {@link FileLock} on {@code project.lock}
 * in the index directory during write operations.
 */
public class IndexLockManager {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS =
            new ConcurrentHashMap<>();

    private static ReentrantReadWriteLock lockFor(String projectId) {
        return LOCKS.computeIfAbsent(projectId, k -> new ReentrantReadWriteLock());
    }

    /**
     * Acquire a write lock for indexing operations.
     * Blocks if another thread holds a read or write lock.
     * Also acquires a cross-process file lock.
     */
    public static LockToken acquireWriteLock(String projectId, Path indexDir) throws IOException {
        ReentrantReadWriteLock rwLock = lockFor(projectId);
        rwLock.writeLock().lock();

        // Cross-process file lock
        Files.createDirectories(indexDir);
        Path lockFile = indexDir.resolve("project.lock");
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fileLock;
        try {
            fileLock = channel.tryLock();
            if (fileLock == null) {
                channel.close();
                rwLock.writeLock().unlock();
                throw new IOException("Index is locked by another process. " +
                        "Another 'kompile code-index' may be running for project '" + projectId + "'.");
            }
        } catch (OverlappingFileLockException e) {
            channel.close();
            rwLock.writeLock().unlock();
            throw new IOException("Index is locked by another thread in this process.");
        }

        return new WriteLockToken(rwLock, channel, fileLock);
    }

    /**
     * Acquire a read lock for search operations.
     * In-process only — SQLite WAL handles cross-process reads.
     */
    public static LockToken acquireReadLock(String projectId) {
        ReentrantReadWriteLock rwLock = lockFor(projectId);
        rwLock.readLock().lock();
        return new ReadLockToken(rwLock);
    }

    /**
     * Token returned from lock acquisition. Call {@link #close()} to release.
     */
    public interface LockToken extends AutoCloseable {
        @Override
        void close();
    }

    private static class WriteLockToken implements LockToken {
        private final ReentrantReadWriteLock rwLock;
        private final FileChannel channel;
        private final FileLock fileLock;

        WriteLockToken(ReentrantReadWriteLock rwLock, FileChannel channel, FileLock fileLock) {
            this.rwLock = rwLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                if (fileLock != null && fileLock.isValid()) fileLock.release();
            } catch (IOException ignored) {}
            try {
                if (channel != null && channel.isOpen()) channel.close();
            } catch (IOException ignored) {}
            rwLock.writeLock().unlock();
        }
    }

    private static class ReadLockToken implements LockToken {
        private final ReentrantReadWriteLock rwLock;

        ReadLockToken(ReentrantReadWriteLock rwLock) {
            this.rwLock = rwLock;
        }

        @Override
        public void close() {
            rwLock.readLock().unlock();
        }
    }
}
