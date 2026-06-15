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
 * Reports knowledge base availability: which backends are active,
 * how many documents/entities are indexed.
 * Calls GET /api/knowledge/status on kompile-app.
 */
public class KnowledgeStatusCliTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KnowledgeStatusCliTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "knowledge_status"; }

    @Override
    public String description() {
        return "Check what knowledge backends are available and how much data is indexed. " +
                "Returns backend names, document count, and graph entity count. " +
                "Call this to verify knowledge search will work.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public String permissionKey() { return "knowledge_status"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Check knowledge base status");

        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("knowledge_status requires a running kompile-app. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/knowledge/status"))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ToolResult.error("Knowledge status failed (HTTP " + response.statusCode() + ")");
            }

            JsonNode result = objectMapper.readTree(response.body());
            return formatStatus(result);

        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl +
                    ". Is it running? Start with: kompile run");
        } catch (Exception e) {
            return ToolResult.error("Knowledge status error: " + e.getMessage());
        }
    }

    private ToolResult formatStatus(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge Base Status\n\n");

        boolean available = result.path("available").asBoolean(false);
        sb.append("Available: ").append(available ? "Yes" : "No").append("\n");

        JsonNode backends = result.path("backends");
        if (backends.isArray() && !backends.isEmpty()) {
            sb.append("Backends: ");
            for (int i = 0; i < backends.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(backends.get(i).asText());
            }
            sb.append("\n");
        } else {
            sb.append("Backends: none\n");
        }

        long sources = result.path("sources_count").asLong(-1);
        long docs = result.path("documents_indexed").asLong(-1);
        long entities = result.path("graph_entities").asLong(-1);

        if (sources >= 0) sb.append("Sources: ").append(sources).append("\n");
        if (docs >= 0) sb.append("Documents indexed: ").append(docs).append("\n");
        if (entities >= 0) sb.append("Graph entities: ").append(entities).append("\n");

        return ToolResult.success("knowledge_status", sb.toString(),
                Map.of("available", available));
    }
}
