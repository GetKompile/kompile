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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.manage.ServiceManager;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command that checks and drives the setup of a running kompile-app instance.
 * <p>
 * Subcommands:
 * <ul>
 *   <li>{@code kompile setup status} — show current setup status</li>
 *   <li>{@code kompile setup staging <url>} — configure a model staging endpoint</li>
 *   <li>{@code kompile setup reload} — force-reload models</li>
 *   <li>{@code kompile setup watch} — poll until all steps complete</li>
 *   <li>{@code kompile setup run} — full interactive setup: configure staging, wait for model, report readiness</li>
 * </ul>
 */
@CommandLine.Command(
        name = "setup",
        description = "Check readiness and configure a running kompile-app instance.%n%n" +
                "Guides you through staging server setup, model loading, and index readiness.%n%n" +
                "Examples:%n" +
                "  kompile app setup status%n" +
                "  kompile app setup run --start-staging --stage-model=bge-base-en-v1.5%n" +
                "  kompile app setup staging http://localhost:8090%n" +
                "  kompile app setup watch --timeout=120%n",
        subcommands = {
                SetupCommand.StatusCmd.class,
                SetupCommand.StagingServerCmd.class,
                SetupCommand.StagingCmd.class,
                SetupCommand.ReloadCmd.class,
                SetupCommand.WatchCmd.class,
                SetupCommand.RunCmd.class
        },
        mixinStandardHelpOptions = true
)
public class SetupCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String CHECK  = "\u2714"; // ✔
    private static final String CROSS  = "\u2718"; // ✘
    private static final String ARROW  = "\u279c"; // ➜
    private static final String SPIN   = "\u25cb"; // ○
    private static final String WARN   = "\u26a0"; // ⚠

    static void printStepStatus(JsonNode step) {
        String status = step.path("status").asText("NOT_STARTED");
        boolean complete = step.path("complete").asBoolean(false);
        String name = step.path("name").asText("?");
        String message = step.path("message").asText("");
        String detail = step.path("detail").asText(null);
        String action = step.path("action").asText(null);

        String icon;
        switch (status) {
            case "COMPLETE":    icon = CHECK; break;
            case "IN_PROGRESS": icon = SPIN;  break;
            case "WARNING":     icon = WARN;  break;
            default:            icon = CROSS; break;
        }

        System.out.printf("  %s  %-18s %s%n", icon, name, message);
        if (detail != null && !detail.isBlank()) {
            System.out.printf("     %-18s %s%n", "", detail);
        }
        if (!complete && action != null && !action.isBlank()) {
            System.out.printf("     %-18s %s %s%n", "", ARROW, action);
        }
    }

    static final String[] ALL_STEP_KEYS = {"stagingServer", "modelSource", "embeddingModel", "indexing", "searchReady"};

    static void printFullStatus(JsonNode root) {
        int currentStep = root.path("currentStep").asInt(1);
        int totalSteps = root.path("totalSteps").asInt(5);
        boolean setupComplete = root.path("setupComplete").asBoolean(false);

        // Count completed
        int completed = 0;
        for (String key : ALL_STEP_KEYS) {
            if (root.path(key).path("complete").asBoolean(false)) completed++;
        }

        System.out.println();
        if (setupComplete) {
            System.out.println("  " + CHECK + "  Setup complete — all " + totalSteps + " steps done");
        } else {
            System.out.println("  Setup progress: " + completed + "/" + totalSteps + " steps complete  (currently on step " + currentStep + ")");
        }
        System.out.println();

        for (String key : ALL_STEP_KEYS) {
            printStepStatus(root.path(key));
        }
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: status
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "status", description = "Show the current setup status of kompile-app", mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/setup/status");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode root = client.getObjectMapper().readTree(response);
                    System.out.println("Kompile App Setup Status");
                    System.out.println("========================");
                    printFullStatus(root);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: staging-server  (start/stop/status the staging service)
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "staging-server",
            description = "Manage the kompile-model-staging server (start, stop, status)",
            subcommands = {
                    StagingServerCmd.StartCmd.class,
                    StagingServerCmd.StopCmd.class,
                    StagingServerCmd.StagingServerStatusCmd.class
            },
            mixinStandardHelpOptions = true)
    static class StagingServerCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @CommandLine.Command(name = "start", description = "Start the kompile-model-staging server", mixinStandardHelpOptions = true)
        static class StartCmd implements Callable<Integer> {
            @CommandLine.Option(names = {"--port"}, description = "Port for the staging server (default: 8090)", defaultValue = "8090")
            private int port;

            @CommandLine.Option(names = {"--stage-model"}, description = "Model ID to stage from catalog after startup (e.g. bge-base-en-v1.5)")
            private String stageModel;

            @Override
            public Integer call() {
                ServiceManager sm = new ServiceManager();

                // Check if already running
                if (sm.checkHealth(port)) {
                    System.out.println(CHECK + "  Staging server already running on port " + port);
                    if (stageModel != null) {
                        return stageModelFromCatalog(port, stageModel);
                    }
                    return 0;
                }

                System.out.println("Starting kompile-model-staging on port " + port + "...");
                try {
                    List<String> appArgs = new ArrayList<>();
                    appArgs.add("--server");
                    ServiceManager.ProcessResult result = sm.startComponent("kompile-model-staging", port, null, appArgs);
                    if (result.hasError()) {
                        System.err.println(CROSS + "  " + result.getMessage());
                        return 1;
                    }
                    System.out.println(CHECK + "  Staging server started on port " + port
                            + result.getPid().map(p -> " (PID " + p + ")").orElse(""));

                    if (stageModel != null) {
                        return stageModelFromCatalog(port, stageModel);
                    }
                    return 0;
                } catch (Exception e) {
                    System.err.println(CROSS + "  Failed to start staging server: " + e.getMessage());
                    return 1;
                }
            }

            private int stageModelFromCatalog(int port, String modelId) {
                System.out.println("Staging model '" + modelId + "' from catalog...");
                try {
                    java.net.URL url = new java.net.URL("http://localhost:" + port + "/api/staging/stage/catalog/" + modelId);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(60_000);
                    int code = conn.getResponseCode();
                    String body = new String(
                            code < 400 ? conn.getInputStream().readAllBytes() : conn.getErrorStream().readAllBytes()
                    );
                    conn.disconnect();
                    if (code < 400) {
                        System.out.println(CHECK + "  Model staging initiated: " + modelId);
                        System.out.println("     " + ARROW + " The model will download and convert in the background.");
                        return 0;
                    } else {
                        System.err.println(WARN + "  Stage model failed (HTTP " + code + "): " + body);
                        return 1;
                    }
                } catch (Exception e) {
                    System.err.println(WARN + "  Failed to stage model: " + e.getMessage());
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "stop", description = "Stop the kompile-model-staging server", mixinStandardHelpOptions = true)
        static class StopCmd implements Callable<Integer> {
            @Override
            public Integer call() {
                try {
                    ServiceManager sm = new ServiceManager();
                    ServiceManager.ProcessResult result = sm.stopComponent("kompile-model-staging");
                    if (result.hasError()) {
                        System.err.println(CROSS + "  " + result.getMessage());
                        return 1;
                    }
                    System.out.println(CHECK + "  Staging server stopped.");
                    return 0;
                } catch (Exception e) {
                    System.err.println(CROSS + "  Failed to stop staging server: " + e.getMessage());
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "status", description = "Show staging server status", mixinStandardHelpOptions = true)
        static class StagingServerStatusCmd implements Callable<Integer> {
            @CommandLine.Option(names = {"--port"}, description = "Port to check (default: 8081)", defaultValue = "8081")
            private int port;

            @Override
            public Integer call() {
                ServiceManager sm = new ServiceManager();
                ServiceManager.ComponentStatus status = sm.getComponentStatus("kompile-model-staging");

                System.out.println("Staging Server Status");
                System.out.println("=====================");
                System.out.printf("  Component:  %s%n", status.getComponentId());
                System.out.printf("  Installed:  %s%n", status.isInstalled() ? CHECK : CROSS);
                System.out.printf("  Status:     %s %s%n", status.getStatusIcon(), status.getStatus());
                status.getPid().ifPresent(p -> System.out.printf("  PID:        %d%n", p));
                status.getPort().ifPresent(p -> System.out.printf("  Port:       %d%n", p));
                status.getUrl().ifPresent(u -> System.out.printf("  URL:        %s%n", u));
                System.out.println();

                // If running, try to get catalog info
                if ("running".equals(status.getStatus())) {
                    try {
                        int checkPort = status.getPort().orElse(port);
                        java.net.URL url = new java.net.URL("http://localhost:" + checkPort + "/api/staging/catalog");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        if (conn.getResponseCode() == 200) {
                            String body = new String(conn.getInputStream().readAllBytes());
                            ObjectMapper mapper = JsonUtils.standardMapper();
                            JsonNode catalog = mapper.readTree(body);
                            System.out.println("  Catalog: " + catalog.size() + " model(s) available");
                        }
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }

                return 0;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: staging
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "staging", description = "Configure a model staging service endpoint", mixinStandardHelpOptions = true)
    static class StagingCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Staging service endpoint URL (e.g. http://staging-host:8090)")
        private String stagingUrl;

        @CommandLine.Option(names = {"--name"}, description = "Display name for this staging config", defaultValue = "CLI-configured")
        private String name;

        @CommandLine.Option(names = {"--api-key"}, description = "API key for the staging service")
        private String apiKey;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                // Check if there's already an active config
                String existing;
                try {
                    existing = client.getString("/api/staging-config/configs/active");
                } catch (IOException e) {
                    existing = null;
                }

                if (existing != null && !existing.isBlank() && !existing.equals("null")) {
                    JsonNode existingNode = client.getObjectMapper().readTree(existing);
                    String existingUrl = existingNode.path("endpointUrl").asText("");
                    if (existingUrl.equals(stagingUrl)) {
                        System.out.println("Staging service already configured at " + stagingUrl);
                        // Trigger a verify/reconnect
                        try {
                            client.postEmpty("/api/staging-config/configs/" + existingNode.path("id").asLong() + "/verify");
                            System.out.println(CHECK + "  Connection verified.");
                        } catch (IOException e) {
                            System.out.println(WARN + "  Could not verify connection: " + e.getMessage());
                        }
                        return 0;
                    }
                    // Update existing config to point to the new URL
                    System.out.println("Updating existing staging config to " + stagingUrl + " ...");
                    long id = existingNode.path("id").asLong();
                    Map<String, Object> updateDto = new LinkedHashMap<>();
                    updateDto.put("name", name);
                    updateDto.put("endpointUrl", stagingUrl);
                    updateDto.put("apiKey", apiKey);
                    updateDto.put("active", true);
                    client.put("/api/staging-config/configs/" + id, updateDto, String.class);
                    System.out.println(CHECK + "  Staging config updated.");
                } else {
                    // Create new config
                    System.out.println("Configuring staging service: " + stagingUrl + " ...");
                    Map<String, Object> createDto = new LinkedHashMap<>();
                    createDto.put("name", name);
                    createDto.put("endpointUrl", stagingUrl);
                    createDto.put("apiKey", apiKey);
                    createDto.put("active", true);
                    client.postString("/api/staging-config/configs", createDto);
                    System.out.println(CHECK + "  Staging config created and activated.");
                }

                // Test the connection
                System.out.println("Testing connection...");
                try {
                    String testResult = client.postEmpty("/api/staging-config/test-connection");
                    JsonNode testNode = client.getObjectMapper().readTree(testResult);
                    if (testNode.path("success").asBoolean(false)) {
                        int modelCount = testNode.path("modelCount").asInt(0);
                        System.out.println(CHECK + "  Connected! " + modelCount + " model(s) available in staging registry.");
                    } else {
                        System.out.println(CROSS + "  Connection test failed: " + testNode.path("message").asText("unknown error"));
                        System.out.println("     " + ARROW + " Check that the staging service is running at " + stagingUrl);
                        return 1;
                    }
                } catch (IOException e) {
                    System.out.println(WARN + "  Connection test failed: " + e.getMessage());
                    System.out.println("     " + ARROW + " The config is saved. The app will retry automatically.");
                }

                // Trigger model reload
                System.out.println("Triggering model reload...");
                try {
                    client.postEmpty("/api/models/registry/refresh-and-reload");
                    System.out.println(CHECK + "  Model reload triggered. Use 'kompile setup watch' to monitor progress.");
                } catch (IOException e) {
                    System.out.println(WARN + "  Could not trigger reload: " + e.getMessage());
                }

                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: reload
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "reload", description = "Force-reload models from the configured staging service", mixinStandardHelpOptions = true)
    static class ReloadCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                System.out.println("Triggering model reload...");
                String response = client.postEmpty("/api/models/registry/refresh-and-reload");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.path("success").asBoolean(false)) {
                        System.out.println(CHECK + "  Model reload successful.");
                        String model = node.path("activeModel").asText(null);
                        if (model != null) {
                            System.out.println("     Active model: " + model);
                        }
                    } else {
                        String error = node.path("error").asText("reload did not succeed");
                        System.out.println(WARN + "  " + error);
                        System.out.println("     " + ARROW + " The model may still be loading. Use 'kompile setup watch' to monitor.");
                    }
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: watch
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "watch", description = "Poll setup status until all steps complete or timeout", mixinStandardHelpOptions = true)
    static class WatchCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = {"--timeout"}, description = "Timeout in seconds (default: 300)", defaultValue = "300")
        private int timeoutSeconds;

        @CommandLine.Option(names = {"--interval"}, description = "Poll interval in seconds (default: 5)", defaultValue = "5")
        private int intervalSeconds;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            ObjectMapper mapper = client.getObjectMapper();
            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            int lastCompleted = -1;

            System.out.println("Watching setup progress (timeout: " + timeoutSeconds + "s, poll every " + intervalSeconds + "s)");
            System.out.println("Press Ctrl+C to stop watching.\n");

            try {
                while (System.currentTimeMillis() < deadline) {
                    String response = client.getString("/api/setup/status");
                    JsonNode root = mapper.readTree(response);
                    boolean complete = root.path("setupComplete").asBoolean(false);

                    // Count completed steps
                    int completed = 0;
                    for (String key : ALL_STEP_KEYS) {
                        if (root.path(key).path("complete").asBoolean(false)) completed++;
                    }

                    // Only re-print when progress changes
                    if (completed != lastCompleted) {
                        lastCompleted = completed;
                        // Clear screen (ANSI escape) for clean display
                        System.out.print("\033[2J\033[H");
                        System.out.println("Kompile App Setup — Live");
                        System.out.println("========================");
                        printFullStatus(root);
                    }

                    if (complete) {
                        System.out.println(CHECK + "  All setup steps complete. kompile-app is ready!");
                        return 0;
                    }

                    // Print a dot to show we're still polling
                    System.out.print(".");
                    System.out.flush();

                    Thread.sleep(intervalSeconds * 1000L);
                }

                System.out.println("\n" + WARN + "  Timed out after " + timeoutSeconds + "s. Setup is not yet complete.");
                System.out.println("     " + ARROW + " Run 'kompile setup status' to check current state.");
                return 1;

            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("\nInterrupted.");
                return 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBCOMMAND: run  (full interactive setup)
    // ═══════════════════════════════════════════════════════════════════════════

    @CommandLine.Command(name = "run", description = "Run full setup: start staging server, configure it, wait for model, report readiness", mixinStandardHelpOptions = true)
    static class RunCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = {"--staging-url"}, description = "Staging service URL to configure (e.g. http://staging-host:8090)")
        private String stagingUrl;

        @CommandLine.Option(names = {"--staging-name"}, description = "Display name for the staging config", defaultValue = "CLI-configured")
        private String stagingName;

        @CommandLine.Option(names = {"--staging-api-key"}, description = "API key for the staging service")
        private String stagingApiKey;

        @CommandLine.Option(names = {"--staging-port"}, description = "Port for the staging server (default: 8090)", defaultValue = "8090")
        private int stagingPort;

        @CommandLine.Option(names = {"--start-staging"}, description = "Automatically start the staging server if not running", defaultValue = "true", negatable = true)
        private boolean startStaging;

        @CommandLine.Option(names = {"--stage-model"}, description = "Model to stage from catalog after starting staging server (e.g. bge-base-en-v1.5)")
        private String stageModel;

        @CommandLine.Option(names = {"--timeout"}, description = "Max seconds to wait for model loading (default: 300)", defaultValue = "300")
        private int timeoutSeconds;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            ObjectMapper mapper = client.getObjectMapper();

            System.out.println();
            System.out.println("Kompile App Setup");
            System.out.println("=================");
            System.out.println();

            try {
                // ── Step 0: Check current status ──
                System.out.println("Checking current status...");
                String statusJson = client.getString("/api/setup/status");
                JsonNode status = mapper.readTree(statusJson);

                if (status.path("setupComplete").asBoolean(false)) {
                    System.out.println(CHECK + "  Already fully set up!");
                    printFullStatus(status);
                    return 0;
                }

                printFullStatus(status);

                // ── Step 1: Start staging server if needed ──
                boolean stagingServerComplete = status.path("stagingServer").path("complete").asBoolean(false);

                if (!stagingServerComplete && startStaging) {
                    System.out.println("Step 1: Starting staging server...");
                    ServiceManager sm = new ServiceManager();

                    if (sm.checkHealth(stagingPort)) {
                        System.out.println("  " + CHECK + "  Staging server already running on port " + stagingPort);
                    } else {
                        System.out.println("  Starting kompile-model-staging on port " + stagingPort + "...");
                        try {
                            List<String> appArgs = new ArrayList<>();
                            appArgs.add("--server");
                            ServiceManager.ProcessResult result = sm.startComponent("kompile-model-staging", stagingPort, null, appArgs);
                            if (result.hasError()) {
                                System.out.println("  " + WARN + "  " + result.getMessage());
                                System.out.println("  " + ARROW + " Continuing without staging server. You can start it manually.");
                            } else {
                                System.out.println("  " + CHECK + "  Staging server started on port " + stagingPort
                                        + result.getPid().map(p -> " (PID " + p + ")").orElse(""));
                            }
                        } catch (Exception e) {
                            System.out.println("  " + WARN + "  Failed to start staging server: " + e.getMessage());
                            System.out.println("  " + ARROW + " Continuing. Install with: kompile install kompile-model-staging");
                        }
                    }

                    // Stage a model from catalog if requested
                    if (stageModel != null && sm.checkHealth(stagingPort)) {
                        System.out.println("  Staging model '" + stageModel + "' from catalog...");
                        try {
                            java.net.URL url = new java.net.URL("http://localhost:" + stagingPort + "/api/staging/stage/catalog/" + stageModel);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(60_000);
                            int code = conn.getResponseCode();
                            conn.disconnect();
                            if (code < 400) {
                                System.out.println("  " + CHECK + "  Model staging initiated for " + stageModel);
                            } else {
                                System.out.println("  " + WARN + "  Stage request returned HTTP " + code);
                            }
                        } catch (Exception e) {
                            System.out.println("  " + WARN + "  " + e.getMessage());
                        }
                    }

                    // Auto-derive staging URL if not explicitly provided
                    if (stagingUrl == null && sm.checkHealth(stagingPort)) {
                        stagingUrl = "http://localhost:" + stagingPort;
                        System.out.println("  " + ARROW + " Using staging URL: " + stagingUrl);
                    }
                } else if (stagingServerComplete) {
                    System.out.println("Step 1: Staging server already running. " + CHECK);
                    // Derive staging URL from status if not set
                    if (stagingUrl == null) {
                        String detail = status.path("stagingServer").path("detail").asText("");
                        if (detail.contains("localhost")) {
                            // Extract port from detail like "Port 8081 — http://localhost:8081"
                            int portIdx = detail.indexOf("http://");
                            if (portIdx >= 0) {
                                stagingUrl = detail.substring(portIdx).trim();
                            }
                        }
                    }
                }
                System.out.println();

                // ── Step 2: Configure staging if needed and URL provided ──
                boolean modelSourceComplete = status.path("modelSource").path("complete").asBoolean(false);

                if (!modelSourceComplete && stagingUrl != null && !stagingUrl.isBlank()) {
                    System.out.println("Step 2: Configuring staging service at " + stagingUrl + " ...");

                    // Check existing
                    JsonNode existingConfig = null;
                    try {
                        String existingJson = client.getString("/api/staging-config/configs/active");
                        if (existingJson != null && !existingJson.isBlank() && !existingJson.equals("null")) {
                            existingConfig = mapper.readTree(existingJson);
                        }
                    } catch (IOException ignored) {}

                    if (existingConfig != null) {
                        long id = existingConfig.path("id").asLong();
                        Map<String, Object> dto = new LinkedHashMap<>();
                        dto.put("name", stagingName);
                        dto.put("endpointUrl", stagingUrl);
                        dto.put("apiKey", stagingApiKey);
                        dto.put("active", true);
                        client.put("/api/staging-config/configs/" + id, dto, String.class);
                        System.out.println("  " + CHECK + "  Updated existing staging config.");
                    } else {
                        Map<String, Object> dto = new LinkedHashMap<>();
                        dto.put("name", stagingName);
                        dto.put("endpointUrl", stagingUrl);
                        dto.put("apiKey", stagingApiKey);
                        dto.put("active", true);
                        client.postString("/api/staging-config/configs", dto);
                        System.out.println("  " + CHECK + "  Created staging config.");
                    }

                    // Test connection
                    try {
                        String testJson = client.postEmpty("/api/staging-config/test-connection");
                        JsonNode testNode = mapper.readTree(testJson);
                        if (testNode.path("success").asBoolean(false)) {
                            int modelCount = testNode.path("modelCount").asInt(0);
                            System.out.println("  " + CHECK + "  Connected. " + modelCount + " model(s) available.");
                        } else {
                            System.out.println("  " + WARN + "  Connection test failed: " + testNode.path("message").asText("unknown"));
                            System.out.println("       The config is saved — the app will retry automatically.");
                        }
                    } catch (IOException e) {
                        System.out.println("  " + WARN + "  Connection test error: " + e.getMessage());
                    }

                    // Trigger model reload
                    System.out.println("  Triggering model reload...");
                    try {
                        client.postEmpty("/api/models/registry/refresh-and-reload");
                        System.out.println("  " + CHECK + "  Reload triggered.");
                    } catch (IOException e) {
                        System.out.println("  " + WARN + "  " + e.getMessage());
                    }
                } else if (!modelSourceComplete) {
                    System.out.println("Step 2: No model source configured.");
                    System.out.println("  " + ARROW + " Re-run with --staging-url=<url> to configure a staging service.");
                    System.out.println("  " + ARROW + " Or configure one via the web UI at Developer > Model Staging.");
                } else {
                    System.out.println("Step 2: Model source already configured. " + CHECK);
                }
                System.out.println();

                // ── Step 3: Wait for embedding model ──
                boolean embeddingComplete = status.path("embeddingModel").path("complete").asBoolean(false);

                if (!embeddingComplete) {
                    System.out.println("Step 3: Waiting for embedding model to load (timeout: " + timeoutSeconds + "s)...");
                    long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
                    int dots = 0;

                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(3000);
                        statusJson = client.getString("/api/setup/status");
                        status = mapper.readTree(statusJson);

                        embeddingComplete = status.path("embeddingModel").path("complete").asBoolean(false);
                        if (embeddingComplete) {
                            // Clear the dots line
                            if (dots > 0) System.out.println();
                            String modelName = status.path("embeddingModel").path("message").asText("loaded");
                            System.out.println("  " + CHECK + "  Embedding model ready: " + modelName);
                            break;
                        }

                        String phase = status.path("embeddingModel").path("status").asText("NOT_STARTED");
                        if ("IN_PROGRESS".equals(phase)) {
                            if (dots == 0) {
                                System.out.print("  Loading");
                            }
                            System.out.print(".");
                            System.out.flush();
                            dots++;
                        }
                    }

                    if (!embeddingComplete) {
                        if (dots > 0) System.out.println();
                        System.out.println("  " + WARN + "  Embedding model did not load within " + timeoutSeconds + "s.");
                        System.out.println("  " + ARROW + " Check staging service availability and try 'kompile setup reload'.");
                    }
                } else {
                    System.out.println("Step 3: Embedding model already loaded. " + CHECK);
                }
                System.out.println();

                // ── Step 4: Report final status ──
                statusJson = client.getString("/api/setup/status");
                status = mapper.readTree(statusJson);

                System.out.println("Final Status");
                System.out.println("────────────");
                printFullStatus(status);

                boolean indexComplete = status.path("indexing").path("complete").asBoolean(false);
                if (!indexComplete && embeddingComplete) {
                    System.out.println("Next step: Index some documents to enable search.");
                    System.out.println("  " + ARROW + " kompile ingest file <document.pdf>");
                    System.out.println("  " + ARROW + " Or upload documents via the web UI Fact Sheets tab.");
                    System.out.println();
                }

                return status.path("setupComplete").asBoolean(false) ? 0 : 1;

            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("\nInterrupted.");
                return 1;
            }
        }
    }
}
