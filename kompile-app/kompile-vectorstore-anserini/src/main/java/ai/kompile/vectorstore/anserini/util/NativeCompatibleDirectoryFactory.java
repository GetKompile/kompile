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

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.NIOFSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory for creating Lucene {@link Directory} instances that are compatible
 * with GraalVM native images.
 *
 * <p>Always uses {@link NIOFSDirectory} to avoid {@code MMapDirectory}'s
 * {@code MemorySegmentIndexInput} which requires {@code Arena.ofShared()} —
 * unsupported in GraalVM native images.</p>
 */
public final class NativeCompatibleDirectoryFactory {

    private static final Logger log = LoggerFactory.getLogger(NativeCompatibleDirectoryFactory.class);

    private NativeCompatibleDirectoryFactory() {
    }

    public static Directory open(Path path, LockFactory lockFactory) throws IOException {
        log.debug("Using NIOFSDirectory for path: {}", path);
        return new NIOFSDirectory(path, lockFactory);
    }

    public static Directory open(Path path) throws IOException {
        log.debug("Using NIOFSDirectory for path: {}", path);
        return new NIOFSDirectory(path);
    }

    public static FSDirectory openFSDirectory(Path path, LockFactory lockFactory) throws IOException {
        log.debug("Using NIOFSDirectory for path: {}", path);
        return new NIOFSDirectory(path, lockFactory);
    }

    public static FSDirectory openFSDirectory(Path path) throws IOException {
        log.debug("Using NIOFSDirectory for path: {}", path);
        return new NIOFSDirectory(path);
    }
}
