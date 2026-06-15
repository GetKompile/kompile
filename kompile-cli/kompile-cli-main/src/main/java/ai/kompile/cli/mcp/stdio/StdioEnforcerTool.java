package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerConversationWindow;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerResult;
import ai.kompile.cli.main.chat.enforcer.EnforcerRuntimePolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerService;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP stdio tool that launches a designated external agent under enforcer control.
 */
public class StdioEnforcerTool {

    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;
    private final Path workDir;
    private final BackgroundProcessManager processManager;

    public StdioEnforcerTool(DirectSubagentRunnerStdio subagentRunner,
                             ObjectMapper objectMapper,
                             Path workDir) {
        this(subagentRunner, objectMapper, workDir, null);
    }

    public StdioEnforcerTool(DirectSubagentRunnerStdio subagentRunner,
                             ObjectMapper objectMapper,
                             Path workDir,
                             BackgroundProcessManager processManager) {
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
        this.workDir = workDir;
        this.processManager = processManager;
    }

    public String id() {
        return "enforcer";
    }

    public String description() {
        return "Launch a designated CLI agent under an enforcer judge. The enforcer evaluates the "
                + "subordinate LLM output against user-provided rules, stops non-compliant turns, "
                + "and sends correction prompts until the output complies or the correction limit is reached. "
                + "Use this when a sub LLM must be forced to follow explicit project, style, safety, "
                + "or workflow rules.";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("prompt")
                .put("type", "string")
                .put("description", "The user task to send to the subordinate agent.");

        var agent = props.putObject("agent");
        agent.put("type", "string");
        agent.put("description", "Designated agent to launch. Default: qwen.");
        ArrayNode enumValues = agent.putArray("enum");
        enumValues.add("qwen");
        enumValues.add("claude");
        enumValues.add("codex");
        enumValues.add("gemini");
        enumValues.add("opencode");

        props.putObject("rules")
                .put("type", "string")
                .put("description", "Inline enforcer rules. Required unless rules_file is provided.");
        props.putObject("rules_file")
                .put("type", "string")
                .put("description", "Optional path to a file containing enforcer rules.");
        props.putObject("max_corrections")
                .put("type", "integer")
                .put("description", "Maximum correction attempts after the first blocked response. Default: 2.");
        props.putObject("role")
                .put("type", "string")
                .put("description", "Optional role to assign to the subordinate agent.");
        props.putObject("return_attempts")
                .put("type", "boolean")
                .put("description", "Include enforcement attempt details in the tool result. Default: false.");

        props.putObject("judge_mode")
                .put("type", "string")
                .put("description", "Optional judge backend mode override: auto, remote, local, auto-server.");
        props.putObject("judge_provider")
                .put("type", "string")
                .put("description", "Optional judge provider override, e.g. anthropic, openai, ollama.");
        props.putObject("judge_model")
                .put("type", "string")
                .put("description", "Optional judge model override.");
        props.putObject("judge_api_key")
                .put("type", "string")
                .put("description", "Optional judge API key override.");
        props.putObject("judge_base_url")
                .put("type", "string")
                .put("description", "Optional judge base URL override.");

        schema.putArray("required").add("prompt");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        String prompt = stringArg(arguments, "prompt", "");
        if (prompt.isBlank()) {
            return ToolResult.error("prompt is required");
        }

        String inlineRules = stringArg(arguments, "rules", "");
        String rulesFile = stringArg(arguments, "rules_file", "");
        String rules;
        try {
            rules = EnforcerPolicy.resolveRules(inlineRules, rulesFile, workDir);
        } catch (Exception e) {
            return ToolResult.error("Could not read enforcer rules: " + e.getMessage());
        }
        if (rules.isBlank()) {
            return ToolResult.error("rules or rules_file is required");
        }

        String agentName = stringArg(arguments, "agent", "qwen");
        String roleName = stringArg(arguments, "role", null);
        int maxCorrections = intArg(arguments, "max_corrections", EnforcerPolicy.DEFAULT_MAX_CORRECTIONS);
        boolean returnAttempts = boolArg(arguments, "return_attempts", false);

        HarnessConfig config = loadHarnessConfig(arguments);
        EnforcerPolicy policy = new EnforcerPolicy(rules, maxCorrections, returnAttempts);
        EnforcerRuntimePolicy runtimePolicy;
        try {
            runtimePolicy = EnforcerRuntimePolicy.create(workDir, policy, config, objectMapper);
        } catch (Exception e) {
            return ToolResult.error("Could not create enforcer runtime policy: " + e.getMessage());
        }
        EnforcerJudge judge = new EnforcerJudge(config, objectMapper);
        BackgroundProcessManager.ProcessEntry processEntry = registerEnforcerProcess(
                runtimePolicy.getSessionId(), agentName, judge.describe());
        EnforcerConversationWindow conversationWindow =
                new EnforcerConversationWindow(runtimePolicy.getContextFile(), objectMapper);
        conversationWindow.addUserMessage(prompt);
        subagentRunner.setExtraEnvironment(runtimePolicy.toEnvironment());
        boolean completed = false;
        try {
            EnforcerService service = new EnforcerService(judge);

            AgentConfig agentConfig = AgentConfig.builder(agentName)
                    .displayName(displayName(agentName))
                    .description("External " + agentName + " agent controlled by enforcer")
                    .systemPrompt("")
                    .maxSteps(50)
                    .isSubagent(true)
                    .canSpawnSubagents(false)
                    .roleName(roleName)
                    .build();

            EnforcerResult result = service.enforce(prompt, policy, conversationWindow::snapshot,
                    agentPrompt -> {
                        String output = subagentRunner.runSubagent(agentConfig, agentPrompt);
                        conversationWindow.finishAssistantMessage(output);
                        return output;
                    });

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status", result.getStatus().name().toLowerCase());
            meta.put("agent", agentName);
            meta.put("attempts", result.getAttempts().size());
            meta.put("judgeBackend", result.getJudgeBackend());
            meta.put("sessionId", runtimePolicy.getSessionId());
            if (processEntry != null) {
                meta.put("processId", processEntry.getId());
            }
            String taskId = subagentRunner.getLastTaskId();
            if (taskId != null) {
                meta.put("lastTaskId", taskId);
            }

            completed = true;
            String started = "Enforcer watcher started: "
                    + (processEntry != null ? processEntry.getId() : "untracked")
                    + " session=" + runtimePolicy.getSessionId()
                    + " agent=" + agentName
                    + " judge=" + result.getJudgeBackend();
            return ToolResult.success("enforcer:" + agentName,
                    started + "\n\n" + result.toMarkdown(returnAttempts), meta);
        } finally {
            if (processEntry != null) {
                if (completed) {
                    processManager.complete(processEntry.getId());
                } else {
                    processManager.fail(processEntry.getId());
                }
            }
            subagentRunner.setExtraEnvironment(Map.<String, String>of());
            runtimePolicy.cleanup();
            judge.close();
        }
    }

    private BackgroundProcessManager.ProcessEntry registerEnforcerProcess(String sessionId,
                                                                         String agentName,
                                                                         String judgeBackend) {
        if (processManager == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId != null ? sessionId : "");
        metadata.put("agent", agentName != null ? agentName : "");
        metadata.put("judge", judgeBackend != null ? judgeBackend : "");
        metadata.put("workingDirectory", workDir.toString());
        BackgroundProcessManager.ProcessEntry entry = processManager.registerVirtual(
                BackgroundProcessManager.ProcessKind.ENFORCER,
                "enforcer " + (agentName != null ? agentName : "agent"),
                "Enforcer watcher for " + (agentName != null ? agentName : "agent"),
                metadata);
        System.out.println("[enforcer] watcher started: " + entry.getId()
                + " session=" + sessionId
                + " judge=" + judgeBackend);
        return entry;
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

    private static String displayName(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "Agent";
        }
        return agentName.substring(0, 1).toUpperCase() + agentName.substring(1);
    }
}
