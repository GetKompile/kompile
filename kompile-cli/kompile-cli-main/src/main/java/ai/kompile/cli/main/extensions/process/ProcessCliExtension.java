package ai.kompile.cli.main.extensions.process;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.chat.mcp.ProcessExtensionMcpBridge;
import ai.kompile.cli.main.extensions.tools.CliToolExtension;
import ai.kompile.cli.main.extensions.tools.ToolContribution;
import ai.kompile.cli.main.extensions.tools.ToolExtensionContext;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/** Installed process packages share metadata/ownership rules with compiled extensions. */
public final class ProcessCliExtension implements CliToolExtension {
    private final ProcessExtensionStore.InstalledExtension installed;

    public ProcessCliExtension(ProcessExtensionStore.InstalledExtension installed) {
        this.installed = Objects.requireNonNull(installed, "installed");
    }

    @Override public CliExtensionDescriptor descriptor() { return installed.manifest().descriptor(); }

    @Override
    public List<ToolContribution> createTools(ToolExtensionContext context) {
        String javaExecutable = JavaRuntimeLocator.javaExecutable();
        return installed.manifest().tools().stream()
                .map(tool -> new ToolContribution(ProcessExtensionMcpBridge.tool(
                        installed, tool, context.objectMapper(), javaExecutable), false))
                .toList();
    }

    @Override
    public void registerCommands(CommandLine root) {
        installed.manifest().commands().forEach((name, launch) -> {
            Invocation invocation = new Invocation(installed, launch);
            CommandLine.Model.CommandSpec spec = CommandLine.Model.CommandSpec.wrapWithoutInspection(invocation)
                    .name(name);
            spec.usageMessage().description("Command from trusted process extension " + installed.manifest().id());
            invocation.arguments = CommandLine.Model.PositionalParamSpec.builder()
                    .index("0..*").arity("0..*").type(String[].class).paramLabel("ARGS").build();
            spec.addPositional(invocation.arguments);
            spec.parser().unmatchedOptionsArePositionalParams(true);
            invocation.spec = spec;
            root.addSubcommand(name, new CommandLine(spec));
        });
    }

    private static final class Invocation implements Callable<Integer> {
        private final ProcessExtensionStore.InstalledExtension installed;
        private final ProcessExtensionManifest.Launch launch;
        private CommandLine.Model.PositionalParamSpec arguments;
        private CommandLine.Model.CommandSpec spec;

        private Invocation(ProcessExtensionStore.InstalledExtension installed, ProcessExtensionManifest.Launch launch) {
            this.installed = installed;
            this.launch = launch;
        }

        @Override
        public Integer call() throws Exception {
            List<String> command = new ArrayList<>(launch.command(installed.directory(), JavaRuntimeLocator.javaExecutable()));
            String[] remaining = arguments.getValue();
            if (remaining != null) command.addAll(List.of(remaining));
            try {
                return ProcessExtensionCommandRunner.run(command,
                        Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                        Map.of("KOMPILE_EXTENSION_ID", installed.manifest().id(),
                                "KOMPILE_EXTENSION_VERSION", installed.manifest().version(),
                                "KOMPILE_EXTENSION_ROOT", installed.directory().toString()),
                        installed.manifest().timeoutSeconds());
            } catch (TimeoutException failure) {
                spec.commandLine().getErr().println(failure.getMessage());
                return 124;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return 130;
            }
        }
    }
}
