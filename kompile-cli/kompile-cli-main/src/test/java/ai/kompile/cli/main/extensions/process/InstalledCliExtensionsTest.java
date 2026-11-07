package ai.kompile.cli.main.extensions.process;

import ai.kompile.cli.main.extensions.ExtensionCommand;
import ai.kompile.cli.main.extensions.InstalledCliExtensions;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InstalledCliExtensionsTest {
    @TempDir Path temp;

    @Test
    void composedCatalogReadsEachProviderOnceAndDoesNotExecuteProcess() throws Exception {
        Path receipt = temp.resolve("receipt");
        Path source = ProcessCliExtensionTest.writePackage(temp.resolve("source"), "demo", "1.0", "demo-command", receipt, 10);
        var store = new ProcessExtensionStore(temp.resolve("store"), new ObjectMapper());
        store.install(source, false);
        AtomicInteger reads = new AtomicInteger();
        CliExtension compiled = () -> {
            if (reads.incrementAndGet() != 1) throw new AssertionError("Provider reread");
            return new CliExtensionDescriptor("compiled", "1", 1, Set.of(), Set.of(), List.of(), Set.of());
        };
        var catalog = InstalledCliExtensions.discover(store, List.of(compiled));
        assertEquals(2, catalog.extensions().size());
        assertEquals(1, reads.get());
        assertFalse(Files.exists(receipt));
    }

    @Test
    void managementInstallsInspectsAndDeactivatesWithoutRunningCode() throws Exception {
        Path receipt = temp.resolve("receipt");
        Path source = ProcessCliExtensionTest.writePackage(temp.resolve("source"), "demo", "1.0", "demo-command", receipt, 10);
        var store = new ProcessExtensionStore(temp.resolve("store"), new ObjectMapper());
        StringWriter output = new StringWriter();
        CommandLine root = management(store, output);
        assertEquals(0, root.execute("extension", "install", source.toString()));
        assertTrue(store.find("demo").isPresent());
        assertFalse(Files.exists(receipt));
        output.getBuffer().setLength(0);
        assertEquals(0, root.execute("extension", "inspect", "demo", "--json"));
        var json = new ObjectMapper().readTree(output.toString());
        assertEquals("compiled-and-installed-process", json.path("composition").asText());
        assertEquals("demo", json.path("extensions").get(0).path("id").asText());
        Path payload = store.find("demo").orElseThrow().directory();
        assertEquals(0, root.execute("extension", "remove", "demo"));
        assertTrue(store.installed().isEmpty());
        assertTrue(Files.isRegularFile(payload.resolve("worker.jar")), "Running sessions retain immutable payloads");
    }

    @Test
    void hostCommandCollisionIsRejectedBeforeSelectingPackage() throws Exception {
        Path source = ProcessCliExtensionTest.writePackage(temp.resolve("source"), "demo", "1.0", "reserved", temp.resolve("receipt"), 10);
        var store = new ProcessExtensionStore(temp.resolve("store"), new ObjectMapper());
        CommandLine root = management(store, new StringWriter());
        root.addSubcommand("reserved", new CommandLine(CommandLine.Model.CommandSpec.create().name("reserved")));
        assertNotEquals(0, root.execute("extension", "install", source.toString()));
        assertTrue(store.installed().isEmpty());
    }

    @Test
    void removalDoesNotNeedToLoadCorruptedPackageMetadata() throws Exception {
        Path source = ProcessCliExtensionTest.writePackage(temp.resolve("source"), "broken", "1.0", "broken-command", temp.resolve("receipt"), 10);
        var store = new ProcessExtensionStore(temp.resolve("store"), new ObjectMapper());
        var installed = store.install(source, false);
        Files.writeString(installed.directory().resolve("extension.json"), "not json");
        assertThrows(Exception.class, () -> InstalledCliExtensions.discover(store, List.of()));
        assertEquals(0, management(store, new StringWriter()).execute("extension", "remove", "broken"));
        assertTrue(store.installed().isEmpty());
    }

    private static CommandLine management(ProcessExtensionStore store, StringWriter output) {
        var manager = new ExtensionCommand(() -> InstalledCliExtensions.discover(store, List.of()), () -> store);
        CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        root.addSubcommand("extension", manager);
        root.setOut(new PrintWriter(output));
        root.setErr(new PrintWriter(new StringWriter()));
        return root;
    }
}
