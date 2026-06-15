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

package ai.kompile.cli.common.util;

/**
 * Shared HTTP constants used across kompile modules.
 * Centralises commonly repeated string literals for headers and media types.
 */
public final class HttpConstants {

    private HttpConstants() {}

    // Media types
    public static final String APPLICATION_JSON = "application/json";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";

    // Header names
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String ACCEPT = "Accept";
    public static final String AUTHORIZATION = "Authorization";
    public static final String X_API_KEY = "X-API-Key";

    // Header value prefixes
    public static final String BEARER_PREFIX = "Bearer ";
}
