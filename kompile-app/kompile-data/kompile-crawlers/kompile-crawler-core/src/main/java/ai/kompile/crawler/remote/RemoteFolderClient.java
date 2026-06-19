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

package ai.kompile.crawler.remote;

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SPI for remote folder backends (S3, SFTP, SMB).
 * Each implementation handles listing and downloading files from a specific protocol.
 */
public interface RemoteFolderClient extends Closeable {

    /**
     * The source type this client handles.
     */
    SourceType sourceType();

    /**
     * Initialize the client from connection properties.
     *
     * <p>Common properties by source type:</p>
     * <ul>
     *   <li><b>S3</b>: {@code accessKey}, {@code secretKey}, {@code region}, {@code endpoint} (for MinIO/S3-compatible)</li>
     *   <li><b>SFTP</b>: {@code host}, {@code port} (default 22), {@code username}, {@code password} or {@code privateKeyPath}</li>
     *   <li><b>SMB</b>: {@code host}, {@code port} (default 445), {@code username}, {@code password}, {@code domain}</li>
     * </ul>
     *
     * @param pathOrUrl the bucket/prefix (S3), remote path (SFTP), or share path (SMB)
     * @param properties connection-specific configuration
     */
    void connect(String pathOrUrl, Map<String, Object> properties) throws IOException;

    /**
     * List all files under the configured path, recursively up to maxDepth.
     *
     * @param maxDepth maximum directory depth to recurse (0 = unlimited)
     * @return discovered remote file entries
     */
    List<RemoteFileEntry> listFiles(int maxDepth) throws IOException;

    /**
     * Download a remote file to a local path.
     *
     * @param remoteKey the remote key/path from {@link RemoteFileEntry#key()}
     * @param localDest the local file to write to
     */
    void download(String remoteKey, Path localDest) throws IOException;
}
