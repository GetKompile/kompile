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

package ai.kompile.embedding.anserini.subprocess;

import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.RestartableSubprocess;
import ai.kompile.app.subprocess.SubprocessBackendResolver;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.NativeImageInfo;
import ai.kompile.utils.NativeRuntimePathSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.Deque;

/**
 * Manages the lifecycle of the embedding subprocess.
 *
 * This class:
 * - Launches the embedding subprocess on demand
 * - Sends commands via stdin and receives responses via stdout
 * - Monitors subprocess health via heartbeats
 * - Restarts subprocess if it crashes
 * - Handles graceful shutdown
 * - Forwards progress, phase transitions, and log messages to callbacks
 *
 * The subprocess runs SameDiff/ND4J in isolation, so the main application
 * JVM never loads these heavy native libraries.
 */
public class EmbeddingSubprocessLauncher implements AutoCloseable, RestartableSubprocess, BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingSubprocessLauncher.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    // Subprocess state
    private volatile Process process;
    private volatile BufferedReader processStdout;
    private volatile BufferedWriter processStdin;
    private volatile Thread outputReaderThread;
    private volatile Thread errorReaderThread;

    // Lock for stdin writes — processStdin is volatile (reassigned on restart),
    // so we must never synchronize on the field itself.
    private final Object stdinLock = new Object();

    // Request/response correlation
    private final ConcurrentHashMap<String, CompletableFuture<EmbeddingSubprocessMessage>> pendingRequests = new ConcurrentHashMap<>();

    // State tracking
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private static final int DEVICE_ERROR_EXIT_CODE = 78; // must match EmbeddingSubprocessMain.DEVICE_ERROR_EXIT_CODE
    /** Set when the embedding lane has encountered an unrecoverable device-level CUDA error. */
    private final AtomicBoolean laneUnavailable = new AtomicBoolean(false);
    private final AtomicLong lastHeartbeat = new AtomicLong(0);
    private volatile String currentModelId;
    private volatile int currentDimensions = -1;
    private volatile String encoderType = "UNKNOWN";
    private volatile boolean modelLoaded = false;
    private volatile long totalEmbeddingsProcessed = 0;

    // Recent error tracking for crash diagnostics
    private static final int MAX_RECENT_ERRORS = 20;
    private final ConcurrentLinkedDeque<String> recentErrors = new ConcurrentLinkedDeque<>();
    private volatile String lastCrashReason = null;

    // Restart tracking
    private volatile int restartAttempts = 0;
    private volatile int maxRestartAttempts = 3;
    private volatile long initialBackoffMs = 5000;
    private volatile double backoffMultiplier = 2.0;
    private volatile String currentTaskId = null;
    private volatile String currentFileName = null;
    private volatile RestartPolicyCallback restartPolicyCallback = null;

    // Configuration
    private final String javaHome;
    private final List<String> classpath;
    private final int maxHeapMb;
    /** JavaCPP maxphysicalbytes cap in MB (0 = 4 × maxHeapMb). */
    private final long maxPhysicalMb;
    private final long requestTimeoutMs;
    /** Separate timeout for LoadModel requests; CPU SameDiff warm-up takes ~111s+. */
    private final long loadModelTimeoutMs;
    private final long heartbeatTimeoutMs;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final boolean localModelOnly;

    // Native image configuration
    private final LaunchMode launchMode;
    private final String nativeExecutablePath;
    private final String subprocessTypeFlag;

    // Debug configuration
    private volatile DebugConfig debugConfig = new DebugConfig();

    // Central log writer for ~/.kompile/logs/subprocesses/embedding/<runId>.log
    private volatile SubprocessLogWriter subprocessLogWriter;

    // Device routing: optional overrides for this subprocess
    // When set, these values override the live ND4J environment values passed to the subprocess
    private volatile Integer deviceRoutingMaxThreads;
    private volatile Integer deviceRoutingMaxMasterThreads;
    private volatile Integer deviceRoutingCudaDevice;
    private volatile Long deviceRoutingMaxDeviceMemory;

    /**
     * Shared device-agnostic placement (the SAME base infra every subprocess uses). Backend priority,
     * device pin, and per-device memory bound flow through this — never CUDA_VISIBLE_DEVICES. The cap is
     * delivered both as {@code nd4j.environment.maxDeviceMemory} for the physical ND4J environment and
     * {@code SD_MAX_DEVICE_BYTES} for early native process setup. Set by the scheduler via
     * {@link #applyPlacement}, or synthesized from a legacy {@link #setDeviceRoutingOverrides} call.
     */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    // Subprocess registry for centralized lifecycle tracking (optional)
    private volatile SubprocessRegistry subprocessRegistry;

    // Callbacks
    private Consumer<EmbeddingSubprocessMessage.Heartbeat> heartbeatCallback;
    private Consumer<EmbeddingSubprocessMessage.Progress> progressCallback;
    private Consumer<EmbeddingSubprocessMessage.PhaseTransition> phaseTransitionCallback;
    private Consumer<EmbeddingSubprocessMessage.Log> logCallback;
    private Consumer<EmbeddingSubprocessMessage.Error> errorCallback;
    private Consumer<Exception> crashCallback;
    /** Optional callback for native-memory-pressure batch-resize decisions from the encoder. */
    private Consumer<EmbeddingSubprocessMessage.BatchResizeNotice> batchResizeCallback;

    // Health monitoring
    private ScheduledExecutorService healthMonitor;

    /**
     * Tool mode for subprocess debugging - MUTUALLY EXCLUSIVE.
     * Only one tool can wrap the JVM command at a time.
     */
    public enum ToolMode {
        NONE("none", "No debug tool", false),
        VALGRIND("valgrind", "Valgrind full leak check", false),
        VALGRIND_MINIMAL("valgrind-minimal", "Valgrind minimal (faster)", false),
        COMPUTE_SANITIZER_MEMCHECK("compute-sanitizer", "CUDA memory checker (memcheck)", true),
        COMPUTE_SANITIZER_RACECHECK("compute-sanitizer-race", "CUDA race condition checker", true),
        COMPUTE_SANITIZER_INITCHECK("compute-sanitizer-init", "CUDA uninitialized memory checker", true),
        COMPUTE_SANITIZER_SYNCCHECK("compute-sanitizer-sync", "CUDA synchronization checker", true),
        CUDA_GDB("cuda-gdb", "CUDA debugger (interactive)", true),
        NSYS("nsys", "NVIDIA Nsight Systems profiler (basic)", true),
        NSYS_PROFILE("nsys-profile", "NVIDIA Nsight Systems profiler (detailed)", true),
        NSYS_CUDA("nsys-cuda", "NVIDIA Nsight Systems CUDA-focused profiling", true),
        ASAN("asan", "AddressSanitizer (requires libasan)", false),
        EFENCE("efence", "Electric Fence (use-after-free detection)", false),
        MALLOC_CHECK("malloc-check", "glibc heap checking (MALLOC_CHECK_=3)", false);

        private final String value;
        private final String description;
        private final boolean requiresCuda;

        ToolMode(String value, String description, boolean requiresCuda) {
            this.value = value;
            this.description = description;
            this.requiresCuda = requiresCuda;
        }

        public String getValue() { return value; }
        public String getDescription() { return description; }
        public boolean requiresCuda() { return requiresCuda; }

        public static ToolMode fromValue(String value) {
            if (value == null || value.isBlank()) return NONE;
            for (ToolMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return NONE;
        }
    }

    /**
     * Debug mode enum - kept for backwards compatibility.
     * @deprecated Use ToolMode and DebugConfig's additive options instead
     */
    @Deprecated
    public enum DebugMode {
        NONE("none", "No debugging", false),
        COMPUTE_SANITIZER_MEMCHECK("compute-sanitizer", "CUDA memory checker", true),
        COMPUTE_SANITIZER_RACECHECK("compute-sanitizer-race", "CUDA race checker", true),
        COMPUTE_SANITIZER_INITCHECK("compute-sanitizer-init", "CUDA init checker", true),
        COMPUTE_SANITIZER_SYNCCHECK("compute-sanitizer-sync", "CUDA sync checker", true),
        CUDA_GDB("cuda-gdb", "CUDA debugger", true),
        VALGRIND("valgrind", "Valgrind full", false),
        VALGRIND_MINIMAL("valgrind-minimal", "Valgrind minimal", false),
        MALLOC_CHECK("malloc-check", "glibc heap check", false),
        ASAN("asan", "AddressSanitizer", false),
        EFENCE("efence", "Electric Fence", false),
        NATIVE_MEMORY_TRACKING("native-memory-tracking", "JVM NMT", false),
        VERBOSE_JNI("verbose-jni", "Verbose JNI", false),
        JVM_DIAGNOSTICS("jvm-diagnostics", "JVM diagnostics", false);

        private final String value;
        private final String description;
        private final boolean requiresCuda;

        DebugMode(String value, String description, boolean requiresCuda) {
            this.value = value;
            this.description = description;
            this.requiresCuda = requiresCuda;
        }

        public String getValue() { return value; }
        public String getDescription() { return description; }
        public boolean requiresCuda() { return requiresCuda; }

        public static DebugMode fromValue(String value) {
            if (value == null || value.isBlank()) return NONE;
            for (DebugMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return NONE;
        }
    }

    /**
     * Debug configuration for subprocess.
     *
     * Supports:
     * - ONE tool mode (valgrind, compute-sanitizer, etc.) - mutually exclusive
     * - MULTIPLE additive JVM options (verbose-jni, native-memory-tracking, etc.)
     * - Valgrind suppression file generation
     * - System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
     * - ND4J environment configuration (separate from system env vars)
     */
    public static class DebugConfig {
        // Tool mode - only one can be active (wraps the java command)
        private ToolMode toolMode = ToolMode.NONE;

        // Additive JVM options - can enable multiple simultaneously
        private boolean verboseJni = false;
        private boolean nativeMemoryTracking = false;
        private boolean extensiveErrorReports = false;
        private boolean disableJit = false;

        // Valgrind suppression file configuration
        private boolean generateValgrindSuppressions = true;
        private String libnd4jSuppressionFile;  // Path to external suppression file
        private Path generatedSuppressionFile;  // Tracks generated file for cleanup

        // Nsys (NVIDIA Nsight Systems) configuration
        private String nsysOutputFile;  // Custom output file name (without extension)
        private boolean nsysStats = true;  // Show stats summary after profiling
        private boolean nsysCudaMemoryUsage = true;  // Track CUDA memory usage
        private boolean nsysForceOverwrite = true;  // Overwrite existing output files
        private String nsysTraceOptions = "cuda,nvtx,cudnn,cublas";  // What to trace
        private int nsysDuration = 0;  // Duration in seconds (0 = until process exit)
        private boolean nsysWaitForCuda = true;  // Wait for CUDA initialization before profiling
        private String nsysExtraArgs;  // Additional nsys arguments

        // Log directory for debug output
        private String logDirectory = "./logs/debug";

        // Extra JVM arguments (user-specified)
        private List<String> extraJvmArgs = new ArrayList<>();

        // System environment variables; native-loader overrides are rejected.
        private Map<String, String> systemEnvironmentVariables = new HashMap<>();

        // ND4J environment configuration (Nd4j.getEnvironment() settings)
        // This is SEPARATE from system env vars - these configure ND4J runtime
        private Map<String, Object> nd4jEnvironmentConfig = new HashMap<>();

        public DebugConfig() {}

        public DebugConfig(ToolMode toolMode) {
            this.toolMode = toolMode;
        }

        // For backwards compatibility with old DebugMode enum
        @Deprecated
        public DebugConfig(DebugMode mode) {
            this.toolMode = convertDebugModeToToolMode(mode);
            // Also set additive options based on old mode
            if (mode == DebugMode.VERBOSE_JNI || mode == DebugMode.JVM_DIAGNOSTICS) {
                this.verboseJni = true;
            }
            if (mode == DebugMode.NATIVE_MEMORY_TRACKING || mode == DebugMode.JVM_DIAGNOSTICS) {
                this.nativeMemoryTracking = true;
            }
            if (mode == DebugMode.JVM_DIAGNOSTICS) {
                this.extensiveErrorReports = true;
            }
        }

        private ToolMode convertDebugModeToToolMode(DebugMode mode) {
            if (mode == null) return ToolMode.NONE;
            return switch (mode) {
                case VALGRIND -> ToolMode.VALGRIND;
                case VALGRIND_MINIMAL -> ToolMode.VALGRIND_MINIMAL;
                case COMPUTE_SANITIZER_MEMCHECK -> ToolMode.COMPUTE_SANITIZER_MEMCHECK;
                case COMPUTE_SANITIZER_RACECHECK -> ToolMode.COMPUTE_SANITIZER_RACECHECK;
                case COMPUTE_SANITIZER_INITCHECK -> ToolMode.COMPUTE_SANITIZER_INITCHECK;
                case COMPUTE_SANITIZER_SYNCCHECK -> ToolMode.COMPUTE_SANITIZER_SYNCCHECK;
                case CUDA_GDB -> ToolMode.CUDA_GDB;
                case ASAN -> ToolMode.ASAN;
                case EFENCE -> ToolMode.EFENCE;
                case MALLOC_CHECK -> ToolMode.MALLOC_CHECK;
                default -> ToolMode.NONE;
            };
        }

        // Getters and setters for tool mode
        public ToolMode getToolMode() { return toolMode; }
        public void setToolMode(ToolMode toolMode) { this.toolMode = toolMode; }

        // For backwards compatibility
        @Deprecated
        public DebugMode getMode() {
            return switch (toolMode) {
                case VALGRIND -> DebugMode.VALGRIND;
                case VALGRIND_MINIMAL -> DebugMode.VALGRIND_MINIMAL;
                case COMPUTE_SANITIZER_MEMCHECK -> DebugMode.COMPUTE_SANITIZER_MEMCHECK;
                case COMPUTE_SANITIZER_RACECHECK -> DebugMode.COMPUTE_SANITIZER_RACECHECK;
                case COMPUTE_SANITIZER_INITCHECK -> DebugMode.COMPUTE_SANITIZER_INITCHECK;
                case COMPUTE_SANITIZER_SYNCCHECK -> DebugMode.COMPUTE_SANITIZER_SYNCCHECK;
                case CUDA_GDB -> DebugMode.CUDA_GDB;
                case ASAN -> DebugMode.ASAN;
                case EFENCE -> DebugMode.EFENCE;
                case MALLOC_CHECK -> DebugMode.MALLOC_CHECK;
                default -> DebugMode.NONE;
            };
        }

        @Deprecated
        public void setMode(DebugMode mode) {
            this.toolMode = convertDebugModeToToolMode(mode);
        }

        // Additive JVM options getters/setters
        public boolean isVerboseJni() { return verboseJni; }
        public void setVerboseJni(boolean verboseJni) { this.verboseJni = verboseJni; }

        public boolean isNativeMemoryTracking() { return nativeMemoryTracking; }
        public void setNativeMemoryTracking(boolean nativeMemoryTracking) { this.nativeMemoryTracking = nativeMemoryTracking; }

        public boolean isExtensiveErrorReports() { return extensiveErrorReports; }
        public void setExtensiveErrorReports(boolean extensiveErrorReports) { this.extensiveErrorReports = extensiveErrorReports; }

        public boolean isDisableJit() { return disableJit; }
        public void setDisableJit(boolean disableJit) { this.disableJit = disableJit; }

        // Valgrind suppression settings
        public boolean isGenerateValgrindSuppressions() { return generateValgrindSuppressions; }
        public void setGenerateValgrindSuppressions(boolean generate) { this.generateValgrindSuppressions = generate; }

        public String getLibnd4jSuppressionFile() { return libnd4jSuppressionFile; }
        public void setLibnd4jSuppressionFile(String file) { this.libnd4jSuppressionFile = file; }

        // Nsys (NVIDIA Nsight Systems) settings
        public String getNsysOutputFile() { return nsysOutputFile; }
        public void setNsysOutputFile(String nsysOutputFile) { this.nsysOutputFile = nsysOutputFile; }

        public boolean isNsysStats() { return nsysStats; }
        public void setNsysStats(boolean nsysStats) { this.nsysStats = nsysStats; }

        public boolean isNsysCudaMemoryUsage() { return nsysCudaMemoryUsage; }
        public void setNsysCudaMemoryUsage(boolean nsysCudaMemoryUsage) { this.nsysCudaMemoryUsage = nsysCudaMemoryUsage; }

        public boolean isNsysForceOverwrite() { return nsysForceOverwrite; }
        public void setNsysForceOverwrite(boolean nsysForceOverwrite) { this.nsysForceOverwrite = nsysForceOverwrite; }

        public String getNsysTraceOptions() { return nsysTraceOptions; }
        public void setNsysTraceOptions(String nsysTraceOptions) { this.nsysTraceOptions = nsysTraceOptions; }

        public int getNsysDuration() { return nsysDuration; }
        public void setNsysDuration(int nsysDuration) { this.nsysDuration = nsysDuration; }

        public boolean isNsysWaitForCuda() { return nsysWaitForCuda; }
        public void setNsysWaitForCuda(boolean nsysWaitForCuda) { this.nsysWaitForCuda = nsysWaitForCuda; }

        public String getNsysExtraArgs() { return nsysExtraArgs; }
        public void setNsysExtraArgs(String nsysExtraArgs) { this.nsysExtraArgs = nsysExtraArgs; }

        // Log directory
        public String getLogDirectory() { return logDirectory; }
        public void setLogDirectory(String logDirectory) { this.logDirectory = logDirectory; }

        // Extra JVM args
        public List<String> getExtraJvmArgs() { return extraJvmArgs; }
        public void setExtraJvmArgs(List<String> extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }

        // System environment variables for the child process.
        public Map<String, String> getSystemEnvironmentVariables() { return systemEnvironmentVariables; }
        public void setSystemEnvironmentVariables(Map<String, String> vars) {
            this.systemEnvironmentVariables = vars == null ? new HashMap<>() : new HashMap<>(vars);
            this.systemEnvironmentVariables.keySet().removeIf("LD_PRELOAD"::equalsIgnoreCase);
        }

        // For backwards compatibility
        public Map<String, String> getEnvironmentVariables() { return systemEnvironmentVariables; }
        public void setEnvironmentVariables(Map<String, String> vars) { setSystemEnvironmentVariables(vars); }

        // ND4J environment configuration (Nd4j.getEnvironment() settings)
        public Map<String, Object> getNd4jEnvironmentConfig() { return nd4jEnvironmentConfig; }
        public void setNd4jEnvironmentConfig(Map<String, Object> config) { this.nd4jEnvironmentConfig = config; }

        /**
         * Check if any debug options are enabled.
         */
        public boolean hasAnyDebugEnabled() {
            return toolMode != ToolMode.NONE || verboseJni || nativeMemoryTracking ||
                   extensiveErrorReports || disableJit || !extraJvmArgs.isEmpty();
        }

        /**
         * Build command prefix for the tool mode.
         * This prefixes the java command (e.g., valgrind java -jar ...)
         */
        public List<String> buildCommandPrefix() {
            List<String> prefix = new ArrayList<>();
            String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path logDir = Paths.get(logDirectory).toAbsolutePath();

            try {
                Files.createDirectories(logDir);
            } catch (IOException e) {
                // Ignore - will fail later if needed
            }

            switch (toolMode) {
                case COMPUTE_SANITIZER_MEMCHECK:
                    prefix.add("compute-sanitizer");
                    prefix.add("--tool");
                    prefix.add("memcheck");
                    prefix.add("--print-limit=0");
                    prefix.add("--show-backtrace=yes");
                    prefix.add("--log-file");
                    prefix.add(logDir.resolve("compute_sanitizer_" + timestamp + ".log").toString());
                    break;
                case COMPUTE_SANITIZER_RACECHECK:
                    prefix.add("compute-sanitizer");
                    prefix.add("--tool");
                    prefix.add("racecheck");
                    prefix.add("--print-limit=0");
                    prefix.add("--show-backtrace=yes");
                    prefix.add("--log-file");
                    prefix.add(logDir.resolve("racecheck_" + timestamp + ".log").toString());
                    break;
                case COMPUTE_SANITIZER_INITCHECK:
                    prefix.add("compute-sanitizer");
                    prefix.add("--tool");
                    prefix.add("initcheck");
                    prefix.add("--print-limit=0");
                    prefix.add("--show-backtrace=yes");
                    prefix.add("--log-file");
                    prefix.add(logDir.resolve("initcheck_" + timestamp + ".log").toString());
                    break;
                case COMPUTE_SANITIZER_SYNCCHECK:
                    prefix.add("compute-sanitizer");
                    prefix.add("--tool");
                    prefix.add("synccheck");
                    prefix.add("--print-limit=0");
                    prefix.add("--show-backtrace=yes");
                    prefix.add("--log-file");
                    prefix.add(logDir.resolve("synccheck_" + timestamp + ".log").toString());
                    break;
                case CUDA_GDB:
                    prefix.add("cuda-gdb");
                    prefix.add("--args");
                    break;
                case VALGRIND:
                case VALGRIND_MINIMAL:
                    prefix.addAll(buildValgrindCommand(logDir, timestamp));
                    break;
                case NSYS:
                case NSYS_PROFILE:
                case NSYS_CUDA:
                    prefix.addAll(buildNsysCommand(logDir, timestamp));
                    break;
                default:
                    break;
            }
            return prefix;
        }

        /**
         * Build nsys (NVIDIA Nsight Systems) command for GPU profiling.
         *
         * <p>Nsys is NVIDIA's system-wide performance analysis tool that can trace:
         * <ul>
         *   <li>CUDA API calls and kernel execution</li>
         *   <li>cuDNN and cuBLAS operations</li>
         *   <li>NVTX annotations for custom markers</li>
         *   <li>CPU activity and OS runtime information</li>
         *   <li>Memory transfers between host and device</li>
         * </ul>
         *
         * <p>The generated .nsys-rep file can be opened with NVIDIA Nsight Systems GUI
         * for detailed timeline visualization and analysis.
         */
        private List<String> buildNsysCommand(Path logDir, String timestamp) {
            List<String> cmd = new ArrayList<>();
            cmd.add("nsys");
            cmd.add("profile");

            // Output file
            String outputName = nsysOutputFile != null && !nsysOutputFile.isBlank()
                ? nsysOutputFile
                : "embedding_subprocess_" + timestamp;
            cmd.add("--output=" + logDir.resolve(outputName).toString());

            // Force overwrite existing output
            if (nsysForceOverwrite) {
                cmd.add("--force-overwrite=true");
            }

            // Configure trace options based on tool mode
            String traceOpts = nsysTraceOptions;
            if (toolMode == ToolMode.NSYS_CUDA) {
                // CUDA-focused profiling with more detailed CUDA tracing
                traceOpts = "cuda,nvtx,cudnn,cublas,osrt";
            } else if (toolMode == ToolMode.NSYS_PROFILE) {
                // Detailed profiling including OS runtime
                traceOpts = "cuda,nvtx,cudnn,cublas,osrt,nvenc,nvdec";
            }
            if (traceOpts != null && !traceOpts.isBlank()) {
                cmd.add("--trace=" + traceOpts);
            }

            // Track CUDA memory usage
            if (nsysCudaMemoryUsage) {
                cmd.add("--cuda-memory-usage=true");
            }

            // Wait for CUDA to initialize before starting profiling
            // This avoids capturing startup noise and focuses on actual work
            if (nsysWaitForCuda) {
                cmd.add("--cudabacktrace=all");
            }

            // Show stats summary after profiling
            if (nsysStats) {
                cmd.add("--stats=true");
            }

            // Duration limit (0 = profile until process exit)
            if (nsysDuration > 0) {
                cmd.add("--duration=" + nsysDuration);
            }

            // Sample CPU backtraces for better CPU profiling
            if (toolMode == ToolMode.NSYS_PROFILE) {
                cmd.add("--sample=cpu");
                cmd.add("--backtrace=dwarf");
            }

            // Capture GPU metrics for detailed performance analysis
            if (toolMode == ToolMode.NSYS_CUDA || toolMode == ToolMode.NSYS_PROFILE) {
                cmd.add("--gpu-metrics-device=all");
            }

            // Add any extra user-specified arguments
            if (nsysExtraArgs != null && !nsysExtraArgs.isBlank()) {
                // Split by whitespace, handling quoted strings would require more complex parsing
                for (String arg : nsysExtraArgs.split("\\s+")) {
                    if (!arg.isBlank()) {
                        cmd.add(arg);
                    }
                }
            }

            logger.info("Nsys profiling output will be written to: {}.nsys-rep",
                logDir.resolve(outputName));
            logger.info("Open with: nsys-ui {}.nsys-rep", logDir.resolve(outputName));

            return cmd;
        }

        /**
         * Build valgrind command with suppression file generation.
         * Mirrors the logic from deeplearning4j/platform-tests/bin/java
         */
        private List<String> buildValgrindCommand(Path logDir, String timestamp) {
            List<String> cmd = new ArrayList<>();
            cmd.add("valgrind");

            // Error handling options
            cmd.add("--error-limit=no");

            // Leak check options based on mode
            if (toolMode == ToolMode.VALGRIND) {
                cmd.add("--leak-check=full");
                cmd.add("--show-leak-kinds=all");
                cmd.add("--track-origins=yes");
            } else {
                cmd.add("--leak-check=summary");
                cmd.add("--show-leak-kinds=definite");
                cmd.add("--track-origins=no");
            }

            cmd.add("--keep-stacktraces=alloc-and-free");

            // Generate dynamic suppression file for libjvm.so
            if (generateValgrindSuppressions) {
                try {
                    Path suppFile = generateValgrindSuppressionFile();
                    if (suppFile != null) {
                        cmd.add("--suppressions=" + suppFile.toAbsolutePath());
                        this.generatedSuppressionFile = suppFile;
                    }
                } catch (IOException e) {
                    logger.warn("Failed to generate valgrind suppression file: {}", e.getMessage());
                }
            }

            // Add external libnd4j suppression file if provided
            if (libnd4jSuppressionFile != null && !libnd4jSuppressionFile.isBlank()) {
                Path suppPath = Paths.get(libnd4jSuppressionFile);
                if (Files.exists(suppPath)) {
                    cmd.add("--suppressions=" + suppPath.toAbsolutePath());
                } else {
                    logger.warn("libnd4j suppression file not found: {}", libnd4jSuppressionFile);
                }
            }

            // Log file
            String logFileName = toolMode == ToolMode.VALGRIND ?
                "valgrind_" + timestamp + ".log" :
                "valgrind_minimal_" + timestamp + ".log";
            cmd.add("--log-file=" + logDir.resolve(logFileName));

            return cmd;
        }

        /**
         * Generate dynamic valgrind suppression file for libjvm.so.
         * This suppresses JVM internal errors to focus on libnd4j issues.
         */
        private Path generateValgrindSuppressionFile() throws IOException {
            // Find libjvm.so path
            String javaHome = System.getProperty("java.home");
            Path libjvmPath = findLibjvm(javaHome);

            if (libjvmPath == null) {
                logger.warn("Could not find libjvm.so, skipping suppression file generation");
                return null;
            }

            String libjvmStr = libjvmPath.toAbsolutePath().toString();
            logger.info("Generating valgrind suppression file for libjvm.so: {}", libjvmStr);

            // Create temp suppression file
            Path suppFile = Files.createTempFile("valgrind_dynamic_", ".supp");

            StringBuilder sb = new StringBuilder();
            sb.append("# Auto-generated valgrind suppression file for libjvm.so\n");
            sb.append("# Generated at: ").append(java.time.LocalDateTime.now()).append("\n");
            sb.append("# libjvm.so path: ").append(libjvmStr).append("\n\n");

            // Memory access error suppressions
            String[] errorTypes = {"Addr1", "Addr2", "Addr4", "Addr8", "Value1", "Value2", "Value4", "Value8", "Jump", "Cond"};
            for (String errorType : errorTypes) {
                sb.append("{\n");
                sb.append("    suppress_libjvm_dynamic_").append(errorType).append("\n");
                sb.append("    Memcheck:").append(errorType).append("\n");
                sb.append("    ...\n");
                sb.append("    obj:").append(libjvmStr).append("\n");
                sb.append("}\n\n");
            }

            // Leak suppressions
            String[] leakKinds = {"definite", "possible", "reachable", "indirect"};
            for (String leakKind : leakKinds) {
                sb.append("{\n");
                sb.append("    suppress_libjvm_leak_").append(leakKind).append("\n");
                sb.append("    Memcheck:Leak\n");
                sb.append("    match-leak-kinds: ").append(leakKind).append("\n");
                sb.append("    ...\n");
                sb.append("    obj:").append(libjvmStr).append("\n");
                sb.append("}\n\n");
            }

            Files.writeString(suppFile, sb.toString());
            logger.info("Generated valgrind suppression file: {}", suppFile);
            return suppFile;
        }

        /**
         * Find libjvm.so in the java home directory.
         */
        private Path findLibjvm(String javaHome) {
            if (javaHome == null) return null;

            // Common locations for libjvm.so
            String[] searchPaths = {
                "lib/server/libjvm.so",
                "lib/libjvm.so",
                "jre/lib/server/libjvm.so",
                "jre/lib/amd64/server/libjvm.so"
            };

            for (String searchPath : searchPaths) {
                Path path = Paths.get(javaHome, searchPath);
                if (Files.exists(path)) {
                    return path;
                }
            }

            // Try to find it recursively
            try {
                return Files.walk(Paths.get(javaHome))
                    .filter(p -> p.getFileName().toString().equals("libjvm.so"))
                    .filter(p -> p.toString().contains("server"))
                    .findFirst()
                    .orElse(null);
            } catch (IOException e) {
                logger.warn("Failed to find libjvm.so under JAVA_HOME={}", javaHome, e);
                return null;
            }
        }

        /**
         * Cleanup generated suppression file.
         */
        public void cleanup() {
            if (generatedSuppressionFile != null && Files.exists(generatedSuppressionFile)) {
                try {
                    Files.delete(generatedSuppressionFile);
                    logger.debug("Cleaned up generated suppression file: {}", generatedSuppressionFile);
                } catch (IOException e) {
                    logger.warn("Failed to cleanup suppression file: {}", e.getMessage());
                }
                generatedSuppressionFile = null;
            }
        }

        /**
         * Build JVM arguments for all enabled debug options.
         * Combines additive options (verbose-jni, nmt, error-reports, disable-jit).
         */
        public List<String> buildJvmArgs() {
            List<String> args = new ArrayList<>();

            // Native Memory Tracking
            if (nativeMemoryTracking) {
                args.add("-XX:+UnlockDiagnosticVMOptions");
                args.add("-XX:NativeMemoryTracking=detail");
            }

            // Extensive Error Reports
            if (extensiveErrorReports) {
                if (!nativeMemoryTracking) {
                    args.add("-XX:+UnlockDiagnosticVMOptions");
                }
                args.add("-XX:+ExtensiveErrorReports");
            }

            // Verbose JNI
            if (verboseJni) {
                args.add("-verbose:jni");
            }

            // Disable JIT - useful for debugging, and required for some tools
            if (disableJit || toolMode == ToolMode.VALGRIND || toolMode == ToolMode.VALGRIND_MINIMAL ||
                toolMode == ToolMode.ASAN || toolMode.name().startsWith("COMPUTE_SANITIZER")) {
                args.add("-Djava.compiler=NONE");
            }

            // Add extra user-specified JVM args
            args.addAll(extraJvmArgs);

            return args;
        }

        /**
         * Build system environment variables for the process.
         * Native libraries are resolved by the packaged runtime, never by
         * subprocess environment injection.
         */
        public Map<String, String> buildEnvironmentVariables() {
            Map<String, String> env = new HashMap<>(systemEnvironmentVariables);
            env.keySet().removeIf("LD_PRELOAD"::equalsIgnoreCase);
            String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path logDir = Paths.get(logDirectory).toAbsolutePath();

            switch (toolMode) {
                case MALLOC_CHECK:
                    env.put("MALLOC_CHECK_", "3");
                    env.put("LIBC_FATAL_STDERR_", "1");
                    break;
                case ASAN:
                    env.put("ASAN_OPTIONS",
                        "alloc_dealloc_mismatch=0:detect_leaks=1:new_delete_type_mismatch=0:" +
                        "halt_on_error=0:exitcode=0:report_objects=1:use_stacks=1:use_registers=1:" +
                        "leak_check_at_exit=1:fast_unwind_on_malloc=0:log_path=" +
                        logDir.resolve("asan_" + timestamp + ".log"));
                    break;
                case EFENCE:
                    // Electric Fence must be linked or launched explicitly; subprocess
                    // environment injection is intentionally unsupported.
                    break;
                default:
                    break;
            }
            return env;
        }

        /**
         * Get a description of the current debug configuration.
         */
        public String getDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append("Tool: ").append(toolMode.getDescription());

            List<String> additiveOptions = new ArrayList<>();
            if (verboseJni) additiveOptions.add("verbose-jni");
            if (nativeMemoryTracking) additiveOptions.add("native-memory-tracking");
            if (extensiveErrorReports) additiveOptions.add("extensive-error-reports");
            if (disableJit) additiveOptions.add("disable-jit");

            if (!additiveOptions.isEmpty()) {
                sb.append(" + ").append(String.join(", ", additiveOptions));
            }

            return sb.toString();
        }

        public DebugConfig copy() {
            DebugConfig copy = new DebugConfig();
            copy.toolMode = this.toolMode;
            copy.verboseJni = this.verboseJni;
            copy.nativeMemoryTracking = this.nativeMemoryTracking;
            copy.extensiveErrorReports = this.extensiveErrorReports;
            copy.disableJit = this.disableJit;
            copy.generateValgrindSuppressions = this.generateValgrindSuppressions;
            copy.libnd4jSuppressionFile = this.libnd4jSuppressionFile;
            // Copy nsys configuration
            copy.nsysOutputFile = this.nsysOutputFile;
            copy.nsysStats = this.nsysStats;
            copy.nsysCudaMemoryUsage = this.nsysCudaMemoryUsage;
            copy.nsysForceOverwrite = this.nsysForceOverwrite;
            copy.nsysTraceOptions = this.nsysTraceOptions;
            copy.nsysDuration = this.nsysDuration;
            copy.nsysWaitForCuda = this.nsysWaitForCuda;
            copy.nsysExtraArgs = this.nsysExtraArgs;
            copy.logDirectory = this.logDirectory;
            copy.extraJvmArgs = new ArrayList<>(this.extraJvmArgs);
            copy.systemEnvironmentVariables = new HashMap<>(this.systemEnvironmentVariables);
            copy.nd4jEnvironmentConfig = new HashMap<>(this.nd4jEnvironmentConfig);
            return copy;
        }
    }

    /**
     * Launch mode for subprocess execution.
     */
    public enum LaunchMode {
        /**
         * Automatically detect whether to use JVM classpath or native executable.
         */
        AUTO,
        /**
         * Always use JVM classpath mode (java -cp ... MainClass).
         */
        JVM_CLASSPATH,
        /**
         * Always use native executable mode.
         */
        NATIVE_EXECUTABLE
    }

    /**
     * Builder for EmbeddingSubprocessLauncher.
     */
    public static class Builder {
        private String javaHome = System.getProperty("java.home");
        private List<String> classpath = new ArrayList<>();
        private int maxHeapMb = 4096;
        /** JavaCPP maxphysicalbytes in MB; 0 means auto (4 × maxHeapMb). */
        private long maxPhysicalMb = 0;
        // Default to 0 (no timeout) - timeouts can be configured via properties
        private long requestTimeoutMs = 0;
        // Default to 0 (falls back to requestTimeoutMs) - configured via properties
        private long loadModelTimeoutMs = 0;
        private long heartbeatTimeoutMs = 0;
        private LaunchMode launchMode = LaunchMode.AUTO;
        private String nativeExecutablePath;
        private String subprocessTypeFlag = "--subprocess=";
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private boolean localModelOnly;
        private Consumer<EmbeddingSubprocessMessage.Heartbeat> heartbeatCallback;
        private Consumer<EmbeddingSubprocessMessage.Progress> progressCallback;
        private Consumer<EmbeddingSubprocessMessage.PhaseTransition> phaseTransitionCallback;
        private Consumer<EmbeddingSubprocessMessage.Log> logCallback;
        private Consumer<EmbeddingSubprocessMessage.Error> errorCallback;
        private Consumer<Exception> crashCallback;
        private Consumer<EmbeddingSubprocessMessage.BatchResizeNotice> batchResizeCallback;

        public Builder javaHome(String javaHome) {
            this.javaHome = javaHome;
            return this;
        }

        public Builder classpath(List<String> classpath) {
            this.classpath = new ArrayList<>(classpath);
            return this;
        }

        public Builder addClasspathEntry(String entry) {
            this.classpath.add(entry);
            return this;
        }

        /**
         * Add environment variables that are scoped to this subprocess only.
         * Explicit values override inherited parent values.
         */
        public Builder environment(Map<String, String> environment) {
            if (environment != null) {
                environment.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        this.environment.put(key, value);
                    }
                });
            }
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Restrict model resolution to the subprocess-local registry/cache. When enabled,
         * staging and archive configuration from the parent process is not forwarded.
         */
        public Builder localModelOnly(boolean localModelOnly) {
            this.localModelOnly = localModelOnly;
            return this;
        }

        public Builder maxHeapMb(int maxHeapMb) {
            this.maxHeapMb = maxHeapMb;
            return this;
        }

        /**
         * Set the JavaCPP native-memory ceiling for the subprocess in MB.
         * Passed as {@code -Dorg.bytedeco.javacpp.maxphysicalbytes=Nm}.
         * 0 (default) → auto: 4 × maxHeapMb.
         */
        public Builder maxPhysicalMb(long maxPhysicalMb) {
            this.maxPhysicalMb = maxPhysicalMb;
            return this;
        }

        public Builder requestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        /**
         * Timeout in milliseconds for the LoadModel request specifically.
         * CPU SameDiff/DSP model warm-up can take ~111s or more; setting this
         * higher than {@code requestTimeoutMs} prevents a crash-loop where
         * the 60s general timeout kills the subprocess before it finishes loading.
         * When 0, falls back to {@code requestTimeoutMs} (or waits indefinitely
         * if that is also 0).
         */
        public Builder loadModelTimeoutMs(long loadModelTimeoutMs) {
            this.loadModelTimeoutMs = loadModelTimeoutMs;
            return this;
        }

        public Builder heartbeatTimeoutMs(long heartbeatTimeoutMs) {
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            return this;
        }

        /**
         * Set the launch mode for subprocess execution.
         *
         * @param launchMode the launch mode (AUTO, JVM_CLASSPATH, or NATIVE_EXECUTABLE)
         * @return this builder
         */
        public Builder launchMode(LaunchMode launchMode) {
            this.launchMode = launchMode;
            return this;
        }

        /**
         * Set the path to the native executable for native mode.
         *
         * @param nativeExecutablePath path to the native executable
         * @return this builder
         */
        public Builder nativeExecutablePath(String nativeExecutablePath) {
            this.nativeExecutablePath = nativeExecutablePath;
            return this;
        }

        /**
         * Set the subprocess type flag prefix for unified native executable mode.
         * Default: "--subprocess="
         *
         * @param subprocessTypeFlag the flag prefix
         * @return this builder
         */
        public Builder subprocessTypeFlag(String subprocessTypeFlag) {
            this.subprocessTypeFlag = subprocessTypeFlag;
            return this;
        }

        public Builder heartbeatCallback(Consumer<EmbeddingSubprocessMessage.Heartbeat> callback) {
            this.heartbeatCallback = callback;
            return this;
        }

        public Builder progressCallback(Consumer<EmbeddingSubprocessMessage.Progress> callback) {
            this.progressCallback = callback;
            return this;
        }

        public Builder phaseTransitionCallback(Consumer<EmbeddingSubprocessMessage.PhaseTransition> callback) {
            this.phaseTransitionCallback = callback;
            return this;
        }

        public Builder logCallback(Consumer<EmbeddingSubprocessMessage.Log> callback) {
            this.logCallback = callback;
            return this;
        }

        public Builder errorCallback(Consumer<EmbeddingSubprocessMessage.Error> callback) {
            this.errorCallback = callback;
            return this;
        }

        public Builder crashCallback(Consumer<Exception> callback) {
            this.crashCallback = callback;
            return this;
        }

        /**
         * Callback invoked when the encoder shrinks a sub-batch due to native-memory pressure.
         * Useful for operator dashboards and auto-tuning.
         */
        public Builder batchResizeCallback(Consumer<EmbeddingSubprocessMessage.BatchResizeNotice> callback) {
            this.batchResizeCallback = callback;
            return this;
        }

        /**
         * Set the debug configuration for subprocess execution.
         *
         * @param debugConfig the debug configuration
         * @return this builder
         */
        public Builder debugConfig(DebugConfig debugConfig) {
            this.debugConfig = debugConfig;
            return this;
        }

        /**
         * Set the debug mode (convenience method).
         *
         * @param mode the debug mode
         * @return this builder
         */
        public Builder debugMode(DebugMode mode) {
            this.debugConfig = new DebugConfig(mode);
            return this;
        }

        public EmbeddingSubprocessLauncher build() {
            return new EmbeddingSubprocessLauncher(this);
        }

        private DebugConfig debugConfig = new DebugConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Set the subprocess registry for centralized lifecycle tracking.
     * When set, the launcher registers/deregisters its process with the registry,
     * enabling orphan protection via JVM shutdown hook.
     */
    public void setSubprocessRegistry(SubprocessRegistry registry) {
        this.subprocessRegistry = registry;
    }

    // ── RestartableSubprocess implementation ──────────────────────────────────

    @Override
    public String getSubprocessId() {
        return "embedding";
    }

    /**
     * Request a restart of the embedding subprocess from the parent-side watchdog.
     *
     * <p>Stores the external-crash reason and fires {@link #triggerExternalCrash(String)}
     * on a daemon thread so the watchdog's scheduler thread is never blocked.
     *
     * @param reason human-readable explanation from the watchdog (e.g. "RSS 18432 MB exceeds limit")
     */
    @Override
    public void requestRestart(String reason) {
        logger.warn("Watchdog-triggered restart requested for embedding subprocess: {}", reason);
        lastCrashReason = reason;
        Thread t = new Thread(() -> triggerExternalCrash(reason), "embedding-watchdog-restart");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Entry point for external (watchdog-initiated) crash handling.
     *
     * <p>Forcibly destroys the current process (if still alive) so that the existing
     * {@link #handleSubprocessCrash()} path picks it up naturally — reusing all
     * backoff, model-reload, and event-history logic already wired there.
     *
     * @param reason diagnostic reason string stored before calling this method
     */
    private synchronized void triggerExternalCrash(String reason) {
        if (shuttingDown.get()) {
            logger.debug("Watchdog restart suppressed — launcher is shutting down");
            return;
        }
        Process p = this.process;
        if (p != null && p.isAlive()) {
            logger.warn("Watchdog killing embedding subprocess PID={} (reason: {})", p.pid(), reason);
            p.destroyForcibly();
        }
        // handleSubprocessCrash() will be invoked by the output-reader thread detecting EOF,
        // which is the normal crash-detection path.  Calling it here too would double-restart.
        // If the process was already dead when we arrived, fire it directly.
        if (p == null || !p.isAlive()) {
            handleSubprocessCrash();
        }
    }

    private EmbeddingSubprocessLauncher(Builder builder) {
        this.javaHome = builder.javaHome;
        this.classpath = builder.classpath.isEmpty() ?
            buildSubprocessClasspath() : builder.classpath;
        this.maxHeapMb = builder.maxHeapMb;
        this.maxPhysicalMb = builder.maxPhysicalMb > 0 ? builder.maxPhysicalMb : (long) builder.maxHeapMb * 4;
        this.requestTimeoutMs = builder.requestTimeoutMs;
        this.loadModelTimeoutMs = builder.loadModelTimeoutMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;
        this.environment = Map.copyOf(builder.environment);
        this.workingDirectory = builder.workingDirectory == null
                ? null : builder.workingDirectory.toAbsolutePath().normalize();
        this.localModelOnly = builder.localModelOnly;
        this.subprocessTypeFlag = builder.subprocessTypeFlag;
        this.heartbeatCallback = builder.heartbeatCallback;
        this.progressCallback = builder.progressCallback;
        this.phaseTransitionCallback = builder.phaseTransitionCallback;
        this.logCallback = builder.logCallback;
        this.errorCallback = builder.errorCallback;
        this.crashCallback = builder.crashCallback;
        this.batchResizeCallback = builder.batchResizeCallback;
        this.debugConfig = builder.debugConfig != null ? builder.debugConfig.copy() : new DebugConfig();

        // Resolve launch mode and native executable path
        LaunchMode resolvedMode = resolvelaunchMode(builder.launchMode);
        this.launchMode = resolvedMode;

        if (resolvedMode == LaunchMode.NATIVE_EXECUTABLE) {
            this.nativeExecutablePath = resolveNativeExecutablePath(builder.nativeExecutablePath);
            if (this.nativeExecutablePath == null) {
                logger.warn("Native executable mode requested but no executable path found. " +
                           "Falling back to JVM classpath mode if classpath is available.");
            }
        } else {
            this.nativeExecutablePath = builder.nativeExecutablePath;
        }
    }

    /**
     * Get the current debug configuration.
     */
    public DebugConfig getDebugConfig() {
        return debugConfig;
    }

    /**
     * Set the debug configuration. Can be changed at runtime before restart.
     */
    public void setDebugConfig(DebugConfig debugConfig) {
        this.debugConfig = debugConfig != null ? debugConfig : new DebugConfig();
    }

    /**
     * Set device routing overrides for this embedding subprocess.
     * When set, these values override the live ND4J environment values.
     *
     * @param maxThreads thread count override (null to use global)
     * @param maxMasterThreads master thread count override (null to use global)
     * @param cudaDevice CUDA device ID override (null to use global, -1 for CPU)
     * @param maxDeviceMemory device memory limit in bytes (null for unlimited)
     */
    public void setDeviceRoutingOverrides(Integer maxThreads, Integer maxMasterThreads,
                                          Integer cudaDevice, Long maxDeviceMemory) {
        this.deviceRoutingMaxThreads = maxThreads;
        this.deviceRoutingMaxMasterThreads = maxMasterThreads;
        this.deviceRoutingCudaDevice = cudaDevice;
        this.deviceRoutingMaxDeviceMemory = maxDeviceMemory;
        // Translate legacy overrides into the shared placement delivery. A null cudaDevice keeps
        // backend/device selection inherited while still allowing a memory-only budget. The scheduler
        // wins if it already assigned a placement.
        if (!placement.hasPlacement()) {
            long bound = maxDeviceMemory != null ? maxDeviceMemory : 0L;
            if (cudaDevice != null) {
                placement.applyPlacement(cudaDevice < 0
                        ? SubprocessPlacement.cpu()
                        : SubprocessPlacement.gpu(cudaDevice, bound));
            } else if (bound > 0L) {
                placement.applyPlacement(new SubprocessPlacement(
                        BackendPreference.INHERIT,
                        SubprocessPlacement.CPU_DEVICE_ID,
                        bound));
            }
        }
        logger.info("Device routing overrides set for embedding subprocess: maxThreads={}, cudaDevice={}, maxMemory={}",
                maxThreads, cudaDevice, maxDeviceMemory);
    }

    /** {@link BackendConfigurable} — scheduler-assigned device-agnostic placement (wins over legacy). */
    @Override
    public void applyPlacement(SubprocessPlacement placement) {
        this.placement.applyPlacement(placement);
    }

    /**
     * Clear device routing overrides.
     */
    public void clearDeviceRoutingOverrides() {
        this.deviceRoutingMaxThreads = null;
        this.deviceRoutingMaxMasterThreads = null;
        this.deviceRoutingCudaDevice = null;
        this.deviceRoutingMaxDeviceMemory = null;
        this.placement.applyPlacement(null);
        logger.info("Device routing overrides cleared for embedding subprocess");
    }

    /**
     * Set the debug mode (convenience method).
     */
    public void setDebugMode(DebugMode mode) {
        if (mode == null) {
            this.debugConfig = new DebugConfig();
        } else {
            this.debugConfig = new DebugConfig(mode);
        }
    }

    /**
     * Get the current debug mode.
     */
    public DebugMode getDebugMode() {
        return debugConfig != null ? debugConfig.getMode() : DebugMode.NONE;
    }

    private LaunchMode resolvelaunchMode(LaunchMode requestedMode) {
        if (requestedMode == LaunchMode.JVM_CLASSPATH) {
            return LaunchMode.JVM_CLASSPATH;
        }
        if (requestedMode == LaunchMode.NATIVE_EXECUTABLE) {
            return LaunchMode.NATIVE_EXECUTABLE;
        }

        // AUTO mode: detect based on runtime context
        // If we have a classpath, prefer JVM mode for easier debugging and development
        if (NativeImageInfo.hasClasspath()) {
            return LaunchMode.JVM_CLASSPATH;
        }
        // Otherwise, we're likely in a native image - use native mode
        return LaunchMode.NATIVE_EXECUTABLE;
    }

    /**
     * Build classpath for the subprocess, handling Spring Boot fat JARs.
     * When running via 'java -jar app.jar', java.class.path is just the fat JAR,
     * but -cp fat-jar.jar can't see classes under BOOT-INF/. We extract nested
     * JARs and classes to a temp directory for use as classpath entries.
     */
    private static List<String> buildSubprocessClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        String systemCp = System.getProperty("java.class.path");
        if (systemCp != null && !systemCp.isBlank()) {
            for (String entry : systemCp.split(pathSeparator)) {
                if (!entry.isBlank()) entries.add(entry);
            }
        }

        // Handle Spring Boot fat JAR: extract BOOT-INF/lib and BOOT-INF/classes
        Set<String> fatJarExpanded = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.endsWith(".jar") && isSpringBootFatJar(entry)) {
                logger.info("Detected Spring Boot fat JAR: {}, extracting BOOT-INF entries", entry);
                try {
                    extractBootInfClasspath(entry, fatJarExpanded);
                } catch (Exception e) {
                    logger.warn("Failed to extract BOOT-INF from {}: {}", entry, e.getMessage());
                }
            }
        }
        if (!fatJarExpanded.isEmpty()) {
            entries.addAll(fatJarExpanded);
            logger.info("Added {} BOOT-INF entries from fat JAR to classpath", fatJarExpanded.size());
        }

        logger.info("Built embedding subprocess classpath with {} entries", entries.size());
        return new ArrayList<>(entries);
    }

    private static boolean isSpringBootFatJar(String jarPath) {
        try (JarFile jarFile = new JarFile(jarPath)) {
            return jarFile.getEntry("BOOT-INF/lib/") != null || jarFile.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void extractBootInfClasspath(String fatJarPath, Set<String> outputEntries) throws IOException {
        Path fatJar = Path.of(fatJarPath).toAbsolutePath();
        Path extractDir = fatJar.getParent().resolve(".boot-inf-extracted");
        Path libDir = extractDir.resolve("lib");
        Path classesDir = extractDir.resolve("classes");

        try (JarFile jarFile = new JarFile(fatJarPath)) {
            if (jarFile.getEntry("BOOT-INF/classes/") != null) {
                Files.createDirectories(classesDir);
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/classes/") && !entry.isDirectory()) {
                        String relativePath = entry.getName().substring("BOOT-INF/classes/".length());
                        Path targetFile = classesDir.resolve(relativePath);
                        Files.createDirectories(targetFile.getParent());
                        if (!Files.exists(targetFile) || Files.getLastModifiedTime(targetFile).toMillis() < entry.getTime()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
                outputEntries.add(classesDir.toString());
            }

            if (jarFile.getEntry("BOOT-INF/lib/") != null) {
                Files.createDirectories(libDir);
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                        String jarName = entry.getName().substring("BOOT-INF/lib/".length());
                        Path targetJar = libDir.resolve(jarName);
                        try {
                            if (!Files.exists(targetJar) || Files.size(targetJar) != entry.getSize()) {
                                try (InputStream is = jarFile.getInputStream(entry)) {
                                    Files.copy(is, targetJar, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                            outputEntries.add(targetJar.toString());
                        } catch (IOException copyError) {
                            if (Files.exists(targetJar)) {
                                outputEntries.add(targetJar.toString());
                                logger.warn("Using existing BOOT-INF lib {} after refresh failed: {}",
                                        jarName, copyError.getMessage());
                            } else {
                                logger.warn("Skipping BOOT-INF lib {} after extraction failed: {}",
                                        jarName, copyError.getMessage());
                            }
                        }
                    }
                }
            }
        }
        logger.info("Extracted BOOT-INF entries to {}", extractDir);
    }

    private String resolveNativeExecutablePath(String configuredPath) {
        // Priority 1: Explicitly configured path
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path path = Paths.get(configuredPath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            logger.warn("Configured native executable path does not exist or is not executable: {}", configuredPath);
        }

        // Priority 2: Detect from current native image
        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            if (execPath != null) {
                logger.info("Using current native image executable: {}", execPath);
                return execPath;
            }
        }

        // Priority 3: Look in standard locations
        String[] searchPaths = {
            System.getProperty("user.home") + "/.kompile/bin/kompile-app",
            "/opt/kompile/kompile-app",
            "/usr/local/bin/kompile-app",
            "./kompile-app"
        };

        for (String searchPath : searchPaths) {
            Path path = Paths.get(searchPath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                logger.info("Found native executable at: {}", path);
                return path.toAbsolutePath().toString();
            }
        }

        return null;
    }

    /**
     * Start the embedding subprocess.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            logger.info("Subprocess already running");
            return;
        }

        if (shuttingDown.get()) {
            throw new IllegalStateException("Launcher is shutting down");
        }

        logger.info("Starting embedding subprocess...");
        logger.info("Launch mode: {}", launchMode);

        // Build command based on launch mode
        List<String> command = buildCommand();

        logger.info("Command: {}", String.join(" ", command));

        // Start process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false); // Keep stderr separate for logging
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }

        // Propagate all ND4J/CUDA/threading/Triton env vars via central propagator
        SubprocessEnvironmentPropagator.propagateToEnvironment(pb.environment());
        pb.environment().putAll(environment);
        if (localModelOnly) {
            restrictToLocalModelSources(pb.environment());
        }

        // Early native per-device memory bound (SD_MAX_DEVICE_BYTES). The matching physical ND4J cap
        // is also emitted by placement.jvmFlags() as nd4j.environment.maxDeviceMemory.
        placement.applyEnv(pb.environment());

        // Bridge DSP capture-OOM knobs from the managed nd4j config (Nd4jEnvironmentConfigService
        // exposes them as nd4j.dsp.* system properties) to the ND4J_DSP_* env vars libnd4j reads.
        // The document pipeline lane handled this first — the embedding lane's capture OOM
        // (2026-07-05) prescribed -Dnd4j.dsp.captureWorkspaceMb with no way to deliver it here.
        String dspCaptureWs = System.getProperty("nd4j.dsp.captureWorkspaceMb");
        if (dspCaptureWs != null && !dspCaptureWs.isBlank()) {
            pb.environment().put("ND4J_DSP_CAPTURE_WORKSPACE_MB", dspCaptureWs);
        }
        String dspProactiveEvict = System.getProperty("nd4j.dsp.proactiveEvict");
        if (dspProactiveEvict != null && !dspProactiveEvict.isBlank()) {
            pb.environment().put("ND4J_DSP_PROACTIVE_EVICT", Boolean.parseBoolean(dspProactiveEvict) ? "1" : "0");
        }
        String dspLruEviction = System.getProperty("nd4j.dsp.lruEviction");
        if (dspLruEviction != null && !dspLruEviction.isBlank()) {
            pb.environment().put("ND4J_DSP_LRU_EVICTION", Boolean.parseBoolean(dspLruEviction) ? "1" : "0");
        }

        // ALWAYS clear DSP diagnostics for the embedding subprocess.
        // SubprocessEnvironmentPropagator propagates ND4J_DSP_DIAGNOSTICS and all ND4J_* vars
        // from the parent environment, which can enable ~970k [DSP_DIAG] native trace lines.
        // Embedding inference never needs DSP diagnostics. Use an explicit opt-in flag
        // (set ND4J_EMBEDDING_DSP_DIAGNOSTICS env var) to re-enable for debugging.
        boolean dspDiagEnabled = System.getenv("ND4J_EMBEDDING_DSP_DIAGNOSTICS") != null;
        if (!dspDiagEnabled) {
            pb.environment().remove("ND4J_DSP_DIAGNOSTICS");
            pb.environment().remove("ND4J_DSP_DIAGNOSTICS_LEVEL");
            pb.environment().remove("ND4J_DSP_DIAGNOSTICS_BUFFER_SIZE");
            pb.environment().remove("ND4J_DSP_OOM_CAPTURE_DIR");
            pb.environment().remove("ND4J_DSP_OOM_CAPTURE_ENABLED");
            logger.debug("Cleared ND4J_DSP_DIAGNOSTICS env vars from embedding subprocess (set ND4J_EMBEDDING_DSP_DIAGNOSTICS to re-enable)");
        } else {
            logger.info("ND4J_EMBEDDING_DSP_DIAGNOSTICS is set — DSP diagnostics will be forwarded to embedding subprocess");
        }

        // Add debug-specific environment variables if enabled
        if (debugConfig != null && debugConfig.getMode() != DebugMode.NONE) {
            Map<String, String> debugEnv = debugConfig.buildEnvironmentVariables();
            if (!debugEnv.isEmpty()) {
                pb.environment().putAll(debugEnv);
                logger.info("Debug mode: {} - setting environment variables: {}",
                    debugConfig.getMode().getValue(), debugEnv.keySet());
            }
        }

        // FIX A: Cap OpenBLAS/OMP native thread count to 1 for the CPU embedding path.
        // nd4j-environment-config.json sets ompNumThreads:1 but the native OMP runtime only
        // honours OMP_NUM_THREADS / OPENBLAS_NUM_THREADS / GOTO_NUM_THREADS env vars — the
        // config-file value is silently ignored by libgomp/libopenblas.  Without these caps
        // each GEMM call spawns 4 OMP threads and each allocates 5–8 GB of UNCAPPED scratch,
        // totalling ~20 GB of avoidable native RSS on a quad-core embedding subprocess.
        // Single-threaded BLAS is the correct default for the CPU embedding path because the
        // batch-planner already parallelises at the request level (one text → one forward pass)
        // and intra-op parallelism causes contention and OOM at this subprocess's RSS budget.
        int ompThreads = Integer.getInteger("kompile.embedding.subprocess.ompThreads", 1);
        pb.environment().put("OMP_NUM_THREADS", String.valueOf(ompThreads));
        pb.environment().put("OPENBLAS_NUM_THREADS", String.valueOf(ompThreads));
        pb.environment().put("GOTO_NUM_THREADS", String.valueOf(ompThreads));
        logger.info("OMP/BLAS thread cap for embedding subprocess: {} (override via -Dkompile.embedding.subprocess.ompThreads)",
                ompThreads);

        process = pb.start();

        // Register with centralized subprocess registry for orphan protection and watchdog restart
        if (subprocessRegistry != null) {
            subprocessRegistry.register("embedding", process, "embedding");
            subprocessRegistry.registerRestartHandler(getSubprocessId(), this);
        }

        // Initialise per-run log writer (non-fatal if it fails)
        String logRunId = currentTaskId != null ? currentTaskId : UUID.randomUUID().toString();
        try {
            String workDir = pb.directory() != null ? pb.directory().getAbsolutePath() : System.getProperty("user.dir");
            SubprocessLogWriter slw = new SubprocessLogWriter("embedding", logRunId, workDir);
            slw.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    currentTaskId,
                    command,
                    workDir,
                    process.pid(),
                    maxHeapMb + "m"));
            subprocessLogWriter = slw;
        } catch (Exception _logEx) {
            logger.debug("SubprocessLogWriter init failed (non-fatal): {}", _logEx.getMessage());
        }

        // Set up I/O
        processStdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        processStdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        // Start output reader thread
        outputReaderThread = new Thread(this::readOutput, "embedding-subprocess-reader");
        outputReaderThread.setDaemon(true);
        outputReaderThread.start();

        // Start error reader thread (for logging)
        errorReaderThread = new Thread(this::readError, "embedding-subprocess-error");
        errorReaderThread.setDaemon(true);
        errorReaderThread.start();

        // Start health monitor
        healthMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-health-monitor");
            t.setDaemon(true);
            return t;
        });

        healthMonitor.scheduleAtFixedRate(this::checkHealth, 30, 30, TimeUnit.SECONDS);

        lastHeartbeat.set(System.currentTimeMillis());
        running.set(true);

        logger.info("Embedding subprocess started (PID: {})",
                process.pid());
    }

    /**
     * Resolve the {@code maxphysicalbytes} ceiling (MB) for this subprocess.
     *
     * JavaCPP's {@code physicalBytes()} on Linux measures SYSTEM-WIDE physical RAM,
     * NOT this process's RSS.  Setting {@code maxphysicalbytes} equal to the
     * per-process off-heap budget ({@code maxbytes}) means the check trips the instant
     * a sibling subprocess (e.g. the matrix graph at :8094) pushes TOTAL box RAM past the
     * threshold — causing a false OOM-restart even when THIS process is well within budget
     * (observed: embedding subprocess restarted at ~30g RSS on a box where sibling was using ~35g).
     *
     * The correct value is a near-machine-TOTAL guard (95% of physical RAM by default):
     * it must only trip when the WHOLE BOX is genuinely exhausted, not when a sibling is busy.
     * The per-process budget is separately enforced by {@code maxbytes}.
     *
     * Fraction is configurable via {@code -Dkompile.subprocess.maxphysical-fraction} (mirrors
     * the same property used by {@link ai.kompile.app.subprocess.ManagedSubprocessLauncher}).
     */
    // visible for testing (EmbeddingSubprocessPhysicalCeilingTest)
    long resolveSystemPhysicalCeilingMb(long offHeapFloorMb) {
        try {
            long totalBytes = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            double fraction = Double.parseDouble(
                    System.getProperty("kompile.subprocess.maxphysical-fraction", "0.95"));
            long ceilingMb = (long) (totalBytes / (1024.0 * 1024.0) * fraction);
            return Math.max(offHeapFloorMb, ceilingMb);
        } catch (Throwable t) {
            // Fallback: 3× the per-process floor keeps the guard sane without a MXBean
            return offHeapFloorMb * 3L;
        }
    }

    /**
     * Build the command for launching the subprocess based on launch mode.
     */
    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();

        // Add debug tool prefix if enabled (e.g., valgrind, compute-sanitizer)
        // This must come BEFORE the java command
        if (debugConfig != null && debugConfig.getMode() != DebugMode.NONE) {
            List<String> prefix = debugConfig.buildCommandPrefix();
            if (!prefix.isEmpty()) {
                command.addAll(prefix);
                logger.info("Debug mode: {} - adding command prefix: {}",
                    debugConfig.getMode().getValue(), String.join(" ", prefix));
            }
        }

        // Determine effective launch mode (may fall back to JVM if native not available)
        boolean useNativeMode = shouldUseNativeMode();

        if (useNativeMode) {
            // Native executable mode - resolve executable path
            String effectiveNativePath = resolveNativeExecutablePath();
            if (effectiveNativePath == null) {
                throw new IllegalStateException(
                    "Native executable mode selected but no executable path available. " +
                    "Configure the native executable path in Processing Settings (Developer Hub) or use JVM classpath mode.");
            }

            logger.info("Using native executable mode: {}", effectiveNativePath);
            command.add(effectiveNativePath);
            command.add(subprocessTypeFlag + "embedding");
        } else {
            // JVM classpath mode
            logger.info("Using JVM classpath mode");
            logger.info("Java home: {}", javaHome);
            logger.info("Max heap: {} MB", maxHeapMb);

            command.add(Path.of(javaHome, "bin", "java").toString());
            command.add("-Xmx" + maxHeapMb + "m");
            command.add("-Xms" + Math.min(maxHeapMb / 2, 1024) + "m");

            // Deterministic JavaCPP native-memory cap so transformer forward-pass activations
            // cannot exhaust host RAM.  maxPhysicalMb defaults to 4 × heap (set in constructor).
            // Without this the subprocess inherits an undefined/cgroup value and a large batch
            // can allocate ~36GB of native activation memory before the JVM detects anything.
            // maxbytes   = PER-PROCESS off-heap cap; the real lever bounding THIS subprocess's ND4J arrays.
            // maxphysicalbytes = SYSTEM-WIDE physical guard; must be near-machine-total so sibling
            //   subprocesses (graph matrix at :8094, etc.) do NOT false-trigger an OOM restart here.
            //   See resolveSystemPhysicalCeilingMb() for full explanation.
            long systemPhysicalCeilingMb = resolveSystemPhysicalCeilingMb(maxPhysicalMb);
            command.add("-Dorg.bytedeco.javacpp.maxbytes=" + maxPhysicalMb + "m");
            command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + systemPhysicalCeilingMb + "m");
            logger.info("JavaCPP native memory cap: maxbytes={}m maxphysicalbytes={}m (heap={}m)",
                    maxPhysicalMb, systemPhysicalCeilingMb, maxHeapMb);

            // Add performance tuning flags
            command.add("-XX:+UseG1GC");
            command.add("-XX:MaxGCPauseMillis=100");

            // Add debug-specific JVM args if enabled
            if (debugConfig != null && debugConfig.getMode() != DebugMode.NONE) {
                List<String> debugJvmArgs = debugConfig.buildJvmArgs();
                if (!debugJvmArgs.isEmpty()) {
                    command.addAll(debugJvmArgs);
                    logger.info("Debug mode: {} - adding JVM args: {}",
                        debugConfig.getMode().getValue(), String.join(" ", debugJvmArgs));
                }
            }

            // Give subprocess its own temp directory for native library extraction
            // (prevents conflicts with the parent process that may have loaded the same libraries).
            // Use a STABLE, reused directory: a fresh Files.createTempDirectory() per launch leaked
            // ~2GB of javacpp natives into /tmp on every (re)start (never cleaned), filling the tmpfs
            // and breaking later builds with "No space left on device". Only one embedding subprocess
            // runs at a time, so reuse is safe and lets javacpp reuse its extracted natives (faster
            // restarts, zero accumulation).
            try {
                Path subprocessTempDir = Path.of(System.getProperty("java.io.tmpdir"), "kompile-embedding-subprocess");
                Files.createDirectories(subprocessTempDir);
                command.add("-Dorg.bytedeco.javacpp.cachedir=" + subprocessTempDir.toAbsolutePath());
                command.add("-Djava.io.tmpdir=" + subprocessTempDir.toAbsolutePath());
                logger.info("Subprocess using temp directory: {}", subprocessTempDir);
            } catch (IOException e) {
                logger.warn("Could not create subprocess temp directory, using default", e);
            }

            // CRITICAL: Pass ALL ND4J environment values from parent to subprocess
            // This ensures the subprocess uses EXACTLY the same configuration as the parent
            // If device routing overrides are set, those values take precedence
            Integer drMaxThreads = this.deviceRoutingMaxThreads;
            Integer drMaxMasterThreads = this.deviceRoutingMaxMasterThreads;
            Integer drCudaDevice = this.deviceRoutingCudaDevice;
            Long drMaxDeviceMemory = this.deviceRoutingMaxDeviceMemory;
            boolean hasDeviceRouting = drMaxThreads != null || drCudaDevice != null || drMaxDeviceMemory != null;
            boolean subprocDebug = Boolean.getBoolean("kompile.embedding.subprocess.nd4j.debug");

            try {
                org.nd4j.linalg.factory.Environment env = org.nd4j.linalg.factory.Nd4j.getEnvironment();
                if (env != null) {
                    // ALWAYS force verbose=false and debug=false for the embedding subprocess.
                    // The parent JVM (Nd4jStartup) unconditionally sets both to true, which
                    // causes ~970k [DSP_DIAG] / KernelDispatch native trace lines per run and
                    // adds ~10 seconds per single-text embed (string-format + IO per op).
                    // Embedding never needs per-op native tracing — use an explicit opt-in
                    // flag (kompile.embedding.subprocess.nd4j.debug=true) if you ever need it.
                    command.add("-Dnd4j.environment.verbose=false");
                    command.add("-Dnd4j.environment.debug=" + subprocDebug);
                    command.add("-Dnd4j.environment.profiling=" + env.isProfiling());
                    command.add("-Dnd4j.environment.detectingLeaks=" + env.isDetectingLeaks());
                    command.add("-Dnd4j.environment.lifecycleTracking=" + env.isLifecycleTracking());
                    command.add("-Dnd4j.environment.trackViews=" + env.isTrackViews());
                    command.add("-Dnd4j.environment.trackDeletions=" + env.isTrackDeletions());
                    command.add("-Dnd4j.environment.trackOperations=" + env.isTrackOperations());
                    command.add("-Dnd4j.environment.ndArrayTracking=" + env.isNDArrayTracking());
                    command.add("-Dnd4j.environment.dataBufferTracking=" + env.isDataBufferTracking());
                    command.add("-Dnd4j.environment.tadCacheTracking=" + env.isTADCacheTracking());
                    command.add("-Dnd4j.environment.shapeCacheTracking=" + env.isShapeCacheTracking());
                    command.add("-Dnd4j.environment.opContextTracking=" + env.isOpContextTracking());

                    // Use device routing overrides for threads if available
                    long maxThreads = drMaxThreads != null ? drMaxThreads : env.maxThreads();
                    long maxMasterThreads = drMaxMasterThreads != null ? drMaxMasterThreads : env.maxMasterThreads();
                    command.add("-Dnd4j.environment.maxThreads=" + maxThreads);
                    command.add("-Dnd4j.environment.maxMasterThreads=" + maxMasterThreads);

                    // Device selection + per-device memory bound are delivered DEVICE-AGNOSTICALLY via
                    // the shared base infra: placement.jvmFlags() adds backend/device flags and the
                    // nd4j.environment.maxDeviceMemory carrier, while placement.applyEnv() adds the early
                    // native SD_MAX_DEVICE_BYTES bridge. NEVER CUDA_VISIBLE_DEVICES.

                    logger.info("Passed all ND4J environment config to subprocess (with device routing: {})",
                            hasDeviceRouting ? "enabled" : "disabled");
                }
            } catch (Exception e) {
                logger.warn("Could not get ND4J environment: {}", e.getMessage());
            }

            // Also pass all org.nd4j.* and related system properties.
            // Backend classpath augmentation (the SAME shared resolver the serving lane uses):
            // backend-priority/device flags can only select a backend whose JARs are actually on
            // the child classpath. Without this, a GPU placement emitted org.nd4j.gpu.priority
            // flags while the classpath carried only nd4j-native — Nd4jBackend silently fell back
            // to CpuBackend (RUNTIME_STATUS: requestedPlacement=gpu, effectiveBackend=CpuBackend,
            // 2026-09-01). When the placement demands GPU, inject the nd4j-cuda JARs from the
            // Maven local repo (dropping conflicting CPU-backend entries); otherwise the resolver
            // records/logs the CPU resolution for the same observability the serving lane has.
            Set<String> backendCpEntries = new LinkedHashSet<>(classpath);
            boolean gpuRequested = placement.effectiveBackend() == BackendPreference.GPU;
            SubprocessBackendResolver.augmentClasspathForBackend(backendCpEntries, gpuRequested, "EMBEDDING");
            String childClasspath = String.join(File.pathSeparator, backendCpEntries);
            String[] propertyPrefixes = {
                "org.nd4j.",           // All ND4J properties
                "org.bytedeco.",       // All JavaCPP/Bytedeco properties
                "nd4j.",               // ND4J environment properties
                "ai.djl.",             // DJL properties if used
                "onnxruntime.",        // ONNX Runtime properties if used
                "cuda.",               // CUDA properties if used
                "cudnn.",              // cuDNN properties if used
                "openblas.",           // OpenBLAS properties if used
                "mkl.",                // MKL properties if used
            };

            command.add("-Dorg.bytedeco.javacpp.logger.debug=" + subprocDebug);
            for (String key : System.getProperties().stringPropertyNames()) {
                if ("org.bytedeco.javacpp.logger.debug".equals(key)) {
                    continue;
                }
                for (String prefix : propertyPrefixes) {
                    if (key.startsWith(prefix)) {
                        String value = System.getProperty(key);
                        if (value != null && !value.isBlank()) {
                            command.add("-D" + key + "=" + value);
                            logger.debug("Passing property to subprocess: {}={}", key, value);
                        }
                        break;
                    }
                }
            }

            // Emit the backend-isolated path last so it overrides the unfiltered parent value.
            // Backend-first ordering also avoids stale same-SONAME LLVM/MLIR links in common
            // JavaCPP cache directories such as OpenBLAS.
            String runtimePath = NativeRuntimePathSelector.forChild(
                    System.getProperty("org.nd4j.presets.sharedRuntimePath"), childClasspath);
            if (runtimePath != null && !runtimePath.isBlank()) {
                command.add("-Dorg.nd4j.presets.sharedRuntimePath=" + runtimePath);
            }

            // Device-agnostic backend/device/cap delivery from the shared base infra — added AFTER the
            // inherited-property loop so scheduler placement always wins over any parent org.nd4j.*
            // priority or environment cap. No CUDA_VISIBLE_DEVICES or vendor env.
            command.addAll(placement.jvmFlags());

            // Add nd4j.backend.memory.fallback=true unless parent already set it.
            // When CUDA context is poisoned, this allows BackendManager to attempt CPU fallback.
            if (System.getProperty("nd4j.backend.memory.fallback") == null) {
                command.add("-Dnd4j.backend.memory.fallback=true");
                logger.debug("Added -Dnd4j.backend.memory.fallback=true to embedding subprocess command");
            }

            if (!localModelOnly) {
                // Managed deployments may resolve models through staging or a loaded archive.
                String stagingUrl = AnseriniEncoderFactory.getStagingUrl();
                String stagingApiKey = AnseriniEncoderFactory.getStagingApiKey();
                java.nio.file.Path archivePath = AnseriniEncoderFactory.getLoadedArchivePath();

                if (stagingUrl != null && !stagingUrl.isBlank()) {
                    command.add("-Dkompile.staging.url=" + stagingUrl);
                    logger.info("Passing staging URL to subprocess: {}", stagingUrl);
                }
                if (stagingApiKey != null && !stagingApiKey.isBlank()) {
                    command.add("-Dkompile.staging.apiKey=" + stagingApiKey);
                    logger.info("Passing staging API key to subprocess");
                }
                if (archivePath != null) {
                    command.add("-Dkompile.models.archivePath=" + archivePath.toAbsolutePath());
                    logger.info("Passing archive path to subprocess: {}", archivePath);
                }
            }

            // Classpath
            command.add("-cp");
            command.add(childClasspath);

            // Main class
            command.add(EmbeddingSubprocessMain.class.getName());
        }

        return command;
    }

    /**
     * Determine if native mode should be used.
     * Falls back to JVM mode if native is requested but not available.
     */
    private boolean shouldUseNativeMode() {
        if (launchMode == LaunchMode.JVM_CLASSPATH) {
            return false;
        }

        if (launchMode == LaunchMode.NATIVE_EXECUTABLE) {
            if (nativeExecutablePath != null) {
                return true;
            }
            // Native requested but not available, check if JVM fallback is possible
            if (NativeImageInfo.hasClasspath()) {
                logger.warn("Native executable mode requested but no executable found. " +
                           "Falling back to JVM classpath mode.");
                return false;
            }
            // No fallback available, native mode will fail
            return true;
        }

        // AUTO mode: use native if we have an executable or can detect one, and no classpath available
        String resolvedPath = resolveNativeExecutablePath();
        if (resolvedPath != null && !NativeImageInfo.hasClasspath()) {
            return true;
        }
        return !NativeImageInfo.hasClasspath();
    }

    /**
     * Resolve the native executable path.
     * Priority:
     * 1. Explicitly configured path
     * 2. Current native image executable (if running in native image)
     * 3. Search standard locations
     */
    private String resolveNativeExecutablePath() {
        // Priority 1: Explicitly configured path
        if (nativeExecutablePath != null && !nativeExecutablePath.isBlank()) {
            Path path = Paths.get(nativeExecutablePath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            logger.warn("Configured native executable path does not exist or is not executable: {}", nativeExecutablePath);
        }

        // Priority 2: Detect from current native image
        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            if (execPath != null) {
                logger.info("Auto-detected native executable from running native image: {}", execPath);
                return execPath;
            }
        }

        // Priority 3: Look in standard locations
        String[] searchPaths = {
            System.getProperty("user.home") + "/.kompile/bin/kompile-app",
            "/opt/kompile/kompile-app",
            "/usr/local/bin/kompile-app",
            "./kompile-app"
        };

        for (String searchPath : searchPaths) {
            Path path = Paths.get(searchPath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                logger.info("Found native executable at: {}", path);
                return path.toAbsolutePath().toString();
            }
        }

        return null;
    }

    /**
     * Get the current launch mode.
     */
    public LaunchMode getLaunchMode() {
        return launchMode;
    }

    /**
     * Check if using native executable mode.
     */
    public boolean isNativeMode() {
        return shouldUseNativeMode();
    }

    /**
     * Stop the subprocess gracefully.
     */
    public synchronized void stop() {
        // Set this before inspecting process/running state. Crash handling may be sleeping in its
        // restart backoff after it already marked the process dead; every such path re-checks this
        // flag before it can spawn a replacement.
        shuttingDown.set(true);
        logger.info("Stopping embedding subprocess...");

        // Send shutdown request
        try {
            String requestId = UUID.randomUUID().toString();
            EmbeddingSubprocessMessage.ShutdownRequest shutdown =
                new EmbeddingSubprocessMessage.ShutdownRequest(requestId);
            sendMessage(shutdown);

            // Wait briefly for graceful shutdown
            if (process != null && process.isAlive()) {
                boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                if (!exited) {
                    logger.warn("Subprocess did not exit gracefully, forcing termination");
                    forceTerminate(process, "graceful shutdown timeout");
                }
            }
        } catch (Exception e) {
            logger.warn("Error during graceful shutdown: {}", e.getMessage());
            if (process != null) {
                forceTerminate(process, "graceful shutdown error");
            }
        }

        // Stop health monitor
        if (healthMonitor != null) {
            healthMonitor.shutdownNow();
        }

        // Close I/O streams. With the process already terminated above, this drives the reader
        // threads' readLine() to EOF / "Stream closed" so they exit on their own.
        if (processStdin != null) {
            try { processStdin.close(); } catch (Exception ignored) {}
            processStdin = null;
        }
        if (processStdout != null) {
            try { processStdout.close(); } catch (Exception ignored) {}
            processStdout = null;
        }

        // Do NOT interrupt the reader threads. They persist subprocess output to H2 via logCallback;
        // a Thread.interrupt() landing while a reader is mid-write throws ClosedByInterruptException
        // on H2's NIO FileChannel and closes the ENTIRE application database ("database has been
        // closed"). The stream close above already breaks the read loop, so we just join (bounded)
        // and let each reader finish its current line cleanly. They are daemon threads, so a
        // straggler can never block JVM shutdown.
        Thread out = outputReaderThread;
        if (out != null) {
            try { out.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        Thread err = errorReaderThread;
        if (err != null) {
            try { err.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        // Complete any pending requests with error
        for (Map.Entry<String, CompletableFuture<EmbeddingSubprocessMessage>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new RuntimeException("Subprocess shutdown"));
        }
        pendingRequests.clear();

        running.set(false);
        modelLoaded = false;

        // Deregister from centralized subprocess registry
        if (subprocessRegistry != null) {
            subprocessRegistry.deregister("embedding");
        }

        // Finalise central log writer on graceful stop
        Integer stopExitCode = null;
        if (process != null) {
            try { stopExitCode = process.exitValue(); } catch (IllegalThreadStateException e) {
                logger.debug("Embedding subprocess still running when exit code was queried: {}", e.getMessage());
            }
        }
        finaliseSubprocessLog("COMPLETED", stopExitCode, null);

        logger.info("Embedding subprocess stopped");
    }

    /** True while the underlying OS process still exists, independent of launcher state flags. */
    public boolean isProcessAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /** Remove inherited JVM/model-source options that can redirect a local-only child. */
    static void restrictToLocalModelSources(Map<String, String> environment) {
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("KOMPILE_STAGING_URL");
        environment.remove("KOMPILE_STAGING_API_KEY");
        environment.remove("KOMPILE_MODELS_ARCHIVE_PATH");
    }

    /** Returns true if the embedding lane was permanently disabled due to a device-level CUDA error. */
    public boolean isLaneUnavailable() { return laneUnavailable.get(); }

    private void stopAndKillCurrentProcess() {
        Process p = this.process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    forceTerminate(p, "termination timeout");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                forceTerminate(p, "interrupted termination");
            }
        }
        if (subprocessRegistry != null) {
            subprocessRegistry.deregister("embedding");
        }
        running.set(false);
        modelLoaded = false;
    }

    private void forceTerminate(Process target, String reason) {
        if (target == null || !target.isAlive()) return;
        try {
            target.destroyForcibly();
            if (!target.waitFor(5, TimeUnit.SECONDS)) {
                logger.error("Embedding subprocess remains alive after forced termination ({})", reason);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for forced embedding subprocess termination ({})",
                    reason);
        } catch (Exception e) {
            logger.error("Failed to force embedding subprocess termination ({}): {}", reason,
                    e.getMessage());
        }
    }

    /**
     * Load a model in the subprocess.
     */
    public CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadModel(
            String modelId, int optimalBatchSize, int maxBatchSize) {

        return loadModel(modelId, optimalBatchSize, maxBatchSize, 0, null);
    }

    /**
     * Load a model in the subprocess with optional configuration.
     * <p>
     * Uses {@code loadModelTimeoutMs} if configured (> 0), otherwise falls back to
     * {@code requestTimeoutMs}.  This allows a longer timeout specifically for model
     * loading (CPU SameDiff warm-up can take ~111s) without raising the timeout for
     * all other short-lived requests.
     */
    public CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadModel(
            String modelId, int optimalBatchSize, int maxBatchSize, Map<String, String> modelConfig) {

        return loadModel(modelId, optimalBatchSize, maxBatchSize, 0, modelConfig);
    }

    /**
     * Load a model in the subprocess with explicit absolute-max batch size.
     *
     * @param absoluteMaxBatchSize ceiling forwarded to {@code GenericDenseSameDiffEncoder.configureBatchSize()};
     *                             0 means "use maxBatchSize as the ceiling" (safe default, avoids the old 8192 runaway)
     */
    public CompletableFuture<EmbeddingSubprocessMessage.LoadModelResponse> loadModel(
            String modelId, int optimalBatchSize, int maxBatchSize, int absoluteMaxBatchSize,
            Map<String, String> modelConfig) {

        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.LoadModelRequest request =
            new EmbeddingSubprocessMessage.LoadModelRequest(requestId, modelId, optimalBatchSize, maxBatchSize,
                    absoluteMaxBatchSize, modelConfig);

        // Prefer the dedicated loadModel timeout; fall back to the general request timeout.
        long effectiveTimeoutMs = loadModelTimeoutMs > 0 ? loadModelTimeoutMs : requestTimeoutMs;
        logger.info("LoadModel request {}: using timeout {}ms (loadModelTimeoutMs={}, requestTimeoutMs={})",
                requestId, effectiveTimeoutMs, loadModelTimeoutMs, requestTimeoutMs);

        return sendRequest(request, requestId, effectiveTimeoutMs)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.LoadModelResponse resp) {
                    if (resp.success()) {
                        currentModelId = resp.modelId();
                        currentDimensions = resp.dimensions();
                        encoderType = resp.encoderType();
                        modelLoaded = true;
                    }
                    return resp;
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Embed a single text.
     */
    public CompletableFuture<float[]> embed(String text) {
        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.EmbedRequest request =
            new EmbeddingSubprocessMessage.EmbedRequest(requestId, text);

        return sendRequest(request, requestId)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.EmbedResponse resp) {
                    if (resp.success()) {
                        return resp.embedding();
                    }
                    throw new RuntimeException("Embed failed: " + resp.error());
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Embed a single text and return full response with timing information.
     * This includes subprocess overhead timing (IPC roundtrip vs actual inference time).
     */
    public CompletableFuture<EmbeddingSubprocessMessage.EmbedResponse> embedWithTiming(String text) {
        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.EmbedRequest request =
            new EmbeddingSubprocessMessage.EmbedRequest(requestId, text);

        return sendRequest(request, requestId)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.EmbedResponse resp) {
                    return resp;
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Embed a batch of texts.
     */
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
        return embedBatch(texts, requestTimeoutMs);
    }

    /**
     * Embed a batch of texts using a caller-selected launcher timeout.
     *
     * <p>Batch embedding can legitimately exceed the short general request timeout on
     * CPU fixed-shape SameDiff runs. The model layer owns the user-visible batch
     * timeout while holding the heavy-memory gate; this method lets it prevent the
     * lower-level request correlator from expiring first and discarding a valid late
     * response.</p>
     */
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts, long effectiveTimeoutMs) {
        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.EmbedBatchRequest request =
            new EmbeddingSubprocessMessage.EmbedBatchRequest(requestId, texts);

        return sendRequest(request, requestId, effectiveTimeoutMs)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.EmbedBatchResponse resp) {
                    if (resp.success()) {
                        return resp.embeddings();
                    }
                    throw new RuntimeException("Embed batch failed: " + resp.error());
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Get subprocess status.
     */
    public CompletableFuture<EmbeddingSubprocessMessage.StatusResponse> getStatus() {
        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.StatusRequest request =
            new EmbeddingSubprocessMessage.StatusRequest(requestId);

        return sendRequest(request, requestId)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.StatusResponse resp) {
                    // Update local tracking from status
                    totalEmbeddingsProcessed = resp.totalEmbeddingsProcessed();
                    return resp;
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // OP TIMING METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Configure op timing in subprocess.
     *
     * @param enabled Whether to enable op timing
     * @param detailedMode Whether to use detailed mode (more overhead but more info)
     * @return Future with configuration response
     */
    public CompletableFuture<EmbeddingSubprocessMessage.OpTimingConfigResponse> configureOpTiming(
            boolean enabled, boolean detailedMode) {
        String requestId = UUID.randomUUID().toString();
        logger.info("=== SENDING OP TIMING CONFIG TO SUBPROCESS === enabled={}, detailed={}, requestId={}",
                enabled, detailedMode, requestId);

        EmbeddingSubprocessMessage.OpTimingConfigRequest request =
            new EmbeddingSubprocessMessage.OpTimingConfigRequest(requestId, enabled, detailedMode);

        return sendRequest(request, requestId)
            .thenApply(msg -> {
                logger.info("=== RECEIVED OP TIMING CONFIG RESPONSE === type={}", msg.getClass().getSimpleName());
                if (msg instanceof EmbeddingSubprocessMessage.OpTimingConfigResponse resp) {
                    logger.info("Op timing configured in subprocess: enabled={}, detailed={}, success={}",
                            resp.enabled(), resp.detailedMode(), resp.success());
                    return resp;
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Flush op timing stats from subprocess.
     *
     * @param topN Number of top ops to return (by total time), 0 for all
     * @param reset Whether to reset timing data after flush
     * @return Future with timing statistics
     */
    public CompletableFuture<EmbeddingSubprocessMessage.OpTimingFlushResponse> flushOpTiming(
            int topN, boolean reset) {
        String requestId = UUID.randomUUID().toString();
        EmbeddingSubprocessMessage.OpTimingFlushRequest request =
            new EmbeddingSubprocessMessage.OpTimingFlushRequest(requestId, topN, reset);

        return sendRequest(request, requestId)
            .thenApply(msg -> {
                if (msg instanceof EmbeddingSubprocessMessage.OpTimingFlushResponse resp) {
                    if (resp.success()) {
                        logger.info("Op timing flushed from subprocess: {} ops, {} executions",
                                resp.numOps(), resp.totalExecutions());
                    }
                    return resp;
                }
                throw new RuntimeException("Unexpected response type: " + msg.getClass().getSimpleName());
            });
    }

    /**
     * Send a request and wait for response.
     * <p>
     * Checks both the {@code running} flag and the actual process liveness before
     * attempting to send. If the process has died but {@code running} is still true
     * (race condition between health checks), this method will detect it and trigger
     * crash handling, giving the subprocess a chance to restart.
     */
    private CompletableFuture<EmbeddingSubprocessMessage> sendRequest(
            EmbeddingSubprocessMessage request, String requestId) {
        return sendRequest(request, requestId, requestTimeoutMs);
    }

    /**
     * Send a request and wait for response, using a caller-specified timeout.
     *
     * @param effectiveTimeoutMs timeout in ms; if {@code <= 0} waits indefinitely
     */
    private CompletableFuture<EmbeddingSubprocessMessage> sendRequest(
            EmbeddingSubprocessMessage request, String requestId, long effectiveTimeoutMs) {

        if (laneUnavailable.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Embedding lane unavailable: device-level CUDA error was encountered; subprocess will not be restarted"));
        }

        if (!running.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Subprocess not running"));
        }

        // Early check: if the process object is dead but running flag is still set,
        // trigger crash handling before trying to send the message.
        if (process != null && !process.isAlive() && !shuttingDown.get()) {
            logger.warn("Subprocess process is dead but running flag was still set. " +
                    "Triggering crash handling before request {}", requestId);
            handleSubprocessCrash();
            return CompletableFuture.failedFuture(
                new IOException("Subprocess process was dead - crash handling triggered. " +
                        "Retry after subprocess restarts."));
        }

        CompletableFuture<EmbeddingSubprocessMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            sendMessage(request);

            // Apply timeout only if configured (> 0), otherwise wait indefinitely
            if (effectiveTimeoutMs > 0) {
                return future.orTimeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((result, error) -> {
                        pendingRequests.remove(requestId);
                        if (error != null && error instanceof TimeoutException) {
                            logger.error("Request {} timed out after {}ms", requestId, effectiveTimeoutMs);
                        }
                    });
            } else {
                // No timeout - just track completion
                return future.whenComplete((result, error) -> {
                    pendingRequests.remove(requestId);
                });
            }

        } catch (Exception e) {
            pendingRequests.remove(requestId);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Send a message to the subprocess.
     * Checks that the process is still alive before writing to avoid
     * "Stream closed" errors when the subprocess has already exited.
     */
    private void sendMessage(EmbeddingSubprocessMessage message) throws IOException {
        if (processStdin == null) {
            throw new IOException("Subprocess stdin not available");
        }

        // Check if the process is still alive before attempting to write.
        // This prevents "Stream closed" / NullOutputStream errors when the
        // subprocess has died but running flag hasn't been updated yet.
        if (process == null || !process.isAlive()) {
            String exitInfo = "";
            if (process != null) {
                try {
                    exitInfo = " (exit code: " + process.exitValue() + ")";
                } catch (IllegalThreadStateException e) {
                    // Process still running after all - race condition, proceed
                }
            }
            throw new IOException("Subprocess process is not alive" + exitInfo
                    + " - cannot send message of type: " + message.getClass().getSimpleName());
        }

        String json = OBJECT_MAPPER.writeValueAsString(message);
        synchronized (stdinLock) {
            // Double-check after acquiring lock since process may have died while waiting
            if (process == null || !process.isAlive()) {
                throw new IOException("Subprocess process died while waiting to send message");
            }
            try {
                processStdin.write(json);
                processStdin.newLine();
                processStdin.flush();
            } catch (IOException e) {
                // The process likely died between our check and the write.
                // Trigger crash handling so the subprocess can be restarted.
                logger.error("Failed to write to subprocess stdin (process alive: {}): {}",
                        process != null && process.isAlive(), e.getMessage());
                if (!shuttingDown.get()) {
                    handleSubprocessCrash();
                }
                throw new IOException("Subprocess communication failed - process may have crashed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Persist a subprocess log line via the callback WITHOUT exposing the reader thread's interrupt
     * status to the persistence layer. A Java NIO interrupt during an H2 FileChannel write throws
     * ClosedByInterruptException and closes the ENTIRE application database, so we clear the interrupt
     * flag before touching H2 (and restore it afterwards so loop-exit logic still works). Combined
     * with stop() no longer interrupting these threads, a subprocess crash/restart can never take
     * down the app DB. Persistence is best-effort — failures are swallowed.
     */
    private void persistLogSafely(EmbeddingSubprocessMessage.Log logMsg) {
        Consumer<EmbeddingSubprocessMessage.Log> cb = logCallback;
        if (cb == null) {
            return;
        }
        boolean wasInterrupted = Thread.interrupted(); // clear flag before any H2 / NIO work
        try {
            cb.accept(logMsg);
        } catch (Throwable t) {
            logger.debug("Subprocess log persistence failed (non-fatal): {}", t.toString());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Read output from subprocess.
     */
    private void readOutput() {
        String line;
        boolean streamEnded = false;
        try {
            while (!Thread.currentThread().isInterrupted() && processStdout != null) {
                line = processStdout.readLine();
                if (line == null) {
                    streamEnded = true;
                    break; // Stream closed
                }

                // Check for protocol message
                if (line.startsWith(EmbeddingSubprocessMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(EmbeddingSubprocessMessage.MESSAGE_PREFIX.length());
                    try {
                        EmbeddingSubprocessMessage message = OBJECT_MAPPER.readValue(json, EmbeddingSubprocessMessage.class);
                        handleMessage(message);
                    } catch (Exception e) {
                        logger.error("Failed to parse subprocess message: {}", json, e);
                    }
                } else {
                    // Regular stdout line - forward to logCallback for database persistence.
                    // Demote routine lines to DEBUG to avoid double-INFO flooding (the structured
                    // EmbeddingSubprocessMessage.Log messages in handleLog() already surface
                    // real INFO/WARN/ERROR lines via the JSON protocol path).
                    // Only promote to WARN/ERROR when the line content indicates a real problem.
                    // Classify by the line's real logger level, not message-body substrings.
                    String level = determineLogLevel(line);
                    if ("ERROR".equals(level)) {
                        logger.warn("[subprocess:stdout] {}", line);
                    } else {
                        logger.debug("[subprocess:stdout] {}", line);
                    }
                    if (logCallback != null) {
                        EmbeddingSubprocessMessage.Log logMsg = new EmbeddingSubprocessMessage.Log(
                                level, "stdout", line, System.currentTimeMillis());
                        persistLogSafely(logMsg);
                    }
                    // Write to central log file (non-fatal)
                    try {
                        SubprocessLogWriter slw = subprocessLogWriter;
                        if (slw != null) {
                            slw.writeLine(AgentLogRecord.Stream.STDOUT, line);
                        }
                    } catch (Exception _logEx) {
                        logger.debug("SubprocessLogWriter stdout write failed: {}", _logEx.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!shuttingDown.get()) {
                logger.error("Error reading subprocess output: {}", e.getMessage());
                handleSubprocessCrash();
            }
        }

        if (streamEnded && !shuttingDown.get() && running.get()) {
            logger.error("Subprocess stdout closed unexpectedly while launcher was running");
            handleSubprocessCrash();
        }

        logger.info("Output reader thread exiting");
    }

    /**
     * Read error stream from subprocess (logging).
     */
    private void readError() {
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while (!Thread.currentThread().isInterrupted()) {
                line = errorReader.readLine();
                if (line == null) {
                    break;
                }
                // Route subprocess stderr at the appropriate level to avoid double-INFO spam.
                // Structured EmbeddingSubprocessMessage.Log messages (forwarded via JSON on
                // stdout) already surface real INFO/WARN/ERROR via handleLog(). Raw stderr
                // lines are demoted to DEBUG unless they indicate an actual error condition.
                // Classify by the line's real logger level (not message-body substrings):
                // benign diagnostics whose text mentions "error" (e.g. the tokenizer
                // printing document text "...formula errors") must not surface as ERROR.
                String level = determineLogLevel(line);
                boolean isErrorLine = "ERROR".equals(level);
                if (isErrorLine) {
                    logger.warn("[subprocess:stderr] {}", line);
                } else {
                    logger.debug("[subprocess:stderr] {}", line);
                }

                // Forward to logCallback so it gets persisted to the database
                if (logCallback != null) {
                    EmbeddingSubprocessMessage.Log logMsg = new EmbeddingSubprocessMessage.Log(
                            level, "stderr", line, System.currentTimeMillis());
                    persistLogSafely(logMsg);
                }
                // Write to central log file (non-fatal)
                try {
                    SubprocessLogWriter slw = subprocessLogWriter;
                    if (slw != null) {
                        slw.writeLine(AgentLogRecord.Stream.STDERR, line);
                    }
                } catch (Exception _logEx) {
                    logger.debug("SubprocessLogWriter stderr write failed: {}", _logEx.getMessage());
                }

                // Track recent error lines for crash diagnostics. Gate on the real ERROR
                // level (which already covers stack traces / OOM / use-after-free) so the
                // ring buffer isn't polluted by benign document text mentioning "error".
                if (isErrorLine) {
                    trackRecentError(line);
                }

                // Detect critical ND4J memory corruption errors that indicate model failure
                // USE-AFTER-FREE means constants in the SameDiff model were garbage collected
                // and the model is now producing garbage output - it cannot be recovered
                if (line.contains("USE-AFTER-FREE DETECTED") ||
                        line.contains("ND4JIllegalStateException") && line.contains("pointer-like value")) {
                    logger.error("CRITICAL: Detected ND4J memory corruption in subprocess. " +
                            "Model constants were garbage collected. Model must be reloaded.");
                    handleCriticalMemoryError(line);
                }
            }
        } catch (IOException e) {
            if (!shuttingDown.get()) {
                logger.warn("Error reading subprocess stderr: {}", e.getMessage());
            }
        }

        logger.info("Error reader thread exiting");
    }

    /**
     * Handle critical memory errors like USE-AFTER-FREE that indicate the model is corrupted.
     * This triggers a model failure notification so the UI can show the error.
     */
    private void handleCriticalMemoryError(String errorLine) {
        // Mark model as not loaded - it's producing garbage
        modelLoaded = false;

        // Notify via error callback
        if (errorCallback != null) {
            String errorMessage = "Critical ND4J memory error detected: Model constants were garbage collected. " +
                    "This typically indicates a memory management bug in ND4J. The subprocess must be restarted. " +
                    "Error: " + errorLine;
            EmbeddingSubprocessMessage.Error error = new EmbeddingSubprocessMessage.Error(
                    null,  // requestId - not associated with a specific request
                    errorMessage,
                    "ND4JMemoryCorruption",  // errorType
                    null,  // stackTrace - captured in errorLine
                    "INFERENCE"  // phase
            );
            errorCallback.accept(error);
        }

        // Track as recent error for diagnostics
        trackRecentError("CRITICAL MEMORY CORRUPTION: " + errorLine);
    }

    /**
     * Matches the actual log-level field a logger emits after the {@code [thread]}
     * bracket, e.g. {@code 12:00:00.123 [main] ERROR o.n.Foo - msg}. Anchoring on the
     * closing {@code ]} of the thread bracket means message bodies are never scanned,
     * so document text like {@code "formula errors"} or {@code "#REF! error"} cannot be
     * mistaken for an ERROR.
     */
    private static final Pattern STDERR_LEVEL_FIELD =
            Pattern.compile("^(?:[^\\]]*\\]\\s+)?(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b");

    /**
     * Determine the log level of a subprocess stderr line.
     *
     * <p>The level is taken from the line's explicit logger level field (see
     * {@link #STDERR_LEVEL_FIELD}) — NOT by substring-matching the message body, which
     * caused benign diagnostics whose text contains the word "error" (e.g. the
     * tokenizer printing {@code Text='...formula errors' -> 34 tokens}) to be surfaced
     * as ERROR job events. Raw diagnostic prints that carry no level field default to
     * INFO; only genuine JVM crash markers (stack traces, OOM, native use-after-free)
     * are always ERROR regardless of formatting.
     */
    private String determineLogLevel(String line) {
        if (line == null) return "INFO";
        // Genuine crash markers are errors regardless of formatting.
        if (line.startsWith("\tat ") || line.startsWith("Caused by:")
                || line.contains("Exception in thread")
                || line.contains("OutOfMemoryError")
                || line.contains("USE-AFTER-FREE")) {
            return "ERROR";
        }
        // Native runtime aborts print to raw fd 2 with no logger level field
        // (glibc/JNI aborts, JavaCPP load failures, hs_err frames, fatal signals).
        // These MUST classify as ERROR so trackRecentError retains them for crash
        // diagnostics — a native SIGABRT otherwise dies with an empty crashReason
        // because its abort lines fall through to INFO and are never retained.
        if (line.contains("terminate called after throwing")
                || line.contains("terminate called repeatedly")
                || line.contains("free(): invalid")
                || line.contains("malloc(): ")
                || line.contains("corrupted size vs. prev_size")
                || line.contains("corrupted top size")
                || line.contains("double free or corruption")
                || line.contains("Failed to load")
                || line.startsWith("A fatal error has been detected")
                || line.startsWith("# ")
                || line.contains("Internal Error")
                || line.contains("SIGABRT")
                || line.contains("SIGSEGV")) {
            return "ERROR";
        }
        // Otherwise trust the logger's own level field; default INFO when absent.
        Matcher m = STDERR_LEVEL_FIELD.matcher(line);
        if (m.find()) {
            String lvl = m.group(1).toUpperCase(Locale.ROOT);
            return "WARNING".equals(lvl) ? "WARN" : lvl;
        }
        return "INFO";
    }

    /**
     * Track a recent error line for crash diagnostics.
     */
    private void trackRecentError(String errorLine) {
        recentErrors.addLast(errorLine);
        while (recentErrors.size() > MAX_RECENT_ERRORS) {
            recentErrors.removeFirst();
        }
    }

    /**
     * Get recent error lines as a formatted string.
     */
    private String getRecentErrorsFormatted() {
        if (recentErrors.isEmpty()) {
            return "No recent error logs captured";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Recent subprocess errors:\n");
        for (String error : recentErrors) {
            sb.append("  ").append(error).append("\n");
        }
        return sb.toString();
    }

    /**
     * Handle a message from the subprocess.
     */
    private void handleMessage(EmbeddingSubprocessMessage message) {
        // Handle status/progress messages (no request ID correlation)
        if (message instanceof EmbeddingSubprocessMessage.Heartbeat heartbeat) {
            lastHeartbeat.set(System.currentTimeMillis());
            modelLoaded = heartbeat.modelLoaded();
            if (heartbeat.modelId() != null) {
                currentModelId = heartbeat.modelId();
            }
            totalEmbeddingsProcessed = heartbeat.totalEmbeddings();
            if (heartbeatCallback != null) {
                heartbeatCallback.accept(heartbeat);
            }
            return;
        }

        if (message instanceof EmbeddingSubprocessMessage.Progress progress) {
            if (progressCallback != null) {
                progressCallback.accept(progress);
            }
            return;
        }

        if (message instanceof EmbeddingSubprocessMessage.PhaseTransition transition) {
            if (phaseTransitionCallback != null) {
                phaseTransitionCallback.accept(transition);
            }
            return;
        }

        if (message instanceof EmbeddingSubprocessMessage.Log log) {
            // Log to our logger as well
            switch (log.level()) {
                case "ERROR":
                    logger.error("[subprocess:{}] {}", log.source(), log.message());
                    break;
                case "WARN":
                    logger.warn("[subprocess:{}] {}", log.source(), log.message());
                    break;
                case "DEBUG":
                    logger.debug("[subprocess:{}] {}", log.source(), log.message());
                    break;
                default:
                    logger.info("[subprocess:{}] {}", log.source(), log.message());
            }
            if (logCallback != null) {
                logCallback.accept(log);
            }
            return;
        }

        if (message instanceof EmbeddingSubprocessMessage.Error error) {
            logger.error("Subprocess error in phase {}: {} - {}",
                    error.phase(), error.errorType(), error.errorMessage());
            if (errorCallback != null) {
                errorCallback.accept(error);
            }
            // If this error has a request ID, complete the pending request
            if (error.requestId() != null) {
                CompletableFuture<EmbeddingSubprocessMessage> future = pendingRequests.remove(error.requestId());
                if (future != null) {
                    future.completeExceptionally(new RuntimeException(error.errorMessage()));
                }
            }
            return;
        }

        if (message instanceof EmbeddingSubprocessMessage.BatchResizeNotice notice) {
            // Log prominently so operators can tune absoluteMaxBatchSize
            logger.warn("EMBED_DECISION batchResize old={} new={} reason={} physicalBytes={} maxPhysicalBytes={}",
                    notice.oldBatch(), notice.newBatch(), notice.reason(),
                    notice.physicalBytes(), notice.maxPhysicalBytes());
            if (batchResizeCallback != null) {
                batchResizeCallback.accept(notice);
            }
            return;
        }

        // Handle request/response messages
        String requestId = extractRequestId(message);
        if (requestId != null) {
            CompletableFuture<EmbeddingSubprocessMessage> future = pendingRequests.remove(requestId);
            if (future != null) {
                future.complete(message);
            } else {
                logger.warn("No pending request for response: {}", requestId);
            }
        }
    }

    /**
     * Extract request ID from message.
     */
    private String extractRequestId(EmbeddingSubprocessMessage message) {
        if (message instanceof EmbeddingSubprocessMessage.LoadModelResponse resp) {
            return resp.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.EmbedResponse resp) {
            return resp.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.EmbedBatchResponse resp) {
            return resp.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.StatusResponse resp) {
            return resp.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.OpTimingConfigResponse resp) {
            return resp.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.OpTimingFlushResponse resp) {
            return resp.requestId();
        }
        return null;
    }

    /**
     * Check subprocess health.
     */
    private void checkHealth() {
        if (!running.get() || shuttingDown.get()) {
            return;
        }

        // Check if process is alive
        if (process == null || !process.isAlive()) {
            logger.error("Subprocess process is not alive!");
            handleSubprocessCrash();
            return;
        }

        // Check heartbeat timeout only if configured (> 0)
        if (heartbeatTimeoutMs > 0) {
            long lastHb = lastHeartbeat.get();
            long now = System.currentTimeMillis();
            if (now - lastHb > heartbeatTimeoutMs) {
                logger.error("Subprocess heartbeat timeout! Last heartbeat: {}ms ago",
                        now - lastHb);
                handleSubprocessCrash();
            }
        }
    }

    /**
     * Handle subprocess crash - capture details and attempt restart with tracking.
     */
    private void handleSubprocessCrash() {
        if (shuttingDown.get()) {
            return;
        }

        // Build detailed crash reason
        String crashReason = buildCrashReason();
        lastCrashReason = crashReason;

        // Get exit code for categorization
        int exitCode = -1;
        if (process != null && !process.isAlive()) {
            try {
                exitCode = process.exitValue();
            } catch (Exception e) {
                // Process state unknown
            }
        }

        // Device-level CUDA error — mark lane permanently unavailable, no restart.
        if (exitCode == DEVICE_ERROR_EXIT_CODE) {
            logger.error("[EmbeddingLauncher] Subprocess exited with DEVICE_ERROR (code {}): CUDA context was " +
                    "poisoned (error 700 or allocation failure cascade). Marking embedding lane as permanently " +
                    "unavailable — will NOT restart.", DEVICE_ERROR_EXIT_CODE);
            laneUnavailable.set(true);
            RuntimeException laneDeadEx = new RuntimeException(
                    "Embedding lane unavailable: subprocess exited with DEVICE_ERROR (CUDA context poisoned)");
            for (CompletableFuture<EmbeddingSubprocessMessage> future : pendingRequests.values()) {
                future.completeExceptionally(laneDeadEx);
            }
            pendingRequests.clear();
            running.set(false);
            modelLoaded = false;
            if (crashCallback != null) {
                crashCallback.accept(laneDeadEx);
            }
            finaliseSubprocessLog("DEVICE_ERROR", exitCode, laneDeadEx.getMessage());
            return; // no restart
        }

        logger.error("Subprocess crashed (exit code {}): {}", exitCode, crashReason);

        // Persist crash diagnostics to disk unconditionally — a native SIGABRT can
        // kill the child before its JSON protocol flushes anything, and the optional
        // SubprocessLogWriter may not have initialised. Without this, the only crash
        // evidence (stderr tail) dies with the launcher process.
        persistCrashDiagnostics(exitCode, crashReason);

        // Finalise central log writer on crash
        finaliseSubprocessLog("CRASHED", exitCode == -1 ? null : exitCode, crashReason);

        // Fail all pending requests with detailed error
        RuntimeException crashException = new RuntimeException("Subprocess crashed: " + crashReason);
        for (CompletableFuture<EmbeddingSubprocessMessage> future : pendingRequests.values()) {
            future.completeExceptionally(crashException);
        }
        pendingRequests.clear();

        running.set(false);
        modelLoaded = false;

        // Notify crash callback with detailed error
        if (crashCallback != null) {
            crashCallback.accept(crashException);
        }

        // Increment restart attempt counter
        restartAttempts++;

        // Check if restart should be attempted
        boolean shouldAttemptRestart = restartAttempts <= maxRestartAttempts;
        RestartConfiguration restartConfig = null;

        // Use restart policy callback if available (use empty taskId if none set)
        String effectiveTaskId = currentTaskId != null ? currentTaskId : "subprocess-" + currentModelId;
        if (restartPolicyCallback != null) {
            restartConfig = restartPolicyCallback.shouldRestart(effectiveTaskId, exitCode, crashReason, restartAttempts);
            shouldAttemptRestart = restartConfig != null;
        }

        if (!shouldAttemptRestart) {
            logger.warn("Not attempting restart: attempt {} exceeds max {} or policy declined",
                    restartAttempts, maxRestartAttempts);

            // Notify policy callback that restarts are exhausted
            if (restartPolicyCallback != null) {
                try {
                    restartPolicyCallback.onRestartExhausted(effectiveTaskId, restartAttempts, crashReason);
                } catch (Exception e) {
                    logger.warn("Failed to notify restart exhausted: {}", e.getMessage());
                }
            }
            return;
        }

        // Calculate backoff time
        long backoffMs = restartConfig != null ? restartConfig.backoffMs()
                : (long) (initialBackoffMs * Math.pow(backoffMultiplier, restartAttempts - 1));

        String reason = restartConfig != null ? restartConfig.reason() : categorizeFailureReason(exitCode);

        logger.info("Attempting restart {}/{} after {}ms delay (reason: {})",
                restartAttempts, maxRestartAttempts, backoffMs, reason);

        // Notify policy callback of restart attempt
        if (restartPolicyCallback != null) {
            try {
                restartPolicyCallback.onRestartAttempt(
                        effectiveTaskId, currentFileName,
                        restartAttempts, maxRestartAttempts,
                        reason, restartConfig
                );
            } catch (Exception e) {
                logger.warn("Failed to notify restart attempt: {}", e.getMessage());
            }
        }

        // Attempt restart with backoff
        try {
            Thread.sleep(backoffMs);
            // Re-check shuttingDown AFTER the backoff sleep — stop() may have been called while we slept.
            if (shuttingDown.get()) {
                logger.info("[EmbeddingLauncher] Restart suppressed: shutdown was initiated during backoff sleep");
                return;
            }
            recentErrors.clear(); // Clear errors before restart
            start();
            // Final re-check: if shutdown started while start() was running, stop the new process.
            if (shuttingDown.get()) {
                logger.warn("[EmbeddingLauncher] Shutdown detected immediately after subprocess restart — stopping new process");
                stopAndKillCurrentProcess();
                return;
            }

            // Notify policy callback of successful restart (subprocess is running)
            if (restartPolicyCallback != null) {
                try {
                    restartPolicyCallback.onRestartSuccess(effectiveTaskId, restartAttempts);
                } catch (Exception e) {
                    logger.warn("Failed to notify restart success: {}", e.getMessage());
                }
            }

            // Reload model if one was loaded
            if (currentModelId != null) {
                logger.info("Reloading model after restart: {}", currentModelId);
                loadModel(currentModelId, 32, 64).join();
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Restart backoff interrupted, aborting restart");
            return;
        } catch (Exception e) {
            logger.error("Failed to restart subprocess: {}", e.getMessage());
        }
    }

    /**
     * Categorize failure reason from exit code.
     */
    private String categorizeFailureReason(int exitCode) {
        return switch (exitCode) {
            case 137 -> "OOM_KILLED";
            case 134, 136, 139 -> "NATIVE_CRASH";
            case 130, 143 -> "CANCELLED";
            case 0 -> "NORMAL_EXIT";
            case 1 -> "GENERAL_ERROR";
            case -1 -> "UNKNOWN";
            default -> exitCode > 128 ? "SIGNAL_" + (exitCode - 128) : "EXIT_" + exitCode;
        };
    }

    /**
     * Build a detailed crash reason including exit code and recent errors.
     */
    private String buildCrashReason() {
        StringBuilder sb = new StringBuilder();

        // Check exit code if process exited
        if (process != null) {
            try {
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    sb.append("Exit code: ").append(exitCode);
                    sb.append(interpretExitCode(exitCode));
                } else {
                    sb.append("Process unresponsive (heartbeat timeout)");
                }
            } catch (Exception e) {
                sb.append("Process state unknown");
            }
        } else {
            sb.append("Process not found");
        }

        // Add model context
        if (currentModelId != null) {
            sb.append(" | Model: ").append(currentModelId);
        }

        // Add recent errors if available
        if (!recentErrors.isEmpty()) {
            sb.append("\n").append(getRecentErrorsFormatted());
        }

        return sb.toString();
    }

    /**
     * Write crash diagnostics (exit code, crash reason, stderr tail) to
     * {@code ~/.kompile/logs/subprocesses/embedding/crash-<timestamp>.log}.
     * Best-effort: failures are logged and ignored — never mask the crash itself.
     */
    private void persistCrashDiagnostics(int exitCode, String crashReason) {
        try {
            Path dir = Path.of(System.getProperty("user.home"),
                    ".kompile", "logs", "subprocesses", "embedding");
            Files.createDirectories(dir);
            Path crashFile = dir.resolve("crash-"
                    + System.currentTimeMillis() + ".log");
            String report = "=== Embedding subprocess crash ===\n"
                    + "time: " + java.time.Instant.now() + "\n"
                    + "exitCode: " + exitCode + " ("
                    + interpretExitCode(exitCode).trim() + ")\n"
                    + "model: " + currentModelId + "\n"
                    + "--- crash reason ---\n" + crashReason + "\n"
                    + "--- recent stderr ---\n" + getRecentErrorsFormatted() + "\n";
            Files.writeString(crashFile, report);
            logger.warn("Crash diagnostics written to {}", crashFile);
        } catch (Exception e) {
            logger.warn("Failed to persist crash diagnostics: {}", e.getMessage());
        }
    }

    /**
     * Interpret common exit codes to provide helpful context.
     */
    private String interpretExitCode(int exitCode) {
        return switch (exitCode) {
            case 0 -> " (normal exit)";
            case 1 -> " (general error)";
            case 137 -> " (killed by SIGKILL - likely OOM killer)";
            case 139 -> " (segmentation fault - native library crash)";
            case 143 -> " (killed by SIGTERM)";
            case 255 -> " (exit code overflow or error)";
            default -> exitCode > 128 ? " (killed by signal " + (exitCode - 128) + ")" : "";
        };
    }

    /**
     * Get the last crash reason for diagnostics.
     */
    public String getLastCrashReason() {
        return lastCrashReason;
    }

    /**
     * Check if subprocess is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if model is loaded.
     */
    public boolean isModelLoaded() {
        return modelLoaded;
    }

    /**
     * Get current model ID.
     */
    public String getCurrentModelId() {
        return currentModelId;
    }

    /**
     * Get current dimensions.
     */
    public int getCurrentDimensions() {
        return currentDimensions;
    }

    /**
     * Get encoder type.
     */
    public String getEncoderType() {
        return encoderType;
    }

    /**
     * Get total embeddings processed.
     */
    public long getTotalEmbeddingsProcessed() {
        return totalEmbeddingsProcessed;
    }

    /**
     * Set the heartbeat callback.
     */
    public void setHeartbeatCallback(Consumer<EmbeddingSubprocessMessage.Heartbeat> callback) {
        this.heartbeatCallback = callback;
    }

    /**
     * Set the progress callback.
     */
    public void setProgressCallback(Consumer<EmbeddingSubprocessMessage.Progress> callback) {
        this.progressCallback = callback;
    }

    /**
     * Set the phase transition callback.
     */
    public void setPhaseTransitionCallback(Consumer<EmbeddingSubprocessMessage.PhaseTransition> callback) {
        this.phaseTransitionCallback = callback;
    }

    /**
     * Set the log callback.
     */
    public void setLogCallback(Consumer<EmbeddingSubprocessMessage.Log> callback) {
        this.logCallback = callback;
    }

    /**
     * Set the error callback.
     */
    public void setErrorCallback(Consumer<EmbeddingSubprocessMessage.Error> callback) {
        this.errorCallback = callback;
    }

    /**
     * Set the crash callback.
     */
    public void setCrashCallback(Consumer<Exception> callback) {
        this.crashCallback = callback;
    }

    /**
     * Set the batch-resize callback (native-memory-pressure shrink decisions).
     */
    public void setBatchResizeCallback(Consumer<EmbeddingSubprocessMessage.BatchResizeNotice> callback) {
        this.batchResizeCallback = callback;
    }

    // ===================== RESTART TRACKING METHODS =====================

    /**
     * Callback interface for restart policy decisions.
     * Allows the main application to provide restart logic without tight coupling.
     */
    public interface RestartPolicyCallback {
        /**
         * Determine if the subprocess should be restarted after a crash.
         *
         * @param taskId The current task ID
         * @param exitCode The process exit code
         * @param crashReason The detailed crash reason
         * @param attemptNumber The restart attempt number (1-based)
         * @return Configuration for restart, or null if restart should not be attempted
         */
        RestartConfiguration shouldRestart(String taskId, int exitCode, String crashReason, int attemptNumber);

        /**
         * Called when a restart attempt is made.
         *
         * @param taskId Task identifier
         * @param fileName File being processed
         * @param attemptNumber Attempt number (1-based)
         * @param maxAttempts Maximum attempts configured
         * @param reason Restart reason
         * @param config Restart configuration being used
         */
        void onRestartAttempt(String taskId, String fileName, int attemptNumber, int maxAttempts,
                              String reason, RestartConfiguration config);

        /**
         * Called when the subprocess is successfully restarted (subprocess is running).
         *
         * @param taskId Task identifier
         * @param attemptNumber Attempt that succeeded
         */
        void onRestartSuccess(String taskId, int attemptNumber);

        /**
         * Called when all restart attempts are exhausted.
         *
         * @param taskId Task identifier
         * @param totalAttempts Total attempts made
         * @param lastReason Last failure reason
         */
        void onRestartExhausted(String taskId, int totalAttempts, String lastReason);
    }

    /**
     * Configuration returned by restart policy for a restart attempt.
     */
    public record RestartConfiguration(
            long backoffMs,
            long newHeapBytes,
            int newBatchSize,
            int newThreadCount,
            String reason
    ) {}

    /**
     * Set the restart policy callback for handling restart decisions.
     */
    public void setRestartPolicyCallback(RestartPolicyCallback callback) {
        this.restartPolicyCallback = callback;
    }

    /**
     * Configure restart settings.
     *
     * @param maxAttempts Maximum restart attempts (0 to disable)
     * @param initialBackoffMs Initial backoff time in milliseconds
     * @param multiplier Backoff multiplier for exponential backoff
     */
    public void setRestartConfig(int maxAttempts, long initialBackoffMs, double multiplier) {
        this.maxRestartAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = multiplier;
        logger.info("Configured restart settings: maxAttempts={}, initialBackoff={}ms, multiplier={}",
                maxAttempts, initialBackoffMs, multiplier);
    }

    /**
     * Set the current task context for restart tracking.
     * Should be called when starting to process a new task.
     *
     * @param taskId Task identifier
     * @param fileName File being processed
     */
    public void setCurrentTaskContext(String taskId, String fileName) {
        this.currentTaskId = taskId;
        this.currentFileName = fileName;
        this.restartAttempts = 0; // Reset restart attempts for new task
    }

    /**
     * Clear the current task context.
     * Should be called when task processing completes or is cancelled.
     */
    public void clearCurrentTaskContext() {
        this.currentTaskId = null;
        this.currentFileName = null;
        this.restartAttempts = 0;
    }

    /**
     * Get the number of restart attempts for the current task.
     */
    public int getRestartAttempts() {
        return restartAttempts;
    }

    /**
     * Get the maximum restart attempts configured.
     */
    public int getMaxRestartAttempts() {
        return maxRestartAttempts;
    }

    /**
     * Write end record and close the SubprocessLogWriter (non-fatal, best-effort).
     *
     * @param state      terminal state string (e.g. "COMPLETED", "CRASHED", "CANCELLED")
     * @param exitCode   process exit code, or null if unknown
     * @param errorMsg   error description, or null
     */
    private void finaliseSubprocessLog(String state, Integer exitCode, String errorMsg) {
        SubprocessLogWriter slw = subprocessLogWriter;
        subprocessLogWriter = null;
        if (slw == null) {
            return;
        }
        try {
            boolean oomDetected = exitCode != null && exitCode == 137;
            slw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMsg, oomDetected, null));
        } catch (Exception _logEx) {
            logger.debug("SubprocessLogWriter writeEnd failed: {}", _logEx.getMessage());
        }
        try {
            slw.close();
        } catch (Exception _logEx) {
            logger.debug("SubprocessLogWriter close failed: {}", _logEx.getMessage());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        stop();
    }
}
