package ai.kompile.cli.main.extensions;

import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.main.extensions.process.ProcessCliExtension;
import ai.kompile.cli.main.extensions.process.ProcessExtensionManifest;
import ai.kompile.cli.main.extensions.process.ProcessExtensionStore;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.CliToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Inspect composed extensions without constructing their tools or loading models. */
@CommandLine.Command(name = "extension", mixinStandardHelpOptions = true,
        subcommands = {ExtensionCommand.Install.class, ExtensionCommand.Remove.class, ExtensionCommand.Inspect.class},
        description = "Inspect composed extensions or manage trusted process packages (not model readiness).")
public final class ExtensionCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "ID",
            description = "Optional extension id to inspect.")
    private String extensionId;

    @CommandLine.Option(names = "--json", description = "Print the extension ownership/capability catalog as JSON.")
    private boolean json;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private final Supplier<CliExtensionCatalog> catalogSupplier;
    private final Supplier<ProcessExtensionStore> storeSupplier;

    public ExtensionCommand() {
        this(InstalledCliExtensions::discover, InstalledCliExtensions::store);
    }

    public ExtensionCommand(Supplier<CliExtensionCatalog> catalogSupplier) {
        this(catalogSupplier, InstalledCliExtensions::store);
    }

    public ExtensionCommand(Supplier<CliExtensionCatalog> catalogSupplier,
                            Supplier<ProcessExtensionStore> storeSupplier) {
        this.catalogSupplier = java.util.Objects.requireNonNull(catalogSupplier, "catalogSupplier");
        this.storeSupplier = java.util.Objects.requireNonNull(storeSupplier, "storeSupplier");
    }

    @Override
    public Integer call() {
        CliExtensionCatalog catalog = catalogSupplier.get();
        List<CliExtensionDescriptor> descriptors = catalog.descriptors().stream()
                .filter(d -> extensionId == null || d.id().equals(extensionId))
                .toList();
        if (extensionId != null && descriptors.isEmpty()) {
            spec.commandLine().getErr().println("Unknown composed extension: " + extensionId);
            return 2;
        }
        PrintWriter out = spec.commandLine().getOut();
        if (json) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode result = mapper.createObjectNode();
            result.put("schemaVersion", "kompile-cli-extensions/v1");
            result.put("composition", catalog.extensions().stream().anyMatch(ProcessCliExtension.class::isInstance)
                    ? "compiled-and-installed-process" : "build-classpath");
            result.put("runtimeReadinessChecked", false);
            ArrayNode entries = result.putArray("extensions");
            descriptors.forEach(d -> entries.add(describe(mapper, d)));
            out.println(result);
        } else {
            out.println("Composed CLI extensions (capabilities are not model/runtime readiness):");
            for (CliExtensionDescriptor d : descriptors) {
                out.println(d.id() + " " + d.version() + " (API " + d.apiVersion() + ")");
                out.println("  provides: " + String.join(", ", d.provides()));
                if (!d.requires().isEmpty()) out.println("  requires: " + String.join(", ", d.requires()));
                if (!d.commands().isEmpty()) out.println("  commands: " + String.join(", ", d.commands()));
                out.println("  tools: " + String.join(", ", d.tools().stream().map(CliToolDescriptor::id).toList()));
            }
        }
        out.flush();
        return 0;
    }

    @CommandLine.Command(name = "install", mixinStandardHelpOptions = true,
            description = "Trust and install a local process-extension directory. No code runs during installation.")
    static final class Install implements Callable<Integer> {
        @CommandLine.ParentCommand private ExtensionCommand parent;
        @CommandLine.Parameters(index = "0", paramLabel = "DIRECTORY") private Path source;
        @CommandLine.Option(names = "--replace", description = "Select a new immutable version of an already installed id.")
        private boolean replace;

        @Override public Integer call() throws Exception {
            Path manifestPath = source.toAbsolutePath().normalize().resolve("extension.json");
            if (!Files.isRegularFile(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifestPath) > 1_048_576) {
                throw new IllegalArgumentException("A regular extension.json of at most 1 MiB is required");
            }
            ProcessExtensionManifest manifest;
            try (var input = Files.newInputStream(manifestPath)) {
                manifest = ProcessExtensionManifest.parse(new ObjectMapper().readTree(input));
            }
            ProcessExtensionStore store = parent.storeSupplier.get();
            List<CliExtension> compiled = CliExtensionDiscovery.providers();
            List<CliExtension> selected = new ArrayList<>(compiled);
            for (var installed : store.installed()) {
                if (!installed.manifest().id().equals(manifest.id())) selected.add(new ProcessCliExtension(installed));
            }
            selected.add(new ProcessCliExtension(new ProcessExtensionStore.InstalledExtension(
                    manifest, source.toAbsolutePath().normalize())));
            // Preflight identity/capability and cross-package command ownership without launching code.
            CliExtensionCatalog proposed = new CliExtensionCatalog(selected, Set.of());
            CommandLine root = parent.spec.commandLine().getParent();
            Set<String> reserved = root == null ? Set.of("extension") : root.getSubcommands().keySet();
            for (String name : manifest.commands().keySet()) {
                if (reserved.contains(name)) throw new IllegalArgumentException("Extension command conflicts with host command: " + name);
            }
            var installed = store.install(source, replace,
                    compiled.stream().map(proposed::descriptorOf).toList(), Set.copyOf(reserved));
            parent.spec.commandLine().getOut().println("Installed trusted process extension "
                    + installed.manifest().id() + " " + installed.manifest().version()
                    + "; available in new sessions.");
            return 0;
        }
    }

    @CommandLine.Command(name = "remove", mixinStandardHelpOptions = true,
            description = "Deactivate a process extension; immutable payloads are retained for running sessions.")
    static final class Remove implements Callable<Integer> {
        @CommandLine.ParentCommand private ExtensionCommand parent;
        @CommandLine.Parameters(index = "0", paramLabel = "ID") private String id;
        @Override public Integer call() throws Exception {
            boolean removed = parent.storeSupplier.get().remove(id);
            parent.spec.commandLine().getOut().println(removed ? "Deactivated process extension " + id
                    : "Process extension is not installed: " + id);
            return removed ? 0 : 2;
        }
    }

    @CommandLine.Command(name = "inspect", mixinStandardHelpOptions = true,
            description = "Inspect an extension by id, including ids that match management command names.")
    static final class Inspect implements Callable<Integer> {
        @CommandLine.ParentCommand private ExtensionCommand parent;
        @CommandLine.Parameters(index = "0", paramLabel = "ID") private String id;
        @CommandLine.Option(names = "--json") private boolean json;
        @Override public Integer call() {
            parent.extensionId = id;
            parent.json = json;
            return parent.call();
        }
    }

    private static ObjectNode describe(ObjectMapper mapper, CliExtensionDescriptor descriptor) {
        ObjectNode result = mapper.createObjectNode();
        result.put("id", descriptor.id());
        result.put("version", descriptor.version());
        result.put("apiVersion", descriptor.apiVersion());
        strings(result.putArray("provides"), descriptor.provides());
        strings(result.putArray("requires"), descriptor.requires());
        strings(result.putArray("commands"), descriptor.commands());
        ArrayNode tools = result.putArray("tools");
        for (CliToolDescriptor tool : descriptor.tools()) {
            ObjectNode entry = tools.addObject();
            entry.put("id", tool.id());
            strings(entry.putArray("requires"), tool.requires());
            ObjectNode actions = entry.putObject("actionRequirements");
            tool.actionRequirements().forEach((action, requirements) ->
                    strings(actions.putArray(action), requirements));
            ObjectNode conditional = entry.putObject("conditionalCapabilities");
            tool.conditionalCapabilities().forEach((action, capabilities) ->
                    strings(conditional.putArray(action), capabilities));
        }
        return result;
    }

    private static void strings(ArrayNode target, Set<String> values) {
        values.forEach(target::add);
    }
}
