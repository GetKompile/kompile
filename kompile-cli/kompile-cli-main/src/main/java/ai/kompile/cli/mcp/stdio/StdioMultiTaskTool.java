package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Splits a complex task into multiple distinct subtasks and runs each on an
 * independent supported agent instance in parallel. Unlike quorum_task which sends the
 * <em>same</em> prompt to every instance, multi_task sends a <em>different</em>
 * prompt to each, then collects and summarises the results.
 */
public class StdioMultiTaskTool {

    private static final String DEFAULT_AGENT = StdioTaskTool.DEFAULT_AGENT;
    private static final List<String> SUPPORTED_AGENTS = StdioTaskTool.SUPPORTED_AGENTS;

    private final AgentRegistry agentRegistry;
    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;
    private final Path workDir;
    private final RoleManager roleManager;

    public StdioMultiTaskTool(AgentRegistry agentRegistry,
                              DirectSubagentRunnerStdio subagentRunner,
                              ObjectMapper objectMapper,
                              Path workDir,
                              RoleManager roleManager) {
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
        this.workDir = workDir;
        this.roleManager = roleManager;
    }

    /**
     * Constructor with optional coordination state manager for multi-agent
     * edit tracking and conflict detection.
     */
    public StdioMultiTaskTool(AgentRegistry agentRegistry,
                              DirectSubagentRunnerStdio subagentRunner,
                              ObjectMapper objectMapper,
                              Path workDir,
                              RoleManager roleManager,
                              Object coordinationStateManager) {
        this(agentRegistry, subagentRunner, objectMapper, workDir, roleManager);
        // coordinationStateManager stored for future use
    }

    public String id() { return "multi_task"; }

    public String description() {
        return "Run 2+ independent subtasks in one parallel batch instead of serial task calls. " +
            "Submit all ready, independent work together before waiting for results. " +
            "This is the default for separate investigations, reviews of existing code, or edits to disjoint files; " +
            "a task need not be complex to benefit from batching.\n\n" +
            "Each subtask gets its own prompt and context. Include scope, required skills, evidence, and file ownership. " +
            "Use edit_coordinator locks for concurrent edits. Do not batch work that depends on another subtask's output " +
            "(such as reviewing a change that has not been made), edits to the same files, or resource-conflicting builds/tests. " +
            "Run dependent phases sequentially, batching independent work within each phase. Use task for one delegation.\n\n" +
            "Available agents: codex (default), claude, opencode, gemini, qwen, pi. Subtasks may set their own agent, role, model, and thinking; " +
            "different roles do not require serial dispatch. Top-level role/model/thinking provide defaults. " +
            "agent_count defaults to 1 per subtask; increase it only to duplicate that subtask's prompt, not to enable parallelism. " +
            "Unlike quorum_task (same prompt for independent judgments), multi_task assigns distinct work.\n\n" +
            "In a workflow team, give each subtask a purpose (or a team role) and no agent or model: the team picks " +
            "its participant, whose CLI agent, model, and thinking the subtask runs on, and the whole batch must fit " +
            "the team's concurrent-worker limit.\n\n" +
            "Example:\n" +
            "{\"description\":\"Inspect independent modules\",\"subtasks\":[" +
            "{\"name\":\"api\",\"prompt\":\"Read-only: inspect API validation and report file:line findings.\"}," +
            "{\"name\":\"ui\",\"prompt\":\"Read-only: inspect UI validation and report file:line findings.\"}]}\n\n" +
            "All subtasks launch before results are collected. Returns per-subtask summaries; full output is written to a file.";
    }

    /** Preserve the batching contract when MCP parameter descriptions are stripped. */
    public String compactHint() {
        return "Run 2+ independent subtasks in parallel, not serial task calls. Send description + subtasks[{name,prompt,agent?,role?,model?,thinking?}]. agent_count defaults to 1; avoid file/resource conflicts.";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        var desc = props.putObject("description");
        desc.put("type", "string");
        desc.put("description", "A short (3-5 word) description of the overall task");

        var subtasks = props.putObject("subtasks");
        subtasks.put("type", "array");
        subtasks.put("description", "Submit all ready independent subtasks together (at least 2). Each gets its own prompt; do not include dependencies between entries.");
        subtasks.put("minItems", 2);

        var items = subtasks.putObject("items");
        items.put("type", "object");
        var itemProps = items.putObject("properties");

        var subName = itemProps.putObject("name");
        subName.put("type", "string");
        subName.put("description", "Short label for this subtask (e.g., 'backend-api', 'tests', 'docs')");

        var subPrompt = itemProps.putObject("prompt");
        subPrompt.put("type", "string");
        subPrompt.put("description", "The prompt for this specific subtask. Include all context the agent needs.");

        var subAgent = itemProps.putObject("agent");
        subAgent.put("type", "string");
        subAgent.put("default", DEFAULT_AGENT);
        subAgent.put("description", "Agent for this subtask. Available: codex (default), claude, opencode, gemini, qwen, pi.");
        ArrayNode enumValues = subAgent.putArray("enum");
        SUPPORTED_AGENTS.forEach(enumValues::add);

        var subAgents = itemProps.putObject("agents");
        subAgents.put("type", "array");
        subAgents.put("description", "Agent types for this subtask. Use agent_count for multiple independent instances of each type.");
        var agentItems = subAgents.putObject("items");
        agentItems.put("type", "string");
        ArrayNode agentEnumValues = agentItems.putArray("enum");
        SUPPORTED_AGENTS.forEach(agentEnumValues::add);

        var subRole = itemProps.putObject("role");
        subRole.put("type", "string");
        subRole.put("description", "Optional role for this subtask. Overrides the top-level role; when both are omitted, the agent's persisted role assignment is used.");

        var subPurpose = itemProps.putObject("purpose");
        subPurpose.put("type", "string");
        subPurpose.put("description", "Workflow teams only: this subtask's routing purpose (e.g. implement, review). The team picks the participant, whose CLI agent, model, and thinking the subtask runs on.");

        var subModel = itemProps.putObject("model");
        subModel.put("type", "string");
        subModel.put("description", "Optional model for this subtask. Precedence: subtask value, top-level value, role default, project default, user default, provider native default.");

        var subThinking = itemProps.putObject("thinking");
        subThinking.put("type", "string");
        subThinking.put("description", "Optional thinking/effort override for this subtask. Maps to Codex effort, Claude effort, or OpenCode variant.");

        var subAgentCount = itemProps.putObject("agent_count");
        subAgentCount.put("type", "integer");
        subAgentCount.put("description", "Number of agent instances for THIS subtask. Overrides the top-level agent_count. Default: inherits top-level agent_count.");
        subAgentCount.put("minimum", 1);
        subAgentCount.put("maximum", 5);

        items.putArray("required").add("name").add("prompt");

        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional default role for subtasks. Roles may define per-agent model and model-specific thinking defaults.");

        var model = props.putObject("model");
        model.put("type", "string");
        model.put("description", "Optional default model override for subtasks that do not specify their own model. It overrides role/project/user defaults.");

        var thinking = props.putObject("thinking");
        thinking.put("type", "string");
        thinking.put("description", "Optional default thinking/effort override for subtasks that do not specify their own. It overrides role/project/user defaults.");

        var agentCount = props.putObject("agent_count");
        agentCount.put("type", "integer");
        agentCount.put("description", "Default number of agent instances to spawn per subtask. Each instance runs the same prompt independently. Default: 1");
        agentCount.put("default", 1);
        agentCount.put("minimum", 1);
        agentCount.put("maximum", 5);

        schema.putArray("required").add("description").add("subtasks");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        return execute(arguments, null);
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        if (context != null && context.isAborted()) return ToolResult.error("Multi-task cancelled");
        String desc = (String) arguments.getOrDefault("description", "");
        Object subtasksObj = arguments.get("subtasks");
        String defaultRole = (String) arguments.get("role");
        String defaultModel = (String) arguments.get("model");
        String defaultThinking = (String) arguments.get("thinking");

        int defaultAgentCount = 1;
        Object agentCountObj = arguments.get("agent_count");
        if (agentCountObj instanceof Number) {
            defaultAgentCount = Math.max(1, Math.min(5, ((Number) agentCountObj).intValue()));
        }

        if (subtasksObj == null || !(subtasksObj instanceof List)) {
            return ToolResult.error("subtasks must be an array");
        }

        List<Map<String, Object>> subtasks;
        try {
            subtasks = (List<Map<String, Object>>) subtasksObj;
        } catch (ClassCastException e) {
            return ToolResult.error("subtasks must be an array of objects with name/prompt/agent fields");
        }

        if (subtasks.size() < 2) {
            return ToolResult.error("At least 2 subtasks are required. Use 'task' for a single delegation.");
        }
        for (Map<String, Object> subtask : subtasks) {
            for (String agentType : resolveAgentTypes(subtask)) {
                if (!StdioTaskTool.isSupportedAgent(agentType)) {
                    return ToolResult.error("Agent '" + agentType
                        + "' is not available. Available agents: " + String.join(", ", SUPPORTED_AGENTS) + ".");
                }
            }
        }

        // ── Workflow team enforcement: validate the WHOLE batch before any launch ──
        WorkflowTeamEnforcement workflow;
        try {
            workflow = StdioTaskTool.workflowEnforcement(workDir, roleManager);
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        }
        // The launch the workflow assigned each subtask; null outside a workflow. Workflow
        // subtasks launch from this, never from the selectors in their arguments.
        List<StdioTaskTool.ParticipantLaunch> launches = new ArrayList<>(Collections.nCopies(subtasks.size(), null));
        if (workflow != null) {
            WorkflowTeamEnforcement.ToolDecision toolDecision = workflow.evaluateToolUse("multi_task");
            if (!toolDecision.allowed()) {
                return ToolResult.error(toolDecision.reason());
            }
            List<String> batchErrors = new ArrayList<>();
            int batchInstances = 0;
            for (int i = 0; i < subtasks.size(); i++) {
                Map<String, Object> subtask = subtasks.get(i);
                String label = "subtask[" + i + "] '" + subtask.getOrDefault("name", "?") + "': ";
                WorkflowTeamEnforcement.DelegationDecision decision = workflow.evaluateDelegation(
                        (String) subtask.get("purpose"), (String) subtask.getOrDefault("role", defaultRole));
                if (decision instanceof WorkflowTeamEnforcement.DelegationDecision.Denied denied) {
                    batchErrors.add(label + denied.reason());
                    continue;
                }
                Set<String> requestedAgents = requestedAgents(subtask);
                if (requestedAgents.size() > 1) {
                    batchErrors.add(label + "a workflow subtask runs as one participant; give at most one agent"
                            + " (requested " + String.join(", ", requestedAgents) + ").");
                    continue;
                }
                try {
                    launches.set(i, StdioTaskTool.participantLaunch(workflow.team(),
                            (WorkflowTeamEnforcement.DelegationDecision.Allowed) decision,
                            requestedAgents.isEmpty() ? null : requestedAgents.iterator().next(),
                            resolveModel(subtask, defaultModel), resolveThinking(subtask, defaultThinking)));
                } catch (IllegalArgumentException e) {
                    batchErrors.add(label + e.getMessage());
                    continue;
                }
                batchInstances += resolveAgentCount(subtask, defaultAgentCount);
            }
            // The concurrent-worker limit covers every instance the batch runs at once.
            if (batchErrors.isEmpty() && workflow.evaluateBatchSize(batchInstances)
                    instanceof WorkflowTeamEnforcement.DelegationDecision.Denied deniedBatch) {
                batchErrors.add(deniedBatch.reason()
                        + " Send fewer subtasks per batch, or delegate one at a time with task.");
            }
            if (!batchErrors.isEmpty()) {
                // One invalid assignment rejects the entire batch; nobody launches.
                return ToolResult.error("Workflow '" + workflow.team().name()
                        + "' rejected this batch before launch:\n- "
                        + String.join("\n- ", batchErrors));
            }
        }
        List<SubtaskLaunch> plans = new ArrayList<>();
        for (int i = 0; i < subtasks.size(); i++) {
            Map<String, Object> st = subtasks.get(i);
            StdioTaskTool.ParticipantLaunch launch = launches.get(i);
            int count = resolveAgentCount(st, defaultAgentCount);
            plans.add(launch != null
                    ? new SubtaskLaunch(List.of(launch.agent()), count, launch.role(), launch.model(),
                            launch.thinking(), launch.participant())
                    : new SubtaskLaunch(resolveAgentTypes(st), count, (String) st.getOrDefault("role", defaultRole),
                            resolveModel(st, defaultModel), resolveThinking(st, defaultThinking), null));
        }

        System.err.println("\u001B[32m  ⟳ Multi-task: " + desc + " (" + subtasks.size() + " subtasks)\u001B[0m");
        for (int i = 0; i < subtasks.size(); i++) {
            Map<String, Object> st = subtasks.get(i);
            SubtaskLaunch plan = plans.get(i);
            String name = (String) st.getOrDefault("name", "subtask-" + i);
            int taskAgentCount = plan.count();
            String agentLabel = (plan.participant() != null ? plan.participant() + " as " : "")
                    + String.join(", ", plan.agents());
            String countSuffix = taskAgentCount > 1 ? " x" + taskAgentCount : "";
            String model = plan.model();
            String thinking = plan.thinking();
            String role = plan.role();
            System.err.println("\u001B[2m    [" + (i + 1) + "] " + name + " → " + agentLabel + countSuffix
                + " (model: " + displayModel(model) + ", thinking: " + displayThinking(thinking)
                + ", role: " + displayRole(role) + ")\u001B[0m");
        }
        System.err.flush();

        // Calculate total instances across all subtasks (agent types × instances per type)
        int totalInstances = 0;
        for (SubtaskLaunch plan : plans) {
            totalInstances += plan.agents().size() * plan.count();
        }
        ExecutorService executor = Executors.newFixedThreadPool(totalInstances);
        List<SubtaskFuture> futures = new ArrayList<>();

        try {
        for (int i = 0; i < subtasks.size(); i++) {
            Map<String, Object> st = subtasks.get(i);
            SubtaskLaunch plan = plans.get(i);
            String name = (String) st.getOrDefault("name", "subtask-" + i);
            String prompt = (String) st.getOrDefault("prompt", "");
            String model = plan.model();
            String thinking = plan.thinking();
            List<String> agentTypes = plan.agents();
            int taskAgentCount = plan.count();

            if (prompt.isEmpty()) {
                futures.add(new SubtaskFuture(name, agentTypes.get(0), model, thinking, plan.participant(), 1, 0,
                    CompletableFuture.completedFuture(SubtaskResult.failed("(empty prompt)"))));
                continue;
            }

            final String fName = name;
            final String fPrompt = prompt;
            int totalPerSubtask = agentTypes.size() * taskAgentCount;

            for (String agentType : agentTypes) {
                for (int instanceIdx = 0; instanceIdx < taskAgentCount; instanceIdx++) {
                    final String fAgent = agentType;
                    // Build a descriptive instance name: subtask#agent or subtask#agent#N
                    String instanceName;
                    if (agentTypes.size() == 1 && taskAgentCount == 1) {
                        instanceName = name;
                    } else if (agentTypes.size() > 1 && taskAgentCount == 1) {
                        instanceName = name + "/" + agentType;
                    } else if (agentTypes.size() == 1) {
                        instanceName = name + "#" + (instanceIdx + 1);
                    } else {
                        instanceName = name + "/" + agentType + "#" + (instanceIdx + 1);
                    }
                    futures.add(new SubtaskFuture(instanceName, agentType, model, thinking, plan.participant(),
                        totalPerSubtask, instanceIdx,
                        CompletableFuture.supplyAsync(() -> runSubtask(fName, fPrompt, fAgent, plan.role(),
                                model, thinking, plan.participant(), context), executor)));
                }
            }
        }

        // Collect results
        StringBuilder fullOutput = new StringBuilder();
        fullOutput.append("# Multi-Task Results: ").append(desc).append("\n\n");
        fullOutput.append("**Subtasks:** ").append(subtasks.size()).append("\n\n");

        int succeeded = 0;
        int timedOut = 0;
        int failed = 0;
        StringBuilder summaryOutput = new StringBuilder();

        for (SubtaskFuture sf : futures) {
            fullOutput.append("---\n\n");
            fullOutput.append("## ").append(sf.name).append(" (").append(launchLabel(sf)).append(")\n\n");

            try {
                SubtaskResult result = sf.future.get(10, TimeUnit.MINUTES);
                switch (result.outcome) {
                    case COMPLETED -> {
                        succeeded++;
                        fullOutput.append(result.output).append("\n\n");
                        summaryOutput.append("- **").append(sf.name).append("** (").append(launchLabel(sf))
                            .append("): ")
                            .append(truncateForSummary(result.output, 200)).append("\n");
                    }
                    case TIMED_OUT -> {
                        // TIMED_OUT is NOT success — the agent did not complete its work.
                        timedOut++;
                        fullOutput.append("**TIMED OUT** (partial output below, task was NOT completed):\n\n");
                        fullOutput.append(result.output).append("\n\n");
                        summaryOutput.append("- **").append(sf.name).append("** (").append(sf.agent)
                            .append("): TIMED OUT — partial: ")
                            .append(truncateForSummary(result.output, 100)).append("\n");
                    }
                    case FAILED -> {
                        failed++;
                        fullOutput.append("**Failed:** ").append(result.output).append("\n\n");
                        summaryOutput.append("- **").append(sf.name).append("** (").append(sf.agent).append("): FAILED — ")
                            .append(truncateForSummary(result.output, 100)).append("\n");
                    }
                }
            } catch (TimeoutException e) {
                // The outer 10-min future timed out — the agent process itself is still running.
                timedOut++;
                fullOutput.append("**Timed out** after 10 minutes (outer gate).\n\n");
                summaryOutput.append("- **").append(sf.name).append("** (").append(sf.agent).append("): TIMED OUT (outer 10m)\n");
            } catch (Exception e) {
                failed++;
                fullOutput.append("**Error:** ").append(e.getMessage()).append("\n\n");
                summaryOutput.append("- **").append(sf.name).append("** (").append(sf.agent).append("): ERROR — ")
                    .append(e.getMessage()).append("\n");
            }
        }

        if (context != null && context.isAborted()) return ToolResult.error("Multi-task cancelled");

        int incomplete = timedOut + failed;
        fullOutput.append("---\n\n");
        fullOutput.append("**Summary:** ").append(succeeded).append("/").append(subtasks.size())
              .append(" subtasks completed successfully");
        if (timedOut > 0) {
            fullOutput.append("; ").append(timedOut).append(" timed out (INCOMPLETE)");
        }
        if (failed > 0) {
            fullOutput.append("; ").append(failed).append(" failed");
        }
        fullOutput.append(".");

        System.err.println("\u001B[32m  \u2713 Multi-task complete: " + succeeded + "/" + subtasks.size() + " succeeded"
            + (timedOut > 0 ? ", " + timedOut + " timed out" : "")
            + (failed > 0 ? ", " + failed + " failed" : "") + "\u001B[0m");
        System.err.flush();

        // Write full output to file
        Path resultFile = writeResultToFile(desc, fullOutput.toString());

        // Build concise tool result
        StringBuilder toolResult = new StringBuilder();
        toolResult.append("## Multi-Task Results: ").append(desc).append("\n\n");
        toolResult.append(succeeded).append("/").append(subtasks.size())
                  .append(" subtasks completed successfully");
        if (timedOut > 0) toolResult.append("; ").append(timedOut).append(" timed out");
        if (failed > 0) toolResult.append("; ").append(failed).append(" failed");
        toolResult.append(".\n\n");
        toolResult.append(summaryOutput).append("\n");
        toolResult.append("**Full output written to:** `").append(resultFile.toAbsolutePath()).append("`\n");
        toolResult.append("Use the `read` tool to access the full results for any subtask.");

        List<String> succeededNames = new ArrayList<>();
        for (SubtaskFuture sf : futures) {
            try {
                if (sf.future.isDone() && sf.future.get().isSuccess()) {
                    succeededNames.add(sf.name);
                }
            } catch (Exception ignored) {}
        }

        return ToolResult.success("multi_task", toolResult.toString(),
            Map.of("description", desc,
                   "totalSubtasks", subtasks.size(),
                   "succeeded", succeeded,
                   "timedOut", timedOut,
                   "failed", failed,
                   "resultFile", resultFile.toAbsolutePath().toString(),
                   "succeededSubtasks", String.join(",", succeededNames)));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Resolve the list of agent types for a subtask.
     * If 'agents' (array) is provided, use that. Otherwise fall back to 'agent' (string, default
     * "codex").
     */
    @SuppressWarnings("unchecked")
    private static List<String> resolveAgentTypes(Map<String, Object> subtask) {
        Object agentsObj = subtask.get("agents");
        if (agentsObj instanceof List) {
            List<String> agents = (List<String>) agentsObj;
            if (!agents.isEmpty()) {
                return agents;
            }
        }
        return List.of((String) subtask.getOrDefault("agent", DEFAULT_AGENT));
    }

    /**
     * Resolve agent_count for a subtask (per-subtask overrides top-level default).
     */
    private static int resolveAgentCount(Map<String, Object> subtask, int defaultCount) {
        Object obj = subtask.get("agent_count");
        if (obj instanceof Number) {
            return Math.max(1, Math.min(5, ((Number) obj).intValue()));
        }
        return defaultCount;
    }

    private static String resolveModel(Map<String, Object> subtask, String defaultModel) {
        Object value = subtask.get("model");
        return value instanceof String && !((String) value).isBlank() ? (String) value : defaultModel;
    }

    private static String resolveThinking(Map<String, Object> subtask, String defaultThinking) {
        Object value = subtask.get("thinking");
        return value instanceof String && !((String) value).isBlank() ? (String) value : defaultThinking;
    }

    private static String displayModel(String model) {
        return model == null || model.isBlank() ? "configured default" : model;
    }

    private static String displayThinking(String thinking) {
        return thinking == null || thinking.isBlank() ? "configured default" : thinking;
    }

    private static String displayRole(String role) {
        return role == null || role.isBlank() ? "none" : role;
    }

    /** The launch a result reports; the workflow participant leads when there is one. */
    private static String launchLabel(SubtaskFuture sf) {
        return (sf.participant != null ? "participant: " + sf.participant + ", " : "")
                + "agent: " + sf.agent + ", model: " + displayModel(sf.model)
                + ", thinking: " + displayThinking(sf.thinking);
    }

    /** The agents a subtask names, lowercased, with {@link #resolveAgentTypes}'s precedence; empty when none. */
    private static Set<String> requestedAgents(Map<String, Object> subtask) {
        Set<String> agents = new LinkedHashSet<>();
        if (subtask.get("agents") instanceof List<?> list && !list.isEmpty()) {
            list.forEach(agent -> agents.add(String.valueOf(agent).trim().toLowerCase(Locale.ROOT)));
        } else if (subtask.get("agent") instanceof String agent && !agent.isBlank()) {
            agents.add(agent.trim().toLowerCase(Locale.ROOT));
        }
        return agents;
    }

    @SuppressWarnings("unchecked")
    public static String dispatchPlan(Map<String, Object> arguments) {
        Object subtasksObj = arguments.get("subtasks");
        if (!(subtasksObj instanceof List<?> rawSubtasks)) return "";

        int defaultCount = 1;
        Object countObj = arguments.get("agent_count");
        if (countObj instanceof Number) {
            defaultCount = Math.max(1, Math.min(5, ((Number) countObj).intValue()));
        }
        String defaultModel = (String) arguments.get("model");
        String defaultThinking = (String) arguments.get("thinking");
        String defaultRole = (String) arguments.get("role");
        // In a workflow the team, not these arguments, picks each subtask's participant and launch.
        String workflowName = WorkflowSessionContext.inheritableEnvironment()
                .get(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME);

        StringBuilder plan = new StringBuilder("**Dispatch plan**\n");
        int totalInstances = 0;
        for (int i = 0; i < rawSubtasks.size(); i++) {
            if (!(rawSubtasks.get(i) instanceof Map<?, ?> rawSubtask)) continue;
            Map<String, Object> subtask = (Map<String, Object>) rawSubtask;
            String name = (String) subtask.getOrDefault("name", "subtask-" + i);
            int count = resolveAgentCount(subtask, defaultCount);
            String role = (String) subtask.getOrDefault("role", defaultRole);
            if (workflowName != null) {
                totalInstances += count;
                plan.append("- **").append(name).append("**: purpose=")
                    .append(displayRole((String) subtask.get("purpose")))
                    .append(", role=").append(displayRole(role));
                if (count > 1) plan.append(" x").append(count);
                plan.append("\n");
                continue;
            }
            List<String> agents = resolveAgentTypes(subtask);
            totalInstances += agents.size() * count;
            String model = resolveModel(subtask, defaultModel);
            String thinking = resolveThinking(subtask, defaultThinking);
            plan.append("- **").append(name).append("**: agent=")
                .append(String.join(",", agents));
            if (count > 1) plan.append(" x").append(count);
            plan.append(", model=").append(displayModel(model))
                .append(", thinking=").append(displayThinking(thinking))
                .append(", role=").append(displayRole(role)).append("\n");
        }
        if (workflowName != null) {
            plan.append("- **Workflow** '").append(workflowName)
                .append("' picks each subtask's participant, CLI agent, and model; the results name them\n");
        }
        plan.append("- **Total agent instances**: ").append(totalInstances).append("\n");
        return plan.toString();
    }

    private SubtaskResult runSubtask(String name, String prompt, String requestedAgent, String roleName,
                                       String model, String thinking, String participant, ToolContext context) {
        if (context != null && context.isAborted()) return SubtaskResult.failed("Task cancelled");
        // A single-provider dispatch surfaces provider failures instead of silently cascading.
        List<String> agentsToTry = List.of(requestedAgent);

        for (String agentName : agentsToTry) {
            try {
                AgentConfig agentConfig = AgentConfig.builder(agentName)
                    .displayName(agentName.substring(0, 1).toUpperCase() + agentName.substring(1))
                    .description("Subtask: " + name)
                    .systemPrompt(prompt).isSubagent(true).canSpawnSubagents(false)
                    .roleName(roleName)
                    .modelOverride(model)
                    .thinkingOverride(thinking)
                    .workflowParticipant(participant)
                    .build();

                String result = StdioTaskTool.runWithCancellation(
                        subagentRunner.forkForSubagent(), agentConfig, prompt, context);
                if (StdioTaskTool.isAgentMissing(result)) {
                    continue;
                }
                return SubtaskResult.completed(result);
            } catch (RateLimitException e) {
                System.err.println("\u001B[33m    \u26a0 " + name
                    + ": " + agentName + " rate limited; provider fallback is disabled.\u001B[0m");
                return SubtaskResult.failed(agentName + " rate limited for subtask '" + name + "'");
            } catch (Exception e) {
                return SubtaskResult.failed(e.getMessage());
            }
        }

        return SubtaskResult.failed("All agents unavailable for subtask '" + name + "'");
    }

    private Path writeResultToFile(String desc, String fullOutput) {
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String sanitizedDesc = desc.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (sanitizedDesc.length() > 40) sanitizedDesc = sanitizedDesc.substring(0, 40);
            Path resultFile = resultsDir.resolve("multi-" + sanitizedDesc + "-" + timestamp + ".md");

            Files.writeString(resultFile, fullOutput, StandardCharsets.UTF_8);
            System.err.println("\033[2m  Full multi-task output written to: " + resultFile + "\033[0m");
            System.err.flush();
            return resultFile;
        } catch (IOException e) {
            System.err.println("\033[31m  Warning: Could not write result file: " + e.getMessage() + "\033[0m");
            try {
                Path tempFile = Files.createTempFile("kompile-multi-", ".md");
                Files.writeString(tempFile, fullOutput, StandardCharsets.UTF_8);
                return tempFile;
            } catch (IOException e2) {
                return workDir.resolve(".kompile-multi-result-unavailable.md");
            }
        }
    }

    private static String truncateForSummary(String text, int maxChars) {
        if (text == null || text.isEmpty()) return "(empty)";
        String firstLine = text.split("\n", 2)[0].trim();
        if (firstLine.length() <= maxChars) return firstLine;
        return firstLine.substring(0, maxChars) + "...";
    }

    /**
     * Outcome codes for a completed subtask.
     * <p>
     * <b>COMPLETED</b> -- the managed agent turn returned normally. This is the only
     * outcome counted as "succeeded".<br>
     * <b>TIMED_OUT</b> -- a managed turn timed out before completion. The {@code output}
     * field holds whatever partial text was captured up to that point. This MUST NOT
     * be counted as success; it means the subtask ran out of time.<br>
     * <b>FAILED</b> -- a communication error, process crash, or explicit error return.
     */
    enum SubtaskOutcome { COMPLETED, TIMED_OUT, FAILED }

    private static class SubtaskResult {
        final SubtaskOutcome outcome;
        final String output;

        SubtaskResult(SubtaskOutcome outcome, String output) {
            this.outcome = outcome;
            this.output = output;
        }

        /** Convenience: true only for COMPLETED. */
        boolean isSuccess() { return outcome == SubtaskOutcome.COMPLETED; }

        /** Convenience: true for TIMED_OUT. */
        boolean isTimedOut() { return outcome == SubtaskOutcome.TIMED_OUT; }

        static SubtaskResult completed(String output) {
            return new SubtaskResult(SubtaskOutcome.COMPLETED, output);
        }

        static SubtaskResult timedOut(String partialOutput) {
            return new SubtaskResult(SubtaskOutcome.TIMED_OUT, partialOutput);
        }

        static SubtaskResult failed(String reason) {
            return new SubtaskResult(SubtaskOutcome.FAILED, reason);
        }
    }

    /** What one subtask launches: each agent {@code count} times, on this role, model, and thinking. */
    private record SubtaskLaunch(List<String> agents, int count, String role, String model, String thinking,
                                 String participant) {}

    private static class SubtaskFuture {
        final String name;
        final String agent;
        final String model;
        final String thinking;
        final String participant;
        final int totalInstances;
        final int instanceIndex;
        final Future<SubtaskResult> future;

        SubtaskFuture(String name, String agent, String model, String thinking, String participant,
                      int totalInstances, int instanceIndex, Future<SubtaskResult> future) {
            this.name = name;
            this.agent = agent;
            this.model = model;
            this.thinking = thinking;
            this.participant = participant;
            this.totalInstances = totalInstances;
            this.instanceIndex = instanceIndex;
            this.future = future;
        }
    }
}
