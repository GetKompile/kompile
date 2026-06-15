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

package ai.kompile.cli.model;

import ai.kompile.cli.common.http.KompileHttpClient;
import picocli.CommandLine;

import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "serve", description = "Launch a model serving endpoint via the staging service.")
public class ModelServeCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--model", "-m"}, required = true, description = "Model ID to serve")
    private String modelId;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8091", description = "Port for serving endpoint")
    private int port;

    @CommandLine.Option(names = {"--endpoint"}, description = "Staging service URL")
    private String endpoint;

    @CommandLine.Option(names = {"--staging-port"}, defaultValue = "8090", description = "Staging service port")
    private int stagingPort;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(endpoint, stagingPort);
        try {
            if (!client.isHealthy()) {
                System.err.println("Staging service not reachable at " + client.getBaseUrl());
                System.err.println("Start the staging service first, or specify --endpoint / --staging-port.");
                return 1;
            }

            Map<String, Object> body = Map.of(
                    "modelId", modelId,
                    "port", port
            );
            String result = client.postString("/api/staging/serve", body);
            System.out.println("Serving model " + modelId + " on port " + port);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to start model serving: " + e.getMessage());
            return 1;
        }
    }
}
