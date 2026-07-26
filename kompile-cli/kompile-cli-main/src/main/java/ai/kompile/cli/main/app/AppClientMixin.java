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
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.main.graph.GraphServiceRouting;
import picocli.CommandLine;

/**
 * Shared Picocli mixin for commands that talk to a running kompile-app instance.
 * Provides connection options and client construction with auto-discovery.
 */
public class AppClientMixin {

    @CommandLine.Option(names = {"--url"}, description = "Pin one backend base URL (e.g. http://localhost:8080). "
            + "Default: route each API to its own service — admin :8080, chat :8081, crawl :8082")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, description = "Localhost port of kompile-app")
    private Integer port;

    @CommandLine.Option(names = {"--graph-url"},
            description = "Base URL of the authoritative kompile-graph-service")
    private String graphUrl;

    @CommandLine.Option(names = {"--json"}, description = "Output raw JSON instead of formatted text")
    private boolean jsonOutput;

    public boolean isJsonOutput() {
        return jsonOutput;
    }

    /**
     * Resolves a KompileHttpClient: {@code --url} > {@code --port} > per-path routing.
     * Returns null and prints an error if nothing is reachable.
     *
     * <p>Naming a server pins the client to it — one host, every request, which is what an
     * all-in-one deployment needs. Otherwise the client routes each request by path
     * ({@link KompileServiceEndpoints}), because the API surface now spans three processes: crawl
     * and indexing live on {@code kompile-app-crawl-manager}, chat and RAG on
     * {@code kompile-app-chat}, and only the admin contracts on {@code kompile-app-main}. Probing
     * for "the" instance was the old answer and is now a coin flip between personas.</p>
     */
    public KompileHttpClient requireClient() {
        String pinned = pinnedUrl();
        KompileHttpClient client = pinned != null ? new KompileHttpClient(pinned) : KompileHttpClient.routed();
        if (!client.isHealthy()) {
            if (pinned != null) {
                System.err.println("Error: kompile-app is not reachable at " + pinned);
                System.err.println("Start the application or specify --url / --port.");
            } else {
                System.err.println("Error: no kompile service is reachable. Checked:");
                for (KompileService service : KompileService.values()) {
                    KompileServiceEndpoints.Resolution route = KompileServiceEndpoints.resolve(service);
                    System.err.println("  " + service.componentId() + " -> " + route.baseUrl()
                            + " (" + route.source().name().toLowerCase().replace('_', ' ') + ")");
                }
                System.err.println("Start one, or specify --url / --port.");
            }
            return null;
        }
        OutputFormatter.info("Connected to " + (pinned != null ? pinned : "kompile services (routed by API)"));
        return client;
    }

    /** Resolves the standalone graph service for graph-owned contracts. */
    public KompileHttpClient requireGraphClient() {
        GraphServiceRouting.Resolution route = GraphServiceRouting.resolve(graphUrl);
        KompileHttpClient client = new KompileHttpClient(route.baseUrl());
        if (!client.isHealthy()) {
            System.err.println("Error: kompile-graph-service is not reachable at " + route.baseUrl());
            System.err.println("Start kompile-graph-service or specify --graph-url.");
            return null;
        }
        OutputFormatter.info("Connected to graph service at " + route.baseUrl());
        return client;
    }

    /** The server the user named with {@code --url} / {@code --port}, or null to route by path. */
    private String pinnedUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (port != null) {
            return "http://localhost:" + port;
        }
        return null;
    }
}
