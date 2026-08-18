/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.model;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Invoke the distribution's configured staging optimizer.
 *
 * <p>This command is deliberately an ABI bridge: optimization remains in the
 * staging distribution, while this CLI exposes the same native-first/JAR-second
 * selection used by conversion.</p>
 */
@Command(
        name = "optimize",
        description = "Optimize a local or catalog SameDiff graph through model staging",
        mixinStandardHelpOptions = true)
public class ModelOptimizeCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"},
            description = "Local input SameDiff artifact (.sdz or .fb)")
    private Path input;

    @Option(names = "--model-id",
            description = "Catalog model id; used when --input is omitted")
    private String modelId;

    @Option(names = {"-o", "--output"},
            description = "Output artifact (.sdz or .fb); omitted updates the input in place")
    private Path output;

    @Option(names = {"--passes", "--selected-pass"}, split = ",",
            description = "Comma-separated optimizer pass ids")
    private List<String> selectedPasses;

    @Option(names = "--profile", defaultValue = "BASIC",
            description = "Default profile when passes are omitted")
    private String profile;

    @Option(names = "--max-iterations", defaultValue = "3")
    private int maxIterations;

    @Option(names = "--quantization-type")
    private String quantizationType;

    @Option(names = "--force", defaultValue = "false")
    private boolean force;

    @Option(names = "--create-backup", defaultValue = "true")
    private boolean createBackup;

    @Option(names = "--dry-run", defaultValue = "false")
    private boolean dryRun;

    @Option(names = "--staging-executable",
            description = "Standalone native kompile-model-staging binary override")
    private Path stagingExecutable;

    @Option(names = "--staging-jar",
            description = "Executable model-staging JAR override (JAR distribution/JVM mode)")
    private Path stagingJar;

    @Option(names = "--java",
            description = "Java executable used for executable-JAR staging")
    private Path java;

    @Option(names = "--timeout-minutes", defaultValue = "60")
    private long timeoutMinutes;

    @Override
    public Integer call() {
        try {
            if (input == null && (modelId == null || modelId.isBlank())) {
                throw new IllegalArgumentException("Either --input or --model-id is required");
            }

            List<String> command = new ArrayList<>();
            Launcher launcher = resolveLauncher();
            if (launcher.nativeExecutable()) {
                command.add(launcher.path().toString());
            } else {
                command.add(resolveJava().toString());
                command.add("-Dfile.encoding=UTF-8");
                command.add("-jar");
                command.add(launcher.path().toString());
            }
            command.add("optimize");
            if (input != null) {
                command.add("--input=" + normalize(input));
            }
            if (modelId != null && !modelId.isBlank()) {
                command.add("--model-id=" + modelId.trim());
            }
            if (output != null) {
                command.add("--output=" + normalize(output));
            }
            if (selectedPasses != null && !selectedPasses.isEmpty()) {
                command.add("--passes=" + String.join(",", selectedPasses));
            }
            if (profile != null && !profile.isBlank()) {
                command.add("--profile=" + profile.trim());
            }
            command.add("--max-iterations=" + Math.max(1, maxIterations));
            if (quantizationType != null && !quantizationType.isBlank()) {
                command.add("--quantization-type=" + quantizationType.trim());
            }
            command.add("--force=" + force);
            command.add("--create-backup=" + createBackup);
            command.add("--dry-run=" + dryRun);

            Path workDir = input == null ? Paths.get(System.getProperty("user.dir", ".")) : normalize(input).getParent();
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .inheritIO()
                    .start();
            long timeout = Math.max(1L, timeoutMinutes);
            if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Model optimization timed out after " + timeout + " minute(s)");
            }
            return process.exitValue();
        } catch (Exception e) {
            System.err.println("Model optimization failed: " + message(e));
            return 1;
        }
    }

    private Launcher resolveLauncher() throws IOException {
        Path explicitExecutable = firstPath(
                stagingExecutable,
                environmentPath("KOMPILE_MODEL_STAGING_EXECUTABLE"));
        if (explicitExecutable != null) {
            requireExecutable(explicitExecutable, "model-staging executable");
            return new Launcher(explicitExecutable, true);
        }

        Path explicitJar = firstPath(
                stagingJar,
                environmentPath("KOMPILE_MODEL_STAGING_JAR"));
        if (explicitJar != null) {
            requireFile(explicitJar, "model-staging executable JAR");
            rejectJarForNativeRuntime();
            return new Launcher(explicitJar, false);
        }

        Path installRoot = firstPath(
                propertyPath("kompile.dist.home"),
                environmentPath("KOMPILE_INSTALL_DIR"),
                environmentPath("KOMPILE_DIST_HOME"));
        if (installRoot != null) {
            Launcher installed = launcherFromRoot(installRoot);
            if (installed != null) {
                return installed;
            }
        }

        Path processRoot = processDistributionRoot();
        if (processRoot != null) {
            Launcher installed = launcherFromRoot(processRoot);
            if (installed != null) {
                return installed;
            }
        }

        Path developmentJar = findDevelopmentStagingJar();
        if (developmentJar != null) {
            rejectJarForNativeRuntime();
            return new Launcher(developmentJar, false);
        }

        throw new IOException("No standalone model-staging runtime found. "
                + "Configure --staging-executable (native) or --staging-jar (JAR distribution/JVM mode).");
    }

    private Launcher launcherFromRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path executable = normalized.resolve("bin").resolve(windows()
                ? "kompile-model-staging.exe" : "kompile-model-staging");
        if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            return new Launcher(executable, true);
        }
        Path jar = normalized.resolve("lib").resolve("kompile-model-staging.jar");
        if (Files.isRegularFile(jar)) {
            rejectJarForNativeRuntime();
            return new Launcher(jar, false);
        }
        return null;
    }

    private Path resolveJava() throws IOException {
        if (java != null) {
            Path configured = normalize(java);
            requireExecutable(configured, "Java executable");
            return configured;
        }
        return Paths.get(JavaRuntimeLocator.javaExecutable());
    }

    private void rejectJarForNativeRuntime() throws IOException {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            throw new IOException("Native model CLI requires the standalone native "
                    + "kompile-model-staging executable; refusing an executable JAR fallback");
        }
    }

    private static Path processDistributionRoot() {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null || command.isBlank()) {
            return null;
        }
        Path executable = Paths.get(command).toAbsolutePath().normalize();
        Path bin = executable.getParent();
        return bin == null ? null : bin.getParent();
    }

    private static Path findDevelopmentStagingJar() {
        Path cursor = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app").resolve("kompile-models")
                    .resolve("kompile-model-staging").resolve("target");
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (Stream<Path> files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .findFirst().orElse(null);
                if (match != null) {
                    return match.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
                // Continue toward the workspace root.
            }
        }
        return null;
    }

    private static Path propertyPath(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Paths.get(value);
    }

    private static Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Paths.get(value);
    }

    private static Path firstPath(Path... paths) {
        for (Path path : paths) {
            if (path != null) {
                return normalize(path);
            }
        }
        return null;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("Configured " + label + " does not exist: " + path);
        }
    }

    private static void requireExecutable(Path path, String label) throws IOException {
        requireFile(path, label);
        if (!Files.isExecutable(path)) {
            throw new IOException("Configured " + label + " is not executable: " + path);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName() : value;
    }

    private record Launcher(Path path, boolean nativeExecutable) { }
}
