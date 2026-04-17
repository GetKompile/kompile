package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
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
 * Spawns the same prompt to multiple agents in parallel and returns all results.
 * The caller can use the collected responses to form a quorum/consensus.
 */
public class StdioQuorumTaskTool {

    private final AgentRegistry agentRegistry;
    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;
    private final Path workDir;

    public StdioQuorumTaskTool(AgentRegistry agentRegistry,
                               DirectSubagentRunnerStdio subagentRunner,
                               ObjectMapper objectMapper,
                               Path workDir) {
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
        this.workDir = workDir;
    }

    public String id() { return "quorum_task"; }

    public String description() {
        return "Spawn the same prompt to multiple agents in parallel and collect all responses. " +
            "Use this for tasks where you want independent opinions from different agents " +
            "to compare, vote on, or synthesize into a consensus.\n\n" +
            "Each agent runs independently with the same prompt. Results are returned together " +
            "so you can identify agreement/disagreement across agents.\n\n" +
            "Available agents: qwen, claude, codex, gemini, opencode.";
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
        prompt.put("description", "The prompt to send to all agents. Each agent receives this identically.");

        var agents = props.putObject("agents");
        agents.put("type", "array");
        agents.put("description", "List of agents to query. Each receives the same prompt in parallel.");
        var items = agents.putObject("items");
        items.put("type", "string");
        ArrayNode enumValues = items.putArray("enum");
        enumValues.add("qwen"); enumValues.add("claude"); enumValues.add("codex");
        enumValues.add("gemini"); enumValues.add("opencode");
        agents.put("minItems", 2);

        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional role to assign to all agents (e.g., 'reviewer', 'architect')");

        schema.putArray("required").add("description").add("prompt").add("agents");
        return schema;
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments) {
        String desc = (String) arguments.getOrDefault("description", "");
        String prompt = (String) arguments.getOrDefault("prompt", "");
        Object agentsObj = arguments.get("agents");
        String roleName = (String) arguments.get("role");

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        List<String> agentNames;
        if (agentsObj instanceof List) {
            agentNames = (List<String>) agentsObj;
        } else {
            return ToolResult.error("agents must be an array of agent names");
        }

        if (agentNames.size() < 2) {
            return ToolResult.error("At least 2 agents are required for a quorum. Use 'task' for single-agent delegation.");
        }

        System.err.println("\u001B[32m  ⟳ Quorum task: " + desc + " (" + agentNames.size() + " agents)\u001B[0m");
        System.err.flush();

        // Run all agents in parallel
        ExecutorService executor = Executors.newFixedThreadPool(agentNames.size());
        Map<String, Future<AgentResult>> futures = new LinkedHashMap<>();

        for (String agentName : agentNames) {
            futures.put(agentName, executor.submit(() -> runAgent(agentName, prompt, roleName, desc)));
        }

        // Collect results — full output goes to file, summary to tool result
        StringBuilder fullOutput = new StringBuilder();
        fullOutput.append("# Quorum Results: ").append(desc).append("\n\n");
        fullOutput.append("**Agents queried:** ").append(String.join(", ", agentNames)).append("\n\n");

        int succeeded = 0;
        int failed = 0;
        List<String> successfulAgents = new ArrayList<>();
        // Per-agent summaries for the inline result
        StringBuilder summaryOutput = new StringBuilder();

        for (Map.Entry<String, Future<AgentResult>> entry : futures.entrySet()) {
            String agentName = entry.getKey();
            fullOutput.append("---\n\n");
            fullOutput.append("## Agent: ").append(agentName).append("\n\n");

            try {
                AgentResult result = entry.getValue().get(10, TimeUnit.MINUTES);
                if (result.success) {
                    succeeded++;
                    successfulAgents.add(agentName);
                    fullOutput.append(result.output).append("\n\n");
                    // Build a brief per-agent summary
                    summaryOutput.append("- **").append(agentName).append("**: ")
                        .append(truncateForSummary(result.output, 200)).append("\n");
                } else {
                    failed++;
                    fullOutput.append("**Failed:** ").append(result.output).append("\n\n");
                    summaryOutput.append("- **").append(agentName).append("**: FAILED — ")
                        .append(truncateForSummary(result.output, 100)).append("\n");
                }
            } catch (TimeoutException e) {
                failed++;
                fullOutput.append("**Timed out** after 10 minutes.\n\n");
                summaryOutput.append("- **").append(agentName).append("**: TIMED OUT\n");
            } catch (Exception e) {
                failed++;
                fullOutput.append("**Error:** ").append(e.getMessage()).append("\n\n");
                summaryOutput.append("- **").append(agentName).append("**: ERROR — ").append(e.getMessage()).append("\n");
            }
        }

        executor.shutdownNow();

        fullOutput.append("---\n\n");
        fullOutput.append("**Summary:** ").append(succeeded).append("/").append(agentNames.size())
              .append(" agents responded successfully.");

        System.err.println("\u001B[32m  ✓ Quorum complete: " + succeeded + "/" + agentNames.size() + " succeeded\u001B[0m");
        System.err.flush();

        // Write full output to file
        Path resultFile = writeQuorumResultToFile(desc, fullOutput.toString());

        // Build concise tool result
        StringBuilder toolResult = new StringBuilder();
        toolResult.append("## Quorum Results: ").append(desc).append("\n\n");
        toolResult.append(succeeded).append("/").append(agentNames.size()).append(" agents responded successfully.\n\n");
        toolResult.append(summaryOutput).append("\n");
        toolResult.append("**Full output written to:** `").append(resultFile.toAbsolutePath()).append("`\n");
        toolResult.append("Use the `read` tool to access the full results if needed.");

        return ToolResult.success("quorum_task", toolResult.toString(),
            Map.of("description", desc,
                   "agentsQueried", String.join(",", agentNames),
                   "agentsSucceeded", String.join(",", successfulAgents),
                   "succeeded", succeeded,
                   "failed", failed,
                   "resultFile", resultFile.toAbsolutePath().toString()));
    }

    private AgentResult runAgent(String agentName, String prompt, String roleName, String desc) {
        try {
            AgentConfig agentConfig = AgentConfig.builder(agentName)
                .displayName(agentName.substring(0, 1).toUpperCase() + agentName.substring(1))
                .description("External " + agentName + " agent")
                .systemPrompt(prompt).maxSteps(50).isSubagent(true).canSpawnSubagents(false)
                .roleName(roleName)
                .build();

            String result = subagentRunner.runSubagent(agentConfig, prompt);
            if (result.contains("not found in PATH")) {
                return new AgentResult(false, agentName + " is not installed.");
            }
            return new AgentResult(true, result);
        } catch (RateLimitException e) {
            return new AgentResult(false, "Rate limited.");
        } catch (Exception e) {
            return new AgentResult(false, e.getMessage());
        }
    }

    private Path writeQuorumResultToFile(String desc, String fullOutput) {
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String sanitizedDesc = desc.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (sanitizedDesc.length() > 40) sanitizedDesc = sanitizedDesc.substring(0, 40);
            Path resultFile = resultsDir.resolve("quorum-" + sanitizedDesc + "-" + timestamp + ".md");

            Files.writeString(resultFile, fullOutput, StandardCharsets.UTF_8);
            System.err.println("\033[2m  Full quorum output written to: " + resultFile + "\033[0m");
            System.err.flush();
            return resultFile;
        } catch (IOException e) {
            System.err.println("\033[31m  Warning: Could not write quorum result file: " + e.getMessage() + "\033[0m");
            try {
                Path tempFile = Files.createTempFile("kompile-quorum-", ".md");
                Files.writeString(tempFile, fullOutput, StandardCharsets.UTF_8);
                return tempFile;
            } catch (IOException e2) {
                return workDir.resolve(".kompile-quorum-result-unavailable.md");
            }
        }
    }

    private static String truncateForSummary(String text, int maxChars) {
        if (text == null || text.isEmpty()) return "(empty)";
        // Take first line or first maxChars, whichever is shorter
        String firstLine = text.split("\n", 2)[0].trim();
        if (firstLine.length() <= maxChars) return firstLine;
        return firstLine.substring(0, maxChars) + "...";
    }

    private static class AgentResult {
        final boolean success;
        final String output;

        AgentResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }
}
