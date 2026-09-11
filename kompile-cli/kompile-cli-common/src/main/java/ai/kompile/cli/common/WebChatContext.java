package ai.kompile.cli.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Non-secret launch context for the CLI-owned web chat server, not a config snapshot. */
public final class WebChatContext {
    public static final String WORKING_DIRECTORY = "kompile.chat.handoff.working-directory";
    public static final String CONFIG_SCOPE = "kompile.chat.handoff.config-scope";

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

    public static List<String> jvmArguments(Path directory, boolean global) throws IOException {
        return List.of("-D" + WORKING_DIRECTORY + "=" + directory.toRealPath(),
                "-D" + CONFIG_SCOPE + "=" + (global ? "global" : "project"));
    }
}
