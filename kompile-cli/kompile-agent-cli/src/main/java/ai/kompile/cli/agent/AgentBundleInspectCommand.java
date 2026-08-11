package ai.kompile.cli.agent;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "inspect", description = "Inspect an agent bundle manifest and resources.", mixinStandardHelpOptions = true)
public final class AgentBundleInspectCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Bundle directory or .kagent archive")
    private Path bundle;
    @CommandLine.Option(names = "--resolve-tools", description = "Discover declared MCP tools")
    private boolean resolveTools;
    @CommandLine.Option(names = "--json", description = "Emit JSON")
    private boolean json;

    @Override
    public Integer call() {
        try {
            AgentBundleLoader loader = new AgentBundleLoader();
            AgentBundleLoader.LoadedBundle loaded = loader.load(bundle);
            try {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("source", loaded.source().toString());
                output.put("archive", loaded.archive());
                output.put("manifest", loaded.manifest());
                output.put("entries", loaded.entries());
                if (resolveTools) output.put("mcpTools", loaded.discoverTools());
                ObjectMapper mapper = JsonUtils.standardMapper();
                if (json) System.out.println(mapper.writeValueAsString(output));
                else {
                    System.out.println("Agent: " + loaded.manifest().path("metadata").path("name").asText());
                    System.out.println("Engine: " + loaded.manifest().path("engine").asText("cli-loop"));
                    System.out.println("Entries: " + loaded.entries().size());
                    if (resolveTools) ((java.util.List<?>) output.get("mcpTools")).forEach(tool -> System.out.println("  " + tool));
                }
                return 0;
            } finally {
                loaded.close();
            }
        } catch (Exception e) {
            System.err.println("Could not inspect agent bundle: " + e.getMessage());
            return 1;
        }
    }
}
