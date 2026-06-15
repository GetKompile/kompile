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

package ai.kompile.app.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Service for determining the actual server port the application is running on.
 * Handles cases where port 0 was configured (random port assignment) or a specific port was set.
 */
@Service
public class ServerPortService {

    private static final Logger log = LoggerFactory.getLogger(ServerPortService.class);

    private final ServletWebServerApplicationContext webServerAppContext;
    private final Environment environment;

    @Autowired
    public ServerPortService(
            @Autowired(required = false) ServletWebServerApplicationContext webServerAppContext,
            Environment environment) {
        this.webServerAppContext = webServerAppContext;
        this.environment = environment;
    }

    /**
     * Get the actual server port the application is running on.
     * This handles cases where port 0 was configured (random port) or a specific port was set.
     *
     * @return the actual port number the server is listening on
     */
    public int getActualPort() {
        // First try to get the port from the running web server (most accurate)
        if (webServerAppContext != null) {
            try {
                int port = webServerAppContext.getWebServer().getPort();
                if (port > 0) {
                    log.debug("Got actual port {} from WebServer", port);
                    return port;
                }
            } catch (Exception e) {
                log.debug("Could not get port from WebServer: {}", e.getMessage());
            }
        }

        // Try the local.server.port property (set after server starts)
        String localPort = environment.getProperty("local.server.port");
        if (localPort != null && !localPort.isEmpty()) {
            try {
                int port = Integer.parseInt(localPort);
                log.debug("Got actual port {} from local.server.port", port);
                return port;
            } catch (NumberFormatException e) {
                log.debug("Could not parse local.server.port: {}", localPort);
            }
        }

        // Fall back to configured server.port
        String configuredPort = environment.getProperty("server.port", "8080");
        try {
            int port = Integer.parseInt(configuredPort);
            // If configured port is 0, we can't use it - default to 8080
            if (port > 0) {
                log.debug("Using configured port {}", port);
                return port;
            }
            log.warn("Configured port is 0 and actual port not available, defaulting to 8080");
            return 8080;
        } catch (NumberFormatException e) {
            log.warn("Could not parse server.port '{}', defaulting to 8080", configuredPort);
            return 8080;
        }
    }

    /**
     * Get the base URL for the server (e.g., "http://localhost:8080").
     *
     * @return the base URL string
     */
    public String getBaseUrl() {
        return "http://localhost:" + getActualPort();
    }

    /**
     * Get the MCP API base URL.
     *
     * @return the MCP API URL (e.g., "http://localhost:8080/api/mcp")
     */
    public String getMcpApiUrl() {
        return getBaseUrl() + "/api/mcp";
    }

    /**
     * Get the tools invoke endpoint URL.
     *
     * @return the tools invoke URL (e.g., "http://localhost:8080/api/mcp/tools/invoke-direct")
     */
    public String getToolsInvokeUrl() {
        return getBaseUrl() + "/api/mcp/tools/invoke-direct";
    }
}
