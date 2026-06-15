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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.mcp.InstanceDiscovery;
import picocli.CommandLine;

/**
 * Shared Picocli mixin for commands that talk to a running kompile-app instance.
 * Provides connection options and client construction with auto-discovery.
 */
public class AppClientMixin {

    @CommandLine.Option(names = {"--url"}, description = "Base URL of kompile-app (e.g. http://localhost:8080)")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, description = "Localhost port of kompile-app")
    private Integer port;

    @CommandLine.Option(names = {"--json"}, description = "Output raw JSON instead of formatted text")
    private boolean jsonOutput;

    public boolean isJsonOutput() {
        return jsonOutput;
    }

    /**
     * Resolves a KompileHttpClient using: --url > --port > auto-discovery > default 8080.
     * Returns null and prints an error if the instance is unreachable.
     */
    public KompileHttpClient requireClient() {
        String resolved;
        if (url != null && !url.isBlank()) {
            resolved = url;
        } else if (port != null) {
            resolved = "http://localhost:" + port;
        } else {
            resolved = InstanceDiscovery.discover();
            if (resolved == null) {
                resolved = "http://localhost:8080";
            }
        }

        KompileHttpClient client = new KompileHttpClient(resolved);
        if (!client.isHealthy()) {
            System.err.println("Error: kompile-app is not reachable at " + resolved);
            System.err.println("Start the application or specify --url / --port.");
            return null;
        }
        OutputFormatter.info("Connected to " + resolved);
        return client;
    }
}
