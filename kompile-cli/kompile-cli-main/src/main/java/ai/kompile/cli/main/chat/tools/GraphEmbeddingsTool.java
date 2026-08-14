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

import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
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
 * Multi-action CLI tool wrapping the KG Embedding surface at
 * {@code /api/knowledge-graph/embeddings}.
 *
 * <p>Provides link-prediction vector training, job monitoring, entity/relation
 * prediction and similarity search — expressed in plain English output.
 *
 * <p>Pattern follows {@link ProcessMiningCliTool}: single {@link CliTool} with
 * an {@code action} dispatch parameter, own HttpClient, mandatory compactHint.
 */
public class GraphEmbeddingsTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    public GraphEmbeddingsTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "graph_embeddings"; }

    @Override
    public String description() {
        return "Learn and query link-prediction vectors (KGE) for the knowledge graph. " +
                "Local stdio automatically initializes and uses the current folder's knowledge base; " +
                "fact_sheet_id is only an explicit remote/legacy selector. " +
                "Actions: " +
                "'train' (train embeddings for the selected graph), " +
                "'jobs' (list training jobs), " +
                "'job_status' (get status and progress of a specific job), " +
                "'cancel' (cancel a running training job), " +
                "'score' (plausibility score for a head/relation/tail triple), " +
                "'predict_tails' (likely targets given head + relation), " +
                "'predict_heads' (likely sources given relation + tail), " +
                "'predict_relations' (likely relation types between head and tail), " +
                "'similar' (entities most similar to a given entity by embedding distance), " +
                "'algorithms' (list available embedding algorithms and their descriptions). " +
                "algorithm values: TRANSE | ROTATE. " +
                "Training runs asynchronously; poll job_status after calling train.";
    }

    @Override
    public String compactHint() {
        return "Learn/refresh link-prediction vectors and predict missing links: " +
                "local stdio defaults to the current folder; action=train then job_status; " +
                "predict_tails {head, relation}; similar {entity_name}; score {head, relation, tail}. " +
                "algorithm=TRANSE|ROTATE (default ROTATE).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        addStringProp(props, "action",
                "Action: train|jobs|job_status|cancel|score|predict_tails|predict_heads|predict_relations|similar|algorithms");
        addLongProp(props, "fact_sheet_id",
                "Optional remote/legacy graph selector; omit locally to use the current folder's knowledge base");
        addStringProp(props, "algorithm",
                "Embedding algorithm: TRANSE | ROTATE (default ROTATE)");
        addIntProp(props, "embedding_dim",
                "Embedding dimension (default from config, typically 64)");
        addIntProp(props, "epochs",
                "Training epochs (default from config, typically 100)");
        addNumberProp(props, "learning_rate",
                "Learning rate (default from config, typically 0.01)");
        addStringProp(props, "job_id",
                "Job ID for job_status and cancel actions");
        addStringProp(props, "head",
                "Head entity name for score, predict_tails, predict_relations, similar");
        addStringProp(props, "relation",
                "Relation type for score, predict_tails, predict_heads");
        addStringProp(props, "tail",
                "Tail entity name for score, predict_heads, predict_relations");
        addStringProp(props, "entity_name",
                "Entity name for similar action");
        addIntProp(props, "top_k",
                "Number of predictions to return (default 10)");
        addIntProp(props, "page",
                "Page number for paginated results (default 0)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "graph_embeddings"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "KG embedding operation");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return localBackend.embeddings(params, context);
        }

        try {
            return switch (action) {
                case "train"            -> train(params);
                case "jobs"             -> jobs(params);
                case "job_status"       -> jobStatus(params);
                case "cancel"           -> cancel(params);
                case "score"            -> score(params);
                case "predict_tails"    -> predictTails(params);
                case "predict_heads"    -> predictHeads(params);
                case "predict_relations"-> predictRelations(params);
                case "similar"          -> similar(params);
                case "algorithms"       -> algorithms();
                default -> ToolResult.error("Unknown action: " + action +
                        ". Valid actions: train, jobs, job_status, cancel, score, " +
                        "predict_tails, predict_heads, predict_relations, similar, algorithms");
            };
        } catch (java.net.ConnectException e) {
            return ToolResult.error("The explicitly configured remote graph embedding service became unavailable at "
                    + baseUrl + ". Remove --url to continue with in-process embeddings.");
        } catch (Exception e) {
            return ToolResult.error("graph_embeddings error: " + e.getMessage());
        }
    }

    // ── action implementations ─────────────────────────────────────────────────

    private ToolResult train(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "train");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("factSheetId", factSheetId);
        body.put("algorithm", params.path("algorithm").asText("ROTATE"));
        if (!params.path("embedding_dim").isMissingNode())
            body.put("embeddingDim", params.path("embedding_dim").asInt());
        if (!params.path("epochs").isMissingNode())
            body.put("epochs", params.path("epochs").asInt());
        if (!params.path("learning_rate").isMissingNode())
            body.put("learningRate", params.path("learning_rate").asDouble());

        JsonNode result = post("/api/knowledge-graph/embeddings/train", body);
        String jobId = result.path("jobId").asText("");
        String status = result.path("status").asText("");
        StringBuilder sb = new StringBuilder("Embedding Training Started\n\n");
        sb.append("Job ID: ").append(jobId).append("\n");
        sb.append("Status: ").append(status).append("\n");
        sb.append("Fact sheet: ").append(factSheetId).append("\n");
        sb.append("Algorithm: ").append(result.path("algorithm").asText("")).append("\n");
        sb.append("\nUse action=job_status with job_id=").append(jobId)
          .append(" to monitor progress.\n");
        return ToolResult.success("graph_embeddings.train", sb.toString(),
                Map.of("jobId", jobId, "factSheetId", factSheetId));
    }

    private ToolResult jobs(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "jobs");
        int page = params.path("page").asInt(0);
        StringBuilder url = new StringBuilder("/api/knowledge-graph/embeddings/jobs")
                .append("?factSheetId=").append(factSheetId)
                .append("&page=").append(page).append("&size=20");
        JsonNode result = get(url.toString());
        return formatJobsResult(factSheetId, result);
    }

    private ToolResult jobStatus(JsonNode params) throws Exception {
        String jobId = params.path("job_id").asText("");
        if (jobId.isBlank()) {
            return ToolResult.error("job_id is required for action=job_status");
        }
        JsonNode result = get("/api/knowledge-graph/embeddings/jobs/" + enc(jobId));
        return formatJobDetail(result);
    }

    private ToolResult cancel(JsonNode params) throws Exception {
        String jobId = params.path("job_id").asText("");
        if (jobId.isBlank()) {
            return ToolResult.error("job_id is required for action=cancel");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/knowledge-graph/embeddings/jobs/" + enc(jobId) + "/cancel"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return ToolResult.success("graph_embeddings.cancel",
                    "Training job " + jobId + " cancellation requested.", Map.of("jobId", jobId));
        }
        if (resp.statusCode() == 404) {
            return ToolResult.error("Job not found: " + jobId);
        }
        return ToolResult.error("Cancel failed (HTTP " + resp.statusCode() + ")");
    }

    private ToolResult score(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "score");
        String head = params.path("head").asText("");
        String relation = params.path("relation").asText("");
        String tail = params.path("tail").asText("");
        if (head.isBlank() || relation.isBlank() || tail.isBlank()) {
            return ToolResult.error("head, relation, and tail are required for action=score");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("factSheetId", factSheetId);
        body.put("head", head);
        body.put("relation", relation);
        body.put("tail", tail);
        JsonNode result = post("/api/knowledge-graph/embeddings/score", body);
        double scoreVal = result.path("score").asDouble(result.path("plausibility").asDouble(0));
        StringBuilder sb = new StringBuilder("Triple Plausibility Score\n\n");
        sb.append("  ").append(head).append("  —[").append(relation).append("]—>  ").append(tail).append("\n");
        sb.append(String.format("  Plausibility score: %.4f\n", scoreVal));
        sb.append("\nHigher scores indicate the triple is more consistent with learned embeddings.\n");
        return ToolResult.success("graph_embeddings.score", sb.toString(),
                Map.of("head", head, "relation", relation, "tail", tail, "score", scoreVal));
    }

    private ToolResult predictTails(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "predict_tails");
        String head = params.path("head").asText("");
        String relation = params.path("relation").asText("");
        if (head.isBlank() || relation.isBlank()) {
            return ToolResult.error("head and relation are required for action=predict_tails");
        }
        ObjectNode body = buildPredictBody(factSheetId, head, relation, null, params);
        JsonNode result = post("/api/knowledge-graph/embeddings/predict/tails", body);
        return formatPredictions("Likely targets for " + head + " —[" + relation + "]—> ?",
                "graph_embeddings.predict_tails", result);
    }

    private ToolResult predictHeads(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "predict_heads");
        String relation = params.path("relation").asText("");
        String tail = params.path("tail").asText("");
        if (relation.isBlank() || tail.isBlank()) {
            return ToolResult.error("relation and tail are required for action=predict_heads");
        }
        ObjectNode body = buildPredictBody(factSheetId, null, relation, tail, params);
        JsonNode result = post("/api/knowledge-graph/embeddings/predict/heads", body);
        return formatPredictions("Likely sources for ? —[" + relation + "]—> " + tail,
                "graph_embeddings.predict_heads", result);
    }

    private ToolResult predictRelations(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "predict_relations");
        String head = params.path("head").asText("");
        String tail = params.path("tail").asText("");
        if (head.isBlank() || tail.isBlank()) {
            return ToolResult.error("head and tail are required for action=predict_relations");
        }
        ObjectNode body = buildPredictBody(factSheetId, head, null, tail, params);
        JsonNode result = post("/api/knowledge-graph/embeddings/predict/relations", body);
        return formatPredictions("Likely relations between " + head + " and " + tail,
                "graph_embeddings.predict_relations", result);
    }

    private ToolResult similar(JsonNode params) throws Exception {
        long factSheetId = requireLong(params, "fact_sheet_id", "similar");
        String entityName = params.path("entity_name").asText(params.path("head").asText(""));
        if (entityName.isBlank()) {
            return ToolResult.error("entity_name is required for action=similar");
        }
        int topK = params.path("top_k").asInt(10);
        String url = "/api/knowledge-graph/embeddings/similar/entities/" + factSheetId +
                "/" + enc(entityName) + "?topK=" + topK;
        JsonNode result = get(url);
        StringBuilder sb = new StringBuilder("Entities most similar to \"").append(entityName).append("\":\n\n");
        if (result.isArray() && !result.isEmpty()) {
            for (JsonNode item : result) {
                String name = item.path("entity").asText(item.path("name").asText("?"));
                double sim = item.path("score").asDouble(item.path("similarity").asDouble(0));
                sb.append(String.format("  %.4f  %s\n", sim, name));
            }
        } else {
            sb.append("  No similar entities found. Run action=train first to generate embeddings.\n");
        }
        return ToolResult.success("graph_embeddings.similar", sb.toString(),
                Map.of("entityName", entityName, "factSheetId", factSheetId));
    }

    private ToolResult algorithms() throws Exception {
        JsonNode result = get("/api/knowledge-graph/embeddings/algorithms");
        StringBuilder sb = new StringBuilder("Available KGE Algorithms:\n\n");
        if (result.isArray()) {
            for (JsonNode a : result) {
                sb.append("- **").append(a.path("displayName").asText(a.path("id").asText(""))).append("**");
                sb.append(" (id=").append(a.path("id").asText("")).append(")\n");
                String desc = a.path("description").asText("");
                if (!desc.isEmpty()) sb.append("  ").append(desc).append("\n");
            }
        }
        return ToolResult.success("graph_embeddings.algorithms", sb.toString(), Map.of());
    }

    // ── formatters ─────────────────────────────────────────────────────────────

    private ToolResult formatJobsResult(long factSheetId, JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) content = result; // unwrapped list
        StringBuilder sb = new StringBuilder("Embedding Jobs (factSheetId=").append(factSheetId).append(")\n\n");
        if (!content.isArray() || content.isEmpty()) {
            sb.append("No training jobs found. Run action=train to start embedding training.\n");
            return ToolResult.success("graph_embeddings.jobs", sb.toString(), Map.of("count", 0));
        }
        sb.append("Total: ").append(content.size()).append("\n\n");
        for (JsonNode job : content) {
            sb.append("- Job: ").append(job.path("jobId").asText("")).append("\n");
            sb.append("  Status: ").append(job.path("status").asText("")).append("\n");
            sb.append("  Algorithm: ").append(job.path("algorithm").asText("")).append("\n");
            if (!job.path("currentEpoch").isMissingNode()) {
                sb.append("  Progress: epoch ").append(job.path("currentEpoch").asInt())
                  .append("/").append(job.path("epochs").asInt(0)).append("\n");
            }
            if (!job.path("entitiesEmbedded").isMissingNode()) {
                sb.append("  Entities embedded: ").append(job.path("entitiesEmbedded").asInt()).append("\n");
            }
            String createdAt = job.path("createdAt").asText("");
            if (!createdAt.isEmpty()) sb.append("  Created: ").append(createdAt).append("\n");
        }
        return ToolResult.success("graph_embeddings.jobs", sb.toString(),
                Map.of("count", content.size(), "factSheetId", factSheetId));
    }

    private ToolResult formatJobDetail(JsonNode job) {
        String jobId = job.path("jobId").asText("");
        String status = job.path("status").asText("UNKNOWN");
        StringBuilder sb = new StringBuilder("Embedding Job: ").append(jobId).append("\n\n");
        sb.append("Status:    ").append(status).append("\n");
        sb.append("Algorithm: ").append(job.path("algorithm").asText("")).append("\n");
        sb.append("Fact sheet: ").append(job.path("factSheetId").asLong(0)).append("\n");
        if (!job.path("currentEpoch").isMissingNode()) {
            sb.append("Epoch: ").append(job.path("currentEpoch").asInt())
              .append("/").append(job.path("epochs").asInt(0)).append("\n");
        }
        if (!job.path("currentLoss").isMissingNode()) {
            sb.append(String.format("Loss: %.6f\n", job.path("currentLoss").asDouble()));
        }
        if (!job.path("entitiesEmbedded").isMissingNode()) {
            sb.append("Entities embedded: ").append(job.path("entitiesEmbedded").asInt()).append("\n");
        }
        if (!job.path("totalTriples").isMissingNode()) {
            sb.append("Total triples: ").append(job.path("totalTriples").asInt()).append("\n");
        }
        String error = job.path("errorMessage").asText("");
        if (!error.isEmpty()) sb.append("Error: ").append(error).append("\n");
        return ToolResult.success("graph_embeddings.job_status", sb.toString(),
                Map.of("jobId", jobId, "status", status));
    }

    private ToolResult formatPredictions(String header, String actionKey, JsonNode result) {
        StringBuilder sb = new StringBuilder(header).append("\n\n");
        if (result.isArray() && !result.isEmpty()) {
            for (JsonNode item : result) {
                String entity = item.path("entity").asText(item.path("name").asText("?"));
                double s = item.path("score").asDouble(0);
                sb.append(String.format("  %.4f  %s\n", s, entity));
            }
        } else {
            sb.append("  No predictions returned. Ensure embeddings are trained (action=train).\n");
        }
        return ToolResult.success(actionKey, sb.toString(), Map.of());
    }

    private ObjectNode buildPredictBody(long factSheetId, String head, String relation, String tail, JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("factSheetId", factSheetId);
        if (head != null && !head.isBlank()) body.put("head", head);
        if (relation != null && !relation.isBlank()) body.put("relation", relation);
        if (tail != null && !tail.isBlank()) body.put("tail", tail);
        body.put("topK", params.path("top_k").asInt(10));
        return body;
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
                .timeout(Duration.ofSeconds(300))
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

    private void addIntProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "integer");
        prop.put("description", description);
    }

    private void addNumberProp(ObjectNode props, String name, String description) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "number");
        prop.put("description", description);
    }
}
