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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement.DelegationDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spawn a subagent to handle a delegated task. Comparable to Claude Code's Agent tool
 * and OpenCode/Codex task delegation.
 *
 * The subagent runs in a child session with its own context window and tool access,
 * then returns its final response as the tool result. Subagents cannot spawn
 * additional subagents (no recursive delegation).
 *
 * Available subagent types include built-in agents (explore-quick, explore-deep,
 * general, code-reviewer, architect, researcher) and any custom agents loaded
 * from .kompile/agents/ or ~/.kompile/agents/ directories.
 */
public class TaskTool implements CliTool {

    private final AgentRegistry agentRegistry;
    private final SubagentRunner subagentRunner;
    private final RoleManager roleManager;

    public TaskTool(AgentRegistry agentRegistry, SubagentRunner subagentRunner) {
        this(agentRegistry, subagentRunner, null);
    }

    public TaskTool(AgentRegistry agentRegistry, SubagentRunner subagentRunner, RoleManager roleManager) {
        if (subagentRunner == null) {
            throw new IllegalArgumentException("SubagentRunner must not be null. " +
                    "TaskTool requires a properly configured subagent runner for delegation.");
        }
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.roleManager = roleManager;
    }

    /**
     * Validate that this TaskTool is properly configured and ready for use.
     *
     * @return true if properly configured, false otherwise
     */
    public boolean isHealthy() {
        return subagentRunner != null && agentRegistry != null;
    }

    /**
     * Get the list of available subagent types.
     *
     * @return list of subagent configs
     */
    public List<AgentConfig> getAvailableSubagents() {
        return agentRegistry.getSubagents();
    }

    @Override
    public String id() { return "task"; }

    @Override
    public String description() {
        StringBuilder desc = new StringBuilder();
        desc.append("Delegate a task to a specialized subagent. The subagent runs autonomously ")
                .append("with its own context window and tools, then returns a result. Use this for:\n")
                .append("- Codebase exploration and research (use explore-quick or explore-deep)\n")
                .append("- Code review of changes (use code-reviewer)\n")
                .append("- Architecture analysis and planning (use architect)\n")
                .append("- Web research and documentation lookup (use researcher)\n")
                .append("- Complex multi-step tasks requiring full tool access (use general)\n\n")
                .append("Available subagent types:\n");

        for (AgentConfig agent : agentRegistry.getSubagents()) {
            String agentDesc = agent.getDescription() != null && !agent.getDescription().isEmpty()
                    ? agent.getDescription() : agent.getDisplayName();
            desc.append("- '").append(agent.getName()).append("': ").append(agentDesc);
            if ("fast".equals(agent.getModelHint())) {
                desc.append(" [fast]");
            }
            if (agent.isCustom()) {
                desc.append(" [custom]");
            }
            desc.append("\n");
        }

        desc.append("\nKompile-managed child: model auto-routes; provider explicitly corrects the route. ")
                .append("authentication_method=oauth/native uses Claude Code login for anthropic; api-key selects its separate API. ")
                .append("Same-provider auth is inherited; resolve_model discovers routes and models. No parent/global settings change. ")
                .append("Use role instead of agent_type to select a named role's prompt and tool policy. ")
                .append("CLI agent selection and per-CLI role defaults belong to the MCP task tool, not this direct chat tool.\n")
                .append("Inside an active workflow team, pass purpose (or a team role): the team decides which ")
                .append("participant handles the task, with that participant's role and model, and refuses ")
                .append("delegations its edges or gates do not allow.\n")
                .append("In standard chat, Ctrl+B backgrounds a running subagent invocation. ")
                .append("Select its activity row and press Delete to stop it, or open it and type ")
                .append("to continue its retained conversation.\n")
                .append("Task invocations background by default (the agent-initiated equivalent of Ctrl+B): ")
                .append("the parent continues immediately with a placeholder and ")
                .append("the final result is delivered as a system message on completion, so several ")
                .append("independent delegations can run concurrently. Set background=false to wait for the result. ")
                .append("Silently falls back to synchronous ")
                .append("execution when the running harness cannot background (headless runs, subagent child contexts).");
        return desc.toString();
    }

    @Override
    public String compactHint() {
        return "Kompile child: role/agent_type, provider/model/authentication_method. Anthropic oauth=Claude login; api-key=HTTP API. resolve_model discovers routes. Not the vendor Agent tool.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "A short (3-5 word) description of the delegated task");

        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "Detailed task description for the subagent. Include all " +
                "necessary context — the subagent has no access to the parent conversation.");

        // Build enum from registered subagents
        List<AgentConfig> subagents = agentRegistry.getSubagents();
        props.putObject("provider").put("type", "string").put("description",
                "Request-local provider override; use resolve_model to correct an ambiguous route.");
        props.putObject("authentication_method").put("type", "string").put("description",
                "Auth route: oauth/native uses Claude Code login for anthropic; api-key uses the separate HTTP API. Same-provider omission inherits auth; no fallback after 401.")
                .putArray("enum").add("oauth").add("native").add("api-key");
        ObjectNode agentType = props.putObject("agent_type");
        agentType.put("type", "string");

        StringBuilder agentDesc = new StringBuilder("Subagent type. ");
        agentDesc.append("Use 'explore-quick' for fast file/code lookups. ");
        agentDesc.append("Use 'explore-deep' for thorough codebase analysis. ");
        agentDesc.append("Use 'code-reviewer' for reviewing changes. ");
        agentDesc.append("Use 'architect' for design and planning. ");
        agentDesc.append("Use 'researcher' for web search and documentation. ");
        agentDesc.append("Use 'general' for full-access multi-step tasks. ");
        agentDesc.append("Default: 'explore-quick'");
        agentType.put("description", agentDesc.toString());

        ArrayNode enumValues = agentType.putArray("enum");
        for (AgentConfig a : subagents) {
            enumValues.add(a.getName());
        }

        props.putObject("model").put("type", "string").put("description",
                "Request-scoped model id; auto-routes when needed. provider/authentication_method correct a route; resolve_model lists options. Never changes global defaults.");
        props.putObject("thinking").put("type", "string").put("description",
                "Request-scoped provider-native reasoning effort (e.g. xhigh). Direct-model chat only; omit to inherit.");
        props.putObject("role").put("type", "string").put("description",
                "Named role's prompt and tool policy, instead of agent_type. Does not apply CLI-agent launch defaults. "
                        + "Recursive delegation remains disabled.");
        props.putObject("purpose").put("type", "string").put("description",
                "Workflow routing purpose (active workflow teams only): which participant should handle this task.");
        props.putObject("background").put("type", "boolean").put("default", true).put("description",
                "Background by default so the parent continues immediately; the final result "
                        + "arrives as a system message on completion. Set false to wait for the result. "
                        + "Ignored with a synchronous fallback when the harness cannot background. Default: true.");
        ObjectNode resumable = props.putObject("resumable");
        resumable.put("type", "boolean").put("default", false).put("description",
                "Opt in to persisting this subagent's conversation so it can be named, listed, "
                        + "and resumed later (this chat, /subagents, or the subagent_resume MCP tool). "
                        + "Default false: nothing is persisted.");
        props.putObject("name").put("type", "string").put("description",
                "Unique short slug for a resumable run (lowercase letters, digits, '-' and '_'). "
                        + "Implies resumable=true. Later turns address the run by this name.");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    @Override
    public String permissionKey() { return "task"; }

    @Override
    public boolean isBackgroundable() { return true; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Spawn subagent");

        String desc = params.path("description").asText("");
        String prompt = params.path("prompt").asText("");
        String agentType = params.path("agent_type").asText("explore-quick");

        if (prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        if (params.hasNonNull("agent")) {
            return ToolResult.error("Chat task uses agent_type/role for a Kompile-managed child, with provider/authentication_method for routing; CLI agent selection requires the MCP task tool.");
        }
        for (String selector : List.of("model", "thinking", "provider", "authentication_method", "role", "purpose", "name")) {
            if (params.hasNonNull(selector) && !params.get(selector).isTextual()) {
                return ToolResult.error(selector + " must be a string");
            }
        }
        String model = params.path("model").asText("").trim();
        String thinking = params.path("thinking").asText("").trim();
        String provider = params.path("provider").asText("").trim();
        String auth = params.path("authentication_method").asText("").trim();
        String roleName = params.path("role").asText("").trim();
        String name = params.path("name").asText("").trim();
        boolean resumableRequested = params.path("resumable").asBoolean(false) || !name.isEmpty();
        if (!name.isEmpty()) {
            String nameError = ai.kompile.cli.main.chat.agent.SubagentResumeMetadata.nameError(name);
            if (nameError != null) return ToolResult.error("name: " + nameError);
        }

        // Workflow team: the team decides who handles the task. Its purposes,
        // delegation edges, and gates are checked before any launch, and the
        // resolved participant's role and model are what the child runs.
        WorkflowTeamEnforcement workflow = WorkflowSessionContext.enforcementFor(context);
        WorkflowTeam.Participant participant = null;
        WorkflowTeam.ModelBinding binding = null;
        if (workflow != null) {
            if (!provider.isEmpty() || !auth.isEmpty()) {
                return ToolResult.error("The workflow owns provider and authentication_method; change its participant binding instead.");
            }
            DelegationDecision decision = workflow.evaluateDelegation(
                    params.path("purpose").asText("").trim(), roleName);
            if (decision instanceof DelegationDecision.Denied denied) {
                return ToolResult.error(denied.reason());
            }
            participant = workflow.team().participant(decision.resolvedParticipant());
            String conflict = bindingConflict(workflow.team(), participant, model, thinking);
            if (conflict != null) return ToolResult.error(conflict);
            String resolvedRole = ((DelegationDecision.Allowed) decision).resolvedRole();
            roleName = resolvedRole != null ? resolvedRole : participant.role();
            binding = participant.model();
            if (binding != null) {
                model = binding.model();
                if (thinking.isEmpty() && binding.thinking() != null) thinking = binding.thinking();
            }
        }

        AgentConfig subagentConfig;
        if (participant != null || !roleName.isEmpty()) {
            if (participant == null && params.hasNonNull("agent_type")) {
                return ToolResult.error("Specify either role or agent_type, not both.");
            }
            RoleConfig role = roleManager != null ? roleManager.getRole(roleName) : agentRegistry.getRole(roleName);
            if (role == null) return ToolResult.error("Unknown role: " + roleName);
            AgentConfig.Builder fromRole = role.toAgentConfig().toBuilder()
                    .isSubagent(true).canSpawnSubagents(false).roleName(roleName);
            if (participant != null) fromRole.workflowParticipant(participant.id());
            subagentConfig = fromRole.build();
            agentType = roleName;
        } else {
            subagentConfig = agentRegistry.get(agentType);
            if (subagentConfig == null || !subagentConfig.isSubagent()) {
                String available = agentRegistry.getSubagents().stream()
                        .map(AgentConfig::getName)
                        .collect(Collectors.joining(", "));
                return ToolResult.error("Unknown subagent type: " + agentType + ". Available: " + available);
            }
        }
        if (!model.isEmpty() && !subagentConfig.isModelAllowed(model)) {
            return ToolResult.error("Model is not allowed for " + agentType + ": " + model
                    + (binding != null ? ". " + rebindHint(participant) : ""));
        }
        AgentConfig.Builder child = subagentConfig.toBuilder().canSpawnSubagents(false);
        if (!model.isEmpty()) child.modelOverride(model);
        if (!thinking.isEmpty()) child.thinkingOverride(thinking);
        if (!provider.isEmpty()) child.providerOverride(provider);
        if (!auth.isEmpty()) child.authenticationMethodOverride(auth);
        subagentConfig = child.build();

        ai.kompile.cli.main.chat.agent.SubagentRunOptions runOptions =
                ai.kompile.cli.main.chat.agent.SubagentRunOptions.DEFAULT;
        if (resumableRequested) {
            ai.kompile.cli.main.chat.agent.SubagentResumeService resumeService =
                    new ai.kompile.cli.main.chat.agent.SubagentResumeService(
                            ai.kompile.cli.common.util.JsonUtils.standardMapper());
            if (!name.isEmpty()) {
                String conflict = resumeService.nameConflict(name);
                if (conflict != null) return ToolResult.error(conflict);
            }
            runOptions = ai.kompile.cli.main.chat.agent.SubagentRunOptions.resumable(
                    name.isEmpty() ? null : name);
        }

        if (participant != null) {
            context.emitOutput("  [Delegating to " + participant.id() + " (" + roleName
                    + (binding != null ? " on " + binding.label() : "") + "): " + desc + "]");
        } else {
            context.emitOutput("  [Spawning " + agentType + " subagent: " + desc + "]");
        }

        boolean requestedBackground = params.path("background").asBoolean(true);
        boolean backgrounded = false;
        if (requestedBackground) {
            backgrounded = context.requestSelfBackground();
            if (backgrounded) {
                context.emitOutput("  [Subagent '" + desc + "' detached to background; "
                        + "its final result will be delivered as a system message]");
            }
            // If the request was rejected (no harness hook, headless run, child
            // context, or the transfer could not be accepted) fall through to the
            // normal synchronous path instead of failing the delegation.
        }

        try {
            String result = runChild(subagentConfig, prompt, context, runOptions);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agentType", agentType);
            metadata.put("description", desc);
            metadata.put("backgroundRequested", requestedBackground);
            metadata.put("backgrounded", backgrounded);
            if (runOptions.effectiveResumable()) metadata.put("resumable", true);
            if (!name.isEmpty()) metadata.put("name", name);
            if (subagentConfig.getModelOverride() != null) metadata.put("model", subagentConfig.getModelOverride());
            if (subagentConfig.getThinkingOverride() != null) metadata.put("thinking", subagentConfig.getThinkingOverride());
            if (subagentConfig.getProviderOverride() != null) metadata.put("provider", subagentConfig.getProviderOverride());
            if (subagentConfig.getAuthenticationMethodOverride() != null) metadata.put("authentication_method", subagentConfig.getAuthenticationMethodOverride());
            if (!roleName.isEmpty()) metadata.put("role", roleName);
            if (participant != null) {
                metadata.put("workflow", workflow.team().name());
                metadata.put("workflowParticipant", participant.id());
            }
            return ToolResult.success("subagent:" + agentType, result, metadata);
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }

    /** Shared policy boundary for fresh tasks and durable native continuations. */
    protected String runChild(AgentConfig agent, String prompt, ToolContext context,
                              ai.kompile.cli.main.chat.agent.SubagentRunOptions options) throws Exception {
        return subagentRunner.runSubagent(agent, prompt, context, options);
    }

    /**
     * Why a workflow participant cannot run this request here, or {@code null}.
     * A participant bound to a CLI agent only runs when the lead delegates
     * through managed CLI agents; an explicit model or thinking that contradicts
     * the participant's binding is refused rather than silently replaced, so the
     * lead learns which model the team assigned.
     */
    static String bindingConflict(WorkflowTeam team, WorkflowTeam.Participant participant,
                                  String model, String thinking) {
        WorkflowTeam.ModelBinding binding = participant.model();
        if (binding == null) return null;
        String subject = "Workflow '" + team.name() + "' runs participant '" + participant.id() + "'";
        if (binding.agent() != null) {
            return subject + " as the " + binding.agent() + " CLI agent, which this chat cannot launch; "
                    + "CLI agents run when the lead chat is a managed CLI agent (kompile chat --setup). "
                    + rebindHint(participant);
        }
        if (!model.isEmpty() && !model.equals(binding.model())) {
            return subject + " on " + binding.label() + "; omit model (requested " + model + "). "
                    + rebindHint(participant);
        }
        if (!thinking.isEmpty() && binding.thinking() != null && !thinking.equalsIgnoreCase(binding.thinking())) {
            return subject + " with thinking " + binding.thinking() + "; omit thinking (requested "
                    + thinking + "). " + rebindHint(participant);
        }
        return null;
    }

    private static String rebindHint(WorkflowTeam.Participant participant) {
        return "The user can change its model with /workflow model " + participant.id() + ".";
    }
}
