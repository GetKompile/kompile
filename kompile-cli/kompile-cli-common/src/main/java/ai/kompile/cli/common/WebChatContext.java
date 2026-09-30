package ai.kompile.cli.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Non-secret launch context for the CLI-owned web chat server, not a config snapshot. */
public final class WebChatContext {
    public static final String WORKING_DIRECTORY = "kompile.chat.handoff.working-directory";
    public static final String CONFIG_SCOPE = "kompile.chat.handoff.config-scope";
    public static final String WORKFLOW = "kompile.chat.handoff.workflow";

    private WebChatContext() { }

    public static Path workingDirectory() throws IOException {
        String value = System.getProperty(WORKING_DIRECTORY);
        return value == null ? null : Path.of(value).toRealPath();
    }

    public static boolean globalConfig() {
        String scope = System.getProperty(CONFIG_SCOPE, "project");
        if (!scope.equals("project") && !scope.equals("global")) {
            throw new IllegalStateException("Invalid web chat config scope: " + scope);
        }
        return scope.equals("global");
    }

    /**
     * The workflow team a new web session starts with ({@code kompile chat --web --workflow <name>}),
     * or {@code null} for none. A resumed session keeps the team its transcript recorded.
     */
    public static String workflow() {
        String value = System.getProperty(WORKFLOW);
        if (value == null || value.isBlank()) return null;
        if (!validWorkflow(value)) throw new IllegalStateException("Invalid web chat workflow team name");
        return value.strip();
    }

    public static List<String> jvmArguments(Path directory, boolean global) throws IOException {
        return jvmArguments(directory, global, null);
    }

    /** Launch properties for the web chat server; {@code workflow} is a team name, or {@code null}. */
    public static List<String> jvmArguments(Path directory, boolean global, String workflow) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("-D" + WORKING_DIRECTORY + "=" + directory.toRealPath(),
                "-D" + CONFIG_SCOPE + "=" + (global ? "global" : "project")));
        if (workflow != null && !workflow.isBlank()) {
            if (!validWorkflow(workflow)) throw new IllegalArgumentException("Invalid workflow team name for web chat");
            arguments.add("-D" + WORKFLOW + "=" + workflow.strip());
        }
        return List.copyOf(arguments);
    }

    private static boolean validWorkflow(String name) {
        String value = name.strip();
        return value.length() <= 256 && value.chars().noneMatch(Character::isISOControl);
    }
}
