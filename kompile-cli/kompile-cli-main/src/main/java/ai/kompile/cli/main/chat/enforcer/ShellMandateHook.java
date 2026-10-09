package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;

/** Pre-execution gate for provider-native tools, independent of optional judge rules. */
public final class ShellMandateHook {
    public static final String ARGUMENT = "--shell-mandate-hook";

    private ShellMandateHook() { }

    /** Claude Code's blocking hook exit code; stderr is returned to the agent. */
    public static int run(InputStream input, PrintStream error) {
        try {
            JsonNode event = new ObjectMapper().readTree(input);
            if (event == null || !event.isObject() || !event.path("tool_name").isTextual()
                    || !event.has("tool_input")) {
                error.println("[kompile] Shell mandate hook received an invalid tool event; refusing execution.");
                return 2;
            }
            String toolName = event.path("tool_name").asText();
            JsonNode args = event.get("tool_input");
            if (ShellMandatePolicy.isShellTool(toolName)
                    && (!args.isObject() || ShellMandatePolicy.extractCommandFromJson(args.toString()) == null)) {
                error.println("[kompile] Shell mandate hook cannot read the shell command; refusing execution.");
                return 2;
            }
            Path cwd = Path.of(event.path("cwd").asText(System.getProperty("user.dir")));
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(
                    toolName, args.toString(), cwd);
            if (decision != null) {
                error.println("[kompile] " + decision.getCorrectionPrompt());
                return 2;
            }
            return 0;
        } catch (Exception e) {
            error.println("[kompile] Shell mandate hook failed; refusing execution: " + e.getMessage());
            return 2;
        }
    }
}
