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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for the filter chain.
 */
@Data
@ConfigurationProperties(prefix = "kompile.filterchain")
public class FilterChainProperties {

    /**
     * Whether the filter chain is enabled.
     */
    private boolean enabled = false;

    /**
     * Global timeout for remote filters in milliseconds.
     */
    private int globalTimeoutMs = 30000;

    /**
     * Whether to continue execution if a filter fails.
     */
    private boolean continueOnError = false;

    /**
     * Whether to collect execution traces.
     */
    private boolean tracingEnabled = true;

    /**
     * Maximum traces to keep per request.
     */
    private int maxTracesPerRequest = 100;

    /**
     * HTTP client configuration.
     */
    private HttpClientConfig httpClient = new HttpClientConfig();

    /**
     * Configuration for the HTTP client used by remote filters.
     */
    @Data
    public static class HttpClientConfig {

        /**
         * Connection timeout in milliseconds.
         */
        private int connectTimeoutMs = 5000;

        /**
         * Read timeout in milliseconds.
         */
        private int readTimeoutMs = 30000;

        /**
         * Maximum connections per host.
         */
        private int maxConnectionsPerHost = 10;

        /**
         * Maximum total connections.
         */
        private int maxTotalConnections = 50;

        /**
         * Whether to follow redirects.
         */
        private boolean followRedirects = true;

        /**
         * Whether to verify SSL certificates (set false for development).
         */
        private boolean verifySsl = true;
    }
}
