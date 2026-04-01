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
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Check health and component status of a running application.")
public class AppStatusCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            if (client.isHealthy()) {
                System.out.println("Status: UP");
                String body = client.getString("/actuator/health");
                System.out.println(body);
                return 0;
            } else {
                System.out.println("Status: DOWN (not reachable at " + client.getBaseUrl() + ")");
                return 1;
            }
        } catch (Exception e) {
            System.err.println("Error checking status: " + e.getMessage());
            return 1;
        }
    }
}
