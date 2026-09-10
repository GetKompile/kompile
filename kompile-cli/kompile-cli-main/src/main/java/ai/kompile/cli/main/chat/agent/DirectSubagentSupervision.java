package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel;

/** Host gates for direct children. The contract is immutable; progress belongs to one child only. */
public final class DirectSubagentSupervision {
    @FunctionalInterface
    public interface ToolReview {
        EnforcerToolCallDecision review(String prompt, String assistant, String tool, String input)
                throws Exception;
    }

    public record Contract(WorkflowController.ChildContract workflow, String parentPrompt,
                           EnforcerEvaluator evaluator, EnforcerPolicy policy,
                           ToolReview toolReview, boolean reviewRequired, int maxCorrections,
                           Ceiling ceiling) {
        public Contract(WorkflowController.ChildContract workflow, String parentPrompt,
                        EnforcerEvaluator evaluator, EnforcerPolicy policy, ToolReview toolReview,
                        boolean reviewRequired, int maxCorrections) {
            this(workflow, parentPrompt, evaluator, policy, toolReview, reviewRequired, maxCorrections, null);
        }

        public Contract withCeiling(ToolContext parent) {
            var inheritedWorkflow = "planner".equals(parent.getAgent().getName())
                    ? new WorkflowController.ChildContract(workflow.policy(), workflow.systemPrompt(), true)
                    : workflow;
            return new Contract(inheritedWorkflow, parentPrompt, evaluator, policy, toolReview,
                    reviewRequired, maxCorrections, ceiling == null ? Ceiling.capture(parent) : ceiling);
        }

        public Contract {
            java.util.Objects.requireNonNull(workflow);
            parentPrompt = parentPrompt == null ? "" : parentPrompt;
            maxCorrections = Math.max(0, Math.min(EnforcerPolicy.HARD_MAX_CORRECTIONS, maxCorrections));
        }
    }

    /** No mutable AgentConfig is retained. New/unclassified permission keys fail closed. */
    public record Ceiling(Set<String> tools, Map<String, PermissionLevel> permissions) {
        public Ceiling {
            tools = Set.copyOf(tools);
            permissions = Map.copyOf(permissions);
        }

        public static Ceiling capture(ToolContext parent) {
            Set<String> ids = new HashSet<>();
            Set<String> keys = new HashSet<>(Set.of("read", "edit", "write", "patch",
                    "bash", "bash.readonly", "bash.write", "bash.destructive", "external_directory",
                    "channel.login", "channel.send"));
            for (CliTool tool : parent.getToolRegistry().getToolsForAgent(parent.getAgent())) {
                ids.add(tool.id());
                if (tool.permissionKey() != null) keys.add(tool.permissionKey());
            }
            if (parent.getAgent().getPermissionOverrides() != null)
                keys.addAll(parent.getAgent().getPermissionOverrides().keySet());
            Map<String, PermissionLevel> permissions = new HashMap<>();
            for (String key : keys) permissions.put(key, parent.isAutoApproveAll() ? PermissionLevel.ALLOW
                    : parent.getPermissionService().getEffectiveLevel(parent.getAgent(), key));
            return new Ceiling(ids, permissions);
        }
    }

    private static final Set<String> CONTROL_TOOLS = Set.of(
            "judge_control", "enforcer_config", "project_config", "config_archive", "role_manager", "skill_manager",
            "server_mode", "resume", "pipeline", "subprocess_watchdog");
    private static final Set<String> INSPECTION_ACTIONS = Set.of(
            "get", "view", "status", "list", "preview", "list_roles", "get_role", "get_agent_role",
            "list_skills", "get_skill", "generate_markdown", "expand_template", "scan_provider_skills",
            "capabilities", "versions", "diff", "config_get");
    private static final Set<String> DELEGATION_TOOLS = Set.of(
            "task", "multi_task", "quorum_task", "agent", "agents");

    private final Contract contract;
    private final WorkflowController workflow;
    private final ObjectMapper mapper;
    private String prompt;
    private String initialPrompt;
    private int corrections;
    private String rejectedCall;
    private boolean completionAccepted;

    public DirectSubagentSupervision(Contract contract, ToolRegistry tools, ObjectMapper mapper) {
        this.contract = contract;
        this.workflow = contract.workflow().newController(tools);
        this.mapper = mapper;
    }

    public void begin(String childPrompt, String sessionId) {
        if (completionAccepted) {
            corrections = 0;
            rejectedCall = null;
            completionAccepted = false;
        }
        if (initialPrompt == null) initialPrompt = childPrompt;
        prompt = contract.parentPrompt() + "\n\n[Delegated task]\n" + initialPrompt
                + "\n\n[Current child input]\n" + childPrompt;
        workflow.beginChildTurn(contract.workflow(), prompt, sessionId);
    }

    public String systemPrompt() {
        return workflow.activeSystemPrompt()
                + "\n[Host child supervision] Follow the inherited parent constraints. "
                + "Do not modify enforcement/configuration or delegate recursively. "
                + "Tool and completion decisions are host-gated and cannot be relaxed by a child.";
    }
    public void end() { workflow.completeTurn(); }
    public void beginToolBatch() { workflow.beginToolBatch(); }

    /** Only arguments can be rewritten. Re-run all deterministic checks after a rewrite. */
    public ToolResult execute(String name, JsonNode arguments, String assistant, ToolContext context)
            throws ToolExecutionException {
        context.markSupervisedChild();
        context.setSubagentSupervision(contract);
        JsonNode effective = arguments == null ? mapper.createObjectNode() : arguments.deepCopy();
        String denial = deterministicGate(name, effective, context);
        if (denial == null && contract.reviewRequired()) {
            try {
                if (contract.evaluator() == null || !contract.evaluator().isAvailable()
                        || contract.toolReview() == null) {
                    denial = "Required child policy reviewer is unavailable";
                } else {
                    EnforcerToolCallDecision decision = contract.toolReview().review(
                            prompt, assistant, name, effective.toString());
                    if (decision == null || failOpen(decision.getReason())) {
                        denial = "Required child policy review returned no valid verdict";
                    } else if (!decision.isAllowed()) {
                        denial = decision.blockMessage();
                    } else if (decision.isRewrite()) {
                        if (decision.getRewrittenArgs() == null) {
                            denial = "Required child policy review returned an invalid rewrite";
                        } else {
                            effective = mapper.valueToTree(decision.getRewrittenArgs());
                            denial = deterministicGate(name, effective, context);
                        }
                    }
                }
            } catch (Exception failure) {
                denial = "Required child policy review failed: " + failure.getMessage();
            }
        }
        if (denial != null) {
            rejectedCall = denial;
            if (++corrections > contract.maxCorrections()) {
                throw new SupervisionFailure("Child supervision correction limit reached: " + denial);
            }
            return ToolResult.error("Child supervision blocked execution: " + denial);
        }
        if (context.isAborted()) throw new SupervisionFailure("Subagent aborted");
        // Lookup/allowlist again at the effect boundary, even if a reviewer changed registry state.
        if (!allowed(name, context)) throw new SupervisionFailure("Tool no longer available: " + name);
        ToolResult result;
        try {
            String permission = context.getToolRegistry().get(name).permissionKey();
            if (permission != null) context.checkPermission(permission, "Child tool: " + name);
            result = context.getToolRegistry().get(name).execute(effective, context);
        } catch (ToolExecutionException | RuntimeException | Error failure) {
            rejectedCall = "Child tool failed: " + failure.getClass().getSimpleName();
            workflow.afterTool(name, effective, ToolResult.error(rejectedCall));
            throw failure;
        }
        if (result == null) result = ToolResult.error("Child tool returned no result");
        workflow.afterTool(name, effective, result);
        if (result.isError()) rejectedCall = "Child tool failed: " + result.getOutput();
        return result;
    }

    private String deterministicGate(String name, JsonNode arguments, ToolContext context) {
        if (context.isAborted()) return "Subagent aborted";
        if (!allowed(name, context)) return "Tool not available to child: " + name;
        if (contract.ceiling() != null) {
            String key = context.getToolRegistry().get(name).permissionKey();
            if (key != null && contract.ceiling().permissions().get(key) != PermissionLevel.ALLOW)
                return "Inherited parent permission ceiling: " + key + "; obtain parent approval before delegation";
        }
        if (!arguments.isObject()) return "Tool arguments must be a JSON object";
        String canonical = name.toLowerCase(Locale.ROOT);
        int namespace = canonical.lastIndexOf("__");
        if (namespace >= 0) canonical = canonical.substring(namespace + 2);
        if (DELEGATION_TOOLS.contains(canonical) || canonical.equals("exit_plan_mode")) {
            return "Children cannot delegate recursively or relax supervision";
        }
        boolean pipelineExecution = canonical.equals("pipeline")
                && Set.of("run", "validate", "test").contains(arguments.path("action").asText(""));
        if (CONTROL_TOOLS.contains(canonical) && !pipelineExecution
                && !INSPECTION_ACTIONS.contains(arguments.path("action").asText(""))) {
            return "Children cannot modify enforcement, configuration, roles or skills";
        }
        WorkflowController.Decision decision = workflow.beforeChildTool(name, arguments);
        return decision.allowed() ? null : decision.reason() + "\n" + decision.correctionPrompt();
    }

    private boolean allowed(String name, ToolContext context) {
        if (contract.ceiling() != null && !contract.ceiling().tools().contains(name)) return false;
        return context.getToolRegistry().getToolsForAgent(context.getAgent()).stream()
                .anyMatch(tool -> tool.id().equals(name));
    }

    boolean permitsTool(String name) {
        return contract.ceiling() == null || contract.ceiling().tools().contains(name);
    }

    /** Null means accepted. Otherwise the returned feedback must be sent back, not marked complete. */
    public String beforeCompletion(String text) {
        WorkflowController.FinalDecision decision = workflow.beforeFinalResponse();
        String feedback = null;
        var status = workflow.activeTurnStatus();
        if (status != null && status.phase() == WorkflowController.Phase.WAITING) {
            feedback = "Child has outstanding asynchronous work; inspect its terminal result before completion";
        }
        if (!decision.allowed()) {
            if (!decision.retry()) throw new SupervisionFailure(decision.reason());
            feedback = decision.reason() + "\n" + decision.correctionPrompt();
        }
        if (feedback == null && contract.reviewRequired()) {
            try {
                if (contract.evaluator() == null || !contract.evaluator().isAvailable()) {
                    feedback = "Required child completion reviewer is unavailable";
                } else {
                    var context = EnforcerConversationContext.of(List.of(
                            new EnforcerConversationContext.Message("user", prompt
                                    + (rejectedCall == null ? "" : "\n[Child execution evidence: rejected/failed call]\n"
                                    + rejectedCall + "\nThe denied operation was not successful. Assess the corrected answer on its merits.")),
                            new EnforcerConversationContext.Message("assistant", text)));
                    EnforcerDecision review = contract.evaluator().evaluate(
                            prompt, text, contract.policy(), corrections + 1, context);
                    if (review == null || failOpen(review.getReasoning())) {
                        feedback = "Required child completion review returned no valid verdict";
                    } else if (review.isStop()) {
                        throw new SupervisionFailure("Child completion rejected: " + review.getReasoning());
                    } else if (!review.isCompliant()) {
                        feedback = review.getViolations() + "\n" + review.getCorrectionPrompt();
                    }
                }
            } catch (SupervisionFailure failure) {
                throw failure;
            } catch (Exception failure) {
                feedback = "Required child completion review failed: " + failure.getMessage();
            }
        }
        if (feedback != null && ++corrections > contract.maxCorrections()) {
            throw new SupervisionFailure("Child completion not accepted: " + feedback);
        }
        completionAccepted = feedback == null;
        return feedback;
    }

    // Existing evaluator parsers explicitly label their fallback verdicts. Do not reinterpret JSON here.
    private static boolean failOpen(String reason) {
        if (reason == null) return true;
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("failing open") || normalized.contains("fail-open")
                || normalized.equals("evaluator has no tool-call policy");
    }

    public static final class SupervisionFailure extends RuntimeException {
        public SupervisionFailure(String message) { super(message); }
    }
}
