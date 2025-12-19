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

import java.util.List;
import java.util.Optional;

/**
 * Interface for managing REST-MCP bridges.
 * Provides CRUD operations and lifecycle management for bridges.
 */
public interface RestMcpBridgeManager {

    /**
     * Creates a new bridge configuration.
     *
     * @param config the bridge configuration
     * @return the created configuration with generated ID
     */
    RestMcpBridgeConfig createBridge(RestMcpBridgeConfig config);

    /**
     * Updates an existing bridge configuration.
     *
     * @param id the bridge ID
     * @param config the updated configuration
     * @return the updated configuration
     */
    RestMcpBridgeConfig updateBridge(String id, RestMcpBridgeConfig config);

    /**
     * Deletes a bridge configuration.
     *
     * @param id the bridge ID
     */
    void deleteBridge(String id);

    /**
     * Gets a bridge configuration by ID.
     *
     * @param id the bridge ID
     * @return optional containing the configuration if found
     */
    Optional<RestMcpBridgeConfig> getBridge(String id);

    /**
     * Lists all bridge configurations.
     *
     * @return list of all bridges
     */
    List<RestMcpBridgeConfig> listBridges();

    /**
     * Starts a bridge.
     *
     * @param id the bridge ID
     * @return the updated configuration with RUNNING status
     */
    RestMcpBridgeConfig startBridge(String id);

    /**
     * Stops a bridge.
     *
     * @param id the bridge ID
     * @return the updated configuration with STOPPED status
     */
    RestMcpBridgeConfig stopBridge(String id);

    /**
     * Gets the current status of a bridge.
     *
     * @param id the bridge ID
     * @return the bridge status
     */
    RestMcpBridgeConfig.BridgeStatus getBridgeStatus(String id);

    /**
     * Discovers endpoints from an OpenAPI specification.
     *
     * @param openApiUrl URL to the OpenAPI/Swagger spec
     * @return list of discovered endpoint mappings
     */
    List<RestMcpBridgeConfig.EndpointMapping> discoverEndpoints(String openApiUrl);

    /**
     * Discovers endpoints from a base URL by probing common patterns.
     *
     * @param baseUrl the API base URL
     * @return list of discovered endpoint mappings
     */
    List<RestMcpBridgeConfig.EndpointMapping> probeEndpoints(String baseUrl);

    /**
     * Tests a specific endpoint mapping.
     *
     * @param bridgeId the bridge ID
     * @param mappingId the mapping ID
     * @param testInput test input data
     * @return test result with response or error
     */
    EndpointTestResult testMapping(String bridgeId, String mappingId, Object testInput);

    /**
     * Syncs bridge mappings with the target (re-discovers and updates).
     *
     * @param id the bridge ID
     * @return the updated configuration
     */
    RestMcpBridgeConfig syncBridge(String id);

    /**
     * Validates a bridge configuration.
     *
     * @param config the configuration to validate
     * @return list of validation errors (empty if valid)
     */
    List<String> validateConfig(RestMcpBridgeConfig config);

    /**
     * Exports a bridge configuration to JSON.
     *
     * @param id the bridge ID
     * @return JSON string
     */
    String exportConfig(String id);

    /**
     * Imports a bridge configuration from JSON.
     *
     * @param json the JSON configuration
     * @return the imported configuration
     */
    RestMcpBridgeConfig importConfig(String json);

    /**
     * Result of testing an endpoint mapping
     */
    class EndpointTestResult {
        private boolean success;
        private int statusCode;
        private Object response;
        private String error;
        private long durationMs;

        public EndpointTestResult() {}

        public EndpointTestResult(boolean success, int statusCode, Object response, String error, long durationMs) {
            this.success = success;
            this.statusCode = statusCode;
            this.response = response;
            this.error = error;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        public Object getResponse() { return response; }
        public void setResponse(Object response) { this.response = response; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    }
}
