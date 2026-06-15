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

package ai.kompile.filterchain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for remote (HTTP/MCP) filters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteFilterConfig {

    /**
     * HTTP endpoint URL or MCP server ID.
     */
    private String endpoint;

    /**
     * HTTP method for HTTP filters (default: POST).
     */
    @Builder.Default
    private String httpMethod = "POST";

    /**
     * Request timeout in milliseconds.
     */
    @Builder.Default
    private int timeoutMs = 10000;

    /**
     * Number of retry attempts on failure.
     */
    @Builder.Default
    private int retries = 1;

    /**
     * Delay between retries in milliseconds.
     */
    @Builder.Default
    private int retryDelayMs = 500;

    /**
     * HTTP headers to include in requests.
     * Supports environment variable substitution: ${env:VAR_NAME}
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * Authentication configuration.
     */
    private AuthConfig authConfig;

    /**
     * For MCP filters: The tool name to invoke.
     */
    private String mcpToolName;

    /**
     * Whether to verify SSL certificates.
     */
    @Builder.Default
    private boolean verifySsl = true;

    /**
     * Authentication configuration for remote filters.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthConfig {

        /**
         * Authentication type.
         */
        public enum AuthType {
            NONE,
            API_KEY,
            BEARER,
            BASIC,
            OAUTH2
        }

        /**
         * The authentication type.
         */
        @Builder.Default
        private AuthType type = AuthType.NONE;

        /**
         * API key value (for API_KEY type).
         * Supports environment variable substitution: ${env:VAR_NAME}
         */
        private String apiKey;

        /**
         * Header name for API key (default: X-API-Key).
         */
        @Builder.Default
        private String apiKeyHeader = "X-API-Key";

        /**
         * Bearer token (for BEARER type).
         * Supports environment variable substitution: ${env:VAR_NAME}
         */
        private String bearerToken;

        /**
         * Username (for BASIC type).
         */
        private String username;

        /**
         * Password (for BASIC type).
         * Supports environment variable substitution: ${env:VAR_NAME}
         */
        private String password;

        /**
         * OAuth2 client ID (for OAUTH2 type).
         */
        private String clientId;

        /**
         * OAuth2 client secret (for OAUTH2 type).
         * Supports environment variable substitution: ${env:VAR_NAME}
         */
        private String clientSecret;

        /**
         * OAuth2 token URL.
         */
        private String tokenUrl;

        /**
         * OAuth2 scopes.
         */
        private String[] scopes;

        /**
         * Create API key authentication.
         */
        public static AuthConfig apiKey(String apiKey) {
            return AuthConfig.builder()
                    .type(AuthType.API_KEY)
                    .apiKey(apiKey)
                    .build();
        }

        /**
         * Create API key authentication with custom header.
         */
        public static AuthConfig apiKey(String apiKey, String header) {
            return AuthConfig.builder()
                    .type(AuthType.API_KEY)
                    .apiKey(apiKey)
                    .apiKeyHeader(header)
                    .build();
        }

        /**
         * Create bearer token authentication.
         */
        public static AuthConfig bearer(String token) {
            return AuthConfig.builder()
                    .type(AuthType.BEARER)
                    .bearerToken(token)
                    .build();
        }

        /**
         * Create basic authentication.
         */
        public static AuthConfig basic(String username, String password) {
            return AuthConfig.builder()
                    .type(AuthType.BASIC)
                    .username(username)
                    .password(password)
                    .build();
        }
    }

    /**
     * Resolve environment variable substitution in a value.
     * Format: ${env:VAR_NAME} or ${env:VAR_NAME:-default}
     */
    public static String resolveEnvVar(String value) {
        if (value == null || !value.contains("${env:")) {
            return value;
        }

        String result = value;
        int start;
        while ((start = result.indexOf("${env:")) != -1) {
            int end = result.indexOf("}", start);
            if (end == -1) {
                break;
            }

            String envExpression = result.substring(start + 6, end);
            String envName;
            String defaultValue = null;

            int defaultSep = envExpression.indexOf(":-");
            if (defaultSep != -1) {
                envName = envExpression.substring(0, defaultSep);
                defaultValue = envExpression.substring(defaultSep + 2);
            } else {
                envName = envExpression;
            }

            String envValue = System.getenv(envName);
            if (envValue == null) {
                envValue = defaultValue != null ? defaultValue : "";
            }

            result = result.substring(0, start) + envValue + result.substring(end + 1);
        }

        return result;
    }

    /**
     * Get the resolved endpoint with environment variables substituted.
     */
    public String getResolvedEndpoint() {
        return resolveEnvVar(endpoint);
    }

    /**
     * Get resolved headers with environment variables substituted.
     */
    public Map<String, String> getResolvedHeaders() {
        if (headers == null) {
            return new HashMap<>();
        }
        Map<String, String> resolved = new HashMap<>();
        headers.forEach((k, v) -> resolved.put(k, resolveEnvVar(v)));
        return resolved;
    }
}
