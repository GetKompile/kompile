/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.crawl.CrawlRunStore;
import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Production crawl-and-reason REPL.
 *
 * <p>This command is intentionally attached to the normal CLI. It does not
 * duplicate the FPNA test harness: the app owns model loading and crawl
 * execution, while this process owns operator controls, tool policy and
 * durable run checkpoints.</p>
 */
@CommandLine.Command(
        name = "crawl",
        description = "Run and supervise a production unified crawl with graph reasoning.",
        mixinStandardHelpOptions = true)
public final class CrawlCommand implements Callable<Integer> {
    @CommandLine.Option(names = "--url", description = "Base URL of the kompile-app server.")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, description = "kompile-app port on localhost.")
    private Integer port;

    @CommandLine.Option(names = "--session-id", description = "Run/session id (generated when absent).")
    private String sessionId;

    @CommandLine.Option(names = {"--resume", "-r"}, description = "Resume the local transcript/run id.")
    private String resumeId;

    @CommandLine.Option(names = "--server-agent", defaultValue = "codex-cli",
            description = "Server-side chat agent used for LLM transport.")
    private String serverAgent;

    @CommandLine.Option(names = "--offline",
            description = "Run a local, non-interactive crawl worker without the chat server.")
    private boolean offline;

    @CommandLine.Option(names = "--local-agent", defaultValue = "crawl-worker",
            description = "Local agent profile used by --offline (default: ${DEFAULT-VALUE}).")
    private String localAgent;

    @CommandLine.Option(names = "--model",
            description = "Override the locally configured model used by --offline.")
    private String model;

    @CommandLine.Option(names = "--crawl-url",
            description = "Optional distributed crawl-manager URL for --offline; omitted uses the project-local MCP backend.")
    private String crawlUrl;

    @CommandLine.Option(names = "--timeout", defaultValue = "0", paramLabel = "SECONDS",
            description = "Offline worker timeout in seconds (0 = no timeout).")
    private long timeoutSeconds;

    @CommandLine.Option(names = "--mode",
            description = "Control mode: auto, supervised, or single-step "
                    + "(default: auto offline, supervised online).")
    private String mode;

    @CommandLine.Option(names = "--rag", negatable = true, defaultValue = "true",
            description = "Enable server-side RAG context.")
    private boolean rag;

    @CommandLine.Option(names = "--memory", negatable = true, defaultValue = "true",
            description = "Persist/use CLI conversation memory.")
    private boolean memory;

    @CommandLine.Option(names = "--request-file",
            description = "JSON file containing the production UnifiedCrawlRequest. Implies --headless.")
    private Path requestFile;

    @CommandLine.Option(names = "--document", paramLabel = "PATH_OR_URL",
            description = "Exact document to add to a knowledge base; repeat for multiple documents.")
    private List<String> documents = new ArrayList<>();

    @CommandLine.Option(names = "--knowledge-base", paramLabel = "ID_OR_NAME",
            description = "Target knowledge-base/fact-sheet id or name for --document.")
    private String knowledgeBase;

    @CommandLine.Option(names = "--pipeline-id",
            description = "Preferred installed pipeline id for --document; omit to let the worker discover one.")
    private String pipelineId;

    @CommandLine.Option(names = "--prompt",
            description = "Initial agent instruction. Implies --headless.")
    private String prompt;

    @CommandLine.Option(names = "--headless",
            description = "Run one bounded agent instruction without starting the interactive TUI.")
    private boolean headless;

    @CommandLine.Option(names = "--clear-graph",
            description = "Ask crawl_control to clear the request fact-sheet graph before preflight.")
    private boolean clearGraph;

    @Override
    public Integer call() {
        if (requestFile != null && documents != null && !documents.isEmpty()) {
            System.err.println("--request-file and --document describe different crawl shapes; choose one.");
            return 2;
        }
        if ((knowledgeBase != null && !knowledgeBase.isBlank()
                || pipelineId != null && !pipelineId.isBlank())
                && (documents == null || documents.isEmpty())) {
            System.err.println("--knowledge-base and --pipeline-id require at least one --document.");
            return 2;
        }

        if (resumeId != null && !resumeId.isBlank()) {
            sessionId = resumeId;
            if (!ChatHistory.exists(sessionId)) {
                System.err.println("No saved crawl transcript found for session: " + sessionId);
                return 1;
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "crawl-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String initialMessage;
        try {
            initialMessage = loadInitialMessage();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Unable to load crawl request: " + e.getMessage());
            return 2;
        }

        if (offline) {
            if (initialMessage == null || initialMessage.isBlank()) {
                System.err.println("Offline crawl requires --document, --request-file, or --prompt.");
                return 2;
            }
            return runOffline(initialMessage);
        }

        String requestedUrl = resolveUrl();
        if (requestedUrl == null || requestedUrl.isBlank()) {
            System.err.println("crawl requires --url/--port or a configured kompile-app endpoint.");
            return 1;
        }

        // MCP uses the /mcp SSE transport, while the agent loop calls the app's
        // REST endpoint at /api/agents/chat/stream. Keep those base URLs distinct.
        String appUrl = normalizeAppUrl(requestedUrl);
        String mcpUrl = normalizeMcpUrl(requestedUrl);

        AgentRunController controller = new AgentRunController(parseMode(mode, false));
        System.out.println("Connecting to " + mcpUrl + " ...");
        try (McpSseClient client = new McpSseClient(mcpUrl)) {
            client.connect();
            client.initialize();
            client.notifyInitialized();
            createChatSession(client);

            ChatRepl repl = new ChatRepl(client, appUrl, sessionId, rag,
                    serverAgent, memory, null, true);
            CrawlRunStore store = new CrawlRunStore(sessionId, client.getObjectMapper());
            AgentRunController.Snapshot prior = resumeId == null ? null : store.latestCheckpoint();
            repl.configureCrawlControl(controller, store);
            if (prior != null) {
                controller.restore(prior);
                store.event("resumed", "checkpoint");
                store.checkpoint(controller, "resumed");
            }

            System.out.println("Crawl run: " + sessionId);
            System.out.println("Mode: " + controller.mode().name().toLowerCase(Locale.ROOT)
                    + (controller.mode() == AgentRunController.Mode.SUPERVISED
                    ? " (mutations require /crawl approve)" : ""));
            if (headless || requestFile != null || documents != null && !documents.isEmpty()
                    || prompt != null && !prompt.isBlank()) {
                if (initialMessage == null || initialMessage.isBlank()) {
                    System.err.println("Headless crawl requires --document, --request-file, or --prompt.");
                    controller.stop();
                    return 1;
                }
                repl.runHeadless(initialMessage);
            } else {
                repl.run();
            }
            return 0;
        } catch (Exception e) {
            controller.stop();
            System.err.println("Crawl command failed: " + e.getMessage());
            return 1;
        }
    }

    private int runOffline(String initialMessage) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        String workerAgent = localAgent == null || localAgent.isBlank() ? "crawl-worker" : localAgent.trim();
        String workerCrawlUrl = crawlUrl == null || crawlUrl.isBlank()
                ? null
                : trimTrailingSlashes(crawlUrl);
        AgentRunController controller = new AgentRunController(parseMode(mode, true));
        CrawlRunStore store = new CrawlRunStore(sessionId, mapper);
        AgentRunController.Snapshot prior = resumeId == null ? null : store.latestCheckpoint();
        if (prior != null) {
            controller.restore(prior);
            store.event("resumed", "checkpoint");
        }
        String crawlBackend = workerCrawlUrl == null ? "(project-local MCP)" : workerCrawlUrl;
        store.open(controller, crawlBackend, workerAgent);

        System.out.println("Offline crawl worker: " + sessionId);
        System.out.println("Agent: " + workerAgent);
        System.out.println("Crawl backend: " + crawlBackend);
        System.out.println("Mode: " + controller.mode().name().toLowerCase(Locale.ROOT));

        Path workDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                initialMessage,
                sessionId,
                resumeId != null && !resumeId.isBlank(),
                workerAgent,
                blankToNull(model),
                HeadlessAgentRunner.OutputMode.TEXT,
                workDir,
                Math.max(0, timeoutSeconds) * 1000L,
                null,
                workerCrawlUrl,
                controller);
        try {
            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);
            store.event("offline_worker_completed", "exitCode=" + result.exitCode());
            store.checkpoint(controller, "offline_worker_closed");
            return result.exitCode();
        } catch (RuntimeException e) {
            controller.stop();
            store.event("offline_worker_failed", String.valueOf(e.getMessage()));
            store.checkpoint(controller, "offline_worker_failed");
            System.err.println("Offline crawl worker failed: " + e.getMessage());
            return 1;
        }
    }

    private String resolveUrl() {
        if (url != null && !url.isBlank()) return url;
        if (port != null) return "http://localhost:" + port;
        return KompileServiceEndpoints.resolve(KompileService.CHAT).baseUrl();
    }

    private String normalizeAppUrl(String requestedUrl) {
        String value = trimTrailingSlashes(requestedUrl);
        if (value.endsWith("/mcp")) {
            return value.substring(0, value.length() - "/mcp".length());
        }
        return value;
    }

    private String normalizeMcpUrl(String requestedUrl) {
        String value = trimTrailingSlashes(requestedUrl);
        return value.endsWith("/mcp") ? value : value + "/mcp";
    }

    private String trimTrailingSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/") && result.length() > "http://".length()) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String loadInitialMessage() throws IOException {
        String generated = null;
        if (requestFile != null) {
            if (!Files.isRegularFile(requestFile)) {
                throw new IOException("Request file does not exist: " + requestFile);
            }
            String request = Files.readString(requestFile);
            if (request.isBlank()) throw new IOException("Request file is empty: " + requestFile);
            generated = buildUnifiedRequestInstruction(request, clearGraph);
        } else if (documents != null && !documents.isEmpty()) {
            generated = buildDocumentInstruction(documents, knowledgeBase, pipelineId, clearGraph);
        }

        if (prompt == null || prompt.isBlank()) return generated;
        if (generated == null || generated.isBlank()) return prompt.trim();
        return generated + "\n\n<additional_operator_instruction>\n" + prompt.trim()
                + "\n</additional_operator_instruction>";
    }

    static String buildUnifiedRequestInstruction(String request, boolean clearGraph) {
        String clearInstruction = clearGraph
                ? "Before preflight, call crawl_control operation=clear_graph using the request factSheetId. "
                : "";
        return clearInstruction + "Execute this production crawl as a bounded indexing worker. "
                + "First call crawl_discover section=all to verify available loaders, pipeline kinds, runtimes, "
                + "and knowledge bases. Then run crawl_control operation=preflight and start the exact "
                + "UnifiedCrawlRequest below using operation=start and body equal to the JSON object. "
                + "Poll operation=status until the job reaches a terminal state. If it fails, preserve the job id, "
                + "retrieve operation=transcript, and report the failure; do not bypass crawl_control. "
                + "After completion, call operation=graph_stats and operation=transcript, then summarize indexed "
                + "documents, graph extraction, and the target knowledge base. Verify searchable content with "
                + "knowledge_status and knowledge_search. The same MCP session exposes memory and, when configured, semantic_memory; "
                + "consult or persist relevant project context when useful.\n\n"
                + "<unified_crawl_request>\n" + request.trim()
                + "\n</unified_crawl_request>";
    }

    static String buildDocumentInstruction(List<String> selectedDocuments, String targetKnowledgeBase,
                                           String preferredPipelineId, boolean clearGraph) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ObjectNode selection = mapper.createObjectNode();
        ArrayNode docs = selection.putArray("documents");
        for (String raw : selectedDocuments) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            ObjectNode document = docs.addObject();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                document.put("url", value);
            } else {
                document.put("path", value);
            }
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("--document values must not be blank.");
        }
        String knowledgeBaseValue = blankToNull(targetKnowledgeBase);
        if (knowledgeBaseValue != null) {
            ObjectNode kb = selection.putObject("knowledgeBase");
            try {
                kb.put("id", Long.parseLong(knowledgeBaseValue));
            } catch (NumberFormatException ignored) {
                kb.put("name", knowledgeBaseValue);
            }
        }
        String pipeline = blankToNull(preferredPipelineId);
        if (pipeline != null) {
            selection.put("defaultPipelineId", pipeline);
        }

        String clearInstruction = clearGraph
                ? "Resolve the target fact-sheet id with crawl_discover section=knowledge_bases and call "
                + "crawl_control operation=clear_graph before launching the crawl. "
                : "";
        return clearInstruction + "Index only the selected documents below into the requested knowledge base. "
                + "First call crawl_discover section=all and use its loaders, chunkers, pipeline kinds, routes, "
                + "runtime settings, and knowledge-base catalog to finish the configuration. Then call "
                + "crawl_documents with the exact documents and target below, adding only supported pipeline and "
                + "runtime fields. Poll crawl_control operation=status with the returned jobId until terminal. "
                + "On failure retrieve the transcript and preserve diagnostics. On success retrieve graph_stats "
                + "and transcript and report job id, fact-sheet id, document counts, and chosen pipeline. Verify "
                + "the resulting local or remote knowledge base with knowledge_status and knowledge_search. The same "
                + "MCP session exposes memory and, when configured, semantic_memory for relevant project context.\n\n"
                + "<selected_document_crawl>\n" + selection.toPrettyString()
                + "\n</selected_document_crawl>";
    }

    private AgentRunController.Mode parseMode(String value, boolean offlineRun) {
        if (value == null || value.isBlank()) {
            return offlineRun ? AgentRunController.Mode.AUTO : AgentRunController.Mode.SUPERVISED;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AgentRunController.Mode.AUTO;
            case "single-step", "single_step", "step" -> AgentRunController.Mode.SINGLE_STEP;
            default -> AgentRunController.Mode.SUPERVISED;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void createChatSession(McpSseClient client) throws Exception {
        ObjectNode args = client.getObjectMapper().createObjectNode();
        args.put("sessionId", sessionId);
        args.put("agentName", serverAgent);
        args.put("enableRag", rag);
        args.put("enableSemanticSearch", true);
        args.put("enableKeywordSearch", true);
        args.put("semanticK", 5);
        args.put("keywordK", 5);
        args.put("maxHistoryMessages", 50);
        args.put("similarityThreshold", 0.5);
        args.put("systemPrompt", "");
        client.callTool("create_chat_session", args);
    }
}
