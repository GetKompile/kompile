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

package ai.kompile.cli.main.manage;

import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager.ComponentStatus;
import ai.kompile.cli.main.manage.ServiceManager.ProcessResult;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Main management command for starting, stopping, and monitoring Kompile components.
 * Subcommands: start, stop, restart, status, list, logs
 */
@CommandLine.Command(name = "manage", 
        description = "Manage Kompile components (start, stop, restart, status)",
        subcommands = {
                ManageComponents.StartCommand.class,
                ManageComponents.StopCommand.class,
                ManageComponents.RestartCommand.class,
                ManageComponents.StatusCommand.class,
                ManageComponents.ListCommand.class,
                ManageComponents.LogsCommand.class
        },
        mixinStandardHelpOptions = true)
public class ManageComponents implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        System.out.println("Kompile Component Manager");
        System.out.println("=========================");
        System.out.println();
        System.out.println("Usage: kompile manage <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  start     - Start a component");
        System.out.println("  stop      - Stop a running component");
        System.out.println("  restart   - Restart a component");
        System.out.println("  status    - Check status of a component");
        System.out.println("  list      - List all components and their statuses");
        System.out.println("  logs      - View component logs");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kompile manage start kompile-app-main");
        System.out.println("  kompile manage start kompile-model-staging --port 9090");
        System.out.println("  kompile manage list");
        System.out.println("  kompile manage stop kompile-app-main");
        System.out.println("  kompile manage status kompile-app-main");
        
        return 0;
    }

    /**
     * Start a component
     */
    @CommandLine.Command(name = "start", description = "Start a Kompile component")
    public static class StartCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Component to start (e.g., kompile-app-main, kompile-model-staging)")
        private String componentId;

        @CommandLine.Option(names = {"--port"}, description = "Port to run the service on")
        private Integer port;

        @CommandLine.Option(names = {"--jvm-arg"}, description = "JVM argument (can be specified multiple times)")
        private List<String> jvmArgs = new ArrayList<>();

        @CommandLine.Option(names = {"--app-arg"}, description = "Application argument (can be specified multiple times)")
        private List<String> appArgs = new ArrayList<>();

        @CommandLine.Option(names = {"--verbose"}, description = "Enable verbose output")
        private boolean verbose = false;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();
            
            // Get default port from registry if not specified
            if (port == null) {
                ComponentRegistry registry =
                        new ComponentRegistry();
                var descriptor = registry.getComponent(componentId);
                port = descriptor.flatMap(ComponentRegistry.ComponentDescriptor::getDefaultPort)
                        .orElse(8080);
            }

            try {
                ProcessResult result = manager.startComponent(componentId, port, jvmArgs, appArgs);
                
                if (result.hasError()) {
                    System.err.println(result.getMessage());
                    return 1;
                }

                System.out.println("\n✓ Component started successfully");
                System.out.println("  Component: " + result.getComponentId());
                System.out.println("  PID: " + result.getPid().orElse(-1L));
                System.out.println("  Port: " + result.getPort().orElse(-1));
                
                return 0;

            } catch (Exception e) {
                System.err.println("✗ Failed to start component: " + e.getMessage());
                if (verbose) {
                    e.printStackTrace();
                }
                return 1;
            }
        }
    }

    /**
     * Stop a component
     */
    @CommandLine.Command(name = "stop", description = "Stop a running component")
    public static class StopCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Component to stop")
        private String componentId;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();

            try {
                ProcessResult result = manager.stopComponent(componentId);
                
                if (result.hasError()) {
                    System.err.println(result.getMessage());
                    return 1;
                }

                System.out.println("✓ Component stopped");
                return 0;

            } catch (Exception e) {
                System.err.println("✗ Failed to stop component: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Restart a component
     */
    @CommandLine.Command(name = "restart", description = "Restart a component")
    public static class RestartCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Component to restart")
        private String componentId;

        @CommandLine.Option(names = {"--port"}, description = "Port to run the service on")
        private Integer port;

        @CommandLine.Option(names = {"--jvm-arg"}, description = "JVM argument")
        private List<String> jvmArgs = new ArrayList<>();

        @CommandLine.Option(names = {"--app-arg"}, description = "Application argument")
        private List<String> appArgs = new ArrayList<>();

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();
            
            if (port == null) {
                ComponentRegistry registry =
                        new ComponentRegistry();
                var descriptor = registry.getComponent(componentId);
                port = descriptor.flatMap(ComponentRegistry.ComponentDescriptor::getDefaultPort)
                        .orElse(8080);
            }

            try {
                ProcessResult result = manager.restartComponent(componentId, port, jvmArgs, appArgs);
                
                if (result.hasError()) {
                    System.err.println(result.getMessage());
                    return 1;
                }

                System.out.println("✓ Component restarted successfully");
                System.out.println("  Component: " + result.getComponentId());
                System.out.println("  PID: " + result.getPid().orElse(-1L));
                System.out.println("  Port: " + result.getPort().orElse(-1));
                
                return 0;

            } catch (Exception e) {
                System.err.println("✗ Failed to restart component: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Check status of a component
     */
    @CommandLine.Command(name = "status", description = "Check status of a component")
    public static class StatusCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Component to check")
        private String componentId;

        @CommandLine.Option(names = {"--json"}, description = "Output as JSON")
        private boolean jsonOutput = false;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();
            ComponentStatus status = manager.getComponentStatus(componentId);

            if (jsonOutput) {
                printStatusJson(status);
            } else {
                System.out.println("Component: " + status.getComponentId());
                System.out.println("  Status: " + status.getStatusIcon() + " " + status.getStatus());
                System.out.println("  Installed: " + (status.isInstalled() ? "yes" : "no"));
                status.getPid().ifPresent(pid -> System.out.println("  PID: " + pid));
                status.getPort().ifPresent(port -> System.out.println("  Port: " + port));
                status.getUrl().ifPresent(url -> System.out.println("  URL: " + url));
                status.getMessage().ifPresent(msg -> System.out.println("  Message: " + msg));
            }

            return status.getStatus().equals("running") ? 0 : 1;
        }
    }

    /**
     * List all components
     */
    @CommandLine.Command(name = "list", description = "List all components and their statuses")
    public static class ListCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"--json"}, description = "Output as JSON")
        private boolean jsonOutput = false;

        @Override
        public Integer call() throws Exception {
            ServiceManager manager = new ServiceManager();
            List<ComponentStatus> statuses = manager.listAllComponents();

            if (jsonOutput) {
                System.out.println("[");
                for (int i = 0; i < statuses.size(); i++) {
                    ComponentStatus status = statuses.get(i);
                    System.out.println("  {");
                    System.out.println("    \"component\": \"" + status.getComponentId() + "\",");
                    System.out.println("    \"status\": \"" + status.getStatus() + "\",");
                    System.out.println("    \"installed\": " + status.isInstalled());
                    status.getPid().ifPresent(pid -> System.out.println("    ,\"pid\": " + pid));
                    status.getPort().ifPresent(port -> System.out.println("    ,\"port\": " + port));
                    System.out.println("  }" + (i < statuses.size() - 1 ? "," : ""));
                }
                System.out.println("]");
            } else {
                System.out.println("Kompile Components");
                System.out.println("==================");
                System.out.println();
                System.out.printf("%-30s %-15s %-10s %-8s %-15s%n", "COMPONENT", "STATUS", "INSTALLED", "PID", "PORT");
                System.out.println("-".repeat(90));

                for (ComponentStatus status : statuses) {
                    System.out.printf("%-30s %-15s %-10s %-8s %-15s%n",
                            status.getComponentId(),
                            status.getStatusIcon() + " " + status.getStatus(),
                            status.isInstalled() ? "yes" : "no",
                            status.getPid().map(Object::toString).orElse("-"),
                            status.getPort().map(Object::toString).orElse("-"));
                }

                System.out.println();
                long running = statuses.stream().filter(s -> s.getStatus().equals("running")).count();
                long total = statuses.size();
                System.out.println("Summary: " + running + "/" + total + " components running");
            }

            return 0;
        }
    }

    /**
     * View component logs
     */
    @CommandLine.Command(name = "logs", description = "View component logs")
    public static class LogsCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Component to view logs for")
        private String componentId;

        @CommandLine.Option(names = {"--lines"}, description = "Number of lines to show", defaultValue = "100")
        private int lines = 100;

        @CommandLine.Option(names = {"--follow"}, description = "Follow log output")
        private boolean follow = false;

        @Override
        public Integer call() throws Exception {
            InstanceInfo info =
                    InstanceRegistry.findByType(componentId);

            if (info == null) {
                System.err.println("Component not running: " + componentId);
                return 1;
            }

            List<File> logFiles = existingComponentLogFiles(componentId);
            if (logFiles.isEmpty()) {
                System.err.println("No component log files found for " + componentId);
                for (File file : componentLogFiles(componentId)) {
                    System.err.println("  expected: " + file.getAbsolutePath());
                }
                return 1;
            }

            boolean showHeaders = logFiles.size() > 1;
            for (File file : logFiles) {
                printLastLines(file, lines, showHeaders);
            }

            if (follow) {
                followLogs(logFiles);
            }

            return 0;
        }
    }

    private static void printStatusJson(ComponentStatus status) {
        List<String> fields = new ArrayList<>();
        fields.add("  \"component\": " + jsonString(status.getComponentId()));
        fields.add("  \"status\": " + jsonString(status.getStatus()));
        fields.add("  \"installed\": " + status.isInstalled());
        status.getPid().ifPresent(pid -> fields.add("  \"pid\": " + pid));
        status.getPort().ifPresent(port -> fields.add("  \"port\": " + port));
        status.getUrl().ifPresent(url -> fields.add("  \"url\": " + jsonString(url)));
        status.getMessage().ifPresent(message -> fields.add("  \"message\": " + jsonString(message)));

        System.out.println("{");
        System.out.println(String.join(",\n", fields));
        System.out.println("}");
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static List<File> existingComponentLogFiles(String componentId) {
        List<File> existing = new ArrayList<>();
        for (File file : componentLogFiles(componentId)) {
            if (file.isFile()) {
                existing.add(file);
            }
        }
        return existing;
    }

    static File[] componentLogFiles(String componentId) {
        File dir = new File(LogPaths.logsDirectory(), "components");
        String safeName = safeFileName(componentId);
        return new File[] {
                new File(dir, safeName + ".out.log"),
                new File(dir, safeName + ".err.log")
        };
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "_unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void printLastLines(File file, int requestedLines, boolean showHeader) throws IOException {
        if (showHeader) {
            System.out.println("==> " + file.getAbsolutePath() + " <==");
        }
        int lineLimit = Math.max(0, requestedLines);
        if (lineLimit == 0) {
            return;
        }

        Deque<String> tail = new ArrayDeque<>(lineLimit);
        try (Stream<String> stream = Files.lines(file.toPath())) {
            stream.forEach(line -> {
                if (tail.size() == lineLimit) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            });
        }
        tail.forEach(System.out::println);
    }

    private static void followLogs(List<File> files) throws IOException {
        List<LogCursor> cursors = new ArrayList<>();
        for (File file : files) {
            cursors.add(new LogCursor(file));
        }
        while (!Thread.currentThread().isInterrupted()) {
            for (LogCursor cursor : cursors) {
                cursor.printNewLines();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class LogCursor {
        private final File file;
        private long position;

        private LogCursor(File file) {
            this.file = file;
            this.position = file.length();
        }

        private void printNewLines() throws IOException {
            if (!file.isFile()) {
                return;
            }
            if (file.length() < position) {
                position = 0;
            }
            try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
                reader.seek(position);
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[" + file.getName() + "] " + decodeUtf8Line(line));
                }
                position = reader.getFilePointer();
            }
        }
    }

    private static String decodeUtf8Line(String line) {
        return new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
}
