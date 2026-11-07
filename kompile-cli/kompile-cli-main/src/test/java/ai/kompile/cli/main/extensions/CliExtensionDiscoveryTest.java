package ai.kompile.cli.main.extensions;

import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CliExtensionDiscoveryTest {
    @Test
    void commandsAndAliasesAttachOnceAndExecute() {
        CliExtension extension = extension("sample", Set.of("greet", "hi"),
                root -> root.addSubcommand("greet", new Greeting()));
        CommandLine root = root();
        CliExtensionDiscovery.registerCommands(root, catalog(extension));
        assertSame(root.getSubcommands().get("greet"), root.getSubcommands().get("hi"));
        assertEquals(7, root.execute("hi"));
    }

    @Test
    void explicitlyRegisteredNamesForOneCommandBothSurviveAttachment() {
        CliExtension extension = extension("sample", Set.of("greet", "hi"), root -> {
            CommandLine command = new CommandLine(new Plain());
            root.addSubcommand("greet", command);
            root.addSubcommand("hi", command);
        });
        CommandLine root = root();
        CliExtensionDiscovery.registerCommands(root, catalog(extension));
        assertSame(root.getSubcommands().get("greet"), root.getSubcommands().get("hi"));
        assertEquals(0, root.execute("greet"));
        assertEquals(0, root.execute("hi"));
    }

    @Test
    void existingCommandConflictFailsBeforeAnyExtensionCodeRuns() {
        AtomicInteger calls = new AtomicInteger();
        CliExtension extension = extension("sample", Set.of("existing"), root -> calls.incrementAndGet());
        CommandLine root = root();
        root.addSubcommand("existing", new Greeting());
        var existing = root.getSubcommands().get("existing");
        assertThrows(IllegalArgumentException.class,
                () -> CliExtensionDiscovery.registerCommands(root, catalog(extension)));
        assertEquals(0, calls.get());
        assertSame(existing, root.getSubcommands().get("existing"));
    }

    @Test
    void undeclaredContributionsNeverReachLiveRoot() {
        CliExtension extension = extension("sample", Set.of("declared"),
                root -> root.addSubcommand("other", new Greeting()));
        CommandLine root = root();
        assertThrows(IllegalArgumentException.class,
                () -> CliExtensionDiscovery.registerCommands(root, catalog(extension)));
        assertTrue(root.getSubcommands().isEmpty());
    }

    @Test
    void laterFailureDoesNotPartiallyAttachEarlierCommands() {
        CliExtension first = extension("first", Set.of("first"),
                root -> root.addSubcommand("first", new Plain()));
        AtomicInteger calls = new AtomicInteger();
        CliExtension later = extension("later", Set.of("later"), root -> {
            calls.incrementAndGet();
            throw new IllegalStateException("broken extension");
        });
        CommandLine root = root();
        assertThrows(IllegalStateException.class,
                () -> CliExtensionDiscovery.registerCommands(root, catalog(first, later)));
        assertEquals(1, calls.get());
        assertTrue(root.getSubcommands().isEmpty());
    }

    @Test
    void absentDeclaredCommandIsAnError() {
        CliExtension extension = extension("sample", Set.of("missing"), root -> { });
        assertThrows(IllegalArgumentException.class,
                () -> CliExtensionDiscovery.registerCommands(root(), catalog(extension)));
    }

    private static CommandLine root() {
        return new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
    }

    private static CliExtensionCatalog catalog(CliExtension... extensions) {
        return new CliExtensionCatalog(List.of(extensions), Set.of());
    }

    private static CliExtension extension(String id, Set<String> commands, Consumer<CommandLine> registration) {
        return new CliExtension() {
            private final CliExtensionDescriptor descriptor = new CliExtensionDescriptor(
                    id, "1.0", 1, Set.of(), Set.of(), List.of(), commands);
            @Override public CliExtensionDescriptor descriptor() { return descriptor; }
            @Override public void registerCommands(CommandLine root) { registration.accept(root); }
        };
    }

    @CommandLine.Command(name = "plain")
    static final class Plain implements Callable<Integer> {
        @Override public Integer call() { return 0; }
    }

    @CommandLine.Command(name = "greet", aliases = "hi")
    static final class Greeting implements Callable<Integer> {
        @Override public Integer call() { return 7; }
    }
}
