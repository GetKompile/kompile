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

import ai.kompile.cli.main.manage.ServiceManager.ComponentStatus;
import ai.kompile.cli.main.manage.ServiceManager.ProcessResult;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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
                ai.kompile.cli.main.install.registry.ComponentRegistry registry = 
                        new ai.kompile.cli.main.install.registry.ComponentRegistry();
                var descriptor = registry.getComponent(componentId);
                port = descriptor.flatMap(ai.kompile.cli.main.install.registry.ComponentRegistry.ComponentDescriptor::getDefaultPort)
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
                ai.kompile.cli.main.install.registry.ComponentRegistry registry = 
                        new ai.kompile.cli.main.install.registry.ComponentRegistry();
                var descriptor = registry.getComponent(componentId);
                port = descriptor.flatMap(ai.kompile.cli.main.install.registry.ComponentRegistry.ComponentDescriptor::getDefaultPort)
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
                // Simple JSON output
                System.out.println("{");
                System.out.println("  \"component\": \"" + status.getComponentId() + "\",");
                System.out.println("  \"status\": \"" + status.getStatus() + "\",");
                System.out.println("  \"installed\": " + status.isInstalled() + ",");
                status.getPid().ifPresent(pid -> System.out.println("  \"pid\": " + pid + ","));
                status.getPort().ifPresent(port -> System.out.println("  \"port\": " + port + ","));
                status.getUrl().ifPresent(url -> System.out.println("  \"url\": \"" + url + "\","));
                System.out.println("}");
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
            ai.kompile.cli.common.registry.InstanceInfo info = 
                    ai.kompile.cli.common.registry.InstanceRegistry.findByType(componentId);

            if (info == null) {
                System.err.println("Component not running: " + componentId);
                return 1;
            }

            // For now, indicate that logs should be checked via standard output
            // In a full implementation, you'd redirect to a log file
            System.out.println("Component " + componentId + " is running with PID: " + info.getPid());
            System.out.println("Logs are being written to stdout/stderr.");
            System.out.println("To view logs in real-time, use:");
            System.out.println("  tail -f /proc/" + info.getPid() + "/fd/1");
            
            return 0;
        }
    }
}
