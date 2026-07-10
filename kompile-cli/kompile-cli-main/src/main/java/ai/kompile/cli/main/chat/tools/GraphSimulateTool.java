/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Multi-action CLI tool wrapping the Graph Simulator surface at
 * {@code /api/graph-sim}.
 *
 * <p>Lets an agent sandbox graph scenarios, step through them, run reasoning
 * at any point, compare results against planted ground truth, and promote the
 * best run into the real knowledge graph — all without touching live data.
 *
 * <p>Pattern follows {@link ProcessMiningCliTool}: single {@link CliTool} with
 * an {@code action} dispatch parameter, own HttpClient, mandatory compactHint.
 */
public class GraphSimulateTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GraphSimulateTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "graph_simulate"; }

    @Override
    public String description() {
        return "Sandbox graph scenarios: create isolated simulation runs, reason over them, " +
                "compare against ground truth, and promote the best results into the real graph. " +
                "Actions: " +
                "'scenarios' (list available simulation scenarios with IDs and descriptions), " +
                "'create_run' (start a new simulation from a scenario; creates a sandbox fact sheet), " +
                "'runs' (list active simulation runs), " +
                "'run' (get details of a specific run: status, step count, fact sheet ID), " +
                "'step' (advance the simulation by one step), " +
                "'play' (run to completion), " +
                "'pause' (pause a running simulation), " +
                "'reason' (trigger reasoning over the current simulation state), " +
                "'ground_truth' (compare current state against the planted ground-truth overlay), " +
                "'promote' (make the sandbox a permanent fact sheet in the real graph), " +
                "'delete' (discard the run and its sandbox fact sheet). " +
                "Sandbox runs never affect live data until promoted.";
    }

    @Override
    public String compactHint() {
        return "Sandbox graph scenarios: list scenarios, then create_run {scenario_id}; " +
                "step/play/reason; compare ground_truth; promote to make it real or delete to discard. " +
                "run_id comes from create_run or runs.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action: scenarios|create_run|runs|run|step|play|pause|reason|ground_truth|promote|delete");
        addStringProp(props, "scenario_id",
                "Scenario identifier (from scenarios action) — required for create_run");
        addStringProp(props, "run_id",
                "Simulation run ID (from create_run or runs) — required for run, step, play, pause, " +
                "reason, ground_truth, promote, delete");
        addStringProp(props, "name",
                "Optional name for the sandbox fact sheet created by create_run");
        addLongProp(props, "seed",
                "Random seed for reproducible scenarios (default 42)");
        addStringProp(props, "mode",
                "Execution mode: ALL (run to completion), STEP (manual stepping), PLAY (auto-play). Default: ALL");
        addNumberProp(props, "confidence_prune_threshold",
                "Low-confidence nodes below this threshold are pruned during simulation (default 0.4)");
        addBoolProp(props, "dry_run",
                "If true, simulate without persisting any data (preview only)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_simulate"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Graph simulation operation");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("graph_simulate requires a running kompile-app. Use --url to connect.");
        }

        try {
            return switch (action) {
                case "scenarios"    -> scenarios();
                case "create_run"   -> createRun(params);
                case "runs"         -> runs();
                case "run"          -> run(params);
                case "step"         -> step(params);
                case "play"         -> play(params);
                case "pause"        -> pause(params);
                case "reason"       -> reason(params);
                case "ground_truth" -> groundTruth(params);
                case "promote"      -> promote(params);
                case "delete"       -> delete(params);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Valid actions: scenarios, create_run, runs, run, step, play, pause, " +
                        "reason, ground_truth, promote, delete");
            };
        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl + ". Is it running?");
        } catch (Exception e) {
            return ToolResult.error("graph_simulate error: " + e.getMessage());
        }
    }

    // ── action implementations ─────────────────────────────────────────────────

    private ToolResult scenarios() throws Exception {
        JsonNode result = get("/api/graph-sim/scenarios");
        StringBuilder sb = new StringBuilder("Available Simulation Scenarios\n\n");
        if (result.isArray() && !result.isEmpty()) {
            for (JsonNode scenario : result) {
                String id = scenario.path("id").asText(scenario.path("scenarioId").asText("?"));
                String name = scenario.path("name").asText(scenario.path("displayName").asText(""));
                String desc = scenario.path("description").asText("");
                sb.append("- **").append(name.isEmpty() ? id : name).append("**");
                sb.append("  id=").append(id).append("\n");
                if (!desc.isEmpty()) sb.append("  ").append(desc).append("\n");
            }
            sb.append("\nUse action=create_run with the scenario id to start a sandbox run.\n");
        } else {
            sb.append("No scenarios found.\n");
        }
        return ToolResult.success("graph_simulate.scenarios", sb.toString(),
                Map.of("count", result.isArray() ? result.size() : 0));
    }

    private ToolResult createRun(JsonNode params) throws Exception {
        String scenarioId = params.path("scenario_id").asText("");
        if (scenarioId.isBlank()) {
            return ToolResult.error("scenario_id is required for action=create_run");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("scenarioId", scenarioId);
        body.put("seed", params.path("seed").asLong(42));
        body.put("mode", params.path("mode").asText("ALL").toUpperCase());
        if (!params.path("confidence_prune_threshold").isMissingNode()) {
            body.put("confidencePruneThreshold", params.path("confidence_prune_threshold").asDouble(0.4));
        }
        if (!params.path("dry_run").isMissingNode()) {
            body.put("dryRun", params.path("dry_run").asBoolean(false));
        }
        String name = params.path("name").asText("");
        if (!name.isEmpty()) body.put("name", name);

        JsonNode result = post("/api/graph-sim/runs", body);
        String runId = result.path("runId").asText("");
        long factSheetId = result.path("factSheetId").asLong(0);
        String status = result.path("status").asText(result.path("state").asText(""));

        StringBuilder sb = new StringBuilder("Simulation Run Created\n\n");
        sb.append("Run ID:      ").append(runId).append("\n");
        sb.append("Fact sheet:  ").append(factSheetId).append("\n");
        sb.append("Scenario:    ").append(scenarioId).append("\n");
        sb.append("Status:      ").append(status).append("\n");
        sb.append("\nThis is a sandbox — it will not affect live data until you use action=promote.\n");
        sb.append("Use action=step or action=reason to advance, action=ground_truth to compare.\n");
        return ToolResult.success("graph_simulate.create_run", sb.toString(),
                Map.of("runId", runId, "factSheetId", factSheetId, "scenarioId", scenarioId));
    }

    private ToolResult runs() throws Exception {
        JsonNode result = get("/api/graph-sim/runs");
        StringBuilder sb = new StringBuilder("Active Simulation Runs\n\n");
        if (result.isArray() && !result.isEmpty()) {
            sb.append("Total: ").append(result.size()).append("\n\n");
            for (JsonNode run : result) {
                String runId = run.path("runId").asText("?");
                String status = run.path("status").asText(run.path("state").asText("?"));
                String scenario = run.path("scenarioId").asText("");
                long fsId = run.path("factSheetId").asLong(0);
                sb.append("- Run: ").append(runId).append("\n");
                sb.append("  Status: ").append(status).append("\n");
                if (!scenario.isEmpty()) sb.append("  Scenario: ").append(scenario).append("\n");
                if (fsId > 0) sb.append("  Fact sheet: ").append(fsId).append("\n");
            }
        } else {
            sb.append("No active simulation runs. Use action=create_run to start one.\n");
        }
        return ToolResult.success("graph_simulate.runs", sb.toString(),
                Map.of("count", result.isArray() ? result.size() : 0));
    }

    private ToolResult run(JsonNode params) throws Exception {
        String runId = requireRunId(params, "run");
        JsonNode result = get("/api/graph-sim/runs/" + runId);
        return formatRunSnapshot("graph_simulate.run", result);
    }

    private ToolResult step(JsonNode params) throws Exception {
        String runId = requireRunId(params, "step");
        JsonNode result = postRunAction(runId, "step");
        return formatRunSnapshot("graph_simulate.step", result);
    }

    private ToolResult play(JsonNode params) throws Exception {
        String runId = requireRunId(params, "play");
        JsonNode result = postRunAction(runId, "play");
        return formatRunSnapshot("graph_simulate.play", result);
    }

    private ToolResult pause(JsonNode params) throws Exception {
        String runId = requireRunId(params, "pause");
        JsonNode result = postRunAction(runId, "pause");
        return formatRunSnapshot("graph_simulate.pause", result);
    }

    private ToolResult reason(JsonNode params) throws Exception {
        String runId = requireRunId(params, "reason");
        JsonNode result = postRunAction(runId, "reason");
        return formatRunSnapshot("graph_simulate.reason", result);
    }

    private ToolResult groundTruth(JsonNode params) throws Exception {
        String runId = requireRunId(params, "ground_truth");
        JsonNode result = get("/api/graph-sim/runs/" + runId + "/ground-truth");
        StringBuilder sb = new StringBuilder("Ground Truth Comparison for run ").append(runId).append("\n\n");

        JsonNode planted = result.path("plantedFacts");
        JsonNode scores = result.path("scores");
        JsonNode precision = result.path("precision");
        JsonNode recall = result.path("recall");

        if (!precision.isMissingNode() || !recall.isMissingNode()) {
            sb.append(String.format("Precision: %.4f\n", precision.asDouble(0)));
            sb.append(String.format("Recall:    %.4f\n", recall.asDouble(0)));
            double p = precision.asDouble(0);
            double r = recall.asDouble(0);
            if (p + r > 0) {
                sb.append(String.format("F1:        %.4f\n", 2 * p * r / (p + r)));
            }
        }

        if (!scores.isMissingNode()) {
            sb.append("\nScores: ").append(scores.toPrettyString()).append("\n");
        }

        if (planted.isArray() && !planted.isEmpty()) {
            sb.append("\nPlanted facts (").append(planted.size()).append("):\n");
            int shown = 0;
            for (JsonNode fact : planted) {
                if (shown >= 10) { sb.append("  ... (more)\n"); break; }
                sb.append("  ").append(fact.asText(fact.toPrettyString())).append("\n");
                shown++;
            }
        }

        return ToolResult.success("graph_simulate.ground_truth", sb.toString(),
                Map.of("runId", runId));
    }

    private ToolResult promote(JsonNode params) throws Exception {
        String runId = requireRunId(params, "promote");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/graph-sim/runs/" + runId + "/promote"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode result = (resp.body() != null && !resp.body().isBlank())
                ? objectMapper.readTree(resp.body()) : objectMapper.createObjectNode();
        long factSheetId = result.path("factSheetId").asLong(0);
        StringBuilder sb = new StringBuilder("Simulation Promoted to Real Graph\n\n");
        sb.append("Run ").append(runId).append(" has been promoted.\n");
        if (factSheetId > 0) {
            sb.append("Permanent fact sheet ID: ").append(factSheetId).append("\n");
            sb.append("The sandbox data is now a real fact sheet and can be reasoned over, queried, and updated.\n");
        }
        return ToolResult.success("graph_simulate.promote", sb.toString(),
                Map.of("runId", runId, "factSheetId", factSheetId));
    }

    private ToolResult delete(JsonNode params) throws Exception {
        String runId = requireRunId(params, "delete");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/graph-sim/runs/" + runId))
                .timeout(Duration.ofSeconds(60))
                .DELETE()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204 || resp.statusCode() == 200) {
            return ToolResult.success("graph_simulate.delete",
                    "Simulation run " + runId + " deleted. Sandbox fact sheet and graph data have been removed.",
                    Map.of("runId", runId));
        }
        if (resp.statusCode() == 404) {
            return ToolResult.error("Run not found: " + runId);
        }
        if (resp.statusCode() == 409) {
            return ToolResult.error("Cannot delete: " + (resp.body() != null ? extractError(resp.body()) : "conflict"));
        }
        throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
    }

    // ── formatters ─────────────────────────────────────────────────────────────

    private ToolResult formatRunSnapshot(String actionKey, JsonNode run) {
        String runId = run.path("runId").asText("?");
        String status = run.path("status").asText(run.path("state").asText("?"));
        StringBuilder sb = new StringBuilder("Simulation Run: ").append(runId).append("\n\n");
        sb.append("Status:       ").append(status).append("\n");
        sb.append("Fact sheet:   ").append(run.path("factSheetId").asLong(0)).append("\n");
        sb.append("Scenario:     ").append(run.path("scenarioId").asText("")).append("\n");
        if (!run.path("stepIndex").isMissingNode()) {
            sb.append("Step:         ").append(run.path("stepIndex").asInt()).append("\n");
        }
        if (!run.path("totalSteps").isMissingNode()) {
            sb.append("Total steps:  ").append(run.path("totalSteps").asInt()).append("\n");
        }
        if (!run.path("nodesInserted").isMissingNode()) {
            sb.append("Nodes so far: ").append(run.path("nodesInserted").asInt()).append("\n");
        }
        String narrative = run.path("narrative").asText(run.path("narrativeSummary").asText(""));
        if (!narrative.isEmpty()) sb.append("\nNarrative: ").append(narrative).append("\n");
        return ToolResult.success(actionKey, sb.toString(), Map.of("runId", runId, "status", status));
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204 || resp.body() == null || resp.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + path + ": " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        String bodyStr = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + path + ": " + resp.body());
        }
        if (resp.body() == null || resp.body().isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(resp.body());
    }

    private JsonNode postRunAction(String runId, String action) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/graph-sim/runs/" + runId + "/" + action))
                .timeout(Duration.ofSeconds(300))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        if (resp.body() == null || resp.body().isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(resp.body());
    }

    private String requireRunId(JsonNode params, String action) throws ToolExecutionException {
        String runId = params.path("run_id").asText("");
        if (runId.isBlank()) {
            throw new ToolExecutionException("run_id is required for action=" + action);
        }
        return runId;
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("error").asText(null);
            if (msg != null) return msg;
            msg = json.path("message").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private void addStringProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
    }

    private void addLongProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "integer");
        prop.put("description", description);
    }

    private void addNumberProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "number");
        prop.put("description", description);
    }

    private void addBoolProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "boolean");
        prop.put("description", description);
    }
}
