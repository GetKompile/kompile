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

package ai.kompile.cli.agent;

import ai.kompile.cli.common.http.KompileHttpClient;
import picocli.CommandLine;

import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "channel", description = "Manage KClaw channels.")
public class AgentChannelCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Operation: list, add, remove, status")
    private String operation;

    @CommandLine.Option(names = {"--name"}, description = "Channel name")
    private String channelName;

    @CommandLine.Option(names = {"--type"}, description = "Channel type (for add)")
    private String channelType;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            switch (operation) {
                case "list":
                    System.out.println(client.getString("/api/channels"));
                    break;
                case "add":
                    if (channelName == null || channelType == null) {
                        System.err.println("--name and --type required for add");
                        return 1;
                    }
                    System.out.println(client.postString("/api/channels", Map.of("name", channelName, "type", channelType)));
                    break;
                case "remove":
                    if (channelName == null) { System.err.println("--name required for remove"); return 1; }
                    System.out.println(client.delete("/api/channels/" + channelName));
                    break;
                case "status":
                    if (channelName == null) { System.err.println("--name required for status"); return 1; }
                    System.out.println(client.getString("/api/channels/" + channelName));
                    break;
                default:
                    System.err.println("Unknown operation: " + operation);
                    return 1;
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Channel operation failed: " + e.getMessage());
            return 1;
        }
    }
}
