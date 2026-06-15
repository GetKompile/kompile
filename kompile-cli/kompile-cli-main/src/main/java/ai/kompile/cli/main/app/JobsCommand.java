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
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "jobs",
        description = "Indexing job history and logs",
        subcommands = {
                JobsCommand.ListCmd.class,
                JobsCommand.ShowCmd.class,
                JobsCommand.LogsCmd.class,
                JobsCommand.StatsCmd.class
        },
        mixinStandardHelpOptions = true
)
public class JobsCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "list", description = "List recent indexing jobs", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = "--status", description = "Filter by status (e.g. COMPLETED, FAILED, RUNNING)")
        private String status;

        @CommandLine.Option(names = "--hours", defaultValue = "24", description = "Hours of history to show (default: 24)")
        private int hours;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String path;
                if (status != null && !status.isBlank()) {
                    path = "/api/indexing/history/status/" + status;
                } else {
                    path = "/api/indexing/history/recent?hours=" + hours;
                }
                String response = client.getString(path);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode array = client.getObjectMapper().readTree(response);
                    System.out.println("Indexing Jobs (last " + hours + "h):");
                    OutputFormatter.printTable(array, "taskId", "status", "type", "startTime", "duration");
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

    @CommandLine.Command(name = "show", description = "Show details of a specific job", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Task ID")
        private String taskId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/indexing/history/" + taskId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Job Details: " + taskId);
                    OutputFormatter.printKv("Status", node.path("status"));
                    OutputFormatter.printKv("Type", node.path("type"));
                    OutputFormatter.printKv("Start Time", node.path("startTime"));
                    OutputFormatter.printKv("End Time", node.path("endTime"));
                    OutputFormatter.printKv("Duration", node.path("duration"));
                    OutputFormatter.printKv("Documents", node.path("documentsProcessed"));
                    OutputFormatter.printKv("Errors", node.path("errorCount"));
                    if (node.has("error") && !node.get("error").isNull()) {
                        OutputFormatter.printKv("Error", node.get("error").asText());
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

    @CommandLine.Command(name = "logs", description = "Tail logs for a job", mixinStandardHelpOptions = true)
    static class LogsCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Task ID")
        private String taskId;

        @CommandLine.Option(names = "--tail", defaultValue = "50", description = "Number of log lines (default: 50)")
        private int lines;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/indexing/jobs/" + taskId + "/logs/tail?lines=" + lines);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.isArray()) {
                        for (JsonNode line : node) {
                            System.out.println(line.asText());
                        }
                    } else if (node.has("lines") && node.get("lines").isArray()) {
                        for (JsonNode line : node.get("lines")) {
                            System.out.println(line.asText());
                        }
                    } else {
                        System.out.println(response);
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

    @CommandLine.Command(name = "stats", description = "Show indexing statistics", mixinStandardHelpOptions = true)
    static class StatsCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = "--hours", defaultValue = "24", description = "Hours of history (default: 24)")
        private int hours;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/indexing/history/statistics?lastHours=" + hours);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Indexing Statistics (last " + hours + "h):");
                    OutputFormatter.printKv("Total Jobs", node.path("totalJobs"));
                    OutputFormatter.printKv("Completed", node.path("completed"));
                    OutputFormatter.printKv("Failed", node.path("failed"));
                    OutputFormatter.printKv("Running", node.path("running"));
                    OutputFormatter.printKv("Avg Duration", node.path("averageDuration"));
                    OutputFormatter.printKv("Total Documents", node.path("totalDocuments"));
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
