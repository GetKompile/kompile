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

package ai.kompile.cli.common.mcp;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Auto-discovers running kompile-app instances that have MCP enabled.
 */
public class InstanceDiscovery {

    private static final int[] PROBE_PORTS = {8080, 8081, 9090, 9091};
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * Discovers a running kompile-app instance with MCP support.
     * Checks the instance registry first, then probes common ports.
     *
     * @return the base URL of the found instance, or null if none found
     */
    public static String discover() {
        // First check the instance registry
        try {
            List<InstanceInfo> instances = InstanceRegistry.listAll();
            for (InstanceInfo info : instances) {
                String url = info.getUrl();
                if (probeMcp(url)) {
                    return url;
                }
            }
        } catch (Exception e) {
            // Registry unavailable, fall through to port probing
        }

        // Probe common ports on localhost
        for (int port : PROBE_PORTS) {
            String url = "http://localhost:" + port;
            if (probeMcp(url)) {
                return url;
            }
        }

        return null;
    }

    /**
     * Probes whether an MCP server is running at the given URL.
     * Tries the /sse endpoint with a quick HEAD/GET to see if it responds.
     */
    private static boolean probeMcp(String baseUrl) {
        try {
            // Try the actuator health endpoint first (fast check)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/health"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
