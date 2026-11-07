package ai.kompile.cli.main.extensions.nativefixture;

import ai.kompile.cli.main.extensions.process.ProcessExtensionCommandRunner;
import ai.kompile.cli.main.extensions.process.ProcessExtensionStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The package is created after this host image is built; its target is never host self-reexec. */
public final class NativeProcessExtensionSmoke {
    public static void main(String[] args) throws Exception {
        Path receipt = Path.of(args[2]);
        var store = new ProcessExtensionStore(Path.of(args[0]), new ObjectMapper());
        var installed = store.install(Path.of(args[1]), false);
        if (Files.exists(receipt)) throw new AssertionError("Installation executed extension code");
        var launch = installed.manifest().commands().get("native-plugin");
        var command = launch.command(installed.directory(), "unused-java");
        if (!Path.of(command.get(0)).startsWith(installed.directory())) {
            throw new AssertionError("Extension target did not resolve to the managed package");
        }
        int exit = ProcessExtensionCommandRunner.run(command, Path.of(args[1]),
                Map.of("KOMPILE_EXTENSION_ID", installed.manifest().id(),
                        "KOMPILE_EXTENSION_VERSION", installed.manifest().version(),
                        "KOMPILE_EXTENSION_ROOT", installed.directory().toString()), 10);
        if (exit != 0 || !Files.readString(receipt).equals("native-demo\n1.0\n" + installed.directory())) {
            throw new AssertionError("Independent native process extension did not execute");
        }
        if (!store.remove("native-demo") || !store.installed().isEmpty()) throw new AssertionError("Deactivation failed");
        System.out.println("NATIVE_PROCESS_EXTENSION_SMOKE_OK");
    }
}
