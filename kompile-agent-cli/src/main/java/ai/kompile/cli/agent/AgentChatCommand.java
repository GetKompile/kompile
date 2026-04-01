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

@CommandLine.Command(name = "chat", description = "Send a message to an agent.")
public class AgentChatCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, required = true, description = "Agent name")
    private String agentName;

    @CommandLine.Option(names = {"--message", "-m"}, required = true, description = "Message to send")
    private String message;

    @CommandLine.Option(names = {"--session"}, description = "Session ID (creates new if not specified)")
    private String sessionId;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("agentName", agentName);
            body.put("message", message);
            if (sessionId != null) {
                body.put("sessionId", sessionId);
            }
            String result = client.postString("/api/chat/send", body);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Chat failed: " + e.getMessage());
            return 1;
        }
    }
}
