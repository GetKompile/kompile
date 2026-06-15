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
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command for managing business processes: ontologies, process definitions,
 * workflow runs, HITL approvals, SOX controls, manifests, and Excel computation.
 */
@CommandLine.Command(name = "process", description = "Manage business processes, approvals, controls, and ontologies.")
public class ProcessCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Resource: ontology, definition, run, approval, control, manifest, spel, excel, integrations, step-types")
    private String resource;

    @CommandLine.Parameters(index = "1", defaultValue = "", description = "Operation: list, get, create, approve, start, cancel, resume, complete-step, pending, respond, evaluate, results, convert, execute, assert-version, validate")
    private String operation;

    @CommandLine.Option(names = {"--id"}, description = "Resource ID")
    private String id;

    @CommandLine.Option(names = {"--version", "-v"}, description = "Version number (for ontology/definition get)")
    private Integer version;

    @CommandLine.Option(names = {"--file", "-f"}, description = "JSON file containing the request body")
    private Path file;

    @CommandLine.Option(names = {"--json", "-j"}, description = "Inline JSON request body")
    private String json;

    @CommandLine.Option(names = {"--definition-id"}, description = "Process definition ID (for starting a run)")
    private String definitionId;

    @CommandLine.Option(names = {"--assigned-to"}, description = "Filter pending approvals by assignee")
    private String assignedTo;

    @CommandLine.Option(names = {"--approved-by"}, description = "Identity of approver")
    private String approvedBy;

    @CommandLine.Option(names = {"--step-id"}, description = "Step ID (for complete-step)")
    private String stepId;

    @CommandLine.Option(names = {"--completed-by"}, description = "Identity of step completer")
    private String completedBy;

    @CommandLine.Option(names = {"--run-id"}, description = "Workflow run ID (for control evaluate/results)")
    private String runId;

    @CommandLine.Option(names = {"--expression"}, description = "SpEL expression to evaluate")
    private String expression;

    @CommandLine.Option(names = {"--file-id"}, description = "File ID (for manifest assert-version)")
    private String fileId;

    @CommandLine.Option(names = {"--asserted-by"}, description = "Identity of version asserter")
    private String assertedBy;

    @CommandLine.Option(names = {"--language"}, defaultValue = "javascript", description = "Target language for Excel conversion")
    private String language;

    @CommandLine.Option(names = {"--active-only"}, description = "Show only active runs")
    private boolean activeOnly;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            switch (resource) {
                case "ontology":
                    return handleOntology(client);
                case "definition":
                    return handleDefinition(client);
                case "run":
                    return handleRun(client);
                case "approval":
                    return handleApproval(client);
                case "control":
                    return handleControl(client);
                case "manifest":
                    return handleManifest(client);
                case "spel":
                    return handleSpel(client);
                case "excel":
                    return handleExcel(client);
                case "integrations":
                    System.out.println(client.getString("/api/process/integrations"));
                    return 0;
                case "step-types":
                    System.out.println(client.getString("/api/process/step-types"));
                    return 0;
                default:
                    System.err.println("Unknown resource: " + resource);
                    System.err.println("Valid resources: ontology, definition, run, approval, control, manifest, spel, excel, integrations, step-types");
                    return 1;
            }
        } catch (Exception e) {
            System.err.println("Process operation failed: " + e.getMessage());
            return 1;
        }
    }

    private int handleOntology(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "list":
                System.out.println(client.getString("/api/process/ontology"));
                return 0;
            case "get":
                if (id == null) { System.err.println("--id required"); return 1; }
                String versionParam = version != null ? "?version=" + version : "";
                System.out.println(client.getString("/api/process/ontology/" + id + versionParam));
                return 0;
            case "create":
                System.out.println(client.postString("/api/process/ontology", readBody()));
                return 0;
            case "update":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.putString("/api/process/ontology/" + id, readBody()));
                return 0;
            case "validate":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.postString("/api/process/ontology/" + id + "/validate", readBody()));
                return 0;
            default:
                System.err.println("Unknown ontology operation: " + operation);
                System.err.println("Valid operations: list, get, create, update, validate");
                return 1;
        }
    }

    private int handleDefinition(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "list":
                System.out.println(client.getString("/api/process/definition"));
                return 0;
            case "get":
                if (id == null) { System.err.println("--id required"); return 1; }
                String versionParam = version != null ? "?version=" + version : "";
                System.out.println(client.getString("/api/process/definition/" + id + versionParam));
                return 0;
            case "create":
                System.out.println(client.postString("/api/process/definition", readBody()));
                return 0;
            case "approve":
                if (id == null) { System.err.println("--id required"); return 1; }
                if (approvedBy == null) { System.err.println("--approved-by required"); return 1; }
                System.out.println(client.postString("/api/process/definition/" + id + "/approve",
                        Map.of("approvedBy", approvedBy)));
                return 0;
            default:
                System.err.println("Unknown definition operation: " + operation);
                System.err.println("Valid operations: list, get, create, approve");
                return 1;
        }
    }

    private int handleRun(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "start":
                if (definitionId == null) { System.err.println("--definition-id required"); return 1; }
                Map<String, Object> startBody = new LinkedHashMap<>();
                startBody.put("processDefinitionId", definitionId);
                Object initialData = readBodyOrNull();
                if (initialData != null) {
                    startBody.put("initialData", initialData);
                } else {
                    startBody.put("initialData", Map.of());
                }
                System.out.println(client.postString("/api/process/run", startBody));
                return 0;
            case "get":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.getString("/api/process/run/" + id));
                return 0;
            case "list":
                if (activeOnly) {
                    System.out.println(client.getString("/api/process/run/active"));
                } else {
                    System.out.println(client.getString("/api/process/run/all"));
                }
                return 0;
            case "cancel":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.postEmpty("/api/process/run/" + id + "/cancel"));
                return 0;
            case "resume":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.postString("/api/process/run/" + id + "/resume", readBody()));
                return 0;
            case "complete-step":
                if (id == null) { System.err.println("--id (run ID) required"); return 1; }
                if (stepId == null) { System.err.println("--step-id required"); return 1; }
                if (completedBy == null) { System.err.println("--completed-by required"); return 1; }
                Map<String, Object> completeBody = new LinkedHashMap<>();
                completeBody.put("stepId", stepId);
                completeBody.put("completedBy", completedBy);
                Object outputs = readBodyOrNull();
                completeBody.put("outputs", outputs != null ? outputs : Map.of());
                System.out.println(client.postString("/api/process/run/" + id + "/complete-step", completeBody));
                return 0;
            default:
                System.err.println("Unknown run operation: " + operation);
                System.err.println("Valid operations: start, get, list, cancel, resume, complete-step");
                return 1;
        }
    }

    private int handleApproval(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "pending":
            case "list":
                String query = assignedTo != null ? "?assignedTo=" + assignedTo : "";
                System.out.println(client.getString("/api/process/approval/pending" + query));
                return 0;
            case "get":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.getString("/api/process/approval/" + id));
                return 0;
            case "respond":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.postString("/api/process/approval/" + id + "/respond", readBody()));
                return 0;
            default:
                System.err.println("Unknown approval operation: " + operation);
                System.err.println("Valid operations: pending (or list), get, respond");
                return 1;
        }
    }

    private int handleControl(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "list":
                System.out.println(client.getString("/api/process/control"));
                return 0;
            case "get":
                if (id == null) { System.err.println("--id required"); return 1; }
                System.out.println(client.getString("/api/process/control/" + id));
                return 0;
            case "create":
                System.out.println(client.postString("/api/process/control", readBody()));
                return 0;
            case "evaluate":
                if (id == null) { System.err.println("--id (control ID) required"); return 1; }
                if (runId == null) { System.err.println("--run-id required"); return 1; }
                Map<String, Object> evalBody = new LinkedHashMap<>();
                evalBody.put("runId", runId);
                Object evalData = readBodyOrNull();
                evalBody.put("data", evalData != null ? evalData : Map.of());
                System.out.println(client.postString("/api/process/control/" + id + "/evaluate", evalBody));
                return 0;
            case "results":
                if (runId == null) { System.err.println("--run-id required"); return 1; }
                System.out.println(client.getString("/api/process/control/results/" + runId));
                return 0;
            default:
                System.err.println("Unknown control operation: " + operation);
                System.err.println("Valid operations: list, get, create, evaluate, results");
                return 1;
        }
    }

    private int handleManifest(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "create":
                System.out.println(client.postString("/api/process/manifest", readBody()));
                return 0;
            case "assert-version":
                if (id == null) { System.err.println("--id (manifest ID) required"); return 1; }
                if (fileId == null) { System.err.println("--file-id required"); return 1; }
                if (assertedBy == null) { System.err.println("--asserted-by required"); return 1; }
                System.out.println(client.postString("/api/process/manifest/" + id + "/assert-version",
                        Map.of("fileId", fileId, "assertedBy", assertedBy)));
                return 0;
            default:
                System.err.println("Unknown manifest operation: " + operation);
                System.err.println("Valid operations: create, assert-version");
                return 1;
        }
    }

    private int handleSpel(KompileHttpClient client) throws Exception {
        if (!"evaluate".equals(operation)) {
            System.err.println("Usage: process spel evaluate --expression '<expr>' [--json '{...}']");
            return 1;
        }
        if (expression == null) { System.err.println("--expression required"); return 1; }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expression", expression);
        Object ctx = readBodyOrNull();
        body.put("context", ctx != null ? ctx : Map.of());
        System.out.println(client.postString("/api/process/spel/evaluate", body));
        return 0;
    }

    private int handleExcel(KompileHttpClient client) throws Exception {
        switch (operation) {
            case "convert":
                System.out.println(client.postString("/api/process/excel/convert", readBody()));
                return 0;
            case "execute":
                System.out.println(client.postString("/api/process/excel/execute", readBody()));
                return 0;
            default:
                System.err.println("Unknown excel operation: " + operation);
                System.err.println("Valid operations: convert, execute");
                return 1;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Object readBody() throws Exception {
        if (file != null) {
            return new ObjectMapper().readValue(Files.readString(file), Object.class);
        }
        if (json != null && !json.isBlank()) {
            return new ObjectMapper().readValue(json, Object.class);
        }
        System.err.println("Request body required: provide --file or --json");
        throw new IllegalArgumentException("No request body provided");
    }

    private Object readBodyOrNull() throws Exception {
        if (file != null) {
            return new ObjectMapper().readValue(Files.readString(file), Object.class);
        }
        if (json != null && !json.isBlank()) {
            return new ObjectMapper().readValue(json, Object.class);
        }
        return null;
    }

}
