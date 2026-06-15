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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "ingest",
        description = "Document ingestion management",
        subcommands = {
                IngestCommand.FileCmd.class,
                IngestCommand.PathCmd.class,
                IngestCommand.UrlCmd.class,
                IngestCommand.StatusCmd.class,
                IngestCommand.CancelCmd.class,
                IngestCommand.ListCmd.class
        },
        mixinStandardHelpOptions = true
)
public class IngestCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "file", description = "Upload and ingest a file", mixinStandardHelpOptions = true)
    static class FileCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Path to file to ingest")
        private Path filePath;

        @CommandLine.Option(names = "--async", description = "Run ingestion asynchronously")
        private boolean async;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = async ? "/api/documents/upload-async" : "/api/documents/upload";
                String response = client.uploadFile(endpoint, filePath);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Uploaded: " + filePath.getFileName());
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.has("taskId")) {
                        OutputFormatter.printKv("Task ID", node.get("taskId").asText());
                    }
                    if (node.has("status")) {
                        OutputFormatter.printKv("Status", node.get("status").asText());
                    }
                    if (node.has("message")) {
                        OutputFormatter.printKv("Message", node.get("message").asText());
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

    @CommandLine.Command(name = "path", description = "Add a server-side directory for ingestion", mixinStandardHelpOptions = true)
    static class PathCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Server-side directory path")
        private String dirPath;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString("/api/documents/add-path", Map.of("path", dirPath));
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Added path: " + dirPath);
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.has("message")) {
                        OutputFormatter.printKv("Message", node.get("message").asText());
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

    @CommandLine.Command(name = "url", description = "Add a URL source for ingestion", mixinStandardHelpOptions = true)
    static class UrlCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "URL to ingest")
        private String sourceUrl;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString("/api/documents/add-url", Map.of("url", sourceUrl));
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Added URL: " + sourceUrl);
                    JsonNode node = client.getObjectMapper().readTree(response);
                    if (node.has("message")) {
                        OutputFormatter.printKv("Message", node.get("message").asText());
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

    @CommandLine.Command(name = "status", description = "Check ingest task status", mixinStandardHelpOptions = true)
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
                    String response = client.getString("/api/documents/ingest-status/" + taskId);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode node = client.getObjectMapper().readTree(response);
                        System.out.println("Ingest Task: " + taskId);
                        OutputFormatter.printKv("Status", node.path("status"));
                        OutputFormatter.printKv("Progress", node.path("progress"));
                        OutputFormatter.printKv("Documents", node.path("documentsProcessed"));
                        OutputFormatter.printKv("Error", node.path("error"));
                    }
                } else {
                    String response = client.getString("/api/documents/ingest-tasks");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode array = client.getObjectMapper().readTree(response);
                        System.out.println("Ingest Tasks:");
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

    @CommandLine.Command(name = "cancel", description = "Cancel an ingest task", mixinStandardHelpOptions = true)
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
                String response = client.postEmpty("/api/documents/ingest-cancel/" + taskId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Cancelled task: " + taskId);
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

    @CommandLine.Command(name = "list", description = "List all ingest tasks", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/documents/ingest-tasks");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode array = client.getObjectMapper().readTree(response);
                    System.out.println("Ingest Tasks:");
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
