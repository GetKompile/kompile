/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import ai.kompile.utils.NativeImageInfo;
import ai.kompile.graph.reasoning.lifecycle.IncrementalGraphPslInference;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Runs folder-local KGE and portable PSL/MEBN learning in the installed bounded learning child.
 *
 * <p>The parent and child exchange complete {@code .kgraph} archives. The child never writes the
 * canonical graph, so a crash, timeout, stale heartbeat, or native OOM cannot corrupt the last good
 * project graph. A global JVM/file lock serializes these heavy jobs across local MCP processes.
 * The child is launched as a bare JVM running the Spring-free {@code LearningSubprocessMain}
 * from the current classpath (CLI/MCP-initiated learning needs no web server or UI). Only when the
 * CLI itself runs as a native image — where a classpath child is impossible — does launch fall
 * back to the installed kompile-app distribution.</p>
 */
final class ProjectLocalLearningSubprocessExecutor {
    static final String MESSAGE_PREFIX = "LEARNING_MSG:";
    static final int PROTOCOL_VERSION = 1;
    static final String OPERATION = "PORTABLE_GRAPH_LEARNING";

    private static final String ENABLED_PROPERTY = "kompile.local.learning.subprocess.enabled";
    private static final String INLINE_FALLBACK_PROPERTY = "kompile.local.learning.inlineFallback";
    /** Child main for the bare local child — same job the installed dist child runs, minus Spring/web. */
    static final String LOCAL_CHILD_MAIN = "ai.kompile.app.learning.subprocess.LearningSubprocessMain";
    private static final String HEAP_MB_PROPERTY = "kompile.local.learning.heap.mb";
    private static final String TIMEOUT_MS_PROPERTY = "kompile.local.learning.timeout.ms";
    private static final String STALE_MS_PROPERTY = "kompile.local.learning.stale.ms";
    private static final String EXECUTABLE_PROPERTY = "kompile.local.learning.executable";
    private static final String JAR_PROPERTY = "kompile.local.learning.jar";
    private static final String BACKEND_JARS_PROPERTY = "kompile.local.learning.backend.jars";
    private static final String EXECUTABLE_ENV = "KOMPILE_LOCAL_LEARNING_EXECUTABLE";
    private static final String JAR_ENV = "KOMPILE_LOCAL_LEARNING_JAR";
    private static final String BACKEND_JARS_ENV = "KOMPILE_LOCAL_LEARNING_BACKEND_JARS";
    private static final int DEFAULT_HEAP_MB = 8192;
    private static final long DEFAULT_TIMEOUT_MS = Duration.ofHours(1).toMillis();
    private static final long DEFAULT_STALE_MS = Duration.ofMinutes(3).toMillis();
    private static final int STDERR_TAIL_LINES = 50;
    private static final ReentrantLock LOCAL_LEARNING_LOCK = new ReentrantLock(true);
    // These are the only graph metadata fields owned by incremental graph-PSL inference. Keep this
    // exact allowlist gated on reasoning; unrelated reasoning metadata remains protected.
    static final String PROJECTED_ENTITY_IDS_META = "reasoningLearning.projectedEntityIds";

    private static final Set<String> INCREMENTAL_PSL_METADATA_KEYS = Set.of(
            IncrementalGraphPslInference.META_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_REUSED_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_SOLVED_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_REUSE_COUNT,
            IncrementalGraphPslInference.META_SOLVE_COUNT,
            IncrementalGraphPslInference.META_TOTAL_REUSED_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_TOTAL_SOLVED_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_CACHE_ENTRIES,
            IncrementalGraphPslInference.META_CACHE_LOADED,
            IncrementalGraphPslInference.META_NON_CONVERGED_COMPONENT_COUNT,
            IncrementalGraphPslInference.META_STATUS);

    private final ObjectMapper mapper;
    private final CommandFactory commandFactory;
    private final boolean subprocessEnabled;
    private final boolean inlineFallback;
    private final long timeoutMs;
    private final long staleMs;

    ProjectLocalLearningSubprocessExecutor(ObjectMapper mapper) {
        this(mapper,
                // CLI/MCP-initiated local learning: bare classpath child. A native-image CLI has
                // no runtime classpath to hand a child, so it resolves the installed dist instead.
                NativeImageInfo.isRunningInNativeImage()
                        ? ProjectLocalLearningSubprocessExecutor::installedLaunchSpec
                        : ProjectLocalLearningSubprocessExecutor::localLaunchSpec,
                Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true")),
                Boolean.parseBoolean(System.getProperty(INLINE_FALLBACK_PROPERTY, "false")),
                longProperty(TIMEOUT_MS_PROPERTY, DEFAULT_TIMEOUT_MS),
                longProperty(STALE_MS_PROPERTY, DEFAULT_STALE_MS));
    }

    ProjectLocalLearningSubprocessExecutor(ObjectMapper mapper,
                                           CommandFactory commandFactory,
                                           boolean subprocessEnabled,
                                           boolean inlineFallback,
                                           long timeoutMs,
                                           long staleMs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
        this.subprocessEnabled = subprocessEnabled;
        this.inlineFallback = inlineFallback;
        this.timeoutMs = Math.max(1_000L, timeoutMs);
        this.staleMs = Math.max(1_000L, staleMs);
    }

    record Plan(UnifiedGraphKgeLifecycle.Config embedding,
                UnifiedGraphReasoningLifecycle.Config reasoning,
                String phase) {
        Plan {
            embedding = Objects.requireNonNull(embedding, "embedding");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
            phase = phase == null || phase.isBlank() ? "LEARNING" : phase;
        }

        boolean enabled() {
            return embedding.enabled() || reasoning.enabled();
        }
    }

    record Result(UnifiedGraph graph,
                  String execution,
                  String runId,
                  Long childPid,
                  Path logPath) {
    }

    @FunctionalInterface
    interface CommandFactory {
        LaunchSpec build(Path argsFile) throws Exception;
    }

    record LaunchSpec(List<String> command, Map<String, String> environment, String launcher) {
        LaunchSpec {
            command = List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    Result learn(UnifiedGraph graph, Plan plan, Path workingDirectory, String crawlJobId)
            throws Exception {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(plan, "plan");
        if (!plan.enabled()) {
            return new Result(graph, "SKIPPED", null, null, null);
        }
        if (!subprocessEnabled) {
            if (!inlineFallback) {
                throw new IllegalStateException("Project-local learning subprocess is disabled. "
                        + "Enable " + ENABLED_PROPERTY + " or explicitly enable the development "
                        + "fallback with " + INLINE_FALLBACK_PROPERTY + ".");
            }
            UnifiedGraphKgeLifecycle.learn(graph, plan.embedding());
            UnifiedGraphReasoningLifecycle.learn(graph, plan.reasoning());
            graph.meta("learning.execution", "IN_PROCESS_FALLBACK")
                    .meta("learning.phase", plan.phase());
            return new Result(graph, "IN_PROCESS_FALLBACK", null, null, null);
        }
        return learnInChild(graph, plan, workingDirectory, crawlJobId);
    }

    private Result learnInChild(UnifiedGraph graph, Plan plan, Path workingDirectory,
                                String crawlJobId) throws Exception {
        Path work = workingDirectory.toAbsolutePath().normalize();
        Path input = null;
        Path output = null;
        Path argsFile = null;
        boolean processLockAcquired = false;
        try {
            Files.createDirectories(work);
            String runId = "local-learning-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 16);
            input = Files.createTempFile(work, ".learning-input-", ".kgraph");
            output = work.resolve(".learning-output-" + runId + ".kgraph");
            argsFile = Files.createTempFile(work, ".learning-args-", ".json");
            graph.save(input);
            // Validate against the exact archive boundary the child reads. F32 vectors and JSON
            // numeric metadata are normalized during serialization, so the pre-save object is not
            // an authoritative byte-level baseline.
            UnifiedGraph serializedBaseline = UnifiedGraph.load(input);
            writeArgs(argsFile, serializedBaseline, plan, input, output, crawlJobId);

            LOCAL_LEARNING_LOCK.lockInterruptibly();
            processLockAcquired = true;
            Path crossProcessLock = KompileHome.homeDirectory().toPath().toAbsolutePath().normalize()
                    .resolve("locks/local-learning.lock");
            Files.createDirectories(crossProcessLock.getParent());
            try (FileChannel channel = FileChannel.open(crossProcessLock,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return runChild(serializedBaseline, plan, work, runId, argsFile, output, crawlJobId);
            }
        } finally {
            if (processLockAcquired) LOCAL_LEARNING_LOCK.unlock();
            deleteQuietly(argsFile);
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private Result runChild(UnifiedGraph inputGraph,
                            Plan plan,
                            Path work,
                            String runId,
                            Path argsFile,
                            Path expectedOutput,
                            String crawlJobId) throws Exception {
        LaunchSpec launch = commandFactory.build(argsFile);
        Process process = null;
        Thread stdout = null;
        Thread stderr = null;
        SubprocessLogWriter logWriter = null;
        String watchdogId = null;
        AtomicReference<JsonNode> terminal = new AtomicReference<>();
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        Deque<String> stderrTail = new ArrayDeque<>();
        String state = "FAILED";
        String error = null;
        Integer exitCode = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(launch.command());
            builder.directory(work.toFile());
            builder.redirectErrorStream(false);
            builder.environment().putAll(launch.environment());
            process = builder.start();

            watchdogId = LocalSubprocessWatchdog.get().register(
                    "learning-" + runId, process, "portable-graph-learning",
                    "learning child for crawl " + crawlJobId);

            logWriter = new SubprocessLogWriter("learning", runId, work);
            logWriter.putMetadata("processType", "portable-graph-learning");
            logWriter.putMetadata("crawlJobId", crawlJobId);
            logWriter.putMetadata("phase", plan.phase());
            logWriter.putMetadata("launcher", launch.launcher());
            logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    crawlJobId, launch.command(), work.toString(), process.pid(),
                    Integer.toString(intProperty(HEAP_MB_PROPERTY, DEFAULT_HEAP_MB)) + "m"));
            SubprocessLogWriter activeLog = logWriter;
            stdout = drainStdout(process.getInputStream(), activeLog, terminal, lastActivity);
            stderr = drainStderr(process.getErrorStream(), activeLog, stderrTail);

            long started = System.currentTimeMillis();
            while (process.isAlive()) {
                JsonNode message = terminal.get();
                if (message != null && "FAILED".equals(message.path("type").asText())) {
                    terminate(process);
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - started > timeoutMs) {
                    error = "Learning subprocess timed out after " + timeoutMs + "ms";
                    terminate(process);
                    break;
                }
                if (now - lastActivity.get() > staleMs) {
                    error = "Learning subprocess heartbeat stale for " + staleMs + "ms";
                    terminate(process);
                    break;
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            if (process.isAlive()) terminate(process);
            if (process.isAlive()) {
                throw new IOException("Learning subprocess could not be terminated");
            }
            exitCode = process.exitValue();
            join(stdout);
            join(stderr);

            JsonNode message = terminal.get();
            if (error != null) throw new IOException(error + tail(stderrTail));
            if (message == null) {
                throw new IOException("Learning subprocess exited without a completion message"
                        + tail(stderrTail));
            }
            if ("FAILED".equals(message.path("type").asText())) {
                throw new IOException(message.path("reason").asText("Learning subprocess failed")
                        + tail(stderrTail));
            }
            if (exitCode != 0) {
                throw new IOException("Learning subprocess exited with code " + exitCode
                        + tail(stderrTail));
            }
            Path reportedOutput = Path.of(message.path("outputPath").asText(""))
                    .toAbsolutePath().normalize();
            if (!reportedOutput.equals(expectedOutput.toAbsolutePath().normalize())) {
                throw new IOException("Learning subprocess reported an unexpected output path: "
                        + reportedOutput);
            }
            if (!Files.isRegularFile(expectedOutput)) {
                throw new IOException("Learning subprocess did not write " + expectedOutput);
            }
            UnifiedGraph learned = UnifiedGraph.load(expectedOutput);
            validateGraphIdentity(inputGraph, learned, plan);
            learned.meta("learning.execution", "SUBPROCESS")
                    .meta("learning.phase", plan.phase())
                    .meta("learning.subprocessRunId", runId)
                    .meta("learning.subprocessPid", process.pid())
                    .meta("learning.subprocessLog", activeLog.getLogFile().getAbsolutePath());
            state = "COMPLETED";
            return new Result(learned, "SUBPROCESS", runId, process.pid(),
                    activeLog.getLogFile().toPath());
        } catch (InterruptedException e) {
            error = "Interrupted while waiting for learning subprocess";
            terminate(process);
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            error = e.getMessage();
            terminate(process);
            throw e;
        } finally {
            join(stdout);
            join(stderr);
            if (watchdogId != null) {
                LocalSubprocessWatchdog.get().deregister(watchdogId);
            }
            if (logWriter != null) {
                try {
                    logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                            state, exitCode, error, containsOom(stderrTail), false));
                } catch (Exception ignored) {
                    // The training result remains authoritative if log finalization fails.
                }
                logWriter.close();
            }
        }
    }

    private void writeArgs(Path argsFile,
                           UnifiedGraph graph,
                           Plan plan,
                           Path input,
                           Path output,
                           String crawlJobId) throws IOException {
        ObjectNode args = mapper.createObjectNode();
        args.put("protocolVersion", PROTOCOL_VERSION);
        args.put("operation", OPERATION);
        args.put("crawlJobId", crawlJobId);
        if (graph.factSheetId() == null) args.putNull("factSheetId");
        else args.put("factSheetId", graph.factSheetId());
        args.put("inputGraphPath", input.toString());
        args.put("outputGraphPath", output.toString());
        UnifiedGraphKgeLifecycle.Config embedding = plan.embedding();
        args.put("embeddingEnabled", embedding.enabled());
        args.put("embeddingAlgorithm", embedding.algorithm());
        args.put("embeddingDim", embedding.embeddingDim());
        args.put("embeddingEpochs", embedding.epochs());
        args.put("embeddingLearningRate", embedding.learningRate());
        args.put("embeddingWarmStartEpochs", embedding.warmStartEpochs());
        args.put("embeddingSeed", embedding.seed());
        UnifiedGraphReasoningLifecycle.Config reasoning = plan.reasoning();
        args.put("reasoningEnabled", reasoning.enabled());
        args.put("pslSteps", reasoning.pslSteps());
        args.put("mebnEpochs", reasoning.mebnEpochs());
        args.put("consensusRounds", reasoning.consensusRounds());
        args.put("consensusWeight", reasoning.consensusWeight());
        args.put("maxRelationTypes", reasoning.maxRelationTypes());
        // In-JVM child watchdog thresholds. The child ignores a kill<=0 section, so an
        // explicit disable (0) flows through and turns the child watchdog off cleanly.
        ObjectNode watchdog = args.putObject("memoryWatchdog");
        watchdog.put("heapStopPercent", watchInt(HEAP_STOP_PROPERTY, 80));
        watchdog.put("heapCriticalPercent", watchInt(HEAP_CRITICAL_PROPERTY, 90));
        watchdog.put("heapKillPercent", watchInt(HEAP_KILL_PROPERTY, 95));
        watchdog.put("checkIntervalMs", watchLong(WATCHDOG_INTERVAL_PROPERTY, 2_000L));
        watchdog.put("offHeapStopPercent", watchInt(OFF_HEAP_STOP_PROPERTY, 80));
        watchdog.put("offHeapCriticalPercent", watchInt(OFF_HEAP_CRITICAL_PROPERTY, 90));
        watchdog.put("offHeapKillPercent", watchInt(OFF_HEAP_KILL_PROPERTY, 95));
        watchdog.put("gpuStopPercent", watchInt(GPU_STOP_PROPERTY, 75));
        watchdog.put("gpuCriticalPercent", watchInt(GPU_CRITICAL_PROPERTY, 85));
        watchdog.put("gpuKillPercent", watchInt(GPU_KILL_PROPERTY, 92));
        mapper.writeValue(argsFile.toFile(), args);
    }

    static final String HEAP_STOP_PROPERTY = "kompile.local.learning.watchdog.heapStopPercent";
    static final String HEAP_CRITICAL_PROPERTY = "kompile.local.learning.watchdog.heapCriticalPercent";
    static final String HEAP_KILL_PROPERTY = "kompile.local.learning.watchdog.heapKillPercent";
    static final String WATCHDOG_INTERVAL_PROPERTY = "kompile.local.learning.watchdog.checkIntervalMs";
    static final String OFF_HEAP_STOP_PROPERTY = "kompile.local.learning.watchdog.offHeapStopPercent";
    static final String OFF_HEAP_CRITICAL_PROPERTY = "kompile.local.learning.watchdog.offHeapCriticalPercent";
    static final String OFF_HEAP_KILL_PROPERTY = "kompile.local.learning.watchdog.offHeapKillPercent";
    static final String GPU_STOP_PROPERTY = "kompile.local.learning.watchdog.gpuStopPercent";
    static final String GPU_CRITICAL_PROPERTY = "kompile.local.learning.watchdog.gpuCriticalPercent";
    static final String GPU_KILL_PROPERTY = "kompile.local.learning.watchdog.gpuKillPercent";

    /** Unclamped int property reader — thresholds need 0..100, not the heap-min clamp. */
    private static int watchInt(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long watchLong(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Thread drainStdout(InputStream stream,
                               SubprocessLogWriter logWriter,
                               AtomicReference<JsonNode> terminal,
                               AtomicLong lastActivity) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(MESSAGE_PREFIX)) {
                        try {
                            JsonNode message = mapper.readTree(line.substring(MESSAGE_PREFIX.length()));
                            lastActivity.set(System.currentTimeMillis());
                            String type = message.path("type").asText("");
                            if ("COMPLETED".equals(type) || "FAILED".equals(type)) terminal.set(message);
                        } catch (Exception parseError) {
                            logWriter.writeLine(AgentLogRecord.Stream.STDOUT,
                                    "Malformed learning protocol line: " + parseError.getMessage());
                        }
                    } else {
                        logWriter.writeLine(AgentLogRecord.Stream.STDOUT, line);
                    }
                }
            } catch (IOException ignored) {
                // Process termination closes the stream.
            }
        }, "local-learning-stdout");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Thread drainStderr(InputStream stream,
                               SubprocessLogWriter logWriter,
                               Deque<String> stderrTail) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        if (stderrTail.size() == STDERR_TAIL_LINES) stderrTail.removeFirst();
                        stderrTail.addLast(line);
                    }
                    logWriter.writeLine(AgentLogRecord.Stream.STDERR, line);
                }
            } catch (IOException ignored) {
                // Process termination closes the stream.
            }
        }, "local-learning-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Bare local child: a JVM running {@link #LOCAL_CHILD_MAIN} directly from the current
     * classpath. Same {@code PORTABLE_GRAPH_LEARNING} protocol and the same underlying
     * graph-reasoning libraries as the installed distribution child, but no Spring context, no
     * HTTP server, and no UI boot. Requires the learning module on the classpath (the kompile-cli
     * build carries it); fails fast with an actionable message otherwise.
     */
    static LaunchSpec localLaunchSpec(Path argsFile) throws IOException {
        // Under surefire java.class.path can be just the booter jar; the explicit test
        // classpath property carries the real entries (same fallback the child fixtures use).
        String childClasspath = firstNonBlank(
                System.getProperty("surefire.test.class.path"),
                System.getProperty("java.class.path", ""));
        if (childClasspath.isBlank()) {
            throw new IOException("Local learning launch requires a readable java.class.path");
        }
        try {
            Class.forName(LOCAL_CHILD_MAIN, false,
                    ProjectLocalLearningSubprocessExecutor.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IOException("Local learning launch requires " + LOCAL_CHILD_MAIN
                    + " on the classpath. Add the kompile-app-subprocess-learning dependency "
                    + "or set " + EXECUTABLE_PROPERTY + "/" + JAR_PROPERTY + " explicitly.", e);
        }
        if (learningBackendPreference(childClasspath) == BackendPreference.INHERIT) {
            // A fat-jar parent may carry no ND4J backend at all (the CLI declares it test-scoped),
            // and Nd4j.<clinit> then kills the child with NoAvailableBackendException. Resolve the
            // backend closure for the child the same way ManagedSubprocessLauncher does for its
            // subprocesses: explicit override, distribution bundle first, Maven repository second.
            List<Path> backendJars = resolveBackendJars();
            if (backendJars.isEmpty()) {
                throw new IOException("Local learning launch found no ND4J backend on the child "
                        + "classpath and none could be resolved from the Kompile distribution or "
                        + "the local Maven repository. Build/install the CPU backend "
                        + "(mvn install -pl :nd4j-native -am) or set " + BACKEND_JARS_PROPERTY
                        + " to a path-separated jar list.");
            }
            StringBuilder augmented = new StringBuilder(childClasspath);
            for (Path backendJar : backendJars) {
                augmented.append(File.pathSeparator).append(backendJar);
            }
            childClasspath = augmented.toString();
        }
        int heapMb = intProperty(HEAP_MB_PROPERTY, DEFAULT_HEAP_MB);
        long offHeapMb = Math.max(heapMb, (long) heapMb * 4L);
        long physicalCeilingMb = systemPhysicalCeilingMb(offHeapMb);
        List<String> command = new ArrayList<>();
        command.add(JavaRuntimeLocator.javaExecutable());
        command.add("-Xmx" + heapMb + "m");
        command.addAll(SubprocessEnvironmentPropagator.buildSystemPropertyFlags(childClasspath));
        command.add("-XX:+UseG1GC");
        command.add("-XX:MaxGCPauseMillis=200");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapMb + "m");
        command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + physicalCeilingMb + "m");
        command.addAll(SubprocessBackendFlags.jvmFlags(null,
                learningBackendPreference(childClasspath)));
        command.add("-cp");
        command.add(childClasspath);
        command.add(LOCAL_CHILD_MAIN);
        command.add(argsFile.toAbsolutePath().normalize().toString());
        Map<String, String> environment = new LinkedHashMap<>();
        SubprocessEnvironmentPropagator.propagateToEnvironment(environment);
        return new LaunchSpec(command, environment, "local-classpath:" + LOCAL_CHILD_MAIN);
    }

    private static LaunchSpec installedLaunchSpec(Path argsFile) throws Exception {
        ComponentRegistry registry = new ComponentRegistry();
        String explicitExecutable = firstNonBlank(
                System.getProperty(EXECUTABLE_PROPERTY), System.getenv(EXECUTABLE_ENV));
        String explicitJar = firstNonBlank(System.getProperty(JAR_PROPERTY), System.getenv(JAR_ENV));
        Path artifact;
        boolean nativeExecutable;
        if (explicitExecutable != null) {
            artifact = Path.of(explicitExecutable).toAbsolutePath().normalize();
            nativeExecutable = true;
        } else if (explicitJar != null) {
            artifact = Path.of(explicitJar).toAbsolutePath().normalize();
            nativeExecutable = false;
        } else {
            File distributionJar = registry.getDistributionJarPath(ComponentRegistry.KOMPILE_APP_MAIN);
            File componentJar = registry.getJarPath(ComponentRegistry.KOMPILE_APP_MAIN);
            File distributionBinary = registry.getDistributionBinaryPath(ComponentRegistry.KOMPILE_APP_MAIN);
            File preferredJar = newerExecutableJar(distributionJar, componentJar);
            File installed = preferredJar != null ? preferredJar
                    : distributionBinary != null ? distributionBinary
                    : registry.findInstalledJar(ComponentRegistry.KOMPILE_APP_MAIN);
            if (installed == null) {
                throw new IOException("The project-local learning subprocess requires the full Kompile "
                        + "distribution (lib/kompile-server.jar or bin/kompile-server). Install "
                        + "kompile-app-main before running a learning-enabled local crawl.");
            }
            artifact = installed.toPath().toAbsolutePath().normalize();
            nativeExecutable = !artifact.getFileName().toString().endsWith(".jar");
        }
        if (!Files.isRegularFile(artifact)) {
            throw new IOException("Configured learning runtime does not exist: " + artifact);
        }
        if (!nativeExecutable && !isExecutableJar(artifact)) {
            throw new IOException("Refusing to launch a thin app-main library JAR for learning: "
                    + artifact + ". Install the executable kompile-server.jar.");
        }
        return buildLaunchSpec(artifact, nativeExecutable, argsFile,
                intProperty(HEAP_MB_PROPERTY, DEFAULT_HEAP_MB));
    }

    private static File newerExecutableJar(File distributionJar, File componentJar) {
        boolean distributionReady = distributionJar != null && distributionJar.isFile()
                && isExecutableJar(distributionJar.toPath());
        boolean componentReady = componentJar != null && componentJar.isFile()
                && isExecutableJar(componentJar.toPath());
        if (!distributionReady) return componentReady ? componentJar : null;
        if (!componentReady) return distributionJar;
        return componentJar.lastModified() >= distributionJar.lastModified()
                ? componentJar : distributionJar;
    }

    static LaunchSpec buildLaunchSpec(Path artifact, boolean nativeExecutable,
                                      Path argsFile, int heapMb) throws IOException {
        long offHeapMb = Math.max(heapMb, (long) heapMb * 4L);
        long physicalCeilingMb = systemPhysicalCeilingMb(offHeapMb);
        String childClasspath = nativeExecutable ? null : resolveBootClasspath(artifact);
        List<String> command = new ArrayList<>();
        if (nativeExecutable) {
            command.add(artifact.toString());
        } else {
            command.add(JavaRuntimeLocator.javaExecutable());
        }
        command.add("-Xmx" + heapMb + "m");
        command.addAll(SubprocessEnvironmentPropagator.buildSystemPropertyFlags(
                childClasspath));
        if (!nativeExecutable) {
            command.add("-XX:+UseG1GC");
            command.add("-XX:MaxGCPauseMillis=200");
            command.add("-XX:+ExitOnOutOfMemoryError");
        }
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapMb + "m");
        command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + physicalCeilingMb + "m");
        // Match the managed learning launcher: use CPU when the child actually carries it so
        // reasoning learning does not compete with serving on the GPU lane. A CUDA-only bundle
        // safely inherits its sole available provider instead of being forced to load a missing one.
        command.addAll(SubprocessBackendFlags.jvmFlags(null,
                learningBackendPreference(childClasspath)));
        if (!nativeExecutable) {
            // Spring Boot's nested loader cannot reliably address multi-gigabyte ZIP64 dependency
            // offsets. Use the same expanded BOOT-INF classpath strategy as ManagedSubprocessLauncher.
            command.add("-cp");
            command.add(childClasspath);
            command.add("ai.kompile.app.MainApplication");
        }
        command.add("--subprocess=learning");
        command.add(argsFile.toAbsolutePath().normalize().toString());

        Map<String, String> environment = new LinkedHashMap<>();
        SubprocessEnvironmentPropagator.propagateToEnvironment(environment);
        File distHome = ComponentRegistry.inferDistributionHome(artifact);
        if (distHome != null) environment.put("KOMPILE_DIST_HOME", distHome.getAbsolutePath());
        return new LaunchSpec(command, environment, artifact.toString());
    }

    private static BackendPreference learningBackendPreference(String childClasspath) {
        if (childClasspath == null || childClasspath.isBlank()) {
            return BackendPreference.INHERIT;
        }
        for (String entry : childClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String name = Path.of(entry).getFileName().toString();
            if ((name.startsWith("nd4j-native-") && !name.startsWith("nd4j-native-api-"))
                    || name.equals("nd4j-native.jar")) {
                return BackendPreference.CPU;
            }
        }
        return BackendPreference.INHERIT;
    }

    /**
     * Resolve ND4J CPU backend jars for a bare classpath child: explicit override first, then the
     * Kompile distribution bundle (lib/backends/cpu, lib/backends), then the local Maven
     * repository closure. Empty result means nothing resolvable anywhere.
     */
    static List<Path> resolveBackendJars() {
        String explicit = firstNonBlank(
                System.getProperty(BACKEND_JARS_PROPERTY), System.getenv(BACKEND_JARS_ENV));
        if (explicit != null && !explicit.isBlank()) {
            List<Path> jars = new ArrayList<>();
            for (String entry : explicit.split(File.pathSeparator)) {
                Path jar = Path.of(entry.trim());
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
            return List.copyOf(jars);
        }
        Path distHome = distributionHome();
        if (distHome != null) {
            for (String candidate : new String[] {"lib/backends/cpu", "lib/backends"}) {
                List<Path> bundled = jarsInDirectory(distHome.resolve(candidate));
                if (!bundled.isEmpty()) {
                    return bundled;
                }
            }
        }
        return mavenBackendClosure();
    }

    private static Path distributionHome() {
        String home = firstNonBlank(
                System.getProperty("kompile.dist.home"), System.getenv("KOMPILE_DIST_HOME"));
        if (home == null || home.isBlank()) {
            return null;
        }
        Path path = Path.of(home);
        return Files.isDirectory(path) ? path : null;
    }

    private static List<Path> jarsInDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Artifacts required for a working nd4j-native CPU backend closure. */
    private static final String[] CPU_BACKEND_ARTIFACTS = {
            "nd4j-native", "nd4j-native-api", "nd4j-native-preset",
            "nd4j-presets-common", "nd4j-cpu-backend-common" };
    private static final String[] BYTEDECO_ARTIFACTS = {"javacpp", "openblas", "mkl"};

    private static List<Path> mavenBackendClosure() {
        List<Path> jars = new ArrayList<>();
        Path repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        String platform = childPlatform();
        for (String artifact : CPU_BACKEND_ARTIFACTS) {
            Path dir = newestVersionDirectory(
                    repo.resolve("org/eclipse/deeplearning4j").resolve(artifact));
            Path base = newestBaseJar(dir, artifact);
            if (base == null && artifact.equals("nd4j-native")) {
                // No CPU backend in the repository at all: force the actionable failure path.
                return List.of();
            }
            if (base != null) {
                jars.add(base);
            }
            Path platformJar = newestJarEndingWith(dir, "-" + platform + ".jar");
            if (platformJar != null) {
                jars.add(platformJar);
            }
        }
        for (String artifact : BYTEDECO_ARTIFACTS) {
            Path dir = newestVersionDirectory(repo.resolve("org/bytedeco").resolve(artifact));
            if (dir == null) {
                continue;
            }
            Path base = newestBaseJar(dir, artifact);
            if (base != null) {
                jars.add(base);
            }
            Path platformJar = newestJarEndingWith(dir, "-" + platform + ".jar");
            if (platformJar != null) {
                jars.add(platformJar);
            }
            if (artifact.equals("mkl")) {
                Path redist = newestJarEndingWith(dir, "-" + platform + "-redist.jar");
                if (redist != null) {
                    jars.add(redist);
                }
            }
        }
        return List.copyOf(jars);
    }

    private static String childPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "linux-arm64" : "linux-x86_64";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "macosx-arm64" : "macosx-x86_64";
        }
        if (os.contains("windows")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "windows-arm64" : "windows-x86_64";
        }
        return "linux-x86_64";
    }

    private static Path newestVersionDirectory(Path artifactRoot) {
        if (artifactRoot == null || !Files.isDirectory(artifactRoot)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(artifactRoot)) {
            return versions
                    .filter(Files::isDirectory)
                    .max(java.util.Comparator.comparingLong(
                            ProjectLocalLearningSubprocessExecutor::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Newest jar whose name is exactly {@code artifact-<version>.jar} (no platform classifier). */
    private static Path newestBaseJar(Path artifactDir, String artifact) {
        if (artifactDir == null || !Files.isDirectory(artifactDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(artifactDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        if (!name.startsWith(artifact + "-") || !name.endsWith(".jar")) {
                            return false;
                        }
                        String middle = name.substring(
                                artifact.length() + 1, name.length() - ".jar".length());
                        return !middle.contains("-linux") && !middle.contains("-macosx")
                                && !middle.contains("-windows") && !middle.contains("-android")
                                && !middle.contains("-ios") && !middle.contains("-redist");
                    })
                    .max(java.util.Comparator.comparingLong(
                            ProjectLocalLearningSubprocessExecutor::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path newestJarEndingWith(Path artifactDir, String suffix) {
        if (artifactDir == null || !Files.isDirectory(artifactDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(artifactDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !path.getFileName().toString().contains("sources"))
                    .filter(path -> !path.getFileName().toString().contains("javadoc"))
                    .max(java.util.Comparator.comparingLong(
                            ProjectLocalLearningSubprocessExecutor::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String resolveBootClasspath(Path executableJar) throws IOException {
        Path jar = executableJar.toAbsolutePath().normalize();
        Path cache = jar.resolveSibling(jar.getFileName() + ".boot-inf-extracted");
        Path stamp = cache.resolve(".source-stamp");
        String sourceStamp = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
        if (!Files.isRegularFile(stamp)
                || !sourceStamp.equals(Files.readString(stamp, StandardCharsets.UTF_8))) {
            deleteTree(cache);
            Path classes = cache.resolve("classes");
            Path libraries = cache.resolve("lib");
            Files.createDirectories(classes);
            Files.createDirectories(libraries);
            try (JarFile source = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    Path target = null;
                    if (entry.getName().startsWith("BOOT-INF/classes/")) {
                        target = classes.resolve(entry.getName().substring("BOOT-INF/classes/".length()))
                                .normalize();
                        if (!target.startsWith(classes)) {
                            throw new IOException("Unsafe BOOT-INF/classes entry: " + entry.getName());
                        }
                    } else if (entry.getName().startsWith("BOOT-INF/lib/")
                            && entry.getName().endsWith(".jar")) {
                        target = libraries.resolve(entry.getName().substring("BOOT-INF/lib/".length()))
                                .normalize();
                        if (!target.startsWith(libraries)) {
                            throw new IOException("Unsafe BOOT-INF/lib entry: " + entry.getName());
                        }
                    }
                    if (target == null) continue;
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    try (InputStream input = source.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (Exception e) {
                deleteTree(cache);
                if (e instanceof IOException io) throw io;
                throw new IOException("Failed to expand executable server JAR: " + e.getMessage(), e);
            }
            Files.writeString(stamp, sourceStamp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        List<String> entries = new ArrayList<>();
        entries.add(cache.resolve("classes").toString());
        Path libraries = cache.resolve("lib");
        if (Files.isDirectory(libraries)) {
            try (var stream = Files.list(libraries)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .map(Path::toString)
                        .forEach(entries::add);
            }
        }
        if (entries.size() == 1) {
            throw new IOException("Executable server JAR has no BOOT-INF libraries: " + jar);
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void validateGraphIdentity(UnifiedGraph before, UnifiedGraph after, Plan plan)
            throws IOException {
        if (!Objects.equals(before.graphId(), after.graphId())) {
            throw new IOException("Learning subprocess changed graphId");
        }
        if (!Objects.equals(before.factSheetId(), after.factSheetId())) {
            throw new IOException("Learning subprocess changed factSheetId");
        }
        boolean preservePrimaryEmbedding = !plan.embedding().enabled();
        if (!entityStructures(before, preservePrimaryEmbedding)
                .equals(entityStructures(after, preservePrimaryEmbedding))) {
            throw new IOException("Learning subprocess changed graph entity structure");
        }
        if (!relationStructures(before).equals(relationStructures(after))) {
            throw new IOException("Learning subprocess changed graph relation structure");
        }
        validateNonLearningAssets(before, after, plan);
        if (plan.embedding().enabled() && !before.relations().isEmpty()
                && (after.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER) == null
                || after.vectorLayer(UnifiedGraphKgeLifecycle.RELATION_LAYER) == null
                || after.artifact(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT) == null)) {
            throw new IOException("Learning subprocess did not produce the requested KGE assets");
        }
        if (plan.reasoning().enabled() && !before.relations().isEmpty()
                && (after.artifact(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT) == null
                || after.artifact(UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT) == null
                || !"COMPLETED".equals(String.valueOf(
                        after.meta().get("reasoningLearning.status"))))) {
            throw new IOException("Learning subprocess did not produce the requested reasoning assets");
        }
    }

    private Map<String, JsonNode> entityStructures(UnifiedGraph graph,
                                                   boolean includePrimaryEmbedding) {
        Map<String, JsonNode> structures = new LinkedHashMap<>();
        graph.entities().stream().sorted(java.util.Comparator.comparing(GraphEntity::id))
                .forEach(entity -> {
                    ObjectNode value = mapper.createObjectNode();
                    value.put("type", entity.type());
                    value.put("label", entity.label());
                    value.put("weight", entity.weight());
                    value.put("confidence", entity.confidence());
                    value.set("tags", mapper.valueToTree(new TreeSet<>(entity.tags())));
                    value.put("timestamp", entity.timestamp() == null ? null
                            : entity.timestamp().toString());
                    if (includePrimaryEmbedding) {
                        value.set("embedding", mapper.valueToTree(entity.embedding()));
                    }
                    value.set("attributes", mapper.valueToTree(entity.attributes()));
                    structures.put(entity.id(), value);
                });
        return structures;
    }

    private Map<String, JsonNode> relationStructures(UnifiedGraph graph) {
        Map<String, JsonNode> structures = new LinkedHashMap<>();
        graph.relations().stream().sorted(java.util.Comparator.comparing(GraphRelation::id))
                .forEach(relation -> {
                    ObjectNode value = mapper.createObjectNode();
                    value.put("sourceId", relation.sourceId());
                    value.put("targetId", relation.targetId());
                    value.put("type", relation.type());
                    value.put("weight", relation.weight());
                    value.put("confidence", relation.confidence());
                    value.put("directed", relation.directed());
                    value.set("tags", mapper.valueToTree(new TreeSet<>(relation.tags())));
                    value.put("timestamp", relation.timestamp() == null ? null
                            : relation.timestamp().toString());
                    value.set("embedding", mapper.valueToTree(relation.embedding()));
                    value.set("attributes", mapper.valueToTree(relation.attributes()));
                    structures.put(relation.id(), value);
                });
        return structures;
    }

    private void validateNonLearningAssets(UnifiedGraph before, UnifiedGraph after, Plan plan)
            throws IOException {
        Set<String> beforeLayers = new LinkedHashSet<>(before.vectorLayers().keySet());
        Set<String> afterLayers = new LinkedHashSet<>(after.vectorLayers().keySet());
        if (plan.embedding().enabled()) {
            beforeLayers.remove(UnifiedGraphKgeLifecycle.ENTITY_LAYER);
            beforeLayers.remove(UnifiedGraphKgeLifecycle.RELATION_LAYER);
            afterLayers.remove(UnifiedGraphKgeLifecycle.ENTITY_LAYER);
            afterLayers.remove(UnifiedGraphKgeLifecycle.RELATION_LAYER);
        }
        if (!beforeLayers.equals(afterLayers)) {
            throw new IOException("Learning subprocess changed unrelated vector layers");
        }
        for (String name : beforeLayers) {
            var left = before.vectorLayer(name);
            var right = after.vectorLayer(name);
            if (right == null || left.target() != right.target() || left.dim() != right.dim()
                    || left.dtype() != right.dtype() || !left.ids().equals(right.ids())) {
                throw new IOException("Learning subprocess changed vector layer " + name);
            }
            for (String id : left.ids()) {
                if (!Arrays.equals(left.get(id), right.get(id))) {
                    throw new IOException("Learning subprocess changed vector layer row "
                            + name + "/" + id);
                }
            }
        }
        // Entity opinions may legitimately CHANGE or GROW: the learning lifecycle projects
        // trained State posteriors onto entities (reasoningLearning.projectedEntityIds meta).
        // The guard protects NON-learning opinions only: any before-opinion on an entity the
        // learning did NOT project to must survive unchanged. Opinions on projected entities are
        // learning-owned and expected to move between runs.
        Set<String> learningProjectedIds = learningProjectedEntityIds(after);
        for (var entry : before.entityOpinions().entrySet()) {
            if (learningProjectedIds.contains(entry.getKey())) {
                continue;
            }
            if (!entry.getValue().equals(after.entityOpinion(entry.getKey()))) {
                throw new IOException("Learning subprocess changed non-learning entity opinion "
                        + entry.getKey());
            }
        }
        if (!before.relationOpinions().equals(after.relationOpinions())) {
            throw new IOException("Learning subprocess changed relation opinions");
        }
        if (!before.weightMaps().equals(after.weightMaps())) {
            throw new IOException("Learning subprocess changed unrelated weight maps");
        }

        Set<String> beforeArtifacts = nonLearningArtifacts(before, plan);
        Set<String> afterArtifacts = nonLearningArtifacts(after, plan);
        if (!beforeArtifacts.equals(afterArtifacts)) {
            throw new IOException("Learning subprocess changed unrelated artifact inventory");
        }
        for (String name : beforeArtifacts) {
            if (!Arrays.equals(before.artifact(name), after.artifact(name))) {
                throw new IOException("Learning subprocess changed unrelated artifact " + name);
            }
        }

        Map<String, Object> beforeMeta = nonLearningMeta(before, plan);
        Map<String, Object> afterMeta = nonLearningMeta(after, plan);
        if (!mapper.valueToTree(beforeMeta).equals(mapper.valueToTree(afterMeta))) {
            throw new IOException("Learning subprocess changed unrelated graph metadata");
        }
    }

    /**
     * Entity ids whose opinions the learning lifecycle owns (projected posteriors).
     *
     * <p>New archives carry an ordered JSON list, so opaque ids (including ids containing commas)
     * remain lossless. Older archives used a comma-delimited string; that compatibility form is
     * necessarily unable to recover a comma-containing id, but duplicate tokens are harmlessly
     * de-duplicated. Any malformed metadata fails closed, preserving the non-learning opinion guard.</p>
     */
    static Set<String> learningProjectedEntityIds(UnifiedGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Object meta = graph.meta().get(PROJECTED_ENTITY_IDS_META);
        if (meta instanceof List<?> values) {
            return structuredProjectedEntityIds(values);
        }
        if (meta instanceof String legacy) {
            return legacyProjectedEntityIds(legacy);
        }
        return Set.of();
    }

    private static Set<String> structuredProjectedEntityIds(List<?> values) {
        if (values.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String id) || id.isBlank()) {
                return Set.of();
            }
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    private static Set<String> legacyProjectedEntityIds(String encoded) {
        if (encoded.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String value : encoded.split(",", -1)) {
            if (value.isBlank()) {
                return Set.of();
            }
            ids.add(value);
        }
        return Set.copyOf(ids);
    }

    private Set<String> nonLearningArtifacts(UnifiedGraph graph, Plan plan) {
        Set<String> names = new LinkedHashSet<>();
        graph.artifacts().keySet().stream()
                .filter(name -> !(plan.embedding().enabled()
                        && UnifiedGraphKgeLifecycle.MODEL_ARTIFACT.equals(name)))
                .filter(name -> !(plan.reasoning().enabled() && name.startsWith("reasoning/")))
                .forEach(names::add);
        return names;
    }

    private Map<String, Object> nonLearningMeta(UnifiedGraph graph, Plan plan) {
        Map<String, Object> values = new LinkedHashMap<>();
        graph.meta().forEach((key, value) -> {
            boolean learningExecution = key.startsWith("learning.");
            boolean embedding = plan.embedding().enabled()
                    && (key.startsWith("embedding") || "phase.embeddingLearning".equals(key));
            boolean reasoning = plan.reasoning().enabled()
                    && (key.startsWith("reasoningLearning.")
                    || INCREMENTAL_PSL_METADATA_KEYS.contains(key));
            if (!learningExecution && !embedding && !reasoning) {
                values.put(key, value);
            }
        });
        return values;
    }

    private static boolean isExecutableJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Attributes attributes = file.getManifest() == null ? null
                    : file.getManifest().getMainAttributes();
            String main = attributes == null ? null : attributes.getValue(Attributes.Name.MAIN_CLASS);
            return main != null && (main.contains("JarLauncher")
                    || main.equals("ai.kompile.app.MainApplication"));
        } catch (Exception e) {
            return false;
        }
    }

    private static long systemPhysicalCeilingMb(long floorMb) {
        try {
            long total = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            double fraction = Double.parseDouble(
                    System.getProperty("kompile.subprocess.maxphysical-fraction", "0.95"));
            return Math.max(floorMb, (long) (total / (1024.0 * 1024.0) * fraction));
        } catch (Throwable ignored) {
            return floorMb * 3L;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary learning files are best-effort cleanup and must not mask the real result.
        }
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            long gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (process.isAlive() && System.nanoTime() < gracefulDeadline) {
                try {
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                long forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (process.isAlive() && System.nanoTime() < forcedDeadline) {
                    try {
                        process.waitFor(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(Deque<String> lines) {
        synchronized (lines) {
            if (lines.isEmpty()) return "";
            return "\nSubprocess stderr tail:\n" + String.join("\n", lines);
        }
    }

    private static boolean containsOom(Deque<String> lines) {
        synchronized (lines) {
            return lines.stream().anyMatch(line -> line.contains("OutOfMemoryError")
                    || line.contains("Cannot allocate memory"));
        }
    }

    private static int intProperty(String key, int fallback) {
        try {
            return Math.max(256, Integer.parseInt(System.getProperty(key, Integer.toString(fallback))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longProperty(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
