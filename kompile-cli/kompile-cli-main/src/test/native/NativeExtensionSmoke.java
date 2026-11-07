package ai.kompile.cli.main.extensions.nativefixture;

import ai.kompile.cli.main.extensions.CliExtensionDiscovery;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deliberately has no application, graph, model, Jackson or numerical dependencies. */
public final class NativeExtensionSmoke {
    public static void main(String[] args) {
        var catalog = CliExtensionDiscovery.discover();
        if (catalog.extensions().size() != 1
                || !catalog.ownerOfTool("fixture_echo").orElseThrow().id().equals("fixture")
                || !catalog.missingCapabilities("fixture_echo", "learn").equals(Set.of("graph.learning"))) {
            throw new AssertionError("Extension metadata was not discovered correctly");
        }
        CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("native-smoke"));
        CliExtensionDiscovery.registerCommands(root, catalog);
        if (root.execute("hello") != 0 || HelloHandler.calls != 1) {
            throw new AssertionError("Contributed extension handler did not execute");
        }
        System.out.println("NATIVE_EXTENSION_SMOKE_OK");
    }

    public static final class HelloHandler implements java.util.concurrent.Callable<Integer> {
        static int calls;
        @Override public Integer call() { calls++; return 0; }
    }

    public static final class FixtureExtension implements CliExtension {
        @Override
        public CliExtensionDescriptor descriptor() {
            return new CliExtensionDescriptor("fixture", "1.0", 1, Set.of("fixture.echo"), Set.of(),
                    List.of(new CliToolDescriptor("fixture_echo", Set.of("fixture.echo"),
                            Map.of("learn", Set.of("graph.learning")))), Set.of("hello"));
        }

        @Override
        public void registerCommands(CommandLine root) {
            root.addSubcommand("hello", new CommandLine(CommandLine.Model.CommandSpec
                    .wrapWithoutInspection(new HelloHandler()).name("hello")));
        }
    }
}
