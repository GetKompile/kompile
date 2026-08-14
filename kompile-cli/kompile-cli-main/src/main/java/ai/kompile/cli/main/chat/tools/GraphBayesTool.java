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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-action CLI tool wrapping the Bayesian-network inference surface at
 * {@code /api/attribution/bayesian}.
 *
 * <p>Provides probabilistic what-if, most-probable explanation, sensitivity
 * analysis, posterior query, and network statistics — all expressed in plain
 * English output so an agent never needs to know the internal model structure.
 *
 * <p>Pattern follows {@link ProcessMiningCliTool}: single {@link CliTool} with
 * an {@code action} dispatch parameter, own HttpClient, mandatory compactHint.
 */
public class GraphBayesTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GraphBayesTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "graph_bayes"; }

    @Override
    public String description() {
        return "Probabilistic Bayesian-network inference over the knowledge graph. " +
                "Actions: " +
                "'query' (posterior probabilities for a node or all nodes, with optional evidence), " +
                "'mpe' (joint most-probable explanation — the single most-likely state of every variable), " +
                "'sensitivity' (which input variables most influence a conclusion, and by how much), " +
                "'whatif' (posteriors under hypothetical evidence — \"what would change if X were true\"), " +
                "'stats' (network topology: node count, edge count, connected components). " +
                "All actions accept seedNodeIds or a single nodeId. " +
                "evidence/hypotheticalEvidence are maps from node identifier to observed state (0/1). " +
                "Local stdio automatically initializes and uses the current folder's knowledge base.";
    }

    @Override
    public String compactHint() {
        return "Probabilistic what-if & explanation over the graph: action=query|mpe|sensitivity|whatif|stats; " +
                "nodeId from knowledge_graph search; local stdio uses the current folder automatically. " +
                "evidence is {\"nodeId\":0} or {\"nodeId\":1}. " +
                "Minimal call: {action:\"query\", nodeId:\"<id>\"}";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action: query|mpe|sensitivity|whatif|stats");
        addStringProp(props, "node_id",
                "Single KG node ID to seed the Bayesian network (use knowledge_graph to find IDs)");
        addArrayStringProp(props, "seed_node_ids",
                "Multiple KG node IDs to seed the network; alternative to node_id");
        addLongProp(props, "fact_sheet_id",
                "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base.");
        addObjectProp(props, "evidence",
                "Observed evidence: map of {nodeId: 0 or 1} for variables with known state");
        addObjectProp(props, "hypothetical_evidence",
                "Hypothetical evidence for whatif: map of {nodeId: 0 or 1} (what if these were true?)");
        addIntProp(props, "max_depth",
                "BFS depth for network construction (default 3)");
        addIntProp(props, "max_nodes",
                "Max nodes in the constructed network (default 100)");
        addIntProp(props, "top_k",
                "Number of top sensitive variables to show in sensitivity results (default 10)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_bayes"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Bayesian network inference");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }

        try {
            return switch (action) {
                case "query"       -> query(params);
                case "mpe"         -> mpe(params);
                case "sensitivity" -> sensitivity(params);
                case "whatif"      -> whatIf(params);
                case "stats"       -> stats(params);
                default -> ToolResult.error("Unknown action: " + action +
                        ". Valid actions: query, mpe, sensitivity, whatif, stats");
            };
        } catch (ConnectException e) {
            return ToolResult.error("The explicitly configured remote Bayesian graph service became unavailable at "
                    + baseUrl + ". Remove --url to continue with in-process inference.");
        } catch (Exception e) {
            return ToolResult.error("graph_bayes error: " + e.getMessage());
        }
    }

    // ── action implementations ─────────────────────────────────────────────────

    private ToolResult query(JsonNode params) throws Exception {
        String nodeId = params.path("node_id").asText(null);
        long factSheetId = params.path("fact_sheet_id").asLong(0);

        if (nodeId != null && !nodeId.isBlank()) {
            // Simple GET
            StringBuilder url = new StringBuilder("/api/attribution/bayesian/query?nodeId=").append(enc(nodeId));
            url.append("&maxDepth=").append(params.path("max_depth").asInt(3));
            url.append("&maxNodes=").append(params.path("max_nodes").asInt(100));
            if (factSheetId > 0) url.append("&factSheetId=").append(factSheetId);
            JsonNode result = get(url.toString());
            return formatQueryResult(nodeId, result);
        }

        // POST with full request
        ObjectNode body = buildBayesBody(params);
        if (params.has("evidence")) body.set("evidence", params.get("evidence"));
        JsonNode result = post("/api/attribution/bayesian/query", body);
        return formatQueryResult(null, result);
    }

    private ToolResult mpe(JsonNode params) throws Exception {
        ObjectNode body = buildBayesBody(params);
        if (params.has("evidence")) body.set("evidence", params.get("evidence"));
        JsonNode result = post("/api/attribution/bayesian/mpe", body);
        return formatMpeResult(result);
    }

    private ToolResult sensitivity(JsonNode params) throws Exception {
        String nodeId = params.path("node_id").asText(null);
        int maxDepth = params.path("max_depth").asInt(3);
        int maxNodes = params.path("max_nodes").asInt(50);
        int topK = params.path("top_k").asInt(10);

        if (nodeId != null && !nodeId.isBlank()) {
            // Simple GET
            StringBuilder url = new StringBuilder("/api/attribution/bayesian/sensitivity?nodeId=")
                    .append(enc(nodeId))
                    .append("&maxDepth=").append(maxDepth)
                    .append("&maxNodes=").append(maxNodes);
            JsonNode result = get(url.toString());
            return formatSensitivityResult(nodeId, topK, result);
        }

        // POST
        ObjectNode body = buildBayesBody(params);
        body.put("queryNodeId", params.path("node_id").asText(""));
        if (params.has("evidence")) body.set("evidence", params.get("evidence"));
        body.put("epsilon", 0.01);
        JsonNode result = post("/api/attribution/bayesian/sensitivity", body);
        return formatSensitivityResult(null, topK, result);
    }

    private ToolResult whatIf(JsonNode params) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        JsonNode seeds = params.path("seed_node_ids");
        if (seeds.isArray() && !seeds.isEmpty()) {
            body.set("seedNodeIds", seeds);
        } else {
            String nodeId = params.path("node_id").asText(null);
            if (nodeId != null && !nodeId.isBlank()) {
                ArrayNode arr = objectMapper.createArrayNode();
                arr.add(nodeId);
                body.set("seedNodeIds", arr);
            }
        }
        body.put("maxDepth", params.path("max_depth").asInt(3));
        body.put("maxNodes", params.path("max_nodes").asInt(100));
        if (params.has("hypothetical_evidence")) {
            body.set("hypotheticalEvidence", params.get("hypothetical_evidence"));
        } else if (params.has("evidence")) {
            // Allow both param names for usability
            body.set("hypotheticalEvidence", params.get("evidence"));
        }
        JsonNode result = post("/api/attribution/bayesian/whatif", body);
        return formatWhatIfResult(result);
    }

    private ToolResult stats(JsonNode params) throws Exception {
        String nodeId = params.path("node_id").asText(null);
        int maxDepth = params.path("max_depth").asInt(3);
        int maxNodes = params.path("max_nodes").asInt(100);

        StringBuilder url = new StringBuilder("/api/attribution/bayesian/network/stats")
                .append("?maxDepth=").append(maxDepth)
                .append("&maxNodes=").append(maxNodes);
        if (nodeId != null && !nodeId.isBlank()) url.append("&nodeId=").append(enc(nodeId));

        JsonNode result = get(url.toString());
        return formatStatsResult(result);
    }

    // ── formatters ─────────────────────────────────────────────────────────────

    private ToolResult formatQueryResult(String seedId, JsonNode result) {
        StringBuilder sb = new StringBuilder("Bayesian Query");
        if (seedId != null) sb.append(" (seed: ").append(seedId).append(")");
        sb.append("\n\n");

        JsonNode posteriors = result.path("posteriors");
        if (posteriors.isObject() && posteriors.size() > 0) {
            sb.append("Posterior probabilities (").append(posteriors.size()).append(" variables):\n");
            int shown = 0;
            // Show top entries sorted by posterior descending via entry iteration
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            posteriors.fields().forEachRemaining(entries::add);
            entries.sort((a, b) -> Double.compare(b.getValue().asDouble(), a.getValue().asDouble()));
            for (Map.Entry<String, JsonNode> e : entries) {
                if (shown >= 15) { sb.append("  ... (").append(posteriors.size() - shown).append(" more)\n"); break; }
                double post = e.getValue().asDouble();
                String title = result.path("variableToTitle").path(e.getKey()).asText(e.getKey());
                sb.append(String.format("  %.3f  %s\n", post, title));
                shown++;
            }
        } else {
            sb.append("No posteriors returned. Check that the node exists and has graph connections.\n");
        }

        String narrative = result.path("narrativeSummary").asText("");
        if (!narrative.isEmpty()) sb.append("\nSummary: ").append(narrative).append("\n");

        return ToolResult.success("graph_bayes.query", sb.toString(),
                Map.of("variableCount", posteriors.size()));
    }

    private ToolResult formatMpeResult(JsonNode result) {
        StringBuilder sb = new StringBuilder("Most Probable Explanation\n\n");

        JsonNode states = result.path("mpeStates");
        if (states.isObject() && states.size() > 0) {
            sb.append("Most likely state of each variable:\n");
            states.fields().forEachRemaining(e -> {
                String title = result.path("variableToTitle").path(e.getKey()).asText(e.getKey());
                sb.append("  ").append(title).append(": ").append(e.getValue().asInt() == 1 ? "TRUE" : "FALSE").append("\n");
            });
        } else {
            sb.append("No MPE result. Ensure the network has variables with non-trivial priors.\n");
        }

        double score = result.path("jointProbability").asDouble(-1);
        if (score >= 0) {
            sb.append(String.format("\nJoint probability of this assignment: %.6f\n", score));
        }

        String narrative = result.path("narrativeSummary").asText("");
        if (!narrative.isEmpty()) sb.append("\nSummary: ").append(narrative).append("\n");

        return ToolResult.success("graph_bayes.mpe", sb.toString(),
                Map.of("stateCount", states.size()));
    }

    private ToolResult formatSensitivityResult(String queryNode, int topK, JsonNode result) {
        StringBuilder sb = new StringBuilder("Sensitivity Analysis");
        if (queryNode != null) sb.append(" for ").append(queryNode);
        sb.append("\n\nWhich inputs most influence this conclusion (sorted by impact):\n\n");

        JsonNode influences = result.path("influences");
        if (influences.isArray() && !influences.isEmpty()) {
            int shown = 0;
            for (JsonNode item : influences) {
                if (shown >= topK) break;
                String varName = item.path("variableName").asText(item.path("variable").asText("?"));
                double impact = item.path("maxImpact").asDouble(item.path("impact").asDouble());
                sb.append(String.format("  %.4f  %s\n", impact, varName));
                shown++;
            }
        } else {
            // Flat map form: {varName: impact}
            JsonNode flat = result.path("sensitivityMap");
            if (flat.isObject() && flat.size() > 0) {
                List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
                flat.fields().forEachRemaining(entries::add);
                entries.sort((a, b) -> Double.compare(
                        Math.abs(b.getValue().asDouble()), Math.abs(a.getValue().asDouble())));
                int shown = 0;
                for (Map.Entry<String, JsonNode> e : entries) {
                    if (shown >= topK) break;
                    sb.append(String.format("  %.4f  %s\n", e.getValue().asDouble(), e.getKey()));
                    shown++;
                }
            } else {
                sb.append("  No sensitivity data returned.\n");
            }
        }

        return ToolResult.success("graph_bayes.sensitivity", sb.toString(), Map.of());
    }

    private ToolResult formatWhatIfResult(JsonNode result) {
        StringBuilder sb = new StringBuilder("What-If Analysis\n\n");
        sb.append("Posteriors under the hypothetical scenario:\n\n");

        JsonNode posteriors = result.path("posteriors");
        if (posteriors.isObject() && posteriors.size() > 0) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            posteriors.fields().forEachRemaining(entries::add);
            entries.sort((a, b) -> Double.compare(b.getValue().asDouble(), a.getValue().asDouble()));
            int shown = 0;
            for (Map.Entry<String, JsonNode> e : entries) {
                if (shown >= 15) { sb.append("  ... (more)\n"); break; }
                String title = result.path("variableToTitle").path(e.getKey()).asText(e.getKey());
                sb.append(String.format("  %.3f  %s\n", e.getValue().asDouble(), title));
                shown++;
            }
        } else {
            sb.append("  No change in posteriors under this scenario.\n");
        }

        String narrative = result.path("narrativeSummary").asText("");
        if (!narrative.isEmpty()) sb.append("\nSummary: ").append(narrative).append("\n");

        return ToolResult.success("graph_bayes.whatif", sb.toString(),
                Map.of("variableCount", posteriors.size()));
    }

    private ToolResult formatStatsResult(JsonNode result) {
        StringBuilder sb = new StringBuilder("Bayesian Network Statistics\n\n");
        sb.append("Nodes: ").append(result.path("nodeCount").asInt(result.path("nodes").asInt(0))).append("\n");
        sb.append("Edges: ").append(result.path("edgeCount").asInt(result.path("edges").asInt(0))).append("\n");
        sb.append("Variables: ").append(result.path("variableCount").asInt(result.path("variables").asInt(0))).append("\n");

        JsonNode components = result.path("connectedComponents");
        if (!components.isMissingNode()) {
            sb.append("Connected components: ").append(components.asInt(0)).append("\n");
        }
        JsonNode algo = result.path("inferenceAlgorithm");
        if (!algo.isMissingNode()) {
            sb.append("Inference algorithm: ").append(algo.asText()).append("\n");
        }
        result.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (!key.equals("nodeCount") && !key.equals("edgeCount") && !key.equals("variableCount")
                    && !key.equals("connectedComponents") && !key.equals("inferenceAlgorithm")
                    && !key.equals("nodes") && !key.equals("edges") && !key.equals("variables")) {
                sb.append(key).append(": ").append(e.getValue().asText()).append("\n");
            }
        });
        return ToolResult.success("graph_bayes.stats", sb.toString(), Map.of());
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private ObjectNode buildBayesBody(JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        JsonNode seeds = params.path("seed_node_ids");
        if (seeds.isArray() && !seeds.isEmpty()) {
            body.set("seedNodeIds", seeds);
        } else {
            String nodeId = params.path("node_id").asText(null);
            if (nodeId != null && !nodeId.isBlank()) {
                ArrayNode arr = objectMapper.createArrayNode();
                arr.add(nodeId);
                body.set("seedNodeIds", arr);
            } else {
                body.set("seedNodeIds", objectMapper.createArrayNode());
            }
        }
        body.put("maxDepth", params.path("max_depth").asInt(3));
        body.put("maxNodes", params.path("max_nodes").asInt(100));
        String queryNodeId = params.path("node_id").asText(null);
        if (queryNodeId != null && !queryNodeId.isBlank()) {
            body.put("queryNodeId", queryNodeId);
        }
        return body;
    }

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

    private void addIntProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "integer");
        prop.put("description", description);
    }

    private void addArrayStringProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "array");
        prop.putObject("items").put("type", "string");
        prop.put("description", description);
    }

    private void addObjectProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "object");
        prop.put("description", description);
    }
}
