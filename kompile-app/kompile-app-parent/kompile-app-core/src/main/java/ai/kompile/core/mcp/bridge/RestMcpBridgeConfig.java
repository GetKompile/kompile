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

package ai.kompile.core.mcp.bridge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a REST-MCP bridge that maps REST APIs to MCP tools and vice versa.
 * Supports bidirectional bridging:
 * - REST-to-MCP: Expose REST endpoints as MCP tools
 * - MCP-to-REST: Expose MCP servers as REST endpoints
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestMcpBridgeConfig {

    /**
     * Unique identifier for this bridge configuration
     */
    private String id;

    /**
     * Human-readable name for the bridge
     */
    private String name;

    /**
     * Description of the bridge's purpose
     */
    private String description;

    /**
     * Bridge direction: REST_TO_MCP or MCP_TO_REST
     */
    @Builder.Default
    private BridgeDirection direction = BridgeDirection.REST_TO_MCP;

    /**
     * Whether the bridge is currently active
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Status of the bridge
     */
    @Builder.Default
    private BridgeStatus status = BridgeStatus.STOPPED;

    /**
     * REST API configuration (source for REST_TO_MCP, target for MCP_TO_REST)
     */
    private RestApiConfig restApiConfig;

    /**
     * MCP server configuration (target for REST_TO_MCP, source for MCP_TO_REST)
     */
    private McpServerRef mcpServerRef;

    /**
     * List of endpoint mappings
     */
    @Builder.Default
    private List<EndpointMapping> mappings = new ArrayList<>();

    /**
     * Authentication configuration for the REST API
     */
    private AuthConfig authConfig;

    /**
     * Global request transformation rules
     */
    private TransformConfig requestTransform;

    /**
     * Global response transformation rules
     */
    private TransformConfig responseTransform;

    /**
     * Creation timestamp
     */
    private Instant createdAt;

    /**
     * Last modified timestamp
     */
    private Instant updatedAt;

    /**
     * Bridge direction enumeration
     */
    public enum BridgeDirection {
        /**
         * Expose REST endpoints as MCP tools
         */
        REST_TO_MCP,
        /**
         * Expose MCP tools as REST endpoints
         */
        MCP_TO_REST
    }

    /**
     * Bridge status
     */
    public enum BridgeStatus {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR,
        SYNCING
    }

    /**
     * REST API configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestApiConfig {
        /**
         * Base URL of the REST API
         */
        private String baseUrl;

        /**
         * OpenAPI/Swagger spec URL for auto-discovery
         */
        private String openApiUrl;

        /**
         * Default timeout in milliseconds
         */
        @Builder.Default
        private int timeoutMs = 30000;

        /**
         * Default headers to include in all requests
         */
        private Map<String, String> defaultHeaders;

        /**
         * Whether to verify SSL certificates
         */
        @Builder.Default
        private boolean verifySsl = true;

        /**
         * Rate limiting: max requests per second (0 = unlimited)
         */
        @Builder.Default
        private int rateLimitPerSecond = 0;
    }

    /**
     * Reference to an MCP server
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpServerRef {
        /**
         * ID of the MCP server configuration
         */
        private String serverId;

        /**
         * Or direct URL if external MCP server
         */
        private String serverUrl;

        /**
         * Port for the bridged MCP server (for REST_TO_MCP)
         */
        @Builder.Default
        private int port = 8082;

        /**
         * Base path for the MCP server
         */
        @Builder.Default
        private String basePath = "/mcp-bridge";
    }

    /**
     * Mapping between a REST endpoint and MCP tool
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointMapping {
        /**
         * Unique ID for this mapping
         */
        private String id;

        /**
         * Whether this mapping is enabled
         */
        @Builder.Default
        private boolean enabled = true;

        /**
         * REST endpoint configuration
         */
        private RestEndpoint restEndpoint;

        /**
         * MCP tool configuration
         */
        private McpToolMapping mcpTool;

        /**
         * Request transformation for this specific mapping
         */
        private TransformConfig requestTransform;

        /**
         * Response transformation for this specific mapping
         */
        private TransformConfig responseTransform;

        /**
         * Parameter mappings between REST and MCP
         */
        @Builder.Default
        private List<ParameterMapping> parameterMappings = new ArrayList<>();
    }

    /**
     * REST endpoint definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestEndpoint {
        /**
         * HTTP method (GET, POST, PUT, DELETE, PATCH)
         */
        @Builder.Default
        private String method = "POST";

        /**
         * Path relative to base URL (supports path parameters like /users/{id})
         */
        private String path;

        /**
         * Content type for request body
         */
        @Builder.Default
        private String contentType = "application/json";

        /**
         * Expected response content type
         */
        @Builder.Default
        private String acceptType = "application/json";

        /**
         * Query parameters definition
         */
        private List<ParameterDef> queryParams;

        /**
         * Path parameters definition
         */
        private List<ParameterDef> pathParams;

        /**
         * Request body schema (JSON Schema)
         */
        private Object requestBodySchema;

        /**
         * Response body schema (JSON Schema)
         */
        private Object responseBodySchema;
    }

    /**
     * MCP tool mapping definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpToolMapping {
        /**
         * Tool name (snake_case recommended)
         */
        private String name;

        /**
         * Tool description for LLM context
         */
        private String description;

        /**
         * Input schema (auto-generated from REST if not specified)
         */
        private Object inputSchema;

        /**
         * Category/tag for organizing tools
         */
        private String category;
    }

    /**
     * Parameter definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {
        private String name;
        private String type;
        private String description;
        @Builder.Default
        private boolean required = false;
        private Object defaultValue;
        private List<Object> enumValues;
    }

    /**
     * Mapping between REST and MCP parameters
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterMapping {
        /**
         * Source parameter name
         */
        private String sourceName;

        /**
         * Target parameter name
         */
        private String targetName;

        /**
         * Source location (query, path, body, header)
         */
        private String sourceLocation;

        /**
         * Target location
         */
        private String targetLocation;

        /**
         * Transformation expression (JSONPath, JMESPath, or simple mapping)
         */
        private String transform;
    }

    /**
     * Transformation configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransformConfig {
        /**
         * Transformation type
         */
        @Builder.Default
        private TransformType type = TransformType.PASSTHROUGH;

        /**
         * JSONPath expression for extraction
         */
        private String jsonPath;

        /**
         * JMESPath expression for transformation
         */
        private String jmesPath;

        /**
         * JavaScript transformation function
         */
        private String script;

        /**
         * Template string with {{variable}} placeholders
         */
        private String template;

        /**
         * Field mappings for simple object transformation
         */
        private Map<String, String> fieldMappings;
    }

    /**
     * Transformation types
     */
    public enum TransformType {
        PASSTHROUGH,
        JSON_PATH,
        JMES_PATH,
        JAVASCRIPT,
        TEMPLATE,
        FIELD_MAPPING
    }

    /**
     * Authentication configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthConfig {
        /**
         * Authentication type
         */
        @Builder.Default
        private AuthType type = AuthType.NONE;

        /**
         * API key value (for API_KEY type)
         */
        private String apiKey;

        /**
         * API key header name
         */
        @Builder.Default
        private String apiKeyHeader = "X-API-Key";

        /**
         * Bearer token (for BEARER type)
         */
        private String bearerToken;

        /**
         * Basic auth username
         */
        private String username;

        /**
         * Basic auth password
         */
        private String password;

        /**
         * OAuth2 configuration
         */
        private OAuth2Config oauth2;
    }

    /**
     * Authentication types
     */
    public enum AuthType {
        NONE,
        API_KEY,
        BEARER,
        BASIC,
        OAUTH2
    }

    /**
     * OAuth2 configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OAuth2Config {
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
        @Builder.Default
        private String grantType = "client_credentials";
    }
}
