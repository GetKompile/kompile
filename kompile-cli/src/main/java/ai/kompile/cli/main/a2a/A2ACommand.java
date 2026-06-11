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

package ai.kompile.cli.main.a2a;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command group for A2A (Agent-to-Agent) protocol management.
 * <p>
 * Talks to a running kompile-app instance via the /api/a2a REST endpoints
 * to discover, manage, and communicate with remote A2A agents.
 */
@CommandLine.Command(
        name = "a2a",
        description = "Agent-to-Agent (A2A) protocol — discover and manage remote agents",
        subcommands = {
                A2ACommand.StatusCmd.class,
                A2ACommand.ConfigCmd.class,
                A2ACommand.EnableCmd.class,
                A2ACommand.DisableCmd.class,
                A2ACommand.ListCmd.class,
                A2ACommand.DiscoverCmd.class,
                A2ACommand.PingCmd.class,
                A2ACommand.SendCmd.class,
                A2ACommand.RemoveCmd.class
        },
        mixinStandardHelpOptions = true
)
public class A2ACommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ========================================================================
    // status — show A2A module status
    // ========================================================================

    @CommandLine.Command(name = "status", description = "Show A2A module status and remote agent summary",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/a2a/status");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("A2A Protocol Status:");
                    OutputFormatter.printKv("Enabled", node.path("enabled"));
                    OutputFormatter.printKv("Server Enabled", node.path("serverEnabled"));
                    OutputFormatter.printKv("Protocol Version", node.path("protocolVersion"));
                    OutputFormatter.printKv("Remote Agents", node.path("totalRemoteAgents"));
                    OutputFormatter.printKv("Enabled Agents", node.path("enabledAgents"));
                    OutputFormatter.printKv("Reachable Agents", node.path("reachableAgents"));
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // config — show/update A2A configuration
    // ========================================================================

    @CommandLine.Command(name = "config", description = "Show or update A2A configuration",
            mixinStandardHelpOptions = true)
    static class ConfigCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = "--set", description = "Set a config value (key=value)", arity = "1..*")
        private String[] sets;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                if (sets != null && sets.length > 0) {
                    // Update configuration
                    Map<String, Object> updates = new java.util.LinkedHashMap<>();
                    for (String s : sets) {
                        String[] kv = s.split("=", 2);
                        if (kv.length == 2) {
                            updates.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                    String response = client.put("/api/a2a/config", updates, String.class);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("A2A configuration updated.");
                        JsonNode node = client.getObjectMapper().readTree(response);
                        printConfig(node.path("config"));
                    }
                } else {
                    // Show current configuration
                    String response = client.getString("/api/a2a/config");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode node = client.getObjectMapper().readTree(response);
                        System.out.println("A2A Configuration:");
                        printConfig(node);
                    }
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }

        private void printConfig(JsonNode node) {
            OutputFormatter.printKv("Enabled", node.path("enabled"));
            OutputFormatter.printKv("Server Enabled", node.path("serverEnabled"));
            OutputFormatter.printKv("Max Concurrent Tasks", node.path("maxConcurrentTasks"));
            OutputFormatter.printKv("Default Timeout (s)", node.path("defaultTimeoutSeconds"));
            OutputFormatter.printKv("Auto-discover on Start", node.path("autoDiscoverOnStartup"));
        }
    }

    // ========================================================================
    // enable / disable
    // ========================================================================

    @CommandLine.Command(name = "enable", description = "Enable the A2A module", mixinStandardHelpOptions = true)
    static class EnableCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            return setEnabled(app, true);
        }
    }

    @CommandLine.Command(name = "disable", description = "Disable the A2A module", mixinStandardHelpOptions = true)
    static class DisableCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            return setEnabled(app, false);
        }
    }

    private static int setEnabled(AppClientMixin app, boolean enabled) {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            String response = client.put("/api/a2a/config/enabled", Map.of("enabled", enabled), String.class);
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
            } else {
                System.out.println("A2A module " + (enabled ? "enabled" : "disabled") + ".");
            }
            return 0;
        } catch (IOException | InterruptedException e) {
            handleError(e);
            return 1;
        }
    }

    // ========================================================================
    // list — list registered remote agents
    // ========================================================================

    @CommandLine.Command(name = "list", description = "List registered remote A2A agents",
            mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/a2a/agents");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    JsonNode agents = node.path("agents");
                    System.out.println("Remote A2A Agents (" + node.path("count").asInt(0) + "):");
                    if (agents.isArray() && agents.size() > 0) {
                        OutputFormatter.printTable(agents, "id", "name", "baseUrl", "enabled", "reachable");
                    } else {
                        System.out.println("  (none registered — use 'kompile a2a discover <url>' to add)");
                    }
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // discover — discover a remote agent by URL
    // ========================================================================

    @CommandLine.Command(name = "discover", description = "Discover and register a remote A2A agent",
            mixinStandardHelpOptions = true)
    static class DiscoverCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Base URL of the remote agent (e.g. http://host:9090)")
        private String baseUrl;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString("/api/a2a/agents/discover", Map.of("baseUrl", baseUrl));
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if ("success".equals(node.path("status").asText())) {
                        JsonNode agent = node.path("agent");
                        System.out.println("Agent discovered and registered:");
                        OutputFormatter.printKv("ID", agent.path("id"));
                        OutputFormatter.printKv("Name", agent.path("name"));
                        OutputFormatter.printKv("URL", agent.path("baseUrl"));
                        OutputFormatter.printKv("Reachable", agent.path("reachable"));
                    } else {
                        System.err.println("Discovery failed: " + node.path("message").asText());
                        return 1;
                    }
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // ping — check connectivity to a remote agent
    // ========================================================================

    @CommandLine.Command(name = "ping", description = "Ping a remote A2A agent to check connectivity",
            mixinStandardHelpOptions = true)
    static class PingCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Agent ID")
        private String agentId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString("/api/a2a/agents/" + agentId + "/ping", Map.of());
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    boolean reachable = node.path("reachable").asBoolean();
                    System.out.println(agentId + ": " + (reachable ? "reachable" : "unreachable"));
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // send — send a task to a remote agent
    // ========================================================================

    @CommandLine.Command(name = "send", description = "Send a task to a remote A2A agent",
            mixinStandardHelpOptions = true)
    static class SendCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Agent ID")
        private String agentId;

        @CommandLine.Parameters(index = "1", description = "Task message/prompt")
        private String message;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString("/api/a2a/agents/" + agentId + "/send", Map.of("message", message));
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if ("success".equals(node.path("status").asText())) {
                        JsonNode task = node.path("task");
                        System.out.println("Task completed:");
                        OutputFormatter.printKv("Task ID", task.path("id"));
                        OutputFormatter.printKv("State", task.path("status").path("state"));
                        // Print response artifacts
                        JsonNode artifacts = task.path("artifacts");
                        if (artifacts.isArray()) {
                            for (JsonNode artifact : artifacts) {
                                JsonNode parts = artifact.path("parts");
                                if (parts.isArray()) {
                                    for (JsonNode part : parts) {
                                        if ("text".equals(part.path("type").asText())) {
                                            System.out.println("\nResponse:");
                                            System.out.println(part.path("text").asText());
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        System.err.println("Task failed: " + node.path("message").asText());
                        return 1;
                    }
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // remove — unregister a remote agent
    // ========================================================================

    @CommandLine.Command(name = "remove", description = "Unregister a remote A2A agent",
            mixinStandardHelpOptions = true)
    static class RemoveCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Agent ID to remove")
        private String agentId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.delete("/api/a2a/agents/" + agentId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Agent removed: " + agentId);
                }
                return 0;
            } catch (IOException | InterruptedException e) {
                handleError(e);
                return 1;
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void handleError(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        System.err.println("Error: " + e.getMessage());
    }
}
