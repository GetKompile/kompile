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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Manage chat monitors — wake a chat session when a background task finishes,
 * or on a one-shot / cron schedule.
 *
 * <pre>
 * kompile-agent monitor watch    --session s1 --task t1 --description "ingest"
 * kompile-agent monitor once     --session s1 --delay 300 --description "reminder"
 * kompile-agent monitor cron     --session s1 --cron "0 * * * * ?" --description "hourly check"
 * kompile-agent monitor list
 * kompile-agent monitor list     --session s1
 * kompile-agent monitor cancel   --id mon-abcdef123456
 * </pre>
 */
@CommandLine.Command(name = "monitor", description = "Manage chat monitors and scheduled wake-ups.")
public class AgentMonitorCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Operation: watch, once, cron, list, cancel, get")
    private String operation;

    @CommandLine.Option(names = {"--session"}, description = "Chat session ID to wake up")
    private String sessionId;

    @CommandLine.Option(names = {"--task"}, description = "Background task ID to watch (for 'watch')")
    private String taskId;

    @CommandLine.Option(names = {"--cron"}, description = "Cron expression (for 'cron')")
    private String cron;

    @CommandLine.Option(names = {"--delay"},
            description = "One-shot delay in seconds from now (for 'once'). Mutually exclusive with --at-epoch-ms.")
    private Long delaySeconds;

    @CommandLine.Option(names = {"--at-epoch-ms"},
            description = "Absolute fire time as epoch millis (for 'once').")
    private Long atEpochMs;

    @CommandLine.Option(names = {"--description"}, description = "Human-readable description")
    private String description;

    @CommandLine.Option(names = {"--payload"}, description = "Optional payload included in the wake-up message")
    private String payload;

    @CommandLine.Option(names = {"--id"}, description = "Monitor ID (for cancel / get)")
    private String monitorId;

    @CommandLine.Option(names = {"--all"}, description = "Include non-active monitors in list")
    private boolean all;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            switch (operation) {
                case "watch":
                    return watch(client);
                case "once":
                    return scheduleOnce(client);
                case "cron":
                    return scheduleCron(client);
                case "list":
                    return list(client);
                case "cancel":
                    return cancel(client);
                case "get":
                    return get(client);
                default:
                    System.err.println("Unknown operation: " + operation);
                    System.err.println("Use one of: watch, once, cron, list, cancel, get");
                    return 1;
            }
        } catch (Exception e) {
            System.err.println("Monitor operation failed: " + e.getMessage());
            return 1;
        }
    }

    private int watch(KompileHttpClient client) throws Exception {
        if (sessionId == null || taskId == null) {
            System.err.println("--session and --task are required for 'watch'");
            return 1;
        }
        Map<String, Object> body = buildBase();
        body.put("taskId", taskId);
        System.out.println(client.postString("/api/monitor/watch-task", body));
        return 0;
    }

    private int scheduleOnce(KompileHttpClient client) throws Exception {
        if (sessionId == null) {
            System.err.println("--session is required for 'once'");
            return 1;
        }
        long fireAt;
        if (atEpochMs != null) {
            fireAt = atEpochMs;
        } else if (delaySeconds != null) {
            fireAt = System.currentTimeMillis() + (delaySeconds * 1000L);
        } else {
            System.err.println("Provide --delay <seconds> or --at-epoch-ms <ms> for 'once'");
            return 1;
        }
        Map<String, Object> body = buildBase();
        body.put("fireAtEpochMs", fireAt);
        System.out.println(client.postString("/api/monitor/schedule-once", body));
        return 0;
    }

    private int scheduleCron(KompileHttpClient client) throws Exception {
        if (sessionId == null || cron == null) {
            System.err.println("--session and --cron are required for 'cron'");
            return 1;
        }
        Map<String, Object> body = buildBase();
        body.put("cronExpression", cron);
        System.out.println(client.postString("/api/monitor/schedule-cron", body));
        return 0;
    }

    private int list(KompileHttpClient client) throws Exception {
        StringBuilder path = new StringBuilder("/api/monitor");
        boolean first = true;
        if (sessionId != null) {
            path.append("?sessionId=").append(java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8));
            first = false;
        }
        if (all) {
            path.append(first ? "?" : "&").append("all=true");
        }
        System.out.println(client.getString(path.toString()));
        return 0;
    }

    private int cancel(KompileHttpClient client) throws Exception {
        if (monitorId == null) {
            System.err.println("--id is required for 'cancel'");
            return 1;
        }
        client.delete("/api/monitor/" + monitorId);
        System.out.println("Cancelled monitor: " + monitorId);
        return 0;
    }

    private int get(KompileHttpClient client) throws Exception {
        if (monitorId == null) {
            System.err.println("--id is required for 'get'");
            return 1;
        }
        System.out.println(client.getString("/api/monitor/" + monitorId));
        return 0;
    }

    private Map<String, Object> buildBase() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        if (description != null) body.put("description", description);
        if (payload != null) body.put("payload", payload);
        return body;
    }
}
