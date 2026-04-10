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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Configuration for per-service device routing.
 * Allows different subprocess types (embedding, ingest, vectorPopulation, modelInit)
 * to run on different devices (CPU, specific GPU).
 *
 * <p>When enabled, the global {@link Nd4jEnvironmentConfig} is merged with
 * per-service overrides before being passed to each subprocess.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceRoutingConfig(
        @JsonProperty("serviceRoutes") Map<String, ServiceDeviceConfig> serviceRoutes,
        @JsonProperty("enabled") Boolean enabled
) {

    /** Service type constants */
    public static final String SERVICE_EMBEDDING = "embedding";
    public static final String SERVICE_VECTOR_POPULATION = "vectorPopulation";
    public static final String SERVICE_INGEST = "ingest";
    public static final String SERVICE_MODEL_INIT = "modelInit";
    public static final String SERVICE_VLM = "vlm";
    public static final String SERVICE_VLM_ENCODER = "vlmEncoder";
    public static final String SERVICE_VLM_DECODER = "vlmDecoder";

    /**
     * Per-service device configuration overlay.
     * Only non-null fields override the global ND4J config.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceDeviceConfig(
            @JsonProperty("deviceType") String deviceType,       // "cpu", "cuda", or null (use global)
            @JsonProperty("cudaDeviceId") Integer cudaDeviceId,  // GPU index when deviceType="cuda"
            @JsonProperty("maxThreads") Integer maxThreads,      // Override thread count
            @JsonProperty("maxDeviceMemory") Long maxDeviceMemory // Memory limit override (bytes)
    ) {}

    /**
     * Returns a default (disabled) configuration with no service routes.
     */
    public static DeviceRoutingConfig defaults() {
        return new DeviceRoutingConfig(Map.of(), false);
    }

    /**
     * Check if routing is enabled and has a config for the given service.
     */
    public boolean hasRouteFor(String serviceType) {
        return Boolean.TRUE.equals(enabled)
                && serviceRoutes != null
                && serviceRoutes.containsKey(serviceType);
    }
}
