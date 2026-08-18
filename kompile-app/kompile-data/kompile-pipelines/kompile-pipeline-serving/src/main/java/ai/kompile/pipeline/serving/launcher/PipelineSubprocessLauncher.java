/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts the single reusable pipeline execution process.
 *
 * <p>There are no one-shot, HTTP, model-specific, or caller-executable branches. Every
 * definition is loaded into the same persistent bidirectional stdio runtime, whose lifecycle is
 * owned by the MCP-side runtime pool.</p>
 */
public class PipelineSubprocessLauncher implements BackendConfigurable {
    private static final Logger logger = LoggerFactory.getLogger(PipelineSubprocessLauncher.class);
    private static final long READY_TIMEOUT_MS = 120_000L;
    private static final String[] FORWARDED_PROPERTY_PREFIXES = {
            "org.nd4j.", "org.bytedeco.", "nd4j.", "cuda.", "cudnn.", "openblas.", "mkl."
    };

    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @Override
    public void applyPlacement(SubprocessPlacement placement) {
        this.placement.applyPlacement(placement);
    }

    /** Launch one reusable runtime session; callers must release it through a pool lease. */
    public PipelineRuntimeSession launch(UnifiedPipelineDefinition definition)
            throws Exception {
        PipelineServingSubprocessArgs args = buildArgs(definition);
        Path argsFile = args.writeToTempFile();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(buildCommand(definition, argsFile));
            builder.redirectErrorStream(false);
            propagateEnvironment(builder.environment());
            process = builder.start();
            PipelineRuntimeSession session = new PipelineRuntimeSession(definition, process);
            session.awaitReady(Duration.ofMillis(READY_TIMEOUT_MS));
            return session;
        } catch (Exception failure) {
            stopChild(process);
            throw failure;
        } finally {
            Files.deleteIfExists(argsFile);
        }
    }

    private PipelineServingSubprocessArgs buildArgs(UnifiedPipelineDefinition definition)
            throws Exception {
        return new PipelineServingSubprocessArgs(objectMapper.writeValueAsString(definition));
    }

    private List<String> buildCommand(UnifiedPipelineDefinition definition, Path argsFile)
            throws IOException {
        LauncherArtifact launcher = resolveLauncher();
        UnifiedPipelineDefinition.ServingConfig serving = definition.getServing() != null
                ? definition.getServing()
                : UnifiedPipelineDefinition.ServingConfig.builder().build();
        List<String> command = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            command.add(launcher.path().toString());
        } else {
            command.add(JavaRuntimeLocator.javaExecutable());
            command.add("-Xmx" + serving.getHeapSize());
            command.add("-XX:+UseG1GC");
            command.add("-XX:MaxGCPauseMillis=200");
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Dorg.bytedeco.javacpp.nopointergc=true");
            var properties = System.getProperties();
            for (String prefix : FORWARDED_PROPERTY_PREFIXES) {
                for (String key : properties.stringPropertyNames()) {
                    if (key.startsWith(prefix)) {
                        command.add("-D" + key + "=" + properties.getProperty(key));
                    }
                }
            }
            command.addAll(placement.jvmFlags());
            command.add("-jar");
            command.add(launcher.path().toString());
        }
        command.add(argsFile.toAbsolutePath().normalize().toString());
        return command;
    }

    private LauncherArtifact resolveLauncher() throws IOException {
        boolean nativeParent = NativeImageInfo.isRunningInNativeImage();
        String configuredExecutable = firstNonBlank(
                System.getProperty("kompile.pipeline.serving.executable"),
                System.getenv("KOMPILE_PIPELINE_SERVING_EXECUTABLE"));
        if (configuredExecutable != null) {
            Path executable = Path.of(configuredExecutable).toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new IOException("Configured pipeline runtime is not executable: " + executable);
            }
            return new LauncherArtifact(executable, true);
        }

        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "kompile-pipeline-serving.exe" : "kompile-pipeline-serving";
        List<Path> binaries = new ArrayList<>();
        addDistributionCandidates(binaries, executableName, "bin");
        for (Path candidate : binaries) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return new LauncherArtifact(candidate, true);
            }
        }
        if (nativeParent) {
            throw new IOException("Native Kompile distribution is missing bin/" + executableName);
        }

        String configuredJar = firstNonBlank(
                System.getProperty("kompile.pipeline.serving.jar"),
                System.getenv("KOMPILE_PIPELINE_SERVING_JAR"));
        if (configuredJar != null) {
            Path jar = Path.of(configuredJar).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Configured pipeline runtime JAR does not exist: " + jar);
            }
            return new LauncherArtifact(jar, false);
        }

        List<Path> jars = new ArrayList<>();
        addDistributionCandidates(jars, "kompile-pipeline-serving-exec.jar", "lib");
        Path developmentJar = findDevelopmentExecJar();
        if (developmentJar != null) jars.add(developmentJar);
        for (Path candidate : jars) {
            if (Files.isRegularFile(candidate)) return new LauncherArtifact(candidate, false);
        }
        throw new IOException("No packaged pipeline runtime found; install kompile-pipeline-serving");
    }

    private void addDistributionCandidates(List<Path> candidates, String name, String directory) {
        String installDir = System.getenv("KOMPILE_INSTALL_DIR");
        if (installDir != null && !installDir.isBlank()) {
            candidates.add(Path.of(installDir).resolve(directory).resolve(name));
        }
        ProcessHandle.current().info().command().ifPresent(command -> {
            Path executable = Path.of(command).toAbsolutePath().normalize();
            Path bin = executable.getParent();
            if (bin != null && bin.getParent() != null) {
                candidates.add(bin.getParent().resolve(directory).resolve(name));
            }
        });
        candidates.add(Path.of(System.getProperty("user.home"), ".kompile", directory, name));
    }

    private Path findDevelopmentExecJar() {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target");
            if (!Files.isDirectory(target)) continue;
            try (var files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .findFirst().orElse(null);
                if (match != null) return match.toAbsolutePath().normalize();
            } catch (IOException ignored) {
                // Continue toward the workspace root.
            }
        }
        return null;
    }

    private void propagateEnvironment(Map<String, String> environment) {
        SubprocessEnvironmentPropagator.propagateToEnvironment(environment);
        placement.applyEnv(environment);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static void stopChild(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    record LauncherArtifact(Path path, boolean nativeExecutable) {
        LauncherArtifact {
            path = path.toAbsolutePath().normalize();
        }
    }
}
