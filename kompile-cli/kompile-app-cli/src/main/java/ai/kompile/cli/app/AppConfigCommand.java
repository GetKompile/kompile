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

@CommandLine.Command(name = "config", description = "Read or write application configuration.")
public class AppConfigCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Operation: get or set")
    private String operation;

    @CommandLine.Parameters(index = "1", description = "Configuration key")
    private String key;

    @CommandLine.Parameters(index = "2", arity = "0..1", description = "Value (for set)")
    private String value;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            if ("get".equals(operation)) {
                String result = client.getString("/api/config/" + key);
                System.out.println(key + " = " + result);
            } else if ("set".equals(operation)) {
                if (value == null) {
                    System.err.println("Error: value is required for set operation");
                    return 1;
                }
                client.put("/api/config/" + key, Map.of("value", value), Void.class);
                System.out.println("Set " + key + " = " + value);
            } else {
                System.err.println("Unknown operation: " + operation + ". Use 'get' or 'set'.");
                return 1;
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Config operation failed: " + e.getMessage());
            return 1;
        }
    }
}
