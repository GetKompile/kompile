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

/**
 * Metadata for a single remote file discovered during listing.
 *
 * @param key            The full remote key/path (e.g., "docs/report.pdf" or "/share/files/data.csv")
 * @param fileName       The file name portion (e.g., "report.pdf")
 * @param sizeBytes      File size in bytes (-1 if unknown)
 * @param lastModifiedMs Last-modified timestamp in epoch millis (0 if unknown)
 * @param contentType    MIME type if provided by the remote source (null if unknown)
 * @param etag           ETag or checksum from the remote source (null if unavailable)
 */
public record RemoteFileEntry(
        String key,
        String fileName,
        long sizeBytes,
        long lastModifiedMs,
        String contentType,
        String etag
) {
    public String effectiveFileName() {
        if (fileName != null && !fileName.isBlank()) return fileName;
        if (key == null) return "unknown";
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }
}
