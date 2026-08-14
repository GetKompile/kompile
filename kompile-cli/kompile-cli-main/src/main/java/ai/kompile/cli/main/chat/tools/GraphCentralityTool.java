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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for computing live graph centrality metrics against the kompile-app
 * REST API.
 *
 * <p>Supports three algorithms via the same tool:
 * <ul>
 *   <li><b>degree</b> — count of edges per node (fast, O(E))</li>
 *   <li><b>pagerank</b> — iterative convergence, identifies globally influential nodes</li>
 *   <li><b>betweenness</b> — BFS-based, identifies bridge nodes on shortest paths</li>
 * </ul>
 *
 * <p>Delegates to
 * {@code GET /api/graph/algorithms/centrality/{degree|pagerank|betweenness}?graphId=X}.
 */
public class GraphCentralityTool implements CliTool {

    private static final String TOOL_ID = "graph_centrality";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_TOP_K = 20;

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;
    private final boolean remoteConfigured;

    public GraphCentralityTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        this.remoteConfigured = baseUrl != null && !baseUrl.isBlank();
        if (remoteConfigured) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Compute live centrality scores for nodes in a knowledge graph. "
                + "Choose algorithm: 'degree' (edge count per node, fast), "
                + "'pagerank' (iterative convergence, finds globally influential nodes), "
                + "or 'betweenness' (BFS-based, finds bridge nodes between communities). "
                + "Returns the top-K nodes ranked by score. "
                + "Local stdio initializes and uses the current folder's knowledge base; graphId is an explicit remote/legacy override.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode graphId = props.putObject("graphId");
        graphId.put("type", "string");
        graphId.put("description",
                "Optional remote/legacy graph identifier. Omit locally to use the current folder's knowledge base.");

        ObjectNode algorithm = props.putObject("algorithm");
        algorithm.put("type", "string");
        algorithm.put("description",
                "Centrality algorithm: 'degree' (default), 'pagerank', or 'betweenness'.");
        algorithm.putArray("enum").add("degree").add("pagerank").add("betweenness");

        ObjectNode degreeType = props.putObject("degree_type");
        degreeType.put("type", "string");
        degreeType.put("description",
                "For degree centrality: 'total' (default), 'in' (incoming edges only), "
                        + "or 'out' (outgoing edges only).");

        ObjectNode topK = props.putObject("top_k");
        topK.put("type", "integer");
        topK.put("description", "Number of top-ranked nodes to return (default: " + DEFAULT_TOP_K + ").");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "graph_centrality";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Compute graph centrality");

        if (!remoteConfigured) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }
        if (!backend.isAvailable()) {
            return ToolResult.error("The explicitly configured remote centrality service is unavailable at "
                    + backend.baseUrlFor("/api/graph/algorithms/centrality") + ". Remove --url to use the in-process graph.");
        }

        String graphId = params.path("graphId").asText(null);
        String algorithm = params.path("algorithm").asText("degree").toLowerCase();
        String degreeType = params.path("degree_type").asText("total").toLowerCase();
        int topK = params.path("top_k").asInt(DEFAULT_TOP_K);
        if (topK <= 0 || topK > 500) {
            topK = DEFAULT_TOP_K;
        }

        String path = buildPath(algorithm, graphId, degreeType);
        try {
            HttpResponse<String> response = backend.get(path, TIMEOUT);
            if (response.statusCode() != 200) {
                return ToolResult.error("Centrality request failed (HTTP "
                        + response.statusCode() + "): " + response.body());
            }
            JsonNode body = objectMapper.readTree(response.body());
            return formatResult(algorithm, graphId, body, topK);
        } catch (ConnectException e) {
            return ToolResult.error("The explicitly configured remote centrality service became unavailable. "
                    + "Remove --url to continue with the in-process graph. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Centrality request timed out after 60s. "
                    + "The graph may be very large — try scoping with graphId.");
        } catch (Exception e) {
            return ToolResult.error("Centrality error: " + e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String buildPath(String algorithm, String graphId, String degreeType) {
        String algo = switch (algorithm) {
            case "pagerank" -> "pagerank";
            case "betweenness" -> "betweenness";
            default -> "degree";
        };
        StringBuilder sb = new StringBuilder("/api/graph/algorithms/centrality/").append(algo);
        boolean first = true;
        if (graphId != null && !graphId.isBlank()) {
            sb.append("?graphId=").append(URLEncoder.encode(graphId, StandardCharsets.UTF_8));
            first = false;
        }
        if ("degree".equals(algo) && !"total".equals(degreeType)) {
            sb.append(first ? "?" : "&").append("type=").append(degreeType);
        }
        return sb.toString();
    }

    private ToolResult formatResult(String algorithm, String graphId, JsonNode scores, int topK) {
        // Collect and sort entries by score descending
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = scores.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            ranked.add(Map.entry(entry.getKey(), entry.getValue().asDouble(0.0)));
        }
        ranked.sort(Comparator.<Map.Entry<String, Double>, Double>comparing(Map.Entry::getValue).reversed());
        int limit = Math.min(topK, ranked.size());

        StringBuilder sb = new StringBuilder();
        sb.append("## Graph Centrality — ").append(algorithm.toUpperCase()).append('\n');
        if (graphId != null && !graphId.isBlank()) {
            sb.append("**Graph:** ").append(graphId).append('\n');
        }
        sb.append("**Total nodes scored:** ").append(ranked.size()).append('\n');
        sb.append("**Showing top ").append(limit).append(":**\n\n");

        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Double> e = ranked.get(i);
            sb.append(String.format("%3d. `%s` — %.6f%n", i + 1, e.getKey(), e.getValue()));
        }

        if (ranked.isEmpty()) {
            sb.append("_No nodes found in graph._\n");
        }

        return ToolResult.success(sb.toString());
    }
}
