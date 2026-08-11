package ai.kompile.cli.agent;

import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "run", description = "Run a packaged agent through the Kompile headless harness.", mixinStandardHelpOptions = true)
public final class AgentBundleRunCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Bundle directory or .kagent archive")
    private Path bundle;
    @CommandLine.Parameters(index = "1..", description = "Prompt text")
    private List<String> prompt = new ArrayList<>();
    @CommandLine.Option(names = "--timeout", description = "Timeout in seconds", defaultValue = "0")
    private long timeout;

    @Override
    public Integer call() {
        AgentBundleLoader.LoadedBundle loaded = null;
        try {
            AgentBundleLoader loader = new AgentBundleLoader();
            loaded = loader.load(bundle);
            Path root = loaded.materialize();
            String request = String.join(" ", prompt).trim();
            if (request.isBlank()) request = readStdin();
            if (request.isBlank()) throw new IllegalArgumentException("A prompt is required (argument or stdin)");
            return AgentBundleExecutor.run(loaded.manifest(), root, request, timeout);
        } catch (Exception e) {
            System.err.println("Could not run agent bundle: " + e.getMessage());
            return 1;
        } finally {
            if (loaded != null) loaded.close();
        }
    }

    private static String readStdin() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b).trim();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read prompt from stdin", e);
        }
    }
}
