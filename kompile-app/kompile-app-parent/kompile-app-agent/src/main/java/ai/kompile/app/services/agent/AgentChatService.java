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

package ai.kompile.app.services.agent;

import ai.kompile.app.rag.GraphReasoningRetriever;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.ToolCallWriterService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessState;
import ai.kompile.core.agent.ProcessStatus;
import ai.kompile.core.citation.CitationDto;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.rag.query.ProcessedQuery;
import ai.kompile.core.rag.query.QueryProcessor;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.knowledgegraph.citation.CitationSupport;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for executing agent chat with RAG-augmented prompts.
 * <p>
 * Features:
 * - Document retrieval from vector store and keyword search
 * - Prompt augmentation with retrieved context
 * - CLI agent execution with streaming output
 * - Process lifecycle management with interrupt support
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentRegistryService agentRegistry;
    private final AgentProcessDiagnosticService diagnosticService;
    private final ClaudeStreamParser streamParser;
    private final AgentSubprocessExecutor subprocessExecutor;
    private final DocumentRetriever keywordRetriever;
    private final VectorStore vectorStore;
    private final GraphRagService graphRagService;
    private final ExecutorService executorService;
    private final BuiltInToolDiscoveryService toolDiscoveryService;
    private final ServerPortService serverPortService;
    private final FolderService folderService;
    private final ApiAgentChatExecutor apiAgentChatExecutor;
    private final QueryProcessor queryProcessor;

    // Track running processes by processId for interrupt support
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    // Persists tool calls made by kompile-managed agent sessions into the shared
    // CLI/MCP tool-call index so they surface in the MCP Hub tool-call catalog.
    @Autowired(required = false)
    private ToolCallWriterService toolCallWriterService;

    // Collects ReasoningTrail DTOs pushed by KbVerifyExplainTool during a chat turn
    // so they can be streamed as reasoning_trace SSE events after the subprocess exits.
    @Autowired(required = false)
    private ReasoningTraceStore reasoningTraceStore;

    // Evidence-sufficiency gate: scores the retrieved evidence and abstains ("insufficient evidence")
    // instead of letting the LLM fabricate when grounding is too weak (grounded-RAG rec 2). Opt-in.
    @Autowired(required = false)
    private RetrievalSufficiencyGate sufficiencyGate;

    // CRAG corrective retrieval: on weak evidence, escalate the graph search mode (LOCAL→HYBRID→GLOBAL)
    // and re-retrieve before abstaining (grounded-RAG rec 3). Opt-in via kbCorrectiveRetrievalEnabled.
    @Autowired(required = false)
    private CorrectiveRetrievalPolicy correctiveRetrieval;

    // Grounded-answer verification loop: after generation, verify the answer's factual claims against
    // the KB and surface per-claim verdicts + a caveat (grounded-RAG rec 1). Opt-in; inert without an
    // AnswerClaimExtractor bean + request.factSheetId + kbAnswerVerificationEnabled.
    @Autowired(required = false)
    private GroundedAnswerVerifier answerVerifier;

    // Citation contract: numbered Sources block + inline [n] instruction, and post-answer extraction of
    // which sources the answer cited (grounded-RAG rec 4). Opt-in via kbCitationContractEnabled.
    @Autowired(required = false)
    private CitationContract citationContract;

    // Context compaction: resolves the model's real context budget per lane (catalog for
    // CLI/API models, staging metadata for local models) and compacts oversized histories
    // before prompt assembly so a turn never blows the window of what it's chatting with.
    @Autowired(required = false)
    private ChatContextBudgetService contextBudgetService;

    @Autowired(required = false)
    private ChatHistoryCompactor historyCompactor;

    @Autowired(required = false)
    private LocalStagingLlmService localStagingLlmService;

    private static final String COMPACTION_SUMMARY_SYSTEM_PROMPT =
            "You are a conversation summarizer. Produce a concise, information-dense summary of the "
                    + "conversation transcript you are given: the user's goals, key facts and decisions, "
                    + "unresolved questions, and the current state of the work. Write it so the "
                    + "conversation can continue seamlessly from the summary alone. Output only the summary.";

    // RAG prompt template
    private static final String RAG_CONTEXT_TEMPLATE = """

            ## Retrieved Context
            The following documents were retrieved from the knowledge base to help answer the question.
            Use this context to provide accurate, grounded responses.

            %s

            ---

            ## User Question
            %s
            """;

    private static final String DOCUMENT_TEMPLATE = """
            ### Document %d (Score: %.3f)
            %s
            """;

    // GraphRAG context template
    private static final String GRAPH_RAG_CONTEXT_TEMPLATE = """
            ## Knowledge Graph Context
            The following information was retrieved from the knowledge graph to help answer the question.
            This includes entities and their relationships.

            %s
            """;

    @Autowired
    public AgentChatService(
            AgentRegistryService agentRegistry,
            AgentProcessDiagnosticService diagnosticService,
            ClaudeStreamParser streamParser,
            AgentSubprocessExecutor subprocessExecutor,
            List<DocumentRetriever> keywordRetrievers,
            List<VectorStore> vectorStores,
            @Autowired(required = false) List<GraphRagService> graphRagServices,
            @Autowired(required = false) BuiltInToolDiscoveryService toolDiscoveryService,
            ServerPortService serverPortService,
            @Autowired(required = false) FolderService folderService,
            @Autowired(required = false) ApiAgentChatExecutor apiAgentChatExecutor,
            @Autowired(required = false) QueryProcessor queryProcessor) {

        this.agentRegistry = agentRegistry;
        this.diagnosticService = diagnosticService;
        this.streamParser = streamParser;
        this.subprocessExecutor = subprocessExecutor;
        this.executorService = new ThreadPoolExecutor(
                2, 32, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100));
        this.toolDiscoveryService = toolDiscoveryService;
        this.serverPortService = serverPortService;
        this.folderService = folderService;
        this.apiAgentChatExecutor = apiAgentChatExecutor;
        this.queryProcessor = queryProcessor;

        // Select real retrieval implementations only; missing retrieval remains unavailable.
        this.keywordRetriever = keywordRetrievers.stream()
                .filter(r -> !(r instanceof NoOpDocumentRetrieverImpl))
                .findFirst()
                .orElse(null);

        this.vectorStore = vectorStores.stream()
                .filter(v -> !(v instanceof NoOpVectorStoreImpl))
                .findFirst()
                .orElse(null);

        // Select first available GraphRagService
        this.graphRagService = graphRagServices != null && !graphRagServices.isEmpty()
                ? graphRagServices.get(0)
                : null;

        log.info("AgentChatService initialized with KeywordRetriever: {}, VectorStore: {}, GraphRAG: {}, MCP Tools: {}",
                this.keywordRetriever != null ? this.keywordRetriever.getClass().getSimpleName() : "unavailable",
                this.vectorStore != null ? this.vectorStore.getClass().getSimpleName() : "unavailable",
                this.graphRagService != null ? this.graphRagService.getClass().getSimpleName() : "unavailable",
                toolDiscoveryService != null ? "available" : "unavailable");
    }

    /**
     * Execute chat with streaming response.
     */
    public void executeChat(AgentChatRequest request, SseEmitter emitter) {
        executorService.submit(() -> {
            ProcessStatus processStatus = null;
            Process process = null;
            List<RetrievedDoc> retrievedSources = new ArrayList<>();

            try {
                // Validate agent
                Optional<AgentProvider> agentOpt = agentRegistry.getAgent(request.getAgentName());
                if (agentOpt.isEmpty()) {
                    sendError(emitter, "Agent not found: " + request.getAgentName());
                    return;
                }

                AgentProvider agent = agentOpt.get();
                if (!agent.isAvailable()) {
                    sendError(emitter, "Agent not available: " + agent.getDisplayName());
                    return;
                }

                // Model-aware auto-compaction: bound the history this turn carries BEFORE
                // prompt assembly, for both lanes (the API lane forwards history as
                // messages[]; the CLI lane embeds it into the prompt).
                applyAutoCompaction(agent, request, emitter);

                // Run query processing and emit query_info event
                if (queryProcessor != null) {
                    try {
                        ProcessedQuery processedQuery = queryProcessor.process(request.getMessage(), List.of());
                        if (processedQuery.wasRewritten()) {
                            Map<String, Object> queryInfo = new LinkedHashMap<>();
                            queryInfo.put("originalQuery", processedQuery.originalQuery());
                            queryInfo.put("rewrittenQuery", processedQuery.rewrittenQuery());
                            queryInfo.put("wasRewritten", true);
                            if (processedQuery.intent() != null) {
                                queryInfo.put("intent", processedQuery.intent().name());
                            }
                            sendEvent(emitter, "query_info", queryInfo);
                        }
                    } catch (Exception e) {
                        log.debug("Query processing skipped: {}", e.getMessage());
                    }
                }

                // Branch on agent type: API agents use HTTP, CLI agents use subprocess
                if (agent.isApiAgent()) {
                    if (apiAgentChatExecutor == null) {
                        sendError(emitter, "API agent executor not available");
                        return;
                    }
                    long apiRagStartMs = System.currentTimeMillis();
                    // API lane: history travels as messages[] (ApiAgentChatExecutor), never in the prompt
                    String augmentedPrompt = buildPromptWithSources(request, retrievedSources, false);
                    long apiRagRetrievalMs = System.currentTimeMillis() - apiRagStartMs;
                    if (!retrievedSources.isEmpty()) {
                        sendEvent(emitter, "rag_metrics", Map.of(
                                "retrievalMs", apiRagRetrievalMs,
                                "documentsRetrieved", retrievedSources.size()));
                    }
                    // The reasoning routes (CAUSAL/PROBABILISTIC) push their trail during the
                    // retrieval above; the API executor never drains the store, so emit here —
                    // otherwise the trail is dropped and leaks into the next turn's drain window.
                    if (reasoningTraceStore != null) {
                        for (Map<String, Object> trace : reasoningTraceStore.drainSince(apiRagStartMs)) {
                            sendEvent(emitter, "reasoning_trace", trace);
                        }
                    }
                    apiAgentChatExecutor.executeApiChat(agent, request, augmentedPrompt, retrievedSources, emitter);
                    return;
                }

                // CLI agent path: build command and execute subprocess
                long ragStartMs = System.currentTimeMillis();
                // Record turn-start BEFORE retrieval so a ReasoningTrail pushed by the reasoning routes
                // (causal / MEBN) during graph-RAG retrieval lands in the drain window — together with
                // any trace KbVerifyExplainTool pushes later during subprocess execution.
                long turnStartMs = ragStartMs;
                String augmented = buildPromptWithSources(request, retrievedSources, true);
                long ragRetrievalMs = System.currentTimeMillis() - ragStartMs;

                // ── Evidence grading → corrective retrieval (rec 3) → abstention (rec 2) ──────────
                // Grade the retrieved evidence once. On a weak grade, first CORRECT (escalate the graph
                // search mode + re-retrieve, rec 3); if still weak, ABSTAIN with "insufficient evidence"
                // (rec 2) rather than launching the LLM to guess. Runs BEFORE any process is created, so
                // an early return cleanly hits the finally{} that completes the emitter.
                if (sufficiencyGate != null) {
                    RetrievalSufficiencyGate.SufficiencyResult suf =
                            sufficiencyGate.assess(request.getMessage(), retrievedSources);

                    // rec 3 (CRAG corrective): escalate LOCAL→HYBRID→GLOBAL and re-retrieve before giving
                    // up. Bounded by the escalation ladder (≤2 extra passes). Independent of the
                    // abstention flag — grading always produces a score.
                    if (!suf.sufficient() && correctiveRetrieval != null && correctiveRetrieval.isEnabled()) {
                        for (String nextType : CorrectiveRetrievalPolicy.escalationLadder(
                                request.getGraphRagSearchType())) {
                            log.info("Corrective retrieval: escalating graph search {} → {} (score {} < {})",
                                    request.getGraphRagSearchType(), nextType, suf.score(), suf.threshold());
                            request.setGraphRagSearchType(nextType);
                            retrievedSources.clear();
                            augmented = buildPromptWithSources(request, retrievedSources, true);
                            suf = sufficiencyGate.assess(request.getMessage(), retrievedSources);
                            sendEvent(emitter, "corrective_retrieval", Map.of(
                                    "escalatedTo", nextType,
                                    "score", suf.score(),
                                    "sufficient", suf.sufficient(),
                                    "sources", retrievedSources.size()));
                            if (suf.sufficient()) {
                                break;
                            }
                        }
                    }

                    // rec 2 (abstention): still insufficient after any correction → answer "insufficient".
                    if (suf.enabled() && !suf.sufficient()) {
                        log.info("Evidence-sufficiency gate: abstaining (score={} < threshold={}, {} sources)",
                                suf.score(), suf.threshold(), retrievedSources.size());
                        if (!retrievedSources.isEmpty()) {
                            sendEvent(emitter, "sources", formatSourcesForClient(retrievedSources));
                        }
                        sendEvent(emitter, "evidence_insufficient", Map.of(
                                "score", suf.score(),
                                "threshold", suf.threshold(),
                                "sourcesFound", retrievedSources.size()));
                        sendEvent(emitter, "chunk", suf.summary());
                        sendEvent(emitter, "complete", Map.of(
                                "content", suf.summary(),
                                "abstained", true));
                        return; // finally{} completes the emitter; no subprocess is started
                    }
                }

                List<String> command = buildCommand(agent, request, augmented);

                // Create process status for tracking
                processStatus = diagnosticService.startProcess(agent.getName(), command);
                String processId = processStatus.getId();

                // Send sources event if RAG was used
                if (!retrievedSources.isEmpty()) {
                    sendEvent(emitter, "sources", formatSourcesForClient(retrievedSources));
                    sendEvent(emitter, "rag_metrics", Map.of(
                            "retrievalMs", ragRetrievalMs,
                            "documentsRetrieved", retrievedSources.size()));
                }

                log.info("Executing agent command: {} (args hidden)", agent.getCommand());

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);

                // Set working directory
                if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().isEmpty()) {
                    File workDir = new File(request.getWorkingDirectory());
                    if (workDir.isDirectory()) {
                        pb.directory(workDir);
                    }
                }

                // Add environment variables
                Map<String, String> env = pb.environment();
                env.putAll(agent.safeEnvironment());

                // turnStartMs was recorded above (before retrieval) so both retrieval-time and
                // subprocess-time reasoning traces are drained for this turn.
                process = pb.start();
                final Process runningProcess = process;

                // Store process for interrupt support
                runningProcesses.put(processId, runningProcess);

                // Update process status to running with PID
                diagnosticService.processStarted(processId, runningProcess.pid());

                // Send initial event
                sendEvent(emitter, "start", Map.of(
                        "processId", processId,
                        "agent", agent.getName(),
                        "ragEnabled", request.isEnableRag(),
                        "graphRagEnabled", request.isEnableGraphRag()));

                // Stream output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream()))) {

                    diagnosticService.processStreaming(processId);

                    StringBuilder fullResponse = new StringBuilder();
                    String line;
                    boolean useStreamParser = streamParser.supportsStreamJson(agent.getName());

                    while ((line = reader.readLine()) != null) {
                        // Check if process was cancelled
                        if (!runningProcesses.containsKey(processId)) {
                            log.info("Process {} was cancelled, stopping stream", processId);
                            sendEvent(emitter, "cancelled", Map.of(
                                    "processId", processId,
                                    "content", fullResponse.toString()));
                            break;
                        }

                        diagnosticService.outputReceived(processId, line);

                        if (useStreamParser) {
                            // Use ClaudeStreamParser for Claude CLI stream-json format
                            ClaudeStreamParser.ParseResult result = streamParser.parseLine(processId, line);
                            if (result != null) {
                                if (result.textContent() != null && !result.textContent().isEmpty()) {
                                    fullResponse.append(result.textContent());
                                    sendEvent(emitter, "chunk", result.textContent());
                                }
                                recordManagedToolCall(processId, result, agent.getName(),
                                        request.getWorkingDirectory());
                                // Structured tool_use event (same shape as the passthrough lane)
                                // so the UI can render tool invocations distinctly from text —
                                // the formatted text block still flows through "chunk" above.
                                if ("tool_use".equals(result.type()) && result.toolName() != null) {
                                    sendEvent(emitter, "tool_use", Map.of(
                                            "toolName", result.toolName(),
                                            "input", result.toolInput() != null ? result.toolInput().toString() : ""));
                                }
                                if (result.isResult()) {
                                    // Build stats map with token metrics if available
                                    Map<String, Object> stats = new HashMap<>();
                                    stats.put("durationMs", result.durationMs() != null ? result.durationMs() : 0);
                                    stats.put("costUsd", result.costUsd() != null ? result.costUsd() : 0.0);
                                    stats.put("numTurns", result.numTurns() != null ? result.numTurns() : 0);
                                    stats.put("isError", result.isError());
                                    // Include token throughput metrics from streaming
                                    Map<String, Object> tokenMetrics = streamParser.getTokenMetrics(processId);
                                    if (tokenMetrics != null) {
                                        stats.put("tokenMetrics", tokenMetrics);
                                    }
                                    sendEvent(emitter, "stats", stats);
                                }
                            }
                        } else {
                            // Plain text for non-Claude agents
                            fullResponse.append(line).append("\n");
                            sendEvent(emitter, "chunk", line + "\n");
                        }
                    }

                    // Wait for process completion with configurable timeout
                    int timeoutSeconds = request.getTimeoutSeconds();
                    boolean completed;
                    if (timeoutSeconds <= 0) {
                        // Default to 1 hour max to prevent indefinite blocking
                        log.info("Waiting for process completion with default 1h timeout");
                        completed = runningProcess.waitFor(3600, TimeUnit.SECONDS);
                    } else {
                        log.info("Waiting for process completion with {}s timeout", timeoutSeconds);
                        completed = runningProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                    }

                    if (!completed) {
                        runningProcess.destroyForcibly();
                        diagnosticService.processTimedOut(processId);
                        sendError(emitter, "Process timed out");
                    } else {
                        int exitCode = runningProcess.exitValue();
                        if (exitCode == 0) {
                            // Emit any reasoning traces accumulated by KbVerifyExplainTool
                            // during this turn BEFORE the complete event so the frontend
                            // can attach them to the streaming message.
                            if (reasoningTraceStore != null) {
                                List<Map<String, Object>> traces = reasoningTraceStore.drainSince(turnStartMs);
                                for (Map<String, Object> trace : traces) {
                                    sendEvent(emitter, "reasoning_trace", trace);
                                }
                            }
                            // ── Grounded-answer verification (grounded-RAG rec 1) ──────────────────
                            // Verify the generated answer's factual claims against the KB, INDEPENDENT
                            // of the draft (a KB lookup never sees it — CoVe's independence for free),
                            // and surface per-claim verdicts + a caveat. Opt-in: needs
                            // kbAnswerVerificationEnabled + an AnswerClaimExtractor bean + a scoping
                            // factSheetId on the request; inert (and non-fatal) otherwise.
                            if (answerVerifier != null && request.getFactSheetId() != null
                                    && answerVerifier.isActive()) {
                                try {
                                    GroundedAnswerVerifier.AnswerVerification av = answerVerifier.verifyAnswer(
                                            fullResponse.toString(), request.getFactSheetId(), 10);
                                    if (av.ran()) {
                                        sendEvent(emitter, "claim_verification", Map.of(
                                                "supported", av.supported(),
                                                "refuted", av.refuted(),
                                                "unknown", av.unknown(),
                                                "verdicts", av.verdicts().stream()
                                                        .map(v -> Map.of("atom", v.atomKey(),
                                                                "status", v.status(),
                                                                "confidence", v.confidence()))
                                                        .toList()));
                                        if (!av.caveat().isBlank()) {
                                            sendEvent(emitter, "chunk", "\n\n" + av.caveat());
                                        }
                                    }
                                } catch (Exception ve) {
                                    log.debug("Grounded-answer verification failed (non-fatal): {}", ve.getMessage());
                                }
                            }
                            // Citation contract (grounded-RAG rec 4): surface which [n] source markers the
                            // answer actually used, so the UI can link them and unattributed claims can feed
                            // verification. Emitted only when enabled and sources were provided.
                            if (citationContract != null && citationContract.isEnabled()
                                    && !retrievedSources.isEmpty()) {
                                Set<Integer> cited = CitationContract.extractCitationIndices(fullResponse.toString());
                                sendEvent(emitter, "citations", Map.of(
                                        "used", new ArrayList<>(cited),
                                        "totalSources", retrievedSources.size()));
                            }
                            diagnosticService.processCompleted(processId, exitCode);
                            sendEvent(emitter, "complete", Map.of(
                                    "processId", processId,
                                    "content", fullResponse.toString(),
                                    "modifiedFiles", streamParser.getModifiedFiles(processId)));
                            streamParser.clearSession(processId);
                            streamParser.clearModifiedFiles(processId);
                        } else {
                            diagnosticService.processCompleted(processId, exitCode);
                            sendError(emitter, "Process exited with code: " + exitCode);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Error executing agent chat", e);
                if (processStatus != null) {
                    diagnosticService.processFailed(processStatus.getId(), e.getMessage());
                }
                sendError(emitter, "Execution error: " + e.getMessage());
            } finally {
                // Clean up process from tracking map
                if (processStatus != null) {
                    runningProcesses.remove(processStatus.getId());
                }
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing emitter", e);
                }
            }
        });
    }

    /**
     * Build the final prompt, optionally augmented with folder context, RAG
     * context, and GraphRAG context.
     * MCP tools are now injected automatically via the agent's native MCP server
     * support.
     * Also populates the retrievedSources list for client-side display.
     */
    /**
     * @param embedHistory embed the (already compacted) conversation history into the
     *                     prompt text. True for the CLI lane, which spawns a fresh
     *                     one-shot subprocess per turn and has no other way to carry
     *                     multi-turn context; false for API lanes, where history is
     *                     forwarded as {@code messages[]} and embedding it here would
     *                     send it twice.
     */
    private String buildPromptWithSources(AgentChatRequest request, List<RetrievedDoc> retrievedSources,
                                          boolean embedHistory) {
        String userMessage = request.getMessage();
        StringBuilder promptBuilder = new StringBuilder();

        // Inject folder context if folderId is provided
        String folderContext = buildFolderContextPrefix(request.getFolderId());
        if (folderContext != null && !folderContext.isEmpty()) {
            promptBuilder.append(folderContext);
            promptBuilder.append("\n---\n\n");
        }

        if (embedHistory && request.isIncludeHistory()) {
            String historyBlock = renderHistoryBlock(request.getChatHistory());
            if (!historyBlock.isEmpty()) {
                promptBuilder.append(historyBlock);
                promptBuilder.append("\n---\n\n");
            }
        }

        // Note: MCP tools are now injected via native CLI flags (e.g., --mcp-server)
        // rather than being embedded in the prompt text

        boolean ragEnabled = request.isEnableRag();
        boolean graphRagEnabled = request.isEnableGraphRag() && graphRagService != null;

        // If neither RAG nor GraphRAG is enabled, return original message
        if (!ragEnabled && !graphRagEnabled) {
            log.debug("RAG and GraphRAG disabled, using original message");
            promptBuilder.append(userMessage);
            return promptBuilder.toString();
        }

        StringBuilder contextBuilder = new StringBuilder();
        boolean hasContext = false;

        // Retrieve GraphRAG context if enabled
        if (graphRagEnabled) {
            log.info("GraphRAG enabled, retrieving graph context for: {}",
                    userMessage.substring(0, Math.min(100, userMessage.length())));

            GraphRagResult graphResult = retrieveGraphContext(
                    userMessage,
                    request.getGraphRagMaxResults(),
                    request.getGraphRagSearchType(),
                    request.getGraphRagConversationId(),
                    request.getFactSheetId());

            if (graphResult != null && graphResult.getFormattedContext() != null
                    && !graphResult.getFormattedContext().isEmpty()) {
                contextBuilder.append(String.format(GRAPH_RAG_CONTEXT_TEMPLATE, graphResult.getFormattedContext()));
                contextBuilder.append("\n");
                hasContext = true;
                log.info("Retrieved graph context for augmentation");

                // Emit per-entity sources so the frontend can show clickable graph-linked cards.
                // Falls back to a single flat source when the entity list is empty.
                List<Entity> entities = graphResult.getEntities();
                if (entities != null && !entities.isEmpty()) {
                    for (Entity entity : entities) {
                        String entityId = entity.getId();
                        if (entityId == null) continue;
                        // Prefer description; fall back to first text unit.
                        String entityText = entity.getDescription();
                        if ((entityText == null || entityText.isBlank())
                                && entity.getTextUnits() != null && !entity.getTextUnits().isEmpty()) {
                            entityText = entity.getTextUnits().get(0);
                        }
                        if (entityText == null) entityText = "";

                        Map<String, Object> entityMeta = new HashMap<>(
                                entity.getMetadata() != null ? entity.getMetadata() : Map.of());
                        entityMeta.put("node_id", entityId);
                        if (entity.getType() != null) {
                            entityMeta.put("type", entity.getType());
                        }
                        // Preserve source_id if already present in entity metadata
                        double confidence = entity.getConfidence() != null ? entity.getConfidence() : 0.0;
                        RetrievedDoc entityDoc = new RetrievedDoc(entityId, entityText, entityMeta, confidence);
                        retrievedSources.add(entityDoc);
                    }
                    log.info("Added {} entity sources from graph RAG result", entities.size());
                } else {
                    // No structured entities — fall back to a single flat context source
                    RetrievedDoc graphDoc = new RetrievedDoc(
                            "graph-context",
                            graphResult.getFormattedContext(),
                            Map.of("type", "graph", "searchType", request.getGraphRagSearchType()),
                            1.0);
                    retrievedSources.add(graphDoc);
                }
            }
        }

        // Retrieve RAG context if enabled
        if (ragEnabled) {
            log.info("RAG enabled, retrieving context for: {}",
                    userMessage.substring(0, Math.min(100, userMessage.length())));

            List<RetrievedDoc> retrievedDocs = retrieveDocuments(
                    userMessage,
                    request.getRagMaxResults(),
                    request.getRagSimilarityThreshold(),
                    request.isIncludeKeywordSearch(),
                    request.isIncludeSemanticSearch());

            if (!retrievedDocs.isEmpty()) {
                // Populate sources for client
                retrievedSources.addAll(retrievedDocs);

                // Format context
                String formattedContext = formatContext(retrievedDocs);
                log.info("Retrieved {} documents for context augmentation", retrievedDocs.size());

                contextBuilder.append(String.format(RAG_CONTEXT_TEMPLATE, formattedContext, ""));
                hasContext = true;
            }
        }

        // If no context was retrieved, use original message
        if (!hasContext) {
            log.warn("No context retrieved from RAG or GraphRAG, using original message");
            promptBuilder.append(userMessage);
            return promptBuilder.toString();
        }

        // Build the final prompt with context and question
        promptBuilder.append(contextBuilder);
        // Citation contract (grounded-RAG rec 4): append a numbered Sources block + instruction so the
        // LLM anchors claims inline as [n] keyed to the retrieved sources. Opt-in; no-op otherwise.
        if (citationContract != null && citationContract.isEnabled() && !retrievedSources.isEmpty()) {
            promptBuilder.append(CitationContract.buildSourcesBlock(retrievedSources));
        }
        promptBuilder.append("\n---\n\n## User Question\n");
        promptBuilder.append(userMessage);
        return promptBuilder.toString();
    }

    /**
     * Reasoning-augmented retriever (causal / MEBN). Field-injected and optional so the existing
     * constructor is unchanged; when absent or a strategy is unsupported, retrieval falls back to
     * standard graph RAG.
     */
    @Autowired(required = false)
    private GraphReasoningRetriever graphReasoningRetriever;

    /**
     * Retrieve context from the knowledge graph using GraphRAG.
     */
    private GraphRagResult retrieveGraphContext(
            String query,
            int maxResults,
            String searchType,
            String conversationId,
            Long factSheetId) {

        // Reasoning strategies (causal / probabilistic) route to the reasoning retriever, which turns
        // the query into causal chains or MEBN posteriors via the event-attribution services.
        if (graphReasoningRetriever != null && graphReasoningRetriever.supports(searchType)) {
            GraphReasoningRetriever.ReasoningRetrieval reasoning =
                    graphReasoningRetriever.retrieveWithTrail(query, searchType, maxResults, factSheetId);
            if (reasoning != null && reasoning.context() != null && !reasoning.context().isBlank()) {
                // Surface the reasoning as a trail card in the chat UI (reasoning-stack gap §1): push it
                // to the trace buffer so this turn drains + emits it as a reasoning_trace SSE event —
                // the same channel the agent-invoked kb_verify_explain tool uses. Previously the
                // reasoning was folded into the LLM prompt as plain text with no structured evidence.
                if (reasoningTraceStore != null && reasoning.trail() != null) {
                    try {
                        reasoningTraceStore.storeTrace(ReasoningTrailMapper.toTrailDto(reasoning.trail()));
                    } catch (Exception e) {
                        log.debug("Could not push reasoning trail to trace store: {}", e.getMessage());
                    }
                }
                return GraphRagResult.builder()
                        .answer(reasoning.context())
                        .formattedContext(reasoning.context())
                        .build();
            }
            // Reasoning produced nothing usable; fall through to standard graph RAG.
        }

        if (graphRagService == null) {
            log.debug("GraphRagService not available");
            return null;
        }

        try {
            // Parse the requested search type (LOCAL, GLOBAL, HYBRID, ...); default to LOCAL on
            // unknown values. Previously only GLOBAL was honored, so HYBRID silently ran LOCAL.
            SearchType type = SearchType.LOCAL;
            if (searchType != null && !searchType.isBlank()) {
                try {
                    type = SearchType.valueOf(searchType.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    log.debug("Unknown graph searchType '{}', defaulting to LOCAL", searchType);
                }
            }

            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(query)
                    .searchType(type)
                    .k(maxResults)
                    .conversationId(conversationId != null ? conversationId : "default")
                    .factSheetId(factSheetId)
                    .build();

            return graphRagService.answerQuery(graphQuery);
        } catch (Exception e) {
            log.error("Error retrieving graph context", e);
            return null;
        }
    }

    /**
     * Format sources for client-side display.
     */
    private List<Map<String, Object>> formatSourcesForClient(List<RetrievedDoc> docs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        int index = 1;

        for (RetrievedDoc doc : docs) {
            if (doc.getText() == null || doc.getText().isEmpty()) {
                continue;
            }

            Map<String, Object> source = new HashMap<>();
            source.put("index", index);
            source.put("id", doc.getId() != null ? doc.getId() : "doc-" + index);
            source.put("score", doc.getScore() != null ? doc.getScore() : 0.0);

            // Extract source name from metadata or generate one
            String sourceName = extractSourceName(doc);
            source.put("sourceName", sourceName);

            // Content preview (first 300 chars)
            String content = doc.getText();
            String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
            source.put("preview", preview);

            // Full content for expansion
            source.put("content", content.length() > 2000 ? content.substring(0, 2000) + "... [truncated]" : content);

            // Include relevant metadata
            Map<String, Object> safeMetadata = new HashMap<>();
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                    // Include string, number, and boolean metadata; skip large/complex objects
                    if (entry.getValue() instanceof String
                            || entry.getValue() instanceof Number
                            || entry.getValue() instanceof Boolean) {
                        safeMetadata.put(entry.getKey(), entry.getValue());
                    }
                }
                source.put("metadata", safeMetadata);
            }

            // Uniform citation object
            CitationDto citation = CitationSupport.from(doc.getMetadata(), doc.getScore(), null);
            source.put("citation", citation);

            // Graph linkage: nodeId for "View in knowledge graph", documentId for document navigation
            String nodeId = extractMetadataString(doc.getMetadata(), "node_id", "nodeId", "externalId");
            if (nodeId == null && doc.getId() != null && !doc.getId().startsWith("doc-")) {
                // doc.getId() is a stable external ID when set by the graph store
                nodeId = doc.getId();
            }
            if (nodeId != null) {
                source.put("nodeId", nodeId);
            }
            String documentId = extractMetadataString(doc.getMetadata(),
                    SourceMetadataConstants.SOURCE_ID,          // "source_id"
                    GraphProvenanceKeys.SOURCE_DOCUMENT_ID);    // "_sourceDocumentId"
            if (documentId == null && citation != null && citation.sourceId() != null) {
                documentId = citation.sourceId();
            }
            if (documentId != null) {
                source.put("documentId", documentId);
            }

            sources.add(source);
            index++;
        }

        return sources;
    }

    /**
     * Extract a readable source name from document metadata.
     */
    private String extractSourceName(RetrievedDoc doc) {
        if (doc.getMetadata() != null) {
            // Try common metadata keys for source name
            String[] nameKeys = { "source", "file_name", "fileName", "title", "name", "path" };
            for (String key : nameKeys) {
                Object value = doc.getMetadata().get(key);
                if (value instanceof String && !((String) value).isEmpty()) {
                    String name = (String) value;
                    // Extract just the filename if it's a path
                    if (name.contains("/") || name.contains("\\")) {
                        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    }
                    return name;
                }
            }
        }

        // Fallback to document ID or generic name
        if (doc.getId() != null && !doc.getId().isEmpty()) {
            String id = doc.getId();
            // If ID looks like a path, extract filename
            if (id.contains("/") || id.contains("\\")) {
                return id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf('\\')) + 1);
            }
            return id.length() > 30 ? id.substring(0, 30) + "..." : id;
        }

        return "Document";
    }

    /**
     * Return the first non-blank string value found in {@code metadata} under any of
     * the supplied keys, or {@code null} if none match.
     */
    private String extractMetadataString(Map<String, Object> metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            Object v = metadata.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Retrieve documents using hybrid search (keyword + semantic).
     */
    private List<RetrievedDoc> retrieveDocuments(
            String query,
            int maxResults,
            double threshold,
            boolean includeKeyword,
            boolean includeSemantic) {

        Set<RetrievedDoc> combinedDocs = new LinkedHashSet<>();
        int resultsPerRetriever = Math.max(1, maxResults / 2);

        // Keyword search
        if (includeKeyword && keywordRetriever != null && !(keywordRetriever instanceof NoOpDocumentRetrieverImpl)) {
            try {
                log.debug("Performing keyword search (k={})", resultsPerRetriever);
                List<RetrievedDoc> keywordDocs = keywordRetriever.retrieveWithDetails(query, resultsPerRetriever);

                if (keywordDocs != null) {
                    keywordDocs.stream()
                            .filter(doc -> doc != null && doc.getText() != null && !doc.getText().isEmpty())
                            .filter(doc -> !doc.getText().startsWith("Error:"))
                            .forEach(combinedDocs::add);

                    log.debug("Keyword search returned {} valid documents",
                            keywordDocs.stream().filter(d -> d != null && d.getText() != null).count());
                }
            } catch (Exception e) {
                log.error("Error during keyword retrieval", e);
            }
        }

        // Semantic search
        if (includeSemantic && vectorStore != null && !(vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                log.debug("Performing semantic search (k={}, threshold={})", resultsPerRetriever, threshold);
                List<Document> semanticDocs = vectorStore.similaritySearch(query, resultsPerRetriever, threshold);

                if (semanticDocs != null) {
                    semanticDocs.stream()
                            .filter(doc -> doc.getText() != null && !doc.getText().trim().isEmpty())
                            .map(this::convertToRetrievedDoc)
                            .forEach(combinedDocs::add);

                    log.debug("Semantic search returned {} valid documents", semanticDocs.size());
                }
            } catch (Exception e) {
                log.error("Error during semantic retrieval", e);
            }
        }

        return new ArrayList<>(combinedDocs);
    }

    /**
     * Convert Spring AI Document to RetrievedDoc.
     */
    private RetrievedDoc convertToRetrievedDoc(Document doc) {
        String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
        double score = 0.0;
        if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
            Object scoreObj = doc.getMetadata().get("score");
            if (scoreObj instanceof Number) {
                score = ((Number) scoreObj).doubleValue();
            }
        }
        return new RetrievedDoc(id, doc.getText(), doc.getMetadata(), score);
    }

    /**
     * Format retrieved documents for prompt context.
     */
    private String formatContext(List<RetrievedDoc> docs) {
        StringBuilder sb = new StringBuilder();
        int docNum = 1;

        for (RetrievedDoc doc : docs) {
            String content = doc.getText();
            if (content == null || content.isEmpty()) {
                continue;
            }

            // Truncate very long documents
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "... [truncated]";
            }

            double score = doc.getScore() != null ? doc.getScore() : 0.0;
            sb.append(String.format(DOCUMENT_TEMPLATE, docNum, score, content.trim()));
            sb.append("\n");
            docNum++;
        }

        return sb.toString();
    }

    /**
     * Build the base interactive command for the agent (without -p prompt).
     * Used by both one-shot and passthrough interactive modes.
     */
    public List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools) {
        return subprocessExecutor.buildInteractiveCommand(agent, skipPermissions, injectMcpTools);
    }

    /**
     * Build the base interactive command for the agent (without -p prompt).
     * Delegates to {@link AgentSubprocessExecutor}.
     */
    public List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools, List<String> agentArgs) {
        return subprocessExecutor.buildInteractiveCommand(agent, skipPermissions, injectMcpTools, agentArgs);
    }

    /**
     * Build the CLI command for the agent, including MCP server configuration if
     * supported.
     * Handles Gemini CLI workspace restrictions by creating prompt files in the
     * project directory.
     */
    private List<String> buildCommand(AgentProvider agent, AgentChatRequest request, String prompt) {
        return subprocessExecutor.buildCommand(agent, request.isSkipPermissions(), request.isInjectMcpTools(),
                request.getAgentArgs(), prompt, request.getWorkingDirectory());
    }

    /**
     * Persist a tool_use parse result from a kompile-managed agent session into the
     * shared tool-call index (the same store the CLI passthrough harvesters write to),
     * so these calls surface in the MCP Hub tool-call catalog. No-op for non-tool_use
     * results or when the writer is unavailable.
     */
    private void recordManagedToolCall(String sessionId, ClaudeStreamParser.ParseResult result,
                                       String agentName, String workingDirectory) {
        if (toolCallWriterService == null || result == null) {
            return;
        }
        if (!"tool_use".equals(result.type()) || result.toolName() == null) {
            return;
        }
        String toolInput = result.toolInput() != null ? result.toolInput().toString() : "";
        toolCallWriterService.record(sessionId, result.toolName(), toolInput,
                agentName, "agent-chat", false, workingDirectory);
    }

    // ========================================================================
    // Context compaction
    // ========================================================================

    /**
     * Auto-compact the request's history against the real context budget of what this
     * chat is talking to. Mutates {@code request.chatHistory} in place; emits a
     * {@code compaction} SSE event (when an emitter is present) so the chat window can
     * mirror the compacted state. Never throws — a failed compaction leaves the
     * request untouched.
     */
    private void applyAutoCompaction(AgentProvider agent, AgentChatRequest request, SseEmitter emitter) {
        if (contextBudgetService == null || historyCompactor == null) return;
        List<AgentChatRequest.ChatHistoryEntry> history = request.getChatHistory();
        if (!request.isIncludeHistory() || history == null || history.isEmpty()) return;

        try {
            ChatContextBudgetService.ContextBudget budget = contextBudgetService.resolve(agent);
            int promptTokens = ChatContextBudgetService.estimateTokens(request.getMessage());
            if (!historyCompactor.needsCompaction(history, budget, promptTokens)) return;

            ChatHistoryCompactor.Result result =
                    historyCompactor.compact(history, budget, null, false, summarizerFor(agent));
            if (!result.compacted()) return;

            request.setChatHistory(result.history());
            if (emitter != null) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("tokensBefore", result.tokensBefore());
                event.put("tokensAfter", result.tokensAfter());
                event.put("contextWindow", budget.contextWindow());
                event.put("model", budget.model());
                event.put("summary", result.summary());
                event.put("usedFallback", result.usedFallback());
                sendEvent(emitter, "compaction", event);
            }
        } catch (Exception e) {
            log.warn("Auto-compaction skipped: {}", e.getMessage());
        }
    }

    /**
     * Force-compact a history for an agent — the manual compact behind
     * {@code POST /api/agents/chat/compact}. Runs the same compactor as
     * auto-compaction with {@code force=true}.
     */
    public ChatHistoryCompactor.Result compactHistory(AgentProvider agent,
                                                      List<AgentChatRequest.ChatHistoryEntry> history,
                                                      String focusInstruction) {
        if (contextBudgetService == null || historyCompactor == null) {
            throw new IllegalStateException("Chat compaction services are not available");
        }
        ChatContextBudgetService.ContextBudget budget = contextBudgetService.resolve(agent);
        return historyCompactor.compact(history, budget, focusInstruction, true, summarizerFor(agent));
    }

    /** The context budget for an agent's lane (staging metadata or model catalog). */
    public ChatContextBudgetService.ContextBudget resolveContextBudget(AgentProvider agent) {
        if (contextBudgetService == null) {
            throw new IllegalStateException("Chat context budget service is not available");
        }
        return contextBudgetService.resolve(agent);
    }

    /**
     * A summarizer that runs through the same lane the chat itself uses, so compaction
     * works with exactly what the user is chatting with: staging generate for local
     * models, a non-streaming completion for API agents, a bare one-shot subprocess
     * for CLI agents.
     */
    private ChatHistoryCompactor.Summarizer summarizerFor(AgentProvider agent) {
        if (localStagingLlmService != null && localStagingLlmService.isLocalAgent(agent.getName())) {
            return (transcript, focus) -> localStagingLlmService.generate(
                    agent.getModelName(),
                    COMPACTION_SUMMARY_SYSTEM_PROMPT + "\n\n" + summaryUserPrompt(transcript, focus),
                    120);
        }
        if (agent.isApiAgent()) {
            if (apiAgentChatExecutor == null) return null;
            return (transcript, focus) -> apiAgentChatExecutor.completeSync(
                    agent, COMPACTION_SUMMARY_SYSTEM_PROMPT, summaryUserPrompt(transcript, focus), 120);
        }
        return (transcript, focus) -> {
            AgentChatRequest summaryRequest = new AgentChatRequest();
            summaryRequest.setAgentName(agent.getName());
            summaryRequest.setMessage(COMPACTION_SUMMARY_SYSTEM_PROMPT + "\n\n"
                    + summaryUserPrompt(transcript, focus));
            summaryRequest.setEnableRag(false);
            summaryRequest.setEnableGraphRag(false);
            summaryRequest.setInjectMcpTools(false);
            summaryRequest.setIncludeHistory(false);
            summaryRequest.setTimeoutSeconds(120);
            SyncChatResult result = executeChatSync(summaryRequest, 120);
            if (result.error() != null) {
                throw new IOException("Summarization via " + agent.getName() + " failed: " + result.error());
            }
            return result.content();
        };
    }

    private static String summaryUserPrompt(String transcript, String focusInstruction) {
        StringBuilder prompt = new StringBuilder("Summarize this conversation:\n\n").append(transcript);
        if (focusInstruction != null && !focusInstruction.isBlank()) {
            prompt.append("\n\nPay particular attention to: ").append(focusInstruction.trim());
        }
        return prompt.toString();
    }

    /** Role-labeled history block for CLI-lane prompt embedding. */
    private String renderHistoryBlock(List<AgentChatRequest.ChatHistoryEntry> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder block = new StringBuilder("## Conversation so far\n");
        boolean any = false;
        for (AgentChatRequest.ChatHistoryEntry entry : history) {
            if (entry == null || entry.getContent() == null || entry.getContent().isBlank()) continue;
            String role = entry.getRole() == null ? "message" : entry.getRole().toLowerCase();
            String label = switch (role) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                default -> entry.getRole();
            };
            block.append(label).append(": ").append(entry.getContent().strip()).append("\n\n");
            any = true;
        }
        if (!any) return "";
        block.append("(End of prior conversation. Respond to the current message below.)");
        return block.toString();
    }

    /**
     * Send SSE event.
     */
    private void sendEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data));
        } catch (IOException e) {
            log.debug("Error sending SSE event: {}", e.getMessage());
        }
    }

    /**
     * Send error event.
     */
    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", errorMessage)));
        } catch (IOException e) {
            log.debug("Error sending error event: {}", e.getMessage());
        }
    }

    /**
     * Build folder context prefix for prompt injection.
     * Lists available files from the folder that the agent can read if needed.
     */
    private String buildFolderContextPrefix(String folderId) {
        if (folderId == null || folderId.isEmpty()) {
            return null;
        }

        if (folderService == null) {
            log.debug("FolderService not available, skipping folder context injection");
            return null;
        }

        try {
            return folderService.buildFileContextPrompt(folderId);
        } catch (Exception e) {
            log.warn("Failed to build folder context for folderId={}: {}", folderId, e.getMessage());
            return null;
        }
    }

    /**
     * Cancel a running process.
     * Actually terminates the underlying OS process and cleans up resources.
     */
    public boolean cancelProcess(String processId) {
        log.info("Cancel requested for process: {}", processId);

        // Try API agent cancellation first
        if (apiAgentChatExecutor != null && apiAgentChatExecutor.isApiStream(processId)) {
            return apiAgentChatExecutor.cancelApiStream(processId);
        }

        // Get and remove the CLI process from tracking map
        Process process = runningProcesses.remove(processId);

        if (process == null) {
            log.debug("No running process found for processId: {}", processId);
            // Check if it's in diagnostic service but already completed
            Optional<ProcessStatus> processOpt = diagnosticService.getProcess(processId);
            if (processOpt.isEmpty()) {
                return false;
            }
            ProcessStatus status = processOpt.get();
            if (status.getState() == ProcessState.RUNNING || status.getState() == ProcessState.STREAMING) {
                // Process was running but we lost the reference - just mark as cancelled
                diagnosticService.processCancelled(processId);
                return true;
            }
            return false;
        }

        // Actually terminate the process
        try {
            if (process.isAlive()) {
                log.info("Destroying process {} (PID: {})", processId, process.pid());

                // First try graceful termination
                process.destroy();

                // Give it a moment to terminate gracefully
                boolean terminated = process.waitFor(2, TimeUnit.SECONDS);

                if (!terminated && process.isAlive()) {
                    // Force kill if still alive
                    log.info("Force killing process {} after graceful termination failed", processId);
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }

                log.info("Process {} terminated successfully", processId);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for process {} to terminate", processId);
            Thread.currentThread().interrupt();
            // Force kill anyway
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        // Update diagnostic service
        diagnosticService.processCancelled(processId);

        // Clean up stream parser session
        streamParser.clearSession(processId);
        streamParser.clearModifiedFiles(processId);

        return true;
    }

    /**
     * Result of a synchronous agent chat execution.
     */
    public record SyncChatResult(
            String content,
            String processId,
            int exitCode,
            long durationMs,
            List<RetrievedDoc> sources,
            List<String> modifiedFiles,
            Map<String, Object> stats,
            String error
    ) {
        public boolean isSuccess() {
            return error == null && exitCode == 0;
        }
    }

    /**
     * Execute chat synchronously, blocking until the agent completes.
     * Used for MCP tool-based inter-agent delegation where we need to return
     * a complete result rather than stream events.
     */
    public SyncChatResult executeChatSync(AgentChatRequest request, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        List<RetrievedDoc> retrievedSources = new ArrayList<>();

        try {
            // Validate agent
            Optional<AgentProvider> agentOpt = agentRegistry.getAgent(request.getAgentName());
            if (agentOpt.isEmpty()) {
                return new SyncChatResult("", null, -1, 0, List.of(), List.of(), null,
                        "Agent not found: " + request.getAgentName());
            }

            AgentProvider agent = agentOpt.get();
            if (!agent.isAvailable()) {
                return new SyncChatResult("", null, -1, 0, List.of(), List.of(), null,
                        "Agent not available: " + agent.getDisplayName());
            }

            // API agents not supported in sync mode (they use HTTP streaming)
            if (agent.isApiAgent()) {
                return new SyncChatResult("", null, -1, 0, List.of(), List.of(), null,
                        "API agents are not supported for synchronous delegation. Use CLI agents.");
            }

            // Same model-aware history bounding as the streaming path (no emitter to notify).
            applyAutoCompaction(agent, request, null);

            // Build prompt with RAG context
            String prompt = buildPromptWithSources(request, retrievedSources, true);

            // Build command
            List<String> command = buildCommand(agent, request, prompt);

            // Create process status for tracking
            ProcessStatus processStatus = diagnosticService.startProcess(agent.getName(), command);
            String processId = processStatus.getId();

            log.info("Executing synchronous agent command: {} (delegationId: {})", agent.getCommand(), processId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().isEmpty()) {
                File workDir = new File(request.getWorkingDirectory());
                if (workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }

            pb.environment().putAll(agent.safeEnvironment());

            Process process = pb.start();
            runningProcesses.put(processId, process);
            diagnosticService.processStarted(processId, process.pid());

            StringBuilder fullResponse = new StringBuilder();
            Map<String, Object> chatStats = new LinkedHashMap<>();
            boolean useStreamParser = streamParser.supportsStreamJson(agent.getName());

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                diagnosticService.processStreaming(processId);
                String line;

                while ((line = reader.readLine()) != null) {
                    if (!runningProcesses.containsKey(processId)) {
                        break; // Cancelled
                    }

                    diagnosticService.outputReceived(processId, line);

                    if (useStreamParser) {
                        ClaudeStreamParser.ParseResult result = streamParser.parseLine(processId, line);
                        if (result != null) {
                            if (result.textContent() != null && !result.textContent().isEmpty()) {
                                fullResponse.append(result.textContent());
                            }
                            recordManagedToolCall(processId, result, agent.getName(),
                                    request.getWorkingDirectory());
                            if (result.isResult()) {
                                chatStats.put("durationMs", result.durationMs() != null ? result.durationMs() : 0);
                                chatStats.put("costUsd", result.costUsd() != null ? result.costUsd() : 0.0);
                                chatStats.put("numTurns", result.numTurns() != null ? result.numTurns() : 0);
                                chatStats.put("isError", result.isError());
                                Map<String, Object> tokenMetrics = streamParser.getTokenMetrics(processId);
                                if (tokenMetrics != null) {
                                    chatStats.put("tokenMetrics", tokenMetrics);
                                }
                            }
                        }
                    } else {
                        fullResponse.append(line).append("\n");
                    }
                }

                boolean completed;
                long effectiveTimeout = timeoutSeconds <= 0 ? 3600 : timeoutSeconds;
                completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    diagnosticService.processTimedOut(processId);
                    return new SyncChatResult(fullResponse.toString(), processId, -1,
                            System.currentTimeMillis() - startTime, retrievedSources, List.of(),
                            chatStats, "Process timed out after " + timeoutSeconds + "s");
                }

                int exitCode = process.exitValue();
                diagnosticService.processCompleted(processId, exitCode);

                List<String> modifiedFiles = new ArrayList<>();
                Object mf = streamParser.getModifiedFiles(processId);
                if (mf instanceof List<?> list) {
                    for (Object item : list) {
                        modifiedFiles.add(item.toString());
                    }
                }

                streamParser.clearSession(processId);
                streamParser.clearModifiedFiles(processId);

                long duration = System.currentTimeMillis() - startTime;

                if (exitCode != 0) {
                    return new SyncChatResult(fullResponse.toString(), processId, exitCode,
                            duration, retrievedSources, modifiedFiles, chatStats,
                            "Process exited with code: " + exitCode);
                }

                return new SyncChatResult(fullResponse.toString(), processId, exitCode,
                        duration, retrievedSources, modifiedFiles, chatStats, null);

            } finally {
                runningProcesses.remove(processId);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

        } catch (Exception e) {
            log.error("Error in synchronous agent chat", e);
            return new SyncChatResult("", null, -1,
                    System.currentTimeMillis() - startTime, retrievedSources, List.of(), null,
                    "Execution error: " + e.getMessage());
        }
    }

    /**
     * Check if a process is currently running.
     */
    public boolean isProcessRunning(String processId) {
        Process process = runningProcesses.get(processId);
        return process != null && process.isAlive();
    }

    /**
     * Get the count of currently running processes.
     */
    public int getRunningProcessCount() {
        return runningProcesses.size();
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
