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

package ai.kompile.cli.agent;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command for managing the Drools rules engine, compute graph execution,
 * and workflow definitions (Xircuits/n8n).
 */
@CommandLine.Command(name = "rules", description = "Manage Drools rules, compute graphs, workflow engines, and scripting runtimes.")
public class RulesCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Resource: evaluate, decision-table, graph, workflow, availability, python, javascript")
    private String resource;

    @CommandLine.Parameters(index = "1", defaultValue = "", description = "Operation (varies by resource)")
    private String operation;

    @CommandLine.Option(names = {"--file", "-f"}, description = "JSON file containing the request body")
    private Path file;

    @CommandLine.Option(names = {"--json", "-j"}, description = "Inline JSON request body")
    private String json;

    @CommandLine.Option(names = {"--id"}, description = "Resource ID (execution ID, workflow name)")
    private String id;

    @CommandLine.Option(names = {"--engine"}, defaultValue = "xircuits", description = "Workflow engine type: xircuits, n8n")
    private String engineType;

    @CommandLine.Option(names = {"--name"}, description = "Workflow name")
    private String name;

    @CommandLine.Option(names = {"--node-id"}, description = "Node ID (for graph artifact retrieval)")
    private String nodeId;

    @CommandLine.Option(names = {"--package"}, description = "Python package name (for pip operations)")
    private String packageName;

    @CommandLine.Option(names = {"--version"}, description = "Package version (for pip install)")
    private String packageVersion;

    @CommandLine.Option(names = {"--requirements"}, description = "Path to requirements.txt file")
    private String requirementsPath;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            switch (resource) {
                case "evaluate":
                    return handleRulesEvaluate(client);
                case "decision-table":
                    return handleDecisionTable(client);
                case "graph":
                    return handleComputeGraph(client);
                case "workflow":
                    return handleWorkflow(client);
                case "availability":
                    return handleAvailability(client);
                case "python":
                    return handlePython(client);
                case "javascript":
                case "js":
                    return handleJavaScript(client);
                default:
                    System.err.println("Unknown resource: " + resource);
                    System.err.println("Valid resources: evaluate, decision-table, graph, workflow, availability, python, javascript");
                    return 1;
            }
        } catch (Exception e) {
            System.err.println("Rules operation failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Evaluate DRL rules against facts.
     * Body should contain: { "drl": "...", "facts": {...} }
     */
    private int handleRulesEvaluate(KompileHttpClient client) throws Exception {
        System.out.println(client.postString("/api/compute-graph/execute", readBody()));
        return 0;
    }

    /**
     * Decision table operations: inspect (convert to DRL for review).
     */
    private int handleDecisionTable(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "inspect":
                System.out.println(client.postString("/api/compute-graph/validate", readBody()));
                return 0;
            case "execute":
                System.out.println(client.postString("/api/compute-graph/execute-node", readBody()));
                return 0;
            default:
                System.err.println("Unknown decision-table operation: " + operation);
                System.err.println("Valid operations: inspect, execute");
                return 1;
        }
    }

    /**
     * Compute graph operations: config, status, execute, validate, artifacts.
     */
    private int handleComputeGraph(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "config":
                System.out.println(client.getString("/api/compute-graph/config"));
                return 0;
            case "status":
                System.out.println(client.getString("/api/compute-graph/status"));
                return 0;
            case "execute":
                System.out.println(client.postString("/api/compute-graph/execute", readBody()));
                return 0;
            case "execute-node":
                System.out.println(client.postString("/api/compute-graph/execute-node", readBody()));
                return 0;
            case "validate":
                System.out.println(client.postString("/api/compute-graph/validate", readBody()));
                return 0;
            case "artifacts":
                if (id == null) { System.err.println("--id (execution ID) required"); return 1; }
                String path = "/api/compute-graph/artifacts/" + id;
                if (nodeId != null) {
                    path += "/" + nodeId;
                }
                System.out.println(client.getString(path));
                return 0;
            default:
                System.err.println("Unknown graph operation: " + operation);
                System.err.println("Valid operations: config, status, execute, execute-node, validate, artifacts");
                return 1;
        }
    }

    /**
     * Workflow operations: list, get, save, delete, inspect, execute.
     */
    private int handleWorkflow(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "list":
                System.out.println(client.getString("/api/workflows"));
                return 0;
            case "get":
                if (name == null) { System.err.println("--name required"); return 1; }
                System.out.println(client.getString("/api/workflows/" + engineType + "/" + name));
                return 0;
            case "save":
                if (name == null) { System.err.println("--name required"); return 1; }
                System.out.println(client.postString("/api/workflows/" + engineType + "/" + name, readBody()));
                return 0;
            case "update":
                if (name == null) { System.err.println("--name required"); return 1; }
                System.out.println(client.put("/api/workflows/" + engineType + "/" + name, readBody(), String.class));
                return 0;
            case "delete":
                if (name == null) { System.err.println("--name required"); return 1; }
                System.out.println(client.delete("/api/workflows/" + engineType + "/" + name));
                return 0;
            case "inspect":
                String inspectPath = "/api/workflows/inspect/" + engineType;
                System.out.println(client.postString(inspectPath, readBody()));
                return 0;
            case "execute":
                System.out.println(client.postString("/api/compute-graph/execute", readBody()));
                return 0;
            default:
                System.err.println("Unknown workflow operation: " + operation);
                System.err.println("Valid operations: list, get, save, update, delete, inspect, execute");
                return 1;
        }
    }

    /**
     * Query runtime availability of all compute backends.
     */
    private int handleAvailability(KompileHttpClient client) throws Exception {
        System.out.println(client.getString("/api/compute-graph/availability"));
        return 0;
    }

    /**
     * Python runtime and package management: runtime, check, install, uninstall, install-requirements.
     */
    private int handlePython(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "runtime":
            case "":
                System.out.println(client.getString("/api/scripting/python/runtime"));
                return 0;
            case "check":
                if (packageName == null) { System.err.println("--package required"); return 1; }
                System.out.println(client.getString("/api/scripting/python/packages/" + packageName));
                return 0;
            case "install":
                if (packageName == null && requirementsPath == null) {
                    System.err.println("--package or --requirements required");
                    return 1;
                }
                if (requirementsPath != null) {
                    Map<String, String> reqBody = new LinkedHashMap<>();
                    reqBody.put("path", requirementsPath);
                    System.out.println(client.postString("/api/scripting/python/packages/install-requirements", reqBody));
                } else {
                    Map<String, String> body = new LinkedHashMap<>();
                    body.put("packageName", packageName);
                    if (packageVersion != null) {
                        body.put("version", packageVersion);
                    }
                    System.out.println(client.postString("/api/scripting/python/packages/install", body));
                }
                return 0;
            case "uninstall":
                if (packageName == null) { System.err.println("--package required"); return 1; }
                Map<String, String> uninstallBody = new LinkedHashMap<>();
                uninstallBody.put("packageName", packageName);
                System.out.println(client.postString("/api/scripting/python/packages/uninstall", uninstallBody));
                return 0;
            default:
                System.err.println("Unknown python operation: " + operation);
                System.err.println("Valid operations: runtime, check, install, uninstall");
                return 1;
        }
    }

    /**
     * JavaScript runtime info.
     */
    private int handleJavaScript(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "runtime":
            case "":
                System.out.println(client.getString("/api/scripting/javascript/runtime"));
                return 0;
            default:
                System.err.println("Unknown javascript operation: " + operation);
                System.err.println("Valid operations: runtime");
                return 1;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Object readBody() throws Exception {
        if (file != null) {
            return JsonUtils.standardMapper().readValue(Files.readString(file), Object.class);
        }
        if (json != null && !json.isBlank()) {
            return JsonUtils.standardMapper().readValue(json, Object.class);
        }
        System.err.println("Request body required: provide --file or --json");
        throw new IllegalArgumentException("No request body provided");
    }


}
