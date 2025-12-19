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

package ai.kompile.staging.auth;

import java.util.Map;

/**
 * Interface for providing authentication for archive downloads and uploads.
 * Implementations handle different authentication mechanisms (Bearer, Basic, S3).
 */
public interface ArchiveAuthProvider {

    /**
     * Get the name of this auth provider.
     */
    String getName();

    /**
     * Get the priority of this provider (higher = preferred).
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Check if this provider can handle the given URL.
     */
    boolean supportsUrl(String url);

    /**
     * Check if this provider is configured (has credentials).
     */
    boolean isConfigured();

    /**
     * Get the authorization headers for a request.
     *
     * @param url The URL being accessed
     * @return Map of header name to header value
     */
    Map<String, String> getAuthHeaders(String url);

    /**
     * Get authorization headers with additional context.
     *
     * @param url The URL being accessed
     * @param method HTTP method (GET, PUT, POST, etc.)
     * @param headers Existing headers that may affect auth (e.g., Content-Type)
     * @return Map of header name to header value
     */
    default Map<String, String> getAuthHeaders(String url, String method, Map<String, String> headers) {
        return getAuthHeaders(url);
    }

    /**
     * Refresh credentials if they are expired.
     * @return true if credentials were refreshed, false if no refresh needed
     */
    default boolean refreshCredentials() {
        return false;
    }
}
