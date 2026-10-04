package ai.kompile.cli.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Non-secret launch context for the CLI-owned web chat server, not a config snapshot. */
public final class WebChatContext {
    public static final String WORKING_DIRECTORY = "kompile.chat.handoff.working-directory";
    public static final String CONFIG_SCOPE = "kompile.chat.handoff.config-scope";
    public static final String WORKFLOW = "kompile.chat.handoff.workflow";
    public static final String MODE = "kompile.chat.handoff.mode";
    public static final String ENV_WORKING_DIRECTORY = "KOMPILE_CHAT_HANDOFF_WORKING_DIRECTORY";
    public static final String ENV_CONFIG_SCOPE = "KOMPILE_CHAT_HANDOFF_CONFIG_SCOPE";
    public static final String ENV_WORKFLOW = "KOMPILE_CHAT_HANDOFF_WORKFLOW";
    public static final String ENV_MODE = "KOMPILE_CHAT_HANDOFF_MODE";

    public static boolean workspace() {
        return workspace(System.getenv());
    }

    static boolean workspace(Map<String, String> environment) {
        String mode = contextValue(MODE, ENV_MODE, "single", environment);
        if (!mode.equals("single") && !mode.equals("workspace"))
            throw new IllegalStateException("Invalid web chat launch mode: " + mode);
        return mode.equals("workspace");
    }

    private WebChatContext() { }

    public static Path workingDirectory() throws IOException {
        return workingDirectory(System.getenv());
    }

    static Path workingDirectory(Map<String, String> environment) throws IOException {
        String value = contextValue(WORKING_DIRECTORY, ENV_WORKING_DIRECTORY, null, environment);
        return value == null ? null : Path.of(value).toRealPath();
    }

    public static boolean globalConfig() {
        return globalConfig(System.getenv());
    }

    static boolean globalConfig(Map<String, String> environment) {
        String scope = contextValue(CONFIG_SCOPE, ENV_CONFIG_SCOPE, "project", environment);
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
        return workflow(System.getenv());
    }

    static String workflow(Map<String, String> environment) {
        String value = contextValue(WORKFLOW, ENV_WORKFLOW, null, environment);
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

    public static List<String> jvmArguments(Path directory, boolean global, String workflow, boolean workspace)
            throws IOException {
        List<String> arguments = new ArrayList<>(jvmArguments(directory, global, workflow));
        if (workspace) arguments.add("-D" + MODE + "=workspace");
        return List.copyOf(arguments);
    }

    private static boolean validWorkflow(String name) {
        String value = name.strip();
        return value.length() <= 256 && value.chars().noneMatch(Character::isISOControl);
    }

    /** Native handoff uses the same canonical path and workflow validation as the JAR tier. */
    public static Map<String, String> environment(Path directory, boolean global, String workflow,
                                                 boolean workspace) throws IOException {
        List<String> properties = jvmArguments(directory, global, workflow, workspace);
        Map<String, String> values = new LinkedHashMap<>();
        for (String property : properties) {
            int separator = property.indexOf('=');
            String key = property.substring(2, separator);
            String environmentKey = switch (key) {
                case WORKING_DIRECTORY -> ENV_WORKING_DIRECTORY;
                case CONFIG_SCOPE -> ENV_CONFIG_SCOPE;
                case WORKFLOW -> ENV_WORKFLOW;
                case MODE -> ENV_MODE;
                default -> throw new IllegalStateException("Unknown web chat context property: " + key);
            };
            values.put(environmentKey, property.substring(separator + 1));
        }
        // Explicit defaults prevent optional values leaking from an enclosing web launch.
        values.putIfAbsent(ENV_WORKFLOW, "");
        values.putIfAbsent(ENV_MODE, "single");
        return Map.copyOf(values);
    }

    /** A managed child must not inherit another server's workspace or harness context. */
    public static void clearEnvironment(Map<String, String> environment) {
        for (String key : List.of(ENV_WORKING_DIRECTORY, ENV_CONFIG_SCOPE, ENV_WORKFLOW, ENV_MODE)) {
            environment.remove(key);
        }
    }

    private static String contextValue(String property, String key, String defaultValue,
                                       Map<String, String> environment) {
        String value = System.getProperty(property);
        return value != null ? value : environment.getOrDefault(key, defaultValue);
    }
}
