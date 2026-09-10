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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.BashTool;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic workflow gate for the standard/headless agentic loop.
 *
 * <p>The controller deliberately does not ask an LLM whether objective events happened.
 * Required skills are expanded by the host before the first model request. Enforced mode
 * applies objective phase, routing, asynchronous-work, coordination, validation, and todo
 * completion gates; advisory mode injects the same guidance without blocking tools.</p>
 */
public final class WorkflowController {

    private static final int DEFAULT_MAX_SKILL_PROMPT_CHARS = 250_000;
    private static final int MAX_IDENTICAL_TOOL_FAILURES = 2;
    private static final Set<String> NON_MUTATING_SESSION_TOOLS = Set.of(
            "activate_tools", "edit_coordinator", "exit_plan_mode", "side_panel",
            "sessions", "todoread", "todowrite");
    private static final Set<String> READ_ONLY_SKILL_ACTIONS = Set.of(
            "list_skills", "get_skill", "generate_markdown", "expand_template",
            "scan_provider_skills");
    private static final Set<String> READ_ONLY_PROCESS_ACTIONS = Set.of(
            "list", "output", "stream", "status", "monitor", "unmonitor", "monitors");
    private static final Set<String> READ_ONLY_CRAWL_ACTIONS = Set.of(
            "preflight", "status", "list", "transcript", "source_types",
            "graph_stats", "runtime_config");
    private static final Set<String> READ_ONLY_PIPELINE_ACTIONS = Set.of(
            "capabilities", "list", "get", "versions", "validate", "test", "diff", "status");
    private static final Set<String> READ_ONLY_GRAPH_ACTIONS = Set.of(
            "overview", "stats", "search_entity", "search_nodes", "find_by_topic",
            "related_docs", "source_context", "entities_in_doc", "find_connected",
            "list_nodes", "get_node", "list_edges", "traverse", "shortest_path",
            "algorithm", "communities", "hierarchy", "ancestors", "source_chunks",
            "list_graphs", "report", "list_builders", "list_jobs",
            "job_status", "job_logs", "list_proposals", "get_config", "list_providers",
            "list_presets", "owl_reasoning", "ontology_conformance", "opinions",
            "facts_by_tier", "graph_health", "list_rules", "reactive_rules",
            "node_provenance", "list_pipelines", "reasoning_layers", "list_fact_sheets",
            "get_fact_sheet", "get_active_fact_sheet", "list_snapshots", "list_predicates");
    private static final Set<String> READ_ONLY_ROLE_ACTIONS = Set.of(
            "list_roles", "get_role", "get_agent_role");
    private static final Set<String> READ_ONLY_CHANNEL_ACTIONS = Set.of(
            "providers", "auth", "connections", "status");
    private static final Set<String> READ_ONLY_CONFIG_ACTIONS = Set.of("status", "get", "view", "test");
    private static final Set<String> READ_ONLY_BROWSER_ACTIONS = Set.of(
            "status", "open", "get_content", "wait");
    private static final Set<String> READ_ONLY_SUBAGENT_TYPES = Set.of(
            "explore-quick", "explore-deep", "explorer", "code-reviewer",
            "architect", "researcher");
    private static final Set<String> COORDINATION_CHECK_ACTIONS = Set.of(
            "awareness", "status", "query_edits", "register_edit", "register_edits",
            "preflight_activity", "query_activities");
    private static final Set<String> TERMINAL_TODO_STATUSES = Set.of("completed", "cancelled");
    private static final Set<String> NON_ARTIFACT_MUTATION_TOOLS = Set.of(
            "activate_tools", "browser", "channel", "edit_coordinator", "exit_plan_mode", "process",
            "role_manager", "sessions", "side_panel", "skill_manager", "task",
            "todoread", "todowrite");
    private static final Pattern TODO_ID = Pattern.compile("Added task #([^:]+):");
    private static final Pattern CHECKPOINT_BEFORE_FIRST = Pattern.compile(
            "\\b(list|design|research|investigate|analy[sz]e|review|plan)\\b.{0,40}\\bfirst\\b");
    private static final Pattern CHECKPOINT_FIRST_BEFORE = Pattern.compile(
            "^\\s*first\\s*,?\\s*(list|design|research|investigate|analy[sz]e|review|plan)\\b"
                    + ".*\\b(then|before|wait)\\b");

    /** Deterministic progress through one logical user turn. */
    public enum Phase {
        PREFLIGHT,
        DISCOVERY,
        PLANNED,
        EXECUTION,
        WAITING,
        VALIDATION,
        COMPLETE
    }

    public record Decision(boolean allowed, String reason, String correctionPrompt) {
        public static Decision allow() {
            return new Decision(true, "", "");
        }

        public static Decision block(String reason, String correctionPrompt) {
            return new Decision(false, reason, correctionPrompt);
        }
    }

    public record FinalDecision(boolean allowed, boolean retry,
                                String reason, String correctionPrompt) {
        public static FinalDecision allow() {
            return new FinalDecision(true, false, "", "");
        }
    }

    public record Status(WorkflowPolicy.Mode configuredMode,
                         WorkflowPolicy.Mode effectiveMode,
                         List<String> requiredSkills,
                         boolean requirePlanBeforeMutation,
                         int maxCorrections,
                         boolean globalEnabled,
                         boolean sessionEnabled,
                         boolean sessionOverride) {
    }

    /** Read-only diagnostic snapshot of the active turn, primarily for UI and tests. */
    public record TurnStatus(String sessionId,
                             Phase phase,
                             boolean readOnlyCheckpoint,
                             boolean planRecorded,
                             boolean coordinationChecked,
                             boolean artifactDirty,
                             boolean validationRecorded,
                             List<String> pendingValidationProcessIds,
                             List<String> activeEditLockIds,
                             List<String> openTodoIds,
                             int corrections) {
    }

    /** Minimal state that must survive a monitored validation crossing a turn boundary. */
    private static final class SessionState {
        private boolean artifactDirty;
        private boolean validationRecorded;
        private final Set<String> validationProcesses = new LinkedHashSet<>();
        private final Map<String, String> todos = new LinkedHashMap<>();
        private final Set<String> editLockIds = new LinkedHashSet<>();
    }

    private static final class TurnState {
        private final WorkflowPolicy policy;
        private final String systemPrompt;
        private final String sessionId;
        private final boolean readOnlyCheckpoint;
        private final Map<String, String> todos = new LinkedHashMap<>();
        private final Map<String, Integer> toolFailures = new LinkedHashMap<>();
        private final Map<String, Integer> correctionCounts = new LinkedHashMap<>();
        private final Set<String> validationProcesses = new LinkedHashSet<>();
        private final Set<String> editLockIds = new LinkedHashSet<>();
        private Phase phase = Phase.PREFLIGHT;
        private boolean planRecorded;
        private boolean planReadyAtBatchStart;
        private boolean coordinationChecked;
        private boolean coordinationReadyAtBatchStart;
        private boolean blockedMutation;
        private String resourceWaitId;
        private boolean artifactDirty;
        private boolean validationRecorded;
        private String pendingCorrectionReason;
        private String pendingCorrectionPrompt;
        private int generatedTodoId;
        private int corrections;

        private TurnState(WorkflowPolicy policy, String systemPrompt,
                          String sessionId, boolean readOnlyCheckpoint,
                          SessionState persisted) {
            this.policy = policy;
            this.systemPrompt = systemPrompt;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.readOnlyCheckpoint = readOnlyCheckpoint;
            if (persisted != null) {
                synchronized (persisted) {
                    artifactDirty = persisted.artifactDirty;
                    validationRecorded = artifactDirty && persisted.validationRecorded;
                    validationProcesses.addAll(persisted.validationProcesses);
                    todos.putAll(persisted.todos);
                    editLockIds.addAll(persisted.editLockIds);
                }
            }
            if (artifactDirty) {
                phase = validationProcesses.isEmpty() ? Phase.EXECUTION : Phase.WAITING;
            }
        }

        private void advance(Phase next) {
            if (next != null && next.ordinal() > phase.ordinal()) phase = next;
        }
    }

    private final Path workingDirectory;
    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final ThreadLocal<TurnState> activeTurn = new ThreadLocal<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    private volatile WorkflowPolicy configuredPolicy;
    private volatile WorkflowPolicy sessionOverride;
    private volatile boolean globalEnabled = true;
    private volatile boolean sessionEnabled = true;

    public WorkflowController(Path workingDirectory, SkillRegistry skillRegistry,
                              ToolRegistry toolRegistry) {
        this.workingDirectory = workingDirectory == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : workingDirectory.toAbsolutePath().normalize();
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        reload();
    }

    /** Test/in-process constructor that does not read project configuration. */
    public WorkflowController(SkillRegistry skillRegistry, ToolRegistry toolRegistry,
                              WorkflowPolicy policy) {
        this.workingDirectory = null;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.configuredPolicy = policy == null ? WorkflowPolicy.off() : policy;
    }

    public synchronized void reload() {
        if (workingDirectory == null) return;
        EnforcerConfig config = EnforcerConfig.load(workingDirectory);
        if (config == null && Files.exists(EnforcerConfig.resolveConfigPath(workingDirectory))) {
            throw new IllegalStateException("Workflow configuration could not be loaded from "
                    + EnforcerConfig.resolveConfigPath(workingDirectory));
        }
        WorkflowPolicy loaded = WorkflowPolicy.from(config);
        configuredPolicy = loaded;
        sessionOverride = null;
    }

    public synchronized void setSessionMode(WorkflowPolicy.Mode mode,
                                            List<String> requiredSkills) {
        WorkflowPolicy base = configuredPolicy == null ? WorkflowPolicy.off() : configuredPolicy;
        WorkflowPolicy replacement = base.withSessionMode(mode, requiredSkills);
        validateRequiredSkills(replacement);
        sessionOverride = replacement;
    }

    public void setGlobalEnabled(boolean enabled) {
        globalEnabled = enabled;
    }

    public void setSessionEnabled(boolean enabled) {
        sessionEnabled = enabled;
    }

    public boolean isActive() {
        return effectivePolicy().active();
    }

    public boolean isEnforced() {
        return effectivePolicy().enforced();
    }

    public Status status() {
        WorkflowPolicy configured = configuredPolicy == null
                ? WorkflowPolicy.off() : configuredPolicy;
        WorkflowPolicy selected = sessionOverride != null ? sessionOverride : configured;
        WorkflowPolicy effective = effectivePolicy();
        return new Status(configured.mode(), effective.mode(), selected.requiredSkills(),
                selected.requirePlanBeforeMutation(), selected.maxCorrections(),
                globalEnabled, sessionEnabled, sessionOverride != null);
    }

    /** Begin one logical user turn before its first model request. */
    public void beginTurn() {
        beginTurn("", "");
    }

    /** Begin a turn with the user intent and session identity needed by strict gates. */
    public void beginTurn(String userMessage, String sessionId) {
        WorkflowPolicy policy = effectivePolicy();
        validateRequiredSkills(policy);
        boolean checkpoint = policy.active() && requestsReadOnlyCheckpoint(userMessage);
        String stateKey = sessionId == null ? "" : sessionId;
        SessionState persisted = policy.enforced()
                ? sessionStates.computeIfAbsent(stateKey, ignored -> new SessionState())
                : null;
        activeTurn.set(new TurnState(policy, renderSystemPrompt(policy, checkpoint),
                stateKey, checkpoint, persisted));
    }

    /** Captured on the parent thread; contains no turn state or ThreadLocal reference. */
    public record ChildContract(WorkflowPolicy policy, String systemPrompt, boolean readOnlyCheckpoint) {
        public ChildContract {
            java.util.Objects.requireNonNull(policy);
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
        }

        public WorkflowController newController(ToolRegistry tools) {
            return new WorkflowController(null, tools, policy);
        }
    }

    public ChildContract captureChildContract() {
        TurnState turn = activeTurn.get();
        WorkflowPolicy policy = turn == null ? effectivePolicy() : turn.policy;
        return new ChildContract(policy, turn == null ? renderSystemPrompt(policy, false)
                : turn.systemPrompt, turn != null && turn.readOnlyCheckpoint);
    }

    /** Start on the child owner thread using frozen skills/policy, never reloading configuration. */
    public void beginChildTurn(ChildContract contract, String prompt, String sessionId) {
        boolean checkpoint = contract.readOnlyCheckpoint()
                || (contract.policy().active() && requestsReadOnlyCheckpoint(prompt));
        activeTurn.set(new TurnState(contract.policy(), contract.systemPrompt(), sessionId,
                checkpoint, sessionStates.computeIfAbsent(sessionId, ignored -> new SessionState())));
    }

    public void completeTurn() {
        TurnState turn = activeTurn.get();
        if (turn != null) persistSessionState(turn);
        activeTurn.remove();
    }

    /** Full required-skill content appended to the system prompt for the active turn. */
    public String activeSystemPrompt() {
        TurnState turn = activeTurn.get();
        return turn == null ? "" : turn.systemPrompt;
    }

    public TurnStatus activeTurnStatus() {
        TurnState turn = activeTurn.get();
        if (turn == null) return null;
        return new TurnStatus(turn.sessionId, turn.phase, turn.readOnlyCheckpoint, turn.planRecorded,
                turn.coordinationChecked, turn.artifactDirty, turn.validationRecorded,
                List.copyOf(turn.validationProcesses),
                List.copyOf(turn.editLockIds),
                openTodoIds(turn), turn.corrections);
    }

    /** Freeze prerequisites at one assistant-response boundary. */
    public void beginToolBatch() {
        TurnState turn = activeTurn.get();
        if (turn != null) {
            turn.planReadyAtBatchStart = turn.planRecorded;
            turn.coordinationReadyAtBatchStart = turn.coordinationChecked;
        }
    }

    /** A captured child checkpoint is an authority boundary, even for an advisory profile. */
    public Decision beforeChildTool(String toolName, JsonNode arguments) {
        TurnState turn = activeTurn.get();
        if (turn == null) return Decision.block("Child workflow was not initialized", "Stop execution.");
        if (turn.readOnlyCheckpoint && isMutation(toolName, arguments)) {
            return Decision.block("The inherited child checkpoint is read-only",
                    "Complete the requested review without mutations; a child cannot relax its checkpoint.");
        }
        return beforeTool(toolName, arguments);
    }

    /** Evaluate a proposed call before any probabilistic judge or tool execution. */
    public Decision beforeTool(String toolName, JsonNode arguments) {
        TurnState turn = activeTurn.get();
        if (turn == null || !turn.policy.enforced()) return Decision.allow();

        String normalized = canonicalToolName(toolName);
        boolean mutation = isMutation(toolName, arguments);

        String fingerprint = toolFingerprint(normalized, arguments);
        int failures = turn.toolFailures.getOrDefault(fingerprint, 0);
        if (failures >= MAX_IDENTICAL_TOOL_FAILURES) {
            return Decision.block(
                    "identical tool call already failed " + failures + " times",
                    "Do not repeat the same call. Change the inputs, use a different dedicated "
                            + "tool, inspect the failure, or report the blocked dependency.");
        }

        if (turn.readOnlyCheckpoint && mutation) {
            return Decision.block(
                    "the user requested a read-only first stage before implementation",
                    "Finish the requested list, design, research, or review without mutating state. "
                            + "Wait for the user to explicitly proceed in a later turn.");
        }

        if ("bash".equals(normalized)) {
            String command = arguments == null ? "" : arguments.path("command").asText("");
            String replacement = dedicatedToolReplacement(command);
            if (replacement != null) {
                return blockUntilProgress(turn,
                        "shell command duplicates dedicated Kompile tooling",
                        replacement);
            }
        }

        if (turn.policy.requirePlanBeforeMutation() && mutation
                && !turn.planReadyAtBatchStart) {
            turn.blockedMutation = true;
            String reason = "workflow requires a current-turn plan before mutating tool '"
                    + normalized + "'";
            String correction = "Before retrying '" + normalized + "', create the task plan with "
                    + "todowrite using action='add' or a non-empty action='set'. Wait for that "
                    + "tool result, then continue in the next model step.";
            return Decision.block(reason, correction);
        }

        Decision asynchronous = requireAsynchronousExecution(turn, normalized, arguments);
        if (!asynchronous.allowed()) return asynchronous;

        if (isArtifactMutation(toolName, arguments) && coordinationToolAvailable()
                && !turn.coordinationReadyAtBatchStart) {
            return blockUntilProgress(turn,
                    "workflow requires a current-turn coordination check before artifact mutation",
                    "Activate the process tool group if needed, then call edit_coordinator with "
                            + "action='awareness', 'preflight_activity', 'query_edits', "
                            + "'register_edit', or 'register_edits'. Wait for its result, "
                            + "coordinate any overlap, then mutate in the next model step.");
        }

        return Decision.allow();
    }

    /** Record only successful tool events; failed calls never satisfy a prerequisite. */
    public void afterTool(String toolName, JsonNode arguments, ToolResult result) {
        TurnState turn = activeTurn.get();
        if (turn == null || result == null) return;
        String normalized = canonicalToolName(toolName);
        updateEditLocks(turn, normalized, arguments, result);

        String fingerprint = toolFingerprint(normalized, arguments);
        Object resourceWaitId = result.getMetadata().get("resourceWaitId");
        if (resourceWaitId != null) {
            turn.resourceWaitId = resourceWaitId.toString();
            if (result.isError() && Boolean.FALSE.equals(result.getMetadata().get("admitted"))) {
                // Resource contention is not a repeated tool failure or completed validation.
                turn.toolFailures.remove(fingerprint);
                turn.phase = Phase.WAITING;
                persistSessionState(turn);
                return;
            }
        }
        if (resultSignalsFailure(result)) {
            turn.toolFailures.merge(fingerprint, 1, Integer::sum);
            handleFailedValidationProcess(turn, normalized, arguments, result);
            if (isArtifactMutation(toolName, arguments) && resultModifiedArtifacts(result)) {
                turn.artifactDirty = true;
                turn.validationRecorded = false;
                turn.validationProcesses.clear();
                turn.phase = Phase.EXECUTION;
            }
            persistSessionState(turn);
            return;
        }
        turn.toolFailures.remove(fingerprint);
        turn.pendingCorrectionReason = null;
        turn.pendingCorrectionPrompt = null;

        if ("todowrite".equals(normalized) && establishesPlan(arguments)) {
            turn.planRecorded = true;
            turn.blockedMutation = false;
            turn.advance(Phase.PLANNED);
        }
        if ("todowrite".equals(normalized)) {
            updateTodoState(turn, arguments, result);
        }
        if (isCoordinationCheck(normalized, arguments)
                && coordinationResultSatisfied(result)) {
            turn.coordinationChecked = true;
        }

        if (isValidationProcessLaunch(normalized, arguments)) {
            Object processId = result.getMetadata().get("processId");
            if (processId != null) turn.validationProcesses.add(processId.toString());
        }

        boolean validation = isSuccessfulValidation(turn, normalized, arguments, result);
        boolean artifactMutation = isArtifactMutation(toolName, arguments);
        if (artifactMutation && !validation) {
            turn.artifactDirty = true;
            turn.validationRecorded = false;
            turn.validationProcesses.clear();
            turn.phase = Phase.EXECUTION;
        }
        if (validation) {
            turn.artifactDirty = false;
            turn.validationRecorded = true;
            if (!"process".equals(normalized)) turn.validationProcesses.clear();
            turn.phase = Phase.VALIDATION;
        } else if (!isMutation(toolName, arguments)) {
            turn.advance(Phase.DISCOVERY);
        }
        persistSessionState(turn);
    }

    /**
     * Prevent a text-only finish immediately after a blocked mutation. This is bounded so
     * a broken model cannot create an infinite correction loop.
     */
    public FinalDecision beforeFinalResponse() {
        TurnState turn = activeTurn.get();
        if (turn == null || !turn.policy.enforced()) {
            return FinalDecision.allow();
        }

        if (turn.blockedMutation && !turn.planRecorded) {
            return correction(turn, "plan",
                    "workflow prerequisites remain incomplete after a blocked mutation",
                    "Do not finish yet. Create a current-turn plan with todowrite "
                            + "(action='add' or a non-empty action='set'), then continue the task.");
        }
        if (!turn.editLockIds.isEmpty()) {
            return correction(turn, "edit-locks",
                    "edit coordinator locks remain active: "
                            + String.join(", ", turn.editLockIds),
                    "Release every acquired edit lock with edit_coordinator release_edit or "
                            + "release_edits before finishing or waiting on long validation.");
        }
        if (turn.pendingCorrectionReason != null) {
            return correction(turn, "pending:" + turn.pendingCorrectionReason,
                    turn.pendingCorrectionReason, turn.pendingCorrectionPrompt);
        }
        if (turn.resourceWaitId != null && toolRegistry != null
                && toolRegistry.get("edit_coordinator") instanceof EditCoordinatorTool coordination
                && coordination.canPauseForActivity(turn.sessionId, turn.resourceWaitId)) {
            turn.phase = Phase.WAITING;
            persistSessionState(turn);
            return FinalDecision.allow();
        }
        if (turn.artifactDirty && !turn.validationProcesses.isEmpty()) {
            turn.phase = Phase.WAITING;
            persistSessionState(turn);
            return FinalDecision.allow();
        }
        if (turn.artifactDirty) {
            return correction(turn, "validation",
                    "artifact mutations have not been validated",
                    "Run the focused build, test, compiler, or validation command now. For long "
                            + "work use process action='launch'; the harness backgrounds and monitors it. "
                            + "Then confirm its "
                            + "successful terminal result before finishing.");
        }
        List<String> openTodos = openTodoIds(turn);
        if (!openTodos.isEmpty()) {
            return correction(turn, "todos",
                    "current-turn todos remain open: " + String.join(", ", openTodos),
                    "Update every current-turn todo to completed or cancelled before finishing.");
        }

        turn.advance(Phase.COMPLETE);
        turn.todos.clear();
        persistSessionState(turn);
        return FinalDecision.allow();
    }

    private FinalDecision correction(
            TurnState turn, String correctionKey, String reason, String correction) {
        int attempts = turn.correctionCounts.getOrDefault(correctionKey, 0);
        if (attempts < turn.policy.maxCorrections()) {
            turn.correctionCounts.put(correctionKey, attempts + 1);
            turn.corrections++;
            return new FinalDecision(false, true, reason, correction);
        }
        return new FinalDecision(false, false, reason,
                "Workflow correction limit reached; the turn cannot be accepted.");
    }

    boolean isMutation(String toolName, JsonNode arguments) {
        String normalized = canonicalToolName(toolName);
        if (NON_MUTATING_SESSION_TOOLS.contains(normalized)) return false;
        if (isKnownReadOnlyAction(normalized, arguments)) return false;
        if (isValidationRequest(normalized, arguments)) return false;
        if ("channel".equals(normalized)) return true;
        if ("bash".equals(normalized)) {
            return !BashTool.isReadOnlyCommand(
                    arguments == null ? "" : arguments.path("command").asText(""));
        }
        if ("skill_manager".equals(normalized)) {
            String action = arguments == null ? "" : arguments.path("action").asText("");
            return !READ_ONLY_SKILL_ACTIONS.contains(action);
        }
        if ("process".equals(normalized)) {
            String action = arguments == null ? "" : arguments.path("action").asText("");
            return !READ_ONLY_PROCESS_ACTIONS.contains(action);
        }

        CliTool tool = toolRegistry == null ? null : toolRegistry.get(toolName);
        if (tool == null && toolRegistry != null) tool = toolRegistry.get(normalized);
        if (tool == null) return false;
        var annotations = tool.mcpAnnotations();
        if (annotations == null) return true;
        if (annotations.readOnlyHint()) return false;
        // Generic NETWORK/DELEGATION annotations describe locality, not mutation.
        // Known consequential actions are classified above; unclassified open-world
        // lookups remain available during discovery.
        return annotations.destructiveHint() || !annotations.openWorldHint();
    }

    boolean isArtifactMutation(String toolName, JsonNode arguments) {
        String normalized = canonicalToolName(toolName);
        if (NON_ARTIFACT_MUTATION_TOOLS.contains(normalized)) return false;
        if ("bash".equals(normalized)) {
            String command = arguments == null ? "" : arguments.path("command").asText("");
            return !BashTool.isReadOnlyCommand(command) && !isValidationCommand(command);
        }
        if ("lsp".equals(normalized)) {
            return "rename".equals(action(arguments))
                    && !(arguments != null && arguments.path("dry_run").asBoolean(true));
        }
        if ("local_code_index".equals(normalized)) {
            return "replace".equals(action(arguments))
                    && !(arguments != null && arguments.path("dry_run").asBoolean(true));
        }

        CliTool tool = toolRegistry == null ? null : toolRegistry.get(toolName);
        if (tool == null && toolRegistry != null) tool = toolRegistry.get(normalized);
        return tool != null && isMutation(toolName, arguments)
                && !isValidationRequest(normalized, arguments);
    }

    private static boolean isKnownReadOnlyAction(String normalized, JsonNode arguments) {
        String action = action(arguments);
        return switch (normalized) {
            case "crawl_control" -> READ_ONLY_CRAWL_ACTIONS.contains(action);
            case "pipeline" -> READ_ONLY_PIPELINE_ACTIONS.contains(action);
            case "knowledge_graph" -> READ_ONLY_GRAPH_ACTIONS.contains(action)
                    || ("cypher".equals(action)
                        && (arguments == null || arguments.path("read_only").asBoolean(true)));
            case "role_manager" -> READ_ONLY_ROLE_ACTIONS.contains(action);
            case "channel" -> READ_ONLY_CHANNEL_ACTIONS.contains(action);
            case "judge_control" -> "status".equals(action);
            case "enforcer_config", "project_config" -> READ_ONLY_CONFIG_ACTIONS.contains(action);
            case "browser" -> READ_ONLY_BROWSER_ACTIONS.contains(action);
            case "task" -> READ_ONLY_SUBAGENT_TYPES.contains(arguments == null
                    ? "explore-quick"
                    : arguments.path("agent_type").asText("explore-quick")
                            .toLowerCase(Locale.ROOT));
            case "webfetch", "websearch", "graph_search", "rag_search",
                 "knowledge_search", "knowledge_status" -> true;
            default -> false;
        };
    }

    private Decision requireAsynchronousExecution(
            TurnState turn, String normalized, JsonNode arguments) {
        if (("crawl_documents".equals(normalized) || "crawl_source".equals(normalized))
                && arguments != null && !arguments.path("dryRun").asBoolean(false)
                && ((arguments.has("async") && !arguments.path("async").asBoolean(true))
                    || arguments.path("waitForCompletion").asBoolean(false))) {
            return blockUntilProgress(turn,
                    "production crawls must use the asynchronous lifecycle",
                    "Start the crawl with async=true and waitForCompletion=false. Preserve the "
                            + "returned jobId, monitor that job, and call crawl_result only after terminal=true.");
        }
        if ("crawl_control".equals(normalized) && "start".equals(action(arguments))
                && arguments != null && arguments.has("async")
                && !arguments.path("async").asBoolean(true)) {
            return blockUntilProgress(turn,
                    "crawl_control start must be asynchronous",
                    "Retry crawl_control operation='start' with async=true and retain the returned jobId.");
        }
        if ("pipeline".equals(normalized) && "run".equals(action(arguments))
                && arguments != null && arguments.path("wait").asBoolean(false)) {
            return blockUntilProgress(turn,
                    "pipeline run must use asynchronous completion",
                    "Retry pipeline action='run' with wait=false, then monitor the returned runId.");
        }
        return Decision.allow();
    }

    private Decision blockUntilProgress(TurnState turn, String reason, String correction) {
        turn.pendingCorrectionReason = reason;
        turn.pendingCorrectionPrompt = correction;
        return Decision.block(reason, correction);
    }

    private boolean coordinationToolAvailable() {
        return toolRegistry != null && toolRegistry.get("edit_coordinator") != null;
    }

    private static boolean isCoordinationCheck(String normalized, JsonNode arguments) {
        return "edit_coordinator".equals(normalized)
                && COORDINATION_CHECK_ACTIONS.contains(action(arguments));
    }

    private static String dedicatedToolReplacement(String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\bsleep\\s+[0-9]+.*")
                || lower.contains("gh run watch")
                || lower.contains("gh pr checks --watch")) {
            return "Use process action='launch' (host-backgrounded and monitored) or a dedicated "
                    + "external-job monitor instead of sleeping or polling in Bash.";
        }
        for (ShellCommand shell : shellCommands(command)) {
            String shellArgs = shell.arguments().stripLeading().toLowerCase(Locale.ROOT);
            if ("git".equals(shell.name()) && shellArgs.matches("reset(?:\\s.*)?")) {
                return "git reset is not allowed in the enforced workflow. Inspect with git status/diff "
                        + "and make explicit, non-destructive staging or file changes instead.";
            }
            if ("git".equals(shell.name()) && shellArgs.matches("grep(?:\\s.*)?")) {
                return "Use grep/grep_batch instead of git grep so search remains structured and highlighted.";
            }
            String replacement = switch (shell.name()) {
                case "cat", "head", "tail", "less", "more" -> shell.arguments().contains(">")
                        ? "Use write for a new file or edit/edit_batch for an existing file; do not redirect file content in Bash."
                        : "Use read/read_batch for file content so output remains structured and highlighted.";
                case "grep", "egrep", "fgrep", "rg", "ag", "ack" ->
                        "Use grep/grep_batch instead of shell search so matches retain paths, lines, and highlighting.";
                case "find", "fd", "locate" ->
                        "Use glob (or explore for a tree overview) instead of shell file discovery.";
                case "ls", "ll", "dir", "tree" ->
                        "Use list for one directory or explore for a recursive project overview.";
                case "curl", "wget", "http", "httpie" ->
                        "Use webfetch for a known URL, websearch for discovery, or browser for interaction.";
                case "sed", "awk", "perl" ->
                        "Use read/grep for inspection and edit/edit_batch/edit_patch for modification; do not transform files in Bash.";
                case "tee" ->
                        "Use write for a new file or edit/edit_batch for an existing file instead of tee/redirection.";
                case "kill", "killall", "pkill" ->
                        "Use process action='kill' with the exact tracked process ID; never broadly kill shared processes.";
                case "echo", "printf" -> shell.arguments().contains(">")
                        ? "Use write for a new file or edit/edit_batch for an existing file instead of shell redirection."
                        : null;
                default -> null;
            };
            if (replacement != null) return replacement;
        }
        return null;
    }

    private record ShellCommand(String name, String arguments) {
    }

    private static List<ShellCommand> shellCommands(String command) {
        List<ShellCommand> commands = new ArrayList<>();
        if (command == null) return commands;
        for (String segment : command.split("[|;&]+")) {
            String cleaned = segment.trim();
            if (cleaned.startsWith("env ")) cleaned = cleaned.substring(4).stripLeading();
            cleaned = cleaned.replaceFirst(
                    "^([A-Za-z_][A-Za-z0-9_]*=\\S+\\s+)+", "");
            if (cleaned.startsWith("sudo ")) cleaned = cleaned.substring(5).stripLeading();
            if (cleaned.isBlank()) continue;
            String[] parts = cleaned.split("\\s+", 2);
            String name = parts[0];
            if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            commands.add(new ShellCommand(name.toLowerCase(Locale.ROOT),
                    parts.length > 1 ? parts[1] : ""));
        }
        return commands;
    }

    private static boolean isValidationCommand(String command) {
        for (ShellCommand shell : shellCommands(command)) {
            String args = shell.arguments().toLowerCase(Locale.ROOT);
            boolean target = containsToken(args, "test") || containsToken(args, "verify")
                    || containsToken(args, "check") || containsToken(args, "build")
                    || containsToken(args, "package") || containsToken(args, "install")
                    || containsToken(args, "compile") || containsToken(args, "lint")
                    || containsToken(args, "clippy") || containsToken(args, "typecheck");
            switch (shell.name()) {
                case "mvn", "mvnw", "gradle", "gradlew", "npm", "npx", "yarn",
                     "pnpm", "bun", "cargo" -> {
                    if (target) return true;
                }
                case "make", "ninja" -> {
                    return true;
                }
                case "cmake" -> {
                    if (target || args.contains("--build")) return true;
                }
                case "pytest", "ctest", "javac", "tsc", "eslint", "ruff", "mypy" -> {
                    return true;
                }
                case "go" -> {
                    if (args.startsWith("test ") || "test".equals(args)) return true;
                }
                case "bash", "sh", "zsh" -> {
                    String script = args.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    if (script.contains("test") || script.contains("build")
                            || script.contains("verify") || script.contains("check")) return true;
                }
                case "python", "python3" -> {
                    if (args.contains("pytest") || args.contains("unittest")
                            || args.contains("mypy") || args.contains("ruff")) return true;
                }
                default -> {
                    if ((shell.name().contains("test") || shell.name().contains("build"))
                            && (shell.name().endsWith(".sh") || shell.name().endsWith(".py"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsToken(String text, String token) {
        return Pattern.compile("(?:^|\\s|=)" + Pattern.quote(token) + "(?:$|\\s|:)")
                .matcher(text == null ? "" : text).find();
    }

    private static boolean isValidationRequest(String normalized, JsonNode arguments) {
        if (normalized == null) return false;
        if (normalized.startsWith("validate_") || normalized.endsWith("_validation")) return true;
        if ("bash".equals(normalized)) {
            return isValidationCommand(arguments == null ? "" : arguments.path("command").asText(""));
        }
        return switch (normalized) {
            case "lsp" -> "diagnostics".equals(action(arguments));
            case "pipeline" -> Set.of("test", "validate").contains(action(arguments));
            case "ask_graph_verify" -> true;
            default -> false;
        };
    }

    private static boolean isValidationProcessLaunch(String normalized, JsonNode arguments) {
        return "process".equals(normalized) && "launch".equals(action(arguments))
                && arguments != null
                && isValidationCommand(arguments.path("command").asText(""));
    }

    private static boolean isSuccessfulValidation(
            TurnState turn, String normalized, JsonNode arguments, ToolResult result) {
        if (isValidationRequest(normalized, arguments)) {
            return validationResultPassed(normalized, result);
        }
        if (!"process".equals(normalized) || arguments == null) return false;
        String processId = arguments.path("process_id").asText("");
        if (processId.isBlank() || !turn.validationProcesses.contains(processId)) return false;
        Object state = result.getMetadata().get("state");
        if (state == null || !"COMPLETED".equalsIgnoreCase(state.toString())) return false;
        turn.validationProcesses.remove(processId);
        return turn.validationProcesses.isEmpty();
    }

    private static boolean validationResultPassed(String normalized, ToolResult result) {
        if (result == null || result.isError()) return false;
        if ("ask_graph_verify".equals(normalized)) {
            Object verdict = result.getMetadata().get("verdict");
            return verdict != null && "SUPPORTED".equalsIgnoreCase(verdict.toString());
        }
        if ("lsp".equals(normalized)) {
            Object count = result.getMetadata().get("count");
            return count == null || (count instanceof Number number && number.longValue() == 0);
        }
        if ("pipeline".equals(normalized)) {
            Object valid = result.getMetadata().get("valid");
            if (valid instanceof Boolean flag) return flag;
            String compact = (result.getOutput() == null ? "" : result.getOutput())
                    .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (compact.contains("\"valid\":false")
                    || compact.contains("\"status\":\"failed\"")
                    || compact.contains("\"status\":\"cancelled\"")) return false;
        }
        return true;
    }

    private static void handleFailedValidationProcess(
            TurnState turn, String normalized, JsonNode arguments, ToolResult result) {
        if (!"process".equals(normalized) || arguments == null || result == null) return;
        String processId = arguments.path("process_id").asText("");
        if (processId.isBlank() || !turn.validationProcesses.contains(processId)) return;
        Object state = result.getMetadata().get("state");
        if (state == null || !Set.of("FAILED", "KILLED", "LOST")
                .contains(state.toString().toUpperCase(Locale.ROOT))) return;
        turn.validationProcesses.clear();
        turn.phase = Phase.EXECUTION;
        turn.pendingCorrectionReason = "monitored validation process " + processId
                + " ended in state " + state;
        turn.pendingCorrectionPrompt = "Inspect the failed validation output, fix or explicitly "
                + "re-plan the problem, then launch a new monitored validation. Do not treat another "
                + "still-running job as proof this failure is harmless.";
    }

    private static boolean resultSignalsFailure(ToolResult result) {
        if (result == null || result.isError()) return true;
        for (String key : List.of("filesFailed", "failed", "errors")) {
            Object value = result.getMetadata().get(key);
            if (value instanceof Number number && number.longValue() > 0) return true;
            if (Boolean.TRUE.equals(value)) return true;
        }
        Object state = result.getMetadata().get("state");
        if (state != null && Set.of("FAILED", "KILLED", "LOST")
                .contains(state.toString().toUpperCase(Locale.ROOT))) return true;
        Object status = result.getMetadata().get("status");
        if (status != null && Set.of("CONFLICT", "PARTIAL")
                .contains(status.toString().toUpperCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean resultModifiedArtifacts(ToolResult result) {
        if (result == null) return false;
        for (String key : List.of("filesEdited", "filesPatched", "editsApplied", "writesApplied")) {
            Object value = result.getMetadata().get(key);
            if (value instanceof Number number && number.longValue() > 0) return true;
        }
        return false;
    }

    private static String toolFingerprint(String normalized, JsonNode arguments) {
        return normalized + ":" + (arguments == null ? "{}" : arguments.toString());
    }

    private static String action(JsonNode arguments) {
        return arguments == null ? ""
                : arguments.path("action").asText(
                        arguments.path("operation").asText("")).toLowerCase(Locale.ROOT);
    }

    private static void updateEditLocks(
            TurnState turn, String normalized, JsonNode arguments, ToolResult result) {
        if (!"edit_coordinator".equals(normalized) || arguments == null || result == null) return;
        switch (action(arguments)) {
            case "register_edit" -> {
                Object lockId = result.getMetadata().get("lockId");
                if (lockId != null && !lockId.toString().isBlank()) {
                    turn.editLockIds.add(lockId.toString());
                }
            }
            case "register_edits" -> {
                Object lockIds = result.getMetadata().get("lockIds");
                if (lockIds instanceof Map<?, ?> map) {
                    map.values().forEach(lockId -> {
                        if (lockId != null && !lockId.toString().isBlank()) {
                            turn.editLockIds.add(lockId.toString());
                        }
                    });
                }
            }
            case "release_edit" -> turn.editLockIds.remove(
                    arguments.path("lock_id").asText(""));
            case "release_edits" -> {
                JsonNode lockIds = arguments.path("lock_ids");
                if (lockIds.isArray()) lockIds.forEach(
                        lockId -> turn.editLockIds.remove(lockId.asText("")));
            }
            default -> { }
        }
    }

    private static boolean coordinationResultSatisfied(ToolResult result) {
        if (result == null || result.isError()) return false;
        Object status = result.getMetadata().get("status");
        return status == null || "ACQUIRED".equalsIgnoreCase(status.toString());
    }

    private static void updateTodoState(
            TurnState turn, JsonNode arguments, ToolResult result) {
        if (arguments == null) return;
        String action = action(arguments);
        if ("set".equals(action)) {
            turn.todos.clear();
            JsonNode todos = arguments.path("todos");
            if (!todos.isArray()) return;
            int index = 0;
            for (JsonNode todo : todos) {
                index++;
                String id = todo.isObject() ? todo.path("id").asText(String.valueOf(index))
                        : String.valueOf(index);
                String status = todo.isObject() ? todo.path("status").asText("pending") : "pending";
                turn.todos.put(id, status.toLowerCase(Locale.ROOT));
            }
            return;
        }
        if ("add".equals(action)) {
            Matcher matcher = TODO_ID.matcher(result.getOutput() == null ? "" : result.getOutput());
            String id = matcher.find() ? matcher.group(1)
                    : "added-" + (++turn.generatedTodoId);
            turn.todos.put(id, arguments.path("status").asText("pending")
                    .toLowerCase(Locale.ROOT));
            return;
        }
        String id = arguments.path("task_id").asText("");
        if (id.isBlank()) return;
        if ("delete".equals(action)) {
            turn.todos.remove(id);
        } else if ("update".equals(action) && arguments.has("status")) {
            turn.todos.put(id, arguments.path("status").asText("pending")
                    .toLowerCase(Locale.ROOT));
        }
    }

    private static List<String> openTodoIds(TurnState turn) {
        if (turn == null || turn.todos.isEmpty()) return List.of();
        List<String> open = new ArrayList<>();
        turn.todos.forEach((id, status) -> {
            if (!TERMINAL_TODO_STATUSES.contains(status)) open.add(id);
        });
        return List.copyOf(open);
    }

    private void persistSessionState(TurnState turn) {
        if (turn == null || !turn.policy.enforced()) return;
        if (!turn.artifactDirty && turn.validationProcesses.isEmpty()
                && turn.todos.isEmpty() && turn.editLockIds.isEmpty()) {
            sessionStates.remove(turn.sessionId);
            return;
        }
        SessionState persisted = sessionStates.computeIfAbsent(
                turn.sessionId, ignored -> new SessionState());
        synchronized (persisted) {
            persisted.artifactDirty = turn.artifactDirty;
            persisted.validationRecorded = turn.validationRecorded;
            persisted.validationProcesses.clear();
            persisted.validationProcesses.addAll(turn.validationProcesses);
            persisted.todos.clear();
            persisted.todos.putAll(turn.todos);
            persisted.editLockIds.clear();
            persisted.editLockIds.addAll(turn.editLockIds);
        }
    }

    private static boolean requestsReadOnlyCheckpoint(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String lower = userMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("no code changes") || lower.contains("no changes yet")
                || lower.contains("do not make changes") || lower.contains("don't make changes")
                || lower.contains("do not implement") || lower.contains("don't implement")) {
            return true;
        }
        boolean discovery = CHECKPOINT_BEFORE_FIRST.matcher(lower).find()
                || CHECKPOINT_FIRST_BEFORE.matcher(lower).find();
        return discovery || (lower.contains("before proceeding")
                && lower.matches("(?s).*(list|design|research|investigat|analy[sz]|review|plan).*") );
    }

    private WorkflowPolicy effectivePolicy() {
        WorkflowPolicy selected = sessionOverride != null
                ? sessionOverride
                : (configuredPolicy == null ? WorkflowPolicy.off() : configuredPolicy);
        if (!globalEnabled || !sessionEnabled) {
            return selected.withSessionMode(WorkflowPolicy.Mode.OFF, null);
        }
        return selected;
    }

    private String renderSystemPrompt(WorkflowPolicy policy, boolean readOnlyCheckpoint) {
        if (!policy.active()) return "";
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Host-Applied Workflow Profile (")
                .append(policy.mode().name()).append(")\n\n")
                .append("This workflow profile was applied by the Kompile harness before the ")
                .append("first model request. Objective prerequisites are tracked by the host; ")
                .append("do not merely claim they happened.\n");
        if (policy.requirePlanBeforeMutation()) {
            prompt.append("- Before any mutating tool call, successfully call todowrite with ")
                    .append("action='add' or a non-empty action='set'.\n")
                    .append("- Read-only investigation, skill lookup, tool activation, and todo ")
                    .append("bookkeeping remain available before the plan.\n");
        }
        prompt.append("- Use dedicated Kompile tools for file I/O, search, web access, and result retrieval; ")
                .append("Bash substitutes are rejected in enforced mode.\n")
                .append("- Process launches are host-backgrounded and receive a host-enforced completion monitor. High-memory builds, tests, crawls, model work, and indexing are serialized by the user-wide activity lane; only lightweight independent processes may overlap. Do not use shell sleep or polling loops.\n")
                .append("- Start production crawls and pipelines asynchronously and retain their job/run IDs.\n")
                .append("- edit_coordinator is the agent message bus: inspect active/conflicting work before artifact mutation, communicate overlap, and release every acquired lock before finishing or waiting. The harness automatically preflights RAM/GPU and peer activity before high-memory tools.\n")
                .append("- Keep the code index current and prefer indexed symbols/signatures/LSP for code navigation; treat implausibly empty or huge results as degraded and use a narrower fallback.\n")
                .append("- Prefer graph query/reasoning tools for relationships, provenance, paths, and factual verification.\n")
                .append("- Parallelize only independent work, background eligible long work, and audit delegated results before integration.\n")
                .append("- After artifact mutation, run successful focused validation and close or cancel every current-turn todo before finishing.\n")
                .append("- A monitored validation may cross turns: report it as waiting, then use its wake-up to confirm the terminal result; never claim success early.\n")
                .append("- If a tool is unavailable, slow, or returns implausible data, record the issue and chosen fallback. After a repeated failure, change the input or route; do not retry indefinitely.\n");
        if (readOnlyCheckpoint) {
            prompt.append("- USER CHECKPOINT: this turn is read-only because the user requested a first-stage ")
                    .append("list/design/research/review. Wait for a later explicit proceed instruction before mutation.\n");
        }
        if (policy.mode() == WorkflowPolicy.Mode.ADVISORY) {
            prompt.append("- This profile is advisory: violations are guidance and do not block tools.\n");
        }
        if (!policy.requiredSkills().isEmpty()) {
            prompt.append("\n## Required Start Skills\n\n")
                    .append("The harness loaded the complete configured skills below before work began. ")
                    .append("Follow each skill, including any instruction to read referenced files.\n\n");
            for (String name : policy.requiredSkills()) {
                SkillConfig skill = skillRegistry.get(name);
                prompt.append("<skill name=\"").append(skill.getName())
                        .append("\" source=\"workflow\">\n")
                        .append(skill.expandTemplate("").strip())
                        .append("\n</skill>\n\n");
            }
        }
        int maxChars = Integer.getInteger(
                "kompile.chat.maxWorkflowSkillChars", DEFAULT_MAX_SKILL_PROMPT_CHARS);
        if (prompt.length() > maxChars) {
            throw new IllegalStateException("Configured workflow prompt requires "
                    + prompt.length() + " characters, exceeding the limit " + maxChars);
        }
        return prompt.toString().stripTrailing();
    }

    private void validateRequiredSkills(WorkflowPolicy policy) {
        if (policy == null || !policy.active() || policy.requiredSkills().isEmpty()) return;
        if (skillRegistry == null) {
            throw new IllegalStateException("Workflow requires skills but no skill registry is available");
        }
        List<String> missing = new ArrayList<>();
        for (String name : policy.requiredSkills()) {
            if (skillRegistry.get(name) == null) missing.add(name);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Required workflow skill(s) not found: "
                    + String.join(", ", missing));
        }
    }

    private static boolean establishesPlan(JsonNode arguments) {
        if (arguments == null) return false;
        String action = arguments.path("action").asText("").toLowerCase(Locale.ROOT);
        if ("add".equals(action)) {
            return !arguments.path("subject").asText("").isBlank();
        }
        return "set".equals(action)
                && arguments.path("todos").isArray()
                && !arguments.path("todos").isEmpty();
    }

    private static String canonicalToolName(String toolName) {
        if (toolName == null) return "";
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        int namespace = normalized.lastIndexOf("__");
        if (namespace >= 0 && namespace + 2 < normalized.length()) {
            normalized = normalized.substring(namespace + 2);
        }
        if (normalized.startsWith("mcp_")) normalized = normalized.substring(4);
        return normalized;
    }
}
