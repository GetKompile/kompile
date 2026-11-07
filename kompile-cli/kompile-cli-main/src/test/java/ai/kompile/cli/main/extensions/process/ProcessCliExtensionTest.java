package ai.kompile.cli.main.extensions.process;

import ai.kompile.cli.main.extensions.CliExtensionDiscovery;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ProcessCliExtensionTest {
    @TempDir Path temp;

    @Test
    void installedCommandRunsOwnJarAndPreservesLiteralArguments() throws Exception {
        Path receipt = temp.resolve("receipt.txt");
        Path source = writePackage(temp.resolve("source"), "demo", "1.0", "demo-command", receipt, 10);
        var store = new ProcessExtensionStore(temp.resolve("installed"), new ObjectMapper());
        var installed = store.install(source, false);
        var extension = new ProcessCliExtension(installed);
        var catalog = new CliExtensionCatalog(List.of(extension), Set.of());
        CommandLine root = root();
        CliExtensionDiscovery.registerCommands(root, catalog);
        assertFalse(Files.exists(receipt), "Discovery/registration must not execute extension code");
        assertEquals(0, root.execute("demo-command", "--flag", "two words", ";not-a-shell"));
        List<String> output = Files.readAllLines(receipt);
        assertEquals("demo", output.get(1));
        assertEquals(installed.directory().toString(), output.get(2));
        assertEquals(List.of("--flag", "two words", ";not-a-shell"), output.subList(3, output.size()));
        assertEquals(7, root.execute("demo-command", "--fail"));
    }

    @Test
    void timedOutCommandTerminatesItsOwnedProcess() throws Exception {
        Path receipt = temp.resolve("timeout.txt");
        Path source = writePackage(temp.resolve("source"), "timeout", "1.0", "wait-command", receipt, 1);
        var store = new ProcessExtensionStore(temp.resolve("installed"), new ObjectMapper());
        var extension = new ProcessCliExtension(store.install(source, false));
        CommandLine root = root();
        CliExtensionDiscovery.registerCommands(root, new CliExtensionCatalog(List.of(extension), Set.of()));
        assertEquals(124, root.execute("wait-command", "--hang"));
        long pid = Long.parseLong(Files.readAllLines(receipt).get(0));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void normalCommandCompletionAlsoCleansObservedDescendants() throws Exception {
        Path receipt = temp.resolve("descendant.txt");
        Path source = writePackage(temp.resolve("source"), "descendant", "1.0", "child-command", receipt, 10);
        var store = new ProcessExtensionStore(temp.resolve("installed"), new ObjectMapper());
        CommandLine root = root();
        CliExtensionDiscovery.registerCommands(root, new CliExtensionCatalog(
                List.of(new ProcessCliExtension(store.install(source, false))), Set.of()));
        assertEquals(0, root.execute("child-command", "--descendant"));
        long pid = Long.parseLong(Files.readAllLines(receipt.resolveSibling("descendant.txt.child")).get(0));
        try {
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    static Path writePackage(Path source, String id, String version, String command, Path receipt, int timeout)
            throws Exception {
        Files.createDirectories(source);
        Manifest jarManifest = new Manifest();
        jarManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        jarManifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Worker.class.getName());
        String classResource = Worker.class.getName().replace('.', '/') + ".class";
        try (var jar = new JarOutputStream(Files.newOutputStream(source.resolve("worker.jar")), jarManifest);
             var bytes = Worker.class.getClassLoader().getResourceAsStream(classResource)) {
            assertNotNull(bytes);
            jar.putNextEntry(new JarEntry(classResource));
            bytes.transferTo(jar);
            jar.closeEntry();
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1).put("apiVersion", 1).put("id", id).put("version", version);
        manifest.put("timeoutSeconds", timeout);
        ObjectNode launch = manifest.putObject("commands").putObject(command);
        launch.put("kind", "jar").put("path", "worker.jar").putArray("args").add(receipt.toString());
        Files.writeString(source.resolve("extension.json"), manifest.toString());
        return source;
    }

    private static CommandLine root() {
        return new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            List<String> values = new java.util.ArrayList<>();
            values.add(Long.toString(ProcessHandle.current().pid()));
            values.add(System.getenv("KOMPILE_EXTENSION_ID"));
            values.add(System.getenv("KOMPILE_EXTENSION_ROOT"));
            values.addAll(Arrays.asList(args).subList(1, args.length));
            Files.write(Path.of(args[0]), values);
            if (Arrays.asList(args).contains("--descendant")) {
                Path childReceipt = Path.of(args[0] + ".child");
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar", System.getProperty("java.class.path"), childReceipt.toString(), "--child").inheritIO().start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(childReceipt) && System.nanoTime() < deadline) Thread.sleep(10);
                if (!Files.exists(childReceipt)) throw new IllegalStateException("Child did not start");
            }
            if (Arrays.asList(args).contains("--hang") || Arrays.asList(args).contains("--child")) {
                new java.util.concurrent.CountDownLatch(1).await();
            }
            if (Arrays.asList(args).contains("--fail")) System.exit(7);
        }
    }
}
