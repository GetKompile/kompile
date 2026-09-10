/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.plugin.api.CliCommandRegistrar;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Registers {@code kompile spin} as the product-facing alias for {@code kompile-agent spin}. */
public final class SpinCommandRegistrar implements CliCommandRegistrar {
    @Override
    public void registerCommands(CommandLine mainAppCommandLine) {
        if (!mainAppCommandLine.getSubcommands().containsKey("spin")) {
            mainAppCommandLine.addSubcommand("spin", new SpinDelegate());
        }
    }

    @CommandLine.Command(name = "spin",
            description = "Build and run installable Kompile spins with custom prompts, MCP tools, and models.")
    static final class SpinDelegate implements Callable<Integer> {
        @CommandLine.Unmatched
        private String[] arguments;

        @Override
        public Integer call() throws Exception {
            ComponentRegistry registry = new ComponentRegistry();
            File binary = registry.getDistributionBinaryPath("kompile-agent");
            if (binary != null) return executeBinary(binary);

            File jar = registry.getDistributionJarPath("kompile-agent");
            if (jar != null) return executeJar(jar);

            File fromPath = findOnPath("kompile-agent");
            if (fromPath != null) return executeBinary(fromPath);

            File homeBinary = new File(Info.homeDirectory(), "bin/kompile-agent");
            if (homeBinary.canExecute()) return executeBinary(homeBinary);

            System.err.println("'kompile-agent' is required for spin management but was not found.");
            System.err.println("Install a full/local Kompile distribution or set KOMPILE_INSTALL_DIR.");
            return 1;
        }

        private int executeBinary(File binary) throws IOException, InterruptedException {
            List<String> command = childArguments();
            command.add(0, binary.getAbsolutePath());
            ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
            File distributionHome = ComponentRegistry.inferDistributionHome(binary.toPath());
            if (distributionHome != null) {
                builder.environment().put("KOMPILE_INSTALL_DIR", distributionHome.getAbsolutePath());
            }
            return waitFor(builder.start());
        }

        private int executeJar(File jar) throws IOException, InterruptedException {
            File distributionHome = ComponentRegistry.inferDistributionHome(jar.toPath());
            List<String> command = new ArrayList<>();
            command.add(JavaRuntimeLocator.javaExecutable());
            if (distributionHome != null) {
                command.add("-Dkompile.dist.home=" + distributionHome.getAbsolutePath());
            }
            command.add("-jar");
            command.add(jar.getAbsolutePath());
            command.addAll(childArguments());
            ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
            if (distributionHome != null) {
                builder.environment().put("KOMPILE_INSTALL_DIR", distributionHome.getAbsolutePath());
            }
            return waitFor(builder.start());
        }

        private List<String> childArguments() {
            List<String> child = new ArrayList<>();
            child.add("spin");
            if (arguments == null || arguments.length == 0) child.add("--help");
            else child.addAll(List.of(arguments));
            return child;
        }

        private static File findOnPath(String name) {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) return null;
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) continue;
                File candidate = new File(directory, name);
                if (candidate.canExecute()) return candidate;
                File windows = new File(directory, name + ".exe");
                if (windows.canExecute()) return windows;
            }
            return null;
        }

        private static int waitFor(Process process) throws InterruptedException {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }
}
