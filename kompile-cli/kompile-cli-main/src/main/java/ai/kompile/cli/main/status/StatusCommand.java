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

package ai.kompile.cli.main.status;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Root runtime status view spanning the daemon, registered app instances, and
 * backend service status endpoints when an app is reachable.
 */
@CommandLine.Command(
        name = "status",
        aliases = {"ps"},
        description = "Show unified Kompile daemon, instance, and backend status",
        mixinStandardHelpOptions = true
)
public class StatusCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @CommandLine.Option(names = "--json", description = "Emit machine-readable JSON")
    private boolean json;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Include extra backend endpoint details")
    private boolean verbose;

    @CommandLine.Option(names = "--strict", description = "Return non-zero if registered instances are dead/unhealthy")
    private boolean strict;

    @CommandLine.Option(names = "--gc", description = "Remove stale registry entries before reporting")
    private boolean gc;

    @CommandLine.Option(names = "--no-backend", description = "Skip backend API probes beyond HTTP health")
    private boolean noBackend;

    @Override
    public Integer call() throws Exception {
        List<InstanceInfo> removed = gc ? InstanceRegistry.gcDeadInstances() : List.of();
        List<InstanceInfo> infos = InstanceRegistry.listAll();
        infos.sort(Comparator
                .comparing((InstanceInfo info) -> safe(info.getType()))
                .thenComparing(info -> safe(info.getName())));

        DaemonSnapshot daemon = inspectDaemon();
        List<InstanceSnapshot> instances = new ArrayList<>();
        for (InstanceInfo info : infos) {
            instances.add(inspectInstance(info));
        }

        Map<String, Object> report = buildReport(daemon, instances, removed);
        if (json) {
            System.out.println(MAPPER.writeValueAsString(report));
        } else {
            printText(daemon, instances, removed);
        }

        boolean hasFailure = "stale".equals(daemon.status)
                || instances.stream().anyMatch(InstanceSnapshot::isProblem);
        return strict && hasFailure ? 1 : 0;
    }

    private DaemonSnapshot inspectDaemon() {
        Path runDir = KompileHome.runtimeDirectory().toPath();
        Path pidFile = runDir.resolve("kompile.pid");
        Path logFile = runDir.resolve("daemon.log");
        Path socketFile = KompileHome.daemonSocketFile().toPath();
        Path lockFile = KompileHome.daemonLockFile().toPath();

        Long pid = null;
        String error = null;
        boolean pidFileExists = Files.exists(pidFile);
        if (pidFileExists) {
            try {
                String text = Files.readString(pidFile).trim();
                if (!text.isBlank()) {
                    pid = Long.parseLong(text);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
        }

        boolean alive = pid != null && isPidAlive(pid);
        String status = alive ? "running" : pidFileExists ? "stale" : "stopped";
        return new DaemonSnapshot(
                status,
                pid,
                alive,
                pidFileExists,
                Files.exists(socketFile),
                Files.exists(lockFile),
                Files.exists(logFile),
                pidFile.toString(),
                socketFile.toString(),
                lockFile.toString(),
                logFile.toString(),
                error
        );
    }

    private InstanceSnapshot inspectInstance(InstanceInfo info) {
        long pid = info.getPid();
        int port = info.getPort();
        boolean pidAlive = pid > 0 && isPidAlive(pid);
        boolean portOpen = port > 0 && isPortOpen(port);
        boolean healthy = false;
        String health = portOpen ? "unhealthy" : "unreachable";
        String error = null;
        Map<String, Object> backend = new LinkedHashMap<>();

        if (portOpen) {
            KompileHttpClient client = new KompileHttpClient(info.getUrl());
            healthy = client.isHealthy();
            health = healthy ? "healthy" : "unhealthy";
            if (!noBackend && healthy && exposesManagementBackend(info)) {
                fetchBackend(client, backend, "components", "/api/system/components");
                fetchBackend(client, backend, "indexStatus", "/api/services/index-status");
                if (verbose) {
                    fetchBackend(client, backend, "systemHealth", "/api/system/health");
                    fetchBackend(client, backend, "serviceState", "/api/services/state");
                }
            }
        }

        String status;
        if (healthy) {
            status = "healthy";
        } else if (!pidAlive && !portOpen) {
            status = "dead";
        } else {
            status = "unhealthy";
        }

        return new InstanceSnapshot(
                safe(info.getName()),
                safe(info.getType()),
                status,
                health,
                pid,
                port,
                info.getUrl(),
                safe(info.getProjectDir()),
                safe(info.getJarPath()),
                info.getStartedAt(),
                pidAlive,
                portOpen,
                healthy,
                backend,
                error
        );
    }

    private boolean exposesManagementBackend(InstanceInfo info) {
        String type = safe(info.getType()).toLowerCase(Locale.ROOT);
        return "app".equals(type) || "kompile-app-main".equals(type) || type.contains("app-main");
    }

    private void fetchBackend(KompileHttpClient client, Map<String, Object> backend, String key, String path) {
        try {
            String body = client.getString(path);
            if (body == null || body.isBlank()) {
                backend.put(key, Map.of("status", "empty"));
            } else {
                backend.put(key, MAPPER.readValue(body, Object.class));
            }
        } catch (Exception e) {
            backend.put(key, Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Map<String, Object> buildReport(DaemonSnapshot daemon,
                                            List<InstanceSnapshot> instances,
                                            List<InstanceInfo> removed) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now());
        report.put("daemon", daemon.toMap());
        report.put("counts", counts(instances));
        report.put("instances", instances.stream().map(InstanceSnapshot::toMap).toList());
        if (!removed.isEmpty()) {
            report.put("removed", removed.stream().map(this::instanceInfoMap).toList());
        }
        return report;
    }

    private Map<String, Object> counts(List<InstanceSnapshot> instances) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", instances.size());
        counts.put("healthy", instances.stream().filter(i -> "healthy".equals(i.status)).count());
        counts.put("unhealthy", instances.stream().filter(i -> "unhealthy".equals(i.status)).count());
        counts.put("dead", instances.stream().filter(i -> "dead".equals(i.status)).count());
        return counts;
    }

    private Map<String, Object> instanceInfoMap(InstanceInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", info.getName());
        map.put("type", info.getType());
        map.put("pid", info.getPid());
        map.put("port", info.getPort());
        map.put("url", info.getUrl());
        map.put("projectDir", info.getProjectDir());
        map.put("jarPath", info.getJarPath());
        map.put("startedAt", info.getStartedAt());
        return map;
    }

    private void printText(DaemonSnapshot daemon, List<InstanceSnapshot> instances, List<InstanceInfo> removed) {
        System.out.println("Kompile Status");
        System.out.printf("daemon:   %-8s", daemon.status);
        if (daemon.pid != null) {
            System.out.printf(" pid=%d", daemon.pid);
        }
        if (daemon.socketExists) {
            System.out.print(" socket=present");
        }
        if (daemon.error != null && !daemon.error.isBlank()) {
            System.out.print(" error=" + daemon.error);
        }
        System.out.println();

        if (!removed.isEmpty()) {
            System.out.printf("gc:       removed %d stale instance%s%n", removed.size(), removed.size() == 1 ? "" : "s");
        }

        System.out.println();
        if (instances.isEmpty()) {
            System.out.println("instances: none registered");
            return;
        }

        System.out.printf("%-24s %-12s %-10s %-11s %-8s %-6s %s%n",
                "NAME", "TYPE", "STATUS", "HEALTH", "PID", "PORT", "PROJECT");
        for (InstanceSnapshot instance : instances) {
            System.out.printf("%-24s %-12s %-10s %-11s %-8s %-6s %s%n",
                    shorten(instance.name, 24),
                    shorten(instance.type, 12),
                    instance.status,
                    instance.health,
                    instance.pid > 0 ? Long.toString(instance.pid) : "-",
                    instance.port > 0 ? Integer.toString(instance.port) : "-",
                    displayProject(instance.projectDir));
        }

        for (InstanceSnapshot instance : instances) {
            String summary = backendSummary(instance.backend);
            if (!summary.isBlank()) {
                System.out.printf("  %s backend: %s%n", instance.name, summary);
                if (verbose) {
                    printVerboseBackend(instance.backend);
                }
            }
        }
    }

    private void printVerboseBackend(Map<String, Object> backend) {
        for (Map.Entry<String, Object> entry : backend.entrySet()) {
            System.out.printf("    %s: %s%n", entry.getKey(), summarizeValue(entry.getValue()));
        }
    }

    private String backendSummary(Map<String, Object> backend) {
        if (backend.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Object components = backend.get("components");
        if (components instanceof List<?> list) {
            parts.add("components=" + list.size());
        } else if (components != null) {
            parts.add("components=" + summarizeValue(components));
        }
        Object index = backend.get("indexStatus");
        if (index != null) {
            parts.add("index=" + summarizeValue(index));
        }
        return String.join(" ", parts);
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "none";
        }
        if (value instanceof Map<?, ?> map) {
            Object error = map.get("error");
            if (error != null) {
                return "error";
            }
            for (String key : List.of("status", "state", "phase", "ready", "healthy", "running")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return key + "=" + candidate;
                }
            }
            return "available";
        }
        if (value instanceof List<?> list) {
            return Integer.toString(list.size());
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        return text.length() > 32 ? text.substring(0, 29) + "..." : text;
    }

    private String displayProject(String projectDir) {
        if (projectDir == null || projectDir.isBlank()) {
            return "-";
        }
        Path path = Path.of(projectDir);
        Path name = path.getFileName();
        return name == null ? projectDir : name.toString();
    }

    private boolean isPidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String shorten(String value, int width) {
        String safe = safe(value);
        if (safe.length() <= width) {
            return safe;
        }
        return safe.substring(0, Math.max(0, width - 1)) + "~";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class DaemonSnapshot {
        final String status;
        final Long pid;
        final boolean pidAlive;
        final boolean pidFileExists;
        final boolean socketExists;
        final boolean lockExists;
        final boolean logExists;
        final String pidFile;
        final String socketFile;
        final String lockFile;
        final String logFile;
        final String error;

        DaemonSnapshot(String status,
                       Long pid,
                       boolean pidAlive,
                       boolean pidFileExists,
                       boolean socketExists,
                       boolean lockExists,
                       boolean logExists,
                       String pidFile,
                       String socketFile,
                       String lockFile,
                       String logFile,
                       String error) {
            this.status = status;
            this.pid = pid;
            this.pidAlive = pidAlive;
            this.pidFileExists = pidFileExists;
            this.socketExists = socketExists;
            this.lockExists = lockExists;
            this.logExists = logExists;
            this.pidFile = pidFile;
            this.socketFile = socketFile;
            this.lockFile = lockFile;
            this.logFile = logFile;
            this.error = error;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("pid", pid);
            map.put("pidAlive", pidAlive);
            map.put("pidFileExists", pidFileExists);
            map.put("socketExists", socketExists);
            map.put("lockExists", lockExists);
            map.put("logExists", logExists);
            map.put("pidFile", pidFile);
            map.put("socketFile", socketFile);
            map.put("lockFile", lockFile);
            map.put("logFile", logFile);
            if (error != null) {
                map.put("error", error);
            }
            return map;
        }
    }

    private static final class InstanceSnapshot {
        final String name;
        final String type;
        final String status;
        final String health;
        final long pid;
        final int port;
        final String url;
        final String projectDir;
        final String jarPath;
        final Instant startedAt;
        final boolean pidAlive;
        final boolean portOpen;
        final boolean httpHealthy;
        final Map<String, Object> backend;
        final String error;

        InstanceSnapshot(String name,
                         String type,
                         String status,
                         String health,
                         long pid,
                         int port,
                         String url,
                         String projectDir,
                         String jarPath,
                         Instant startedAt,
                         boolean pidAlive,
                         boolean portOpen,
                         boolean httpHealthy,
                         Map<String, Object> backend,
                         String error) {
            this.name = name;
            this.type = type;
            this.status = status;
            this.health = health;
            this.pid = pid;
            this.port = port;
            this.url = url;
            this.projectDir = projectDir;
            this.jarPath = jarPath;
            this.startedAt = startedAt;
            this.pidAlive = pidAlive;
            this.portOpen = portOpen;
            this.httpHealthy = httpHealthy;
            this.backend = backend;
            this.error = error;
        }

        boolean isProblem() {
            return "dead".equals(status) || "unhealthy".equals(status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("status", status);
            map.put("health", health);
            map.put("pid", pid);
            map.put("pidAlive", pidAlive);
            map.put("port", port);
            map.put("portOpen", portOpen);
            map.put("httpHealthy", httpHealthy);
            map.put("url", url);
            map.put("projectDir", projectDir);
            map.put("jarPath", jarPath);
            map.put("startedAt", startedAt);
            if (!backend.isEmpty()) {
                map.put("backend", backend);
            }
            if (error != null) {
                map.put("error", error);
            }
            return map;
        }
    }
}
