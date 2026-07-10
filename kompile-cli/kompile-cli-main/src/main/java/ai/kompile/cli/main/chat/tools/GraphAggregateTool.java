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
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Tool that performs hierarchy-aware numeric aggregation over the kompile knowledge graph.
 * <p>
 * Queries the {@code POST /api/graph/aggregate} endpoint, which expands a requested
 * root type to all its subtypes via the OWL is-a closure ({@code owlInferredTypes}
 * node metadata), then aggregates a numeric attribute across all matching nodes.
 * <p>
 * Example: rootType="Wine", numericAttribute="volume_liters", aggregation="SUM" will
 * sum volumes across RedWine, WhiteWine, and any other subtype nodes.
 */
public class GraphAggregateTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;

    public GraphAggregateTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "graph_aggregate"; }

    @Override
    public String description() {
        return "Aggregate a numeric attribute over all graph nodes whose type (or any ancestor " +
                "in the OWL is-a hierarchy) matches rootType. Supports SUM, COUNT, AVG, MIN, MAX. " +
                "Results include a per-subtype breakdown and contributing node IDs for evidence tracing. " +
                "Example: rootType='Revenue', numericAttribute='amount', aggregation='SUM' " +
                "rolls up all Revenue subtypes (OperatingRevenue, RecurringRevenue, etc.).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode rootType = props.putObject("root_type");
        rootType.put("type", "string");
        rootType.put("description", "The entity type (or supertype) to aggregate over. " +
                "All nodes whose owlInferredTypes chain contains this type are included.");

        ObjectNode numericAttribute = props.putObject("numeric_attribute");
        numericAttribute.put("type", "string");
        numericAttribute.put("description", "The metadata key holding the numeric value to aggregate. " +
                "Required for SUM, AVG, MIN, MAX; ignored for COUNT.");

        ObjectNode aggregation = props.putObject("aggregation");
        aggregation.put("type", "string");
        aggregation.put("description", "Aggregation function: SUM | COUNT | AVG | MIN | MAX. Default: COUNT");

        ObjectNode graphId = props.putObject("graph_id");
        graphId.put("type", "string");
        graphId.put("description", "Specific graph ID to aggregate over (e.g. 'factsheet_42'). " +
                "Omit to search all loaded graphs.");

        ObjectNode groupBySubtype = props.putObject("group_by_subtype");
        groupBySubtype.put("type", "boolean");
        groupBySubtype.put("description", "If true, the response includes a perSubtypeBreakdown map. Default: false");

        schema.putArray("required").add("root_type");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_aggregate"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Aggregate knowledge graph numeric attributes");

        String rootType = params.path("root_type").asText("");
        if (rootType.isEmpty()) {
            return ToolResult.error("root_type is required");
        }

        String numericAttribute = params.path("numeric_attribute").asText(null);
        String aggregationStr = params.path("aggregation").asText("COUNT").toUpperCase();
        String graphId = params.path("graph_id").asText(null);
        boolean groupBySubtype = params.path("group_by_subtype").asBoolean(false);

        if (!backend.isAvailable()) {
            return ToolResult.error("Graph aggregation requires a running kompile-app instance. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("rootType", rootType);
            request.put("aggregation", aggregationStr);
            request.put("groupBySubtype", groupBySubtype);
            if (numericAttribute != null && !numericAttribute.isEmpty()) {
                request.put("numericAttribute", numericAttribute);
            }
            if (graphId != null && !graphId.isEmpty()) {
                request.put("graphId", graphId);
            }

            HttpResponse<String> response = backend.post(
                    "/api/graph/aggregate",
                    objectMapper.writeValueAsString(request),
                    Duration.ofSeconds(30));

            if (response.statusCode() != 200) {
                return ToolResult.error("Graph aggregation failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatResult(rootType, aggregationStr, numericAttribute, result);

        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Graph aggregation timed out after 30s.");
        } catch (Exception e) {
            return ToolResult.error("Graph aggregation error: " + e.getMessage());
        }
    }

    private ToolResult formatResult(String rootType, String aggregation, String attribute, JsonNode result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Graph aggregation: ").append(aggregation).append("(").append(rootType);
        if (attribute != null && !attribute.isEmpty()) {
            sb.append(".").append(attribute);
        }
        sb.append(")\n\n");

        double total = result.path("total").asDouble(0);
        int matched = result.path("matchedNodeCount").asInt(0);
        int skipped = result.path("skippedNodeCount").asInt(0);

        sb.append(String.format("**Result**: %.4g\n", total));
        sb.append(String.format("**Matched nodes**: %d", matched));
        if (skipped > 0) {
            sb.append(String.format(" (%d skipped — attribute missing or non-numeric)", skipped));
        }
        sb.append("\n");

        JsonNode breakdown = result.path("perSubtypeBreakdown");
        if (breakdown.isObject() && breakdown.size() > 0) {
            sb.append("\n**Per-subtype breakdown**:\n");
            breakdown.fields().forEachRemaining(entry ->
                    sb.append(String.format("  - %s: %.4g\n", entry.getKey(), entry.getValue().asDouble())));
        }

        JsonNode contributors = result.path("contributingNodeIds");
        if (contributors.isArray() && !contributors.isEmpty()) {
            sb.append(String.format("\n**Contributing node IDs** (%d total):\n", contributors.size()));
            int shown = Math.min(10, contributors.size());
            for (int i = 0; i < shown; i++) {
                sb.append("  - ").append(contributors.get(i).asText()).append("\n");
            }
            if (contributors.size() > 10) {
                sb.append(String.format("  ... and %d more\n", contributors.size() - 10));
            }
        }

        return ToolResult.success("graph_aggregate: " + aggregation + "(" + rootType + ")", sb.toString(),
                Map.of("rootType", rootType, "aggregation", aggregation,
                        "total", total, "matchedNodeCount", matched));
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
