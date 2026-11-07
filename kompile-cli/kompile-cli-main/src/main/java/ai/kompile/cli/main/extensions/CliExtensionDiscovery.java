package ai.kompile.cli.main.extensions;

import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/** Build/classpath composition, not runtime loading of unknown Java code in a native image. */
public final class CliExtensionDiscovery {
    private CliExtensionDiscovery() { }

    public static CliExtensionCatalog discover() {
        return discover(CliExtension.class.getClassLoader());
    }

    public static CliExtensionCatalog discover(ClassLoader classLoader) {
        return new CliExtensionCatalog(providers(classLoader), Set.of());
    }

    /** Unvalidated providers for a host that composes additional process descriptors before validation. */
    public static List<CliExtension> providers() {
        return providers(CliExtension.class.getClassLoader());
    }

    public static List<CliExtension> providers(ClassLoader classLoader) {
        List<CliExtension> extensions = new ArrayList<>();
        ServiceLoader.load(CliExtension.class, Objects.requireNonNull(classLoader, "classLoader"))
                .forEach(extensions::add);
        return List.copyOf(extensions);
    }

    /**
     * Validate every contribution before attaching any commands to the live root.
     * New-style extensions receive a staging root and may contribute only their declared
     * top-level names (including aliases). Legacy registrars retain their existing contract.
     */
    public static void registerCommands(CommandLine root, CliExtensionCatalog catalog) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(catalog, "catalog");
        for (CliExtension extension : catalog.extensions()) {
            for (String name : catalog.descriptorOf(extension).commands()) {
                if (root.getSubcommands().containsKey(name)) {
                    throw new IllegalArgumentException("Extension command conflicts with existing command: " + name);
                }
            }
        }

        Map<String, CommandLine> additions = new LinkedHashMap<>();
        for (CliExtension extension : catalog.extensions()) {
            CliExtensionDescriptor descriptor = catalog.descriptorOf(extension);
            CommandLine staging = new CommandLine(CommandLine.Model.CommandSpec.create().name("extension-staging"));
            extension.registerCommands(staging);
            if (!staging.getSubcommands().keySet().equals(descriptor.commands())) {
                throw new IllegalArgumentException("Extension " + descriptor.id()
                        + " must register exactly its declared command names: " + descriptor.commands());
            }
            for (Map.Entry<String, CommandLine> entry : staging.getSubcommands().entrySet()) {
                if (additions.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Duplicate extension command: " + entry.getKey());
                }
            }
        }
        // Spec aliases are added automatically; explicitly registered aliases may
        // instead be separate validated mappings to the same CommandLine instance.
        for (Map.Entry<String, CommandLine> entry : additions.entrySet()) {
            if (!root.getSubcommands().containsKey(entry.getKey())) {
                root.addSubcommand(entry.getKey(), entry.getValue());
            }
        }
    }
}
