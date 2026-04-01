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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.vectorstore.anserini.util;

import ai.kompile.cli.main.util.NativeImageInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory for creating Lucene {@link Directory} instances that are compatible
 * with GraalVM native images.
 *
 * <p>In a standard JVM, {@link FSDirectory#open(Path)} returns {@link org.apache.lucene.store.MMapDirectory}
 * on Linux, which uses {@code ScopedMemoryAccess} — a mechanism that GraalVM native images
 * cannot link. This factory falls back to {@link NIOFSDirectory} when running in a
 * native image context.</p>
 *
 * <p>{@link NIOFSDirectory} is ~10-20% slower than {@code MMapDirectory} for large indices
 * but is functionally identical and fully supported in native images.</p>
 */
public final class NativeCompatibleDirectoryFactory {

    private static final Logger log = LoggerFactory.getLogger(NativeCompatibleDirectoryFactory.class);

    private NativeCompatibleDirectoryFactory() {
    }

    /**
     * Opens a directory at the given path using the best available implementation.
     * Uses {@link NIOFSDirectory} in native image mode, {@link FSDirectory#open(Path, LockFactory)}
     * otherwise.
     *
     * @param path        the directory path
     * @param lockFactory the lock factory to use
     * @return a Lucene {@link Directory} instance
     * @throws IOException if the directory cannot be opened
     */
    public static Directory open(Path path, LockFactory lockFactory) throws IOException {
        if (NativeImageInfo.isRunningInNativeImage()) {
            log.debug("Running in native image mode — using NIOFSDirectory for path: {}", path);
            return new NIOFSDirectory(path, lockFactory);
        }
        return FSDirectory.open(path, lockFactory);
    }

    /**
     * Opens a directory at the given path using the default lock factory.
     * Uses {@link NIOFSDirectory} in native image mode, {@link FSDirectory#open(Path)}
     * otherwise.
     *
     * @param path the directory path
     * @return a Lucene {@link Directory} instance
     * @throws IOException if the directory cannot be opened
     */
    public static Directory open(Path path) throws IOException {
        if (NativeImageInfo.isRunningInNativeImage()) {
            log.debug("Running in native image mode — using NIOFSDirectory for path: {}", path);
            return new NIOFSDirectory(path);
        }
        return FSDirectory.open(path);
    }

    /**
     * Opens a directory specifically as an {@link FSDirectory} (or {@link NIOFSDirectory} in native mode).
     * This is useful when the caller needs the returned type to be {@link FSDirectory}.
     *
     * @param path        the directory path
     * @param lockFactory the lock factory to use
     * @return an {@link FSDirectory} instance
     * @throws IOException if the directory cannot be opened
     */
    public static FSDirectory openFSDirectory(Path path, LockFactory lockFactory) throws IOException {
        if (NativeImageInfo.isRunningInNativeImage()) {
            log.debug("Running in native image mode — using NIOFSDirectory for path: {}", path);
            return new NIOFSDirectory(path, lockFactory);
        }
        return FSDirectory.open(path, lockFactory);
    }

    /**
     * Opens a directory specifically as an {@link FSDirectory} (or {@link NIOFSDirectory} in native mode).
     *
     * @param path the directory path
     * @return an {@link FSDirectory} instance
     * @throws IOException if the directory cannot be opened
     */
    public static FSDirectory openFSDirectory(Path path) throws IOException {
        if (NativeImageInfo.isRunningInNativeImage()) {
            log.debug("Running in native image mode — using NIOFSDirectory for path: {}", path);
            return new NIOFSDirectory(path);
        }
        return FSDirectory.open(path);
    }
}
