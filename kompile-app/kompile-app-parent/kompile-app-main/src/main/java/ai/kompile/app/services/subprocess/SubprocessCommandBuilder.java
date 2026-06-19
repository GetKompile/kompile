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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.cli.common.util.NativeImageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds subprocess launch commands for vector population jobs.
 *
 * Handles both JVM classpath mode and native executable mode,
 * memory argument construction, and ND4J environment propagation.
 */
@Component
public class SubprocessCommandBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessCommandBuilder.class);

    private static final String SUBPROCESS_MAIN_CLASS = "ai.kompile.app.subprocess.VectorPopulationSubprocessMain";
    private static final Pattern MEMORY_SIZE_PATTERN = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]{0,2})$");

    @Value("${kompile.vectorpopulation.subprocess.java-path:java}")
    private String fallbackJavaPath;

    @Value("${kompile.vectorpopulation.subprocess.heap-size:4g}")
    private String fallbackHeapSize;

    private final SubprocessConfigService subprocessConfigService;

    public SubprocessCommandBuilder(SubprocessConfigService subprocessConfigService) {
        this.subprocessConfigService = subprocessConfigService;
    }

    /**
     * Memory overrides for subprocess restart.
     */
    public record MemoryOverrides(
            String heapSize,
            Long offHeapBytes) {

        public static MemoryOverrides none() {
            return new MemoryOverrides(null, null);
        }

        public static MemoryOverrides of(String heap, long offHeap) {
            return new MemoryOverrides(heap, offHeap);
        }

        public boolean hasOverrides() {
            return (heapSize != null && !heapSize.isBlank()) || (offHeapBytes != null && offHeapBytes > 0);
        }
    }

    /**
     * Thread override settings for restart recovery.
     */
    public record ThreadOverrides(
            Integer ompNumThreads,
            Integer openBlasNumThreads,
            Integer mklNumThreads) {

        public static ThreadOverrides none() {
            return new ThreadOverrides(null, null, null);
        }

        public static ThreadOverrides from(int ompThreads, int blasThreads) {
            return new ThreadOverrides(ompThreads, blasThreads, ompThreads);
        }

        public boolean hasOverrides() {
            return ompNumThreads != null || openBlasNumThreads != null || mklNumThreads != null;
        }
    }

    /**
     * Build the subprocess command.
     *
     * @param argsFile        Path to the subprocess arguments JSON file
     * @param memoryOverrides Optional memory overrides from restart config.
     */
    public List<String> buildCommand(Path argsFile, MemoryOverrides memoryOverrides) {
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile);
        }
        return buildJvmCommand(argsFile, memoryOverrides);
    }

    /**
     * Check if native executable mode should be used.
     */
    private boolean shouldUseNativeExecutableMode() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.shouldUseNativeExecutableMode();
        }
        if (NativeImageInfo.isRunningInNativeImage() && !NativeImageInfo.hasClasspath()) {
            return true;
        }
        return false;
    }

    /**
     * Build command for native executable mode.
     */
    public List<String> buildNativeCommand(Path argsFile) {
        if (subprocessConfigService == null) {
            throw new IllegalStateException(
                    "Native executable mode required but SubprocessConfigService not available.");
        }

        String executablePath = subprocessConfigService.getExecutablePathForType("vector-population");
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                    "Native executable mode required but no executable path configured. " +
                    "Configure the native executable path in Processing Settings (Developer Hub).");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);

        if (subprocessConfigService.useUnifiedExecutable("vector-population")) {
            command.add(subprocessConfigService.getSubprocessTypeFlag() + "vector-population");
        }

        command.add(argsFile.toString());

        logger.info("Using native executable mode for vector population subprocess: {}", executablePath);
        return command;
    }

    /**
     * Build command for JVM classpath mode.
     */
    public List<String> buildJvmCommand(Path argsFile, MemoryOverrides memoryOverrides) {
        List<String> command = new ArrayList<>();

        command.add(getEffectiveJavaPath());

        String effectiveHeapSize;
        if (memoryOverrides != null && memoryOverrides.heapSize() != null && !memoryOverrides.heapSize().isBlank()) {
            effectiveHeapSize = memoryOverrides.heapSize();
            logger.info("RESTART: Using heap size override: {} (was: {})", effectiveHeapSize, getEffectiveHeapSize());
        } else {
            effectiveHeapSize = getEffectiveHeapSize();
        }

        String heapSizeArg = toXmxArg(effectiveHeapSize);
        if (heapSizeArg != null) {
            command.add(heapSizeArg);
        }
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");

        Long offHeapBytes;
        if (memoryOverrides != null && memoryOverrides.offHeapBytes() != null && memoryOverrides.offHeapBytes() > 0) {
            offHeapBytes = memoryOverrides.offHeapBytes();
            logger.info("RESTART: Using off-heap override: {} (org.bytedeco.javacpp.maxbytes)",
                    SystemMemoryAnalyzer.formatBytes(offHeapBytes));
        } else {
            Long heapBytes = parseMemoryToBytes(effectiveHeapSize);
            int multiplier = subprocessConfigService != null ? subprocessConfigService.getOffHeapMultiplier() : 2;
            offHeapBytes = (heapBytes != null) ? heapBytes * multiplier : null;
        }

        if (offHeapBytes != null && offHeapBytes > 0) {
            command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
            command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + offHeapBytes);
            logger.debug("Set JavaCPP off-heap limits: maxbytes={}, maxphysicalbytes={}",
                    SystemMemoryAnalyzer.formatBytes(offHeapBytes), SystemMemoryAnalyzer.formatBytes(offHeapBytes));
        }

        String classpath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classpath);

        command.add(SUBPROCESS_MAIN_CLASS);
        command.add(argsFile.toString());

        return command;
    }

    public String getEffectiveJavaPath() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getJavaPath();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return fallbackJavaPath;
    }

    public String getEffectiveHeapSize() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getHeapSize();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return fallbackHeapSize;
    }

    public Long getEffectiveOffHeapMaxBytes() {
        String configured = null;
        if (subprocessConfigService != null) {
            configured = subprocessConfigService.getOffHeapMaxBytes();
        }
        Long configuredBytes = parseMemoryToBytes(configured);
        if (configuredBytes != null) {
            return configuredBytes;
        }

        Long heapBytes = parseMemoryToBytes(getEffectiveHeapSize());
        if (heapBytes == null) {
            return null;
        }
        try {
            int multiplier = subprocessConfigService != null ? subprocessConfigService.getOffHeapMultiplier() : 2;
            return Math.multiplyExact(heapBytes, (long) multiplier);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    public static String toXmxArg(String heapSize) {
        if (heapSize == null) {
            return null;
        }
        String trimmed = heapSize.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("-Xmx")) {
            return trimmed;
        }
        return "-Xmx" + trimmed;
    }

    public static Long parseMemoryToBytes(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return null;
        }

        s = s.replace("_", "").replace(",", "");
        Matcher matcher = MEMORY_SIZE_PATTERN.matcher(s);
        if (!matcher.matches()) {
            return null;
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }

        String unitRaw = matcher.group(2) != null ? matcher.group(2).trim() : "";
        String unit = unitRaw.toLowerCase();

        if (unit.isEmpty() && amount > 0 && amount <= 1024) {
            unit = "g";
        }
        long multiplier = switch (unit) {
            case "", "b" -> 1L;
            case "k", "kb" -> 1024L;
            case "m", "mb" -> 1024L * 1024;
            case "g", "gb" -> 1024L * 1024 * 1024;
            case "t", "tb" -> 1024L * 1024 * 1024 * 1024;
            default -> -1L;
        };
        if (multiplier < 0) {
            return null;
        }

        try {
            return Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Propagate ND4J-related environment variables to subprocess.
     * Sets OMP_NUM_THREADS to match ND4J's maxThreads setting.
     *
     * @param env            The environment map to populate
     * @param threadOverrides Optional thread overrides from restart config.
     */
    public void propagateNd4jEnvironment(Map<String, String> env, ThreadOverrides threadOverrides) {
        // Use central propagator for all ND4J/CUDA/Triton/DSP env vars
        SubprocessEnvironmentPropagator.propagateToEnvironment(env);

        // Apply thread overrides on top if specified
        if (threadOverrides != null && threadOverrides.hasOverrides()) {
            if (threadOverrides.ompNumThreads() != null) {
                env.put("OMP_NUM_THREADS", String.valueOf(threadOverrides.ompNumThreads()));
                logger.info("RESTART: Setting OMP_NUM_THREADS={} (override from restart config)", threadOverrides.ompNumThreads());
            }
            if (threadOverrides.openBlasNumThreads() != null) {
                env.put("OPENBLAS_NUM_THREADS", String.valueOf(threadOverrides.openBlasNumThreads()));
                logger.info("RESTART: Setting OPENBLAS_NUM_THREADS={} (override from restart config)", threadOverrides.openBlasNumThreads());
            }
            if (threadOverrides.mklNumThreads() != null) {
                env.put("MKL_NUM_THREADS", String.valueOf(threadOverrides.mklNumThreads()));
                logger.info("RESTART: Setting MKL_NUM_THREADS={} (override from restart config)", threadOverrides.mklNumThreads());
            }
        } else if (!env.containsKey("OMP_NUM_THREADS")) {
            // Fall back to ND4J maxThreads if nothing was set
            try {
                int maxThreads = (int) org.nd4j.linalg.factory.Nd4j.getEnvironment().maxThreads();
                if (maxThreads > 0) {
                    env.put("OMP_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("MKL_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("OPENBLAS_NUM_THREADS", String.valueOf(maxThreads));
                    logger.info("Set OMP_NUM_THREADS={} from ND4J maxThreads", maxThreads);
                }
            } catch (Exception e) {
                logger.debug("Could not get ND4J maxThreads: {}", e.getMessage());
            }
        }
    }
}
