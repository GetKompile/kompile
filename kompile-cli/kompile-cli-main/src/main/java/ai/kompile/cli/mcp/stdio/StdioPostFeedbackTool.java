package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.enforcer.EnforcerConversationContext;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.PostFeedbackDecision;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Last-resort post-run audit tool. It does not block execution; it checks
 * whether completed agent work appears correct using available transcripts,
 * task output, MCP logs, git diff, and optional test command evidence.
 */
public class StdioPostFeedbackTool {

    private static final int MAX_PROMPT_CHARS = 6_000;
    private static final int MAX_EVIDENCE_CHARS = 36_000;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 12_000;

    private static final String SYSTEM_PROMPT = """
            You are Kompile Post Feedback, a strict post-run audit judge.

            Decide whether the completed agent work actually satisfied the original request
            and user rules, based only on the evidence provided. This is a last-resort check
            for modes where real-time interruption may not be possible.

            Use:
            - PASS when the work appears correct and rule-compliant.
            - WARN when there are uncertainties, minor gaps, or missing verification.
            - FAIL when the work is incorrect, incomplete, unsafe, out of scope, or contradicted by tests/evidence.

            Respond ONLY with valid JSON on a single line:
            {"status":"PASS|WARN|FAIL","score":0.0,"findings":["..."],"evidence":["..."],"next_actions":["..."],"correction_prompt":"...","reasoning":"..."}

            Do not add prose outside the JSON.
            """;

    private final ObjectMapper objectMapper;
    private final Path workDir;

    public StdioPostFeedbackTool(ObjectMapper objectMapper, Path workDir) {
        this.objectMapper = objectMapper;
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    public String id() {
        return "post_feedback";
    }

    public String description() {
        return "Post-run feedback audit. Checks whether an agent's completed work was correct "
                + "using task output, transcript/session output, MCP logs, git diff, and optional "
                + "test command evidence. This is a last-resort fallback for passthrough modes where "
                + "real-time interruption is not possible.";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("original_prompt")
                .put("type", "string")
                .put("description", "Original user request. Recommended; inferred from task_id when available.");
        props.putObject("rules")
                .put("type", "string")
                .put("description", "Rules or acceptance criteria to audit against.");
        props.putObject("rules_file")
                .put("type", "string")
                .put("description", "Optional file containing rules or acceptance criteria.");
        props.putObject("agent_output")
                .put("type", "string")
                .put("description", "Agent's final answer or output. Optional when task_id/session_id is provided.");
        var recentMessagesSchema = props.putObject("recent_messages");
        ArrayNode recentTypes = recentMessagesSchema.putArray("type");
        recentTypes.add("string");
        recentTypes.add("array");
        recentMessagesSchema.put("description", "Recent chat messages to include as audit context. Accepts a string or an array of {role, content} objects.");
        props.putObject("recent_messages_file")
                .put("type", "string")
                .put("description", "Path to an enforcer recent-message context JSON file.");
        props.putObject("task_id")
                .put("type", "string")
                .put("description", "Task registry ID from task/multi_task/quorum_task/enforcer.");
        props.putObject("session_id")
                .put("type", "string")
                .put("description", "Chat transcript session ID under ~/.kompile/conversations.");
        props.putObject("include_diff")
                .put("type", "boolean")
                .put("description", "Include git status/diff evidence. Default: true.");
        props.putObject("include_mcp_log")
                .put("type", "boolean")
                .put("description", "Include recent MCP activity log evidence. Default: true.");
        props.putObject("run_tests")
                .put("type", "boolean")
                .put("description", "Run test_command and include its output. Default: false.");
        props.putObject("test_command")
                .put("type", "string")
                .put("description", "Explicit test/check command to run when run_tests=true.");
        props.putObject("test_timeout_seconds")
                .put("type", "integer")
                .put("description", "Timeout for test_command. Default: 120.");

        var judgeMode = props.putObject("judge_mode");
        judgeMode.put("type", "string");
        ArrayNode judgeModes = judgeMode.putArray("enum");
        judgeModes.add("auto");
        judgeModes.add("remote");
        judgeModes.add("local");
        judgeModes.add("auto-server");
        judgeMode.put("description", "Optional judge backend mode override.");
        props.putObject("judge_provider").put("type", "string")
                .put("description", "Optional judge provider override, e.g. anthropic, openai, ollama.");
        props.putObject("judge_model").put("type", "string")
                .put("description", "Optional judge model override.");
        props.putObject("judge_api_key").put("type", "string")
                .put("description", "Optional judge API key override.");
        props.putObject("judge_base_url").put("type", "string")
                .put("description", "Optional judge API base URL override.");

        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> args = arguments != null ? arguments : Map.of();

        String originalPrompt = stringArg(args, "original_prompt", "");
        String agentOutput = stringArg(args, "agent_output", "");
        String recentMessages = recentMessagesArg(args);
        String recentMessagesFile = stringArg(args, "recent_messages_file", "");
        String taskId = stringArg(args, "task_id", "");
        String sessionId = stringArg(args, "session_id", "");
        boolean includeDiff = boolArg(args, "include_diff", true);
        boolean includeMcpLog = boolArg(args, "include_mcp_log", true);
        boolean runTests = boolArg(args, "run_tests", false);
        String testCommand = stringArg(args, "test_command", "");
        int testTimeoutSeconds = intArg(args, "test_timeout_seconds", 120);

        String rules;
        try {
            rules = EnforcerPolicy.resolveRules(
                    stringArg(args, "rules", ""),
                    stringArg(args, "rules_file", ""),
                    workDir);
        } catch (Exception e) {
            return ToolResult.error("Could not read rules_file: " + e.getMessage());
        }

        StringBuilder evidence = new StringBuilder();
        TaskRecord taskRecord = null;
        if (!taskId.isBlank()) {
            taskRecord = new TaskRegistry(workDir).get(taskId);
            appendTaskEvidence(evidence, taskId, taskRecord);
            if (taskRecord != null) {
                if (originalPrompt.isBlank() && taskRecord.getPromptSummary() != null) {
                    originalPrompt = taskRecord.getPromptSummary();
                }
                if (agentOutput.isBlank()) {
                    agentOutput = readTaskOutput(taskRecord);
                }
            }
        }

        if (!recentMessagesFile.isBlank()) {
            appendSection(evidence, "Recent Chat Messages", readRecentMessagesFile(Path.of(recentMessagesFile)));
        }
        if (!recentMessages.isBlank()) {
            appendSection(evidence, "Recent Chat Messages", recentMessages);
        }
        if (!sessionId.isBlank()) {
            appendSection(evidence, "Transcript " + sessionId, readTranscript(sessionId));
        }
        if (!agentOutput.isBlank()) {
            appendSection(evidence, "Agent Output", agentOutput);
        }
        if (includeDiff) {
            appendSection(evidence, "Git Status", runReadOnlyCommand(List.of("git", "status", "--short"), 20));
            appendSection(evidence, "Git Diff Stat", runReadOnlyCommand(List.of("git", "diff", "--stat"), 20));
            appendSection(evidence, "Git Diff", runReadOnlyCommand(List.of("git", "diff", "--"), 20));
        }
        if (includeMcpLog) {
            appendSection(evidence, "Recent MCP Activity", readRecentMcpLog());
        }
        if (runTests) {
            if (testCommand.isBlank()) {
                appendSection(evidence, "Test Command", "run_tests=true but no test_command was supplied.");
            } else {
                appendSection(evidence, "Test Command", "$ " + testCommand + "\n"
                        + runShellCommand(testCommand, Math.max(1, testTimeoutSeconds)));
            }
        }

        if (originalPrompt.isBlank() && taskRecord == null && agentOutput.isBlank()
                && sessionId.isBlank() && recentMessages.isBlank() && recentMessagesFile.isBlank()) {
            return ToolResult.error("Provide original_prompt, agent_output, task_id, session_id, or recent_messages for post feedback.");
        }

        HarnessConfig config = loadHarnessConfig(args);
        JudgeBackend backend = JudgeBackendFactory.create(config, objectMapper);
        if (backend == null || !backend.isAvailable()) {
            return ToolResult.error("No post_feedback judge backend is available. Configure harness judge settings or pass judge_* overrides.");
        }

        try {
            String judgePrompt = buildJudgePrompt(originalPrompt, rules, evidence.toString());
            String response = backend.generate(judgePrompt, SYSTEM_PROMPT);
            PostFeedbackDecision decision = PostFeedbackDecision.parse(objectMapper, response);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("status", decision.getStatus().name().toLowerCase());
            metadata.put("score", decision.getScore());
            metadata.put("judgeBackend", backend.describe());
            if (!taskId.isBlank()) metadata.put("taskId", taskId);
            if (!sessionId.isBlank()) metadata.put("sessionId", sessionId);

            return ToolResult.success("post_feedback:" + decision.getStatus().name().toLowerCase(),
                    decision.toMarkdown(), metadata);
        } catch (Exception e) {
            return ToolResult.error("Post feedback judge failed: " + e.getMessage());
        } finally {
            backend.close();
        }
    }

    private String buildJudgePrompt(String originalPrompt, String rules, String evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[ORIGINAL REQUEST]\n")
                .append(truncate(originalPrompt, MAX_PROMPT_CHARS))
                .append("\n[END ORIGINAL REQUEST]\n\n");

        prompt.append("[RULES / ACCEPTANCE CRITERIA]\n")
                .append(rules == null || rules.isBlank() ? "(none supplied)" : rules)
                .append("\n[END RULES / ACCEPTANCE CRITERIA]\n\n");

        prompt.append("[EVIDENCE]\n")
                .append(truncate(evidence, MAX_EVIDENCE_CHARS))
                .append("\n[END EVIDENCE]\n\n");

        prompt.append("Audit whether the completed work is correct and rule-compliant. ")
                .append("If evidence is insufficient, use WARN and say exactly what is missing. ")
                .append("If the work should be corrected, include a concrete correction_prompt.");
        return prompt.toString();
    }

    private void appendTaskEvidence(StringBuilder evidence, String taskId, TaskRecord taskRecord) {
        if (taskRecord == null) {
            appendSection(evidence, "Task " + taskId, "Task record not found.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(TaskRegistry.formatStatus(taskRecord)).append("\n");
        if (taskRecord.getPromptSummary() != null) {
            sb.append("Prompt summary: ").append(taskRecord.getPromptSummary()).append("\n");
        }
        if (taskRecord.getResultSummary() != null) {
            sb.append("Result summary: ").append(taskRecord.getResultSummary()).append("\n");
        }
        if (taskRecord.getOutputPath() != null) {
            sb.append("Output path: ").append(taskRecord.getOutputPath()).append("\n");
        }
        appendSection(evidence, "Task " + taskId, sb.toString());
    }

    private String readTaskOutput(TaskRecord taskRecord) {
        if (taskRecord == null || taskRecord.getOutputPath() == null || taskRecord.getOutputPath().isBlank()) {
            return "";
        }
        return readFile(Path.of(taskRecord.getOutputPath()), MAX_EVIDENCE_CHARS);
    }

    private String readTranscript(String sessionId) {
        Path transcript = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(sessionId + ".txt");
        return readFile(transcript, MAX_EVIDENCE_CHARS);
    }

    private String readRecentMessagesFile(Path path) {
        Path resolved = path.isAbsolute() ? path : workDir.resolve(path).normalize();
        EnforcerConversationContext context = EnforcerConversationContext.read(resolved, objectMapper);
        if (!context.isEmpty()) {
            return context.formatForPrompt(MAX_EVIDENCE_CHARS);
        }
        return readFile(resolved, MAX_EVIDENCE_CHARS);
    }

    private String readRecentMcpLog() {
        Path log = KompileHome.homeDirectory().toPath()
                .resolve("logs").resolve("mcp-activity.log");
        if (!Files.exists(log)) {
            return "No MCP activity log found at " + log;
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 120);
            return String.join("\n", lines.subList(start, lines.size()));
        } catch (Exception e) {
            return "Could not read MCP activity log: " + e.getMessage();
        }
    }

    private String readFile(Path path, int maxChars) {
        if (path == null || !Files.exists(path)) {
            return path == null ? "" : "File not found: " + path;
        }
        try {
            return truncate(Files.readString(path, StandardCharsets.UTF_8), maxChars);
        } catch (Exception e) {
            return "Could not read " + path + ": " + e.getMessage();
        }
    }

    private String runReadOnlyCommand(List<String> command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readProcessOutput(process, timeoutSeconds);
            return output.isBlank() ? "(no output)" : output;
        } catch (Exception e) {
            return "Could not run " + String.join(" ", command) + ": " + e.getMessage();
        }
    }

    private String runShellCommand(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return readProcessOutput(process, timeoutSeconds);
        } catch (Exception e) {
            return "Could not run test command: " + e.getMessage();
        }
    }

    private String readProcessOutput(Process process, int timeoutSeconds) throws Exception {
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > MAX_COMMAND_OUTPUT_CHARS * 2) {
                        output.delete(0, output.length() - MAX_COMMAND_OUTPUT_CHARS);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "post-feedback-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(Duration.ofSeconds(2).toMillis());
            return truncate(output + "\n[TIMED OUT after " + timeoutSeconds + "s]", MAX_COMMAND_OUTPUT_CHARS);
        }
        reader.join(Duration.ofSeconds(2).toMillis());
        return truncate(output + "\n[exit code " + process.exitValue() + "]", MAX_COMMAND_OUTPUT_CHARS);
    }

    private void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        sb.append("\n## ").append(title).append("\n")
                .append(body).append("\n");
    }

    private HarnessConfig loadHarnessConfig(Map<String, Object> arguments) {
        HarnessConfig config = HarnessConfig.load(objectMapper);
        setIfPresent(arguments, "judge_mode", config::setJudgeMode);
        setIfPresent(arguments, "judge_provider", config::setJudgeProvider);
        setIfPresent(arguments, "judge_model", config::setJudgeModel);
        setIfPresent(arguments, "judge_api_key", config::setJudgeApiKey);
        setIfPresent(arguments, "judge_base_url", config::setJudgeBaseUrl);
        return config;
    }

    private static void setIfPresent(Map<String, Object> args, String key,
                                     java.util.function.Consumer<String> setter) {
        String value = stringArg(args, key, null);
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        String value = String.valueOf(args.get(key));
        return value != null ? value : defaultValue;
    }

    private String recentMessagesArg(Map<String, Object> args) {
        if (args == null || args.get("recent_messages") == null) {
            return "";
        }
        Object value = args.get("recent_messages");
        if (value instanceof String s) {
            return s;
        }
        try {
            JsonNode node = objectMapper.valueToTree(value);
            EnforcerConversationContext context = EnforcerConversationContext.fromJson(node);
            if (!context.isEmpty()) {
                return context.formatForPrompt(MAX_EVIDENCE_CHARS);
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || args.get(key) == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null || args.get(key) == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars - 80)
                + "\n... (truncated, " + text.length() + " chars total)";
    }
}
