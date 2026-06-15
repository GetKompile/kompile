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

package ai.kompile.cli.component.cmd;

import ai.kompile.cli.component.output.OutputFormatter;
import ai.kompile.cli.component.output.OutputFormatter.Format;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Command to check status of specific component(s)
 */
@Command(name = "status", 
        description = "Check status of specific component(s)",
        mixinStandardHelpOptions = true)
public class ComponentStatusCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Component ID to check (omit for all)")
    private String componentId;

    @Option(names = {"--format", "-f"}, 
            description = "Output format: text, json, yaml, csv, table",
            defaultValue = "text")
    private Format format = Format.TEXT;

    @Option(names = {"--health-check"}, 
            description = "Perform HTTP health check")
    private boolean healthCheck = false;

    @Option(names = {"--verbose"}, 
            description = "Show detailed information")
    private boolean verbose = false;

    @Override
    public Integer call() throws Exception {
        List<Map<String, Object>> statuses = new ArrayList<>();

        if (componentId != null) {
            statuses.add(getComponentStatus(componentId));
        } else {
            // Get status for all known components
            List<String> allComponents = List.of(
                    "kompile-app-main",
                    "kompile-model-staging",
                    "kompile-cli",
                    "kompile-app",
                    "kompile-model",
                    "kompile-agent"
            );

            for (String id : allComponents) {
                statuses.add(getComponentStatus(id));
            }
        }

        // Output
        if (statuses.size() == 1 && format == Format.TEXT && !verbose) {
            // Single component - show detailed text
            System.out.println(formatAsDetailedText(statuses.get(0)));
        } else {
            String output = OutputFormatter.formatList(statuses, format);
            System.out.println(output);
        }

        // Return non-zero if any component is not running
        boolean allRunning = statuses.stream()
                .allMatch(s -> "running".equals(s.get("status")));
        
        return allRunning ? 0 : 1;
    }

    private Map<String, Object> getComponentStatus(String id) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", id);
        
        // Check installation
        boolean installed = checkIfInstalled(id);
        status.put("installed", installed);

        if (!installed) {
            status.put("status", "not_installed");
            status.put("message", "Component is not installed");
            return status;
        }

        // Check if running
        InstanceInfo instance = getInstanceInfo(id);
        
        if (instance == null) {
            status.put("status", "not_running");
            status.put("message", "Component is not running");
            status.put("installPath", getInstallPath(id));
            return status;
        }

        // Component has a registered instance
        boolean isAlive = ProcessHandle.of(instance.pid())
                .map(ProcessHandle::isAlive)
                .orElse(false);

        if (!isAlive) {
            status.put("status", "dead");
            status.put("pid", instance.pid());
            status.put("port", instance.port());
            status.put("message", "Process is not alive (stale registry entry)");
            return status;
        }

        // Component is running
        status.put("status", "running");
        status.put("pid", instance.pid());
        status.put("port", instance.port());
        status.put("url", "http://localhost:" + instance.port());
        status.put("startedAt", instance.startedAt());
        status.put("jarPath", instance.jarPath());

        // Health check if requested
        if (healthCheck && instance.port() != null) {
            boolean healthy = checkHealth(instance.port());
            status.put("healthy", healthy);
            status.put("status", healthy ? "running" : "unhealthy");
        }

        // Additional verbose info
        if (verbose) {
            status.put("uptime", calculateUptime(instance.startedAt()));
            status.put("installPath", getInstallPath(id));
            
            // Check resource usage (if available)
            ProcessHandle handle = ProcessHandle.of(instance.pid()).orElse(null);
            if (handle != null) {
                ProcessHandle.Info info = handle.info();
                info.totalCpuDuration().ifPresent(d -> 
                        status.put("cpuTime", d.toMillis() + "ms"));
                info.user().ifPresent(u -> 
                        status.put("user", u));
            }
        }

        return status;
    }

    private String formatAsDetailedText(Map<String, Object> status) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component: ").append(status.get("component")).append("\n");
        sb.append("  Status: ").append(getStatusIcon(status)).append(" ").append(status.get("status")).append("\n");
        sb.append("  Installed: ").append(status.get("installed")).append("\n");
        
        if (status.containsKey("pid")) {
            sb.append("  PID: ").append(status.get("pid")).append("\n");
        }
        if (status.containsKey("port")) {
            sb.append("  Port: ").append(status.get("port")).append("\n");
        }
        if (status.containsKey("url")) {
            sb.append("  URL: ").append(status.get("url")).append("\n");
        }
        if (status.containsKey("healthy")) {
            sb.append("  Health: ").append(Boolean.TRUE.equals(status.get("healthy")) ? "✓ healthy" : "✗ unhealthy").append("\n");
        }
        if (status.containsKey("startedAt")) {
            sb.append("  Started: ").append(status.get("startedAt")).append("\n");
        }
        if (status.containsKey("uptime")) {
            sb.append("  Uptime: ").append(status.get("uptime")).append("\n");
        }
        if (status.containsKey("jarPath")) {
            sb.append("  JAR: ").append(status.get("jarPath")).append("\n");
        }
        if (status.containsKey("message")) {
            sb.append("  Message: ").append(status.get("message")).append("\n");
        }

        return sb.toString();
    }

    private String getStatusIcon(Map<String, Object> status) {
        String statusStr = (String) status.get("status");
        return switch (statusStr) {
            case "running" -> "✓";
            case "unhealthy" -> "⚠";
            case "not_running", "dead" -> "✗";
            case "not_installed" -> "✗";
            default -> "?";
        };
    }

    private boolean checkIfInstalled(String componentId) {
        return getInstallPath(componentId) != null;
    }

    private String getInstallPath(String componentId) {
        String homeDir = System.getProperty("user.home");
        File componentsDir = new File(homeDir, ".kompile/components/" + componentId);
        
        if (!componentsDir.exists()) {
            return null;
        }

        File[] versions = componentsDir.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            return null;
        }

        // Return the first version directory
        return versions[0].getAbsolutePath();
    }

    private InstanceInfo getInstanceInfo(String componentId) {
        try {
            String homeDir = System.getProperty("user.home");
            File instancesDir = new File(homeDir, ".kompile/instances");
            
            if (!instancesDir.exists()) {
                return null;
            }

            File[] instanceFiles = instancesDir.listFiles((dir, name) -> 
                    name.equals(componentId + ".json"));

            if (instanceFiles == null || instanceFiles.length == 0) {
                return null;
            }

            File instanceFile = instanceFiles[0];
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> instanceMap = mapper.readValue(instanceFile, Map.class);
            
            Long pid = ((Number) instanceMap.get("pid")).longValue();
            Integer port = instanceMap.get("port") != null ? 
                    ((Number) instanceMap.get("port")).intValue() : null;
            String jarPath = (String) instanceMap.get("jarPath");
            String startedAt = (String) instanceMap.get("startedAt");

            return new InstanceInfo(componentId, pid, port, jarPath, startedAt);

        } catch (Exception e) {
            return null;
        }
    }

    private boolean checkHealth(int port) {
        try {
            // Try actuator health endpoint first
            URL url = new URL("http://localhost:" + port + "/actuator/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            
            if (responseCode == 200) {
                return true;
            }

            // Fallback to root endpoint
            URL rootUrl = new URL("http://localhost:" + port + "/");
            HttpURLConnection rootConn = (HttpURLConnection) rootUrl.openConnection();
            rootConn.setRequestMethod("GET");
            rootConn.setConnectTimeout(3000);
            rootConn.setReadTimeout(3000);
            
            int rootResponse = rootConn.getResponseCode();
            rootConn.disconnect();
            
            return rootResponse == 200;

        } catch (Exception e) {
            return false;
        }
    }

    private String calculateUptime(String startedAtStr) {
        try {
            Instant startedAt = Instant.parse(startedAtStr);
            Instant now = Instant.now();
            long uptimeMs = java.time.Duration.between(startedAt, now).toMillis();
            
            long seconds = uptimeMs / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;

            if (days > 0) {
                return String.format("%d days, %d hours", days, hours % 24);
            } else if (hours > 0) {
                return String.format("%d hours, %d minutes", hours, minutes % 60);
            } else if (minutes > 0) {
                return String.format("%d minutes, %d seconds", minutes, seconds % 60);
            } else {
                return seconds + " seconds";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    private record InstanceInfo(String componentId, Long pid, Integer port, 
                                 String jarPath, String startedAt) {}
}
