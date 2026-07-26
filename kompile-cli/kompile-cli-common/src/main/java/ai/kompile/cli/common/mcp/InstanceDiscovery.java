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
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Auto-discovers a running kompile app that has MCP enabled.
 *
 * <p>Every persona — admin console, chat, crawl manager — mounts its own MCP server carrying its own
 * tools, so "any app that answers" is the honest contract here; unlike an API path, an MCP endpoint
 * does not name one owner. What is no longer guessed is <em>where</em> to look: candidates come from
 * {@link KompileServiceEndpoints}, which honours the per-service override ladder, rather than a fixed
 * port sweep that predated :8082.</p>
 */
public class InstanceDiscovery {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * Discovers a running kompile app instance with MCP support.
     * Checks the instance registry first, then the resolved endpoint of each service.
     *
     * @return the base URL of the found instance, or null if none found
     */
    public static String discover() {
        // First check the instance registry
        try {
            List<InstanceInfo> instances = InstanceRegistry.listAll();
            for (InstanceInfo info : instances) {
                if (!isAppInstance(info)) {
                    continue;
                }
                String url = info.getUrl();
                if (probeMcp(url)) {
                    return url;
                }
            }
        } catch (Exception e) {
            // Registry unavailable, fall through to the resolved endpoints
        }

        for (String url : KompileServiceEndpoints.allBaseUrls()) {
            if (probeMcp(url)) {
                return url;
            }
        }

        return null;
    }

    /** Prevent auxiliary services with an actuator endpoint from being mistaken for a kompile app. */
    static boolean isAppInstance(InstanceInfo info) {
        if (info == null || info.getType() == null) {
            return false;
        }
        if ("app".equals(info.getType())) {
            return true;
        }
        // ServiceManager registers instances under the component id, so each persona shows up as
        // its own type. All three serve MCP; none of them is "the" app any more.
        for (KompileService service : KompileService.values()) {
            if (service.componentId().equals(info.getType())) {
                return true;
            }
        }
        return false;
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
