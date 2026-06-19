package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.PersistentAgentProcess;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.core.agent.CliAgentRegistry;
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
 * Splits a complex task into multiple distinct subtasks and runs each on a
 * (possibly different) agent in parallel.  Unlike quorum_task which sends
 * the <em>same</em> prompt to every agent, multi_task sends a <em>different</em>
 * prompt to each, then collects and summarises the results.
 */
public class StdioMultiTaskTool {

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
        return "Split a complex task into distinct subtasks and run each on a separate agent in parallel. " +
            "Unlike quorum_task (same prompt to all agents), multi_task gives each agent a DIFFERENT prompt " +
            "representing a different part of the work.\n\n" +
            "Each subtask can specify a single agent (via 'agent') or multiple agent types (via 'agents' array). " +
            "When 'agents' is provided, every listed agent type works on the same subtask prompt in parallel. " +
            "Combine with 'agent_count' to spawn N instances of each agent type.\n\n" +
            "Examples:\n" +
            "  - {\"agent\": \"qwen\"} → 1 qwen instance\n" +
            "  - {\"agents\": [\"qwen\", \"gemini\"]} → 1 qwen + 1 gemini on same prompt\n" +
            "  - {\"agents\": [\"qwen\", \"gemini\"], \"agent_count\": 2} → 2 qwen + 2 gemini\n\n" +
            "All subtasks run concurrently. Returns a per-subtask summary; full output is written to a file.\n\n" +
            "Available agents: qwen (default), claude, codex, gemini, opencode.";
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
        subtasks.put("description", "Array of subtasks to run in parallel. Each gets a different prompt.");
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
        subAgent.put("description", "Single agent for this subtask. Ignored if 'agents' is provided. Defaults to qwen.");
        ArrayNode enumValues = subAgent.putArray("enum");
        for (String name : CliAgentRegistry.commandNames()) enumValues.add(name);

        var subAgents = itemProps.putObject("agents");
        subAgents.put("type", "array");
        subAgents.put("description", "Multiple agent types for this subtask. Each agent works on the same prompt in parallel. Overrides 'agent' if provided.");
        var agentItems = subAgents.putObject("items");
        agentItems.put("type", "string");
        ArrayNode agentEnumValues = agentItems.putArray("enum");
        for (String name : CliAgentRegistry.commandNames()) agentEnumValues.add(name);

        var subRole = itemProps.putObject("role");
        subRole.put("type", "string");
        subRole.put("description", "Optional role to assign to the agent for this subtask");

        var subAgentCount = itemProps.putObject("agent_count");
        subAgentCount.put("type", "integer");
        subAgentCount.put("description", "Number of agent instances for THIS subtask. Overrides the top-level agent_count. Default: inherits top-level agent_count.");
        subAgentCount.put("minimum", 1);
        subAgentCount.put("maximum", 5);

        items.putArray("required").add("name").add("prompt");

        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional default role applied to all subtasks that don't specify their own");

        var agentCount = props.putObject("agent_count");
        agentCount.put("type", "integer");
        agentCount.put("description", "Default number of agent instances to spawn per subtask. Each instance runs the same prompt independently. Default: 1");
        agentCount.put("default", 1);
        agentCount.put("minimum", 1);
        agentCount.put("maximum", 5);

        schema.putArray("required").add("description").add("subtasks");
        return schema;
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments) {
        String desc = (String) arguments.getOrDefault("description", "");
        Object subtasksObj = arguments.get("subtasks");
        String defaultRole = (String) arguments.get("role");

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

        System.err.println("\u001B[32m  ⟳ Multi-task: " + desc + " (" + subtasks.size() + " subtasks)\u001B[0m");
        for (int i = 0; i < subtasks.size(); i++) {
            Map<String, Object> st = subtasks.get(i);
            String name = (String) st.getOrDefault("name", "subtask-" + i);
            List<String> agentTypes = resolveAgentTypes(st);
            int taskAgentCount = resolveAgentCount(st, defaultAgentCount);
            String agentLabel = String.join(", ", agentTypes);
            String countSuffix = taskAgentCount > 1 ? " x" + taskAgentCount : "";
            System.err.println("\u001B[2m    [" + (i + 1) + "] " + name + " → " + agentLabel + countSuffix + "\u001B[0m");
        }
        System.err.flush();

        // Calculate total instances across all subtasks (agent types × instances per type)
        int totalInstances = 0;
        for (Map<String, Object> st : subtasks) {
            totalInstances += resolveAgentTypes(st).size() * resolveAgentCount(st, defaultAgentCount);
        }
        ExecutorService executor = Executors.newFixedThreadPool(totalInstances);
        List<SubtaskFuture> futures = new ArrayList<>();

        for (int i = 0; i < subtasks.size(); i++) {
            Map<String, Object> st = subtasks.get(i);
            String name = (String) st.getOrDefault("name", "subtask-" + i);
            String prompt = (String) st.getOrDefault("prompt", "");
            String role = (String) st.getOrDefault("role", defaultRole);
            List<String> agentTypes = resolveAgentTypes(st);
            int taskAgentCount = resolveAgentCount(st, defaultAgentCount);

            if (prompt.isEmpty()) {
                futures.add(new SubtaskFuture(name, agentTypes.get(0), 1, 0,
                    CompletableFuture.completedFuture(SubtaskResult.failed("(empty prompt)"))));
                continue;
            }

            final String fName = name;
            final String fPrompt = prompt;
            final String fRole = role;
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
                    futures.add(new SubtaskFuture(instanceName, agentType, totalPerSubtask, instanceIdx,
                        CompletableFuture.supplyAsync(() -> runSubtask(fName, fPrompt, fAgent, fRole), executor)));
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
            fullOutput.append("## ").append(sf.name).append(" (").append(sf.agent).append(")\n\n");

            try {
                SubtaskResult result = sf.future.get(10, TimeUnit.MINUTES);
                switch (result.outcome) {
                    case COMPLETED -> {
                        succeeded++;
                        fullOutput.append(result.output).append("\n\n");
                        summaryOutput.append("- **").append(sf.name).append("** (").append(sf.agent).append("): ")
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

        executor.shutdownNow();

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
    }

    /**
     * Resolve the list of agent types for a subtask.
     * If 'agents' (array) is provided, use that. Otherwise fall back to 'agent' (string, default "qwen").
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
        return List.of((String) subtask.getOrDefault("agent", "qwen"));
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

    private SubtaskResult runSubtask(String name, String prompt, String requestedAgent, String roleName) {
        // Try the requested agent first, then role-based fallbacks
        List<String> roleFallbacks = roleManager.getAgentFallbackOrder(roleName);
        List<String> agentsToTry = new ArrayList<>();
        agentsToTry.add(requestedAgent);
        for (String fallback : roleFallbacks) {
            if (!fallback.equals(requestedAgent)) {
                agentsToTry.add(fallback);
            }
        }

        for (String agentName : agentsToTry) {
            try {
                AgentConfig agentConfig = AgentConfig.builder(agentName)
                    .displayName(agentName.substring(0, 1).toUpperCase() + agentName.substring(1))
                    .description("Subtask: " + name)
                    .systemPrompt(prompt).maxSteps(50).isSubagent(true).canSpawnSubagents(false)
                    .roleName(roleName)
                    .build();

                String result = subagentRunner.runSubagent(agentConfig, prompt);
                if (result.contains("not found in PATH")) {
                    continue; // Try next agent
                }
                return SubtaskResult.completed(result);
            } catch (PersistentAgentProcess.TimedOutException toe) {
                // The turn timed out — surface as TIMED_OUT, NOT as a success.
                // Partial output is preserved so it can be written to the result file.
                String partial = toe.getPartialOutput();
                String snippet = partial.isEmpty() ? "(no output captured)"
                    : partial.substring(0, Math.min(80, partial.length()));
                System.err.println("\u001B[31m    \u23f1 " + name + ": " + agentName
                    + " timed out. Partial: " + snippet + "\u001B[0m");
                return SubtaskResult.timedOut(partial);
            } catch (RateLimitException e) {
                System.err.println("\u001B[33m    \u26a0 " + name + ": " + agentName
                    + " rate limited, trying fallback...\u001B[0m");
                continue;
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
     * <b>COMPLETED</b> — the agent produced a result event (stream-json {@code "type":"result"})
     * and the turn ended normally.  This is the only outcome counted as "succeeded".<br>
     * <b>TIMED_OUT</b> — the per-turn timeout fired before a result event was received.
     * The {@code output} field holds whatever partial text was captured up to that point.
     * This MUST NOT be counted as success; it means the subtask ran out of time.<br>
     * <b>FAILED</b> — a communication error, process crash, or explicit error return.
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

    private static class SubtaskFuture {
        final String name;
        final String agent;
        final int totalInstances;
        final int instanceIndex;
        final Future<SubtaskResult> future;

        SubtaskFuture(String name, String agent, int totalInstances, int instanceIndex, Future<SubtaskResult> future) {
            this.name = name;
            this.agent = agent;
            this.totalInstances = totalInstances;
            this.instanceIndex = instanceIndex;
            this.future = future;
        }
    }
}
