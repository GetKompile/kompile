package ai.kompile.cli.agent;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "validate", description = "Validate an agent bundle and its declared resources.", mixinStandardHelpOptions = true)
public final class AgentBundleValidateCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Bundle directory or .kagent archive")
    private Path bundle;
    @CommandLine.Option(names = "--check-tools", description = "Connect to declared MCP servers and list tools")
    private boolean checkTools;

    @Override
    public Integer call() {
        try {
            AgentBundleLoader loader = new AgentBundleLoader();
            AgentBundleLoader.LoadedBundle loaded = loader.load(bundle);
            try {
                if (checkTools) loaded.discoverTools().forEach(tool -> System.out.println("MCP " + tool));
                System.out.println("Valid agent bundle: " + loaded.manifest().path("metadata").path("name").asText());
                return 0;
            } finally {
                loaded.close();
            }
        } catch (Exception e) {
            System.err.println("Invalid agent bundle: " + e.getMessage());
            return 1;
        }
    }
}
