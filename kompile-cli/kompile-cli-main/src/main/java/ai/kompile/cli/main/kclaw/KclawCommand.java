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
package ai.kompile.cli.main.kclaw;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code kompile kclaw} — run kclaw agent tasks (run an agent, do work, save the output) against a
 * running kompile app's {@code /api/kclaw/tasks} API.
 *
 * <pre>
 *   kompile kclaw run "summarize the repo" --engine kompile-cli --wait
 *   kompile kclaw run "draft release notes" --agent jarvis --channel slack --target C123
 *   kompile kclaw list
 *   kompile kclaw get &lt;id&gt;
 *   kompile kclaw output &lt;id&gt;
 * </pre>
 */
@Command(
        name = "kclaw",
        description = "Run kclaw agent tasks (run agents, do work, save output) against a running kompile app.",
        mixinStandardHelpOptions = true,
        subcommands = {
                KclawCommand.RunCmd.class,
                KclawCommand.ListCmd.class,
                KclawCommand.GetCmd.class,
                KclawCommand.OutputCmd.class
        })
public class KclawCommand implements Callable<Integer> {

    static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ── shared helpers ──────────────────────────────────────────────────────

    abstract static class Base implements Callable<Integer> {
        @Option(names = "--url", description = "Base URL of the kompile app (default: http://localhost:8080)")
        String url;

        @Option(names = {"-p", "--port"}, description = "App port on localhost (default: 8080)")
        Integer port;

        String tasksUrl() {
            String base = (url != null && !url.isBlank())
                    ? url.replaceAll("/+$", "")
                    : "http://localhost:" + (port != null ? port : 8080);
            return base + "/api/kclaw/tasks";
        }
    }

    static HttpResponse<String> send(HttpRequest req) throws Exception {
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    static int fail(String msg) {
        System.err.println(msg);
        return 1;
    }

    static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ── subcommands ─────────────────────────────────────────────────────────

    @Command(name = "run", description = "Submit a task (run an agent to do work).", mixinStandardHelpOptions = true)
    static class RunCmd extends Base {
        @Parameters(arity = "1..*", paramLabel = "TASK", description = "The work for the agent to do.")
        List<String> taskParts;

        @Option(names = "--engine", defaultValue = "react", description = "react | kompile-cli (default: react)")
        String engine;

        @Option(names = "--agent", description = "ReAct agent id (default: the server's default agent)")
        String agent;

        @Option(names = "--model", description = "Model override (kompile-cli engine)")
        String model;

        @Option(names = "--channel", description = "Deliver the result to this channel (e.g. discord, slack)")
        String channel;

        @Option(names = "--target", description = "Channel target id (required with --channel)")
        String target;

        @Option(names = "--wait", defaultValue = "false", description = "Wait for completion and print the output")
        boolean wait;

        @Override
        public Integer call() throws Exception {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("engine", engine);
            body.put("task", String.join(" ", taskParts));
            if (agent != null) body.put("agentId", agent);
            if (model != null) body.put("model", model);
            if (channel != null) body.put("channel", channel);
            if (target != null) body.put("channelTarget", target);

            HttpRequest req = HttpRequest.newBuilder(URI.create(tasksUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() >= 300) {
                return fail("Submit failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode t = MAPPER.readTree(resp.body());
            String id = t.path("id").asText();
            System.out.println("task " + id + "  (" + t.path("status").asText() + ", engine="
                    + t.path("engine").asText() + ")");
            if (!wait) {
                return 0;
            }
            for (int i = 0; i < 1800; i++) {
                Thread.sleep(1000);
                HttpResponse<String> g = send(HttpRequest.newBuilder(
                        URI.create(tasksUrl() + "/" + id)).GET().build());
                if (g.statusCode() >= 300) continue;
                JsonNode jt = MAPPER.readTree(g.body());
                switch (jt.path("status").asText()) {
                    case "SUCCEEDED" -> {
                        System.out.println();
                        System.out.println(jt.path("output").asText(""));
                        return 0;
                    }
                    case "FAILED" -> {
                        return fail("task failed: " + jt.path("error").asText(""));
                    }
                    default -> { /* PENDING/RUNNING — keep polling */ }
                }
            }
            return fail("timed out waiting for task " + id);
        }
    }

    @Command(name = "list", description = "List tasks (newest first).", mixinStandardHelpOptions = true)
    static class ListCmd extends Base {
        @Override
        public Integer call() throws Exception {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(tasksUrl())).GET().build());
            if (resp.statusCode() >= 300) {
                return fail("List failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode arr = MAPPER.readTree(resp.body());
            if (!arr.isArray() || arr.isEmpty()) {
                System.out.println("(no tasks)");
                return 0;
            }
            for (JsonNode t : arr) {
                System.out.printf("%-36s  %-9s  %-11s  %s%n",
                        t.path("id").asText(), t.path("status").asText(),
                        t.path("engine").asText(), truncate(t.path("task").asText(""), 50));
            }
            return 0;
        }
    }

    @Command(name = "get", description = "Show a task record (status, output, file path).", mixinStandardHelpOptions = true)
    static class GetCmd extends Base {
        @Parameters(index = "0", paramLabel = "ID")
        String id;

        @Override
        public Integer call() throws Exception {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(
                    URI.create(tasksUrl() + "/" + id)).GET().build());
            if (resp.statusCode() == 404) return fail("task not found: " + id);
            if (resp.statusCode() >= 300) return fail("Get failed (" + resp.statusCode() + "): " + resp.body());
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(MAPPER.readTree(resp.body())));
            return 0;
        }
    }

    @Command(name = "output", description = "Print a task's saved output text.", mixinStandardHelpOptions = true)
    static class OutputCmd extends Base {
        @Parameters(index = "0", paramLabel = "ID")
        String id;

        @Override
        public Integer call() throws Exception {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(
                    URI.create(tasksUrl() + "/" + id + "/output")).GET().build());
            if (resp.statusCode() == 404) return fail("task not found: " + id);
            if (resp.statusCode() >= 300) return fail("Output failed (" + resp.statusCode() + "): " + resp.body());
            System.out.println(resp.body());
            return 0;
        }
    }
}
