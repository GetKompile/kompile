package ai.kompile.cli.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process-boundary adapter to the existing native `kompile exec` harness. */
final class AgentBundleExecutor {
    private AgentBundleExecutor() { }

    static int run(JsonNode manifest, Path workspace, String prompt, long timeoutSeconds)
            throws IOException, InterruptedException {
        String engine = manifest.path("engine").asText("cli-loop");
        List<String> command = commandFor(manifest, engine, workspace, prompt);
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.directory(workspace.toFile());
        builder.environment().put("KOMPILE_AGENT_PROMPT", prompt);
        Process process = builder.start();
        if (timeoutSeconds > 0 && !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 124;
        }
        return process.waitFor();
    }

    private static List<String> commandFor(JsonNode manifest, String engine, Path workspace, String prompt) {
        if ("external-cli".equals(engine)) {
            JsonNode configured = manifest.path("command");
            List<String> command = new ArrayList<>();
            if (configured.isArray()) configured.forEach(node -> command.add(node.asText()));
            else if (configured.isTextual()) command.add(configured.asText());
            if (command.isEmpty()) throw new IllegalArgumentException(
                    "external-cli bundles must declare a command array or string");
            boolean substituted = false;
            for (int i = 0; i < command.size(); i++) {
                if ("{prompt}".equals(command.get(i))) {
                    command.set(i, prompt);
                    substituted = true;
                }
            }
            if (!substituted) command.add(prompt);
            return command;
        }
        String cli = System.getenv().getOrDefault("KOMPILE_CLI", "kompile");
        return new ArrayList<>(List.of(cli, "exec", "--cwd", workspace.toString(), prompt));
    }
}
