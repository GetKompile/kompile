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

package ai.kompile.cli.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import picocli.CommandLine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "logs",
        description = "View application logs.",
        mixinStandardHelpOptions = true,
        subcommands = {
                AppLogsCommand.ConfigCmd.class,
                AppLogsCommand.ConfigureCmd.class,
                AppLogsCommand.StatusCmd.class,
                AppLogsCommand.CleanupCmd.class,
                AppLogsCommand.EnableCmd.class,
                AppLogsCommand.DisableCmd.class,
                AppLogsCommand.ArchivesCmd.class
        })
public class AppLogsCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--tail", "-n"}, defaultValue = "100", description = "Number of lines to show")
    private int lines;

    @CommandLine.Option(names = {"--subprocess"}, description = "Filter by subprocess type")
    private String subprocessType;

    @CommandLine.Mixin
    private EndpointOptions endpoint;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = endpoint.client();
        try {
            int limit = Math.max(1, lines);
            String path = subprocessType == null || subprocessType.isBlank()
                    ? "/api/agent-logs/aggregate?limit=" + limit
                    : "/api/subprocess-logs/aggregate?limit=" + limit + "&type=" + encode(subprocessType);
            String result = client.getString(path);
            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to fetch logs: " + e.getMessage());
            return 1;
        }
    }

    static class EndpointOptions {
        @CommandLine.Option(names = {"--url"}, description = "Application URL")
        String url;

        @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Port of the application")
        int port;

        KompileHttpClient client() {
            return KompileHttpClient.create(url, port);
        }
    }

    @CommandLine.Command(name = "config",
            description = "Show job-log retention configuration.",
            mixinStandardHelpOptions = true)
    public static class ConfigCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @Override
        public Integer call() {
            return printJson("Failed to fetch log config", () ->
                    endpoint.client().getString("/api/config/logs"));
        }
    }

    @CommandLine.Command(name = "configure",
            aliases = {"set-config"},
            description = "Update job-log retention configuration.",
            mixinStandardHelpOptions = true)
    public static class ConfigureCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @CommandLine.Option(names = {"--enabled"}, arity = "0..1", fallbackValue = "true",
                description = "Enable or disable job logging")
        Boolean enabled;

        @CommandLine.Option(names = {"--retention-days"}, description = "Retention window in days")
        Integer retentionDays;

        @CommandLine.Option(names = {"--max-entries-per-job"}, description = "Maximum log entries per job")
        Integer maxEntriesPerJob;

        @CommandLine.Option(names = {"--max-total-entries"}, description = "Maximum total log entries")
        Long maxTotalEntries;

        @CommandLine.Option(names = {"--archive-enabled"}, arity = "0..1", fallbackValue = "true",
                description = "Enable or disable log archiving")
        Boolean archiveEnabled;

        @CommandLine.Option(names = {"--archive-path"}, description = "Archive directory path")
        String archivePath;

        @CommandLine.Option(names = {"--archive-on-cleanup"}, arity = "0..1", fallbackValue = "true",
                description = "Archive removed entries during cleanup")
        Boolean archiveOnCleanup;

        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            putIfPresent(body, "enabled", enabled);
            putIfPresent(body, "retentionDays", retentionDays);
            putIfPresent(body, "maxEntriesPerJob", maxEntriesPerJob);
            putIfPresent(body, "maxTotalEntries", maxTotalEntries);
            putIfPresent(body, "archiveEnabled", archiveEnabled);
            putIfPresent(body, "archivePath", archivePath);
            putIfPresent(body, "archiveOnCleanup", archiveOnCleanup);
            if (body.isEmpty()) {
                System.err.println("No configuration changes specified.");
                return 1;
            }
            return printJson("Failed to update log config", () ->
                    endpoint.client().putString("/api/config/logs", body));
        }
    }

    @CommandLine.Command(name = "status",
            description = "Show job-log storage status.",
            mixinStandardHelpOptions = true)
    public static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @Override
        public Integer call() {
            return printJson("Failed to fetch log status", () ->
                    endpoint.client().getString("/api/config/logs/status"));
        }
    }

    @CommandLine.Command(name = "cleanup",
            description = "Run job-log cleanup immediately.",
            mixinStandardHelpOptions = true)
    public static class CleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @CommandLine.Option(names = {"--hours-to-keep"}, defaultValue = "168",
                description = "Hours of logs to retain")
        int hoursToKeep;

        @Override
        public Integer call() {
            int hours = Math.max(1, hoursToKeep);
            return printJson("Failed to run log cleanup", () ->
                    endpoint.client().postEmpty("/api/config/logs/cleanup?hoursToKeep=" + hours));
        }
    }

    @CommandLine.Command(name = "enable",
            description = "Enable job logging.",
            mixinStandardHelpOptions = true)
    public static class EnableCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @Override
        public Integer call() {
            return printJson("Failed to enable job logging", () ->
                    endpoint.client().postEmpty("/api/config/logs/enable"));
        }
    }

    @CommandLine.Command(name = "disable",
            description = "Disable job logging.",
            mixinStandardHelpOptions = true)
    public static class DisableCmd implements Callable<Integer> {
        @CommandLine.Mixin
        EndpointOptions endpoint;

        @Override
        public Integer call() {
            return printJson("Failed to disable job logging", () ->
                    endpoint.client().postEmpty("/api/config/logs/disable"));
        }
    }

    @CommandLine.Command(name = "archives",
            description = "Manage job-log archives.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    ArchivesCmd.ListCmd.class,
                    ArchivesCmd.CreateCmd.class,
                    ArchivesCmd.TaskCmd.class,
                    ArchivesCmd.DownloadCmd.class,
                    ArchivesCmd.DeleteCmd.class
            })
    public static class ArchivesCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            System.err.println("No archive operation specified. Use: list | create | task | download | delete");
            new CommandLine(this).usage(System.err);
            return 1;
        }

        @CommandLine.Command(name = "list",
                description = "List job-log archives.",
                mixinStandardHelpOptions = true)
        public static class ListCmd implements Callable<Integer> {
            @CommandLine.Mixin
            EndpointOptions endpoint;

            @Override
            public Integer call() {
                return printJson("Failed to list log archives", () ->
                        endpoint.client().getString("/api/config/logs/archives"));
            }
        }

        @CommandLine.Command(name = "create",
                description = "Create an archive from all current job logs.",
                mixinStandardHelpOptions = true)
        public static class CreateCmd implements Callable<Integer> {
            @CommandLine.Mixin
            EndpointOptions endpoint;

            @Override
            public Integer call() {
                return printJson("Failed to create log archive", () ->
                        endpoint.client().postEmpty("/api/config/logs/archives/create"));
            }
        }

        @CommandLine.Command(name = "task",
                description = "Create an archive for a single task's logs.",
                mixinStandardHelpOptions = true)
        public static class TaskCmd implements Callable<Integer> {
            @CommandLine.Mixin
            EndpointOptions endpoint;

            @CommandLine.Parameters(index = "0", description = "Task ID")
            String taskId;

            @Override
            public Integer call() {
                return printJson("Failed to create task log archive", () ->
                        endpoint.client().postEmpty("/api/config/logs/archives/task/" + encodePathSegment(taskId)));
            }
        }

        @CommandLine.Command(name = "download",
                description = "Download a job-log archive.",
                mixinStandardHelpOptions = true)
        public static class DownloadCmd implements Callable<Integer> {
            @CommandLine.Mixin
            EndpointOptions endpoint;

            @CommandLine.Parameters(index = "0", description = "Archive file name")
            String fileName;

            @CommandLine.Option(names = {"--output", "-o"}, description = "Output path")
            Path output;

            @Override
            public Integer call() {
                try {
                    KompileHttpClient client = endpoint.client();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(client.getBaseUrl() + "/api/config/logs/archives/download/" + encodePathSegment(fileName)))
                            .header("Accept", "application/octet-stream")
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();
                    HttpResponse<byte[]> response = HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        System.err.println("Failed to download log archive: HTTP " + response.statusCode());
                        if (response.body() != null && response.body().length > 0) {
                            System.err.println(new String(response.body(), StandardCharsets.UTF_8));
                        }
                        return 1;
                    }
                    Path target = output == null ? Path.of(fileName).getFileName() : output;
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.write(target, response.body());
                    System.out.println("Downloaded " + fileName + " to " + target);
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to download log archive: " + e.getMessage());
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "delete",
                description = "Delete a job-log archive.",
                mixinStandardHelpOptions = true)
        public static class DeleteCmd implements Callable<Integer> {
            @CommandLine.Mixin
            EndpointOptions endpoint;

            @CommandLine.Parameters(index = "0", description = "Archive file name")
            String fileName;

            @Override
            public Integer call() {
                return printJson("Failed to delete log archive", () ->
                        endpoint.client().delete("/api/config/logs/archives/" + encodePathSegment(fileName)));
            }
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static int printJson(String failurePrefix, HttpAction action) {
        try {
            System.out.println(action.call());
            return 0;
        } catch (Exception e) {
            System.err.println(failurePrefix + ": " + e.getMessage());
            return 1;
        }
    }

    @FunctionalInterface
    interface HttpAction {
        String call() throws Exception;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String value) {
        return encode(value).replace("+", "%20");
    }
}
