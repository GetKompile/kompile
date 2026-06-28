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

import io.anserini.search.LuceneRuntimeConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory for the Lucene {@link Directory} backing the vector store.
 *
 * <p>The index — postings, stored fields, and (critically) the vector/HNSW data — is always served
 * OFF-heap via the OS page cache ({@link MMapDirectory}). On-heap NIO buffers would pull index data
 * onto the JVM heap and inflate {@code -Xmx} for a large graph/vector index; mmap keeps it off-heap.
 * There is deliberately no NIO fallback and no mode switch — mmap is the only correct choice on
 * every runtime we ship.</p>
 *
 * <p>Native-image compatibility is handled automatically by {@link LuceneRuntimeConfig} (it selects
 * Lucene's native-image-safe legacy mmap provider when, and only when, running inside a GraalVM
 * native image). Nothing here, and nobody launching the app, has to know about it.</p>
 */
public final class NativeCompatibleDirectoryFactory {

    private static final Logger log = LoggerFactory.getLogger(NativeCompatibleDirectoryFactory.class);

    static {
        // Choose the native-image-safe mmap provider before the first MMapDirectory is constructed.
        LuceneRuntimeConfig.ensure();
    }

    private NativeCompatibleDirectoryFactory() {
    }

    public static Directory open(Path path, LockFactory lockFactory) throws IOException {
        return openFSDirectory(path, lockFactory);
    }

    public static Directory open(Path path) throws IOException {
        return openFSDirectory(path);
    }

    public static FSDirectory openFSDirectory(Path path, LockFactory lockFactory) throws IOException {
        log.debug("Opening MMapDirectory (off-heap, OS page cache) for path: {}", path);
        return new MMapDirectory(path, lockFactory);
    }

    public static FSDirectory openFSDirectory(Path path) throws IOException {
        log.debug("Opening MMapDirectory (off-heap, OS page cache) for path: {}", path);
        return new MMapDirectory(path);
    }
}
