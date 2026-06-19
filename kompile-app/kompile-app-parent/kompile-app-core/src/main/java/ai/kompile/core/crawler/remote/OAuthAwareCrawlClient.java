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

package ai.kompile.core.crawler.remote;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unified abstraction for OAuth-backed remote crawlers (Google Drive, OneDrive, SharePoint, etc.).
 *
 * <p>Implementations are expected to perform all authentication via short-lived Bearer tokens
 * supplied at connect time and refreshed on demand via {@link #refreshConnection(String)}.
 * The interface deliberately mirrors the shape of {@link ai.kompile.crawler.remote.RemoteFolderClient}
 * from kompile-crawler-core but adds OAuth-specific concerns: token management, connection health,
 * and folder-based item listing rather than flat file listing.</p>
 *
 * <h3>Typical lifecycle</h3>
 * <pre>
 *   client.connect(rootFolderId, properties, accessToken);
 *   List&lt;RemoteFileEntry&gt; items = client.listItems("root", 3);
 *   client.downloadFile(items.get(0).key(), localPath);
 *   // token expired? caller calls refreshConnection(newToken)
 *   client.close();
 * </pre>
 */
public interface OAuthAwareCrawlClient extends Closeable {

    /**
     * Returns the OAuth provider identifier for this client.
     * Used to correlate {@link ConnectionHealthEvent}s and route token-refresh callbacks.
     * Examples: {@code "google"}, {@code "microsoft"}, {@code "dropbox"}.
     */
    String getProviderId();

    /**
     * Initialises the client and establishes a logical connection to the remote resource.
     *
     * @param pathOrUrl   root folder ID, drive ID, or URL (provider-specific interpretation)
     * @param properties  provider-specific connection options (client ID, tenant, scopes, etc.)
     * @param accessToken valid OAuth 2.0 Bearer access token
     * @throws IOException if the connection cannot be established
     */
    void connect(String pathOrUrl, Map<String, Object> properties, String accessToken) throws IOException;

    /**
     * Lists all items (files and optionally folders) reachable from {@code folderId}
     * up to {@code maxDepth} levels of nesting.
     *
     * @param folderId the root folder to start listing from (provider-specific ID or path)
     * @param maxDepth maximum recursion depth (0 = unlimited; 1 = immediate children only)
     * @return discovered remote file entries, never {@code null}
     * @throws IOException if the listing request fails
     */
    List<RemoteFileEntry> listItems(String folderId, int maxDepth) throws IOException;

    /**
     * Downloads the content of the specified item to a local file.
     *
     * @param itemId    the remote item identifier (matches {@link RemoteFileEntry#key()})
     * @param localDest local path to write the downloaded content to; parent directories
     *                  will be created if they do not exist
     * @throws IOException if the download fails
     */
    void downloadFile(String itemId, Path localDest) throws IOException;

    /**
     * Returns {@code true} if the client currently has a usable connection to the
     * remote provider — i.e., the token has been set and no unrecoverable error has
     * been recorded since the last successful call.
     */
    boolean isConnected();

    /**
     * Replaces the active access token with a freshly obtained one.
     * Implementations should update any stored credential and, if possible, proactively
     * verify that the new token is valid before returning.
     *
     * @param newAccessToken the replacement Bearer token
     * @throws IOException if the token cannot be applied or validation fails
     */
    void refreshConnection(String newAccessToken) throws IOException;
}
