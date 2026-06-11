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
 * MCP tool for knowledge graph operations via the kompile-app REST API.
 * Provides full CRUD, graph algorithms, traversal, hierarchy, extraction,
 * named graph management, Cypher queries, and reporting.
 * <p>
 * This complements {@link GraphRagSearchTool} (which does Neo4j vector+Cypher
 * RAG queries) by exposing the full knowledge graph operations surface.
 */
public class KnowledgeGraphTool implements CliTool {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() { return "knowledge_graph"; }

    @Override
    public String description() {
        return "Full knowledge graph operations: CRUD, algorithms, traversal, hierarchy, extraction, and more. " +
                "Actions: " +
                // Read/search
                "'overview' (graph statistics, sources, topics), " +
                "'stats' (node/edge counts and breakdown), " +
                "'search_entity' (find docs mentioning an entity), " +
                "'search_nodes' (text search over graph nodes), " +
                "'find_by_topic' (documents in a topic category), " +
                "'related_docs' (documents related through graph edges), " +
                "'source_context' (source metadata and document list), " +
                "'entities_in_doc' (entities extracted from a document), " +
                // Node/Edge CRUD
                "'list_nodes' (list/filter nodes by type), " +
                "'get_node' (get a single node by ID), " +
                "'add_node' (create a new node), " +
                "'delete_node' (delete a node), " +
                "'list_edges' (list edges for a node), " +
                "'add_edge' (create an edge between nodes), " +
                "'delete_edge' (delete an edge), " +
                // Traversal & algorithms
                "'find_connected' (BFS from a node, up to depth 3), " +
                "'traverse' (BFS traversal grouped by depth), " +
                "'shortest_path' (find shortest path between two nodes), " +
                "'algorithm' (run pagerank/degree/betweenness/wcc/jaccard), " +
                "'communities' (detect communities via Louvain), " +
                // Hierarchy
                "'hierarchy' (tree view of graph hierarchy), " +
                "'ancestors' (ancestor breadcrumb chain for a node), " +
                "'source_chunks' (source text chunks that produced an entity), " +
                // Named graphs
                "'list_graphs' (list named graph containers), " +
                "'create_graph' (create a named graph), " +
                "'delete_graph' (delete a named graph), " +
                // Extraction & building
                "'extract' (multi-agent entity/relationship extraction), " +
                "'build_graph' (build graph from fact sheet index), " +
                // Reporting & Cypher
                "'report' (generate markdown graph report), " +
                "'cypher' (execute a Cypher query against Neo4j), " +
                // Builder & Jobs
                "'list_builders' (list available graph builders), " +
                "'start_job' (start graph extraction job on fact sheet), " +
                "'list_jobs' (list extraction jobs for a fact sheet), " +
                "'job_status' (get extraction job details), " +
                "'cancel_job' (cancel a running extraction job), " +
                "'job_logs' (view extraction logs for a job), " +
                // Proposals
                "'list_proposals' (list triple proposals for review), " +
                "'accept_proposal' (accept a triple proposal), " +
                "'reject_proposal' (reject a triple proposal), " +
                "'manual_proposal' (create a manual triple), " +
                // Config
                "'get_config' (show extraction config), " +
                "'set_config' (update extraction config), " +
                "'toggle_extraction' (toggle extraction on/off), " +
                "'list_providers' (list LLM providers), " +
                "'list_presets' (list schema presets), " +
                "'apply_preset' (apply a schema preset).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // ── Core ──
        addStringProp(props, "action",
                "Action to perform (see tool description for full list)");

        // ── Identifiers ──
        addStringProp(props, "node_id",
                "Node UUID (for get_node, delete_node, find_connected, traverse, hierarchy, ancestors, source_chunks)");
        addStringProp(props, "edge_id",
                "Edge UUID (for delete_edge)");
        addStringProp(props, "graph_id",
                "Named graph UUID (for delete_graph)");

        // ── Search/filter ──
        addStringProp(props, "query",
                "Search query text (for search_nodes, list_graphs)");
        addStringProp(props, "entity_name",
                "Entity name to search for (for search_entity)");
        addStringProp(props, "topic",
                "Topic name (for find_by_topic)");
        addStringProp(props, "node_type",
                "Filter by node type: SOURCE, DOCUMENT, SNIPPET, ENTITY, CUSTOM (for search_nodes, list_nodes)");
        addStringProp(props, "relationship_type",
                "Filter: entity, similarity, hierarchical, any (for related_docs)");

        // ── Document/Source IDs ──
        addStringProp(props, "document_id",
                "Document ID (for related_docs, entities_in_doc)");
        addStringProp(props, "source_id",
                "Source ID (for source_context)");

        // ── Node creation ──
        addStringProp(props, "title",
                "Node/graph title (for add_node, create_graph)");
        addStringProp(props, "external_id",
                "External identifier for new node (for add_node)");
        addStringProp(props, "item_description",
                "Description text for new node/edge/graph (for add_node, add_edge, create_graph)");
        addStringProp(props, "metadata_json",
                "JSON object string of metadata key-value pairs (for add_node)");

        // ── Edge creation ──
        addStringProp(props, "from_node_id",
                "Source node UUID (for add_edge, shortest_path)");
        addStringProp(props, "to_node_id",
                "Target node UUID (for add_edge, shortest_path)");
        addStringProp(props, "edge_type",
                "Edge type: HIERARCHICAL, EMBEDDING_SIMILARITY, SHARED_ENTITY, USER_DEFINED, CITATION, TEMPORAL, CROSS_SOURCE (for add_edge, list_edges)");

        // ── Numeric ──
        addIntProp(props, "max_results", "Maximum results to return (default: 10)");
        addIntProp(props, "limit", "Max items to return for list operations (default: 50)");
        addIntProp(props, "depth", "Traversal/hierarchy depth (default: 2-3)");
        addNumberProp(props, "weight", "Edge weight 0.0-1.0 (for add_edge, default: 1.0)");

        // ── Booleans ──
        addBoolProp(props, "include_children", "Include child documents in source_context (default: false)");
        addBoolProp(props, "weighted", "Use weighted Dijkstra for shortest_path (default: false)");
        addBoolProp(props, "persist", "Persist extraction results to graph (for extract, default: false)");
        addBoolProp(props, "read_only", "Execute Cypher as read-only (for cypher, default: true)");

        // ── Algorithm ──
        addStringProp(props, "algorithm_name",
                "Algorithm: pagerank, degree, betweenness, wcc, jaccard (for algorithm)");

        // ── Fact sheet ──
        addIntProp(props, "fact_sheet_id", "Fact sheet ID to scope operations");

        // ── Extraction ──
        addStringProp(props, "text",
                "Input text for extraction (for extract)");
        addStringProp(props, "agents",
                "Comma-separated agent names for extraction (for extract)");
        addStringProp(props, "merge_strategy",
                "Merge strategy: UNION, INTERSECTION, HIGHEST_CONFIDENCE, FIRST_WINS (for extract)");
        addNumberProp(props, "min_confidence", "Minimum confidence threshold (for extract, default: 0.5)");
        addStringProp(props, "entity_types",
                "Comma-separated entity types to extract (for extract)");

        // ── Cypher ──
        addStringProp(props, "cypher_query",
                "Cypher query string (for cypher)");

        // ── Report ──
        addStringProp(props, "report_type",
                "Report type: summary, communities, entities (for report, default: summary)");

        // ── Named graph creation ──
        addStringProp(props, "graph_name",
                "Name for new named graph (for create_graph)");
        addStringProp(props, "parent_graph_id",
                "Parent graph UUID for nesting (for create_graph)");
        addStringProp(props, "ontology_type",
                "Ontology type: domain_ontology, taxonomy, knowledge_graph (for create_graph)");

        // ── Builder & Jobs ──
        addStringProp(props, "job_id",
                "Extraction job ID (for job_status, cancel_job, job_logs, list_proposals)");
        addStringProp(props, "builder_type",
                "Builder type (for start_job, e.g., llm, pattern, hybrid)");
        addStringProp(props, "model_provider",
                "LLM provider for extraction (for start_job, set_config)");
        addStringProp(props, "model_name",
                "LLM model name for extraction (for start_job, set_config)");
        addNumberProp(props, "temperature",
                "LLM temperature 0.0-2.0 (for start_job, set_config)");
        addBoolProp(props, "auto_accept",
                "Auto-accept proposals above threshold (for start_job, manual_proposal)");
        addNumberProp(props, "auto_accept_threshold",
                "Auto-accept confidence threshold 0.0-1.0 (for start_job, set_config)");
        addStringProp(props, "custom_prompt",
                "Custom extraction prompt (for start_job, set_config)");

        // ── Proposals ──
        addStringProp(props, "proposal_id",
                "Proposal UUID (for accept_proposal, reject_proposal)");
        addStringProp(props, "proposal_status",
                "Filter proposals by status: PENDING, ACCEPTED, REJECTED (for list_proposals)");
        addStringProp(props, "subject_name",
                "Subject entity name (for manual_proposal)");
        addStringProp(props, "subject_type",
                "Subject entity type (for manual_proposal)");
        addStringProp(props, "predicate_name",
                "Relationship predicate (for manual_proposal)");
        addStringProp(props, "object_name",
                "Object entity name (for manual_proposal)");
        addStringProp(props, "object_type",
                "Object entity type (for manual_proposal)");
        addStringProp(props, "rejection_reason",
                "Reason for rejecting a proposal (for reject_proposal)");

        // ── Config ──
        addStringProp(props, "schema_mode",
                "Schema enforcement mode: NONE, LENIENT, STRICT (for set_config)");
        addStringProp(props, "preset_id",
                "Schema preset ID (for apply_preset)");
        addBoolProp(props, "enabled",
                "Enable/disable extraction (for set_config)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "knowledge_graph"; }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        // Mixed read/write operations — no blanket annotation
        return null;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Knowledge graph operation");

        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("Knowledge graph tool requires a running kompile-app instance. " +
                    "Start kompile-app or use --url to connect.");
        }

        try {
            return switch (action) {
                // ── Read/search ──
                case "overview" -> overview();
                case "stats" -> stats();
                case "search_entity" -> searchEntity(params);
                case "search_nodes" -> searchNodes(params);
                case "find_by_topic" -> findByTopic(params);
                case "related_docs" -> relatedDocs(params);
                case "source_context" -> sourceContext(params);
                case "entities_in_doc" -> entitiesInDoc(params);
                case "find_connected" -> findConnected(params);

                // ── Node/Edge CRUD ──
                case "list_nodes" -> listNodes(params);
                case "get_node" -> getNode(params);
                case "add_node" -> addNode(params);
                case "delete_node" -> deleteNode(params);
                case "list_edges" -> listEdges(params);
                case "add_edge" -> addEdge(params);
                case "delete_edge" -> deleteEdge(params);

                // ── Traversal & algorithms ──
                case "traverse" -> traverse(params);
                case "shortest_path" -> shortestPath(params);
                case "algorithm" -> algorithm(params);
                case "communities" -> communities(params);

                // ── Hierarchy ──
                case "hierarchy" -> hierarchy(params);
                case "ancestors" -> ancestors(params);
                case "source_chunks" -> sourceChunks(params);

                // ── Named graphs ──
                case "list_graphs" -> listGraphs(params);
                case "create_graph" -> createGraph(params);
                case "delete_graph" -> deleteGraph(params);

                // ── Extraction & building ──
                case "extract" -> extract(params);
                case "build_graph" -> buildGraph(params);

                // ── Reporting & Cypher ──
                case "report" -> report(params);
                case "cypher" -> cypher(params);

                // ── Builder & Jobs ──
                case "list_builders" -> listBuilders();
                case "start_job" -> startJob(params);
                case "list_jobs" -> listJobs(params);
                case "job_status" -> jobStatus(params);
                case "cancel_job" -> cancelJob(params);
                case "job_logs" -> jobLogs(params);

                // ── Proposals ──
                case "list_proposals" -> listProposals(params);
                case "accept_proposal" -> acceptProposal(params);
                case "reject_proposal" -> rejectProposal(params);
                case "manual_proposal" -> manualProposal(params);

                // ── Config ──
                case "get_config" -> getConfig();
                case "set_config" -> setConfig(params);
                case "toggle_extraction" -> toggleExtraction();
                case "list_providers" -> listProviders();
                case "list_presets" -> listPresets();
                case "apply_preset" -> applyPreset(params);

                default -> ToolResult.error("Unknown action: " + action +
                        ". Use one of: overview, stats, search_entity, search_nodes, find_by_topic, " +
                        "related_docs, source_context, entities_in_doc, find_connected, " +
                        "list_nodes, get_node, add_node, delete_node, " +
                        "list_edges, add_edge, delete_edge, " +
                        "traverse, shortest_path, algorithm, communities, " +
                        "hierarchy, ancestors, source_chunks, " +
                        "list_graphs, create_graph, delete_graph, " +
                        "extract, build_graph, report, cypher, " +
                        "list_builders, start_job, list_jobs, job_status, cancel_job, job_logs, " +
                        "list_proposals, accept_proposal, reject_proposal, manual_proposal, " +
                        "get_config, set_config, toggle_extraction, list_providers, list_presets, apply_preset");
            };
        } catch (java.net.ConnectException e) {
            return ToolResult.error("Cannot connect to kompile-app at " + baseUrl + ". Is it running?");
        } catch (Exception e) {
            return ToolResult.error("Knowledge graph error: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Read / Search actions (original)
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult overview() throws Exception {
        JsonNode response = get("/api/knowledge-graph/overview");
        StringBuilder sb = new StringBuilder("Knowledge Graph Overview\n\n");

        JsonNode stats = response.path("statistics");
        if (stats.isObject()) {
            sb.append("### Statistics\n");
            stats.fields().forEachRemaining(e ->
                    sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
            sb.append("\n");
        }

        JsonNode sources = response.path("sources");
        if (sources.isArray() && !sources.isEmpty()) {
            sb.append("### Sources (").append(response.path("sourceCount").asInt(0)).append(")\n");
            for (JsonNode src : sources) {
                sb.append("- **").append(src.path("title").asText("Untitled")).append("** (")
                        .append(src.path("type").asText("")).append(") - ")
                        .append(src.path("documentCount").asInt(0)).append(" docs\n");
            }
            sb.append("\n");
        }

        JsonNode topics = response.path("topics");
        if (topics.isArray() && !topics.isEmpty()) {
            sb.append("### Topics (").append(response.path("topicCount").asInt(0)).append(")\n");
            for (JsonNode t : topics) sb.append("- ").append(t.asText()).append("\n");
        }

        return ToolResult.success("knowledge_graph_overview", sb.toString(), Map.of());
    }

    private ToolResult stats() throws Exception {
        JsonNode response = get("/api/knowledge-graph/statistics");
        StringBuilder sb = new StringBuilder("Knowledge Graph Statistics:\n\n");
        response.fields().forEachRemaining(e ->
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
        return ToolResult.success("graph_stats", sb.toString(), Map.of());
    }

    private ToolResult searchEntity(JsonNode params) throws Exception {
        String entityName = requireString(params, "entity_name", "search_entity");
        int maxResults = params.path("max_results").asInt(10);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("entityName", entityName);
        body.put("maxResults", maxResults);

        JsonNode response = post("/api/knowledge-graph/search-by-entity", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Documents mentioning \"").append(entityName).append("\":\n\n");
        formatDocResults(sb, response.path("results"));
        int count = response.path("resultCount").asInt(0);
        return ToolResult.success("search_entity: " + entityName, sb.toString(),
                Map.of("entity", entityName, "resultCount", count));
    }

    private ToolResult searchNodes(JsonNode params) throws Exception {
        String query = requireString(params, "query", "search_nodes");
        int maxResults = params.path("max_results").asInt(10);
        String nodeType = params.path("node_type").asText("");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("maxResults", maxResults);
        if (!nodeType.isEmpty()) body.put("nodeType", nodeType);

        JsonNode response = post("/api/knowledge-graph/search-nodes", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Node search: \"").append(query).append("\"");
        if (!nodeType.isEmpty()) sb.append(" (type: ").append(nodeType).append(")");
        sb.append("\n\n");
        formatNodeList(sb, response.path("results"));
        int count = response.path("resultCount").asInt(0);
        return ToolResult.success("search_nodes: " + query, sb.toString(),
                Map.of("query", query, "resultCount", count));
    }

    private ToolResult findByTopic(JsonNode params) throws Exception {
        String topic = requireString(params, "topic", "find_by_topic");
        int maxResults = params.path("max_results").asInt(10);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("topic", topic);
        body.put("maxResults", maxResults);

        JsonNode response = post("/api/knowledge-graph/find-by-topic", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Documents for topic \"").append(topic).append("\":\n\n");
        String message = response.path("message").asText("");
        if (!message.isEmpty()) sb.append(message).append("\n");
        formatDocResults(sb, response.path("results"));
        int count = response.path("resultCount").asInt(0);
        return ToolResult.success("find_by_topic: " + topic, sb.toString(),
                Map.of("topic", topic, "resultCount", count));
    }

    private ToolResult relatedDocs(JsonNode params) throws Exception {
        String documentId = requireString(params, "document_id", "related_docs");
        int maxResults = params.path("max_results").asInt(10);
        String relType = params.path("relationship_type").asText("any");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("documentId", documentId);
        body.put("maxResults", maxResults);
        body.put("relationshipType", relType);

        JsonNode response = post("/api/knowledge-graph/related-documents", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Documents related to ").append(documentId).append(":\n\n");
        formatDocResults(sb, response.path("results"));
        int count = response.path("resultCount").asInt(0);
        return ToolResult.success("related_docs: " + documentId, sb.toString(),
                Map.of("documentId", documentId, "resultCount", count));
    }

    private ToolResult sourceContext(JsonNode params) throws Exception {
        String sourceId = requireString(params, "source_id", "source_context");
        boolean includeChildren = params.path("include_children").asBoolean(false);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceId", sourceId);
        body.put("includeChildren", includeChildren);

        JsonNode response = post("/api/knowledge-graph/source-context", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Source: ").append(response.path("title").asText("Untitled")).append("\n");
        sb.append("- ID: ").append(response.path("sourceId").asText("")).append("\n");
        sb.append("- Type: ").append(response.path("type").asText("unknown")).append("\n");
        sb.append("- Documents: ").append(response.path("documentCount").asInt(0)).append("\n");
        sb.append("- Weight: ").append(response.path("weight").asDouble(1.0)).append("\n");
        appendIfPresent(sb, "Description", response.path("description"));

        JsonNode docs = response.path("documents");
        if (docs.isArray() && !docs.isEmpty()) {
            sb.append("\nDocuments:\n");
            for (JsonNode doc : docs) {
                sb.append("- ").append(doc.path("title").asText("Untitled"))
                        .append(" (").append(doc.path("id").asText("")).append(")\n");
            }
        }

        return ToolResult.success("source_context: " + sourceId, sb.toString(),
                Map.of("sourceId", sourceId));
    }

    private ToolResult entitiesInDoc(JsonNode params) throws Exception {
        String documentId = requireString(params, "document_id", "entities_in_doc");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("documentId", documentId);

        JsonNode response = post("/api/knowledge-graph/document-entities", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Entities in document ").append(documentId).append(":\n\n");

        JsonNode entities = response.path("entities");
        if (entities.isArray()) {
            for (JsonNode entity : entities) {
                sb.append("- **").append(entity.path("entity").asText("")).append("** (")
                        .append(entity.path("type").asText("")).append(") - ")
                        .append(entity.path("mentions").asInt(0)).append(" mentions, ")
                        .append(String.format("%.0f%% confidence", entity.path("confidence").asDouble(0) * 100))
                        .append("\n");
            }
        }

        int count = response.path("entityCount").asInt(0);
        return ToolResult.success("entities_in_doc: " + documentId, sb.toString(),
                Map.of("documentId", documentId, "entityCount", count));
    }

    private ToolResult findConnected(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "find_connected");
        int depth = params.path("depth").asInt(2);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("nodeId", nodeId);
        body.put("depth", depth);

        JsonNode response = post("/api/knowledge-graph/find-connected", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Connected nodes from ").append(nodeId).append(" (depth ").append(depth).append("):\n\n");
        formatNodeList(sb, response.path("nodes"));
        int count = response.path("nodeCount").asInt(0);
        return ToolResult.success("find_connected: " + nodeId, sb.toString(),
                Map.of("nodeId", nodeId, "depth", depth, "nodeCount", count));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Node CRUD
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult listNodes(JsonNode params) throws Exception {
        int limit = params.path("limit").asInt(50);
        String type = params.path("node_type").asText("");

        StringBuilder url = new StringBuilder("/api/knowledge-graph/nodes?limit=").append(limit);
        if (!type.isEmpty()) url.append("&type=").append(enc(type));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Nodes");
        if (!type.isEmpty()) sb.append(" (type: ").append(type).append(")");
        sb.append(":\n\n");

        if (response.isArray()) {
            for (JsonNode node : response) {
                sb.append("- **").append(node.path("title").asText("Untitled")).append("** [")
                        .append(node.path("nodeType").asText("")).append("] ID=")
                        .append(node.path("nodeId").asText("")).append("\n");
            }
            return ToolResult.success("list_nodes", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_nodes", sb.append(response.toPrettyString()).toString(), Map.of());
    }

    private ToolResult getNode(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "get_node");
        JsonNode node = get("/api/knowledge-graph/nodes/" + enc(nodeId));

        StringBuilder sb = new StringBuilder("Node Details:\n");
        sb.append("- ID: ").append(node.path("nodeId").asText("")).append("\n");
        sb.append("- Type: ").append(node.path("nodeType").asText("")).append("\n");
        sb.append("- External ID: ").append(node.path("externalId").asText("")).append("\n");
        sb.append("- Title: ").append(node.path("title").asText("")).append("\n");
        appendIfPresent(sb, "Description", node.path("description"));
        appendIfPresent(sb, "Confidence", node.path("confidence"));
        appendIfPresent(sb, "Source Type", node.path("sourceType"));
        appendIfPresent(sb, "Path/URL", node.path("pathOrUrl"));
        appendIfPresent(sb, "Content Preview", node.path("contentPreview"));
        appendIfPresent(sb, "Created", node.path("createdAt"));
        appendIfPresent(sb, "Updated", node.path("updatedAt"));

        JsonNode meta = node.path("metadata");
        if (meta.isObject() && !meta.isEmpty()) {
            sb.append("- Metadata: ").append(meta.toPrettyString()).append("\n");
        }

        return ToolResult.success("get_node: " + nodeId, sb.toString(),
                Map.of("nodeId", nodeId, "nodeType", node.path("nodeType").asText("")));
    }

    private ToolResult addNode(JsonNode params) throws Exception {
        String title = requireString(params, "title", "add_node");
        String externalId = requireString(params, "external_id", "add_node");
        String type = params.path("node_type").asText("ENTITY");
        String desc = params.path("item_description").asText("");
        String metaJson = params.path("metadata_json").asText("");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("nodeType", type);
        body.put("externalId", externalId);
        body.put("title", title);
        if (!desc.isEmpty()) body.put("description", desc);
        if (!metaJson.isEmpty()) {
            body.set("metadata", objectMapper.readTree(metaJson));
        }
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post("/api/knowledge-graph/nodes", body);
        String nodeId = response.path("nodeId").asText("");
        return ToolResult.success("add_node: " + title,
                "Created node: " + title + " [" + type + "] ID=" + nodeId,
                Map.of("nodeId", nodeId, "title", title, "nodeType", type));
    }

    private ToolResult deleteNode(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "delete_node");
        delete("/api/knowledge-graph/nodes/" + enc(nodeId));
        return ToolResult.success("delete_node: " + nodeId,
                "Deleted node: " + nodeId, Map.of("nodeId", nodeId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Edge CRUD
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult listEdges(JsonNode params) throws Exception {
        int limit = params.path("limit").asInt(100);
        String nodeId = params.path("node_id").asText("");
        String edgeType = params.path("edge_type").asText("");

        StringBuilder url = new StringBuilder("/api/knowledge-graph/edges?limit=").append(limit);
        if (!nodeId.isEmpty()) url.append("&nodeId=").append(enc(nodeId));
        if (!edgeType.isEmpty()) url.append("&type=").append(enc(edgeType));

        JsonNode response = get(url.toString());
        StringBuilder sb = new StringBuilder("Edges");
        if (!nodeId.isEmpty()) sb.append(" for node ").append(nodeId);
        if (!edgeType.isEmpty()) sb.append(" (type: ").append(edgeType).append(")");
        sb.append(":\n\n");

        if (response.isArray()) {
            for (JsonNode edge : response) {
                sb.append("- [").append(edge.path("edgeType").asText("")).append("] ")
                        .append(edge.path("sourceNodeId").asText("")).append(" -> ")
                        .append(edge.path("targetNodeId").asText(""))
                        .append(" (weight: ").append(String.format("%.2f", edge.path("weight").asDouble(1.0)))
                        .append(") ID=").append(edge.path("edgeId").asText("")).append("\n");
                appendIfPresent(sb, "  Description", edge.path("description"));
            }
            return ToolResult.success("list_edges", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_edges", sb.append(response.toPrettyString()).toString(), Map.of());
    }

    private ToolResult addEdge(JsonNode params) throws Exception {
        String from = requireString(params, "from_node_id", "add_edge");
        String to = requireString(params, "to_node_id", "add_edge");
        String edgeType = params.path("edge_type").asText("USER_DEFINED");
        double weight = params.path("weight").asDouble(1.0);
        String desc = params.path("item_description").asText("");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceNodeId", from);
        body.put("targetNodeId", to);
        body.put("edgeType", edgeType);
        body.put("weight", weight);
        if (!desc.isEmpty()) body.put("description", desc);

        JsonNode response = post("/api/knowledge-graph/edges", body);
        String edgeId = response.path("edgeId").asText("");
        return ToolResult.success("add_edge",
                "Created edge: " + from + " -[" + edgeType + "]-> " + to + " ID=" + edgeId,
                Map.of("edgeId", edgeId, "edgeType", edgeType));
    }

    private ToolResult deleteEdge(JsonNode params) throws Exception {
        String edgeId = requireString(params, "edge_id", "delete_edge");
        delete("/api/knowledge-graph/edges/" + enc(edgeId));
        return ToolResult.success("delete_edge: " + edgeId,
                "Deleted edge: " + edgeId, Map.of("edgeId", edgeId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Traversal & Algorithms
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult traverse(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "traverse");
        int depth = params.path("depth").asInt(3);
        long factSheetId = params.path("fact_sheet_id").asLong(0);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("startNodeId", nodeId);
        body.put("maxDepth", depth);
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post("/api/graph/algorithms/traverse/bfs", body);
        StringBuilder sb = new StringBuilder();
        sb.append("BFS traversal from ").append(nodeId).append(" (max depth ").append(depth).append("):\n\n");

        int totalNodes = 0;
        var it = response.fields();
        while (it.hasNext()) {
            var entry = it.next();
            int count = entry.getValue().isArray() ? entry.getValue().size() : 0;
            totalNodes += count;
            sb.append("Level ").append(entry.getKey()).append(": ").append(count).append(" nodes");
            if (entry.getValue().isArray() && count <= 10) {
                sb.append(" [");
                for (int i = 0; i < count; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(entry.getValue().get(i).asText());
                }
                sb.append("]");
            }
            sb.append("\n");
        }
        sb.append("\nTotal: ").append(totalNodes).append(" nodes\n");

        return ToolResult.success("traverse: " + nodeId, sb.toString(),
                Map.of("nodeId", nodeId, "depth", depth, "totalNodes", totalNodes));
    }

    private ToolResult shortestPath(JsonNode params) throws Exception {
        String from = requireString(params, "from_node_id", "shortest_path");
        String to = requireString(params, "to_node_id", "shortest_path");
        boolean weighted = params.path("weighted").asBoolean(false);
        long factSheetId = params.path("fact_sheet_id").asLong(0);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("fromNodeId", from);
        body.put("toNodeId", to);
        body.put("weighted", weighted);
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post("/api/graph/algorithms/path/shortest", body);
        StringBuilder sb = new StringBuilder();

        if (!response.path("found").asBoolean()) {
            sb.append("No path found from ").append(from).append(" to ").append(to);
            return ToolResult.success("shortest_path", sb.toString(),
                    Map.of("found", false));
        }

        double length = response.path("length").asDouble();
        sb.append("Shortest path (length ").append(length).append("):\n");
        JsonNode path = response.path("path");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(path.get(i).asText());
        }
        sb.append("\n");

        return ToolResult.success("shortest_path", sb.toString(),
                Map.of("found", true, "length", length, "hops", path.size()));
    }

    private ToolResult algorithm(JsonNode params) throws Exception {
        String name = requireString(params, "algorithm_name", "algorithm");
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        int limit = params.path("limit").asInt(20);

        String endpoint = switch (name.toLowerCase()) {
            case "pagerank" -> "/api/graph/algorithms/pagerank";
            case "degree" -> "/api/graph/algorithms/centrality/degree";
            case "betweenness" -> "/api/graph/algorithms/centrality/betweenness";
            case "wcc" -> "/api/graph/algorithms/components/wcc";
            case "jaccard" -> "/api/graph/algorithms/similarity/jaccard";
            default -> null;
        };
        if (endpoint == null) {
            return ToolResult.error("Unknown algorithm: " + name +
                    ". Use: pagerank, degree, betweenness, wcc, jaccard");
        }

        ObjectNode body = objectMapper.createObjectNode();
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post(endpoint, body);
        StringBuilder sb = new StringBuilder();
        sb.append("Algorithm: ").append(name).append("\n\n");

        if (response.isObject()) {
            // Score map: nodeId -> score, sort by score descending
            java.util.List<Map.Entry<String, JsonNode>> entries = new java.util.ArrayList<>();
            response.fields().forEachRemaining(entries::add);
            entries.sort((a, b) -> {
                double av = a.getValue().isNumber() ? a.getValue().asDouble() : 0;
                double bv = b.getValue().isNumber() ? b.getValue().asDouble() : 0;
                return Double.compare(bv, av);
            });
            int shown = 0;
            for (var entry : entries) {
                if (shown >= limit) break;
                sb.append("- ").append(entry.getKey()).append(": ")
                        .append(String.format("%.6f", entry.getValue().asDouble())).append("\n");
                shown++;
            }
            if (entries.size() > limit) {
                sb.append("... and ").append(entries.size() - limit).append(" more\n");
            }
        } else {
            sb.append(response.toPrettyString()).append("\n");
        }

        return ToolResult.success("algorithm: " + name, sb.toString(),
                Map.of("algorithm", name));
    }

    private ToolResult communities(JsonNode params) throws Exception {
        long factSheetId = params.path("fact_sheet_id").asLong(0);

        ObjectNode body = objectMapper.createObjectNode();
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post("/api/graph/algorithms/communities/louvain", body);
        StringBuilder sb = new StringBuilder("Communities (Louvain):\n\n");

        if (response.isObject()) {
            // Count nodes per community
            java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
            response.fields().forEachRemaining(e -> {
                int comm = e.getValue().asInt();
                counts.merge(comm, 1, Integer::sum);
            });
            counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append("- Community ").append(e.getKey())
                            .append(": ").append(e.getValue()).append(" nodes\n"));
            sb.append("\nTotal: ").append(counts.size()).append(" communities\n");
        } else {
            sb.append(response.toPrettyString()).append("\n");
        }

        return ToolResult.success("communities", sb.toString(), Map.of());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Hierarchy
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult hierarchy(JsonNode params) throws Exception {
        String nodeId = params.path("node_id").asText("");
        int depth = params.path("depth").asInt(5);

        String url;
        if (!nodeId.isEmpty()) {
            url = "/api/knowledge-graph/hierarchy/" + enc(nodeId) + "?maxDepth=" + depth;
        } else {
            // Root hierarchy - list root SOURCE nodes then show hierarchy
            url = "/api/knowledge-graph/nodes?type=SOURCE&limit=50";
        }

        JsonNode response = get(url);
        StringBuilder sb = new StringBuilder("Graph Hierarchy");
        if (!nodeId.isEmpty()) sb.append(" (root: ").append(nodeId).append(", depth: ").append(depth).append(")");
        sb.append(":\n\n");
        formatHierarchyTree(sb, response, 0);

        return ToolResult.success("hierarchy", sb.toString(),
                Map.of("rootNodeId", nodeId.isEmpty() ? "roots" : nodeId));
    }

    private ToolResult ancestors(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "ancestors");

        JsonNode response = get("/api/knowledge-graph/nodes/" + enc(nodeId) + "/ancestors");
        StringBuilder sb = new StringBuilder();
        sb.append("Ancestor chain for ").append(nodeId).append(":\n\n");

        if (response.isArray()) {
            for (int i = 0; i < response.size(); i++) {
                JsonNode ancestor = response.get(i);
                if (i > 0) sb.append(" -> ");
                sb.append(ancestor.path("title").asText("Untitled"))
                        .append(" [").append(ancestor.path("nodeType").asText("")).append("]");
            }
            sb.append("\n");
        }

        return ToolResult.success("ancestors: " + nodeId, sb.toString(),
                Map.of("nodeId", nodeId));
    }

    private ToolResult sourceChunks(JsonNode params) throws Exception {
        String nodeId = requireString(params, "node_id", "source_chunks");

        JsonNode response = get("/api/knowledge-graph/nodes/" + enc(nodeId) + "/source-chunks");
        StringBuilder sb = new StringBuilder();
        sb.append("Source chunks for entity ").append(nodeId).append(":\n\n");

        if (response.isArray()) {
            for (JsonNode chunk : response) {
                sb.append("### ").append(chunk.path("chunkTitle").asText("Untitled chunk")).append("\n");
                appendIfPresent(sb, "Chunk ID", chunk.path("chunkNodeId"));
                appendIfPresent(sb, "Document", chunk.path("documentTitle"));
                appendIfPresent(sb, "Source", chunk.path("sourceTitle"));
                appendIfPresent(sb, "Source Path", chunk.path("sourcePathOrUrl"));
                appendIfPresent(sb, "Extracted As", chunk.path("extractedAs"));
                appendIfPresent(sb, "Predicate", chunk.path("predicate"));
                appendIfPresent(sb, "Confidence", chunk.path("confidence"));
                String content = chunk.path("contentPreview").asText("");
                if (!content.isEmpty()) {
                    sb.append("- Content: ").append(content.length() > 200
                            ? content.substring(0, 200) + "..." : content).append("\n");
                }
                sb.append("\n");
            }
        }

        return ToolResult.success("source_chunks: " + nodeId, sb.toString(),
                Map.of("nodeId", nodeId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Named Graphs
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult listGraphs(JsonNode params) throws Exception {
        String query = params.path("query").asText("");
        String url = "/api/graphs";
        if (!query.isEmpty()) url += "?query=" + enc(query);

        JsonNode response = get(url);
        StringBuilder sb = new StringBuilder("Named Graphs:\n\n");

        if (response.isArray()) {
            for (JsonNode graph : response) {
                sb.append("- **").append(graph.path("name").asText("Unnamed")).append("**");
                sb.append(" ID=").append(graph.path("id").asText(""));
                appendIfPresent(sb, " Ontology", graph.path("ontologyType"));
                int nodeCount = graph.path("nodeCount").asInt(0);
                int edgeCount = graph.path("edgeCount").asInt(0);
                int childCount = graph.path("childGraphCount").asInt(0);
                sb.append(" (").append(nodeCount).append(" nodes, ")
                        .append(edgeCount).append(" edges");
                if (childCount > 0) sb.append(", ").append(childCount).append(" children");
                sb.append(")\n");
                appendIfPresent(sb, "  Description", graph.path("description"));
            }
            return ToolResult.success("list_graphs", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_graphs", sb.append(response.toPrettyString()).toString(), Map.of());
    }

    private ToolResult createGraph(JsonNode params) throws Exception {
        String name = params.path("graph_name").asText("");
        if (name.isEmpty()) name = params.path("title").asText("");
        if (name.isEmpty()) return ToolResult.error("graph_name or title is required for create_graph");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        String desc = params.path("item_description").asText("");
        if (!desc.isEmpty()) body.put("description", desc);
        String parentId = params.path("parent_graph_id").asText("");
        if (!parentId.isEmpty()) body.put("parentGraphId", parentId);
        String ontologyType = params.path("ontology_type").asText("");
        if (!ontologyType.isEmpty()) body.put("ontologyType", ontologyType);
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId > 0) body.put("factSheetId", factSheetId);

        JsonNode response = post("/api/graphs", body);
        String graphId = response.path("id").asText("");
        return ToolResult.success("create_graph: " + name,
                "Created named graph: " + name + " ID=" + graphId,
                Map.of("graphId", graphId, "name", name));
    }

    private ToolResult deleteGraph(JsonNode params) throws Exception {
        String graphId = requireString(params, "graph_id", "delete_graph");
        delete("/api/graphs/" + enc(graphId));
        return ToolResult.success("delete_graph: " + graphId,
                "Deleted named graph: " + graphId, Map.of("graphId", graphId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Extraction & Building
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult extract(JsonNode params) throws Exception {
        String text = params.path("text").asText("");
        if (text.isEmpty()) {
            return ToolResult.error("text is required for extract");
        }

        boolean persist = params.path("persist").asBoolean(false);
        String endpoint = persist
                ? "/api/graph/multi-agent/extract-and-persist"
                : "/api/graph/multi-agent/extract";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);

        String strategy = params.path("merge_strategy").asText("");
        if (!strategy.isEmpty()) body.put("mergeStrategy", strategy);
        double minConf = params.path("min_confidence").asDouble(0);
        if (minConf > 0) body.put("minConfidence", minConf);
        String entityTypes = params.path("entity_types").asText("");
        if (!entityTypes.isEmpty()) body.put("entityTypes", entityTypes);
        String agents = params.path("agents").asText("");
        if (!agents.isEmpty()) body.put("agents", agents);

        JsonNode response = post(endpoint, body);
        StringBuilder sb = new StringBuilder();
        sb.append("Multi-agent extraction").append(persist ? " (persisted)" : " (in-memory)").append(":\n\n");

        JsonNode entities = response.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            sb.append("### Entities (").append(entities.size()).append(")\n");
            for (JsonNode entity : entities) {
                sb.append("- **").append(entity.path("title").asText(entity.path("name").asText("")))
                        .append("** [").append(entity.path("type").asText("")).append("]");
                double conf = entity.path("confidence").asDouble(0);
                if (conf > 0) sb.append(String.format(" %.0f%%", conf * 100));
                sb.append("\n");
            }
            sb.append("\n");
        }

        JsonNode relations = response.path("relations");
        if (relations == null || relations.isMissingNode()) relations = response.path("relationships");
        if (relations.isArray() && !relations.isEmpty()) {
            sb.append("### Relationships (").append(relations.size()).append(")\n");
            for (JsonNode rel : relations) {
                sb.append("- ").append(rel.path("source").asText(""))
                        .append(" -[").append(rel.path("type").asText("")).append("]-> ")
                        .append(rel.path("target").asText("")).append("\n");
            }
        }

        return ToolResult.success("extract", sb.toString(),
                Map.of("entityCount", entities.isArray() ? entities.size() : 0,
                        "relationCount", relations.isArray() ? relations.size() : 0,
                        "persisted", persist));
    }

    private ToolResult buildGraph(JsonNode params) throws Exception {
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId <= 0) {
            return ToolResult.error("fact_sheet_id is required for build_graph");
        }

        JsonNode response = post("/api/fact-sheets/" + factSheetId + "/graph/build", null);
        String jobId = response.path("jobId").asText(response.path("id").asText(""));
        String status = response.path("status").asText("unknown");
        return ToolResult.success("build_graph",
                "Started graph build for fact sheet " + factSheetId +
                        " (job: " + jobId + ", status: " + status + ")",
                Map.of("factSheetId", factSheetId, "jobId", jobId, "status", status));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Reporting & Cypher
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult report(JsonNode params) throws Exception {
        String type = params.path("report_type").asText("summary");
        long factSheetId = params.path("fact_sheet_id").asLong(0);

        StringBuilder url = new StringBuilder("/api/graph/report?type=").append(enc(type));
        if (factSheetId > 0) url.append("&factSheetId=").append(factSheetId);

        JsonNode response = get(url.toString());
        // Report endpoint returns text/plain as markdown
        String reportText = response.isTextual() ? response.asText() : response.toPrettyString();
        return ToolResult.success("report: " + type, reportText,
                Map.of("reportType", type));
    }

    private ToolResult cypher(JsonNode params) throws Exception {
        String query = requireString(params, "cypher_query", "cypher");
        boolean readOnly = params.path("read_only").asBoolean(true);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("cypher", query);
        body.put("readOnly", readOnly);

        JsonNode response = post("/api/graph/cypher/query", body);
        StringBuilder sb = new StringBuilder();
        sb.append("Cypher query result (").append(response.path("elapsedMs").asLong(0)).append("ms):\n\n");

        JsonNode columns = response.path("columns");
        JsonNode rows = response.path("rows");

        if (columns.isArray() && columns.size() > 0) {
            // Header
            sb.append("| ");
            for (JsonNode col : columns) sb.append(col.asText()).append(" | ");
            sb.append("\n| ");
            for (int i = 0; i < columns.size(); i++) sb.append("--- | ");
            sb.append("\n");

            // Rows
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    sb.append("| ");
                    if (row.isArray()) {
                        for (JsonNode cell : row) {
                            String val = cell.isTextual() ? cell.asText() :
                                    (cell.isNull() ? "" : cell.toString());
                            sb.append(val).append(" | ");
                        }
                    }
                    sb.append("\n");
                }
                sb.append("\n").append(rows.size()).append(" row(s)\n");
            }
        }

        JsonNode stats = response.path("stats");
        if (stats.isObject() && !stats.isEmpty()) {
            sb.append("\nStats: ");
            stats.fields().forEachRemaining(e -> {
                if (e.getValue().asInt(0) > 0 || e.getValue().asLong(0) > 0) {
                    sb.append(e.getKey()).append("=").append(e.getValue().asText()).append(" ");
                }
            });
            sb.append("\n");
        }

        return ToolResult.success("cypher", sb.toString(),
                Map.of("rowCount", rows.isArray() ? rows.size() : 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Builder & Jobs
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult listBuilders() throws Exception {
        JsonNode response = get("/api/knowledge-graph/builder/builders");
        StringBuilder sb = new StringBuilder("Available Graph Builders:\n\n");
        if (response.isArray()) {
            for (JsonNode b : response) {
                sb.append("- **").append(b.path("displayName").asText("")).append("** (")
                        .append(b.path("id").asText("")).append(")\n");
                sb.append("  Type: ").append(b.path("type").asText("")).append("\n");
                appendIfPresent(sb, "  Description", b.path("description"));
                sb.append("  Supports Logs: ").append(b.path("supportsExtractionLog").asBoolean()).append("\n\n");
            }
            return ToolResult.success("list_builders", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_builders", sb.append(response.toPrettyString()).toString(), Map.of());
    }

    private ToolResult startJob(JsonNode params) throws Exception {
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId <= 0) return ToolResult.error("fact_sheet_id is required for start_job");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("factSheetId", factSheetId);
        String builderType = params.path("builder_type").asText("");
        if (!builderType.isEmpty()) body.put("builderType", builderType);

        ObjectNode config = objectMapper.createObjectNode();
        String mp = params.path("model_provider").asText("");
        if (!mp.isEmpty()) config.put("modelProvider", mp);
        String mn = params.path("model_name").asText("");
        if (!mn.isEmpty()) config.put("modelName", mn);
        double temp = params.path("temperature").asDouble(-1);
        if (temp >= 0) config.put("temperature", temp);
        double minConf = params.path("min_confidence").asDouble(-1);
        if (minConf >= 0) config.put("minConfidence", minConf);
        boolean autoAccept = params.path("auto_accept").asBoolean(false);
        if (params.has("auto_accept")) config.put("autoAccept", autoAccept);
        double aat = params.path("auto_accept_threshold").asDouble(-1);
        if (aat >= 0) config.put("autoAcceptThreshold", aat);
        String prompt = params.path("custom_prompt").asText("");
        if (!prompt.isEmpty()) config.put("customPrompt", prompt);
        String et = params.path("entity_types").asText("");
        if (!et.isEmpty()) {
            var arr = config.putArray("entityTypes");
            for (String t : et.split(",")) arr.add(t.trim());
        }

        if (!config.isEmpty()) body.set("config", config);

        JsonNode response = post("/api/knowledge-graph/builder/jobs", body);
        String jobId = response.path("jobId").asText("");
        String status = response.path("status").asText("");
        return ToolResult.success("start_job",
                "Started extraction job: " + jobId + " (status: " + status + ", builder: "
                        + response.path("builderType").asText("") + ", chunks: "
                        + response.path("totalChunks").asInt(0) + ")",
                Map.of("jobId", jobId, "status", status, "factSheetId", factSheetId));
    }

    private ToolResult listJobs(JsonNode params) throws Exception {
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        int limit = params.path("limit").asInt(20);
        String url = "/api/knowledge-graph/builder/jobs?page=0&size=" + limit;
        if (factSheetId > 0) url += "&factSheetId=" + factSheetId;

        JsonNode response = get(url);
        JsonNode content = response.has("content") ? response.get("content") : response;
        StringBuilder sb = new StringBuilder("Extraction Jobs:\n\n");

        if (content.isArray()) {
            for (JsonNode job : content) {
                sb.append("- **").append(job.path("jobId").asText("")).append("** [")
                        .append(job.path("status").asText("")).append("] ")
                        .append(job.path("builderType").asText("")).append(" - ")
                        .append(job.path("processedChunks").asInt(0)).append("/")
                        .append(job.path("totalChunks").asInt(0)).append(" chunks, ")
                        .append(job.path("proposalsCreated").asInt(0)).append(" proposals\n");
            }
            return ToolResult.success("list_jobs", sb.toString(),
                    Map.of("count", content.size()));
        }
        return ToolResult.success("list_jobs", sb.append(content.toPrettyString()).toString(), Map.of());
    }

    private ToolResult jobStatus(JsonNode params) throws Exception {
        String jobId = requireString(params, "job_id", "job_status");
        JsonNode job = get("/api/knowledge-graph/builder/jobs/" + enc(jobId));
        StringBuilder sb = new StringBuilder("Extraction Job: " + jobId + "\n\n");
        sb.append("- Status: ").append(job.path("status").asText("")).append("\n");
        sb.append("- Builder: ").append(job.path("builderType").asText("")).append("\n");
        sb.append("- Progress: ").append(job.path("processedChunks").asInt(0))
                .append("/").append(job.path("totalChunks").asInt(0))
                .append(" (").append(job.path("progressPercent").asInt(0)).append("%)\n");
        sb.append("- Proposals: ").append(job.path("proposalsCreated").asInt(0))
                .append(" created, ").append(job.path("proposalsAccepted").asInt(0))
                .append(" accepted, ").append(job.path("proposalsRejected").asInt(0))
                .append(" rejected\n");
        appendIfPresent(sb, "Created", job.path("createdAt"));
        appendIfPresent(sb, "Started", job.path("startedAt"));
        appendIfPresent(sb, "Completed", job.path("completedAt"));
        appendIfPresent(sb, "Error", job.path("errorMessage"));
        return ToolResult.success("job_status: " + jobId, sb.toString(),
                Map.of("jobId", jobId, "status", job.path("status").asText("")));
    }

    private ToolResult cancelJob(JsonNode params) throws Exception {
        String jobId = requireString(params, "job_id", "cancel_job");
        post("/api/knowledge-graph/builder/jobs/" + enc(jobId) + "/cancel", null);
        return ToolResult.success("cancel_job: " + jobId,
                "Cancelled extraction job: " + jobId, Map.of("jobId", jobId));
    }

    private ToolResult jobLogs(JsonNode params) throws Exception {
        String jobId = requireString(params, "job_id", "job_logs");
        int limit = params.path("limit").asInt(10);
        JsonNode response = get("/api/knowledge-graph/builder/jobs/" + enc(jobId) + "/logs?page=0&size=" + limit);
        JsonNode content = response.has("content") ? response.get("content") : response;
        StringBuilder sb = new StringBuilder("Extraction Logs for job " + jobId + ":\n\n");

        if (content.isArray()) {
            for (JsonNode log : content) {
                sb.append("### Chunk: ").append(log.path("chunkId").asText("")).append("\n");
                sb.append("- Model: ").append(log.path("modelProvider").asText(""))
                        .append("/").append(log.path("modelName").asText("")).append("\n");
                sb.append("- Success: ").append(log.path("success").asBoolean()).append("\n");
                sb.append("- Entities: ").append(log.path("entitiesCount").asInt(0))
                        .append(", Relationships: ").append(log.path("relationshipsCount").asInt(0)).append("\n");
                sb.append("- Latency: ").append(log.path("latencyMs").asLong(0)).append("ms\n");
                String error = log.path("errorMessage").asText("");
                if (!error.isEmpty()) sb.append("- Error: ").append(error).append("\n");
                sb.append("\n");
            }
            return ToolResult.success("job_logs: " + jobId, sb.toString(),
                    Map.of("jobId", jobId, "logCount", content.size()));
        }
        return ToolResult.success("job_logs: " + jobId, sb.toString(), Map.of());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Proposals
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult listProposals(JsonNode params) throws Exception {
        int limit = params.path("limit").asInt(20);
        StringBuilder url = new StringBuilder("/api/knowledge-graph/builder/proposals?page=0&size=").append(limit);
        String jobId = params.path("job_id").asText("");
        if (!jobId.isEmpty()) url.append("&jobId=").append(enc(jobId));
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId > 0) url.append("&factSheetId=").append(factSheetId);
        String status = params.path("proposal_status").asText("");
        if (!status.isEmpty()) url.append("&status=").append(enc(status));
        String query = params.path("query").asText("");
        if (!query.isEmpty()) url.append("&query=").append(enc(query));

        JsonNode response = get(url.toString());
        JsonNode content = response.has("content") ? response.get("content") : response;
        StringBuilder sb = new StringBuilder("Triple Proposals:\n\n");

        if (content.isArray()) {
            for (JsonNode p : content) {
                sb.append("- **").append(p.path("proposalId").asText("")).append("** [")
                        .append(p.path("status").asText("")).append("] ");
                sb.append(p.path("subjectName").asText("?")).append(" [")
                        .append(p.path("subjectType").asText("")).append("] --")
                        .append(p.path("predicateName").asText("?")).append("--> ")
                        .append(p.path("objectName").asText("?")).append(" [")
                        .append(p.path("objectType").asText("")).append("] ");
                sb.append(String.format("(%.0f%% confidence)\n", p.path("confidence").asDouble(0) * 100));
            }
            int total = response.path("totalElements").asInt(content.size());
            sb.append("\nTotal: ").append(total).append(" proposals\n");
            return ToolResult.success("list_proposals", sb.toString(),
                    Map.of("count", content.size(), "total", total));
        }
        return ToolResult.success("list_proposals", sb.toString(), Map.of());
    }

    private ToolResult acceptProposal(JsonNode params) throws Exception {
        String proposalId = requireString(params, "proposal_id", "accept_proposal");
        post("/api/knowledge-graph/builder/proposals/" + enc(proposalId) + "/accept?reviewedBy=mcp-tool", null);
        return ToolResult.success("accept_proposal: " + proposalId,
                "Accepted proposal: " + proposalId, Map.of("proposalId", proposalId));
    }

    private ToolResult rejectProposal(JsonNode params) throws Exception {
        String proposalId = requireString(params, "proposal_id", "reject_proposal");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reviewedBy", "mcp-tool");
        String reason = params.path("rejection_reason").asText("");
        if (!reason.isEmpty()) body.put("reason", reason);

        post("/api/knowledge-graph/builder/proposals/" + enc(proposalId) + "/reject", body);
        return ToolResult.success("reject_proposal: " + proposalId,
                "Rejected proposal: " + proposalId +
                        (reason.isEmpty() ? "" : " (reason: " + reason + ")"),
                Map.of("proposalId", proposalId));
    }

    private ToolResult manualProposal(JsonNode params) throws Exception {
        String subjectName = requireString(params, "subject_name", "manual_proposal");
        String subjectType = requireString(params, "subject_type", "manual_proposal");
        String predicateName = requireString(params, "predicate_name", "manual_proposal");
        String objectName = requireString(params, "object_name", "manual_proposal");
        String objectType = requireString(params, "object_type", "manual_proposal");
        long factSheetId = params.path("fact_sheet_id").asLong(0);
        if (factSheetId <= 0) return ToolResult.error("fact_sheet_id is required for manual_proposal");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("factSheetId", factSheetId);
        body.put("subjectName", subjectName);
        body.put("subjectType", subjectType);
        body.put("predicateName", predicateName);
        body.put("objectName", objectName);
        body.put("objectType", objectType);
        String desc = params.path("item_description").asText("");
        if (!desc.isEmpty()) body.put("description", desc);
        boolean autoAccept = params.path("auto_accept").asBoolean(false);
        body.put("autoAccept", autoAccept);

        JsonNode response = post("/api/knowledge-graph/builder/proposals/manual", body);
        String proposalId = response.path("proposalId").asText("");
        return ToolResult.success("manual_proposal",
                "Created manual proposal: " + subjectName + " --" + predicateName + "--> " + objectName
                        + " (ID: " + proposalId + ", status: " + response.path("status").asText("") + ")",
                Map.of("proposalId", proposalId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Config
    // ══════════════════════════════════════════════════════════════════════════

    private ToolResult getConfig() throws Exception {
        JsonNode config = get("/api/graph-extraction/config");
        StringBuilder sb = new StringBuilder("Graph Extraction Config:\n\n");
        sb.append("- Enabled: ").append(config.path("enabled").asBoolean()).append("\n");
        sb.append("- Schema Mode: ").append(config.path("schemaEnforcement").asText("NONE")).append("\n");
        sb.append("- Batch Size: ").append(config.path("batchSize").asInt(0)).append("\n");
        sb.append("- Provider: ").append(config.path("extractionModelProvider").asText("-")).append("\n");
        sb.append("- Model: ").append(config.path("extractionModelName").asText("-")).append("\n");
        sb.append("- Temperature: ").append(config.path("extractionTemperature").asDouble(0)).append("\n");
        sb.append("- Auto-Accept Threshold: ").append(config.path("autoAcceptThreshold").asDouble(0)).append("\n");
        sb.append("- Max Entities/Chunk: ").append(config.path("maxEntitiesPerChunk").asInt(0)).append("\n");
        sb.append("- Max Relations/Chunk: ").append(config.path("maxRelationshipsPerChunk").asInt(0)).append("\n");
        sb.append("- Deduplication: ").append(config.path("deduplicationEnabled").asBoolean()).append("\n");
        sb.append("- Neo4j: ").append(config.path("neo4jEnabled").asBoolean()).append("\n");

        JsonNode entityTypes = config.path("entityTypes");
        if (entityTypes.isArray() && !entityTypes.isEmpty()) {
            sb.append("- Entity Types: ");
            for (int i = 0; i < entityTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entityTypes.get(i).asText());
            }
            sb.append("\n");
        }

        return ToolResult.success("get_config", sb.toString(),
                Map.of("enabled", config.path("enabled").asBoolean()));
    }

    private ToolResult setConfig(JsonNode params) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        if (params.has("enabled")) body.put("enabled", params.path("enabled").asBoolean());
        String schemaMode = params.path("schema_mode").asText("");
        if (!schemaMode.isEmpty()) body.put("schemaEnforcement", schemaMode);
        String mp = params.path("model_provider").asText("");
        if (!mp.isEmpty()) body.put("extractionModelProvider", mp);
        String mn = params.path("model_name").asText("");
        if (!mn.isEmpty()) body.put("extractionModelName", mn);
        double temp = params.path("temperature").asDouble(-1);
        if (temp >= 0) body.put("extractionTemperature", temp);
        double aat = params.path("auto_accept_threshold").asDouble(-1);
        if (aat >= 0) body.put("autoAcceptThreshold", aat);
        double minConf = params.path("min_confidence").asDouble(-1);
        if (minConf >= 0) body.put("minConfidence", minConf);
        String et = params.path("entity_types").asText("");
        if (!et.isEmpty()) {
            var arr = body.putArray("entityTypes");
            for (String t : et.split(",")) arr.add(t.trim());
        }
        String cp = params.path("custom_prompt").asText("");
        if (!cp.isEmpty()) body.put("customExtractionPrompt", cp);

        if (body.isEmpty()) return ToolResult.error("No config fields specified for set_config");

        JsonNode response = post("/api/graph-extraction/config", body);
        return ToolResult.success("set_config",
                "Updated extraction config. Enabled: " + response.path("enabled").asBoolean(),
                Map.of("enabled", response.path("enabled").asBoolean()));
    }

    private ToolResult toggleExtraction() throws Exception {
        JsonNode response = post("/api/graph-extraction/config/toggle", null);
        boolean enabled = response.path("enabled").asBoolean();
        return ToolResult.success("toggle_extraction",
                "Graph extraction " + (enabled ? "ENABLED" : "DISABLED"),
                Map.of("enabled", enabled));
    }

    private ToolResult listProviders() throws Exception {
        JsonNode response = get("/api/graph-extraction/model-providers");
        StringBuilder sb = new StringBuilder("LLM Providers for Graph Extraction:\n\n");
        if (response.isArray()) {
            for (JsonNode p : response) {
                sb.append("- **").append(p.path("name").asText("")).append("** (")
                        .append(p.path("id").asText("")).append(") - ")
                        .append(p.path("available").asBoolean() ? "available" : "not configured").append("\n");
                JsonNode models = p.path("models");
                if (models.isArray() && !models.isEmpty()) {
                    sb.append("  Models: ");
                    for (int i = 0; i < models.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(models.get(i).asText());
                    }
                    sb.append("\n");
                }
            }
            return ToolResult.success("list_providers", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_providers", sb.toString(), Map.of());
    }

    private ToolResult listPresets() throws Exception {
        JsonNode response = get("/api/graph-extraction/schema-presets");
        StringBuilder sb = new StringBuilder("Schema Presets:\n\n");
        if (response.isArray()) {
            for (JsonNode p : response) {
                sb.append("- **").append(p.path("name").asText("")).append("** (")
                        .append(p.path("id").asText("")).append(")\n");
                appendIfPresent(sb, "  Description", p.path("description"));
                sb.append("  Node Types: ").append(p.path("nodeTypeCount").asInt(0))
                        .append(", Relationship Types: ").append(p.path("relationshipTypeCount").asInt(0))
                        .append("\n\n");
            }
            return ToolResult.success("list_presets", sb.toString(),
                    Map.of("count", response.size()));
        }
        return ToolResult.success("list_presets", sb.toString(), Map.of());
    }

    private ToolResult applyPreset(JsonNode params) throws Exception {
        String presetId = requireString(params, "preset_id", "apply_preset");
        JsonNode response = post("/api/graph-extraction/schema-presets/" + enc(presetId) + "/apply", null);
        StringBuilder sb = new StringBuilder("Applied schema preset: " + presetId + "\n\n");
        JsonNode entityTypes = response.path("entityTypes");
        if (entityTypes.isArray()) {
            sb.append("Entity Types: ");
            for (int i = 0; i < entityTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entityTypes.get(i).asText());
            }
            sb.append("\n");
        }
        JsonNode relTypes = response.path("relationshipTypes");
        if (relTypes.isArray()) {
            sb.append("Relationship Types: ");
            for (int i = 0; i < relTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(relTypes.get(i).asText());
            }
            sb.append("\n");
        }
        return ToolResult.success("apply_preset: " + presetId, sb.toString(),
                Map.of("presetId", presetId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Formatters
    // ══════════════════════════════════════════════════════════════════════════

    private void formatNodeList(StringBuilder sb, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            sb.append("- **").append(node.path("title").asText("Untitled")).append("** (")
                    .append(node.path("type").asText(node.path("nodeType").asText(""))).append(")");
            String desc = node.path("description").asText("");
            if (!desc.isEmpty()) sb.append(": ").append(desc);
            int connections = node.path("connections").asInt(
                    node.path("connectionCount").asInt(0));
            if (connections > 0) sb.append(" [").append(connections).append(" connections]");
            String id = node.path("nodeId").asText(node.path("id").asText(""));
            if (!id.isEmpty()) sb.append(" ID=").append(id);
            sb.append("\n");
        }
    }

    private void formatDocResults(StringBuilder sb, JsonNode results) {
        if (results == null || !results.isArray()) return;
        for (JsonNode doc : results) {
            sb.append("- **").append(doc.path("title").asText("Untitled")).append("** (")
                    .append(doc.path("type").asText("")).append(")")
                    .append("\n  ID: ").append(doc.path("documentId").asText(doc.path("id").asText("")))
                    .append(" | Source: ").append(doc.path("source").asText("")).append("\n");
            String desc = doc.path("description").asText("");
            if (!desc.isEmpty()) sb.append("  ").append(desc).append("\n");
        }
    }

    private void formatHierarchyTree(StringBuilder sb, JsonNode node, int indent) {
        String prefix = "  ".repeat(indent);
        if (node.isArray()) {
            for (JsonNode item : node) formatHierarchyTree(sb, item, indent);
            return;
        }
        if (!node.isObject()) return;

        sb.append(prefix).append("- ").append(node.path("title").asText("Untitled"))
                .append(" [").append(node.path("nodeType").asText(node.path("type").asText(""))).append("]");
        String id = node.path("nodeId").asText(node.path("id").asText(""));
        if (!id.isEmpty()) sb.append(" ID=").append(id);
        sb.append("\n");

        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode child : children) formatHierarchyTree(sb, child, indent + 1);
        }
    }

    private static void appendIfPresent(StringBuilder sb, String label, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        String text = value.isTextual() ? value.asText() : value.toString();
        if (!text.isEmpty()) {
            sb.append("- ").append(label).append(": ").append(text).append("\n");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private String requireString(JsonNode params, String field, String action) throws ToolExecutionException {
        String value = params.path(field).asText("");
        if (value.isEmpty()) {
            throw new ToolExecutionException(field + " is required for '" + action + "'");
        }
        return value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void addStringProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "string");
        prop.put("description", desc);
    }

    private void addIntProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "integer");
        prop.put("description", desc);
    }

    private void addNumberProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "number");
        prop.put("description", desc);
    }

    private void addBoolProp(ObjectNode props, String name, String desc) {
        ObjectNode prop = props.putObject(name);
        prop.put("type", "boolean");
        prop.put("description", desc);
    }

    // ── HTTP ──

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ToolExecutionException("HTTP " + response.statusCode() + ": " + extractError(response.body()));
        }
        String body = response.body();
        if (body == null || body.isBlank()) return objectMapper.createObjectNode();
        // Handle plain text responses (e.g., report endpoint)
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(body);
        }
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60));

        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new ToolExecutionException("HTTP " + response.statusCode() + ": " + extractError(response.body()));
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(responseBody);
    }

    private void delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .DELETE()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new ToolExecutionException("HTTP " + response.statusCode() + ": " + extractError(response.body()));
        }
    }

    private String extractError(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String msg = json.path("message").asText(null);
            if (msg != null) return msg;
            msg = json.path("error").asText(null);
            if (msg != null) return msg;
        } catch (Exception ignored) {}
        return body != null && body.length() > 200 ? body.substring(0, 200) + "..." : (body != null ? body : "");
    }
}
