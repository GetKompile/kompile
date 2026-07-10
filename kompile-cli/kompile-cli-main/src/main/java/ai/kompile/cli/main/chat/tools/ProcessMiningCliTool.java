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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Multi-action CLI tool for the process-mining engine (/api/process/mining/*,
 * /api/process/discovery/suggestions*, /api/process-mining-config).
 *
 * <p>Pattern follows {@link KnowledgeGraphTool}: single {@link CliTool} with an
 * {@code action} dispatch parameter, one HTTP client, compact hint.
 */
public class ProcessMiningCliTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ProcessMiningCliTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "process_mining"; }

    @Override
    public String description() {
        return "Process-mining engine: mine, inspect, and configure process models from knowledge graphs. " +
                "Actions: " +
                "'discover' (mine a fact sheet → process suggestion via Inductive Miner), " +
                "'discover_all' (mine every fact sheet), " +
                "'entailment' (PSL precedence entailment: posteriors, rules, temporal verdicts, fused opinion), " +
                "'conformance' (fitness/precision/simplicity of discovered model vs event log), " +
                "'declare' (Declare constraints: Response/Precedence/ChainResponse/NotCoExistence/Init/End), " +
                "'bpmn' (export discovered process as BPMN 2.0 XML with swim lanes), " +
                "'suggestions' (list stored process suggestions with id/name/state/processKey), " +
                "'suggestion' (get full suggestion detail incl. driftReport/conflictReport/changePointReport), " +
                "'config_get' (read the 18 mining tunables), " +
                "'config_update' (update any subset of the 18 mining tunables).";
    }

    @Override
    public String compactHint() {
        return "process_mining: mine/inspect/configure process models. " +
                "Required: action=discover|discover_all|entailment|conformance|declare|bpmn|suggestions|suggestion|config_get|config_update. " +
                "Most actions need fact_sheet_id. 'suggestion' needs suggestion_id. " +
                "'config_update' needs config_json with mining* keys.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action: discover|discover_all|entailment|conformance|declare|bpmn|suggestions|suggestion|config_get|config_update");
        addLongProp(props, "fact_sheet_id",
                "Fact sheet ID (required for discover, entailment, conformance, declare, bpmn)");
        addNumberProp(props, "noise",
                "Inductive Miner noise threshold 0-1, 0=classic (for discover, discover_all, conformance, bpmn)");
        addStringProp(props, "anchor_type",
                "Object-centric case notion, e.g. 'ORDER' (optional override)");
        addNumberProp(props, "min_support",
                "Declare/entailment minimum support threshold (default: 0.1 for declare, 0.2 for entailment)");
        addNumberProp(props, "min_confidence",
                "Declare/entailment minimum confidence threshold (default: 0.9 for declare, 0.66 for entailment)");
        addStringProp(props, "suggestion_id",
                "Process suggestion ID (required for action=suggestion)");
        addBoolProp(props, "include_superseded",
                "Include superseded suggestions in list (default false, for action=suggestions)");
        addStringProp(props, "config_json",
                "JSON object string with mining* keys to update (for config_update)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "process_mining"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Process mining operation");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("process_mining requires a running kompile-app. Use --url to connect.");
        }

        try {
            return switch (action) {
                case "discover"       -> discover(params);
                case "discover_all"   -> discoverAll(params);
                case "entailment"     -> entailment(params);
                case "conformance"    -> conformance(params);
                case "declare"        -> declare(params);
                case "bpmn"           -> bpmn(params);
                case "suggestions"    -> suggestions(params);
                case "suggestion"     -> suggestion(params);
                case "config_get"     -> configGet();
                case "config_update"  -> configUpdate(params);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Valid actions: discover, discover_all, entailment, conformance, " +
                        "declare, bpmn, suggestions, suggestion, config_get, config_update");
            };
        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl + ". Is it running?");
        } catch (Exception e) {
            return ToolResult.error("process_mining error: " + e.getMessage());
        }
    }

    // ── action implementations ───────────────────────────────────────────────────

    private ToolResult discover(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "discover");
        StringBuilder url = new StringBuilder("/api/process/mining/discover?factSheetId=").append(factSheetId);
        double noise = params.path("noise").asDouble(0.0);
        url.append("&noise=").append(noise);
        String anchorType = params.path("anchor_type").asText("");
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Process Mining — Discover (factSheetId=").append(factSheetId).append(")\n\n");
        if (response == null || response.isNull()) {
            sb.append("No process mined. Ensure the fact sheet has a knowledge graph with event data.");
            return ToolResult.success("process_mining.discover", sb.toString(), Map.of());
        }
        sb.append("Suggestion ID: ").append(response.path("id").asText("")).append("\n");
        sb.append("Name: ").append(response.path("name").asText("")).append("\n");
        sb.append("Confidence: ").append(String.format("%.3f", response.path("confidence").asDouble())).append("\n");
        sb.append("Process Key: ").append(response.path("processKey").asText("")).append("\n");
        JsonNode phases = response.path("phases");
        if (phases.isArray()) sb.append("Phases: ").append(phases.size()).append("\n");
        String narrative = response.path("narrative").asText("");
        if (!narrative.isEmpty()) sb.append("\nNarrative:\n").append(narrative).append("\n");
        return ToolResult.success("process_mining.discover", sb.toString(),
                Map.of("suggestionId", response.path("id").asText(""),
                       "factSheetId", factSheetId));
    }

    private ToolResult discoverAll(JsonNode params) throws Exception {
        StringBuilder url = new StringBuilder("/api/process/mining/discover-all");
        double noise = params.path("noise").asDouble(0.0);
        url.append("?noise=").append(noise);
        String anchorType = params.path("anchor_type").asText("");
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        JsonNode response = post(url.toString(), objectMapper.createObjectNode());
        StringBuilder sb = new StringBuilder("Process Mining — Discover All\n\n");
        sb.append("Fact sheets mined: ").append(response.path("count").asInt(0)).append("\n");
        JsonNode bySheet = response.path("suggestionsByFactSheet");
        if (bySheet.isObject()) {
            sb.append("\nSuggestions by fact sheet:\n");
            bySheet.fields().forEachRemaining(e ->
                    sb.append("  factSheetId ").append(e.getKey()).append(" → ").append(e.getValue().asText()).append("\n"));
        }
        return ToolResult.success("process_mining.discover_all", sb.toString(),
                Map.of("count", response.path("count").asInt(0)));
    }

    private ToolResult entailment(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "entailment");
        StringBuilder url = new StringBuilder("/api/process/mining/entailment?factSheetId=").append(factSheetId);
        double minSupport = params.path("min_support").asDouble(0.2);
        double minConfidence = params.path("min_confidence").asDouble(0.66);
        url.append("&minSupport=").append(minSupport).append("&minConfidence=").append(minConfidence);
        String anchorType = params.path("anchor_type").asText("");
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Process Mining — Entailment (factSheetId=").append(factSheetId).append(")\n\n");

        JsonNode precedences = response.path("precedences");
        int total = precedences.isArray() ? precedences.size() : 0;
        sb.append("Total pairs: ").append(total).append("\n");
        sb.append("Transitivity applied: ").append(response.path("transitivityApplied").asBoolean()).append("\n");
        sb.append("Run ID: ").append(response.path("runId").asText("")).append("\n\n");

        if (precedences.isArray() && !precedences.isEmpty()) {
            int shown = 0;
            sb.append("Accepted precedences (posterior ≥ 0.5):\n");
            for (JsonNode p : precedences) {
                if (p.path("posterior").asDouble() < 0.5) continue;
                if (shown >= 20) { sb.append("  ... (more)\n"); break; }
                sb.append("  ").append(p.path("from").asText()).append(" → ").append(p.path("to").asText())
                  .append("  posterior=").append(String.format("%.3f", p.path("posterior").asDouble()))
                  .append(p.path("observed").asBoolean() ? " (observed)" : " (derived)")
                  .append(p.path("temporallyRefuted").asBoolean() ? " [REFUTED]" : "")
                  .append(p.path("concurrent").asBoolean() ? " [CONCURRENT]" : "")
                  .append("\n");
                shown++;
            }
            if (shown == 0) sb.append("  None above threshold.\n");
        }

        JsonNode rules = response.path("ruleTexts");
        if (rules.isArray() && !rules.isEmpty()) {
            sb.append("\nPSL rules (").append(rules.size()).append("):\n");
            int r = 0;
            for (JsonNode rule : rules) {
                if (r >= 10) { sb.append("  ... (more)\n"); break; }
                sb.append("  ").append(rule.asText()).append("\n");
                r++;
            }
        }
        return ToolResult.success("process_mining.entailment", sb.toString(),
                Map.of("factSheetId", factSheetId, "totalPairs", total));
    }

    private ToolResult conformance(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "conformance");
        double noise = params.path("noise").asDouble(0.0);
        String anchorType = params.path("anchor_type").asText("");
        StringBuilder url = new StringBuilder("/api/process/mining/conformance?factSheetId=").append(factSheetId)
                .append("&noise=").append(noise);
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Process Mining — Conformance (factSheetId=").append(factSheetId).append(")\n\n");
        sb.append("Fitness:    ").append(String.format("%.4f", response.path("fitness").asDouble())).append("\n");
        sb.append("Precision:  ").append(String.format("%.4f", response.path("precision").asDouble())).append("\n");
        sb.append("Simplicity: ").append(String.format("%.4f", response.path("simplicity").asDouble())).append("\n");
        // fScore is a computed method, not a JSON field — compute it from the values
        double fitness = response.path("fitness").asDouble();
        double precision = response.path("precision").asDouble();
        double fscore = (fitness + precision > 0) ? (2.0 * fitness * precision) / (fitness + precision) : 0.0;
        sb.append("F-Score:    ").append(String.format("%.4f", fscore)).append("\n");
        sb.append("Perfect Fit: ").append(response.path("perfectFit").asBoolean()).append("\n");
        return ToolResult.success("process_mining.conformance", sb.toString(),
                Map.of("factSheetId", factSheetId, "fitness", fitness, "precision", precision));
    }

    private ToolResult declare(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "declare");
        double minSupport = params.path("min_support").asDouble(0.1);
        double minConfidence = params.path("min_confidence").asDouble(0.9);
        String anchorType = params.path("anchor_type").asText("");
        StringBuilder url = new StringBuilder("/api/process/mining/declare?factSheetId=").append(factSheetId)
                .append("&minSupport=").append(minSupport).append("&minConfidence=").append(minConfidence);
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Process Mining — Declare Constraints (factSheetId=").append(factSheetId).append(")\n\n");
        if (response.isArray()) {
            if (response.isEmpty()) {
                sb.append("No constraints found above thresholds.\n");
            } else {
                sb.append("Constraints (").append(response.size()).append("):\n");
                int shown = 0;
                for (JsonNode c : response) {
                    if (shown >= 25) { sb.append("... (more)\n"); break; }
                    sb.append("- [").append(c.path("template").asText("?")).append("] ")
                      .append(c.path("activityA").asText("")).append(" → ").append(c.path("activityB").asText(""))
                      .append("  support=").append(String.format("%.2f", c.path("support").asDouble()))
                      .append(" conf=").append(String.format("%.2f", c.path("confidence").asDouble()))
                      .append("\n");
                    shown++;
                }
            }
            return ToolResult.success("process_mining.declare", sb.toString(),
                    Map.of("factSheetId", factSheetId, "count", response.size()));
        }
        sb.append(response.toPrettyString()).append("\n");
        return ToolResult.success("process_mining.declare", sb.toString(), Map.of("factSheetId", factSheetId));
    }

    private ToolResult bpmn(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "bpmn");
        double noise = params.path("noise").asDouble(0.0);
        String anchorType = params.path("anchor_type").asText("");
        StringBuilder url = new StringBuilder("/api/process/mining/bpmn?factSheetId=").append(factSheetId)
                .append("&noise=").append(noise);
        if (!anchorType.isEmpty()) url.append("&anchorType=").append(enc(anchorType));

        String xml = getRaw(url.toString());
        if (xml == null || xml.isBlank()) {
            return ToolResult.success("process_mining.bpmn",
                    "No BPMN produced — run 'discover' first to mine the process.",
                    Map.of("factSheetId", factSheetId));
        }
        return ToolResult.success("process_mining.bpmn",
                "BPMN 2.0 XML (factSheetId=" + factSheetId + ", length=" + xml.length() + " chars):\n\n" + xml,
                Map.of("factSheetId", factSheetId, "length", xml.length()));
    }

    private ToolResult suggestions(JsonNode params) throws Exception {
        StringBuilder url = new StringBuilder("/api/process/discovery/suggestions");
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        boolean includeSuperseded = params.path("include_superseded").asBoolean(false);
        String sep = "?";
        if (factSheetId > 0) { url.append(sep).append("factSheetId=").append(factSheetId); sep = "&"; }
        if (includeSuperseded) { url.append(sep).append("includeSuperseded=true"); }

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Process Suggestions");
        if (factSheetId > 0) sb.append(" (factSheetId=").append(factSheetId).append(")");
        sb.append("\n\n");

        if (response.isArray()) {
            if (response.isEmpty()) {
                sb.append("No suggestions found. Run 'discover' or 'discover_all' first.\n");
            } else {
                sb.append("Total: ").append(response.size()).append("\n\n");
                for (JsonNode s : response) {
                    sb.append("- **").append(s.path("name").asText("Unnamed")).append("**")
                      .append(" ID=").append(s.path("id").asText(""))
                      .append(" state=").append(s.path("accepted").asBoolean() ? "ACCEPTED" : "PENDING")
                      .append(" conf=").append(String.format("%.3f", s.path("confidence").asDouble()))
                      .append(" key=").append(s.path("processKey").asText(""))
                      .append("\n");
                    String superseded = s.path("supersededBySuggestionId").asText("");
                    if (!superseded.isEmpty()) sb.append("  supersededBy=").append(superseded).append("\n");
                }
            }
            return ToolResult.success("process_mining.suggestions", sb.toString(),
                    Map.of("count", response.size()));
        }
        sb.append(response.toPrettyString()).append("\n");
        return ToolResult.success("process_mining.suggestions", sb.toString(), Map.of());
    }

    private ToolResult suggestion(JsonNode params) throws Exception {
        String suggestionId = params.path("suggestion_id").asText("");
        if (suggestionId.isBlank()) {
            return ToolResult.error("suggestion_id is required for action=suggestion");
        }
        JsonNode response = get("/api/process/discovery/suggestions/" + enc(suggestionId));
        StringBuilder sb = new StringBuilder("Process Suggestion: ").append(suggestionId).append("\n\n");

        sb.append("Name: ").append(response.path("name").asText("")).append("\n");
        sb.append("State: ").append(response.path("accepted").asBoolean() ? "ACCEPTED" : "PENDING").append("\n");
        sb.append("Confidence: ").append(String.format("%.3f", response.path("confidence").asDouble())).append("\n");
        sb.append("Discovery source: ").append(response.path("discoverySource").asText("")).append("\n");
        sb.append("Process key: ").append(response.path("processKey").asText("")).append("\n");
        String prev = response.path("previousSuggestionId").asText("");
        if (!prev.isEmpty()) sb.append("Previous generation: ").append(prev).append("\n");
        String superseded = response.path("supersededBySuggestionId").asText("");
        if (!superseded.isEmpty()) sb.append("Superseded by: ").append(superseded).append("\n");

        String narrative = response.path("narrative").asText("");
        if (!narrative.isEmpty()) sb.append("\nNarrative:\n").append(narrative).append("\n");

        // Structured evidence grouped by type
        JsonNode evidence = response.path("structuredEvidence");
        if (evidence.isArray() && !evidence.isEmpty()) {
            sb.append("\nStructured evidence (").append(evidence.size()).append(" items):\n");
            for (JsonNode ev : evidence) {
                String type = ev.path("type").asText("");
                sb.append("  [").append(type).append("] ")
                  .append(ev.path("description").asText("")).append("\n");
                if (!ev.path("score").isMissingNode()) {
                    sb.append("    score=").append(String.format("%.3f", ev.path("score").asDouble())).append("\n");
                }
            }
        }

        JsonNode lineage = response.path("lineageRef");
        if (lineage.isObject()) {
            sb.append("\nLineage: derivationMethod=").append(lineage.path("derivationMethod").asText("")).append("\n");
            if (!lineage.path("softTruthValue").isMissingNode()) {
                sb.append("  softTruthValue=").append(String.format("%.3f", lineage.path("softTruthValue").asDouble())).append("\n");
            }
        }

        String traceId = response.path("reasoningTraceId").asText("");
        if (!traceId.isEmpty()) sb.append("\nReasoningTrace ID: ").append(traceId).append("\n");

        return ToolResult.success("process_mining.suggestion", sb.toString(),
                Map.of("suggestionId", suggestionId));
    }

    private ToolResult configGet() throws Exception {
        JsonNode response = get("/api/process-mining-config");
        StringBuilder sb = new StringBuilder("Process Mining Config\n\n");
        response.fields().forEachRemaining(e ->
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
        return ToolResult.success("process_mining.config_get", sb.toString(), Map.of());
    }

    private ToolResult configUpdate(JsonNode params) throws Exception {
        String configJson = params.path("config_json").asText("");
        if (configJson.isBlank()) {
            return ToolResult.error("config_json is required for config_update (JSON object with mining* keys)");
        }
        JsonNode configNode = objectMapper.readTree(configJson);
        JsonNode response = post("/api/process-mining-config", configNode);
        StringBuilder sb = new StringBuilder("Process Mining Config — Updated\n\n");
        response.fields().forEachRemaining(e ->
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
        return ToolResult.success("process_mining.config_update", sb.toString(), Map.of());
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204 || resp.body() == null || resp.body().isBlank()) {
            return objectMapper.nullNode();
        }
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + path + ": " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    private String getRaw(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/xml, text/xml, */*")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204) return null;
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + path + ": " + resp.body());
        }
        return resp.body();
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

    private long requireLong(JsonNode params, String key, String action) throws ToolExecutionException {
        long v = params.path(key).asLong(0);
        if (v <= 0) {
            throw new ToolExecutionException(key + " is required and must be > 0 for action=" + action);
        }
        return v;
    }

    private String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
