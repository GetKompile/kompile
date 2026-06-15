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

package ai.kompile.cli.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "stop", description = "Gracefully stop a running Kompile application.")
public class AppStopCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url"}, description = "Application URL (overrides auto-discovery)")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @CommandLine.Option(names = {"--name"}, defaultValue = "default", description = "Instance name")
    private String name;

    @Override
    public Integer call() throws Exception {
        String targetUrl = url;
        if (targetUrl == null) {
            InstanceInfo info = InstanceRegistry.get(name);
            if (info != null) {
                targetUrl = info.getUrl();
            } else {
                targetUrl = "http://localhost:" + port;
            }
        }

        KompileHttpClient client = new KompileHttpClient(targetUrl);
        try {
            client.postEmpty("/actuator/shutdown");
            System.out.println("Shutdown signal sent to " + targetUrl);
            InstanceRegistry.unregister(name);
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to stop application at " + targetUrl + ": " + e.getMessage());
            return 1;
        }
    }
}
