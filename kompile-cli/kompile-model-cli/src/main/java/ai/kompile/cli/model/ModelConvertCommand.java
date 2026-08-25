/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.model;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.modelmanager.ManagedModelArtifactCatalog;
import ai.kompile.modelmanager.ManagedModelRuntimeRegistrar;
import picocli.CommandLine;

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
 * Convert a local model through the standalone model-staging converter.
 *
 * <p>The model CLI deliberately does not embed a second copy of the importer or
 * choose an ND4J backend. It resolves the distribution's configured staging
 * component (native executable first, executable JAR second in JVM mode) and
 * invokes its one-shot {@code convert} command.</p>
 */
@CommandLine.Command(
        name = "convert",
        description = "Convert a local model to canonical SameDiff (.sdz); supports ONNX, TensorFlow/Keras, GGUF/GGML, and SafeTensors.",
        mixinStandardHelpOptions = true)
public class ModelConvertCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-i", "--input"}, required = true,
            description = "Input model file (ONNX, TensorFlow/Keras, GGUF/GGML, or SafeTensors)")
    private Path input;

    @CommandLine.Option(names = {"-o", "--output"}, required = true,
            description = "Output canonical SameDiff archive (.sdz)")
    private Path output;

    @CommandLine.Option(names = {"-f", "--format"},
            description = "Input format: onnx, tensorflow, keras, gguf, ggml, safetensors (auto-detected when omitted)")
    private String format;

    @CommandLine.Option(names = "--staging-executable",
            description = "Standalone native kompile-model-staging binary override")
    private Path stagingExecutable;

    @CommandLine.Option(names = "--staging-jar",
            description = "Executable model-staging JAR override (JAR distribution/JVM mode)")
    private Path stagingJar;

    @CommandLine.Option(names = "--onnx-importer-executable",
            description = "Standalone native ONNX importer override")
    private Path onnxImporterExecutable;

    @CommandLine.Option(names = "--onnx-importer-jar",
            description = "Standalone ONNX importer CLI JAR override")
    private Path onnxImporterJar;

    @CommandLine.Option(names = "--force", description = "Overwrite the destination artifact")
    private boolean force;

    @CommandLine.Option(names = "--model-id",
            description = "Managed model id to register after successful conversion")
    private String modelId;

    @CommandLine.Option(names = "--models-root",
            description = "Project-local models root used for runtime registration")
    private Path modelsRoot;

    @CommandLine.Option(names = "--java",
            description = "Java executable used for the JAR distribution/JVM staging path")
    private Path java;

    @CommandLine.Option(names = "--timeout-minutes", defaultValue = "60",
            description = "Maximum conversion time")
    private long timeoutMinutes;

    @Override
    public Integer call() {
        Path inputPath = normalize(input);
        Path outputPath = normalize(output);
        try {
            if (!Files.isRegularFile(inputPath) || Files.isSymbolicLink(inputPath)) {
                throw new IOException("Input model file does not exist or is a symbolic link: " + inputPath);
            }
            if (!outputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdz")) {
                throw new IOException("Output must use the canonical .sdz extension: " + outputPath);
            }
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean directOnnx = isOnnx(inputPath, format);
            Launcher launcher = directOnnx ? resolveOnnxLauncher() : resolveLauncher();
            List<String> command = new ArrayList<>();
            if (launcher.nativeExecutable()) {
                command.add(launcher.path().toString());
            } else {
                command.add(resolveJava().toString());
                command.add("-Dfile.encoding=UTF-8");
                command.add("-jar");
                command.add(launcher.path().toString());
            }
            if (directOnnx) {
                command.add(inputPath.toString());
                command.add(outputPath.toString());
                if (force) command.add("--force");
            } else {
                command.add("convert");
                command.add("--input=" + inputPath);
                command.add("--output=" + outputPath);
                if (format != null && !format.isBlank()) {
                    command.add("--format=" + format.trim());
                }
            }

            Process process = new ProcessBuilder(command)
                    .directory(inputPath.getParent().toFile())
                    .inheritIO()
                    .start();
            long timeout = Math.max(1L, timeoutMinutes);
            if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Model conversion timed out after " + timeout + " minute(s)");
            }
            int exitCode = process.exitValue();
            if (exitCode == 0 && directOnnx && modelId != null && !modelId.isBlank()) {
                ManagedModelArtifactCatalog.Definition definition = ManagedModelArtifactCatalog.find(modelId)
                        .orElseThrow(() -> new IOException(
                                "No managed component manifest is registered for model '" + modelId + "'"));
                Path tokenizer = inputPath.resolveSibling("tokenizer.json");
                Path registryRoot = modelsRoot == null
                        ? outputPath.getParent().getParent()
                        : normalize(modelsRoot);
                ManagedModelRuntimeRegistrar.register(
                        registryRoot, definition, outputPath, tokenizer);
            }
            return exitCode;
        } catch (Exception e) {
            System.err.println("Model conversion failed: " + message(e));
            return 1;
        }
    }

    static boolean isOnnx(Path inputPath, String configuredFormat) {
        if (configuredFormat != null && !configuredFormat.isBlank()) {
            return "onnx".equalsIgnoreCase(configuredFormat.trim());
        }
        return inputPath != null
                && inputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".onnx");
    }

    private Launcher resolveOnnxLauncher() throws IOException {
        Path explicitExecutable = firstPath(
                onnxImporterExecutable,
                environmentPath("KOMPILE_ONNX_IMPORTER_EXECUTABLE"));
        if (explicitExecutable != null) {
            requireExecutable(explicitExecutable, "ONNX importer executable");
            return new Launcher(explicitExecutable, true);
        }
        Path explicitJar = firstPath(
                onnxImporterJar,
                environmentPath("KOMPILE_ONNX_IMPORTER_JAR"));
        if (explicitJar != null) {
            requireFile(explicitJar, "ONNX importer CLI JAR");
            rejectJarForNativeRuntime("ONNX importer");
            return new Launcher(explicitJar, false);
        }

        Path installRoot = firstPath(
                propertyPath("kompile.dist.home"),
                environmentPath("KOMPILE_INSTALL_DIR"),
                environmentPath("KOMPILE_DIST_HOME"));
        if (installRoot != null) {
            Launcher installed = onnxLauncherFromRoot(installRoot);
            if (installed != null) return installed;
        }
        Path processRoot = processDistributionRoot();
        if (processRoot != null) {
            Launcher installed = onnxLauncherFromRoot(processRoot);
            if (installed != null) return installed;
        }
        Path developmentJar = findDevelopmentOnnxImporterJar();
        if (developmentJar != null) {
            rejectJarForNativeRuntime("ONNX importer");
            return new Launcher(developmentJar, false);
        }
        throw new IOException("No standalone ONNX importer found. Install onnx-importer or configure "
                + "--onnx-importer-executable / --onnx-importer-jar.");
    }

    private Launcher onnxLauncherFromRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path executable = normalized.resolve("bin").resolve(windows()
                ? "onnx-importer.exe" : "onnx-importer");
        if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            return new Launcher(executable, true);
        }
        Path jar = normalized.resolve("lib").resolve("kompile-model-importer-onnx.jar");
        if (Files.isRegularFile(jar)) {
            rejectJarForNativeRuntime("ONNX importer");
            return new Launcher(jar, false);
        }
        return null;
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
            rejectJarForNativeRuntime("model-staging");
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
            rejectJarForNativeRuntime("model-staging");
            return new Launcher(developmentJar, false);
        }

        throw new IOException("No standalone model-staging runtime found. "
                + "Install the distribution's native worker or configure --staging-executable "
                + "(native) / --staging-jar (JAR distribution/JVM mode).");
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
            rejectJarForNativeRuntime("model-staging");
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

    private void rejectJarForNativeRuntime(String component) throws IOException {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            throw new IOException("Native model CLI requires the standalone native "
                    + component + " executable; refusing an executable JAR fallback");
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

    private static Path findDevelopmentOnnxImporterJar() {
        Path cursor = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app").resolve("kompile-models")
                    .resolve("kompile-model-importers").resolve("kompile-model-importer-onnx")
                    .resolve("target");
            if (!Files.isDirectory(target)) continue;
            try (Stream<Path> files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-cli.jar"))
                        .findFirst().orElse(null);
                if (match != null) return match.toAbsolutePath().normalize();
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
