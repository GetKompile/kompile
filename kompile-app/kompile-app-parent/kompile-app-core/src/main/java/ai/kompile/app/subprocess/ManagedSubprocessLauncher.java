/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PreDestroy;
import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import ai.kompile.utils.NativeImageInfo;

/**
 * Shared base for every managed JVM subprocess launcher in Kompile.
 *
 * <p><b>Why this exists.</b> Embedding, learning, serving, graph, VLM, reranking,
 * graph-compute … each used to re-implement the same machinery: build the {@code java}
 * command (heap, GC, the all-important JavaCPP {@code maxphysicalbytes} native cap,
 * classpath), spawn the process, register it with {@link SubprocessRegistry}, drain
 * stdout/stderr, and wire {@link RestartableSubprocess}. That duplication is exactly
 * what this class removes: a new subprocess type only declares its id, main class,
 * heap/native budget, and (optionally) its structured-message prefix and handler.</p>
 *
 * <p><b>Native-memory containment.</b> ND4J/SameDiff runs only in subprocesses so a
 * native OOM is killable and bounded. This base always passes
 * {@code -Dorg.bytedeco.javacpp.maxbytes} / {@code maxphysicalbytes} (default 4× heap),
 * so every subprocess type inherits the cap by construction.</p>
 *
 * <p><b>Live logs + lifecycle.</b> Unlike the old launchers that {@code INHERIT}ed
 * stderr to the parent (invisible to operators), this base reads <em>both</em> streams
 * and republishes every line — plus STARTING/STARTED/STOPPED lifecycle transitions — to
 * the {@link SubprocessLogBus}. A crawl that owns a subprocess's lifecycle subscribes a
 * per-job listener so the subprocess's own output streams to the crawl UI in real time.</p>
 *
 * <p>Subclasses are Spring beans; the {@code @Autowired} fields below are injected into
 * the subclass instance. Concrete behaviour is added by overriding the abstract config
 * getters and calling {@link #startProcess}.</p>
 */
public abstract class ManagedSubprocessLauncher implements RestartableSubprocess, BackendConfigurable {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired(required = false)
    protected SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    protected SubprocessLogBus logBus;

    /** In-flight runs keyed by runId, so {@link #requestRestart}/{@link #stopAll} can reach them. */
    private final ConcurrentHashMap<String, ManagedRun> activeRuns = new ConcurrentHashMap<>();

    /** Set when shutdown has begun; prevents requestRestart from spawning new threads after shutdown. */
    private final java.util.concurrent.atomic.AtomicBoolean shutdownStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

    // ── abstract / overridable configuration ──────────────────────────────────

    /** Stable subprocess id, also the registry + restart-handler key (e.g. {@code "embedding"}). */
    @Override
    public abstract String getSubprocessId();

    /** Human-readable type label for {@link SubprocessRegistry} (e.g. {@code "reranker"}). */
    protected abstract String getTypeLabel();

    /** Fully-qualified main class run in the child JVM. */
    protected abstract String getMainClass();

    /** Child JVM max heap in MB. */
    protected abstract int getHeapMb();

    /** JavaCPP native cap in MB; {@code 0} ⇒ auto (4 × heap). */
    protected long getMaxPhysicalMb() {
        return 0L;
    }

    /**
     * Prefix marking a structured-protocol line on the child's stdout
     * (e.g. {@code "LEARNING_MSG:"}). When a stdout line starts with this, it is routed
     * to the {@link StructuredLineHandler} instead of being published as a log line.
     * {@code null}/blank ⇒ the subprocess has no structured protocol; all stdout is logs.
     */
    protected String getStructuredMessagePrefix() {
        return null;
    }

    /**
     * The {@code --subprocess=} type token the unified native binary dispatches on.
     *
     * <p>In native self-exec mode (GraalVM native image with no classpath),
     * {@link #buildJvmCommand} passes {@code --subprocess=<type>} as the first
     * program argument so {@code MainApplication.main()} routes to this subprocess's
     * main class. Defaults to {@link #getSubprocessId()}; override when the dispatch
     * type key differs from the subprocess id (e.g. the id is a registry handle but
     * the switch-case key is different).</p>
     */
    protected String getSubprocessDispatchType() {
        return getSubprocessId();
    }

    /** Extra JVM flags appended after the standard set (GC tuning, system props, …). */
    protected List<String> getExtraJvmArgs() {
        return List.of();
    }

    /**
     * Which ND4J compute backend this subprocess should select.
     *
     * <p>Backend selection in {@link org.nd4j.linalg.factory.Nd4jBackend#load()} is driven by the
     * ServiceLoader ordering properties {@code org.nd4j.cpu.priority} / {@code org.nd4j.gpu.priority}
     * — NOT by {@code nd4j.backend.priority}, which only orders {@code BackendManager}'s device list
     * and is inert for selection. On a classpath carrying both {@code nd4j-native} and
     * {@code nd4j-cuda} (the dual-backend jar) both priorities default to 0, so {@code JCublasBackend}
     * wins the tie and a subprocess with no GPU role loads CUDA anyway and crashes on a device context
     * it never set up. Declaring a preference here makes selection explicit and device-abstract — via
     * ND4J's own backend-priority mechanism, with no CUDA-specific environment variables.</p>
     */
    public enum BackendPreference { INHERIT, CPU, GPU }

    /** Default {@link BackendPreference#INHERIT}: emit no selection flags (legacy behaviour). */
    protected BackendPreference getBackendPreference() {
        return BackendPreference.INHERIT;
    }

    /**
     * Scheduler-assigned placement for the NEXT spawn (device-agnostic). When set it overrides the
     * static {@link #getBackendPreference()} and delivers the device + memory bound via ND4J's real
     * knobs: {@code nd4j.placement.defaultDevice} for device select, {@code nd4j.environment.maxDeviceMemory}
     * for the physical environment cap, and {@code SD_MAX_DEVICE_BYTES} for early native process setup.
     * NEVER {@code CUDA_VISIBLE_DEVICES}. Set through {@link #applyPlacement}.
     */
    private volatile SubprocessPlacement schedulerPlacement;

    /** {@link BackendConfigurable} contract — the scheduler sets placement before spawn. */
    public void applyPlacement(SubprocessPlacement placement) {
        this.schedulerPlacement = placement;
    }

    /**
     * Translate the effective backend + device into real ND4J ServiceLoader/placement flags via the
     * shared {@link SubprocessBackendFlags} — the SAME device-agnostic delivery every standalone
     * launcher uses, so there is one code path, not per-type copies.
     */
    private List<String> backendSelectionFlags() {
        return SubprocessBackendFlags.jvmFlags(schedulerPlacement, getBackendPreference());
    }

    /**
     * Hook to set environment variables on the child process (e.g. ND4J/CUDA device
     * routing). Default: inherit the parent environment unchanged.
     */
    protected void configureEnvironment(Map<String, String> env) {
        // Propagate the parent's ND4J/CUDA/threading/Triton environment variables to the subprocess
        // (single source of truth). Subclasses overriding this should also call super.
        SubprocessEnvironmentPropagator.propagateToEnvironment(env);
        // Scheduler-assigned early native memory bound. The matching physical ND4J cap is emitted by
        // backendSelectionFlags() as nd4j.environment.maxDeviceMemory and applied by child startup code.
        SubprocessBackendFlags.applyEnv(env, schedulerPlacement);
    }

    // ── command building ──────────────────────────────────────────────────────

    /**
     * Build the full {@code java … MainClass <programArgs>} command, including the
     * native-memory cap and an expanded classpath that works from a Spring-Boot uber jar.
     */
    /**
     * Resolve the {@code maxphysicalbytes} ceiling (MB). JavaCPP's physical check is SYSTEM-WIDE, so this
     * must be a near-machine-total guard — only tripping when the WHOLE box is genuinely out of memory —
     * never the per-process off-heap budget (which is {@code maxbytes}). Returns the larger of the
     * per-process floor and {@code totalPhysical × fraction}. Fraction configurable on the fly via
     * {@code -Dkompile.subprocess.maxphysical-fraction} (default 0.95); falls back to 3× the floor when the
     * total-memory MXBean is unavailable.
     */
    protected long resolveSystemPhysicalCeilingMb(long offHeapFloorMb) {
        try {
            long totalBytes = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            double fraction = Double.parseDouble(
                    System.getProperty("kompile.subprocess.maxphysical-fraction", "0.95"));
            long ceilingMb = (long) (totalBytes / (1024.0 * 1024.0) * fraction);
            return Math.max(offHeapFloorMb, ceilingMb);
        } catch (Throwable t) {
            return offHeapFloorMb * 3L;
        }
    }

    protected List<String> buildJvmCommand(List<String> programArgs) {
        // In GraalVM native image mode (no classpath available) re-exec the unified binary so
        // MainApplication dispatches to this subprocess type via --subprocess=<type>.
        if (NativeImageInfo.isRunningInNativeImage() && !NativeImageInfo.hasClasspath()) {
            return buildNativeSelfExecCommand(programArgs);
        }
        return buildJvmClasspathCommand(programArgs);
    }

    /**
     * Build the native self-exec command used when the launcher is running inside a GraalVM
     * native image and there is no classpath to fork a child JVM from.
     *
     * <p>GraalVM native binaries accept {@code -Xmx} and {@code -D} runtime flags but reject
     * unknown HotSpot flags ({@code -XX:+UseG1GC}, etc.), and there is no classpath — the image
     * re-execs itself and {@code MainApplication} dispatches on {@code --subprocess=}. Only
     * {@code -Xm*} and {@code -D*} flags from {@link #getExtraJvmArgs()} are forwarded; other
     * flags (GC tuning, etc.) are silently dropped because they are JVM-only.</p>
     */
    private List<String> buildNativeSelfExecCommand(List<String> programArgs) {
        String selfExe = NativeImageInfo.getExecutablePath();
        if (selfExe == null || selfExe.isBlank()) {
            log.warn("[{}] native self-exec: could not determine executable path; falling back to JVM command",
                    getSubprocessId());
            return buildJvmClasspathCommand(programArgs);
        }

        long physicalMb = getMaxPhysicalMb() > 0 ? getMaxPhysicalMb() : (long) getHeapMb() * 4L;

        List<String> cmd = new ArrayList<>();
        cmd.add(selfExe);
        cmd.add("-Xmx" + getHeapMb() + "m");
        // Forward ND4J/JavaCPP system properties (same set as the JVM path).
        cmd.addAll(SubprocessEnvironmentPropagator.buildSystemPropertyFlags());
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dorg.bytedeco.javacpp.maxbytes=" + physicalMb + "m");
        cmd.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + resolveSystemPhysicalCeilingMb(physicalMb) + "m");
        cmd.addAll(backendSelectionFlags());
        // From getExtraJvmArgs(), forward only -D and -Xm* flags — GraalVM native rejects -XX:.
        for (String extra : getExtraJvmArgs()) {
            if (extra.startsWith("-D") || extra.startsWith("-Xm")) {
                cmd.add(extra);
            }
        }
        // Dispatch type token: MainApplication.dispatchSubprocess() switches on this value.
        cmd.add("--subprocess=" + getSubprocessDispatchType());
        if (programArgs != null) {
            cmd.addAll(programArgs);
        }
        return cmd;
    }

    /**
     * Build the standard {@code java -cp … MainClass <programArgs>} command used in JVM mode.
     */
    private List<String> buildJvmClasspathCommand(List<String> programArgs) {
        String javaPath = ProcessHandle.current().info().command().orElse("java");

        long physicalMb = getMaxPhysicalMb() > 0 ? getMaxPhysicalMb() : (long) getHeapMb() * 4L;
        String childClasspath = resolveClasspath();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xmx" + getHeapMb() + "m");
        // Forward the parent's ND4J/JavaCPP/threading system properties (graph-capture/freeze toggles,
        // backend, threads, debug/verbose, …) so EVERY managed subprocess runs the SAME configured ND4J
        // environment as the main app. Added BEFORE the per-subprocess javacpp cap below so that cap
        // (subprocess-specific) overrides any forwarded org.bytedeco.javacpp.max* value.
        cmd.addAll(SubprocessEnvironmentPropagator.buildSystemPropertyFlags(childClasspath));
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:MaxGCPauseMillis=200");
        cmd.add("-XX:+ExitOnOutOfMemoryError");
        cmd.add("-Dfile.encoding=UTF-8");
        // Native-memory bounds for ND4J/JavaCPP — the reason ND4J lives in a subprocess.
        //  • maxbytes = the PER-PROCESS off-heap cap (the real lever bounding THIS subprocess's ND4J arrays).
        //  • maxphysicalbytes gates JavaCPP's physicalBytes(), which on Linux measures SYSTEM-WIDE physical
        //    memory — NOT this process. Setting it equal to the per-process budget makes a heavy subprocess
        //    FALSE-OOM the instant ND4J initialises whenever the rest of the box (main app + sibling
        //    subprocesses) is already busy (observed: KGE died at Nd4j.<clinit> with physicalBytes=44G on a
        //    fresh subprocess). So it must be a near-machine-TOTAL ceiling (a last-resort whole-machine
        //    guard), never the per-process budget.
        cmd.add("-Dorg.bytedeco.javacpp.maxbytes=" + physicalMb + "m");
        cmd.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + resolveSystemPhysicalCeilingMb(physicalMb) + "m");
        cmd.addAll(backendSelectionFlags());
        cmd.addAll(getExtraJvmArgs());
        cmd.add("-cp");
        cmd.add(childClasspath);
        cmd.add(getMainClass());
        if (programArgs != null) {
            cmd.addAll(programArgs);
        }
        return cmd;
    }

    /**
     * Resolve a classpath usable by a child JVM.
     *
     * <p>When the app runs from a Spring-Boot uber jar, {@code java.class.path} is just the
     * fat jar — and {@code -cp fat.jar MainClass} cannot see classes under {@code BOOT-INF/}.
     * This expands {@code BOOT-INF/classes} and {@code BOOT-INF/lib/*.jar} into a sibling
     * {@code .boot-inf-extracted} directory and returns them as classpath entries. (The old
     * Learning launcher passed {@code java.class.path} verbatim — a latent bug that prevented
     * it starting from the uber jar; centralising the fix here fixes it for every subprocess.)</p>
     */
    protected static String resolveClasspath() {
        String sep = System.getProperty("path.separator");
        Set<String> entries = new LinkedHashSet<>();
        String systemCp = System.getProperty("java.class.path");
        if (systemCp != null && !systemCp.isBlank()) {
            for (String e : systemCp.split(sep)) {
                if (!e.isBlank()) {
                    entries.add(e);
                }
            }
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.endsWith(".jar") && isSpringBootFatJar(entry)) {
                try {
                    extractBootInf(entry, expanded);
                } catch (Exception e) {
                    LoggerFactory.getLogger(ManagedSubprocessLauncher.class)
                            .warn("Failed to expand BOOT-INF from {}: {}", entry, e.getMessage());
                }
            }
        }
        entries.addAll(expanded);
        return String.join(sep, entries);
    }

    private static boolean isSpringBootFatJar(String jarPath) {
        try (JarFile jar = new JarFile(jarPath)) {
            return jar.getEntry("BOOT-INF/lib/") != null || jar.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void extractBootInf(String fatJarPath, Set<String> out) throws IOException {
        Path fatJar = Path.of(fatJarPath).toAbsolutePath();
        Path extractDir = fatJar.getParent().resolve(".boot-inf-extracted");
        Path libDir = extractDir.resolve("lib");
        Path classesDir = extractDir.resolve("classes");
        try (JarFile jar = new JarFile(fatJarPath)) {
            if (jar.getEntry("BOOT-INF/classes/") != null) {
                Files.createDirectories(classesDir);
                Enumeration<JarEntry> es = jar.entries();
                while (es.hasMoreElements()) {
                    JarEntry e = es.nextElement();
                    if (e.getName().startsWith("BOOT-INF/classes/") && !e.isDirectory()) {
                        Path target = classesDir.resolve(e.getName().substring("BOOT-INF/classes/".length()));
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target)
                                || Files.getLastModifiedTime(target).toMillis() < e.getTime()) {
                            try (InputStream is = jar.getInputStream(e)) {
                                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
                out.add(classesDir.toString());
            }
            if (jar.getEntry("BOOT-INF/lib/") != null) {
                Files.createDirectories(libDir);
                Enumeration<JarEntry> es = jar.entries();
                while (es.hasMoreElements()) {
                    JarEntry e = es.nextElement();
                    if (e.getName().startsWith("BOOT-INF/lib/") && e.getName().endsWith(".jar")) {
                        Path target = libDir.resolve(e.getName().substring("BOOT-INF/lib/".length()));
                        if (!Files.exists(target)
                                || Files.getLastModifiedTime(target).toMillis() < e.getTime()) {
                            try (InputStream is = jar.getInputStream(e)) {
                                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        out.add(target.toString());
                    }
                }
            }
        }
    }

    // ── process lifecycle ─────────────────────────────────────────────────────

    /**
     * Start the subprocess, register it, and begin draining both streams to the
     * {@link SubprocessLogBus}. Returns immediately with a {@link ManagedRun} handle;
     * the caller waits on its own completion signal (latch, response future, …).
     *
     * @param runId             unique per invocation
     * @param jobId             owning crawl job id (may be {@code null})
     * @param programArgs       args appended after the main class
     * @param structuredHandler receives the payload of each structured stdout line
     *                          (the part after {@link #getStructuredMessagePrefix()});
     *                          may be {@code null} if there is no structured protocol
     */
    protected ManagedRun startProcess(String runId, String jobId, List<String> programArgs,
                                      StructuredLineHandler structuredHandler) throws IOException {
        List<String> cmd = buildJvmCommand(programArgs);
        log.debug("[{}] launching: {}", getSubprocessId(), cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false); // keep stderr separate so we can stream it distinctly
        configureEnvironment(pb.environment());
        Process process = pb.start();

        String registryId = getSubprocessId() + "-" + runId;
        if (subprocessRegistry != null) {
            subprocessRegistry.register(registryId, process, getTypeLabel());
            subprocessRegistry.registerRestartHandler(getSubprocessId(), this);
        }

        ManagedRun run = new ManagedRun(runId, jobId, registryId, process);
        activeRuns.put(runId, run);
        publishLifecycle(runId, jobId, "Subprocess '" + getSubprocessId() + "' started (PID "
                + process.pid() + ", heap " + getHeapMb() + "MB)");

        run.stdoutThread = drain(process.getInputStream(), runId, jobId,
                SubprocessLogEvent.Stream.STDOUT, structuredHandler, getSubprocessId() + "-stdout-" + runId);
        run.stderrThread = drain(process.getErrorStream(), runId, jobId,
                SubprocessLogEvent.Stream.STDERR, null, getSubprocessId() + "-stderr-" + runId);
        return run;
    }

    private Thread drain(InputStream stream, String runId, String jobId,
                         SubprocessLogEvent.Stream origin, StructuredLineHandler structuredHandler,
                         String threadName) {
        String prefix = getStructuredMessagePrefix();
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (origin == SubprocessLogEvent.Stream.STDOUT
                            && prefix != null && !prefix.isBlank() && line.startsWith(prefix)) {
                        if (structuredHandler != null) {
                            try {
                                structuredHandler.onStructured(line.substring(prefix.length()));
                            } catch (Exception ex) {
                                log.debug("[{}] structured handler error: {}", getSubprocessId(), ex.toString());
                            }
                        }
                    } else {
                        publishLog(runId, jobId, origin, inferLevel(line), line);
                    }
                }
            } catch (IOException e) {
                log.debug("[{}] {} reader closed: {}", getSubprocessId(), origin, e.getMessage());
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Mark a run finished and remove it from tracking (does not kill — used on normal completion). */
    protected void finishRun(String runId) {
        ManagedRun run = activeRuns.remove(runId);
        if (run != null) {
            // Emit a spin-down lifecycle event on normal completion too (stop() covers the killed
            // path) so an owning crawl sees both spin-up and shut-down for one-shot subprocesses.
            publishLifecycle(runId, run.jobId, "Subprocess '" + getSubprocessId() + "' finished");
            if (subprocessRegistry != null) {
                subprocessRegistry.deregister(run.registryId);
            }
        }
    }

    /** Forcibly stop one run. */
    protected void stop(String runId, String reason) {
        ManagedRun run = activeRuns.remove(runId);
        if (run == null) {
            return;
        }
        if (run.process.isAlive()) {
            publishLifecycle(runId, run.jobId, "Stopping subprocess '" + getSubprocessId()
                    + "' (" + reason + ")");
            run.process.destroy();
            try {
                if (!run.process.waitFor(3, TimeUnit.SECONDS)) {
                    run.process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                run.process.destroyForcibly();
            }
        }
        if (subprocessRegistry != null) {
            subprocessRegistry.deregister(run.registryId);
        }
        publishLifecycle(runId, run.jobId, "Subprocess '" + getSubprocessId() + "' stopped");
    }

    /** Forcibly stop every active run (used on shutdown / restart). */
    public void stopAll() {
        for (String runId : List.copyOf(activeRuns.keySet())) {
            stop(runId, "launcher shutdown");
        }
    }

    @PreDestroy
    public void shutdown() {
        shutdownStarted.set(true);
        if (!activeRuns.isEmpty()) {
            log.info("[{}] shutting down — stopping {} active run(s)", getSubprocessId(), activeRuns.size());
            stopAll();
        }
    }

    // ── RestartableSubprocess ─────────────────────────────────────────────────

    /**
     * Default restart: destroy active runs on a daemon thread (fire-and-forget so the
     * watchdog scheduler is never blocked). Persistent-process launchers (e.g. embedding)
     * override this with their crash/backoff/model-reload path.
     */
    @Override
    public void requestRestart(String reason) {
        if (shutdownStarted.get()) {
            log.debug("[{}] restart suppressed — launcher shutdown has started", getSubprocessId());
            return;
        }
        Thread t = new Thread(() -> {
            if (shutdownStarted.get()) {
                log.debug("[{}] restart thread suppressed — shutdown began before it ran", getSubprocessId());
                return;
            }
            log.warn("[{}] restart requested: {} — destroying {} active run(s)",
                    getSubprocessId(), reason, activeRuns.size());
            stopAll();
        }, getSubprocessId() + "-restart");
        t.setDaemon(true);
        t.start();
    }

    // ── log helpers ───────────────────────────────────────────────────────────

    protected void publishLog(String runId, String jobId, SubprocessLogEvent.Stream stream,
                              String level, String message) {
        if (logBus != null) {
            logBus.publish(SubprocessLogEvent.line(getSubprocessId(), runId, jobId, stream, level, message));
        }
        if ("ERROR".equals(level)) {
            log.warn("[{}/{}] {}", getSubprocessId(), stream, message);
        } else {
            log.debug("[{}/{}] {}", getSubprocessId(), stream, message);
        }
    }

    protected void publishLifecycle(String runId, String jobId, String message) {
        if (logBus != null) {
            logBus.publish(SubprocessLogEvent.lifecycle(getSubprocessId(), runId, jobId, message));
        }
        log.info("[{}] {}", getSubprocessId(), message);
    }

    /**
     * Matches the explicit logger level field after the {@code [thread]} bracket
     * (e.g. {@code 12:00:00 [main] ERROR o.k.Foo - msg}). Anchoring on the bracket's
     * closing {@code ]} keeps message bodies out of classification, so subprocess
     * output that merely contains the word "error" is never surfaced as an ERROR event.
     */
    private static final Pattern LEVEL_FIELD =
            Pattern.compile("^(?:[^\\]]*\\]\\s+)?(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b");

    /**
     * Infer a log level from a raw subprocess line. The level comes from the line's own
     * logger level field, NOT from substring-matching the message — only genuine JVM
     * crash markers (stack traces, OOM) are forced to ERROR irrespective of formatting.
     * Lines with no level field default to INFO.
     */
    private static String inferLevel(String line) {
        if (line == null) {
            return "INFO";
        }
        if (line.startsWith("\tat ") || line.startsWith("Caused by:")
                || line.contains("Exception in thread")
                || line.contains("OutOfMemoryError")
                || line.contains("USE-AFTER-FREE")) {
            return "ERROR";
        }
        Matcher m = LEVEL_FIELD.matcher(line);
        if (m.find()) {
            String lvl = m.group(1).toUpperCase(Locale.ROOT);
            return "WARNING".equals(lvl) ? "WARN" : lvl;
        }
        return "INFO";
    }

    // ── types ─────────────────────────────────────────────────────────────────

    /** Receives the payload (text after {@link #getStructuredMessagePrefix()}) of a structured stdout line. */
    @FunctionalInterface
    public interface StructuredLineHandler {
        void onStructured(String payloadJson);
    }

    /** Handle to a started subprocess run. */
    protected static final class ManagedRun {
        final String runId;
        final String jobId;
        final String registryId;
        final Process process;
        volatile Thread stdoutThread;
        volatile Thread stderrThread;

        ManagedRun(String runId, String jobId, String registryId, Process process) {
            this.runId = runId;
            this.jobId = jobId;
            this.registryId = registryId;
            this.process = process;
        }

        public Process process() {
            return process;
        }

        public String runId() {
            return runId;
        }

        public String jobId() {
            return jobId;
        }
    }
}
