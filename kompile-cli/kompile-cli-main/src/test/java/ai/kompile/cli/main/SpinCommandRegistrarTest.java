/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main;

import ai.kompile.cli.plugin.api.CliCommandRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinCommandRegistrarTest {
    @TempDir
    Path temp;

    @Test
    void serviceLoaderPublishesSpinRegistrar() {
        assertTrue(ServiceLoader.load(CliCommandRegistrar.class).stream()
                .map(ServiceLoader.Provider::type)
                .anyMatch(SpinCommandRegistrar.class::equals));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void topLevelSpinDelegatesToDistributionAgentCliWithPrefix() throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Files.createDirectories(temp.resolve("lib"));
        Path argsCapture = temp.resolve("args.txt");
        Path rootCapture = temp.resolve("root.txt");
        Path executable = bin.resolve("kompile-agent");
        Files.writeString(executable, """
                #!/usr/bin/env sh
                printf '%%s\n' "$@" > '%s'
                printf '%%s' "$KOMPILE_INSTALL_DIR" > '%s'
                """.formatted(
                argsCapture.toString().replace("'", "'\\''"),
                rootCapture.toString().replace("'", "'\\''")));
        assertTrue(executable.toFile().setExecutable(true));

        String previous = System.getProperty("kompile.install.dir");
        try {
            System.setProperty("kompile.install.dir", temp.toString());
            CommandLine root = new CommandLine(new MainCommand());
            new SpinCommandRegistrar().registerCommands(root);

            int exit = root.execute("spin", "build", "demo", "--runtime-dir", "runtime");

            assertEquals(0, exit);
            assertEquals(List.of("spin", "build", "demo", "--runtime-dir", "runtime"),
                    Files.readAllLines(argsCapture));
            assertEquals(temp.toAbsolutePath().toString(), Files.readString(rootCapture));

            assertEquals(0, root.execute("spin", "--help"));
            assertEquals(List.of("spin", "--help"), Files.readAllLines(argsCapture));
        } finally {
            if (previous == null) System.clearProperty("kompile.install.dir");
            else System.setProperty("kompile.install.dir", previous);
        }
    }
}
