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
        name = "index",
        description = "Index and vector population management",
        subcommands = {
                IndexCommand.StatusCmd.class,
                IndexCommand.RebuildCmd.class,
                IndexCommand.VectorCmd.class
        },
        mixinStandardHelpOptions = true
)
public class IndexCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "status", description = "Show index status and statistics", mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/indexer/status");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode node = client.getObjectMapper().readTree(response);
                    System.out.println("Index Status:");
                    OutputFormatter.printKv("State", node.path("state"));
                    OutputFormatter.printKv("Documents", node.path("documentCount"));
                    OutputFormatter.printKv("Index Type", node.path("indexType"));
                    OutputFormatter.printKv("Last Updated", node.path("lastUpdated"));
                    OutputFormatter.printKv("Index Size", node.path("indexSize"));
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

    @CommandLine.Command(name = "rebuild", description = "Rebuild index from all sources", mixinStandardHelpOptions = true)
    static class RebuildCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/indexer/rebuild-all-sources");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Index rebuild started.");
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.has("taskId")) {
                        OutputFormatter.printKv("Task ID", node.get("taskId").asText());
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

    @CommandLine.Command(
            name = "vector",
            description = "Vector population management",
            subcommands = {
                    VectorCmd.StartCmd.class,
                    VectorCmd.StatusCmd.class,
                    VectorCmd.CancelCmd.class,
                    VectorCmd.TasksCmd.class
            },
            mixinStandardHelpOptions = true
    )
    static class VectorCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @CommandLine.Command(name = "start", description = "Start vector population", mixinStandardHelpOptions = true)
        static class StartCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    String response = client.postEmpty("/api/vector-population/start");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Vector population started.");
                        JsonNode node = client.getObjectMapper().readTree(response);
                        if (node.has("taskId")) {
                            OutputFormatter.printKv("Task ID", node.get("taskId").asText());
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

        @CommandLine.Command(name = "status", description = "Check vector population status", mixinStandardHelpOptions = true)
        static class StatusCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Parameters(index = "0", arity = "0..1", description = "Task ID (omit to list all)")
            private String taskId;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    if (taskId != null) {
                        String response = client.getString("/api/vector-population/status/" + taskId);
                        if (app.isJsonOutput()) {
                            OutputFormatter.printJson(response);
                        } else {
                            JsonNode node = client.getObjectMapper().readTree(response);
                            System.out.println("Vector Population Task: " + taskId);
                            OutputFormatter.printKv("Status", node.path("status"));
                            OutputFormatter.printKv("Progress", node.path("progress"));
                            OutputFormatter.printKv("Documents", node.path("documentsProcessed"));
                            OutputFormatter.printKv("Errors", node.path("errors"));
                        }
                    } else {
                        String response = client.getString("/api/vector-population/tasks");
                        if (app.isJsonOutput()) {
                            OutputFormatter.printJson(response);
                        } else {
                            JsonNode array = client.getObjectMapper().readTree(response);
                            System.out.println("Vector Population Tasks:");
                            OutputFormatter.printTable(array, "taskId", "status", "progress", "startTime");
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

        @CommandLine.Command(name = "cancel", description = "Cancel a vector population task", mixinStandardHelpOptions = true)
        static class CancelCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Parameters(index = "0", description = "Task ID to cancel")
            private String taskId;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    String response = client.postEmpty("/api/vector-population/cancel/" + taskId);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Cancelled vector population task: " + taskId);
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

        @CommandLine.Command(name = "tasks", description = "List all vector population tasks", mixinStandardHelpOptions = true)
        static class TasksCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    String response = client.getString("/api/vector-population/tasks");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode array = client.getObjectMapper().readTree(response);
                        System.out.println("Vector Population Tasks:");
                        OutputFormatter.printTable(array, "taskId", "status", "progress", "startTime");
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
}
