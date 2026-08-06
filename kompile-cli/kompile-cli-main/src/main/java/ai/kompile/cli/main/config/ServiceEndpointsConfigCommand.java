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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.config;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.cli.common.util.JsonUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** CLI peer of the component Connections/Settings UIs for {@code service-endpoints.json}. */
@Command(name = "endpoints",
        mixinStandardHelpOptions = true,
        description = "Show or update component dependency endpoints.%n" +
                "Run without URL options to display the current managed topology.")
public class ServiceEndpointsConfigCommand implements Callable<Integer> {

    @Option(names = "--admin-url", description = "Admin component base URL")
    private String adminUrl;

    @Option(names = "--chat-url", description = "Chat component base URL")
    private String chatUrl;

    @Option(names = "--crawl-url", description = "Crawl Manager component base URL")
    private String crawlUrl;

    @Option(names = "--staging-url", description = "Model Staging component base URL")
    private String stagingUrl;

    @Option(names = "--serving-url",
            description = "Internal Serving child base URL (localhost/loopback only)")
    private String servingUrl;

    @Option(names = {"--root", "-r"},
            description = "Project root whose managed endpoint topology should be shown or updated")
    private Path projectRoot;

    private final ServiceEndpointsConfigManager configuredManager;
    private final PrintWriter out;

    public ServiceEndpointsConfigCommand() {
        this(null, new PrintWriter(System.out, true));
    }

    ServiceEndpointsConfigCommand(
            ServiceEndpointsConfigManager configManager,
            PrintWriter out) {
        this.configuredManager = configManager;
        this.out = Objects.requireNonNull(out);
    }

    @Override
    public Integer call() throws Exception {
        ServiceEndpointsConfigManager configManager = configuredManager != null
                ? configuredManager
                : projectRoot != null
                    ? ServiceEndpointsConfigManager.forProjectDirectory(projectRoot)
                    : ServiceEndpointsConfigManager.shared();
        Map<String, Object> updates = new LinkedHashMap<>();
        addUrl(updates, KompileService.ADMIN.configKey(), adminUrl);
        addUrl(updates, KompileService.CHAT.configKey(), chatUrl);
        addUrl(updates, KompileService.CRAWL.configKey(), crawlUrl);
        addUrl(updates, ServiceEndpointsConfigManager.STAGING_URL_KEY, stagingUrl);
        addUrl(updates, ServiceEndpointsConfigManager.SERVING_URL_KEY, servingUrl);

        Map<String, Object> effective = updates.isEmpty()
                ? configManager.currentAsMap()
                : configManager.update(updates);
        out.println(JsonUtils.standardMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(effective));
        out.flush();
        return 0;
    }

    private static void addUrl(Map<String, Object> updates, String key, String rawUrl) {
        if (rawUrl == null) {
            return;
        }
        String normalized = ServiceEndpointsConfigManager.SERVING_URL_KEY.equals(key)
                ? ServiceEndpointsConfigManager.requireLoopbackServingBaseUrl(rawUrl)
                : ServiceEndpointsConfigManager.requireHttpBaseUrl(key, rawUrl);
        updates.put(key, normalized);
    }
}
