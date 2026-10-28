package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.DirectSubagentSupervision;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ModelRouting;
import ai.kompile.cli.main.chat.config.SubagentChatConfig;
import ai.kompile.cli.main.chat.agent.ProjectChatContext;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerConversationContext;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.coordination.CoordinationStateManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** MCP adapter for Kompile's chat harness, including its provider-owned subscription transports. */
class KompileNativeTaskRunner {
    private final Path workDir;
    private final RoleManager roles;
    private final BackgroundProcessManager processes;
    private final CoordinationStateManager coordinator;
    private final TerminalRenderer renderer;

    KompileNativeTaskRunner(Path workDir, RoleManager roles, BackgroundProcessManager processes,
                            CoordinationStateManager coordinator, TerminalRenderer renderer) {
        this.workDir = workDir;
        this.roles = roles;
        this.processes = processes;
        this.coordinator = coordinator;
        this.renderer = renderer;
    }

    static boolean supports(String agent) {
        return "kompile".equalsIgnoreCase(agent) || "general".equalsIgnoreCase(agent);
    }

    /** Keep credentials/provider project-scoped and all overrides request-local. */
    static ChatConfig configuration(ChatConfig configured, String model, String thinking) {
        return configuration(configured, null, model, thinking);
    }

    /**
     * Apply the request's model/thinking overrides and, when given, its provider
     * override, to a copy of the project's stored configuration.
     *
     * <p>Provider routing has two levers: an explicit {@code provider} argument,
     * and auto-routing from the model id when no provider is given —
     * {@link ModelRouting} decides whether the configured route hosts the model's
     * family, routes to the family's first-party owner (e.g. {@code claude-*}
     * dispatched on the Codex route goes to anthropic), or refuses with
     * {@link ModelRouting.AmbiguousModelRouteException} when several configured
     * routes could host it and none is the owner. Refusals surface as dispatch
     * errors naming the candidates; dispatch never guesses between equivalent
     * credentials.</p>
     *
     * <p>A provider switch never carries the old route's credential selection,
     * base URL, or authentication method across (same rule as the REPL's
     * {@code buildModelProviderCandidateFrom} and workflow
     * {@code WorkflowLaunch.configFor}): the new route resolves its own stored
     * credential at request time.</p>
     */
    static ChatConfig configuration(ChatConfig configured, String provider, String model, String thinking) {
        return SubagentChatConfig.configure(configured, provider, model, thinking, null);
    }

    ChatConfig loadConfiguration() {
        return ChatConfig.loadOrFromEnv(workDir);
    }

    ToolRegistry createRegistry(ChatConfig config, AgentRegistry agents, PermissionService permissions) {
        return ToolRegistryFactory.create(JsonUtils.standardMapper(), null, agents, permissions, renderer,
                processes, config, roles, null, workDir, coordinator);
    }

    HarnessConfig loadHarnessConfig() {
        return HarnessConfig.load(JsonUtils.standardMapper());
    }

    /** Native MCP calls have no parent chat loop from which to capture a reviewer. */
    AutoCloseable configureSupervision(ToolContext context, String prompt) throws Exception {
        if (context.getSubagentSupervision() != null) return () -> {};
        HarnessConfig harness = loadHarnessConfig();
        EnforcerConfig enforcer = EnforcerConfig.load(workDir);
        boolean required = harness.isJudgeGlobalEnabled() && (enforcer == null
                ? EnforcerConfig.exists(workDir) : enforcer.isEnforcementEnabled());
        if (!required) return () -> {};
        if (enforcer == null) throw new IllegalStateException("Configured enforcement policy could not be loaded");
        EnforcerPolicy policy = new EnforcerPolicy(enforcer.buildRulesText(workDir),
                enforcer.getMaxCorrections(), false);
        if (!policy.hasRules()) throw new IllegalStateException("Configured enforcement policy has no readable rules");
        if (enforcer.getJudgeMode() != null) harness.setJudgeMode(enforcer.getJudgeMode());
        if (enforcer.getJudgeProvider() != null) harness.setJudgeProvider(enforcer.getJudgeProvider());
        if (enforcer.getJudgeModel() != null) harness.setJudgeModel(enforcer.getJudgeModel());
        if (enforcer.getJudgeApiKey() != null) harness.setJudgeApiKey(enforcer.getJudgeApiKey());
        if (enforcer.getJudgeBaseUrl() != null) harness.setJudgeBaseUrl(enforcer.getJudgeBaseUrl());
        EnforcerEvaluator evaluator = enforcer.isKeywordMode()
                ? KeywordEnforcerEvaluator.fromPolicy(policy, JsonUtils.standardMapper(), enforcer)
                : new EnforcerJudge(harness, JsonUtils.standardMapper(), workDir);
        AutoCloseable owned = evaluator instanceof AutoCloseable closeable ? closeable : () -> {};
        try {
            WorkflowController workflow = new WorkflowController(workDir,
                    ProjectChatContext.load(workDir).skillRegistry(), context.getToolRegistry());
            workflow.beginTurn(prompt, context.getSessionId());
            try {
                context.setSubagentSupervision(new DirectSubagentSupervision.Contract(
                        workflow.captureChildContract(), prompt, evaluator, policy,
                        (childPrompt, assistant, name, input) -> evaluator instanceof EnforcerJudge judge
                                ? judge.evaluateToolCall(name, input, policy, EnforcerConversationContext.of(List.of(
                                        new EnforcerConversationContext.Message("user", childPrompt),
                                        new EnforcerConversationContext.Message("assistant", assistant))))
                                : evaluator.evaluateToolCall(name, input, policy),
                        true, enforcer.getMaxCorrections()).withCeiling(context));
            } finally {
                workflow.completeTurn();
            }
            return owned;
        } catch (Exception failure) {
            owned.close();
            throw failure;
        }
    }

    String run(AgentConfig request, String prompt, AtomicBoolean cancelled) throws Exception {
        return run(request, prompt, cancelled, null, null);
    }

    String resume(AgentConfig request, String sessionId, String prompt, AtomicBoolean cancelled, ToolContext parentContext) throws Exception {
        return run(request, prompt, cancelled, sessionId, parentContext);
    }

    private String run(AgentConfig request, String prompt, AtomicBoolean cancelled, String resumeSessionId, ToolContext parentContext) throws Exception {
        if (cancelled.get()) throw new InterruptedException("Native Kompile task cancelled");
        ChatConfig config = SubagentChatConfig.configure(loadConfiguration(), request.getProviderOverride(),
                request.getModelOverride(), request.getThinkingOverride(), request.getAuthenticationMethodOverride());
        if ("passthrough".equalsIgnoreCase(config.getChatMode()) || !config.isValid()) {
            throw new IllegalStateException("Incomplete Kompile chat configuration for provider '"
                    + config.getProvider() + "'. Run kompile chat --setup; no vendor CLI fallback was attempted.");
        }
        AgentRegistry agents = new AgentRegistry();
        RoleConfig role = roles == null || request.getRoleName() == null
                ? null : roles.getRole(request.getRoleName());
        AgentConfig child = (role == null ? agents.get("general") : role.toAgentConfig()).toBuilder()
                .isSubagent(true).canSpawnSubagents(false).roleName(request.getRoleName())
                .modelOverride(config.getModel()).thinkingOverride(config.getThinking())
                .providerOverride(config.getProvider()).authenticationMethodOverride(config.getAuthenticationMethod())
                .workflowParticipant(request.getWorkflowParticipant()).build();
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true); // Same full-access contract as managed MCP delegation.
        ToolRegistry tools = createRegistry(config, agents, permissions);
        SubagentRunner runner = tools.getSubagentRunner();
        String sessionId = "kompile-task-" + UUID.randomUUID();
        ToolContext context = new ToolContext(parentContext == null ? sessionId : parentContext.getSessionId(), child, permissions, workDir, tools);
        context.linkAbortSignal(cancelled);
        if (parentContext != null) {
            context.linkAbortCheck(parentContext::isAborted);
            context.setSubagentSupervision(parentContext.getSubagentSupervision());
        }
        context.bindWorkflow(StdioTaskTool.workflowEnforcement(workDir, roles));
        String dispatch = "Dispatch: mode=kompile-native, provider=" + config.getProvider()
                + ", model=" + config.getModel() + ", thinking=" + config.getThinking()
                + ", auth=" + (config.getAuthenticationMethod() == null ? "provider-default" : config.getAuthenticationMethod())
                + ", transport=" + (config.isClaudeCliNative() ? "claude-code" : "provider")
                + (request.getProviderOverride() != null && !request.getProviderOverride().isBlank()
                        ? ", providerOverride=" + request.getProviderOverride() : "");
        Path resultsDir = workDir.resolve(".kompile/task-results");
        Files.createDirectories(resultsDir);
        Path resultFile = resultsDir.resolve(sessionId + ".md");
        BackgroundProcessManager.ProcessEntry log = processes == null ? null : processes.registerVirtual(
                BackgroundProcessManager.ProcessKind.COMMAND, "kompile", "Native task: " + request.getDescription(),
                Map.of("source", "mcp-subagent", "kind", "subagent", "task_type", "agent", "sessionId", sessionId),
                () -> { cancelled.set(true); context.abort(); });
        if (log != null) {
            processes.appendVirtualOutput(log.getId(), dispatch + "\nUser:\n" + prompt + "\n");
            if (coordinator != null) coordinator.publishProcess(log.getId(), log.getCommand(), log.getDescription(),
                    -1L, "RUNNING", log.getOutputFile().toString(), "kompile", null, "subagent",
                    log.getStartTime(), null, null);
        }
        // ToolContext otherwise falls back to System.out, which is the MCP JSON-RPC wire.
        context.setOutputConsumer(text -> {
            if (log != null) processes.appendVirtualOutput(log.getId(), text);
            else System.err.println(text);
        });
        runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
            public void onSubagentStart(String id, String type, String description) {}
            private boolean ended;
            private boolean publicationQueued;
            public synchronized void onSubagentEnd(String id) { ended = true; }
            public synchronized void onSubagentMetadata(String id, Map<String, String> metadata) {
                if (log == null) return;
                Map<String, String> snapshot = new java.util.LinkedHashMap<>(log.getMetadata());
                snapshot.putAll(metadata);
                processes.updateVirtual(log.getId(), null, snapshot, false);
                if (coordinator != null && !publicationQueued && !ended) {
                    publicationQueued = true;
                    java.util.concurrent.CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> publishPending());
                }
            }
            private synchronized void publishPending() {
                publicationQueued = false;
                // The terminal path flushes the final snapshot. Never resurrect a finished child.
                if (!ended && log.isRunning()) coordinator.updateProcessState(
                        log.getId(), "RUNNING", null, null, log.getMetadata());
            }
            public void onSubagentActivity(String id, String summary, String detail) {
                if (log != null && detail != null && !detail.isBlank()) {
                    processes.appendVirtualOutput(log.getId(), detail);
                }
            }
            public void onSubagentOutput(String id, String chunk) {
                if (log != null && chunk != null) processes.appendVirtualOutputChunk(log.getId(), chunk);
            }
        });
        boolean completed = false;
        try (AutoCloseable owned = runner instanceof AutoCloseable closeable ? closeable : () -> {};
             AutoCloseable supervision = configureSupervision(context, prompt)) {
            String result = resumeSessionId != null
                    ? runner.resumeSubagent(child, resumeSessionId, prompt, context)
                    : runner.runSubagent(child, prompt, context,
                            child.getWorkflowParticipant() == null
                                    ? ai.kompile.cli.main.chat.agent.SubagentRunOptions.resumable(null)
                                    : ai.kompile.cli.main.chat.agent.SubagentRunOptions.DEFAULT);
            if (cancelled.get()) throw new InterruptedException("Native Kompile task cancelled");
            Files.writeString(resultFile, dispatch + "\n\n" + result, StandardCharsets.UTF_8);
            completed = true;
            return dispatch + "\n\n" + result + "\n\nFull output: `" + resultFile.toAbsolutePath() + "`";
        } catch (Exception failure) {
            completed = false;
            if (log != null) processes.appendVirtualOutput(log.getId(), "Native task failed: " + failure.getMessage());
            if (failure instanceof InterruptedException || cancelled.get()) throw failure;
            throw new IllegalStateException(dispatch + "\nNative task failed: " + failure.getMessage()
                    + "\nUse resolve_model with this provider/authentication_method, then retry Kompile task with "
                    + "agent=kompile, provider, model, and authentication_method. For anthropic, oauth/native uses "
                    + "Claude Code login; api-key uses the separate API. Do not substitute the vendor Agent tool. "
                    + "No account fallback was attempted.", failure);
        } finally {
            // Finish process state publication even when cancellation interrupted this worker.
            boolean interrupted = Thread.interrupted();
            try {
                if (log != null) {
                    if (completed) processes.complete(log.getId());
                    else processes.fail(log.getId(), -1);
                    if (coordinator != null) coordinator.updateProcessState(log.getId(), log.getState().name(),
                            log.getEndTime(), log.getExitCode(), log.getMetadata());
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
