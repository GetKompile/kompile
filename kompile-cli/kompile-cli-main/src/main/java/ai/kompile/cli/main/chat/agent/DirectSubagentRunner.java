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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.ModelFailoverController;
import ai.kompile.cli.main.chat.SessionModelLadder;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SubagentChatConfig;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.LoopDetector;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.render.OutputTruncator;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.workflow.WorkflowLaunch;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs subagents through the selected provider transport (HTTP or provider-owned login; no kompile-app server needed).
 * Creates a fresh DirectLlmClient for each subagent invocation with its own conversation
 * history, ensuring context isolation between the parent and subagent.
 *
 * Each subagent runs a complete agentic loop: send prompt → receive response →
 * execute tool calls → send results → repeat until text-only response.
 */
public class DirectSubagentRunner implements SubagentRunner, AutoCloseable {

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ChatConfig chatConfig;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final TerminalRenderer renderer;
    private final OutputTruncator outputTruncator = new OutputTruncator();
    private volatile LifecycleListener lifecycleListener;
    private volatile java.util.function.BiConsumer<String, String> asyncCompletionListener;

    @Override
    public void setAsyncCompletionListener(java.util.function.BiConsumer<String, String> listener) {
        this.asyncCompletionListener = listener;
    }
    private volatile ReminderManager reminderManager;
    private final Map<String, DirectSession> sessions = new ConcurrentHashMap<>();
    private static final int MAX_RETAINED_SESSIONS = 16;
    /** Bounded compact-and-retry attempts per child session after an overflow. */
    private static final int MAX_OVERFLOW_RECOVERIES = 2;

    private static final class DirectSession {
        private final String id;
        private final AgentConfig agent;
        private final ToolContext parentContext;
        private final DirectLlmClient client;
        private final String systemPrompt;
        private volatile String modelOverride;
        /** The transport retains this isolated config, never the parent's defaults. */
        private final ChatConfig config;
        private final DirectSubagentSupervision supervision;
        private final DirectSubagentCompactor compactor;
        /** The workflow participant this child runs as; {@code null} outside a workflow team. */
        private final WorkflowTeamEnforcement workflow;
        /** How rows name this child (see {@link DirectSubagentRunner#displayName}). */
        private final String label;
        /** Ledger for resumable runs; {@code null} means this run is not resumable. */
        private final SubagentLedger ledger;
        /** How many committed projection entries the ledger already holds. */
        private volatile int ledgerFlushed;
        private final LoopDetector loopDetector = new LoopDetector();
        private final AtomicInteger overflowRecoveryCount = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> followUps = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean cancelled;
        private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);
        private volatile Thread ownerThread;
        private SubagentAdmission.Lease admission;
        private String provider;
        private volatile ToolContext activeToolContext;
        private volatile long lastTouched = System.currentTimeMillis();
        /** Transcript-only stream boundary; reasoning never enters the returned answer. */
        private String streamKind = "";
        private final ai.kompile.cli.main.chat.SubagentTelemetry telemetry =
                new ai.kompile.cli.main.chat.SubagentTelemetry();

        private DirectSession(String id, AgentConfig agent, ToolContext parentContext,
                              DirectLlmClient client, String systemPrompt,
                              String modelOverride, ChatConfig config, AtomicBoolean cancelled,
                              DirectSubagentSupervision supervision,
                              DirectSubagentCompactor compactor,
                              WorkflowTeamEnforcement workflow, String label) {
            this(id, agent, parentContext, client, systemPrompt, modelOverride, config, cancelled,
                    supervision, compactor, workflow, label, null);
        }

        private DirectSession(String id, AgentConfig agent, ToolContext parentContext,
                              DirectLlmClient client, String systemPrompt,
                              String modelOverride, ChatConfig config, AtomicBoolean cancelled,
                              DirectSubagentSupervision supervision,
                              DirectSubagentCompactor compactor,
                              WorkflowTeamEnforcement workflow, String label,
                              SubagentLedger ledger) {
            this.id = id;
            this.agent = agent;
            this.parentContext = parentContext;
            this.client = client;
            this.systemPrompt = systemPrompt;
            this.modelOverride = modelOverride;
            this.config = config;
            this.cancelled = cancelled;
            this.supervision = supervision;
            this.compactor = compactor;
            this.workflow = workflow;
            this.label = label;
            this.ledger = ledger;
        }
    }

    public DirectSubagentRunner(ChatConfig chatConfig, ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry, PermissionService permissionService,
                                 TerminalRenderer renderer) {
        this.chatConfig = chatConfig;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.renderer = renderer;
    }

    /** Isolated transport per child; provider-owned login routes remain supported. */
    protected DirectLlmClient createClient(ChatConfig config, ToolContext context) {
        return new DirectLlmClient(config, objectMapper, context.getWorkingDirectory());
    }

    @Override
    public void setLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = listener;
    }


    @Override
    public void setReminderManager(ReminderManager reminderManager) {
        this.reminderManager = reminderManager;
    }

    @Override
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception {
        return runSubagent(agent, prompt, parentContext, SubagentRunOptions.DEFAULT);
    }

    @Override
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext,
                              SubagentRunOptions options) throws Exception {
        boolean resumable = options != null && options.effectiveResumable();
        if (resumable && agent.getWorkflowParticipant() != null
                && !agent.getWorkflowParticipant().isBlank()) {
            throw new IllegalArgumentException(
                    "Workflow participant subagents cannot be resumable: the team's delegation "
                            + "graph is turn-scoped, so there is no correct context to resume into.");
        }
        agent = agent.toBuilder()
                .enabledTools(agent.getEnabledTools() == null ? java.util.Set.of("*") : java.util.Set.copyOf(agent.getEnabledTools()))
                .permissionOverrides(agent.getPermissionOverrides() == null ? Map.of() : Map.copyOf(agent.getPermissionOverrides()))
                .allowedModels(agent.getAllowedModels() == null ? List.of() : List.copyOf(agent.getAllowedModels())).build();
        long startTime = System.currentTimeMillis();
        // Resumable runs carry the subagent- id prefix — the identity boundary that
        // keeps them out of every main resume listing (and out of their own callers'
        // session-id namespaces) by construction. Non-resumable ids are unchanged.
        String subagentId = resumable
                ? "subagent-" + agent.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + "-"
                        + UUID.randomUUID().toString().substring(0, 8)
                : agent.getName() + "-" + UUID.randomUUID();
        SubagentLedger ledger = null;
        if (resumable) {
            java.nio.file.Path conversationsDir = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("conversations");
            ledger = new SubagentLedger(
                    SubagentLedger.fileFor(conversationsDir, subagentId), objectMapper);
        }
        // Create an isolated, retained LLM history for this subagent while
        // sharing the parent turn's cancellation signal. Retention is what lets
        // the selected subagent accept later follow-up messages. A workflow
        // participant runs on its bound model with that route's own credential.
        WorkflowTeamEnforcement workflow =
                WorkflowSessionContext.participantEnforcement(agent.getWorkflowParticipant(), parentContext);
        WorkflowTeam.ModelBinding binding = workflow == null ? null
                : workflow.team().participant(workflow.callerParticipant()).model();
        String label = displayName(agent, workflow == null ? null : workflow.callerParticipant(), binding);
        ChatConfig childConfig = WorkflowLaunch.configFor(chatConfig, binding);
        childConfig = SubagentChatConfig.configure(childConfig,
                binding != null ? binding.provider() : agent.getProviderOverride(),
                agent.getModelOverride(), agent.getThinkingOverride(),
                binding != null ? binding.authenticationMethod() : agent.getAuthenticationMethodOverride());
        if (binding != null && !childConfig.isValid()) {
            throw new IllegalArgumentException("Workflow participant '" + workflow.callerParticipant()
                    + "' runs on " + binding.label() + ", which is not usable here (no stored credential "
                    + "or endpoint). Configure it with kompile chat --setup, or the user can change the "
                    + "participant's model with /workflow model " + workflow.callerParticipant() + ".");
        }
        String model = childConfig.getModel();
        if (!agent.isModelAllowed(model)) {
            throw new IllegalArgumentException("Model is not allowed for " + agent.getName() + ": " + model);
        }
        childConfig.setModel(model);
        if (agent.getThinkingOverride() != null && !agent.getThinkingOverride().isBlank()) {
            childConfig.setThinking(agent.getThinkingOverride());
        }
        try (SubagentAdmission.Lease admission = SubagentAdmission.acquire(childConfig.getProvider(), model)) {
        DirectLlmClient subClient = createClient(childConfig, parentContext);
        subClient.setPromptCacheSessionId(subagentId);
        AtomicBoolean subagentCancelled = new AtomicBoolean(false);
        subClient.setCancellationCheck(
                () -> subagentCancelled.get() || parentContext.isAborted());
        DirectSubagentCompactor compactor = new DirectSubagentCompactor(objectMapper);
        compactor.configure(childConfig, model);
        subClient.setContextWindowTokens(compactor.contextWindowTokens());
        subClient.setWireMaxOutputTokens(compactor.wireMaxOutputTokens());
        String systemPrompt = agent.getSystemPrompt();
        if (systemPrompt == null) systemPrompt = "";
        ProjectChatContext projectContext = ProjectChatContext.load(parentContext.getWorkingDirectory());
        String projectPrompt = projectContext.renderSystemPrompt();
        if (!projectPrompt.isBlank()) {
            systemPrompt = systemPrompt.isBlank()
                    ? projectPrompt : systemPrompt.strip() + "\n\n" + projectPrompt;
        }
        if (workflow != null) {
            systemPrompt = systemPrompt.isBlank()
                    ? workflow.participantBrief() : systemPrompt.strip() + "\n\n" + workflow.participantBrief();
        }
        DirectSubagentSupervision.Contract contract = parentContext.getSubagentSupervision();
        if (contract == null) {
            // Standalone callers still use local workflow configuration, captured once.
            var localWorkflow = new ai.kompile.cli.main.chat.workflow.WorkflowController(
                    parentContext.getWorkingDirectory(), projectContext.skillRegistry(),
                    toolRegistry);
            localWorkflow.beginTurn(prompt, subagentId);
            try {
                contract = standaloneContract(localWorkflow.captureChildContract(), prompt, parentContext,
                        ai.kompile.cli.main.chat.harness.HarnessConfig.load(objectMapper).isJudgeGlobalEnabled());
            } finally {
                localWorkflow.completeTurn();
            }
        }
        contract = contract.withCeiling(parentContext);
        if (ledger != null) {
            ledger.open();
            SubagentResumeMetadata metadata = SubagentResumeMetadata.create(
                    subagentId, options.name(), agent.getName(),
                    parentContext.getSessionId(), agent.getWorkflowParticipant(), model)
                    .withRoute(childConfig.getProvider(), childConfig.getAuthenticationMethod(),
                            model, childConfig.getCredentialName(), childConfig.getThinking());
            try {
                new SubagentResumeService(objectMapper).writeMetadata(metadata);
            } catch (java.io.IOException sidecarFailure) {
                // Without a sidecar the run is not discoverable/resumable; the ledger
                // alone is inert. Warn once and continue — the delegation still runs.
                System.err.println("Warning: Resumable subagent metadata unavailable ("
                        + sidecarFailure.getMessage() + "); this run will not be resumable.");
                try {
                    ledger.close();
                } catch (RuntimeException ignored) {
                }
                ledger = null;
            }
        }
        DirectSession session = new DirectSession(
                subagentId, agent, parentContext, subClient,
                systemPrompt, model, childConfig, subagentCancelled,
                new DirectSubagentSupervision(contract, toolRegistry, objectMapper), compactor, workflow, label,
                ledger);
        session.admission = admission;
        session.provider = childConfig.getProvider();
        wireTranscript(session, childConfig);
        sessions.put(subagentId, session);
        trimSessions();
        session.running.set(true);
        session.ownerThread = Thread.currentThread();
        try {
            // Startup callbacks are inside the cleanup boundary too: a failed
            // observer must not strand the active ledger's writer lock.
            if (lifecycleListener != null) {
                lifecycleListener.onSubagentStart(subagentId, label, StringUtils.truncate(prompt, 60));
                publishMetadata(session);
            }
            emitActivity(subagentId, "starting",
                    renderer.renderSubagentStart(label, StringUtils.truncate(prompt, 80)),
                    parentContext);
            String reminderContent = ReminderManager.reminderBlockContent(prompt);
            if (reminderContent != null) {
                emitActivity(subagentId, "starting",
                        renderer.renderReminderSection(reminderContent), parentContext);
            }
            notifyStatus(subagentId, "starting");
            return runConversation(session, prompt, startTime);
        } catch (Exception e) {
            notifyStatus(subagentId, "failed · " + e.getClass().getSimpleName());
            emitActivity(subagentId, "failed · " + e.getClass().getSimpleName(),
                    renderer.renderSubagentError(label, e.getMessage()), parentContext);
            throw e;
        } finally {
            finishRun(session);
        }
        }
    }

    /**
     * How rows name a child: a workflow participant the way its delegation line does,
     * {@code participant (role on provider/model)}, else the agent. Package-private for
     * {@link ServerSubagentRunner} and tests.
     */
    static String displayName(AgentConfig agent, String participant, WorkflowTeam.ModelBinding binding) {
        if (participant == null || participant.isBlank()) return agent.getName();
        String role = agent.getRoleName() != null && !agent.getRoleName().isBlank()
                ? agent.getRoleName() : agent.getName();
        return participant + " (" + role + (binding != null ? " on " + binding.label() : "") + ")";
    }

    /**
     * Rehydrate a persisted resumable run and continue it with a new prompt, as a
     * child of the resuming caller. The child keeps its original {@code subagent-}
     * id and appends to the same ledger; permissions, supervision and tool access
     * are re-derived from the resuming caller exactly as for a fresh delegation —
     * a resumed child is a subagent of whoever resumed it, never a resurrected
     * free-floating conversation.
     *
     * @throws IllegalArgumentException when the session id is not a persisted
     *         resumable subagent (unknown id, missing ledger, non-subagent id)
     */
    @Override
    public String resumeSubagent(AgentConfig agent, String sessionId, String prompt,
                                 ToolContext parentContext) throws Exception {
        if (sessionId == null || !sessionId.matches("subagent-[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "Only subagent- prefixed sessions are resumable: " + sessionId);
        }
        if (agent.getWorkflowParticipant() != null
                || ai.kompile.cli.main.chat.workflow.WorkflowSessionContext.enforcementFor(parentContext) != null) {
            throw new IllegalArgumentException("Durable subagent resume cannot enter a turn-scoped workflow team");
        }
        java.nio.file.Path conversationsDir = ai.kompile.cli.common.KompileHome.homeDirectory()
                .toPath().resolve("conversations");
        SubagentResumeService service = new SubagentResumeService(objectMapper);
        SubagentResumeMetadata metadata = service.metadataFor(sessionId);
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "No resumable subagent metadata for " + sessionId);
        }
        List<CompactionService.ConversationEntry> restored =
                SubagentLedger.read(SubagentLedger.fileFor(conversationsDir, sessionId), objectMapper);
        if (restored.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resumable subagent " + sessionId + " has no committed history to resume");
        }

        agent = agent.toBuilder()
                .enabledTools(agent.getEnabledTools() == null ? java.util.Set.of("*") : java.util.Set.copyOf(agent.getEnabledTools()))
                .permissionOverrides(agent.getPermissionOverrides() == null ? Map.of() : Map.copyOf(agent.getPermissionOverrides()))
                .allowedModels(agent.getAllowedModels() == null ? List.of() : List.copyOf(agent.getAllowedModels()))
                .canSpawnSubagents(false).build();
        long startTime = System.currentTimeMillis();
        String label = displayName(agent, null, null);

        // A new target owns its whole route, never a mix with the saved agent's auth/model.
        boolean targetRoute = agent.getProviderOverride() != null
                || agent.getModelOverride() != null || agent.getAuthenticationMethodOverride() != null;
        ChatConfig childConfig = SubagentChatConfig.configure(chatConfig,
                targetRoute ? agent.getProviderOverride() : metadata.provider(),
                targetRoute ? agent.getModelOverride() : metadata.model(),
                agent.getThinkingOverride() != null ? agent.getThinkingOverride()
                        : targetRoute ? null : metadata.thinking(),
                targetRoute ? agent.getAuthenticationMethodOverride() : metadata.authenticationMethod());
        if (!targetRoute && (metadata.credentialName() != null
                || "native".equalsIgnoreCase(metadata.authenticationMethod())
                || "none".equalsIgnoreCase(metadata.authenticationMethod()))) {
            // A saved account reference must not be shadowed by the resuming
            // caller's inline key, even when the provider did not change.
            childConfig.setApiKey(null);
            childConfig.setAuthenticationScope("session");
            childConfig.setCredentialName(metadata.credentialName());
        }
        if (!targetRoute && agent.getThinkingOverride() == null) {
            childConfig.setThinking(metadata.thinking());
        }
        String model = childConfig.getModel();
        if (!agent.isModelAllowed(model)) {
            throw new IllegalArgumentException("Model is not allowed for " + agent.getName() + ": " + model);
        }
        childConfig.setModel(model);
        if (agent.getThinkingOverride() != null && !agent.getThinkingOverride().isBlank()) {
            childConfig.setThinking(agent.getThinkingOverride());
        }
        try (SubagentAdmission.Lease admission = SubagentAdmission.acquire(childConfig.getProvider(), model)) {
        DirectLlmClient subClient = createClient(childConfig, parentContext);
        subClient.setPromptCacheSessionId(sessionId);
        AtomicBoolean subagentCancelled = new AtomicBoolean(false);
        subClient.setCancellationCheck(
                () -> subagentCancelled.get() || parentContext.isAborted());
        DirectSubagentCompactor compactor = new DirectSubagentCompactor(objectMapper);
        compactor.configure(childConfig, model);
        subClient.setContextWindowTokens(compactor.contextWindowTokens());
        subClient.setWireMaxOutputTokens(compactor.wireMaxOutputTokens());
        String systemPrompt = agent.getSystemPrompt();
        if (systemPrompt == null) systemPrompt = "";
        ProjectChatContext projectContext = ProjectChatContext.load(parentContext.getWorkingDirectory());
        String projectPrompt = projectContext.renderSystemPrompt();
        if (!projectPrompt.isBlank()) {
            systemPrompt = systemPrompt.isBlank()
                    ? projectPrompt : systemPrompt.strip() + "\n\n" + projectPrompt;
        }
        DirectSubagentSupervision.Contract contract = parentContext.getSubagentSupervision();
        if (contract == null) {
            var localWorkflow = new ai.kompile.cli.main.chat.workflow.WorkflowController(
                    parentContext.getWorkingDirectory(), projectContext.skillRegistry(),
                    toolRegistry);
            localWorkflow.beginTurn(prompt, sessionId);
            try {
                contract = standaloneContract(localWorkflow.captureChildContract(), prompt, parentContext,
                        ai.kompile.cli.main.chat.harness.HarnessConfig.load(objectMapper).isJudgeGlobalEnabled());
            } finally {
                localWorkflow.completeTurn();
            }
        }
        contract = contract.withCeiling(parentContext);

        // Rehydrate: replay the committed ledger into the wire history and mark it
        // all committed, then append to the same ledger file on later commits.
        SubagentLedger ledger = new SubagentLedger(
                SubagentLedger.fileFor(conversationsDir, sessionId), objectMapper);
        try {
            ledger.open();
            // Re-read only after acquiring ownership: another run may have
            // committed or pruned this child while configuration was resolved.
            restored = SubagentLedger.read(ledger.file(), objectMapper);
            if (restored.isEmpty()) throw new IllegalArgumentException("Subagent history is no longer available: " + sessionId);
            compactor.restore(subClient, model, restored);
        } catch (RuntimeException failure) {
            ledger.close();
            subClient.close();
            throw failure;
        }

        SubagentLedger finalLedger = ledger;
        DirectSession session = new DirectSession(
                sessionId, agent, parentContext, subClient,
                systemPrompt, model, childConfig, subagentCancelled,
                new DirectSubagentSupervision(contract, toolRegistry, objectMapper), compactor, null, label,
                finalLedger);
        session.ledgerFlushed = restored.size();

        // Adopting parent: the resuming caller owns this run's continuation.
        try {
            service.writeMetadata(metadata.withParent(parentContext.getSessionId()).withRoute(
                    childConfig.getProvider(), childConfig.getAuthenticationMethod(), model,
                    childConfig.getCredentialName(), childConfig.getThinking()));
        } catch (java.io.IOException ignored) {
            // Continuation proceeds; the parent update is metadata, not history.
        }

        session.admission = admission;
        session.provider = childConfig.getProvider();
        wireTranscript(session, childConfig);
        sessions.put(sessionId, session);
        trimSessions();
        session.running.set(true);
        session.ownerThread = Thread.currentThread();
        try {
            if (lifecycleListener != null) {
                lifecycleListener.onSubagentStart(sessionId, label,
                        StringUtils.truncate("resumed: " + prompt, 60));
                publishMetadata(session);
            }
            emitActivity(sessionId, "starting",
                    renderer.renderSubagentStart(label + " [resumed]",
                            StringUtils.truncate(prompt, 80)),
                    parentContext);
            notifyStatus(sessionId, "starting");
            return runConversation(session, prompt, startTime);
        } catch (Exception e) {
            notifyStatus(sessionId, "failed · " + e.getClass().getSimpleName());
            emitActivity(sessionId, "failed · " + e.getClass().getSimpleName(),
                    renderer.renderSubagentError(label, e.getMessage()), parentContext);
            throw e;
        } finally {
            finishRun(session);
        }
        }
    }

    /**
     * Delta-append the committed projection to the run's ledger. Called only at
     * commit points (accepted exchange, tool-call envelope commit), so the ledger
     * is always exactly the committed wire prefix. Resumable runs only; a
     * non-resumable session's ledger is null and this is a no-op.
     */
    private void flushLedger(DirectSession session) {
        SubagentLedger ledger = session.ledger;
        if (ledger == null || !ledger.usable()) return;
        List<CompactionService.ConversationEntry> committed =
                session.compactor.committedProjection();
        if (committed.size() > ledgerFlushedEntries(session)) {
            ledger.appendAll(committed.subList(ledgerFlushedEntries(session), committed.size()));
            if (ledger.usable()) markLedgerFlushed(session, committed.size());
        }
    }

    /** How many of this session's committed entries the ledger already holds. */
    private int ledgerFlushedEntries(DirectSession session) {
        return session.ledgerFlushed;
    }

    private void markLedgerFlushed(DirectSession session, int count) {
        session.ledgerFlushed = count;
    }

    /** Switch only the child's transport after current policy admits the route. */
    private boolean activateFailover(DirectSession session, ChatConfig candidate) {
        if (session.cancelled.get() || session.parentContext.isAborted()
                || Thread.currentThread().isInterrupted()) return false;
        if (!session.agent.isModelAllowed(candidate.getModel())) return false;
        try {
            SubagentAdmission.validateRoute(candidate.getProvider(), candidate.getModel());
        } catch (java.io.IOException | IllegalStateException refused) {
            return false;
        }
        // Keep config/client/supervision identities and the existing admission
        // lease: a fallback is not a new child (and must work at maxConcurrent=1).
        session.config.applyLlmSettingsFrom(candidate);
        session.modelOverride = candidate.getModel();
        session.provider = candidate.getProvider();
        session.compactor.configure(session.config, session.modelOverride);
        session.client.setContextWindowTokens(session.compactor.contextWindowTokens());
        session.client.setWireMaxOutputTokens(session.compactor.wireMaxOutputTokens());
        wireTranscript(session, session.config);
        publishMetadata(session);
        if (session.ledger != null) {
            try {
                var service = new SubagentResumeService(objectMapper);
                var metadata = service.metadataFor(session.id);
                if (metadata != null) service.writeMetadata(metadata.withRoute(
                        session.provider, session.config.getAuthenticationMethod(), session.modelOverride,
                        session.config.getCredentialName(), session.config.getThinking()));
            } catch (java.io.IOException persistenceFailure) {
                emitActivity(session.id, "failover",
                        renderer.renderSubagentStatus(session.label,
                                "fallback active, but its resume route could not be saved"), session.parentContext);
            }
        }
        return true;
    }

    private String runConversation(DirectSession session, String prompt, long startTime) throws Exception {
        session.loopDetector.reset();
        var previousCancellationCheck = session.client.getCancellationCheck();
        session.client.setCancellationCheck(() -> session.loopDetector.isTerminated()
                || session.cancelled.get() || session.parentContext.isAborted()
                || (previousCancellationCheck != null && previousCancellationCheck.getAsBoolean()));
        try {
            session.supervision.begin(prompt, session.id);
            try {
                return runSupervisedConversation(session, prompt, startTime);
            } finally {
                session.supervision.end();
            }
        } finally {
            session.client.setCancellationCheck(previousCancellationCheck);
        }
    }

    private String runSupervisedConversation(DirectSession session, String prompt, long startTime) throws Exception {
        StringBuilder fullResponse = new StringBuilder();
        LoopDetector loopDetector = session.loopDetector;
        SubagentConfig policy = SubagentConfig.load();
        ModelFailoverController failover = new ModelFailoverController(
                SessionModelLadder.forSubagent(session.id, session.parentContext.getWorkingDirectory(),
                        policy.failoverRungs(), policy.failoverEnabled()),
                () -> {
                    ChatConfig active = session.config.copy();
                    active.setModel(session.modelOverride);
                    return active;
                },
                candidate -> activateFailover(session, candidate),
                notice -> emitActivity(session.id, "failover",
                        renderer.renderSubagentStatus(session.label, notice), session.parentContext));

        String currentMessage = prompt;
        List<DirectLlmClient.ToolCallResultInput> pendingToolResults = null;

        // Direct subagents run until they finish or their shared abort signal
        // fires. Chat does not impose an arbitrary execution-limit cutoff.
        while (true) {
            if (session.cancelled.get() || session.parentContext.isAborted()) {
                notifyStatus(session.id, "aborted");
                emitActivity(session.id, "aborted",
                        renderer.renderSubagentError(session.label, "Aborted"),
                        session.parentContext);
                return fullResponse + "\n[Subagent aborted]";
            }

            notifyStatus(session.id, "thinking");

            // Rebuild on every iteration so an activate_tools call immediately exposes
            // its selected capability group to the subagent's next request.
            ArrayNode toolDefinitions = directToolDefinitionsFor(session.agent);
            for (int i = toolDefinitions.size() - 1; i >= 0; i--) {
                if (!session.supervision.permitsTool(toolDefinitions.get(i).path("name").asText()))
                    toolDefinitions.remove(i);
            }
            // Model-aware auto-compaction, mirroring main chat: when the next request
            // would cross the trigger, Tier 1 shrinks old tool/assistant bodies in
            // place first — it is legal mid-exchange and keeps call/result ids intact.
            String loopWarning = loopDetector.drainWarning();
            if (loopWarning != null) {
                currentMessage = currentMessage == null ? loopWarning
                        : currentMessage + "\n\n" + loopWarning;
                emitActivity(session.id, "loop warning", loopWarning, session.parentContext);
            }
            String outboundMessage = reminderManager == null
                    ? currentMessage : reminderManager.prependTo(currentMessage);
            if (session.compactor.preventiveNeeded(outboundMessage)) {
                int shrunkBodies = session.compactor.shrinkInPlace(session.client);
                if (session.ledger != null && session.ledger.usable()) {
                    session.ledger.rewrite(session.compactor.committedProjection());
                    session.ledgerFlushed = session.compactor.committedProjection().size();
                }
                emitActivity(session.id, "compacting",
                        renderer.renderSubagentStatus(session.label,
                                shrunkBodies > 0
                                        ? "context nearing the model limit; pruned " + shrunkBodies + " old bodies"
                                        : "context nearing the model limit"),
                        session.parentContext);
                if (session.compactor.preventiveNeeded(outboundMessage)) {
                    DirectSubagentCompactor.CommitResult committed =
                            session.compactor.compactAtBoundary(session.client,
                                    session.modelOverride,
                                    "Free context before the next subagent step");
                    if (committed.committed()) {
                        if (session.ledger != null && session.ledger.usable()) {
                            session.ledger.rewrite(session.compactor.committedProjection());
                            session.ledgerFlushed =
                                    session.compactor.committedProjection().size();
                        }
                        emitActivity(session.id, "compacted",
                                renderer.renderCompactionNotice(
                                        committed.tokensBefore(), committed.tokensAfter()),
                                session.parentContext);
                    }
                }
            }
            // The pending outbound participates in overflow handling, but a rejected
            // request must not keep it in the projection: failed requests commit no
            // client history, and the retry re-sends the message exactly once.
            int requestStart = session.compactor.markRequestStart();
            session.compactor.appendUser(outboundMessage);
            session.telemetry.beginRequest();
            DirectLlmClient.StreamResult result = session.client.streamChat(
                    outboundMessage, session.systemPrompt + "\n\n" + session.supervision.systemPrompt(), toolDefinitions,
                    pendingToolResults, session.modelOverride);
            session.compactor.recordReportedTokens(result.contextInputTokens());
            if (loopDetector.isTerminated() && !session.cancelled.get()
                    && !session.parentContext.isAborted()) {
                session.compactor.appendAssistant(result.text);
                throw stopLoop(session, loopDetector);
            }
            if (result.cancelled || session.cancelled.get()
                    || session.parentContext.isAborted()) {
                session.compactor.rollbackTo(requestStart);
                notifyStatus(session.id, "aborted");
                emitActivity(session.id, "aborted",
                        renderer.renderSubagentError(session.label, "Aborted"),
                        session.parentContext);
                return fullResponse + "\n[Subagent aborted]";
            }

            // Provider context-window rejection: the failed request committed no
            // history, so a Tier-2 rewrite plus a same-turn retry cannot duplicate
            // anything. Each recovery must strictly shrink the history (the compactor
            // refuses to commit otherwise), and a per-session cap bounds the worst case.
            if (result.canRetryAfterContextOverflow()) {
                session.compactor.rollbackTo(requestStart);
                if (session.overflowRecoveryCount.incrementAndGet()
                        > MAX_OVERFLOW_RECOVERIES) {
                    notifyStatus(session.id, "failed · context overflow");
                    emitActivity(session.id, "failed · context overflow",
                            renderer.renderSubagentError(session.label,
                                    "Context still exceeded after compaction"),
                            session.parentContext);
                    throw new IllegalStateException(
                            "Subagent context exceeded the model window after compaction");
                }
                DirectSubagentCompactor.CommitResult committed =
                        session.compactor.compactAtBoundary(session.client,
                                session.modelOverride,
                                "Recover from a provider context-window rejection");
                emitActivity(session.id, "compacting",
                        renderer.renderSubagentStatus(session.label,
                                committed.committed()
                                        ? "provider rejected the request as too long; compacted child context"
                                        : "provider rejected the request as too long; no reduction possible: "
                                                + committed.rejection()),
                        session.parentContext);
                if (!committed.committed()) {
                    throw new IllegalStateException(
                            "Subagent history cannot be reduced below the provider context limit: "
                                    + committed.rejection());
                }
                if (session.ledger != null && session.ledger.usable()) {
                    session.ledger.rewrite(session.compactor.committedProjection());
                    session.ledgerFlushed = session.compactor.committedProjection().size();
                }
                continue;
            }

            if (result.failed) {
                session.compactor.rollbackTo(requestStart);
                String reason = result.failureMessage != null && !result.failureMessage.isBlank()
                        ? result.failureMessage : result.text;
                if (failover.tryFailover(result.failureKind, reason, result.isReplaySafe())) {
                    // The rejected tail was never committed. Completed local tools,
                    // however, must cross the provider boundary as results, not be
                    // executed again or replaced by replay's dangling-call repairs.
                    var history = new ArrayList<>(session.compactor.committedProjection());
                    if (pendingToolResults != null) {
                        for (var tool : pendingToolResults) {
                            history.add(CompactionService.ConversationEntry.toolResult(
                                    tool.name, tool.callId, tool.output));
                        }
                    }
                    session.compactor.restore(session.client, session.modelOverride, history, true);
                    pendingToolResults = null;
                    flushLedger(session);
                    continue;
                }
                throw new IllegalStateException("Subagent provider request failed: "
                        + (reason == null || reason.isBlank() ? result.failureKind : reason));
            }

            // Accepted response: the adapter has committed the previous pending tool
            // results, the outbound user message, and this response to the history.
            session.compactor.appendAssistant(result.text);
            session.compactor.commitThroughPendingOutbound();
            flushLedger(session);
            if (result.text != null && !result.text.isEmpty()) {
                if (fullResponse.length() > 0) fullResponse.append('\n');
                fullResponse.append(result.text);
                // Streaming chunks are already retained through onSubagentOutput.
                emitActivity(session.id, "responding",
                        "", session.parentContext);
            }

            if (result.toolCalls.isEmpty()) {
                if (loopDetector.afterAssistantText(result.text).shouldStopTurn()) {
                    throw stopLoop(session, loopDetector);
                }
                String correction = session.supervision.beforeCompletion(fullResponse.toString());
                if (correction != null) {
                    fullResponse.setLength(0);
                    currentMessage = "[Child supervision correction]\n" + correction;
                    pendingToolResults = null;
                    continue;
                }
                String followUp = session.followUps.poll();
                if (followUp == null) break;
                session.supervision.end();
                session.supervision.begin(followUp, session.id);
                loopDetector.reset();
                fullResponse.setLength(0);
                currentMessage = followUp;
                pendingToolResults = null;
                continue;
            }

            // Mirror the assistant tool-call envelope this response just committed,
            // then advance the committed mark before execution appends results.
            for (DirectLlmClient.ToolCallOutput tc : result.toolCalls) {
                session.compactor.appendToolCall(tc.name, tc.id,
                        tc.arguments == null ? null : tc.arguments.toString());
            }
            session.compactor.commitThroughPendingOutbound();
            flushLedger(session);

            session.supervision.beginToolBatch();
            List<DirectLlmClient.ToolCallResultInput> toolResults = new ArrayList<>();
            for (DirectLlmClient.ToolCallOutput tc : result.toolCalls) {
                if (loopDetector.isTerminated()) {
                    String skipped = "Not executed: " + loopDetector.stopNotice();
                    session.compactor.appendToolResult(tc.name, tc.id, skipped);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(tc.id, tc.name, skipped, true));
                    continue;
                }
                if (session.cancelled.get() || session.parentContext.isAborted()) {
                    notifyStatus(session.id, "aborted");
                    break;
                }
                String rawInput = tc.arguments == null ? "" : tc.arguments.toString();
                session.telemetry.tool();
                publishMetadata(session);
                String callSummary = TerminalRenderer.summarizeToolCall(tc.name, rawInput, 88);
                emitActivity(session.id, callSummary + " …",
                        renderer.renderToolCallStart(tc.name, rawInput), session.parentContext);
                ToolContext subContext = new ToolContext(
                        session.id,
                        session.agent,
                        permissionService,
                        session.parentContext.getWorkingDirectory(),
                        toolRegistry
                );
                subContext.markSupervisedChild();
                subContext.bindWorkflow(session.workflow);
                // Sibling subagents share the parent's coordination session; their own id
                // scopes edit-lock ownership so they arbitrate against each other.
                subContext.setCoordinationAgent(session.id);
                subContext.bindCoordinator(session.parentContext.getCoordinator());
                subContext.linkAbortCheck(
                        () -> session.cancelled.get() || session.parentContext.isAborted());
                subContext.setOutputConsumer(session.parentContext.getOutputConsumer());

                var executedArguments = new java.util.concurrent.atomic.AtomicReference<>(tc.arguments);
                try {
                    session.activeToolContext = subContext;
                    ToolResult toolResult = session.supervision.execute(
                            tc.name, tc.arguments, fullResponse.toString(), subContext,
                            (tool, arguments, context) -> {
                                executedArguments.set(arguments.deepCopy());
                                return executeChildToolInterruptibly(session, tool, arguments, context);
                            });
                    loopDetector.afterToolResult(tc.name, executedArguments.get(),
                            toolResult.getOutput(), toolResult.isError());
                    String outcome = TerminalRenderer.summarizeToolResult(toolResult, 72);
                    emitActivity(session.id,
                            callSummary + (toolResult.isError() ? " ✗ " : " ✓ ") + outcome,
                            renderer.renderSubagentToolCall(tc.name, rawInput, toolResult),
                            session.parentContext);

                    // Match main chat's UTF-8/line budget and preserve the full
                    // result on disk instead of silently discarding the tail.
                    String output = childExecutionOutput(tc.arguments, executedArguments.get(),
                            outputTruncator.truncate(toolResult.getOutput(), tc.name).getOutput());

                    session.compactor.appendToolResult(tc.name, tc.id, output);
                    // Not flushed here: a tool result rides the next request and only
                    // becomes committed wire history at the next commit point, where
                    // the ledger delta-append picks it up.

                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, output, toolResult.isError()));
                } catch (DirectSubagentSupervision.SupervisionFailure failure) {
                    session.compactor.appendToolResult(tc.name, tc.id,
                            "Error: " + failure.getMessage());
                    throw failure;
                } catch (ToolExecutionException e) {
                    loopDetector.afterToolResult(tc.name, executedArguments.get(), "Error: " + e.getMessage(), true);
                    String output = childExecutionOutput(tc.arguments, executedArguments.get(), "Error: " + e.getMessage());
                    session.compactor.appendToolResult(tc.name, tc.id, output);
                    ToolResult failed = ToolResult.error(e.getMessage());
                    emitActivity(session.id, callSummary + " ✗ "
                                    + TerminalRenderer.truncatePreview(e.getMessage(), 72),
                            renderer.renderSubagentToolCall(tc.name, rawInput, failed),
                            session.parentContext);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, output, true));
                } catch (Throwable unexpectedToolFailure) {
                    // Same contract as the main loop: an uncaught LinkageError must not
                    // unwind the subagent turn silently — surface it as a tool error.
                    String failure = AgenticChatLoop.describeThrowable(unexpectedToolFailure);
                    loopDetector.afterToolResult(tc.name, executedArguments.get(), "Error: " + failure, true);
                    String output = childExecutionOutput(tc.arguments, executedArguments.get(), "Error: " + failure);
                    session.compactor.appendToolResult(tc.name, tc.id, output);
                    ToolResult failed = ToolResult.error(failure);
                    emitActivity(session.id, callSummary + " ✗ "
                                    + TerminalRenderer.truncatePreview(failure, 72),
                            renderer.renderSubagentToolCall(tc.name, rawInput, failed),
                            session.parentContext);
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, output, true));
                } finally {
                    session.activeToolContext = null;
                }
            }

            if (loopDetector.isTerminated()) throw stopLoop(session, loopDetector);
            pendingToolResults = toolResults;
            currentMessage = null;
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String finalResult = fullResponse.toString().trim();

        if (session.cancelled.get() || session.parentContext.isAborted()
                || !session.terminalClaimed.compareAndSet(false, true)) {
            notifyStatus(session.id, "aborted");
            return finalResult + "\n[Subagent aborted]";
        }

        notifyStatus(session.id, "completed");
        emitActivity(session.id, "completed",
                renderer.renderSubagentComplete(session.label, durationMs),
                session.parentContext);

        return finalResult.isEmpty() ? "(subagent returned empty response)" : finalResult;
    }

    private static String childExecutionOutput(JsonNode requested, JsonNode executed, String output) {
        if (java.util.Objects.equals(requested, executed)
                || (requested == null && executed != null && executed.isObject() && executed.isEmpty())) return output;
        return "Arguments rewritten by child supervision before execution: " + executed + "\n" + output;
    }

    /** Match main chat's cancellation boundary even for a tool which swallows interrupts. */
    private ToolResult executeChildToolInterruptibly(DirectSession session, CliTool tool,
            JsonNode arguments, ToolContext context) throws ToolExecutionException {
        // An admitted product mutation retains ownership until it really settles.
        if (toolRegistry.isProductMode()) {
            return tool.execute(arguments, context);
        }
        var execution = new java.util.concurrent.FutureTask<ToolResult>(sessionContext.wrapCallable(
                () -> tool.execute(arguments, context)));
        Thread worker = new Thread(execution, "subagent-tool-" + session.id);
        worker.setDaemon(true);
        worker.start();
        try {
            while (true) {
                if (session.cancelled.get() || session.parentContext.isAborted()) {
                    throw new InterruptedException("Subagent aborted");
                }
                try {
                    return execution.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException waiting) {
                    // Long productive tools have no arbitrary deadline; cancellation
                    // must still release the turn owner without joining the delegate.
                }
            }
        } catch (InterruptedException interrupted) {
            context.abort();
            context.setOutputConsumer(ignored -> { });
            execution.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Subagent aborted", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ToolExecutionException toolFailure) throw toolFailure;
            if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (cause instanceof Error error) throw error;
            throw new ToolExecutionException(AgenticChatLoop.describeThrowable(cause), cause);
        }
    }

    private IllegalStateException stopLoop(DirectSession session, LoopDetector detector) {
        session.compactor.appendAssistant("[" + detector.stopNotice() + "]");
        // No further request will submit the last results. Close the envelope
        // locally so retained/resumable children remain protocol-valid.
        session.compactor.commitThroughPendingOutbound();
        session.compactor.restore(session.client, session.modelOverride,
                session.compactor.committedProjection());
        flushLedger(session);
        return new IllegalStateException(detector.stopNotice());
    }

    @Override
    public boolean sendMessage(String subagentId, String message) {
        DirectSession session = sessions.get(subagentId);
        if (session == null || !canSendMessage(subagentId)
                || message == null || message.isBlank() || session.followUps.size() >= 64) return false;
        session.lastTouched = System.currentTimeMillis();
        session.followUps.add(message.strip());
        emitActivity(session.id, "follow-up queued", "\n  You › " + message.strip(),
                session.parentContext);
        startQueuedRun(session);
        return true;
    }

    @Override
    public boolean canSendMessage(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        return session != null && !session.cancelled.get() && !session.parentContext.isAborted();
    }

    @Override
    public boolean hasPendingWork(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        return session != null && (session.running.get() || !session.followUps.isEmpty());
    }

    @Override
    public boolean canCancel(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        return session != null && session.running.get()
                && !session.terminalClaimed.get() && !session.cancelled.get();
    }

    @Override
    public boolean cancel(String subagentId) {
        DirectSession session = sessions.get(subagentId);
        if (session == null || !session.running.get()
                || !session.terminalClaimed.compareAndSet(false, true)) return false;
        session.cancelled.set(true);
        session.followUps.clear();
        ToolContext activeTool = session.activeToolContext;
        if (activeTool != null) activeTool.abort();
        notifyStatus(session.id, "cancelling");
        emitActivity(session.id, "cancelling",
                renderer.renderSubagentError(session.label, "Cancelled by user"),
                session.parentContext);
        Thread owner = session.ownerThread;
        if (owner != null && owner != Thread.currentThread()) owner.interrupt();
        return true;
    }

    private void finishRun(DirectSession session) {
        session.lastTouched = System.currentTimeMillis();
        session.ownerThread = null;
        if (session.ledger != null) session.ledger.close();
        if (session.cancelled.get()) Thread.interrupted();
        // The run is over (completed, failed or cancelled): free every edit lock it took
        // under its coordination identity now, before anyone is told it ended. A later
        // follow-up run re-registers what it needs.
        EditCoordinatorTool.releaseAgentLocks(toolRegistry, session.id);
        try {
            finishStream(session);
            if (lifecycleListener != null) lifecycleListener.onSubagentEnd(session.id);
        } catch (RuntimeException observerFailure) {
            System.err.println("Warning: Subagent completion observer failed: " + observerFailure.getMessage());
        }
        if (session.admission != null) session.admission.close();
        session.running.set(false);
        if (session.cancelled.get()) {
            session.followUps.clear();
            sessions.remove(session.id, session);
        } else {
            startQueuedRun(session);
        }
    }

    private void startQueuedRun(DirectSession session) {
        if (session.cancelled.get() || session.followUps.isEmpty()
                || !session.running.compareAndSet(false, true)) return;
        session.terminalClaimed.set(false);
        String first = session.followUps.poll();
        Thread worker = new Thread(sessionContext.wrap(() -> {
            session.ownerThread = Thread.currentThread();
            try {
                String result;
                try (SubagentAdmission.Lease admission = SubagentAdmission.acquire(session.provider, session.modelOverride)) {
                    session.admission = admission;
                    if (lifecycleListener != null) {
                        lifecycleListener.onSubagentStart(session.id, session.label, "Interactive follow-up");
                        publishMetadata(session);
                    }
                    if (session.ledger != null && session.ledger.usable()) {
                        session.ledger.open();
                        var restored = SubagentLedger.read(session.ledger.file(), objectMapper);
                        if (restored.isEmpty()) throw new IllegalStateException("Subagent history is no longer available: " + session.id);
                        session.compactor.restore(session.client, session.modelOverride, restored);
                        session.ledgerFlushed = restored.size();
                    }
                    result = runConversation(session, first, System.currentTimeMillis());
                } catch (Exception e) {
                    result = "Subagent follow-up failed: " + AgenticChatLoop.describeThrowable(e);
                    notifyStatus(session.id, "failed · " + e.getClass().getSimpleName());
                    emitActivity(session.id, "failed · " + e.getClass().getSimpleName(),
                            renderer.renderSubagentError(session.label, e.getMessage()),
                            session.parentContext);
                }
                // Publish before releasing ownership: a live host must not observe
                // an idle child and terminate before its completion is delivered.
                var listener = asyncCompletionListener;
                if (listener != null) listener.accept(session.id, session.cancelled.get()
                        ? "Subagent follow-up cancelled." : result);
            } finally {
                finishRun(session);
            }
        }), "subagent-followup-" + session.id);
        worker.setDaemon(true);
        worker.start();
    }

    /** Release retained provider clients when a one-shot harness no longer owns any children. */
    @Override
    public void close() {
        for (DirectSession session : sessions.values()) {
            cancel(session.id);
            session.client.close();
        }
        sessions.clear();
    }

    private void trimSessions() {
        if (sessions.size() <= MAX_RETAINED_SESSIONS) return;
        sessions.values().stream()
                .filter(session -> !session.running.get())
                .sorted(Comparator.comparingLong(session -> session.lastTouched))
                .limit(Math.max(0, sessions.size() - MAX_RETAINED_SESSIONS))
                .map(session -> session.id)
                .toList()
                .forEach(sessions::remove);
    }

    /** Capture local policy intent without provisioning a standalone policy reviewer. */
    static DirectSubagentSupervision.Contract standaloneContract(
            ai.kompile.cli.main.chat.workflow.WorkflowController.ChildContract workflow,
            String prompt, ToolContext parent, boolean enabled) {
        var config = ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(parent.getWorkingDirectory());
        boolean required = enabled && (config == null
                ? ai.kompile.cli.main.chat.enforcer.EnforcerConfig.exists(parent.getWorkingDirectory())
                : config.isEnforcementEnabled());
        // No backend is launched for standalone callers. Requested policy without a captured
        // reviewer is explicitly unavailable, not silently disabled.
        return new DirectSubagentSupervision.Contract(workflow, prompt, null, null, null,
                required, config == null ? 3 : config.getMaxCorrections()).withCeiling(parent);
    }

    /** Provider-neutral schema consumed by every DirectLlmClient adapter. */
    ArrayNode directToolDefinitionsFor(AgentConfig agent) {
        // Provider adapters translate this top-level name/inputSchema shape to Responses,
        // Chat Completions, Anthropic, or Pi. The nested MCP/function shape is not accepted here.
        if (usesProgressiveToolLoading()) {
            toolRegistry.prepareProgressiveTools(agent);
            return toolRegistry.buildProgressiveDirectToolDefinitions(agent);
        }
        return toolRegistry.buildDirectToolDefinitions(agent);
    }

    private boolean usesProgressiveToolLoading() {
        String configured = System.getProperty("kompile.chat.progressiveTools");
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }
        return chatConfig != null && chatConfig.isKompileLocalServing();
    }

    private void notifyStatus(String subagentId, String status) {
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentStatus(subagentId, status);
        }
    }

    private void emitActivity(String subagentId, String summary, String detail,
                              ToolContext parentContext) {
        DirectSession session = sessions.get(subagentId);
        if (session != null) finishStream(session);
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentActivity(subagentId, summary, detail);
        } else {
            parentContext.emitOutput(detail);
        }
    }

    private void publishMetadata(DirectSession session) {
        LifecycleListener listener = lifecycleListener;
        if (listener != null) listener.onSubagentMetadata(session.id, session.telemetry.snapshot());
    }

    private void wireTranscript(DirectSession session, ChatConfig config) {
        session.telemetry.put("model", config.getModel());
        session.telemetry.put("vendor", config.getProvider());
        session.telemetry.put("authentication", config.getAuthenticationMethod());
        session.telemetry.put("thinking", config.getThinking());
        session.client.setProviderActivityListener(new DirectLlmClient.ProviderActivityListener() {
            private final Map<String, String> inputs = new ConcurrentHashMap<>();
            @Override
            public void onToolStart(String callId, String name, String input) {
                if (callId != null && input != null) inputs.put(callId, input);
                session.telemetry.tool();
                publishMetadata(session);
            }
            @Override
            public void onToolInput(String callId, String name, String input) {
                if (callId != null && input != null) inputs.put(callId, input);
            }
            @Override
            public void onToolComplete(String callId, String name, String output, int exitCode, boolean error) {
                String input = callId == null ? null : inputs.remove(callId);
                LoopDetector.Verdict verdict = session.loopDetector.afterProviderToolResult(name, input, output, error);
                if (verdict.action() == LoopDetector.Action.WARN) {
                    emitActivity(session.id, "loop warning", verdict.notice(), session.parentContext);
                }
            }
            @Override
            public void onToolInputDelta(String delta) {
                session.telemetry.stream(delta);
                publishMetadata(session);
            }
            @Override
            public void onTokenUsage(long input, long output, long read, long creation) {
                session.telemetry.usage(input, output, read, creation);
                publishMetadata(session);
            }
            @Override
            public void onContextUsage(long inputTokens) {
                session.telemetry.put("context_tokens", Long.toString(inputTokens));
                publishMetadata(session);
            }
        });
        session.client.setOutputConsumer(sessionContext.wrapConsumer(
                chunk -> emitStream(session, "assistant", chunk)));
        session.client.setThinkingConsumer(sessionContext.wrapConsumer(
                chunk -> emitStream(session, "thinking", chunk)));
    }

    /** Keep deltas intact and label reasoning in the child's transcript, not its result. */
    private void emitStream(DirectSession session, String kind, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        synchronized (session) {
            session.telemetry.stream(chunk);
            publishMetadata(session);
            if (!kind.equals(session.streamKind)) {
                if (!session.streamKind.isEmpty()) emitOutput(session.id, "\n");
                if (kind.equals("thinking")) emitOutput(session.id, renderer.dim("Thinking:\n"));
                else if (session.streamKind.equals("thinking")) emitOutput(session.id, "Assistant:\n");
                session.streamKind = kind;
            }
            emitOutput(session.id, kind.equals("thinking") ? renderer.dim(chunk) : chunk);
        }
    }

    private void finishStream(DirectSession session) {
        synchronized (session) {
            if (session.streamKind.isEmpty()) return;
            emitOutput(session.id, "\n");
            session.streamKind = "";
        }
    }

    private void emitOutput(String subagentId, String chunk) {
        LifecycleListener listener = lifecycleListener;
        if (listener != null) listener.onSubagentOutput(subagentId, chunk);
    }

}
