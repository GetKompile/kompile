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

@CommandLine.Command(name = "logs", description = "View application logs.")
public class AppLogsCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--tail", "-n"}, defaultValue = "100", description = "Number of lines to show")
    private int lines;

    @CommandLine.Option(names = {"--subprocess"}, description = "Filter by subprocess type")
    private String subprocessType;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            String path = "/api/logs?lines=" + lines;
            if (subprocessType != null) {
                path += "&subprocess=" + subprocessType;
            }
            String result = client.getString(path);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to fetch logs: " + e.getMessage());
            return 1;
        }
    }
}
