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

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessState;
import ai.kompile.core.agent.ProcessStatus;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final DocumentRetriever keywordRetriever;
    private final VectorStore vectorStore;
    private final ExecutorService executorService;
    private final BuiltInToolDiscoveryService toolDiscoveryService;
    private final ServerPortService serverPortService;
    private final FolderService folderService;

    // Track running processes by processId for interrupt support
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

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

    @Autowired
    public AgentChatService(
            AgentRegistryService agentRegistry,
            AgentProcessDiagnosticService diagnosticService,
            ClaudeStreamParser streamParser,
            List<DocumentRetriever> keywordRetrievers,
            List<VectorStore> vectorStores,
            @Autowired(required = false) BuiltInToolDiscoveryService toolDiscoveryService,
            ServerPortService serverPortService,
            @Autowired(required = false) FolderService folderService) {

        this.agentRegistry = agentRegistry;
        this.diagnosticService = diagnosticService;
        this.streamParser = streamParser;
        this.executorService = Executors.newCachedThreadPool();
        this.toolDiscoveryService = toolDiscoveryService;
        this.serverPortService = serverPortService;
        this.folderService = folderService;

        // Select non-NoOp implementations
        this.keywordRetriever = keywordRetrievers.stream()
                .filter(r -> !(r instanceof NoOpDocumentRetrieverImpl))
                .findFirst()
                .orElse(keywordRetrievers.isEmpty() ? new NoOpDocumentRetrieverImpl() : keywordRetrievers.get(0));

        this.vectorStore = vectorStores.stream()
                .filter(v -> !(v instanceof NoOpVectorStoreImpl))
                .findFirst()
                .orElse(vectorStores.isEmpty() ? new NoOpVectorStoreImpl() : vectorStores.get(0));

        log.info("AgentChatService initialized with KeywordRetriever: {}, VectorStore: {}, MCP Tools: {}",
                this.keywordRetriever.getClass().getSimpleName(),
                this.vectorStore.getClass().getSimpleName(),
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

                // Build command first so we can pass it to diagnostics
                List<String> command = buildCommand(agent, request, buildPromptWithSources(request, retrievedSources));

                // Create process status for tracking
                processStatus = diagnosticService.startProcess(agent.getName(), command);
                String processId = processStatus.getId();

                // Send sources event if RAG was used
                if (!retrievedSources.isEmpty()) {
                    sendEvent(emitter, "sources", formatSourcesForClient(retrievedSources));
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
                        "ragEnabled", request.isEnableRag()));

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
                                if (result.isResult()) {
                                    // Send completion stats
                                    sendEvent(emitter, "stats", Map.of(
                                            "durationMs", result.durationMs() != null ? result.durationMs() : 0,
                                            "costUsd", result.costUsd() != null ? result.costUsd() : 0.0,
                                            "numTurns", result.numTurns() != null ? result.numTurns() : 0,
                                            "isError", result.isError()));
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
                        // No timeout - wait indefinitely
                        log.info("Waiting for process completion with no timeout");
                        runningProcess.waitFor();
                        completed = true;
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
     * Build the final prompt, optionally augmented with folder context and RAG context.
     * MCP tools are now injected automatically via the agent's native MCP server
     * support.
     * Also populates the retrievedSources list for client-side display.
     */
    private String buildPromptWithSources(AgentChatRequest request, List<RetrievedDoc> retrievedSources) {
        String userMessage = request.getMessage();
        StringBuilder promptBuilder = new StringBuilder();

        // Inject folder context if folderId is provided
        String folderContext = buildFolderContextPrefix(request.getFolderId());
        if (folderContext != null && !folderContext.isEmpty()) {
            promptBuilder.append(folderContext);
            promptBuilder.append("\n---\n\n");
        }

        // Note: MCP tools are now injected via native CLI flags (e.g., --mcp-server)
        // rather than being embedded in the prompt text

        if (!request.isEnableRag()) {
            log.debug("RAG disabled, using original message");
            promptBuilder.append(userMessage);
            return promptBuilder.toString();
        }

        log.info("RAG enabled, retrieving context for: {}",
                userMessage.substring(0, Math.min(100, userMessage.length())));

        // Retrieve documents
        List<RetrievedDoc> retrievedDocs = retrieveDocuments(
                userMessage,
                request.getRagMaxResults(),
                request.getRagSimilarityThreshold(),
                request.isIncludeKeywordSearch(),
                request.isIncludeSemanticSearch());

        if (retrievedDocs.isEmpty()) {
            log.warn("No documents retrieved, using original message");
            promptBuilder.append(userMessage);
            return promptBuilder.toString();
        }

        // Populate sources for client
        retrievedSources.addAll(retrievedDocs);

        // Format context
        String formattedContext = formatContext(retrievedDocs);
        log.info("Retrieved {} documents for context augmentation", retrievedDocs.size());

        // Build augmented prompt
        promptBuilder.append(String.format(RAG_CONTEXT_TEMPLATE, formattedContext, userMessage));
        return promptBuilder.toString();
    }

    /**
     * Format sources for client-side display.
     */
    private List<Map<String, Object>> formatSourcesForClient(List<RetrievedDoc> docs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        int index = 1;

        for (RetrievedDoc doc : docs) {
            if (doc.getContent() == null || doc.getContent().isEmpty()) {
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
            String content = doc.getContent();
            String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
            source.put("preview", preview);

            // Full content for expansion
            source.put("content", content.length() > 2000 ? content.substring(0, 2000) + "... [truncated]" : content);

            // Include relevant metadata
            if (doc.getMetadata() != null) {
                Map<String, Object> safeMetadata = new HashMap<>();
                for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                    // Only include string/number metadata, skip large objects
                    if (entry.getValue() instanceof String || entry.getValue() instanceof Number) {
                        safeMetadata.put(entry.getKey(), entry.getValue());
                    }
                }
                source.put("metadata", safeMetadata);
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
        if (includeKeyword && !(keywordRetriever instanceof NoOpDocumentRetrieverImpl)) {
            try {
                log.debug("Performing keyword search (k={})", resultsPerRetriever);
                List<RetrievedDoc> keywordDocs = keywordRetriever.retrieveWithDetails(query, resultsPerRetriever);

                if (keywordDocs != null) {
                    keywordDocs.stream()
                            .filter(doc -> doc != null && doc.getContent() != null && !doc.getContent().isEmpty())
                            .filter(doc -> !doc.getContent().startsWith("Error:"))
                            .forEach(combinedDocs::add);

                    log.debug("Keyword search returned {} valid documents",
                            keywordDocs.stream().filter(d -> d != null && d.getContent() != null).count());
                }
            } catch (Exception e) {
                log.error("Error during keyword retrieval: {}", e.getMessage());
            }
        }

        // Semantic search
        if (includeSemantic && !(vectorStore instanceof NoOpVectorStoreImpl)) {
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
                log.error("Error during semantic retrieval: {}", e.getMessage());
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
            String content = doc.getContent();
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
     * Build the CLI command for the agent, including MCP server configuration if
     * supported.
     * Handles Gemini CLI workspace restrictions by creating prompt files in the
     * project directory.
     */
    private List<String> buildCommand(AgentProvider agent, AgentChatRequest request, String prompt) {
        List<String> command = new ArrayList<>();
        command.add(agent.getCommand());

        // Add skip permissions flag if enabled
        if (request.isSkipPermissions() && agent.getSkipPermissionsFlag() != null) {
            command.add(agent.getSkipPermissionsFlag());
        }

        // Add MCP server configuration if agent supports it and tools are available
        if (request.isInjectMcpTools() && agent.isMcpSupported() && toolDiscoveryService != null) {
            addMcpServerArgs(command, agent);
        }

        // Add agent-specific args
        command.addAll(agent.safeArgs());

        // Add the prompt - handle Gemini's workspace restrictions
        command.add("-p");

        // For Gemini CLI, we need to use a prompt file in the project directory
        // because Gemini has workspace restrictions and can't read from /tmp/
        if (isGeminiAgent(agent)) {
            try {
                String promptFilePath = createGeminiPromptFile(request.getWorkingDirectory(), prompt);
                // Use @ prefix to indicate file path for prompt
                command.add("@" + promptFilePath);
                log.debug("Created Gemini prompt file at: {}", promptFilePath);
            } catch (IOException e) {
                log.warn("Failed to create Gemini prompt file, using inline prompt: {}", e.getMessage());
                command.add(prompt);
            }
        } else {
            command.add(prompt);
        }

        return command;
    }

    /**
     * Check if the agent is a Gemini CLI agent.
     */
    private boolean isGeminiAgent(AgentProvider agent) {
        if (agent == null || agent.getName() == null) {
            return false;
        }
        String name = agent.getName().toLowerCase();
        String command = agent.getCommand() != null ? agent.getCommand().toLowerCase() : "";
        return name.contains("gemini") || command.contains("gemini");
    }

    /**
     * Create a prompt file in the Gemini-accessible directory.
     * Gemini CLI can only read files within:
     * - The project directory
     * - The user's ~/.gemini/tmp/ directory
     *
     * We prefer creating files in the project's .gemini/tmp/ directory.
     */
    private String createGeminiPromptFile(String workingDirectory, String prompt) throws IOException {
        Path promptDir;

        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            // Create in project's .gemini/tmp/ directory
            promptDir = Path.of(workingDirectory, ".gemini", "tmp");
        } else {
            // Fallback to user's ~/.gemini/tmp/
            String userHome = System.getProperty("user.home");
            promptDir = Path.of(userHome, ".gemini", "tmp");
        }

        // Ensure directory exists
        Files.createDirectories(promptDir);

        // Create unique prompt file
        String filename = "agent-prompt-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8) + ".txt";
        Path promptFile = promptDir.resolve(filename);

        // Write prompt to file
        Files.writeString(promptFile, prompt);

        // Schedule cleanup after 5 minutes
        schedulePromptFileCleanup(promptFile);

        return promptFile.toAbsolutePath().toString();
    }

    /**
     * Schedule cleanup of the prompt file after a delay.
     */
    private void schedulePromptFileCleanup(Path promptFile) {
        executorService.submit(() -> {
            try {
                // Wait 5 minutes before cleanup
                Thread.sleep(5 * 60 * 1000);
                if (Files.exists(promptFile)) {
                    Files.delete(promptFile);
                    log.debug("Cleaned up prompt file: {}", promptFile);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.debug("Failed to cleanup prompt file: {}", e.getMessage());
            }
        });
    }

    /**
     * Add MCP server arguments to the command based on the agent's detected
     * capabilities.
     */
    private void addMcpServerArgs(List<String> command, AgentProvider agent) {
        if (toolDiscoveryService == null || toolDiscoveryService.getDiscoveredTools().isEmpty()) {
            return;
        }

        String mcpServerUrl = serverPortService.getMcpApiUrl();

        // Use the appropriate flag based on what was detected
        if (agent.getMcpServerFlag() != null) {
            // For Claude CLI: --mcp-server "name:url"
            // Format: --mcp-server "kompile-rag:http://localhost:PORT/api/mcp"
            command.add(agent.getMcpServerFlag());
            command.add("kompile-rag:" + mcpServerUrl);
            log.info("Injecting MCP server for agent '{}': {} kompile-rag:{}",
                    agent.getName(), agent.getMcpServerFlag(), mcpServerUrl);
        } else if (agent.getMcpConfigFlag() != null) {
            // For agents that need a config file, we could write one
            // This is a fallback - most CLIs should support inline server specification
            log.debug("Agent '{}' requires config file for MCP - skipping auto-injection",
                    agent.getName());
        }
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

        // Get and remove the process from tracking map
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
}
