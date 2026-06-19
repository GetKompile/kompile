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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * CLI commands for monitoring and managing enforcer sessions on a running
 * kompile-app server. Connects to the {@code /api/enforcer} REST API.
 */
@CommandLine.Command(
        name = "monitor",
        description = "Monitor and manage enforcer sessions on a kompile-app server",
        mixinStandardHelpOptions = true,
        subcommands = {
                EnforcerMonitorCommand.ListCmd.class,
                EnforcerMonitorCommand.StatusCmd.class,
                EnforcerMonitorCommand.TailCmd.class,
                EnforcerMonitorCommand.EnableCmd.class,
                EnforcerMonitorCommand.DisableCmd.class,
                EnforcerMonitorCommand.RestartCmd.class,
                EnforcerMonitorCommand.DeleteCmd.class,
                EnforcerMonitorCommand.ViolationsCmd.class
        }
)
public class EnforcerMonitorCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url", "-u"}, description = "kompile-app base URL",
            defaultValue = "http://localhost:8080")
    static String baseUrl;

    private static final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @Override
    public Integer call() {
        // Default: list sessions
        return new ListCmd().call();
    }

    // ========================================================================
    // Subcommands
    // ========================================================================

    @CommandLine.Command(name = "list", description = "List active enforcer sessions")
    static class ListCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                String body = httpGet("/api/enforcer/sessions");
                JsonNode sessions = objectMapper.readTree(body);

                if (!sessions.isArray() || sessions.isEmpty()) {
                    System.out.println("No active enforcer sessions.");
                    return 0;
                }

                System.out.printf("%-28s %-10s %-7s %-10s %-6s %-8s%n",
                        "SESSION ID", "AGENT", "SCORE", "VIOLATIONS", "TURNS", "ENABLED");
                System.out.println("-".repeat(75));

                for (JsonNode s : sessions) {
                    double score = s.path("score").asDouble(1.0);
                    System.out.printf("%-28s %-10s %5.0f%%  %-10d %-6d %-8s%n",
                            s.path("sessionId").asText(""),
                            s.path("agentName").asText(""),
                            score * 100,
                            s.path("violations").asInt(0),
                            s.path("totalTurns").asInt(0),
                            s.path("enabled").asBoolean(true) ? "yes" : "no");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error listing sessions: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "status", description = "Show detail for a session")
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                String body = httpGet("/api/enforcer/sessions/" + sessionId);
                JsonNode s = objectMapper.readTree(body);

                System.out.println("Enforcer Session: " + s.path("sessionId").asText());
                System.out.println("  Agent:        " + s.path("agentName").asText());
                System.out.println("  Enabled:      " + s.path("enabled").asBoolean());
                System.out.println("  Active:       " + s.path("active").asBoolean());
                System.out.printf("  Score:        %.0f%%%n", s.path("score").asDouble(1.0) * 100);
                System.out.println("  Violations:   " + s.path("violations").asInt());
                System.out.println("  Corrections:  " + s.path("corrections").asInt()
                        + "/" + s.path("maxCorrections").asInt());
                System.out.println("  Turns:        " + s.path("totalTurns").asInt());
                System.out.println("  Judge:        " + s.path("judgeBackend").asText(""));
                System.out.println("  Started:      " + s.path("startedAt").asText(""));
                System.out.println("  Working Dir:  " + s.path("workingDirectory").asText(""));

                JsonNode events = s.path("events");
                if (events.isArray() && !events.isEmpty()) {
                    System.out.println();
                    System.out.println("  Recent Events (" + events.size() + "):");
                    int shown = 0;
                    for (JsonNode e : events) {
                        if (shown >= 10) break;
                        System.out.printf("    [%s] %s %s  score=%.0f%%  %s%n",
                                e.path("timestamp").asText("").substring(11, 19),
                                e.path("severity").asText(""),
                                e.path("type").asText(""),
                                e.path("score").asDouble(1.0) * 100,
                                StringUtils.truncate(e.path("reason").asText(""), 60));
                        shown++;
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Session not found or error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "tail", description = "Tail real-time events from a session via SSE")
    static class TailCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                String url = resolveUrl() + "/api/enforcer/sessions/" + sessionId + "/stream";
                System.out.println("Tailing events for " + sessionId + " (Ctrl+C to stop)...");
                System.out.println();

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();

                HttpResponse<java.io.InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    System.err.println("HTTP " + response.statusCode());
                    return 1;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String eventName = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                        } else if (line.isBlank() && data.length() > 0) {
                            renderSseEvent(eventName, data.toString());
                            eventName = "";
                            data.setLength(0);
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error tailing events: " + e.getMessage());
                return 1;
            }
        }

        private void renderSseEvent(String name, String dataStr) {
            if ("heartbeat".equals(name)) {
                return; // Skip heartbeats
            }
            try {
                JsonNode data = objectMapper.readTree(dataStr);
                String ts = data.has("timestamp")
                        ? data.path("timestamp").asText("").substring(11, 19) : "";

                switch (name) {
                    case "interrupt_event" -> {
                        String severity = data.path("severity").asText("");
                        String type = data.path("type").asText("");
                        double score = data.path("score").asDouble(1.0);
                        String reason = data.path("reason").asText("");
                        System.out.printf("\033[33m[%s] %s %s  score=%.0f%%  %s\033[0m%n",
                                ts, severity, type, score * 100, StringUtils.truncate(reason, 60));
                    }
                    case "chunk" -> {
                        String text = data.path("text").asText("");
                        if (!text.isBlank()) {
                            System.out.print(text);
                            System.out.flush();
                        }
                    }
                    case "turn_complete" -> {
                        double score = data.path("score").asDouble(1.0);
                        int turn = data.path("turn").asInt(0);
                        System.out.printf("%n\033[2m[turn %d complete, score=%.0f%%]\033[0m%n", turn, score * 100);
                    }
                    case "session_ended" ->
                            System.out.println("\n\033[31m[session ended]\033[0m");
                    case "enforcement_enabled" ->
                            System.out.println("\033[32m[enforcement enabled]\033[0m");
                    case "enforcement_disabled" ->
                            System.out.println("\033[33m[enforcement disabled]\033[0m");
                    default ->
                            System.out.printf("\033[2m[%s] %s\033[0m%n", ts, name);
                }
            } catch (Exception e) {
                System.out.println("[" + name + "] " + StringUtils.truncate(dataStr, 80));
            }
        }
    }

    @CommandLine.Command(name = "enable", description = "Enable enforcement on a session")
    static class EnableCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                httpPut("/api/enforcer/sessions/" + sessionId + "/enable");
                System.out.println("Enforcement enabled for " + sessionId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "disable", description = "Disable enforcement on a session")
    static class DisableCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                httpPut("/api/enforcer/sessions/" + sessionId + "/disable");
                System.out.println("Enforcement disabled for " + sessionId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "restart", description = "Restart an enforcer session")
    static class RestartCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                String body = httpPost("/api/enforcer/sessions/" + sessionId + "/restart", "{}");
                JsonNode result = objectMapper.readTree(body);
                System.out.println("Restarted. New session: " + result.path("sessionId").asText(""));
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "delete", description = "Delete an enforcer session")
    static class DeleteCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                httpDelete("/api/enforcer/sessions/" + sessionId);
                System.out.println("Deleted " + sessionId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "violations", description = "Show violations for a session")
    static class ViolationsCmd implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Session ID")
        String sessionId;

        @Override
        public Integer call() {
            try {
                String body = httpGet("/api/enforcer/sessions/" + sessionId + "/violations");
                JsonNode violations = objectMapper.readTree(body);

                if (!violations.isArray() || violations.isEmpty()) {
                    System.out.println("No violations recorded.");
                    return 0;
                }

                System.out.printf("%-10s %-18s %-8s %-7s %s%n",
                        "TIME", "TYPE", "SEVERITY", "SCORE", "REASON");
                System.out.println("-".repeat(70));

                for (JsonNode v : violations) {
                    String ts = v.path("timestamp").asText("");
                    if (ts.length() > 19) ts = ts.substring(11, 19);
                    System.out.printf("%-10s %-18s %-8s %5.0f%%  %s%n",
                            ts,
                            v.path("type").asText(""),
                            v.path("severity").asText(""),
                            v.path("score").asDouble(1.0) * 100,
                            StringUtils.truncate(v.path("reason").asText(""), 40));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ========================================================================
    // HTTP helpers
    // ========================================================================

    private static String resolveUrl() {
        return baseUrl != null && !baseUrl.isBlank() ? baseUrl : "http://localhost:8080";
    }

    private static String httpGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl() + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String httpPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static void httpPut(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static void httpDelete(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl() + path))
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

}
