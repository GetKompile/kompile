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

package ai.kompile.core.filter;

/**
 * Defines the type of filter implementation.
 * This determines how the filter is executed.
 */
public enum FilterType {

    /**
     * A built-in filter implemented as a local Java class.
     * These are registered as Spring beans and executed in-process.
     */
    LOCAL,

    /**
     * A remote filter accessible via HTTP REST API.
     * The filter service must respond with appropriate HTTP status codes:
     * - 200: Success, continue with mutations
     * - 4xx: User error, terminate request
     * - 5xx: Fatal error, terminate request
     */
    HTTP,

    /**
     * A remote filter accessible via Model Context Protocol (MCP).
     * The filter is invoked as an MCP tool on a registered MCP server.
     */
    MCP
}
