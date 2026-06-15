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

@CommandLine.Command(name = "download", description = "Download a model from a registry.")
public class ModelDownloadCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--source", "-s"}, required = true, description = "Model source (e.g., huggingface, s3, http)")
    private String source;

    @CommandLine.Option(names = {"--repo", "-r"}, required = true, description = "Repository or model identifier")
    private String repo;

    @CommandLine.Option(names = {"--endpoint"}, description = "Staging service URL")
    private String endpoint;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8090", description = "Staging service port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(endpoint, port);
        try {
            Map<String, Object> body = Map.of("source", source, "repo", repo);
            String result = client.postString("/api/staging/download", body);
            System.out.println("Download initiated: " + result);
            return 0;
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
            return 1;
        }
    }
}
