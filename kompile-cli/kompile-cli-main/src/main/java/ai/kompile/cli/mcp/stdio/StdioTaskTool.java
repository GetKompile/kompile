package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.workflow.WorkflowLaunch;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StdioTaskTool {

    static final String DEFAULT_AGENT = "codex";
    static final List<String> SUPPORTED_AGENTS = AgentLaunchDefaults.SUPPORTED_AGENTS;

    private final AgentRegistry agentRegistry;
    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;
    private final RoleManager roleManager;
    private final Path workDir;

    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper,
                         RoleManager roleManager) {
        this(agentRegistry, subagentRunner, objectMapper, roleManager, null);
    }

    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper,
                         RoleManager roleManager,
                         Path workDir) {
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
        this.roleManager = roleManager;
        this.workDir = workDir;
    }

    /**
     * Constructor with optional coordination state manager for multi-agent
     * edit tracking and conflict detection.
     */
    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper,
                         RoleManager roleManager,
                         Object coordinationStateManager) {
        this(agentRegistry, subagentRunner, objectMapper, roleManager);
        // coordinationStateManager stored for future use
    }

    public String id() { return "task"; }

    public String description() {
        return "Delegate one task; use multi_task for 2+ independent subtasks in one parallel batch. " +
            "Do not call task repeatedly and wait for each result when the work can run concurrently. " +
            "Use sequential task calls only when later work needs an earlier result, or shared files/resources require serialization.\n\n" +
            "The subagent runs through the same managed terminal launcher used by interactive passthrough " +
            "with its own context window, then returns a summary.\n\n" +
            "Available agents: codex (default), claude, opencode, gemini, qwen, pi. Roles customize the prompt and " +
            "model defaults without disabling tools, edits, execution, or delegation.\n" +
            "In a workflow team, pass purpose (or a team role) and no agent or model: the team picks the " +
            "participant, whose CLI agent, model, and thinking the task runs on.\n" +
            "Returns a concise summary. Full output is written to a file under .kompile/task-results/ " +
            "which can be read with the `read` tool if more detail is needed.\n" +
            "The subagent runs once and returns — it cannot send follow-up messages.";
    }

    /** Keep parallel-tool discovery visible in compact MCP tools/list responses. */
    public String compactHint() {
        return "Delegate one task. For 2+ independent subtasks use multi_task in one parallel batch, not serial task calls. Sequence only for dependencies or shared files/resources.";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var desc = props.putObject("description");
        desc.put("type", "string");
        desc.put("description", "A short (3-5 word) description of the task");
        var prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "Detailed task description for the subagent. Include all necessary context.");
        var agent = props.putObject("agent");
        agent.put("type", "string");
        agent.put("default", DEFAULT_AGENT);
        agent.put("description", "Which agent to spawn. Available: codex (default), claude, opencode, gemini, qwen, pi.");
        ArrayNode enumValues = agent.putArray("enum");
        SUPPORTED_AGENTS.forEach(enumValues::add);
        var model = props.putObject("model");
        model.put("type", "string");
        model.put("description", "Optional model override. Precedence: explicit value, selected role default, project default, user default, provider native default.");
        var thinking = props.putObject("thinking");
        thinking.put("type", "string");
        thinking.put("description", "Optional thinking/effort override. When omitted, exact-model/default thinking from the selected role is tried before project/user defaults. Native mapping: Codex reasoning effort, Claude effort, OpenCode variant.");
        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional role to assign to the subagent. When omitted, the agent's persisted role assignment is used. Roles may define prompt, model, and thinking defaults; all tools remain enabled.");
        var purpose = props.putObject("purpose");
        purpose.put("type", "string");
        purpose.put("description", "Workflow teams only: the routing purpose (e.g. implement, review). The team picks the participant, whose CLI agent, model, and thinking the task runs on.");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        return execute(arguments, null);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        if (context != null && context.isAborted()) return ToolResult.error("Task cancelled");
        String desc = (String) arguments.getOrDefault("description", "");
        String prompt = (String) arguments.getOrDefault("prompt", "");
        Object explicitAgent = arguments.get("agent");
        String requestedAgent = String.valueOf(explicitAgent != null ? explicitAgent : DEFAULT_AGENT)
                .toLowerCase(Locale.ROOT);
        String model = (String) arguments.get("model");
        String thinking = (String) arguments.get("thinking");
        String roleName = (String) arguments.get("role"); // optional

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        // ── Workflow team enforcement (harness-owned identity) ─────────────
        WorkflowTeamEnforcement workflow;
        try {
            workflow = workflowEnforcement(workDir, roleManager);
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }
        String participant = null;
        if (workflow != null) {
            WorkflowTeamEnforcement.ToolDecision toolDecision =
                    workflow.evaluateToolUse("task");
            if (!toolDecision.allowed()) {
                return ToolResult.error(toolDecision.reason());
            }
            String purpose = (String) arguments.get("purpose");
            WorkflowTeamEnforcement.DelegationDecision decision =
                    workflow.evaluateDelegation(purpose, roleName);
            if (decision instanceof WorkflowTeamEnforcement.DelegationDecision.Denied denied) {
                return ToolResult.error(denied.reason());
            }
            // The team owns the destination: the resolved participant's role, CLI
            // agent, model, and thinking are what the child runs.
            ParticipantLaunch launch;
            try {
                launch = participantLaunch(workflow.team(),
                        (WorkflowTeamEnforcement.DelegationDecision.Allowed) decision,
                        explicitAgent != null ? requestedAgent : null, model, thinking);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            participant = launch.participant();
            requestedAgent = launch.agent();
            roleName = launch.role();
            model = launch.model();
            thinking = launch.thinking();
        }

        if (!isSupportedAgent(requestedAgent)) {
            return ToolResult.error("Agent '" + requestedAgent
                + "' is not available. Available agents: " + String.join(", ", SUPPORTED_AGENTS) + ".");
        }
        if (roleName != null && !roleName.isBlank() && roleManager != null
                && roleManager.getRole(roleName) == null) {
            return ToolResult.error("Unknown role '" + roleName + "'; refusing to launch subagent.");
        }

        String displayName = requestedAgent.substring(0, 1).toUpperCase(Locale.ROOT) + requestedAgent.substring(1);
        AgentConfig agentConfig = AgentConfig.builder(requestedAgent)
            .displayName(displayName)
            .description("External " + displayName + " agent")
            .systemPrompt(prompt).isSubagent(true).canSpawnSubagents(true)
            .roleName(roleName)
            .modelOverride(model)
            .thinkingOverride(thinking)
            .workflowParticipant(participant)
            .build();

        System.err.println("\u001B[32m  ⟳ Spawning " + displayName + " subagent: " + desc + "\u001B[0m");

        try {
            // The shared runner is a factory, never the cancellation target of a request.
            String result = runWithCancellation(subagentRunner.forkForSubagent(), agentConfig, prompt, context);
            if (isAgentMissing(result)) {
                return ToolResult.error(displayName + " is not available on PATH.");
            }
            String effectiveRoleName = roleName;
            RoleConfig effectiveRole = roleName != null && !roleName.isBlank() && roleManager != null
                    ? roleManager.getRole(roleName) : null;
            if ((effectiveRoleName == null || effectiveRoleName.isBlank()) && roleManager != null) {
                effectiveRoleName = roleManager.getAgentRole(requestedAgent);
                effectiveRole = effectiveRoleName == null ? null : roleManager.getRole(effectiveRoleName);
            }
            AgentLaunchDefaults.Selection selection = AgentLaunchDefaults.resolve(
                    requestedAgent, workDir != null ? workDir : Path.of("."), model, thinking,
                    effectiveRole != null ? effectiveRole.getAgentDefaultsFor(requestedAgent) : null);
            String effectiveModel = selection.model();
            String effectiveThinking = selection.thinking();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agent", requestedAgent);
            metadata.put("description", desc);
            metadata.put("mode", "managed-terminal");
            metadata.put("role", effectiveRoleName == null ? "" : effectiveRoleName);
            metadata.put("model", effectiveModel == null ? "" : effectiveModel);
            metadata.put("thinking", effectiveThinking == null ? "" : effectiveThinking);
            metadata.put("policy", "FULL_ACCESS");
            metadata.put("fallbacksUsed", "0");
            if (workflow != null) {
                metadata.put("workflow", workflow.team().name());
                metadata.put("workflowParticipant", participant);
            }
            return ToolResult.success("task:" + requestedAgent, result, metadata);
        } catch (RateLimitException e) {
            System.err.println("\u001B[33m  \u26a0 " + displayName + " rate limited; provider fallback is disabled.\u001B[0m");
            return ToolResult.error(displayName + " is rate limited. No provider fallback was attempted.");
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }

    static String runWithCancellation(DirectSubagentRunnerStdio runner, AgentConfig agent,
                                      String prompt, ToolContext context) throws Exception {
        if (context == null) return runner.runSubagent(agent, prompt);
        try (CancellationWatch watch = new CancellationWatch(context, runner)) {
            if (context.isAborted()) throw new InterruptedException("Task cancelled");
            String result = runner.runSubagent(agent, prompt);
            if (context.isAborted()) throw new InterruptedException("Task cancelled");
            return result;
        }
    }

    /** One execution owns one fork and one watcher; no shared runner or global cancel state. */
    static final class CancellationWatch implements AutoCloseable {
        private final ToolContext context;
        private final DirectSubagentRunnerStdio runner;
        final Thread thread;
        private boolean closed;

        CancellationWatch(ToolContext context, DirectSubagentRunnerStdio runner) {
            this.context = context;
            this.runner = runner;
            thread = new Thread(() -> {
                try {
                    while (checkCancellation()) Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    // close() retires the watcher, not the request's worker thread.
                }
            }, "mcp-task-cancellation");
            thread.setDaemon(true);
            thread.start();
        }

        private synchronized boolean checkCancellation() {
            if (closed) return false;
            // Retry while aborted: the runner resets its local flag during startup,
            // and may publish its managed runner only after the first cancellation.
            if (context.isAborted()) runner.cancel();
            return true;
        }

        @Override
        public synchronized void close() {
            closed = true;
            thread.interrupt();
        }
    }

    static boolean isSupportedAgent(String agentName) {
        return agentName != null && SUPPORTED_AGENTS.contains(agentName.toLowerCase(Locale.ROOT));
    }

    // ── Workflow team support ───────────────────────────────────────────────

    /**
     * Resolves the active workflow enforcement for this process, or {@code null}
     * outside a workflow. Identity comes from the harness, never from tool
     * arguments: the in-process session context (the chat lead, whose JVM cannot
     * mutate its own environment, with the gates the user approved), else the
     * environment a delegated server process inherited.
     *
     * @throws IllegalStateException when a workflow is configured but cannot be
     *         resolved; delegation then fails closed rather than running unenforced
     */
    static WorkflowTeamEnforcement workflowEnforcement(Path workDir, RoleManager roleManager) {
        WorkflowSessionContext context = WorkflowSessionContext.current();
        if (context != null) {
            return context.enforcement();
        }
        return inheritedEnforcement(workDir != null ? workDir : Path.of("").toAbsolutePath(), roleManager,
                System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME),
                System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT),
                System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION));
    }

    /**
     * Enforcement for a server launched as {@code participant} of workflow
     * {@code workflowName} in chat session {@code sessionId} (a blank participant
     * is the lead). The session's recorded team is the one enforced: if the
     * stored definition is changed outside this chat after the session started,
     * delegation fails closed until a new chat session starts rather than
     * switching to rules the session did not start under. The gates the user
     * approved in that session are re-read on every call. {@code null} when no
     * workflow is named.
     *
     * @throws IllegalStateException when the named team, session, or participant
     *         cannot be resolved, or the team changed since the session started
     */
    static WorkflowTeamEnforcement inheritedEnforcement(Path workDir, RoleManager roleManager,
                                                        String workflowName, String participant,
                                                        String sessionId) {
        if (workflowName == null || workflowName.isBlank()) return null;
        String name = workflowName.trim();
        String session = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        WorkflowTeamSnapshot snapshot;
        try {
            snapshot = WorkflowSessionContext.restore(session, workDir);
            if (snapshot == null) {
                WorkflowTeam team = WorkflowTeamStore.get(workDir, name);
                if (team == null) {
                    throw new IllegalStateException("Workflow '" + name + "' is not defined in "
                            + WorkflowTeamStore.path(workDir) + "; refusing to delegate outside it.");
                }
                snapshot = storedSnapshot(workDir, roleManager, team);
            }
        } catch (IOException e) {
            // A configured workflow that cannot be read must fail closed, not open.
            throw new IllegalStateException("Workflow '" + name
                    + "' is configured but could not be read: " + e.getMessage(), e);
        }
        WorkflowTeam team = snapshot.team();
        if (!WorkflowTeam.key(team.name()).equals(WorkflowTeam.key(name))) {
            throw new IllegalStateException("Chat session " + session + " runs workflow '" + team.name()
                    + "', not '" + name + "'; refusing to delegate.");
        }
        String caller = participant == null || participant.isBlank() ? team.lead() : WorkflowTeam.key(participant);
        if (team.participant(caller) == null) {
            throw new IllegalStateException("Workflow '" + team.name() + "' has no participant '" + caller
                    + "'; refusing to delegate.");
        }
        WorkflowTeamEnforcement enforcement = WorkflowTeamEnforcement.forCaller(snapshot, caller, session);
        WorkflowSessionContext.satisfiedGates(session).forEach(enforcement::satisfyGate);
        return enforcement;
    }

    /** Enforcement for an already-resolved team, as this process's inherited participant. */
    static WorkflowTeamEnforcement workflowEnforcementWith(Path workDir, RoleManager roleManager,
                                                           WorkflowTeam team) {
        return WorkflowTeamEnforcement.forCaller(storedSnapshot(workDir, roleManager, team),
                WorkflowTeamEnforcement.resolveCallerParticipant(team));
    }

    /** The stored team's snapshot. Fails closed unless every referenced role exists. */
    private static WorkflowTeamSnapshot storedSnapshot(Path workDir, RoleManager roleManager, WorkflowTeam team) {
        RoleManager roles = roleManager != null ? roleManager : new RoleManager(workDir);
        Map<String, String> resolved = new LinkedHashMap<>();
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (roles.getRole(participant.role()) == null) {
                throw new IllegalStateException("Workflow '" + team.name() + "' participant '"
                        + participant.id() + "' references unknown role '" + participant.role()
                        + "'. Create the role or fix the workflow before delegating.");
            }
            resolved.put(participant.id(), participant.role());
        }
        return new WorkflowTeamSnapshot(team, resolved, null);
    }

    /** What a workflow delegation launches: the participant, its role, CLI agent, model, and thinking. */
    record ParticipantLaunch(String participant, String role, String agent, String model, String thinking) {}

    /**
     * The launch for the participant a workflow resolved. A participant bound to
     * a model runs as that binding's CLI agent on that model; an explicit agent,
     * model, or thinking that contradicts the binding is refused rather than
     * silently replaced, so the lead learns what the team assigned. An unbound
     * participant runs the requested selectors (agent defaulting to codex).
     *
     * @param agent the explicitly requested agent, or {@code null}
     * @throws IllegalArgumentException when the request contradicts the binding,
     *         or no supported CLI agent runs it
     */
    static ParticipantLaunch participantLaunch(WorkflowTeam team,
                                               WorkflowTeamEnforcement.DelegationDecision.Allowed allowed,
                                               String agent, String model, String thinking) {
        WorkflowTeam.Participant participant = team.participant(allowed.resolvedParticipant());
        String role = allowed.resolvedRole() != null ? allowed.resolvedRole() : participant.role();
        String requestedAgent = blankToNull(agent);
        String requestedModel = blankToNull(model);
        String requestedThinking = blankToNull(thinking);
        WorkflowTeam.ModelBinding binding = participant.model();
        if (binding == null) {
            return new ParticipantLaunch(participant.id(), role,
                    requestedAgent != null ? requestedAgent.toLowerCase(Locale.ROOT) : DEFAULT_AGENT,
                    requestedModel, requestedThinking);
        }
        String subject = "Workflow '" + team.name() + "' runs participant '" + participant.id() + "'";
        String hint = " The user can change its model with /workflow model " + participant.id() + ".";
        String boundAgent = WorkflowLaunch.agentFor(binding);
        if (boundAgent == null) {
            throw new IllegalArgumentException(subject + " on " + binding.label()
                    + ", which no supported CLI agent runs." + hint);
        }
        if (requestedAgent != null && !requestedAgent.equalsIgnoreCase(boundAgent)) {
            throw new IllegalArgumentException(subject + " as the " + boundAgent
                    + " CLI agent; omit agent (requested " + requestedAgent + ")." + hint);
        }
        if (requestedModel != null && !requestedModel.equals(binding.model())) {
            throw new IllegalArgumentException(subject + " on " + binding.label()
                    + "; omit model (requested " + requestedModel + ")." + hint);
        }
        if (requestedThinking != null && binding.thinking() != null
                && !requestedThinking.equalsIgnoreCase(binding.thinking())) {
            throw new IllegalArgumentException(subject + " with thinking " + binding.thinking()
                    + "; omit thinking (requested " + requestedThinking + ")." + hint);
        }
        return new ParticipantLaunch(participant.id(), role, boundAgent, binding.model(),
                requestedThinking != null ? requestedThinking : binding.thinking());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean isAgentMissing(String result) {
        return result != null && (result.contains("not found in PATH") || result.contains("not found on PATH"));
    }
}
