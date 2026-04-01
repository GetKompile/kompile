/*
 * Copyright (c) 2024 Konduit AI.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for subprocess debugging tools.
 * Supports various debugging modes for CUDA, memory checking, and JVM diagnostics.
 *
 * <p>Debug modes available:
 * <ul>
 *   <li><b>CUDA Tools:</b> compute-sanitizer (memcheck, racecheck, initcheck, synccheck), cuda-gdb</li>
 *   <li><b>Memory Tools:</b> valgrind, valgrind-minimal, asan, efence, malloc-check</li>
 *   <li><b>JVM Tools:</b> native-memory-tracking, verbose-jni, extensive-error-reports</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "kompile.subprocess.debug")
public class SubprocessDebugConfig {

    /**
     * Debug mode enum with all supported debugging tools.
     */
    public enum DebugMode {
        /** No debugging */
        NONE("none", "No debugging", false, false),

        // CUDA debugging tools
        /** CUDA compute-sanitizer memcheck - detects memory errors */
        COMPUTE_SANITIZER_MEMCHECK("compute-sanitizer", "CUDA memory checker (memcheck)", true, false),
        /** CUDA compute-sanitizer racecheck - detects race conditions */
        COMPUTE_SANITIZER_RACECHECK("compute-sanitizer-race", "CUDA race condition checker", true, false),
        /** CUDA compute-sanitizer initcheck - detects uninitialized memory */
        COMPUTE_SANITIZER_INITCHECK("compute-sanitizer-init", "CUDA uninitialized memory checker", true, false),
        /** CUDA compute-sanitizer synccheck - detects synchronization errors */
        COMPUTE_SANITIZER_SYNCCHECK("compute-sanitizer-sync", "CUDA synchronization checker", true, false),
        /** CUDA debugger - interactive */
        CUDA_GDB("cuda-gdb", "CUDA debugger (interactive)", true, true),

        // Memory debugging tools
        /** Valgrind full leak check */
        VALGRIND("valgrind", "Valgrind full leak check", false, false),
        /** Valgrind minimal/fast check */
        VALGRIND_MINIMAL("valgrind-minimal", "Valgrind minimal (faster)", false, false),
        /** glibc malloc checking */
        MALLOC_CHECK("malloc-check", "glibc heap checking (MALLOC_CHECK_=3)", false, false),
        /** AddressSanitizer */
        ASAN("asan", "AddressSanitizer", false, false),
        /** Electric Fence */
        EFENCE("efence", "Electric Fence (use-after-free detection)", false, false),

        // JVM diagnostic tools
        /** Enable native memory tracking */
        NATIVE_MEMORY_TRACKING("native-memory-tracking", "JVM Native Memory Tracking", false, false),
        /** Enable verbose JNI logging */
        VERBOSE_JNI("verbose-jni", "Verbose JNI logging", false, false),
        /** Enable extensive error reports */
        EXTENSIVE_ERROR_REPORTS("extensive-error-reports", "JVM extensive error reports", false, false),
        /** All JVM diagnostics combined */
        JVM_DIAGNOSTICS("jvm-diagnostics", "All JVM diagnostic options", false, false);

        private final String value;
        private final String description;
        private final boolean requiresCuda;
        private final boolean interactive;

        DebugMode(String value, String description, boolean requiresCuda, boolean interactive) {
            this.value = value;
            this.description = description;
            this.requiresCuda = requiresCuda;
            this.interactive = interactive;
        }

        public String getValue() { return value; }
        public String getDescription() { return description; }
        public boolean requiresCuda() { return requiresCuda; }
        public boolean isInteractive() { return interactive; }

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

    /** Currently active debug mode */
    private DebugMode mode = DebugMode.NONE;

    /** Directory for debug log files */
    private String logDirectory = "./logs/debug";

    /** Whether to disable JIT compilation (-Djava.compiler=NONE) */
    private boolean disableJit = false;

    /** Extra JVM args to add when debugging */
    private List<String> extraJvmArgs = new ArrayList<>();

    /** Environment variables to set for debugging */
    private Map<String, String> environmentVariables = new HashMap<>();

    // Getters and setters
    public DebugMode getMode() { return mode; }
    public void setMode(DebugMode mode) { this.mode = mode; }

    public String getLogDirectory() { return logDirectory; }
    public void setLogDirectory(String logDirectory) { this.logDirectory = logDirectory; }

    public boolean isDisableJit() { return disableJit; }
    public void setDisableJit(boolean disableJit) { this.disableJit = disableJit; }

    public List<String> getExtraJvmArgs() { return extraJvmArgs; }
    public void setExtraJvmArgs(List<String> extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }

    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    /**
     * Build the command prefix for the current debug mode.
     * This returns the tool command that should prefix the java command.
     *
     * @return List of command prefix arguments, or empty list if no debug mode
     */
    public List<String> buildCommandPrefix() {
        List<String> prefix = new ArrayList<>();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path logDir = Paths.get(logDirectory).toAbsolutePath();

        switch (mode) {
            case COMPUTE_SANITIZER_MEMCHECK:
                prefix.add("compute-sanitizer");
                prefix.add("--tool");
                prefix.add("memcheck");
                prefix.add("--log-file");
                prefix.add(logDir.resolve("compute_sanitizer_" + timestamp + ".log").toString());
                break;

            case COMPUTE_SANITIZER_RACECHECK:
                prefix.add("compute-sanitizer");
                prefix.add("--tool");
                prefix.add("racecheck");
                prefix.add("--log-file");
                prefix.add(logDir.resolve("racecheck_" + timestamp + ".log").toString());
                break;

            case COMPUTE_SANITIZER_INITCHECK:
                prefix.add("compute-sanitizer");
                prefix.add("--tool");
                prefix.add("initcheck");
                prefix.add("--log-file");
                prefix.add(logDir.resolve("initcheck_" + timestamp + ".log").toString());
                break;

            case COMPUTE_SANITIZER_SYNCCHECK:
                prefix.add("compute-sanitizer");
                prefix.add("--tool");
                prefix.add("synccheck");
                prefix.add("--log-file");
                prefix.add(logDir.resolve("synccheck_" + timestamp + ".log").toString());
                break;

            case CUDA_GDB:
                prefix.add("cuda-gdb");
                prefix.add("--args");
                break;

            case VALGRIND:
                prefix.add("valgrind");
                prefix.add("--leak-check=full");
                prefix.add("--show-leak-kinds=all");
                prefix.add("--track-origins=yes");
                prefix.add("--log-file=" + logDir.resolve("valgrind_" + timestamp + ".log"));
                break;

            case VALGRIND_MINIMAL:
                prefix.add("valgrind");
                prefix.add("--leak-check=summary");
                prefix.add("--show-leak-kinds=definite");
                prefix.add("--track-origins=no");
                prefix.add("--log-file=" + logDir.resolve("valgrind_minimal_" + timestamp + ".log"));
                break;

            default:
                // No prefix for other modes
                break;
        }

        return prefix;
    }

    /**
     * Build additional JVM arguments for the current debug mode.
     *
     * @return List of JVM arguments
     */
    public List<String> buildJvmArgs() {
        List<String> args = new ArrayList<>();

        switch (mode) {
            case NATIVE_MEMORY_TRACKING:
            case JVM_DIAGNOSTICS:
                args.add("-XX:+UnlockDiagnosticVMOptions");
                args.add("-XX:NativeMemoryTracking=detail");
                if (mode == DebugMode.JVM_DIAGNOSTICS) {
                    args.add("-XX:+ExtensiveErrorReports");
                    args.add("-verbose:jni");
                }
                break;

            case VERBOSE_JNI:
                args.add("-verbose:jni");
                break;

            case EXTENSIVE_ERROR_REPORTS:
                args.add("-XX:+UnlockDiagnosticVMOptions");
                args.add("-XX:+ExtensiveErrorReports");
                break;

            default:
                break;
        }

        // Add JIT disable if requested
        if (disableJit) {
            args.add("-Djava.compiler=NONE");
        }

        // Add any extra JVM args
        args.addAll(extraJvmArgs);

        return args;
    }

    /**
     * Build environment variables for the current debug mode.
     *
     * @return Map of environment variables to set
     */
    public Map<String, String> buildEnvironmentVariables() {
        Map<String, String> env = new HashMap<>(environmentVariables);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path logDir = Paths.get(logDirectory).toAbsolutePath();

        switch (mode) {
            case MALLOC_CHECK:
                env.put("MALLOC_CHECK_", "3");
                env.put("LIBC_FATAL_STDERR_", "1");
                break;

            case ASAN:
                env.put("ASAN_OPTIONS",
                    "detect_leaks=1:halt_on_error=0:print_stats=1:log_path=" +
                    logDir.resolve("asan_" + timestamp + ".log"));
                // Note: LD_PRELOAD for libasan needs to be set carefully
                // env.put("LD_PRELOAD", "/usr/lib64/libasan.so.8");
                break;

            case EFENCE:
                env.put("LD_PRELOAD", "/usr/lib64/libefence.so");
                env.put("EF_PROTECT_BELOW", "0");
                env.put("EF_PROTECT_FREE", "1");
                env.put("EF_ALLOW_MALLOC_0", "1");
                break;

            default:
                break;
        }

        return env;
    }

    /**
     * Get a description of the current debug configuration.
     *
     * @return Human-readable description
     */
    public String getDescription() {
        if (mode == DebugMode.NONE) {
            return "No debugging enabled";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Debug mode: ").append(mode.getDescription());

        List<String> prefix = buildCommandPrefix();
        if (!prefix.isEmpty()) {
            sb.append("\nCommand prefix: ").append(String.join(" ", prefix));
        }

        List<String> jvmArgs = buildJvmArgs();
        if (!jvmArgs.isEmpty()) {
            sb.append("\nJVM args: ").append(String.join(" ", jvmArgs));
        }

        Map<String, String> envVars = buildEnvironmentVariables();
        if (!envVars.isEmpty()) {
            sb.append("\nEnvironment: ").append(envVars);
        }

        return sb.toString();
    }

    /**
     * Create a copy of this config with a different debug mode.
     */
    public SubprocessDebugConfig withMode(DebugMode newMode) {
        SubprocessDebugConfig copy = new SubprocessDebugConfig();
        copy.mode = newMode;
        copy.logDirectory = this.logDirectory;
        copy.disableJit = this.disableJit;
        copy.extraJvmArgs = new ArrayList<>(this.extraJvmArgs);
        copy.environmentVariables = new HashMap<>(this.environmentVariables);
        return copy;
    }
}
