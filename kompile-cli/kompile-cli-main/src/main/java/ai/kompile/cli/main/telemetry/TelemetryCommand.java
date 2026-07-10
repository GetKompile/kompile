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

package ai.kompile.cli.main.telemetry;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI surface for kompile-app observability endpoints.
 */
@CommandLine.Command(
        name = "telemetry",
        description = "Inspect kompile-app telemetry, metrics, GPU lifecycle, and ND4J timing",
        subcommands = {
                TelemetryCommand.TopCmd.class,
                TelemetryCommand.SystemCmd.class,
                TelemetryCommand.GpuCmd.class,
                TelemetryCommand.MetricsCmd.class,
                TelemetryCommand.OpTimingCmd.class,
                TelemetryCommand.SessionsCmd.class
        },
        mixinStandardHelpOptions = true
)
public class TelemetryCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(name = "top", description = "Show a compact operational telemetry snapshot", mixinStandardHelpOptions = true)
    static class TopCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                if (app.isJsonOutput()) {
                    ObjectNode root = client.getObjectMapper().createObjectNode();
                    putFetch(client, root, "system", "/api/system/resources");
                    putFetch(client, root, "nd4j", "/api/system/nd4j");
                    putFetch(client, root, "gpuJobs", "/api/gpu-lifecycle/jobs");
                    putFetch(client, root, "memoryMetrics", "/api/metrics/memory");
                    OutputFormatter.printJson(root.toString());
                    return 0;
                }

                printSection(client, "System Resources", "/api/system/resources");
                printSection(client, "ND4J", "/api/system/nd4j");
                printSection(client, "GPU Jobs", "/api/gpu-lifecycle/jobs", TelemetryCommand::printGpuJobs);
                printSection(client, "Memory Metrics", "/api/metrics/memory");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "system", description = "Read /api/system telemetry", mixinStandardHelpOptions = true)
    static class SystemCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "resources",
                description = "View: resources, cpu, memory, nd4j, devices, threads, threads-dump, process, memory-watchdog")
        private String view;

        @Override
        public Integer call() {
            String path = systemPath(view);
            if (path == null) {
                System.err.println("Unknown system view: " + view);
                return 1;
            }
            if ("threads-dump".equals(normalize(view))) {
                return printTextEndpoint(app, path);
            }
            return printEndpoint(app, "GET", path, "System " + view, TelemetryCommand::printGeneric);
        }
    }

    @CommandLine.Command(name = "gpu", description = "Inspect or adjust GPU lifecycle state", subcommands = {
            GpuCmd.StatusCmd.class,
            GpuCmd.DevicesCmd.class,
            GpuCmd.JobsCmd.class,
            GpuCmd.BudgetsCmd.class,
            GpuCmd.ReleaseCmd.class,
            GpuCmd.BudgetCmd.class,
            GpuCmd.PriorityCmd.class
    }, mixinStandardHelpOptions = true)
    static class GpuCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @CommandLine.Command(name = "status", description = "Show GPU lifecycle status", mixinStandardHelpOptions = true)
        static class StatusCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @Override public Integer call() {
                return printEndpoint(app, "GET", "/api/gpu-lifecycle/status", "GPU Lifecycle", TelemetryCommand::printGeneric);
            }
        }

        @CommandLine.Command(name = "devices", description = "Show GPU device and reservation state", mixinStandardHelpOptions = true)
        static class DevicesCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @Override public Integer call() {
                return printEndpoint(app, "GET", "/api/gpu-lifecycle/devices", "GPU Devices", TelemetryCommand::printGeneric);
            }
        }

        @CommandLine.Command(name = "jobs", description = "Show active job GPU holds", mixinStandardHelpOptions = true)
        static class JobsCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @Override public Integer call() {
                return printEndpoint(app, "GET", "/api/gpu-lifecycle/jobs", "GPU Jobs", TelemetryCommand::printGpuJobs);
            }
        }

        @CommandLine.Command(name = "budgets", description = "Show GPU memory budgets by service", mixinStandardHelpOptions = true)
        static class BudgetsCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @Override public Integer call() {
                return printEndpoint(app, "GET", "/api/gpu-lifecycle/budgets", "GPU Budgets", TelemetryCommand::printGpuBudgets);
            }
        }

        @CommandLine.Command(name = "release", description = "Force-release a stuck job GPU hold", mixinStandardHelpOptions = true)
        static class ReleaseCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @CommandLine.Parameters(index = "0", description = "Job id to release") private String jobId;
            @Override public Integer call() {
                return printEndpoint(app, "DELETE", "/api/gpu-lifecycle/jobs/" + encode(jobId), "GPU Job Release", TelemetryCommand::printGeneric);
            }
        }

        @CommandLine.Command(name = "budget", description = "Set GPU memory budget for a service", mixinStandardHelpOptions = true)
        static class BudgetCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @CommandLine.Parameters(index = "0", description = "Service type") private String serviceType;
            @CommandLine.Option(names = "--mb", required = true, description = "Budget in MiB") private long budgetMb;
            @Override public Integer call() {
                return printEndpoint(app, "POST", "/api/gpu-lifecycle/budgets/" + encode(serviceType) + "?budgetMb=" + budgetMb,
                        "GPU Budget Update", TelemetryCommand::printGeneric);
            }
        }

        @CommandLine.Command(name = "priority", description = "Set GPU scheduling priority for a service", mixinStandardHelpOptions = true)
        static class PriorityCmd implements Callable<Integer> {
            @CommandLine.Mixin private AppClientMixin app;
            @CommandLine.Parameters(index = "0", description = "Service type") private String serviceType;
            @CommandLine.Option(names = "--priority", required = true, description = "Priority value") private int priority;
            @Override public Integer call() {
                return printEndpoint(app, "POST", "/api/gpu-lifecycle/priorities/" + encode(serviceType) + "?priority=" + priority,
                        "GPU Priority Update", TelemetryCommand::printGeneric);
            }
        }
    }

    @CommandLine.Command(name = "metrics", description = "Read /api/metrics summaries", mixinStandardHelpOptions = true)
    static class MetricsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "summary",
                description = "Domain: summary, embedding, vectorstore, retrieval, llm, ingest, crawl, jobs, chat, graph, guardrails, mcp, memory")
        private String domain;

        @Override
        public Integer call() {
            String path = metricsPath(domain);
            if (path == null) {
                System.err.println("Unknown metrics domain: " + domain);
                return 1;
            }
            return printEndpoint(app, "GET", path, "Metrics " + domain, TelemetryCommand::printGeneric);
        }
    }

    @CommandLine.Command(name = "op-timing", description = "Control and inspect ND4J op-timing telemetry", mixinStandardHelpOptions = true)
    static class OpTimingCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "status",
                description = "Action: status, stats, flush, enable, enable-trace, disable, reset, trace, csv, breakdown, histogram, threads, subprocess-active, subprocess-history")
        private String action;

        @CommandLine.Option(names = "--op", description = "Operation name for breakdown or histogram") private String opName;
        @CommandLine.Option(names = "--top", defaultValue = "20", description = "Top op count for flush") private int topN;
        @CommandLine.Option(names = "--limit", defaultValue = "50", description = "History limit") private int limit;
        @CommandLine.Option(names = "--detailed", description = "Enable detailed timing") private boolean detailed;

        @Override
        public Integer call() {
            String normalized = normalize(action);
            String method = "GET";
            String path;
            switch (normalized) {
                case "status":
                    path = "/api/op-timing/status";
                    break;
                case "stats":
                    path = "/api/op-timing/stats";
                    break;
                case "flush":
                    method = "POST";
                    path = "/api/op-timing/flush?topN=" + topN;
                    break;
                case "enable":
                    method = "POST";
                    path = "/api/op-timing/enable?detailed=" + detailed;
                    break;
                case "enable-trace":
                    method = "POST";
                    path = "/api/op-timing/enable-trace?detailed=" + detailed;
                    break;
                case "disable":
                    method = "POST";
                    path = "/api/op-timing/disable";
                    break;
                case "reset":
                    method = "POST";
                    path = "/api/op-timing/reset";
                    break;
                case "trace":
                    path = "/api/op-timing/export/chrome-trace";
                    break;
                case "csv":
                    path = "/api/op-timing/export/csv";
                    break;
                case "breakdown":
                    if (opName == null || opName.isBlank()) {
                        System.err.println("--op is required for breakdown");
                        return 1;
                    }
                    path = "/api/op-timing/breakdown/" + encode(opName);
                    break;
                case "histogram":
                    if (opName == null || opName.isBlank()) {
                        System.err.println("--op is required for histogram");
                        return 1;
                    }
                    path = "/api/op-timing/histogram/" + encode(opName);
                    break;
                case "threads":
                    path = "/api/op-timing/thread-stats";
                    break;
                case "subprocess-active":
                    path = "/api/op-timing/subprocess/active";
                    break;
                case "subprocess-history":
                    path = "/api/op-timing/subprocess/history?limit=" + limit;
                    break;
                default:
                    System.err.println("Unknown op-timing action: " + action);
                    return 1;
            }
            return printEndpoint(app, method, path, "Op Timing " + normalized, TelemetryCommand::printGeneric);
        }
    }

    @CommandLine.Command(name = "sessions", description = "Read /api/session-metrics", mixinStandardHelpOptions = true)
    static class SessionsCmd implements Callable<Integer> {
        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "stats",
                description = "View: list, stats, by-project, provider-usage, or a session id")
        private String view;

        @CommandLine.Option(names = "--session", description = "Session id to show") private String sessionId;

        @Override
        public Integer call() {
            String normalized = normalize(view);
            String path;
            Formatter formatter = TelemetryCommand::printGeneric;
            switch (normalized) {
                case "list":
                    path = "/api/session-metrics";
                    formatter = TelemetryCommand::printSessionList;
                    break;
                case "stats":
                    path = "/api/session-metrics/stats";
                    break;
                case "by-project":
                    path = "/api/session-metrics/by-project";
                    break;
                case "provider-usage":
                    path = "/api/session-metrics/provider-usage";
                    break;
                case "show":
                    if (sessionId == null || sessionId.isBlank()) {
                        System.err.println("--session is required for sessions show");
                        return 1;
                    }
                    path = "/api/session-metrics/" + encode(sessionId);
                    break;
                default:
                    path = "/api/session-metrics/" + encode(view);
                    break;
            }
            return printEndpoint(app, "GET", path, "Session Metrics " + view, formatter);
        }
    }

    private interface Formatter {
        void print(KompileHttpClient client, JsonNode node) throws Exception;
    }

    private static int printEndpoint(AppClientMixin app, String method, String path, String title, Formatter formatter) {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            String response = request(client, method, path);
            if (response == null || response.isBlank()) {
                response = "{}";
            }
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
            } else {
                JsonNode node = client.getObjectMapper().readTree(response);
                System.out.println(title + ":");
                formatter.print(client, node);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int printTextEndpoint(AppClientMixin app, String path) {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            System.out.println(client.getString(path));
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static String request(KompileHttpClient client, String method, String path) throws Exception {
        if ("POST".equals(method)) {
            return client.postEmpty(path);
        }
        if ("DELETE".equals(method)) {
            return client.delete(path);
        }
        return client.getString(path);
    }

    private static void printSection(KompileHttpClient client, String title, String path) {
        printSection(client, title, path, TelemetryCommand::printGeneric);
    }

    private static void printSection(KompileHttpClient client, String title, String path, Formatter formatter) {
        System.out.println(title + ":");
        try {
            JsonNode node = getNode(client, path);
            formatter.print(client, node);
        } catch (Exception e) {
            System.out.println("  unavailable: " + e.getMessage());
        }
        System.out.println();
    }

    private static JsonNode getNode(KompileHttpClient client, String path) throws Exception {
        return client.getObjectMapper().readTree(client.getString(path));
    }

    private static void putFetch(KompileHttpClient client, ObjectNode root, String name, String path) {
        try {
            root.set(name, getNode(client, path));
        } catch (Exception e) {
            ObjectNode error = client.getObjectMapper().createObjectNode();
            error.put("error", e.getMessage());
            root.set(name, error);
        }
    }

    private static void printGeneric(KompileHttpClient client, JsonNode node) {
        if (node == null || node.isNull()) {
            System.out.println("  (empty)");
            return;
        }
        if (node.isArray()) {
            printArray(node);
            return;
        }
        if (!node.isObject()) {
            System.out.println("  " + nodeToString(node));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        if (!fields.hasNext()) {
            System.out.println("  (empty)");
            return;
        }
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            OutputFormatter.printKv(entry.getKey(), compact(entry.getValue()));
        }
    }

    private static void printArray(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            System.out.println("  (no results)");
            return;
        }
        JsonNode firstObject = null;
        for (JsonNode item : array) {
            if (item.isObject()) {
                firstObject = item;
                break;
            }
        }
        if (firstObject == null) {
            for (JsonNode item : array) {
                System.out.println("  " + nodeToString(item));
            }
            return;
        }
        List<String> columns = new ArrayList<>();
        firstObject.fieldNames().forEachRemaining(name -> {
            if (columns.size() < 6) {
                columns.add(name);
            }
        });
        OutputFormatter.printTable(array, columns.toArray(String[]::new));
    }

    private static void printGpuJobs(KompileHttpClient client, JsonNode node) {
        OutputFormatter.printKv("totalActiveJobs", node.path("totalActiveJobs"));
        JsonNode jobs = node.path("jobs");
        OutputFormatter.printTable(jobs, "jobId", "serviceType", "device", "heldForMs", "description");
    }

    private static void printGpuBudgets(KompileHttpClient client, JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            System.out.println("  (no budgets)");
            return;
        }
        System.out.printf("  %-20s %-12s %-10s %-15s %-18s%n", "SERVICE", "BUDGET_MB", "PRIORITY", "RESERVATION", "RESERVATION_COUNT");
        System.out.printf("  %-20s %-12s %-10s %-15s %-18s%n", "--------------------", "---------", "--------", "-----------", "-----------------");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode info = entry.getValue();
            System.out.printf("  %-20s %-12s %-10s %-15s %-18s%n",
                    entry.getKey(),
                    compact(info.path("budgetMb")),
                    compact(info.path("priority")),
                    compact(info.path("hasReservation")),
                    compact(info.path("reservationCount")));
        }
    }

    private static void printSessionList(KompileHttpClient client, JsonNode node) {
        OutputFormatter.printTable(node, "sessionId", "agent", "provider", "model", "totalTokens", "toolCalls", "updatedAt");
    }

    private static String systemPath(String view) {
        switch (normalize(view)) {
            case "resources": return "/api/system/resources";
            case "cpu": return "/api/system/cpu";
            case "memory": return "/api/system/memory";
            case "nd4j": return "/api/system/nd4j";
            case "devices": return "/api/system/devices";
            case "threads": return "/api/system/threads";
            case "threads-dump": return "/api/system/threads/dump";
            case "process": return "/api/system/process";
            case "memory-watchdog":
            case "watchdog": return "/api/system/memory-watchdog";
            default: return null;
        }
    }

    private static String metricsPath(String domain) {
        switch (normalize(domain)) {
            case "summary": return "/api/metrics/summary";
            case "embedding": return "/api/metrics/embedding";
            case "vectorstore": return "/api/metrics/vectorstore";
            case "retrieval": return "/api/metrics/retrieval";
            case "llm": return "/api/metrics/llm";
            case "ingest": return "/api/metrics/ingest";
            case "crawl": return "/api/metrics/crawl";
            case "jobs": return "/api/metrics/jobs";
            case "chat": return "/api/metrics/chat";
            case "graph": return "/api/metrics/graph";
            case "guardrails": return "/api/metrics/guardrails";
            case "mcp": return "/api/metrics/mcp";
            case "memory": return "/api/metrics/memory";
            default: return null;
        }
    }

    private static String compact(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "-";
        }
        String value = nodeToString(node);
        if (value.length() > 240) {
            return value.substring(0, 237) + "...";
        }
        return value;
    }

    private static String nodeToString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "-";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
