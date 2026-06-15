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
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "subprocess",
        description = "Subprocess monitoring and configuration",
        subcommands = {
                SubprocessCommand.ListCmd.class,
                SubprocessCommand.StatusCmd.class,
                SubprocessCommand.ConfigCmd.class,
                SubprocessCommand.EventsCmd.class,
                SubprocessCommand.StatsCmd.class
        },
        mixinStandardHelpOptions = true
)
public class SubprocessCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "list", description = "List active subprocesses", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/subprocess-config/active-processes");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode array = client.getObjectMapper().readTree(response);
                    System.out.println("Active Subprocesses:");
                    OutputFormatter.printTable(array, "taskId", "type", "status", "pid", "startTime");
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

    @CommandLine.Command(name = "status", description = "Show subprocess details", mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Task ID")
        private String taskId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/subprocess-config/active-processes/" + taskId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Subprocess: " + taskId);
                    OutputFormatter.printKv("Type", node.path("type"));
                    OutputFormatter.printKv("Status", node.path("status"));
                    OutputFormatter.printKv("PID", node.path("pid"));
                    OutputFormatter.printKv("Start Time", node.path("startTime"));
                    OutputFormatter.printKv("Memory", node.path("memoryUsage"));
                    OutputFormatter.printKv("CPU", node.path("cpuUsage"));
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

    @CommandLine.Command(
            name = "config",
            description = "Subprocess configuration",
            subcommands = {
                    ConfigCmd.ShowCmd.class,
                    ConfigCmd.SetCmd.class
            },
            mixinStandardHelpOptions = true
    )
    static class ConfigCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @CommandLine.Command(name = "show", description = "Show subprocess configuration", mixinStandardHelpOptions = true)
        static class ShowCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    String response = client.getString("/api/subprocess-config");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode node = client.getObjectMapper().readTree(response);
                        System.out.println("Subprocess Configuration:");
                        node.fields().forEachRemaining(entry ->
                                OutputFormatter.printKv(entry.getKey(), entry.getValue()));
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

        @CommandLine.Command(name = "set", description = "Update subprocess configuration", mixinStandardHelpOptions = true)
        static class SetCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Option(names = "--heap", description = "Heap size (e.g. 4g)")
            private String heap;

            @CommandLine.Option(names = "--timeout", description = "Timeout in seconds")
            private Integer timeout;

            @CommandLine.Option(names = "--max-processes", description = "Maximum concurrent subprocesses")
            private Integer maxProcesses;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (heap != null) body.put("heapSize", heap);
                    if (timeout != null) body.put("timeoutSeconds", timeout);
                    if (maxProcesses != null) body.put("maxConcurrentProcesses", maxProcesses);

                    if (body.isEmpty()) {
                        System.err.println("No configuration options specified. Use --heap, --timeout, or --max-processes.");
                        return 1;
                    }

                    String response = client.postString("/api/subprocess-config", body);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Subprocess configuration updated.");
                        body.forEach((k, v) -> OutputFormatter.printKv(k, v));
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
    }

    @CommandLine.Command(name = "events", description = "Show recent subprocess events", mixinStandardHelpOptions = true)
    static class EventsCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = "--hours", defaultValue = "24", description = "Hours of history (default: 24)")
        private int hours;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/subprocess-events/recent?hours=" + hours);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode array = client.getObjectMapper().readTree(response);
                    System.out.println("Subprocess Events (last " + hours + "h):");
                    OutputFormatter.printTable(array, "timestamp", "type", "event", "taskId", "message");
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

    @CommandLine.Command(name = "stats", description = "Show subprocess event statistics", mixinStandardHelpOptions = true)
    static class StatsCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/subprocess-events/statistics");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Subprocess Statistics:");
                    node.fields().forEachRemaining(entry ->
                            OutputFormatter.printKv(entry.getKey(), entry.getValue()));
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
}
