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

import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "search", description = "Vector similarity search.")
public class AppSearchCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--text", "-t"}, description = "Search text", required = true)
    private String text;

    @CommandLine.Option(names = {"--max-results", "-k"}, defaultValue = "5", description = "Maximum number of results")
    private int maxResults;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            Map<String, Object> body = Map.of("query", text, "maxResults", maxResults);
            String result = client.postString("/api/search/similarity", body);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return 1;
        }
    }
}
