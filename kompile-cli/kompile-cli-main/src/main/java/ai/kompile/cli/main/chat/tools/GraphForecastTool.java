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
 * MCP tool that projects a numeric attribute over time buckets from the knowledge graph.
 *
 * <p>Calls {@code POST /api/graph/forecast}, which:
 * <ol>
 *   <li>Groups nodes matching {@code root_type} into calendar buckets (quarter/month/year)
 *       using temporal metadata (event_time, date, created_at, …).</li>
 *   <li>Aggregates the chosen numeric attribute within each bucket.</li>
 *   <li>Projects the next N buckets with a least-squares linear trend.</li>
 * </ol>
 *
 * <p><b>Honesty:</b> projected values are clearly labelled as ESTIMATE and are accompanied
 * by an explicit caveat string. Use them as rough guidance, not certainties.
 */
public class GraphForecastTool implements CliTool {

    private final KompileBackendClient backend;
    private final ObjectMapper objectMapper;
    private final boolean remoteConfigured;

    public GraphForecastTool(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = KompileBackendClient.getInstance();
        this.remoteConfigured = baseUrl != null && !baseUrl.isBlank();
        if (remoteConfigured) {
            backend.setBaseUrl(baseUrl);
        }
    }

    @Override
    public String id() { return "graph_forecast"; }

    @Override
    public String description() {
        return "Project a numeric metric forward in time using data already in the knowledge graph. " +
                "Groups matching nodes into calendar time buckets (quarter/month/year) derived from " +
                "their temporal metadata (event_time, date, created_at, etc.), aggregates per bucket, " +
                "then projects the next N buckets with a least-squares linear trend. " +
                "All projected values are explicitly labelled as ESTIMATES with a caveat — " +
                "they are rough guidance, not certainties. " +
                "Returns both the historical series and the projection, plus an optional LLM narrative. " +
                "Example: root_type='Revenue', numeric_attribute='amount', aggregation='SUM', " +
                "bucket_size='QUARTER', horizon_buckets=4.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode rootType = props.putObject("root_type");
        rootType.put("type", "string");
        rootType.put("description", "Entity type (or supertype via OWL is-a closure) to forecast over. " +
                "E.g. 'Revenue', 'SalesOrder', 'Event'.");

        ObjectNode numericAttribute = props.putObject("numeric_attribute");
        numericAttribute.put("type", "string");
        numericAttribute.put("description", "Metadata key holding the numeric value to aggregate per bucket. " +
                "Required for SUM, AVG, MIN, MAX; ignored for COUNT.");

        ObjectNode aggregation = props.putObject("aggregation");
        aggregation.put("type", "string");
        aggregation.put("description", "Aggregation function within each bucket: SUM | COUNT | AVG | MIN | MAX. Default: SUM.");

        ObjectNode bucketSize = props.putObject("bucket_size");
        bucketSize.put("type", "string");
        bucketSize.put("description", "Time granularity: QUARTER | MONTH | YEAR. Default: QUARTER.");

        ObjectNode horizonBuckets = props.putObject("horizon_buckets");
        horizonBuckets.put("type", "integer");
        horizonBuckets.put("description", "Number of future periods to project. Default: 4.");

        ObjectNode graphId = props.putObject("graph_id");
        graphId.put("type", "string");
        graphId.put("description", "Optional remote/legacy graph selector. Omit locally to use the current folder's knowledge base.");

        ObjectNode llmProvider = props.putObject("preferred_llm_provider");
        llmProvider.put("type", "string");
        llmProvider.put("description", "Optional LLM provider ID for a plain-English narrative summary. " +
                "Skipped if unavailable — never fails the call.");

        schema.putArray("required").add("root_type");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_forecast"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Forecast knowledge graph numeric attribute over time");

        String rootType = params.path("root_type").asText("");
        if (rootType.isEmpty()) {
            return ToolResult.error("root_type is required");
        }

        if (!remoteConfigured) {
            return OfflineToolRuntime.execute(id(), params, context, objectMapper);
        }
        if (!backend.isAvailable("/api/graph/forecast")) {
            return ToolResult.error("The explicitly configured remote graph forecast service is unavailable at "
                    + backend.baseUrlFor("/api/graph/forecast")
                    + ". Remove --url to use the in-process graph.");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("rootType", rootType);
            request.put("aggregation", params.path("aggregation").asText("SUM").toUpperCase());
            request.put("bucketSize", params.path("bucket_size").asText("QUARTER").toUpperCase());
            request.put("horizonBuckets", params.path("horizon_buckets").asInt(4));

            String numericAttribute = params.path("numeric_attribute").asText(null);
            if (numericAttribute != null && !numericAttribute.isEmpty()) {
                request.put("numericAttribute", numericAttribute);
            }
            String graphId = params.path("graph_id").asText(null);
            if (graphId != null && !graphId.isEmpty()) {
                request.put("graphId", graphId);
            }
            String llmProvider = params.path("preferred_llm_provider").asText(null);
            if (llmProvider != null && !llmProvider.isEmpty()) {
                request.put("preferredLlmProvider", llmProvider);
            }

            HttpResponse<String> response = backend.post(
                    "/api/graph/forecast",
                    objectMapper.writeValueAsString(request),
                    Duration.ofSeconds(60));

            if (response.statusCode() != 200) {
                return ToolResult.error("Graph forecast failed (HTTP " + response.statusCode() + "): " +
                        extractError(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatResult(rootType, params, result);

        } catch (ConnectException e) {
            return ToolResult.error("The explicitly configured remote graph forecast service became unavailable. "
                    + "Remove --url to continue with the in-process graph. " + e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return ToolResult.error("Graph forecast timed out after 60s.");
        } catch (Exception e) {
            return ToolResult.error("Graph forecast error: " + e.getMessage());
        }
    }

    private ToolResult formatResult(String rootType, JsonNode params, JsonNode result) {
        StringBuilder sb = new StringBuilder();

        String aggregation = params.path("aggregation").asText("SUM").toUpperCase();
        String bucketSize  = params.path("bucket_size").asText("QUARTER").toUpperCase();
        String attr        = params.path("numeric_attribute").asText(null);

        sb.append("## Graph Forecast: ").append(aggregation).append("(").append(rootType);
        if (attr != null && !attr.isEmpty()) sb.append(".").append(attr);
        sb.append(") by ").append(bucketSize).append("\n\n");

        // Honesty guards first
        if (result.path("insufficientHistory").asBoolean(false)) {
            sb.append("**Insufficient history to forecast.**\n");
            sb.append(result.path("caveat").asText("")).append("\n");
        }
        if (result.path("missingTemporalData").asBoolean(false)) {
            sb.append("**Temporal metadata missing** — no time-series could be built.\n");
            double flat = result.path("flatAggregate").asDouble(0);
            sb.append(String.format("Flat aggregate (all matched nodes): **%.4g**\n", flat));
            sb.append(result.path("caveat").asText("")).append("\n");
        }

        // Historical series
        JsonNode history = result.path("historicalSeries");
        if (history.isArray() && !history.isEmpty()) {
            sb.append("### Historical series (").append(history.size()).append(" buckets)\n");
            history.forEach(p ->
                    sb.append(String.format("  %s: %.4g  (%d nodes)\n",
                            p.path("bucket").asText(),
                            p.path("value").asDouble(),
                            p.path("nodeCount").asInt())));
            sb.append("\n");
        }

        // Projected buckets
        JsonNode projected = result.path("projectedBuckets");
        if (projected.isArray() && !projected.isEmpty()) {
            String method = result.path("projectionMethod").asText("LINEAR_TREND");
            sb.append("### Projected buckets [ESTIMATE — ").append(method).append("]\n");
            projected.forEach(p ->
                    sb.append(String.format("  %s: **%.4g** [ESTIMATE]\n",
                            p.path("bucket").asText(),
                            p.path("value").asDouble())));
            sb.append("\n");
        }

        // Caveat (always shown unless already shown above)
        if (!result.path("insufficientHistory").asBoolean(false) &&
                !result.path("missingTemporalData").asBoolean(false)) {
            String caveat = result.path("caveat").asText(null);
            if (caveat != null && !caveat.isBlank()) {
                sb.append("> ").append(caveat).append("\n\n");
            }
        }

        // Optional LLM narrative
        String narrative = result.path("narrative").asText(null);
        if (narrative != null && !narrative.isBlank()) {
            sb.append("### Trend narrative\n").append(narrative).append("\n");
        }

        int totalNodes = result.path("totalMatchedNodes").asInt(0);
        sb.append(String.format("\n_Matched %d nodes total._\n", totalNodes));

        return ToolResult.success(
                "graph_forecast: " + aggregation + "(" + rootType + ") by " + bucketSize,
                sb.toString(),
                Map.of("rootType", rootType,
                        "aggregation", aggregation,
                        "bucketSize", bucketSize,
                        "historicalBucketCount", result.path("historicalBucketCount").asInt(0),
                        "insufficientHistory", result.path("insufficientHistory").asBoolean(false))
        );
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
