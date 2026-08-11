package ai.kompile.cli.agent;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "tools", description = "Discover tools exposed by an agent bundle's MCP servers.", mixinStandardHelpOptions = true)
public final class AgentBundleToolsCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Bundle directory or .kagent archive")
    private Path bundle;
    @CommandLine.Option(names = "--json", description = "Emit JSON")
    private boolean json;

    @Override
    public Integer call() {
        try {
            AgentBundleLoader loader = new AgentBundleLoader();
            AgentBundleLoader.LoadedBundle loaded = loader.load(bundle);
            try {
                var tools = loaded.discoverTools();
                if (json) {
                    System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tools));
                } else if (tools.isEmpty()) {
                    System.out.println("No MCP servers are declared in this bundle.");
                } else {
                    tools.forEach(System.out::println);
                }
                return tools.stream().anyMatch(tool -> tool.contains("[unavailable:")) ? 1 : 0;
            } finally {
                loaded.close();
            }
        } catch (Exception e) {
            System.err.println("Could not discover MCP tools: " + e.getMessage());
            return 1;
        }
    }
}
