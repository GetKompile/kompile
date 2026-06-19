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

/**
 * Metadata for a single remote file discovered during listing by an OAuth-backed crawler.
 *
 * <p>Mirrors {@code ai.kompile.crawler.remote.RemoteFileEntry} from kompile-crawler-core
 * but lives in kompile-app-core so that {@link OAuthAwareCrawlClient} and
 * {@link AbstractOAuthCrawlClient} can reference it without introducing a module
 * dependency on kompile-crawler-core.</p>
 *
 * @param key            The full remote identifier (e.g., Drive file ID, OneDrive item ID, or path)
 * @param fileName       The human-readable file name (e.g., "report.pdf")
 * @param sizeBytes      File size in bytes (-1 if unknown)
 * @param lastModifiedMs Last-modified timestamp in epoch millis (0 if unknown)
 * @param contentType    MIME type reported by the remote provider (null if unknown)
 * @param etag           ETag or version token from the remote provider (null if unavailable)
 */
public record RemoteFileEntry(
        String key,
        String fileName,
        long sizeBytes,
        long lastModifiedMs,
        String contentType,
        String etag
) {
    /**
     * Returns the best available file name: prefers the explicit {@link #fileName},
     * falls back to the last path segment of {@link #key}, and returns "unknown" if
     * neither is available.
     */
    public String effectiveFileName() {
        if (fileName != null && !fileName.isBlank()) return fileName;
        if (key == null) return "unknown";
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }
}
