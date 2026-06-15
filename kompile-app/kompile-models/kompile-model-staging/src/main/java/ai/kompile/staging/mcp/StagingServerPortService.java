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

package ai.kompile.staging.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class StagingServerPortService {

    private static final Logger log = LoggerFactory.getLogger(StagingServerPortService.class);

    private final ServletWebServerApplicationContext webServerAppContext;
    private final Environment environment;

    @Autowired
    public StagingServerPortService(
            @Autowired(required = false) ServletWebServerApplicationContext webServerAppContext,
            Environment environment) {
        this.webServerAppContext = webServerAppContext;
        this.environment = environment;
    }

    public int getActualPort() {
        if (webServerAppContext != null) {
            try {
                int port = webServerAppContext.getWebServer().getPort();
                if (port > 0) return port;
            } catch (Exception e) {
                log.debug("Could not get port from WebServer: {}", e.getMessage());
            }
        }

        String localPort = environment.getProperty("local.server.port");
        if (localPort != null && !localPort.isEmpty()) {
            try {
                return Integer.parseInt(localPort);
            } catch (NumberFormatException e) {
                log.debug("Could not parse local.server.port: {}", localPort);
            }
        }

        String configuredPort = environment.getProperty("server.port", "8090");
        try {
            int port = Integer.parseInt(configuredPort);
            if (port > 0) return port;
            return 8090;
        } catch (NumberFormatException e) {
            return 8090;
        }
    }

    public String getBaseUrl() {
        return "http://localhost:" + getActualPort();
    }
}
