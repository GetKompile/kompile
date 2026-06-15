/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.diagram.service;

import ai.kompile.app.diagram.domain.DiagramSession;
import ai.kompile.app.diagram.repository.DiagramSessionRepository;
import ai.kompile.app.mcp.McpToolRegistry;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates diagram generation by delegating to an agent via SSE streaming.
 * The agent is given a system prompt that instructs it to use available MCP tools
 * (graph search, RAG, text search) to explore fact sheet data, identify business
 * processes, and produce a Mermaid flowchart.
 */
@Service
public class DiagramGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DiagramGenerationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DiagramSessionRepository sessionRepository;
    private final AgentChatService agentChatService;
    private final MermaidProcessConverter mermaidConverter;
    private final ServerPortService serverPortService;

    @Autowired(required = false)
    private ProcessEngineService processEngineService;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private McpToolRegistry mcpToolRegistry;

    public DiagramGenerationService(DiagramSessionRepository sessionRepository,
                                     AgentChatService agentChatService,
                                     MermaidProcessConverter mermaidConverter,
                                     ServerPortService serverPortService) {
        this.sessionRepository = sessionRepository;
        this.agentChatService = agentChatService;
        this.mermaidConverter = mermaidConverter;
        this.serverPortService = serverPortService;
    }

    /**
     * Start a diagram generation session. Creates a DiagramSession record,
     * augments the user prompt with diagram instructions, and delegates to
     * the existing AgentChatService for SSE streaming. The SSE events flow
     * directly to the client. The client captures the transcript locally and
     * POSTs it back via the finalize endpoint.
     */
    public DiagramSession startGeneration(String userPrompt, String agentName,
                                           Long factSheetId, SseEmitter emitter) {
        DiagramSession session = DiagramSession.builder()
                .prompt(userPrompt)
                .agentName(agentName)
                .factSheetId(factSheetId)
                .status("RUNNING")
                .build();
        session = sessionRepository.save(session);
        final Long sessionId = session.getId();

        String augmentedPrompt = buildDiagramPrompt(userPrompt, factSheetId);

        AgentChatRequest chatRequest = new AgentChatRequest();
        chatRequest.setMessage(augmentedPrompt);
        chatRequest.setAgentName(agentName);
        chatRequest.setSkipPermissions(true);
        chatRequest.setInjectMcpTools(true);
        chatRequest.setEnableRag(true);
        chatRequest.setEnableGraphRag(true);
        chatRequest.setIncludeKeywordSearch(true);
        chatRequest.setIncludeSemanticSearch(true);
        chatRequest.setRagMaxResults(10);
        chatRequest.setGraphRagMaxResults(10);
        chatRequest.setTimeoutSeconds(600);

        agentChatService.executeChat(chatRequest, emitter);

        return session;
    }

    /**
     * Finalize a session after the agent completes. Called by the client
     * with the full transcript and extracted mermaid code.
     */
    public Optional<DiagramSession> finalizeSession(Long sessionId, String transcriptJson,
                                                      String mermaidCode, String title,
                                                      String description, String sourcesJson) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setTranscriptJson(transcriptJson);
            session.setMermaidCode(mermaidCode);
            session.setTitle(title);
            session.setDescription(description);
            session.setSourcesJson(sourcesJson);
            session.setStatus(mermaidCode != null && !mermaidCode.isBlank() ? "COMPLETED" : "COMPLETED_NO_DIAGRAM");
            session.setCompletedAt(Instant.now());
            return sessionRepository.save(session);
        });
    }

    /**
     * Mark a session as failed.
     */
    public Optional<DiagramSession> failSession(Long sessionId, String errorMessage) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setStatus("FAILED");
            session.setErrorMessage(errorMessage);
            session.setCompletedAt(Instant.now());
            return sessionRepository.save(session);
        });
    }

    public List<DiagramSession> listSessions(Long factSheetId) {
        if (factSheetId != null) {
            return sessionRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId);
        }
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<DiagramSession> getSession(Long sessionId) {
        return sessionRepository.findById(sessionId);
    }

    public void deleteSession(Long sessionId) {
        sessionRepository.deleteById(sessionId);
    }

    public Optional<DiagramSession> updateTitle(Long sessionId, String title) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setTitle(title);
            return sessionRepository.save(session);
        });
    }

    /**
     * Update just the mermaid code of a session (e.g., after manual editing).
     */
    public Optional<DiagramSession> updateMermaidCode(Long sessionId, String mermaidCode) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setMermaidCode(mermaidCode);
            return sessionRepository.save(session);
        });
    }

    /**
     * Converts a diagram session's Mermaid code into a ProcessDefinition,
     * creates it via the process engine, and links it back to the session.
     *
     * @param sessionId the diagram session to convert
     * @return the created ProcessDefinition
     * @throws IllegalStateException if the process engine is not available
     * @throws IllegalArgumentException if the session has no mermaid code
     */
    public ProcessDefinition convertToProcess(Long sessionId) {
        if (processEngineService == null) {
            throw new IllegalStateException("ProcessEngineService is not available");
        }

        DiagramSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Diagram session not found: " + sessionId));

        if (session.getMermaidCode() == null || session.getMermaidCode().isBlank()) {
            throw new IllegalArgumentException("Diagram session " + sessionId + " has no Mermaid code to convert");
        }

        String processName = session.getTitle() != null ? session.getTitle() : "Process from Diagram #" + sessionId;
        ProcessDefinition draft = mermaidConverter.fromMermaid(session.getMermaidCode(), processName);

        ProcessDefinition created = processEngineService.createProcess(draft);
        session.setProcessDefinitionId(created.getId());
        sessionRepository.save(session);

        log.info("Converted diagram session {} to process definition {}", sessionId, created.getId());
        return created;
    }

    /**
     * Renders a ProcessDefinition as a Mermaid diagram.
     *
     * @param processDefinitionId the process to render
     * @return the Mermaid flowchart source
     */
    public String renderProcessAsMermaid(String processDefinitionId) {
        if (processEngineService == null) {
            throw new IllegalStateException("ProcessEngineService is not available");
        }

        ProcessDefinition def = processEngineService.getProcess(processDefinitionId, -1);
        if (def == null) {
            throw new IllegalArgumentException("Process definition not found: " + processDefinitionId);
        }
        return mermaidConverter.toMermaid(def);
    }

    /**
     * Gets the MermaidProcessConverter for direct use.
     */
    public MermaidProcessConverter getConverter() {
        return mermaidConverter;
    }

    /**
     * Get a diagram session by its linked process definition ID.
     */
    public Optional<DiagramSession> getByProcessDefinitionId(String processDefinitionId) {
        return sessionRepository.findByProcessDefinitionId(processDefinitionId);
    }

    /**
     * Get structured provenance citations for a diagram session.
     * Attempts to parse sourcesJson as a JSON array of structured citations.
     * Falls back to a single legacy entry for raw-text sessions.
     * Hydrates each entry with KG node data when nodeId is available.
     */
    public List<Map<String, Object>> getStructuredProvenance(Long sessionId) {
        Optional<DiagramSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return List.of();
        }

        DiagramSession session = sessionOpt.get();
        String sourcesJson = session.getSourcesJson();
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }

        // Try parsing as structured JSON array
        try {
            List<Map<String, Object>> parsed = MAPPER.readValue(sourcesJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (parsed != null && !parsed.isEmpty()) {
                return hydrateProvenanceCitations(parsed);
            }
        } catch (Exception e) {
            // Not JSON — fall through to legacy handling
            log.debug("sourcesJson is not structured JSON for session {}, using legacy mode", sessionId);
        }

        // Legacy fallback: raw text sources
        Map<String, Object> legacyEntry = new LinkedHashMap<>();
        legacyEntry.put("nodeId", null);
        legacyEntry.put("sourceId", null);
        legacyEntry.put("title", "Unstructured sources");
        legacyEntry.put("location", null);
        legacyEntry.put("extractedText", sourcesJson);
        legacyEntry.put("discoverySource", "LLM_GENERATED");
        legacyEntry.put("entityType", null);
        legacyEntry.put("sourceType", null);
        legacyEntry.put("contentPreview", sourcesJson.length() > 500
                ? sourcesJson.substring(0, 500) + "..." : sourcesJson);
        legacyEntry.put("confidence", 0.5);
        legacyEntry.put("documentTitle", null);
        legacyEntry.put("sourcePathOrUrl", null);
        return List.of(legacyEntry);
    }

    /**
     * Hydrate structured provenance citations with KG node data.
     */
    private List<Map<String, Object>> hydrateProvenanceCitations(List<Map<String, Object>> citations) {
        List<Map<String, Object>> result = new ArrayList<>(citations.size());
        for (Map<String, Object> citation : citations) {
            Map<String, Object> hydrated = new LinkedHashMap<>(citation);
            Object nodeIdObj = citation.get("nodeId");
            if (nodeIdObj instanceof String nodeId && !nodeId.isBlank() && knowledgeGraphService != null) {
                try {
                    Optional<GraphNode> nodeOpt = knowledgeGraphService.getNode(nodeId);
                    if (nodeOpt.isPresent()) {
                        GraphNode node = nodeOpt.get();
                        if (hydrated.get("title") == null && node.getTitle() != null) {
                            hydrated.put("title", node.getTitle());
                        }
                        hydrated.put("sourceType", node.getSourceType());
                        hydrated.put("sourcePathOrUrl", node.getPathOrUrl());
                        if (node.getContentPreview() != null) {
                            hydrated.put("contentPreview", node.getContentPreview());
                        }
                        if (node.getDescription() != null && hydrated.get("documentTitle") == null) {
                            hydrated.put("documentTitle", node.getTitle());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to hydrate KG node {}: {}", nodeId, e.getMessage());
                }
            }
            // Ensure discoverySource has a default
            if (hydrated.get("discoverySource") == null) {
                hydrated.put("discoverySource", "LLM_GENERATED");
            }
            // Ensure confidence has a default
            if (hydrated.get("confidence") == null) {
                hydrated.put("confidence", 0.5);
            }
            result.add(hydrated);
        }
        return result;
    }

    /**
     * Gathers live operating context: instance location, KG availability,
     * graph statistics. Used to inject pre-flight data into the agent prompt
     * so it knows what is available before making any tool calls.
     */
    private Map<String, Object> gatherOperatingContext(Long factSheetId) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Instance location
        String baseUrl = serverPortService.getBaseUrl();
        ctx.put("baseUrl", baseUrl);
        ctx.put("mcpUrl", serverPortService.getMcpApiUrl());
        ctx.put("port", serverPortService.getActualPort());

        // Knowledge graph availability
        boolean kgAvailable = knowledgeGraphService != null;
        ctx.put("knowledgeGraphAvailable", kgAvailable);

        if (kgAvailable) {
            try {
                Map<String, Object> stats = knowledgeGraphService.getGraphStatistics();
                ctx.put("graphStatistics", stats);
            } catch (Exception e) {
                log.debug("Failed to gather graph statistics: {}", e.getMessage());
                ctx.put("graphStatistics", Map.of("error", e.getMessage()));
            }

            if (factSheetId != null) {
                try {
                    List<GraphNode> sources = knowledgeGraphService.getAllSources();
                    long factSheetSources = sources != null ? sources.stream()
                            .filter(s -> factSheetId.equals(s.getFactSheetId()))
                            .count() : 0L;
                    ctx.put("factSheetSourceCount", factSheetSources);
                } catch (Exception e) {
                    log.debug("Failed to count fact sheet sources: {}", e.getMessage());
                }
            }
        }

        // Process engine availability
        ctx.put("processEngineAvailable", processEngineService != null);

        return ctx;
    }

    private String buildDiagramPrompt(String userPrompt, Long factSheetId) {
        Map<String, Object> ctx = gatherOperatingContext(factSheetId);
        String baseUrl = (String) ctx.get("baseUrl");
        boolean kgAvailable = Boolean.TRUE.equals(ctx.get("knowledgeGraphAvailable"));

        StringBuilder sb = new StringBuilder();

        // =====================================================================
        // ROLE & AUTONOMY
        // =====================================================================
        sb.append("# Autonomous Business Process Discovery Agent\n\n");

        sb.append("You are an autonomous business process analyst connected to a Kompile RAG instance. ");
        sb.append("You have full access to all MCP tools on this server. ");
        sb.append("Your job is to independently discover business processes from crawled and indexed documents, ");
        sb.append("then produce a Mermaid flowchart that maps to an executable process definition.\n\n");

        sb.append("**You are fully autonomous.** Do not ask the user for clarification, confirmation, or ");
        sb.append("additional input. Make all decisions yourself based on what you find in the data. ");
        sb.append("If data is ambiguous, state your interpretation and proceed. ");
        sb.append("If data is missing, produce the best diagram you can and note the gaps.\n\n");

        sb.append("**IMPORTANT: Context above this message.** If you see `## Knowledge Graph Context` or ");
        sb.append("`## Retrieved Context` sections above, those are pre-fetched RAG results for your query. ");
        sb.append("Use them as a starting point but DO NOT stop there — call tools to get deeper data.\n\n");

        // =====================================================================
        // OPERATING ENVIRONMENT
        // =====================================================================
        sb.append("## Operating Environment\n\n");

        sb.append("- **Kompile Base URL**: `").append(baseUrl).append("`\n");
        sb.append("- **MCP Server**: `").append(ctx.get("mcpUrl")).append("` (tools auto-discovered via `--mcp-server` flag)\n");
        sb.append("- **Port**: ").append(ctx.get("port")).append("\n\n");

        // Pre-flight status table
        sb.append("### Pre-flight Status\n\n");
        sb.append("| Component | Status |\n");
        sb.append("|-----------|--------|\n");
        sb.append("| Knowledge Graph | ").append(kgAvailable ? "AVAILABLE" : "UNAVAILABLE").append(" |\n");
        sb.append("| Process Engine | ").append(Boolean.TRUE.equals(ctx.get("processEngineAvailable")) ? "AVAILABLE" : "UNAVAILABLE").append(" |\n");

        Object statsObj = ctx.get("graphStatistics");
        if (statsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = new LinkedHashMap<>((Map<String, Object>) statsObj);
            if (stats.containsKey("error")) {
                sb.append("| Graph Statistics | ERROR: ").append(stats.get("error")).append(" |\n");
            } else {
                for (Map.Entry<String, Object> entry : stats.entrySet()) {
                    sb.append("| Graph: ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
                }
            }
        }

        if (factSheetId != null && ctx.containsKey("factSheetSourceCount")) {
            sb.append("| Fact Sheet Sources | ").append(ctx.get("factSheetSourceCount")).append(" |\n");
        }
        sb.append("\n");

        // Warnings
        if (!kgAvailable) {
            sb.append("**WARNING**: Knowledge graph is not available. Graph tools (`graph_*`) will fail. ");
            sb.append("Fall back to `rag_query` and `knowledge_search` only.\n\n");
        }
        if (statsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) statsObj;
            Object totalNodes = stats.get("totalNodes");
            if (totalNodes instanceof Number && ((Number) totalNodes).longValue() == 0) {
                sb.append("**WARNING**: The knowledge graph is empty (0 nodes). The graph has not been built yet. ");
                sb.append("Only `rag_query` and `knowledge_search` (vector/keyword backends) will return results. ");
                sb.append("Graph tools will return empty results.\n\n");
            }
        }

        // =====================================================================
        // TASK
        // =====================================================================
        sb.append("## Your Task\n\n");
        sb.append(userPrompt).append("\n\n");

        if (factSheetId != null) {
            sb.append("**Fact Sheet ID: ").append(factSheetId).append("**\n");
            sb.append("All searches are scoped to this fact sheet. Each fact sheet has its own vector store, ");
            sb.append("keyword index, and knowledge graph built from its crawled documents.\n\n");
        }

        // =====================================================================
        // KOMPILE DATA MODEL — how crawled data is organized
        // =====================================================================
        sb.append("## How Crawled Data Is Organized\n\n");

        sb.append("Kompile organizes data in a hierarchy. Understanding this helps you navigate effectively:\n\n");

        sb.append("```\n");
        sb.append("FactSheet (named collection — one is active at a time)\n");
        sb.append("  └── Facts (individual files/URLs/text added to the sheet)\n");
        sb.append("       └── Chunks (text segments from each document)\n");
        sb.append("            ├── Keyword Index (BM25 — exact term matching)\n");
        sb.append("            ├── Vector Store (semantic embeddings — meaning-based search)\n");
        sb.append("            └── Knowledge Graph (entities + relationships extracted from chunks)\n");
        sb.append("                 Nodes: SOURCE → DOCUMENT → SNIPPET → ENTITY\n");
        sb.append("```\n\n");

        sb.append("**Three search backends** serve the same data differently:\n");
        sb.append("1. **Keyword index** — fast exact/BM25 matching. Good for names, codes, specific terms.\n");
        sb.append("2. **Vector store** — semantic similarity. Good for conceptual queries (\"approval workflow\").\n");
        sb.append("3. **Knowledge graph** — entity/relationship traversal. Good for discovering connections between people, ");
        sb.append("departments, documents, and processes.\n\n");

        sb.append("`knowledge_search` fans out across ALL three backends in one call. ");
        sb.append("`rag_query` uses the vector store only but returns richer metadata (table content, source filenames, scores).\n\n");

        // =====================================================================
        // KEY TOOL USAGE — what to call and what you get back
        // =====================================================================
        sb.append("## Key Tools and What They Return\n\n");

        sb.append("Your MCP tools are auto-discovered from the server. Here is how to use the most important ones ");
        sb.append("for process discovery:\n\n");

        sb.append("### Primary Discovery Tools\n\n");

        sb.append("**`knowledge_search`** — Your primary tool. Fans out to all search backends in parallel.\n");
        sb.append("- Input: `{\"query\": \"...\", \"topic\": \"optional filename filter\"}`\n");
        sb.append("- Returns: `{results: [{content, source, relevance}], graph_context, summary}`\n");
        sb.append("- `graph_context` contains entity names, types, and relationship triples when graph is available\n");
        sb.append("- Use multiple queries: process names, department names, action verbs (approve, submit, review, escalate)\n\n");

        sb.append("**`knowledge_status`** — Check what backends are active before starting.\n");
        sb.append("- Returns: `{available, backends: [...], sources_count, documents_indexed, graph_entities}`\n");
        sb.append("- If `available: false`, no data is indexed — report this and stop.\n\n");

        sb.append("**`rag_query`** — Single-retriever query with rich metadata.\n");
        sb.append("- Input: `{\"query\": \"...\", \"maxResults\": 5}`\n");
        sb.append("- Returns: `{retrieved_documents: [...], metadata: [{source_filename, page_number, content_type, score, ");
        sb.append("table_row_count, table_headers}]}`\n");
        sb.append("- For `content_type=table`: returns full table content, not just a summary — use this to extract ");
        sb.append("structured data from spreadsheets and decision tables\n");
        sb.append("- If a result is truncated, use `fetch_result` with the returned `result_id` to get full content\n\n");

        sb.append("### Fact Sheet Tools\n\n");
        sb.append("**`list_fact_sheets`** — List all fact sheets with `{id, name, description, active}`\n");
        sb.append("**`get_fact_sheet`** — Input: `{\"id\": N}` — Get details of a specific fact sheet\n");
        sb.append("**`get_active_fact_sheet`** — Get the currently active fact sheet\n\n");

        if (kgAvailable) {
            sb.append("### Knowledge Graph Tools\n\n");
            sb.append("**`graph_get_overview`** — Returns source nodes (up to 20) with document counts and topic categories. CALL THIS FIRST.\n");
            sb.append("**`graph_search_nodes`** — Input: `{\"query\": \"...\", \"nodeType\": \"source|document|snippet|entity\", \"maxResults\": N}`\n");
            sb.append("**`graph_search_by_entity`** — Input: `{\"entityName\": \"...\"}` — Find documents mentioning a specific entity\n");
            sb.append("**`graph_get_document_entities`** — Input: `{\"documentId\": \"...\"}` — List entities extracted from a document\n");
            sb.append("**`graph_get_related_documents`** — Input: `{\"documentId\": \"...\", \"relationshipType\": \"entity|similarity|hierarchical|any\"}`\n");
            sb.append("**`graph_find_connected`** — Input: `{\"nodeId\": \"...\", \"depth\": 1-3}` — Traverse graph relationships\n");
            sb.append("**`graph_rag_query`** — Input: `{\"query\": \"...\", \"searchType\": \"LOCAL|GLOBAL\"}` — GraphRAG with entity context\n\n");
        } else {
            sb.append("### Knowledge Graph Tools — UNAVAILABLE\n\n");
            sb.append("Graph tools (`graph_*`) are not available in this session. ");
            sb.append("Use `knowledge_search` and `rag_query` instead.\n\n");
        }

        // =====================================================================
        // COMPLETE MCP TOOL CATALOG — dynamically from registry
        // =====================================================================
        sb.append("## Complete MCP Tool Catalog\n\n");

        sb.append("All tools below are available via the MCP server. ");
        sb.append("If a tool call returns `isError=true`, the tool is unavailable or the input was invalid.\n\n");

        appendToolCatalog(sb, kgAvailable);

        // =====================================================================
        // REST API FALLBACK
        // =====================================================================
        sb.append("## REST API Reference (Fallback)\n\n");
        sb.append("Use these only if MCP tools fail or return insufficient data. ");
        sb.append("All endpoints are on `").append(baseUrl).append("`.\n\n");

        sb.append("### Fact Sheets & Crawled Data\n");
        sb.append("- `GET /api/fact-sheets` — List all fact sheets\n");
        sb.append("- `GET /api/fact-sheets/active` — Active fact sheet\n");
        if (factSheetId != null) {
            sb.append("- `GET /api/fact-sheets/").append(factSheetId).append("/facts` — All crawled documents in this sheet\n");
            sb.append("- `GET /api/fact-sheets/").append(factSheetId).append("/indexing-stats` — Indexing progress (total, indexed, unindexed)\n");
        }
        sb.append("- `GET /api/fact-sheets/active/facts/search?q=QUERY` — Search facts by keyword\n");
        sb.append("- `GET /api/indexer/status` — Index health, vector store status, document counts\n\n");

        sb.append("### Knowledge Graph\n");
        sb.append("- `GET /api/knowledge-graph/statistics` — Node/edge/entity counts\n");
        sb.append("- `GET /api/knowledge-graph/nodes?type=TYPE&query=Q&limit=50` — Search nodes (type: source, document, snippet, entity)\n");
        sb.append("- `GET /api/knowledge-graph/nodes/{nodeId}/children` — Child nodes\n");
        sb.append("- `GET /api/knowledge-graph/nodes/{nodeId}/connected?depth=2` — Traverse connections\n");
        sb.append("- `GET /api/knowledge-graph/topics` — All topics\n");
        if (factSheetId != null) {
            sb.append("- `GET /api/fact-sheets/").append(factSheetId).append("/graph/statistics` — Graph stats for this sheet\n");
            sb.append("- `GET /api/fact-sheets/").append(factSheetId).append("/graph/search?query=Q&limit=20` — Search this sheet's graph\n");
            sb.append("- `GET /api/fact-sheets/").append(factSheetId).append("/graph/concepts/top?limit=20` — Top concepts\n");
        }
        sb.append("\n");

        sb.append("### Direct Search\n");
        sb.append("- `POST /api/rag/query` body: `{\"query\": \"...\"}` — RAG query\n");
        sb.append("- `POST /api/graph-rag/search` body: `{\"query\": \"...\", \"searchType\": \"LOCAL\"|\"GLOBAL\"}` — GraphRAG\n");
        sb.append("- `GET /api/graph-rag/info` — GraphRAG availability\n\n");

        // =====================================================================
        // AUTONOMOUS RESEARCH WORKFLOW
        // =====================================================================
        sb.append("## Autonomous Research Workflow\n\n");

        sb.append("Execute these steps in order. Do NOT skip steps or ask for confirmation.\n\n");

        sb.append("### Step 1: Assess data availability\n");
        sb.append("Call `knowledge_status` to confirm which backends are active and how many documents are indexed. ");
        sb.append("If `available: false` or `documents_indexed: 0`, report that no data has been crawled and stop.\n");
        if (kgAvailable) {
            sb.append("Call `graph_get_overview` to see what sources and topics exist in the graph.\n");
        }
        sb.append("\n");

        sb.append("### Step 2: Broad discovery — find all relevant content\n");
        sb.append("Run at least 3-5 different `knowledge_search` queries to cast a wide net:\n");
        sb.append("- Query the user's task directly (e.g., \"invoice approval process\")\n");
        sb.append("- Search for action verbs: \"approve\", \"submit\", \"review\", \"escalate\", \"notify\", \"validate\"\n");
        sb.append("- Search for roles: \"manager\", \"finance\", \"compliance\", \"approver\"\n");
        sb.append("- Search for document types: \"policy\", \"procedure\", \"SOP\", \"workflow\", \"checklist\"\n");
        sb.append("- Use `rag_query` for queries where you need table data or source file metadata\n\n");

        sb.append("### Step 3: Deep dive — extract process details\n");
        sb.append("For each relevant document or entity found in Step 2:\n");
        if (kgAvailable) {
            sb.append("- Call `graph_get_document_entities` to identify actors, systems, and objects involved\n");
            sb.append("- Call `graph_search_by_entity` to find other documents mentioning the same actors\n");
            sb.append("- Call `graph_get_related_documents` to trace document relationships\n");
            sb.append("- Call `graph_find_connected` with depth 2-3 to explore entity neighborhoods\n");
        }
        sb.append("- Call `rag_query` with specific questions to extract step-by-step procedures\n");
        sb.append("- Call `knowledge_search` with entity names found in earlier results to discover cross-references\n");
        sb.append("- If `rag_query` returns `content_type=table`, examine the table content for decision matrices or checklists\n\n");

        sb.append("### Step 4: Synthesize findings into a process model\n");
        sb.append("From your research, identify and list:\n");
        sb.append("- **Actors**: People, roles, departments, systems involved\n");
        sb.append("- **Steps**: Actions performed in sequence or parallel\n");
        sb.append("- **Decisions**: Branch points where the process diverges\n");
        sb.append("- **Controls**: Approval gates, compliance checks, SOX controls\n");
        sb.append("- **Data flows**: Documents, spreadsheets, forms passed between steps\n");
        sb.append("- **Phases**: Logical groupings (e.g., Initiation, Review, Execution, Closeout)\n\n");

        sb.append("### Step 5: Produce the Mermaid diagram and structured output\n");
        sb.append("Convert your findings into the Mermaid flowchart and output format specified below.\n\n");

        // =====================================================================
        // MERMAID DIAGRAM REQUIREMENTS
        // =====================================================================
        sb.append("## Mermaid Diagram Requirements\n\n");

        sb.append("The diagram MUST use `flowchart TD` syntax. Node shapes map to executable step types ");
        sb.append("when the diagram is converted to a process definition. Use the correct shape for each step:\n\n");

        sb.append("| Shape | Syntax | Step Type | Use For |\n");
        sb.append("|-------|--------|-----------|----------|\n");
        sb.append("| Rectangle | `A[Label]` | AUTO | Automated steps, computations, data transforms |\n");
        sb.append("| Stadium | `A([Label])` | HUMAN | Manual human tasks (review, data entry, physical work) |\n");
        sb.append("| Diamond | `A{Label}` | CONTROL_GATE | Decision points, approval gates, compliance checks |\n");
        sb.append("| Subroutine | `A[[Label]]` | PIPELINE | Sub-process or pipeline invocation |\n");
        sb.append("| Parallelogram | `A[/Label/]` | SCRIPT | Script execution (JS, Python, Excel formula) |\n");
        sb.append("| Asymmetric | `A>Label]` | TOOL_CALL | External tool/API call |\n");
        sb.append("| Hexagon | `A{{Label}}` | DROOLS_RULE | Business rules, policy decisions, reasoning, decision-table routing |\n");
        sb.append("| Circle | `A((Label))` | AUTO | Start/end markers only |\n\n");

        sb.append("### Diagram rules\n");
        sb.append("- Start with `flowchart TD`\n");
        sb.append("- Use `subgraph \"Phase Name\" ... end` to group steps into phases\n");
        sb.append("- Label decision edges with `-->|Yes|` and `-->|No|` (or descriptive conditions)\n");
        sb.append("- Use hexagon nodes for Drools-backed reasoning or business-rule decisions; outgoing edge labels become executable branch conditions\n");
        sb.append("- Use descriptive node IDs: `reqSubmit`, `mgrApprove`, `finCheck` — not `A`, `B`, `C`\n");
        sb.append("- Every process needs at least a start node `start((Start))` and end node `done((End))`\n");
        sb.append("- Add classDef styles:\n");
        sb.append("  ```\n");
        sb.append("  classDef human fill:#e1bee7,stroke:#8e24aa,color:#000\n");
        sb.append("  classDef gate fill:#fff9c4,stroke:#f9a825,color:#000\n");
        sb.append("  classDef pipeline fill:#bbdefb,stroke:#1565c0,color:#000\n");
        sb.append("  classDef script fill:#c8e6c9,stroke:#2e7d32,color:#000\n");
        sb.append("  classDef tool fill:#ffe0b2,stroke:#e65100,color:#000\n");
        sb.append("  classDef drools fill:#dcedc8,stroke:#558b2f,color:#000\n");
        sb.append("  ```\n");
        sb.append("- Apply classes: `class mgrApprove gate` for each styled node\n\n");

        // =====================================================================
        // REQUIRED OUTPUT FORMAT
        // =====================================================================
        sb.append("## Required Output Format\n\n");
        sb.append("Your final response MUST contain exactly these sections:\n\n");

        sb.append("### `## Process Title`\n");
        sb.append("A short descriptive name for the process.\n\n");

        sb.append("### `## Description`\n");
        sb.append("2-4 sentences explaining what the process does, who is involved, and when it triggers.\n\n");

        sb.append("### `## Diagram`\n");
        sb.append("A single fenced mermaid code block:\n");
        sb.append("```mermaid\n");
        sb.append("flowchart TD\n");
        sb.append("    subgraph \"Initiation\"\n");
        sb.append("        start((Start)) --> reqSubmit([Submit Request])\n");
        sb.append("        reqSubmit --> validate[Validate Data]\n");
        sb.append("    end\n");
        sb.append("    subgraph \"Approval\"\n");
        sb.append("        validate --> mgrReview{Manager Review}\n");
        sb.append("        mgrReview -->|Approved| finCheck{Finance Check}\n");
        sb.append("        mgrReview -->|Rejected| revise([Revise Request])\n");
        sb.append("        revise --> validate\n");
        sb.append("        finCheck -->|Pass| execute[Execute]\n");
        sb.append("        finCheck -->|Fail| escalate([Escalate to VP])\n");
        sb.append("    end\n");
        sb.append("    subgraph \"Execution\"\n");
        sb.append("        execute --> notify>Send Notification]\n");
        sb.append("        escalate --> mgrReview\n");
        sb.append("        notify --> done((End))\n");
        sb.append("    end\n\n");
        sb.append("    classDef human fill:#e1bee7,stroke:#8e24aa,color:#000\n");
        sb.append("    classDef gate fill:#fff9c4,stroke:#f9a825,color:#000\n");
        sb.append("    classDef tool fill:#ffe0b2,stroke:#e65100,color:#000\n");
        sb.append("    class reqSubmit,revise,escalate human\n");
        sb.append("    class mgrReview,finCheck gate\n");
        sb.append("    class notify tool\n");
        sb.append("```\n\n");

        sb.append("### `## Sources`\n");
        sb.append("A JSON array of source citations. Each citation must include IDs from tool results for traceability:\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\"nodeId\": \"graph-node-uuid\", \"title\": \"Document name\", \"extractedText\": \"relevant quote\"},\n");
        sb.append("  {\"sourceId\": \"file:///path/to/doc.pdf\", \"title\": \"Source name\", \"extractedText\": \"relevant quote\"}\n");
        sb.append("]\n");
        sb.append("```\n");
        sb.append("The `sourceId` format is typically `file:///absolute/path` for uploaded files or a URL for web sources.\n\n");

        // =====================================================================
        // ERROR HANDLING
        // =====================================================================
        sb.append("## Error Handling\n\n");
        sb.append("- `isError=true` with `\"denied by permission policy\"` — tool is blocked. Skip it.\n");
        sb.append("- `isError=true` with `\"blocked by gateway\"` — rate-limited. Skip it.\n");
        sb.append("- Result has `\"error\"` key but `isError=false` — bad input. Fix parameters and retry once.\n");
        sb.append("- Tool not recognized (method not found) — not registered on this instance. Do not retry.\n");
        sb.append("- When graph tools fail, fall back to `knowledge_search` and `rag_query`.\n\n");

        // =====================================================================
        // GROUND RULES
        // =====================================================================
        sb.append("## Ground Rules\n\n");
        sb.append("- DO NOT fabricate steps. Every step must be grounded in data from the tools.\n");
        sb.append("- DO NOT ask the user for input. You are autonomous — make decisions and proceed.\n");
        sb.append("- If the data is insufficient, produce a partial diagram with what you found and note gaps.\n");
        sb.append("- Show your reasoning as you explore — the full transcript is captured for audit.\n");
        sb.append("- Prefer `knowledge_search` for broad discovery, `rag_query` for detailed extraction with metadata.");

        return sb.toString();
    }

    /**
     * Appends a complete catalog of all registered MCP tools to the prompt,
     * grouped by category. Each tool includes its name, description, and
     * parameter schema extracted from the live McpToolRegistry.
     * Falls back to a minimal hardcoded set if the registry is unavailable.
     */
    private void appendToolCatalog(StringBuilder sb, boolean kgAvailable) {
        if (mcpToolRegistry == null) {
            appendFallbackToolCatalog(sb, kgAvailable);
            return;
        }

        Map<String, List<EnhancedToolDefinition>> byCategory;
        try {
            byCategory = mcpToolRegistry.getToolsByCategory();
        } catch (Exception e) {
            log.debug("Failed to load tool catalog from registry: {}", e.getMessage());
            appendFallbackToolCatalog(sb, kgAvailable);
            return;
        }

        if (byCategory == null || byCategory.isEmpty()) {
            appendFallbackToolCatalog(sb, kgAvailable);
            return;
        }

        // Render each category as a subsection
        for (Map.Entry<String, List<EnhancedToolDefinition>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<EnhancedToolDefinition> tools = entry.getValue();
            if (tools == null || tools.isEmpty()) continue;

            sb.append("### ").append(formatCategoryName(category)).append("\n");
            for (EnhancedToolDefinition tool : tools) {
                if (!tool.isEnabled()) continue;

                String name = tool.getName();
                // Mark graph tools as unavailable when KG is down
                boolean isGraphTool = name != null && name.startsWith("graph_");
                sb.append("- **`").append(name).append("`**");
                if (isGraphTool && !kgAvailable) {
                    sb.append(" **(UNAVAILABLE)**");
                }
                sb.append(" — ");
                if (tool.getDescription() != null) {
                    sb.append(tool.getDescription());
                }
                sb.append("\n");

                // Append parameter info from input schema
                appendParamsFromSchema(sb, tool.getInputSchema());
            }
            sb.append("\n");
        }
    }

    /**
     * Extracts parameter names, types, and required flags from a JSON Schema
     * node and appends them as a compact parameter list.
     */
    private void appendParamsFromSchema(StringBuilder sb, JsonNode schema) {
        if (schema == null) return;
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) return;

        // Collect required field names
        Set<String> required = new HashSet<>();
        JsonNode reqNode = schema.get("required");
        if (reqNode != null && reqNode.isArray()) {
            for (JsonNode r : reqNode) {
                required.add(r.asText());
            }
        }

        sb.append("  Params: ");
        boolean first = true;
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!first) sb.append(", ");
            first = false;

            String paramName = field.getKey();
            JsonNode paramDef = field.getValue();
            String type = paramDef.has("type") ? paramDef.get("type").asText() : "any";

            sb.append("`").append(paramName).append("` (").append(type);
            if (required.contains(paramName)) {
                sb.append(", required");
            }

            // Include enum values if present
            JsonNode enumNode = paramDef.get("enum");
            if (enumNode != null && enumNode.isArray() && enumNode.size() <= 6) {
                sb.append(": ");
                for (int i = 0; i < enumNode.size(); i++) {
                    if (i > 0) sb.append("|");
                    sb.append(enumNode.get(i).asText());
                }
            }
            sb.append(")");
        }
        sb.append("\n");
    }

    private String formatCategoryName(String category) {
        if (category == null || category.isEmpty()) return "Other Tools";
        // "rag" → "RAG", "knowledge-graph" → "Knowledge Graph", "process_engine" → "Process Engine"
        return Arrays.stream(category.replace("-", " ").replace("_", " ").split("\\s+"))
                .map(w -> {
                    if (w.equalsIgnoreCase("rag") || w.equalsIgnoreCase("api") || w.equalsIgnoreCase("llm")) {
                        return w.toUpperCase();
                    }
                    return w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase();
                })
                .reduce((a, b) -> a + " " + b)
                .orElse("Other Tools");
    }

    /**
     * Fallback tool list used when McpToolRegistry is unavailable.
     * Covers the core tools most relevant for business process discovery.
     */
    private void appendFallbackToolCatalog(StringBuilder sb, boolean kgAvailable) {
        sb.append("### Discovery Tools (use these first)\n");
        sb.append("- **`graph_get_overview`** — Returns graph statistics, sources, and topics. CALL THIS FIRST.");
        if (!kgAvailable) sb.append(" **(UNAVAILABLE)**");
        sb.append("\n");
        sb.append("- **`knowledge_search`** — Params: `query` (string, required), `topic` (string, optional). ");
        sb.append("Fan-out search across all backends.\n");
        sb.append("- **`knowledge_status`** — Reports which backends are active and document counts.\n\n");

        sb.append("### Knowledge Graph Tools\n");
        if (!kgAvailable) {
            sb.append("**(UNAVAILABLE — KG not loaded. Skip these and use RAG/knowledge search.)**\n\n");
        } else {
            sb.append("- **`graph_search_nodes`** — Params: `query`, `nodeType` (source|document|snippet|entity), `maxResults`\n");
            sb.append("- **`graph_search_by_entity`** — Params: `entityName`, `maxResults`\n");
            sb.append("- **`graph_get_document_entities`** — Params: `documentId`\n");
            sb.append("- **`graph_get_related_documents`** — Params: `documentId`, `maxResults`, `relationshipType` (entity|similarity|hierarchical|any)\n");
            sb.append("- **`graph_find_connected`** — Params: `nodeId`, `depth` (max 3)\n");
            sb.append("- **`graph_rag_query`** — Params: `query`, `searchType` (LOCAL|GLOBAL), `maxResults`\n\n");
        }

        sb.append("### RAG Tool\n");
        sb.append("- **`rag_query`** — Params: `query` (required), `maxResults`. Queries the document corpus.\n\n");

        sb.append("### Fact Sheet Tools\n");
        sb.append("- **`list_fact_sheets`** — List all fact sheets\n");
        sb.append("- **`get_fact_sheet`** — Params: `id`. Get fact sheet details\n");
        sb.append("- **`get_active_fact_sheet`** — Get the active fact sheet\n");
        sb.append("- **`get_fact_sheet_stats`** — Params: `id`. Get entity/relationship counts\n\n");

        sb.append("### Process Engine Tools\n");
        sb.append("- **`process_list_ontologies`** — List process ontologies\n");
        sb.append("- **`process_get_ontology`** — Params: `id`. Get ontology details\n");
        sb.append("- **`process_create_ontology`** — Create a new ontology\n");
        sb.append("- **`process_list_runs`** — List process runs\n");
        sb.append("- **`process_start_run`** — Start a process run\n");
        sb.append("- **`process_list_approvals`** — List pending HITL approvals\n");
        sb.append("- **`process_submit_approval`** — Submit approval decision\n");
        sb.append("- **`process_list_sox_controls`** — List SOX compliance controls\n");
        sb.append("- **`process_get_metrics`** — Get process engine metrics\n\n");

        sb.append("### Process Discovery Tools\n");
        sb.append("- **`discover_processes`** — Mine a data source for process patterns\n");
        sb.append("- **`list_discovered_processes`** — List discovered process patterns\n");
        sb.append("- **`create_ontology_from_discovery`** — Create ontology from discovery\n");
        sb.append("- **`get_discovery_status`** — Check running discovery jobs\n\n");

        sb.append("### Filesystem Tools\n");
        sb.append("- **`list_files`** — Params: `directory`, `pattern`, `limit`. List files\n");
        sb.append("- **`read_file`** — Params: `filePath`. Read file contents\n");
        sb.append("- **`write_file`** — Params: `filePath`, `content`. Write to file\n");
        sb.append("- **`create_directory`** — Params: `directoryPath`. Create directory\n\n");

        sb.append("*Note: Additional tools (code indexer, compute graph, model staging, etc.) ");
        sb.append("may be available. Call any tool by name — the MCP server will return an error ");
        sb.append("if it is not registered on this instance.*\n\n");
    }
}
