/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Knowledge graph operations over either the explicit REST service or the folder-local archive. */
public class KnowledgeGraphTool implements CliTool {
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LocalProjectGraphBackend localBackend;

    public KnowledgeGraphTool(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.localBackend = new LocalProjectGraphBackend(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public String id() { return "knowledge_graph"; }

    @Override public String description() {
        return "Full knowledge graph operations: CRUD, algorithms, traversal, hierarchy, extraction, and more. "
                + "Without a configured server, the current MCP folder is the knowledge-base identity and its local graph is initialized automatically. "
                + "Actions: overview, stats, search_entity, search_nodes, find_by_topic, related_docs, source_context, entities_in_doc, "
                + "list_nodes, get_node, add_node, delete_node, list_edges, add_edge, delete_edge, find_connected, traverse, shortest_path, "
                + "algorithm, communities, hierarchy, ancestors, source_chunks, list_graphs, create_graph, delete_graph, extract, build_graph, "
                + "report, cypher, list_builders, start_job, list_jobs, job_status, cancel_job, job_logs, list_proposals, accept_proposal, "
                + "reject_proposal, manual_proposal, get_config, set_config, list_providers, list_models, capability_probe, list_presets, "
                + "apply_preset, owl_reasoning, ontology_conformance, bind_ontology, unbind_ontology, opinions, facts_by_tier, graph_health, "
                + "list_rules, reactive_rules, node_provenance, list_pipelines, reasoning_layers, list_fact_sheets, get_fact_sheet, "
                + "get_active_fact_sheet, create_fact_sheet, activate_fact_sheet, create_snapshot, list_snapshots, restore_snapshot, "
                + "delete_snapshot, and list_predicates.";
    }

    @Override public String compactHint() {
        return "knowledge_graph: full KG operations. Local stdio defaults to and auto-initializes the current folder; start with action=overview or list_predicates and omit fact_sheet_id. "
                + "list_fact_sheets inventories local crawl summaries; fact-sheet mutation and snapshot actions are remote/legacy workflows with explicit IDs. "
                + "Required param: action=<action_name>.";
    }

    @Override public JsonNode parameterSchema() {
        ObjectNode s = objectMapper.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        string(p,"action","Action to perform (see tool description for full list)");
        String[] strings = {"node_id","edge_id","graph_id","query","entity_name","topic","node_type","relationship_type","document_id","source_id","title","external_id","item_description","metadata_json","from_node_id","to_node_id","edge_type","algorithm_name","text","agents","merge_strategy","entity_types","cypher_query","report_type","graph_name","parent_graph_id","ontology_type","job_id","builder_type","model_provider","model_name","thinking","custom_prompt","proposal_id","proposal_status","subject_name","subject_type","predicate_name","object_name","object_type","rejection_reason","schema_mode","preset_id","ontology_schema_id","method","tier","basis_type","name","snapshot_id","label","probe_operation"};
        for (String n : strings) string(p,n,n.replace('_',' '));
        for (String n : new String[]{"max_results","limit","depth","fact_sheet_id","ontology_version","timeout_seconds"}) integer(p,n,n);
        for (String n : new String[]{"weight","min_confidence","temperature","auto_accept_threshold","resolution"}) number(p,n,n);
        for (String n : new String[]{"include_children","weighted","persist","read_only","auto_accept","live"}) bool(p,n,n);
        p.putObject("graphExtraction").put("type", "object")
                .put("description", "Local extract production graph config, as in crawl_documents. No request credentials.");
        p.putObject("processingRoute").put("type", "object")
                .put("description", "Local extract backend chain, as in crawl_documents; CHAT_MODEL selects native text chat without tools.");
        p.putObject("knowledgeBase").put("type","string").put("description","Local extract persistence destination; omitted uses the folder KB");
        s.putArray("required").add("action");
        return s;
    }
    @Override public String permissionKey() { return "knowledge_graph"; }
    @Override public McpToolAnnotations mcpAnnotations() { return null; }

    @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Knowledge graph operation");
        String action = params.path("action").asText("").toLowerCase();
        if (action.isEmpty()) return ToolResult.error("action is required");
        try {
            if (baseUrl == null || baseUrl.isBlank()) return localBackend.knowledgeGraph(params, context);
            if ("extract".equals(action)) return managedHostExtraction(params, context, action);
            return remote(action, params);
        } catch (ConnectException e) {
            return ToolResult.error("Cannot connect to explicitly configured remote graph service at " + baseUrl + ". Is it running?");
        } catch (Exception e) {
            return ToolResult.error("Knowledge graph error: " + e.getMessage());
        }
    }

    /**
     * Explicit remote graph configuration must not move graph extraction onto the managed
     * multi-agent endpoint.  Extraction is still a host operation: it uses the same native chat
     * selection as local stdio, including request-scoped provider/model/thinking overrides, and
     * only the resulting graph may be persisted by the local backend.
     */
    private ToolResult managedHostExtraction(JsonNode params, ToolContext context, String action)
            throws Exception {
        return localBackend.knowledgeGraph(params, context);
    }

    private ToolResult remote(String a, JsonNode p) throws Exception {
        return switch (a) {
            case "overview" -> overview();
            case "stats" -> stats();
            case "search_entity" -> searchEntity(p);
            case "search_nodes" -> searchNodes(p);
            case "find_by_topic" -> findByTopic(p);
            case "related_docs" -> relatedDocs(p);
            case "source_context" -> sourceContext(p);
            case "entities_in_doc" -> entitiesInDoc(p);
            case "find_connected" -> findConnected(p);
            case "list_nodes" -> listNodes(p);
            case "get_node" -> getNode(p);
            case "add_node" -> addNode(p);
            case "delete_node" -> deleteAction(p,"node_id","delete_node","/api/knowledge-graph/nodes/");
            case "list_edges" -> listEdges(p);
            case "add_edge" -> addEdge(p);
            case "delete_edge" -> deleteAction(p,"edge_id","delete_edge","/api/knowledge-graph/edges/");
            case "traverse" -> traverseRemote(p);
            case "shortest_path" -> shortestPath(p);
            case "algorithm" -> algorithm(p);
            case "communities" -> communities(p);
            case "hierarchy" -> hierarchyRemote(p);
            case "ancestors" -> getRequired(p,"node_id",a,"/api/knowledge-graph/nodes/","/ancestors");
            case "source_chunks" -> getRequired(p,"node_id",a,"/api/knowledge-graph/nodes/","/source-chunks");
            case "list_graphs" -> listGraphsRemote(p);
            case "create_graph" -> createGraph(p);
            case "delete_graph" -> deleteAction(p,"graph_id","delete_graph","/api/graphs/");
            case "build_graph" -> buildGraph(p);
            case "report" -> reportRemote(p);
            case "cypher" -> postRequired(p,"cypher_query",a,"/api/graph/cypher/query","cypher");
            case "list_builders" -> getResult(a,"/api/knowledge-graph/builder/builders");
            case "start_job" -> startJobRemote(p);
            case "list_jobs" -> listJobsRemote(p);
            case "job_status" -> getRequired(p,"job_id",a,"/api/knowledge-graph/builder/jobs/");
            case "cancel_job" -> cancelJob(p);
            case "job_logs" -> jobLogsRemote(p);
            case "list_proposals" -> listProposalsRemote(p);
            case "accept_proposal" -> acceptProposal(p);
            case "reject_proposal" -> rejectProposal(p);
            case "manual_proposal" -> manualProposal(p);
            case "get_config" -> getResult(a,"/api/graph-extraction/config");
            case "set_config" -> setConfigRemote(p);
            case "list_providers" -> getResult(a,"/api/graph-extraction/model-providers");
            case "list_models", "capability_probe" -> ToolResult.error(a+" is host-native and requires local stdio");
            case "list_presets" -> getResult(a,"/api/graph-extraction/schema-presets");
            case "apply_preset" -> applyPreset(p);
            case "owl_reasoning" -> factSheetGet(p,a,"/api/graph-ontology/owl?factSheetId=");
            case "ontology_conformance" -> factSheetGet(p,a,"/api/process/ontology/conformance?factSheetId=");
            case "bind_ontology" -> bindOntology(p);
            case "unbind_ontology" -> unbindOntology(p);
            case "opinions" -> opinionsRemote(p);
            case "facts_by_tier" -> factsByTierRemote(p);
            case "graph_health" -> graphHealthRemote(p);
            case "list_rules" -> listRulesRemote(p);
            case "reactive_rules" -> getResult(a,"/api/graph/rules");
            case "node_provenance" -> getRequired(p,"node_id",a,"/api/knowledge-graph/nodes/","/provenance");
            case "list_pipelines" -> getResult(a,"/api/graph/pipelines");
            case "reasoning_layers" -> reasoningLayers(p);
            case "list_fact_sheets" -> listFactSheets();
            case "get_fact_sheet" -> getFactSheet(p);
            case "get_active_fact_sheet" -> getActiveFactSheet();
            case "create_fact_sheet" -> createFactSheet(p);
            case "activate_fact_sheet" -> activateFactSheet(p);
            case "create_snapshot" -> snapshotCreate(p);
            case "list_snapshots" -> snapshotList(p);
            case "restore_snapshot" -> snapshotRestore(p);
            case "delete_snapshot" -> snapshotDelete(p);
            case "list_predicates" -> listPredicates(p);
            default -> ToolResult.error("Unknown action: " + a
                    + ". Use one of: overview, stats, search_entity, search_nodes, find_by_topic, "
                    + "related_docs, source_context, entities_in_doc, find_connected, list_nodes, "
                    + "get_node, add_node, delete_node, list_edges, add_edge, delete_edge, traverse, "
                    + "shortest_path, algorithm, communities, hierarchy, ancestors, source_chunks, "
                    + "list_graphs, create_graph, delete_graph, extract, build_graph, report, cypher, "
                    + "list_builders, start_job, list_jobs, job_status, cancel_job, job_logs, "
                    + "list_proposals, accept_proposal, reject_proposal, manual_proposal, get_config, "
                    + "set_config, list_providers, list_models, capability_probe, list_presets, "
                    + "apply_preset, owl_reasoning, ontology_conformance, bind_ontology, "
                    + "unbind_ontology, opinions, facts_by_tier, graph_health, list_rules, "
                    + "reactive_rules, node_provenance, list_pipelines, reasoning_layers, "
                    + "list_fact_sheets, get_fact_sheet, get_active_fact_sheet, create_fact_sheet, "
                    + "activate_fact_sheet, create_snapshot, list_snapshots, restore_snapshot, "
                    + "delete_snapshot, list_predicates");
        };
    }

    private ToolResult overview() throws Exception {
        JsonNode response=get("/api/knowledge-graph/overview");
        StringBuilder sb=new StringBuilder("Knowledge Graph Overview\n\n");
        JsonNode stats=response.path("statistics");
        if(stats.isObject()){sb.append("### Statistics\n");stats.fields().forEachRemaining(e->sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));sb.append("\n");}
        JsonNode sources=response.path("sources");
        if(sources.isArray()&&!sources.isEmpty()){sb.append("### Sources (").append(response.path("sourceCount").asInt(0)).append(")\n");for(JsonNode src:sources)sb.append("- **").append(src.path("title").asText("Untitled")).append("** (").append(src.path("type").asText("")).append(") - ").append(src.path("documentCount").asInt(0)).append(" docs\n");sb.append("\n");}
        JsonNode topics=response.path("topics");
        if(topics.isArray()&&!topics.isEmpty()){sb.append("### Topics (").append(response.path("topicCount").asInt(0)).append(")\n");for(JsonNode topic:topics)sb.append("- ").append(topic.asText()).append("\n");}
        return ToolResult.success("knowledge_graph_overview",sb.toString(),Map.of());
    }
    private ToolResult stats() throws Exception {
        JsonNode response=get("/api/knowledge-graph/statistics");
        StringBuilder sb=new StringBuilder("Knowledge Graph Statistics:\n\n");
        response.fields().forEachRemaining(e->sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n"));
        return ToolResult.success("graph_stats",sb.toString(),Map.of());
    }
    private ToolResult searchEntity(JsonNode p) throws Exception {
        String value=require(p,"entity_name","search_entity");
        ObjectNode body=objectMapper.createObjectNode().put("entityName",value).put("maxResults",p.path("max_results").asInt(10));
        JsonNode response=post("/api/knowledge-graph/search-by-entity",body);
        StringBuilder sb=new StringBuilder("Documents mentioning \"").append(value).append("\":\n\n");
        formatDocResults(sb,response.path("results"));
        return ToolResult.success("search_entity: "+value,sb.toString(),Map.of("entity",value,"resultCount",response.path("resultCount").asInt(0)));
    }
    private ToolResult searchNodes(JsonNode p) throws Exception {
        String value=require(p,"query","search_nodes");
        ObjectNode body=objectMapper.createObjectNode().put("query",value).put("maxResults",p.path("max_results").asInt(10));
        String type=p.path("node_type").asText(""); if(!type.isBlank())body.put("nodeType",type);
        JsonNode response=post("/api/knowledge-graph/search-nodes",body);
        StringBuilder sb=new StringBuilder("Node search: \"").append(value).append("\""); if(!type.isBlank())sb.append(" (type: ").append(type).append(")"); sb.append("\n\n");
        formatNodeList(sb,response.path("results"));
        return ToolResult.success("search_nodes: "+value,sb.toString(),Map.of("query",value,"resultCount",response.path("resultCount").asInt(0)));
    }
    private ToolResult findByTopic(JsonNode p) throws Exception {
        String value=require(p,"topic","find_by_topic");
        JsonNode response=post("/api/knowledge-graph/find-by-topic",objectMapper.createObjectNode().put("topic",value).put("maxResults",p.path("max_results").asInt(10)));
        StringBuilder sb=new StringBuilder("Documents for topic \"").append(value).append("\":\n\n"); String message=response.path("message").asText(""); if(!message.isEmpty())sb.append(message).append("\n"); formatDocResults(sb,response.path("results"));
        return ToolResult.success("find_by_topic: "+value,sb.toString(),Map.of("topic",value,"resultCount",response.path("resultCount").asInt(0)));
    }
    private ToolResult relatedDocs(JsonNode p) throws Exception {
        String value=require(p,"document_id","related_docs"); String type=p.path("relationship_type").asText("any");
        ObjectNode body=objectMapper.createObjectNode().put("documentId",value).put("maxResults",p.path("max_results").asInt(10)).put("relationshipType",type);
        JsonNode response=post("/api/knowledge-graph/related-documents",body); StringBuilder sb=new StringBuilder("Documents related to ").append(value).append(":\n\n"); formatDocResults(sb,response.path("results"));
        return ToolResult.success("related_docs: "+value,sb.toString(),Map.of("documentId",value,"resultCount",response.path("resultCount").asInt(0)));
    }
    private ToolResult sourceContext(JsonNode p) throws Exception {
        String value=require(p,"source_id","source_context"); boolean children=p.path("include_children").asBoolean(false);
        JsonNode response=post("/api/knowledge-graph/source-context",objectMapper.createObjectNode().put("sourceId",value).put("includeChildren",children));
        StringBuilder sb=new StringBuilder("Source: ").append(response.path("title").asText("Untitled")).append("\n"); sb.append("- ID: ").append(response.path("sourceId").asText("")).append("\n"); sb.append("- Type: ").append(response.path("type").asText("unknown")).append("\n"); sb.append("- Documents: ").append(response.path("documentCount").asInt(0)).append("\n"); sb.append("- Weight: ").append(response.path("weight").asDouble(1.0)).append("\n"); appendIfPresent(sb,"Description",response.path("description"));
        JsonNode docs=response.path("documents"); if(docs.isArray()&&!docs.isEmpty()){sb.append("\nDocuments:\n");for(JsonNode doc:docs)sb.append("- ").append(doc.path("title").asText("Untitled")).append(" (").append(doc.path("id").asText("")).append(")\n");}
        return ToolResult.success("source_context: "+value,sb.toString(),Map.of("sourceId",value));
    }
    private ToolResult entitiesInDoc(JsonNode p) throws Exception {
        String value=require(p,"document_id","entities_in_doc"); JsonNode response=post("/api/knowledge-graph/document-entities",objectMapper.createObjectNode().put("documentId",value));
        StringBuilder sb=new StringBuilder("Entities in document ").append(value).append(":\n\n"); JsonNode entities=response.path("entities"); if(entities.isArray())for(JsonNode entity:entities)sb.append("- **").append(entity.path("entity").asText("")).append("** (").append(entity.path("type").asText("")).append(") - ").append(entity.path("mentions").asInt(0)).append(" mentions, ").append(String.format("%.0f%% confidence",entity.path("confidence").asDouble(0)*100)).append("\n");
        return ToolResult.success("entities_in_doc: "+value,sb.toString(),Map.of("documentId",value,"entityCount",response.path("entityCount").asInt(0)));
    }
    private ToolResult findConnected(JsonNode p) throws Exception {
        String value=require(p,"node_id","find_connected"); int depth=p.path("depth").asInt(2); JsonNode response=post("/api/knowledge-graph/find-connected",objectMapper.createObjectNode().put("nodeId",value).put("depth",depth)); StringBuilder sb=new StringBuilder("Connected nodes from ").append(value).append(" (depth ").append(depth).append("):\n\n"); formatNodeList(sb,response.path("nodes"));
        return ToolResult.success("find_connected: "+value,sb.toString(),Map.of("nodeId",value,"depth",depth,"nodeCount",response.path("nodeCount").asInt(0)));
    }

    private ToolResult postSearch(JsonNode p,String field,String action,String path,String jsonField)throws Exception{
        String v=require(p,field,action);
        ObjectNode body=objectMapper.createObjectNode().put(jsonField,v).put("maxResults",p.path("max_results").asInt(10));
        if ("search_nodes".equals(action) && !p.path("node_type").asText("").isBlank())
            body.put("nodeType",p.path("node_type").asText());
        if ("related_docs".equals(action))
            body.put("relationshipType",p.path("relationship_type").asText("any"));
        if ("source_context".equals(action))
            body.put("includeChildren",p.path("include_children").asBoolean(false));
        return postResult(action+": "+v,path,body);
    }
    private ToolResult postRequired(JsonNode p,String field,String action,String path,String jsonField)throws Exception{String v=require(p,field,action);return postResult(action+": "+v,path,objectMapper.createObjectNode().put(jsonField,v).put("depth",p.path("depth").asInt(2)));}
    private String edgesPath(JsonNode p) {
        StringBuilder path = new StringBuilder("/api/knowledge-graph/edges?limit=")
                .append(p.path("limit").asInt(100));
        if (!p.path("node_id").asText("").isBlank()) path.append("&nodeId=").append(enc(p.path("node_id").asText()));
        if (!p.path("edge_type").asText("").isBlank()) path.append("&type=").append(enc(p.path("edge_type").asText()));
        return path.toString();
    }
    private ToolResult traverseRemote(JsonNode p) throws Exception {
        String nodeId = require(p,"node_id","traverse");
        ObjectNode body = objectMapper.createObjectNode().put("startNodeId",nodeId)
                .put("maxDepth",p.path("depth").asInt(3));
        if (p.path("fact_sheet_id").asLong(0) > 0) body.put("factSheetId",p.path("fact_sheet_id").asLong());
        JsonNode response=post("/api/graph/algorithms/traverse/bfs",body); StringBuilder sb=new StringBuilder("BFS traversal from ").append(nodeId).append(" (max depth ").append(p.path("depth").asInt(3)).append("):\n\n"); int total=0;
        var fields=response.fields(); while(fields.hasNext()){var entry=fields.next();int count=entry.getValue().isArray()?entry.getValue().size():0;total+=count;sb.append("Level ").append(entry.getKey()).append(": ").append(count).append(" nodes");if(entry.getValue().isArray()&&count<=10){sb.append(" [");for(int i=0;i<count;i++){if(i>0)sb.append(", ");sb.append(entry.getValue().get(i).asText());}sb.append("]");}sb.append("\n");} sb.append("\nTotal: ").append(total).append(" nodes\n");
        return ToolResult.success("traverse: "+nodeId,sb.toString(),Map.of("nodeId",nodeId,"depth",p.path("depth").asInt(3),"totalNodes",total));
    }
    private ToolResult listNodes(JsonNode p) throws Exception {
        int limit=p.path("limit").asInt(50); String type=p.path("node_type").asText("");
        String path="/api/knowledge-graph/nodes?limit="+limit; if(!type.isBlank())path+="&type="+enc(type);
        JsonNode response=get(path); StringBuilder sb=new StringBuilder("Nodes"); if(!type.isBlank())sb.append(" (type: ").append(type).append(")"); sb.append(":\n\n");
        if(response.isArray()){for(JsonNode node:response)sb.append("- **").append(node.path("title").asText("Untitled")).append("** [").append(node.path("nodeType").asText("")).append("] ID=").append(node.path("nodeId").asText("")).append("\n");return ToolResult.success("list_nodes",sb.toString(),Map.of("count",response.size()));}
        return ToolResult.success("list_nodes",sb.append(response.toPrettyString()).toString(),Map.of());
    }
    private ToolResult getNode(JsonNode p) throws Exception {
        String id=require(p,"node_id","get_node"); JsonNode node=get("/api/knowledge-graph/nodes/"+enc(id)); StringBuilder sb=new StringBuilder("Node Details:\n");
        sb.append("- ID: ").append(node.path("nodeId").asText("")).append("\n- Type: ").append(node.path("nodeType").asText("")).append("\n- External ID: ").append(node.path("externalId").asText("")).append("\n- Title: ").append(node.path("title").asText("")).append("\n");
        appendIfPresent(sb,"Description",node.path("description")); appendIfPresent(sb,"Confidence",node.path("confidence")); appendIfPresent(sb,"Source Type",node.path("sourceType")); appendIfPresent(sb,"Path/URL",node.path("pathOrUrl")); appendIfPresent(sb,"Content Preview",node.path("contentPreview")); appendIfPresent(sb,"Created",node.path("createdAt")); appendIfPresent(sb,"Updated",node.path("updatedAt"));
        JsonNode meta=node.path("metadata"); if(meta.isObject()&&!meta.isEmpty())sb.append("- Metadata: ").append(meta.toPrettyString()).append("\n");
        return ToolResult.success("get_node: "+id,sb.toString(),Map.of("nodeId",id,"nodeType",node.path("nodeType").asText("")));
    }
    private ToolResult listEdges(JsonNode p) throws Exception {
        JsonNode response=get(edgesPath(p)); String node=p.path("node_id").asText(""); String type=p.path("edge_type").asText(""); StringBuilder sb=new StringBuilder("Edges"); if(!node.isBlank())sb.append(" for node ").append(node); if(!type.isBlank())sb.append(" (type: ").append(type).append(")"); sb.append(":\n\n");
        if(response.isArray()){for(JsonNode edge:response)sb.append("- [").append(edge.path("edgeType").asText("")).append("] ").append(edge.path("sourceNodeId").asText("")).append(" -> ").append(edge.path("targetNodeId").asText("")).append(" (weight: ").append(String.format("%.2f",edge.path("weight").asDouble(1.0))).append(") ID=").append(edge.path("edgeId").asText("")).append("\n");return ToolResult.success("list_edges",sb.toString(),Map.of("count",response.size()));}
        return ToolResult.success("list_edges",sb.append(response.toPrettyString()).toString(),Map.of());
    }
    private ToolResult getResult(String t,String path)throws Exception{JsonNode r=get(path);return ToolResult.success(t,r.isTextual()?r.asText():r.toPrettyString(),Map.of());}
    private ToolResult postResult(String t,String path,JsonNode body)throws Exception{JsonNode r=post(path,body);return ToolResult.success(t,r.toPrettyString(),Map.of());}
    private ToolResult getRequired(JsonNode p,String field,String action,String prefix)throws Exception{return getRequired(p,field,action,prefix,"");}
    private ToolResult getRequired(JsonNode p,String field,String action,String prefix,String suffix)throws Exception{String v=require(p,field,action);return getResult(action+": "+v,prefix+enc(v)+suffix);}
    private ToolResult deleteAction(JsonNode p,String field,String action,String prefix)throws Exception{String v=require(p,field,action);delete(prefix+enc(v));return ToolResult.success(action+": "+v,"Deleted: "+v,Map.of(field,v));}
    private ToolResult addNode(JsonNode p)throws Exception{
        String title=require(p,"title","add_node");
        String externalId=require(p,"external_id","add_node");
        ObjectNode b=objectMapper.createObjectNode().put("nodeType",p.path("node_type").asText("ENTITY"))
                .put("externalId",externalId).put("title",title);
        if (!p.path("item_description").asText("").isBlank()) b.put("description",p.path("item_description").asText());
        if (!p.path("metadata_json").asText("").isBlank()) b.set("metadata",objectMapper.readTree(p.path("metadata_json").asText()));
        if (p.path("fact_sheet_id").asLong(0)>0) b.put("factSheetId",p.path("fact_sheet_id").asLong());
        return postResult("add_node: "+title,"/api/knowledge-graph/nodes",b);
    }
    private ToolResult addEdge(JsonNode p)throws Exception{
        String from=require(p,"from_node_id","add_edge"),to=require(p,"to_node_id","add_edge");
        ObjectNode b=objectMapper.createObjectNode().put("sourceNodeId",from).put("targetNodeId",to)
                .put("edgeType",p.path("edge_type").asText("USER_DEFINED"))
                .put("weight",p.path("weight").asDouble(1.0));
        if (!p.path("item_description").asText("").isBlank()) b.put("description",p.path("item_description").asText());
        return postResult("add_edge","/api/knowledge-graph/edges",b);
    }
    private ToolResult shortestPath(JsonNode p)throws Exception{
        String f=require(p,"from_node_id","shortest_path"),t=require(p,"to_node_id","shortest_path");
        ObjectNode b=objectMapper.createObjectNode().put("fromNodeId",f).put("toNodeId",t)
                .put("weighted",p.path("weighted").asBoolean(false));
        if (p.path("fact_sheet_id").asLong(0)>0) b.put("factSheetId",p.path("fact_sheet_id").asLong());
        return postResult("shortest_path","/api/graph/algorithms/path/shortest",b);
    }
    private ToolResult algorithm(JsonNode p)throws Exception{
        String n=require(p,"algorithm_name","algorithm");
        String e=switch(n.toLowerCase()){case"pagerank"->"/api/graph/algorithms/pagerank";case"degree"->"/api/graph/algorithms/centrality/degree";case"betweenness"->"/api/graph/algorithms/centrality/betweenness";case"wcc"->"/api/graph/algorithms/components/wcc";case"jaccard"->"/api/graph/algorithms/similarity/jaccard";default->null;};
        if (e==null) return ToolResult.error("Unknown algorithm: "+n+                ". Use: pagerank, degree, betweenness, wcc, jaccard");
        ObjectNode b=objectMapper.createObjectNode();
        if (p.path("fact_sheet_id").asLong(0)>0) b.put("factSheetId",p.path("fact_sheet_id").asLong());
        return postResult("algorithm: "+n,e,b);
    }
    private ToolResult communities(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);return id<=0?postResult("communities","/api/graph/algorithms/communities/louvain",objectMapper.createObjectNode()):getResult("communities","/api/graph/"+id+"/communities?method="+enc(p.path("method").asText("louvain"))+"&resolution="+p.path("resolution").asDouble(1.0));}
    private ToolResult createGraph(JsonNode p)throws Exception{
        String n=p.path("graph_name").asText(p.path("title").asText(""));
        if(n.isEmpty())return ToolResult.error("graph_name or title is required for create_graph");
        ObjectNode b=objectMapper.createObjectNode().put("name",n);
        if(!p.path("item_description").asText("").isBlank()) b.put("description",p.path("item_description").asText());
        if(!p.path("parent_graph_id").asText("").isBlank()) b.put("parentGraphId",p.path("parent_graph_id").asText());
        if(!p.path("ontology_type").asText("").isBlank()) b.put("ontologyType",p.path("ontology_type").asText());
        if(p.path("fact_sheet_id").asLong(0)>0) b.put("factSheetId",p.path("fact_sheet_id").asLong());
        return postResult("create_graph: "+n,"/api/graphs",b);
    }
    private ToolResult buildGraph(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for build_graph");return postResult("build_graph","/api/fact-sheets/"+id+"/graph/build",null);}
    private ToolResult reportRemote(JsonNode p)throws Exception{
        String type=p.path("report_type").asText("summary");
        String path="/api/graph/report?type="+enc(type);
        if(p.path("fact_sheet_id").asLong(0)>0) path+="&factSheetId="+p.path("fact_sheet_id").asLong();
        return getResult("report: "+type,path);
    }
    private ToolResult listGraphsRemote(JsonNode p)throws Exception{String path="/api/graphs";if(!p.path("query").asText("").isBlank())path+="?query="+enc(p.path("query").asText());return getResult("list_graphs",path);}
    private ToolResult hierarchyRemote(JsonNode p)throws Exception{String id=p.path("node_id").asText("");String path=id.isBlank()?"/api/knowledge-graph/nodes?type=SOURCE&limit=50":"/api/knowledge-graph/hierarchy/"+enc(id)+"?maxDepth="+p.path("depth").asInt(5);return getResult("hierarchy",path);}
    private ToolResult opinionsRemote(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for opinions");StringBuilder path=new StringBuilder("/api/kb-grounding/").append(id).append("/opinions?");List<String> query=new ArrayList<>();if(!p.path("query").asText("").isBlank())query.add("q="+enc(p.path("query").asText()));if(!p.path("tier").asText("").isBlank())query.add("tier="+enc(p.path("tier").asText()));query.add("limit="+p.path("limit").asInt(50));if(!p.path("basis_type").asText("").isBlank())query.add("basisType="+enc(p.path("basis_type").asText()));return getResult("opinions",path+String.join("&",query));}
    private ToolResult factsByTierRemote(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for facts_by_tier");String path="/api/kb-grounding/"+id+"/facts";if(!p.path("tier").asText("").isBlank())path+="?tier="+enc(p.path("tier").asText());return getResult("facts_by_tier",path);}
    private ToolResult graphHealthRemote(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for graph_health");return getResult("graph_health", "/api/graph-health/"+id);}
    private ToolResult listRulesRemote(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for list_rules");return getResult("list_rules","/api/graph/"+id+"/rules");}
    private ToolResult setConfigRemote(JsonNode p)throws Exception{
        ObjectNode body=objectMapper.createObjectNode();
        if(!p.path("schema_mode").asText("").isBlank())body.put("schemaEnforcement",p.path("schema_mode").asText());
        if(!p.path("model_provider").asText("").isBlank())body.put("extractionModelProvider",p.path("model_provider").asText());
        if(!p.path("model_name").asText("").isBlank())body.put("extractionModelName",p.path("model_name").asText());
        if(p.has("temperature"))body.put("extractionTemperature",p.path("temperature").asDouble());
        if(p.has("auto_accept_threshold"))body.put("autoAcceptThreshold",p.path("auto_accept_threshold").asDouble());
        if(p.has("min_confidence"))body.put("minConfidence",p.path("min_confidence").asDouble());
        if(!p.path("entity_types").asText("").isBlank()){ArrayNode values=body.putArray("entityTypes");for(String value:csv(p.path("entity_types").asText("")))values.add(value);}
        if(!p.path("custom_prompt").asText("").isBlank())body.put("customExtractionPrompt",p.path("custom_prompt").asText());
        if(body.isEmpty())return ToolResult.error("No config fields specified for set_config");
        post("/api/graph-extraction/config",body);return ToolResult.success("set_config","Updated extraction config. Graph extraction is mandatory.",Map.of("mandatory",true));
    }
    private ToolResult startJobRemote(JsonNode p)throws Exception{
        long id=p.path("fact_sheet_id").asLong(0);
        if(id<=0)return ToolResult.error("fact_sheet_id is required for start_job");
        ObjectNode b=objectMapper.createObjectNode().put("factSheetId",id);
        if(!p.path("builder_type").asText("").isBlank()) b.put("builderType",p.path("builder_type").asText());
        ObjectNode cfg=b.putObject("config");
        if(!p.path("model_provider").asText("").isBlank()) cfg.put("modelProvider",p.path("model_provider").asText());
        if(!p.path("model_name").asText("").isBlank()) cfg.put("modelName",p.path("model_name").asText());
        if(p.has("temperature")) cfg.put("temperature",p.path("temperature").asDouble());
        if(p.has("min_confidence")) cfg.put("minConfidence",p.path("min_confidence").asDouble());
        if(p.has("auto_accept")) cfg.put("autoAccept",p.path("auto_accept").asBoolean());
        if(p.has("auto_accept_threshold")) cfg.put("autoAcceptThreshold",p.path("auto_accept_threshold").asDouble());
        if(!p.path("custom_prompt").asText("").isBlank()) cfg.put("customPrompt",p.path("custom_prompt").asText());
        ArrayNode types=cfg.putArray("entityTypes"); for(String type:csv(p.path("entity_types").asText(""))) types.add(type);
        if(types.isEmpty()) cfg.remove("entityTypes");
        if(cfg.isEmpty()) b.remove("config");
        return postResult("start_job","/api/knowledge-graph/builder/jobs",b);
    }
    private ToolResult listJobsRemote(JsonNode p)throws Exception{
        String path="/api/knowledge-graph/builder/jobs?page=0&size="+p.path("limit").asInt(20);
        if(p.path("fact_sheet_id").asLong(0)>0) path+="&factSheetId="+p.path("fact_sheet_id").asLong();
        return getResult("list_jobs",path);
    }
    private ToolResult jobLogsRemote(JsonNode p)throws Exception{
        String id=require(p,"job_id","job_logs");
        return getResult("job_logs: "+id,"/api/knowledge-graph/builder/jobs/"+enc(id)+"/logs?page=0&size="+p.path("limit").asInt(10));
    }
    private ToolResult listProposalsRemote(JsonNode p)throws Exception{
        String path="/api/knowledge-graph/builder/proposals?page=0&size="+p.path("limit").asInt(20);
        if(!p.path("job_id").asText("").isBlank()) path+="&jobId="+enc(p.path("job_id").asText());
        if(p.path("fact_sheet_id").asLong(0)>0) path+="&factSheetId="+p.path("fact_sheet_id").asLong();
        if(!p.path("proposal_status").asText("").isBlank()) path+="&status="+enc(p.path("proposal_status").asText());
        if(!p.path("query").asText("").isBlank()) path+="&query="+enc(p.path("query").asText());
        return getResult("list_proposals",path);
    }
    private ToolResult factSheetRequired(JsonNode p,String a,String path)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for "+a);return postResult(a,path,objectMapper.createObjectNode().put("factSheetId",id));}
    private ToolResult factSheetGet(JsonNode p,String a,String prefix)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for "+a);return getResult(a,prefix+id);}
    private ToolResult cancelJob(JsonNode p)throws Exception{String id=require(p,"job_id","cancel_job");post("/api/knowledge-graph/builder/jobs/"+enc(id)+"/cancel",null);return ToolResult.success("cancel_job: "+id,"Cancelled extraction job: "+id,Map.of("jobId",id));}
    private ToolResult acceptProposal(JsonNode p)throws Exception{String id=require(p,"proposal_id","accept_proposal");post("/api/knowledge-graph/builder/proposals/"+enc(id)+"/accept?reviewedBy=mcp-tool",null);return ToolResult.success("accept_proposal: "+id,"Accepted proposal: "+id,Map.of("proposalId",id));}
    private ToolResult rejectProposal(JsonNode p)throws Exception{String id=require(p,"proposal_id","reject_proposal");ObjectNode b=objectMapper.createObjectNode().put("reviewedBy","mcp-tool");if(!p.path("rejection_reason").asText("").isBlank())b.put("reason",p.path("rejection_reason").asText());post("/api/knowledge-graph/builder/proposals/"+enc(id)+"/reject",b);return ToolResult.success("reject_proposal: "+id,"Rejected proposal: "+id,Map.of("proposalId",id));}
    private ToolResult manualProposal(JsonNode p)throws Exception{for(String f:new String[]{"subject_name","subject_type","predicate_name","object_name","object_type"})require(p,f,"manual_proposal");long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for manual_proposal");ObjectNode b=objectMapper.createObjectNode().put("factSheetId",id).put("subjectName",p.path("subject_name").asText()).put("subjectType",p.path("subject_type").asText()).put("predicateName",p.path("predicate_name").asText()).put("objectName",p.path("object_name").asText()).put("objectType",p.path("object_type").asText()).put("autoAccept",p.path("auto_accept").asBoolean(false));if(!p.path("item_description").asText("").isBlank())b.put("description",p.path("item_description").asText());return postResult("manual_proposal","/api/knowledge-graph/builder/proposals/manual",b);}
    private ToolResult applyPreset(JsonNode p)throws Exception{String id=require(p,"preset_id","apply_preset");return postResult("apply_preset: "+id,"/api/graph-extraction/schema-presets/"+enc(id)+"/apply",null);}
    private ToolResult bindOntology(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for bind_ontology");String schema=require(p,"ontology_schema_id","bind_ontology");String path="/api/process/ontology/binding?factSheetId="+id+"&ontologySchemaId="+enc(schema);if(p.path("ontology_version").asInt(0)>0)path+="&ontologyVersion="+p.path("ontology_version").asInt();HttpRequest r=HttpRequest.newBuilder().uri(URI.create(baseUrl+path)).header("Content-Type","application/json").header("Accept","application/json").PUT(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build();HttpResponse<String> h=httpClient.send(r,HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200&&h.statusCode()!=204)throw new ToolExecutionException("HTTP "+h.statusCode()+": "+extractError(h.body()));return ToolResult.success("bind_ontology","Bound ontology "+schema+" to fact sheet "+id,Map.of("factSheetId",id,"ontologySchemaId",schema));}
    private ToolResult unbindOntology(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for unbind_ontology");HttpRequest r=HttpRequest.newBuilder().uri(URI.create(baseUrl+"/api/process/ontology/binding?factSheetId="+id)).DELETE().timeout(Duration.ofSeconds(30)).build();HttpResponse<String> h=httpClient.send(r,HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200&&h.statusCode()!=204)throw new ToolExecutionException("HTTP "+h.statusCode()+": "+extractError(h.body()));return ToolResult.success("unbind_ontology","Removed ontology binding from fact sheet "+id,Map.of("factSheetId",id));}
    private ToolResult reasoningLayers(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for reasoning_layers");JsonNode r=get("/api/graph/"+id+"/reasoning-layers"),s=r.path("statistics");int n=s.path("nodeCount").asInt(r.path("nodes").size()),e=s.path("edgeCount").asInt(r.path("edges").size());String o="Reasoning layers for fact sheet "+id+":\n\n- Nodes: "+n+"\n- Edges: "+e+"\n- Ontology overlays: "+s.path("ontologyCount").asInt()+"\n- PSL overlays: "+s.path("pslCount").asInt()+"\n- MEBN overlays: "+s.path("mebnCount").asInt()+"\n- Provenance overlays: "+s.path("provenanceCount").asInt()+"\n- Opinion overlays: "+s.path("opinionCount").asInt()+"\n- Neural score overlays: "+s.path("neuralScoreCount").asInt()+"\n\n### MEBN posteriors\n\n### Neural edge scores\n";Map<String,Object> m=new LinkedHashMap<>();m.put("factSheetId",id);m.put("nodeCount",n);m.put("edgeCount",e);m.put("statistics",objectMapper.convertValue(s,Map.class));m.put("reasoningLayers",objectMapper.convertValue(r,Map.class));return ToolResult.success("reasoning_layers: "+id,o,m);}
    private ToolResult listFactSheets()throws Exception{JsonNode r=get("/api/fact-sheets");StringBuilder o=new StringBuilder("Fact Sheets:\n\n");if(r.isArray()){for(JsonNode s:r){long id=s.path("id").asLong(0);String n=s.path("name").asText("Unnamed");boolean active=s.path("active").asBoolean(s.path("isActive").asBoolean(false));o.append("- **").append(n).append("** id=").append(id);if(active)o.append(" [ACTIVE]");o.append(" (").append(s.path("factCount").asLong(0)).append(" facts)\n");}return ToolResult.success("list_fact_sheets",o.toString(),Map.of("count",r.size()));}return ToolResult.success("list_fact_sheets",o.append(r.toPrettyString()).toString(),Map.of());}
    private ToolResult getFactSheet(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for get_fact_sheet");return ToolResult.success("get_fact_sheet: "+id,get("/api/fact-sheets/"+id).toPrettyString(),Map.of("id",id));}
    private ToolResult getActiveFactSheet()throws Exception{return ToolResult.success("get_active_fact_sheet",get("/api/fact-sheets/active").toPrettyString(),Map.of());}
    private ToolResult createFactSheet(JsonNode p)throws Exception{String n=p.path("name").asText(p.path("title").asText(""));if(n.isBlank())return ToolResult.error("name (or title) is required for create_fact_sheet");return postResult("create_fact_sheet: "+n,"/api/fact-sheets",objectMapper.createObjectNode().put("name",n));}
    private ToolResult activateFactSheet(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for activate_fact_sheet");return postResult("activate_fact_sheet: "+id,"/api/fact-sheets/"+id+"/activate",null);}
    private ToolResult snapshotCreate(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for create_snapshot");ObjectNode b=objectMapper.createObjectNode().put("factSheetId",id);if(p.hasNonNull("label"))b.put("label",p.path("label").asText());JsonNode r=post("/api/graph/snapshots",b);return ToolResult.success("create_snapshot: "+id,"Created snapshot for fact sheet "+id+":\n  snapshotId: "+r.path("snapshotId").asText()+"\n  createdAt: "+r.path("createdAt").asText()+"\n  sizeBytes: "+r.path("sizeBytes").asLong(),Map.of("factSheetId",id,"snapshotId",r.path("snapshotId").asText()));}
    private ToolResult snapshotList(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for list_snapshots");JsonNode r=get("/api/graph/snapshots?factSheetId="+id);String o="Snapshots for fact sheet "+id+":\n\n"+(r.isArray()&&r.isEmpty()?"No snapshots found.\n":r.toPrettyString());return ToolResult.success("list_snapshots: "+id,o,Map.of("factSheetId",id,"count",r.isArray()?r.size():0));}
    private ToolResult snapshotRestore(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for restore_snapshot");String s=p.path("snapshot_id").asText("");if(s.isBlank())return ToolResult.error("snapshot_id is required for restore_snapshot");JsonNode r=post("/api/graph/snapshots/"+enc(s)+"/restore",objectMapper.createObjectNode().put("factSheetId",id));return ToolResult.success("restore_snapshot: "+s,"Restored snapshot "+s+" into fact sheet "+id+":\n  pre-restore safety snapshot: "+r.path("preRestoreSnapshotId").asText()+"\nGraph is immediately reasoning-ready (fact store re-projected, graph-build event fired).",Map.of("factSheetId",id,"snapshotId",s));}
    private ToolResult snapshotDelete(JsonNode p)throws Exception{long id=p.path("fact_sheet_id").asLong(0);if(id<=0)return ToolResult.error("fact_sheet_id is required for delete_snapshot");String s=p.path("snapshot_id").asText("");if(s.isBlank())return ToolResult.error("snapshot_id is required for delete_snapshot");delete("/api/graph/snapshots/"+enc(s)+"?factSheetId="+id);return ToolResult.success("delete_snapshot: "+s,"Deleted snapshot "+s+" from fact sheet "+id,Map.of("factSheetId",id,"snapshotId",s,"deleted",true));}
    private ToolResult listPredicates(JsonNode p)throws Exception{String u="/api/kb-grounding/predicates";if(p.path("fact_sheet_id").asLong(0)>0)u+="?factSheetId="+p.path("fact_sheet_id").asLong();return getResult("list_predicates",u);}

    private void formatNodeList(StringBuilder sb, JsonNode nodes) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            sb.append("- **").append(node.path("title").asText("Untitled")).append("** (")
                    .append(node.path("type").asText(node.path("nodeType").asText(""))).append(")");
            String description=node.path("description").asText("");
            if (!description.isEmpty()) sb.append(": ").append(description);
            String id=node.path("nodeId").asText(node.path("id").asText(""));
            if (!id.isEmpty()) sb.append(" ID=").append(id);
            sb.append("\n");
        }
    }
    private void formatDocResults(StringBuilder sb, JsonNode results) {
        if (results == null || !results.isArray()) return;
        for (JsonNode doc : results) {
            sb.append("- **").append(doc.path("title").asText("Untitled")).append("** (")
                    .append(doc.path("type").asText("")).append(")\n  ID: ")
                    .append(doc.path("documentId").asText(doc.path("id").asText("")))
                    .append(" | Source: ").append(doc.path("source").asText("")).append("\n");
            String description=doc.path("description").asText("");
            if (!description.isEmpty()) sb.append("  ").append(description).append("\n");
        }
    }
    private static void appendIfPresent(StringBuilder sb, String label, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        String text=value.isTextual() ? value.asText() : value.toString();
        if (!text.isEmpty()) sb.append("- ").append(label).append(": ").append(text).append("\n");
    }

    private String require(JsonNode p,String field,String action)throws ToolExecutionException{String v=p.path(field).asText("");if(v.isEmpty())throw new ToolExecutionException(field+" is required for '"+action+"'");return v;}
    private static List<String> csv(String v){List<String> out=new ArrayList<>();for(String x:v.split(","))if(!x.isBlank())out.add(x.trim());return out;}
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private static void string(ObjectNode p,String n,String d){p.putObject(n).put("type","string").put("description",d);}
    private static void integer(ObjectNode p,String n,String d){p.putObject(n).put("type","integer").put("description",d);}
    private static void number(ObjectNode p,String n,String d){p.putObject(n).put("type","number").put("description",d);}
    private static void bool(ObjectNode p,String n,String d){p.putObject(n).put("type","boolean").put("description",d);}
    private JsonNode get(String path)throws Exception{HttpRequest r=HttpRequest.newBuilder().uri(URI.create(baseUrl+path)).header("Accept","application/json").GET().timeout(Duration.ofSeconds(30)).build();HttpResponse<String> h=httpClient.send(r,HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200)throw new ToolExecutionException("HTTP "+h.statusCode()+": "+extractError(h.body()));return parse(h.body());}
    private JsonNode post(String path,JsonNode body)throws Exception{HttpRequest.Builder b=HttpRequest.newBuilder().uri(URI.create(baseUrl+path)).header("Content-Type","application/json").header("Accept","application/json").timeout(Duration.ofSeconds(60));b.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));HttpResponse<String> h=httpClient.send(b.build(),HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200&&h.statusCode()!=201)throw new ToolExecutionException("HTTP "+h.statusCode()+": "+extractError(h.body()));return parse(h.body());}
    private void delete(String path)throws Exception{HttpRequest r=HttpRequest.newBuilder().uri(URI.create(baseUrl+path)).header("Accept","application/json").DELETE().timeout(Duration.ofSeconds(30)).build();HttpResponse<String> h=httpClient.send(r,HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200&&h.statusCode()!=204)throw new ToolExecutionException("HTTP "+h.statusCode()+": "+extractError(h.body()));}
    private JsonNode parse(String body)throws Exception{if(body==null||body.isBlank())return objectMapper.createObjectNode();try{return objectMapper.readTree(body);}catch(Exception e){return objectMapper.getNodeFactory().textNode(body);}}
    private String extractError(String body){try{JsonNode j=objectMapper.readTree(body);if(j.has("message"))return j.path("message").asText();if(j.has("error"))return j.path("error").asText();}catch(Exception ignored){}return body==null?"":body;}
}
