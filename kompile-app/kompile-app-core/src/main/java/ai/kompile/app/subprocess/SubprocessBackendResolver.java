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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared utility for resolving and augmenting the ND4J backend classpath for subprocesses.
 *
 * <p>When a subprocess is configured for CUDA (via device routing), this class handles:
 * <ul>
 *   <li>Finding CUDA backend JARs in the Maven local repository</li>
 *   <li>Removing conflicting CPU backend JARs from the classpath</li>
 *   <li>Adding the CUDA JARs to the classpath</li>
 *   <li>Logging exactly what was done in an unambiguous format</li>
 * </ul>
 *
 * <p>Used by both {@code ServingSubprocessLauncher} and {@code EmbeddingSubprocessLauncher}
 * to ensure consistent backend resolution across all subprocess types.</p>
 */
public final class SubprocessBackendResolver {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessBackendResolver.class);

    private SubprocessBackendResolver() {}

    /** Registry of the most recent backend resolution per subprocess type. Thread-safe. */
    private static final ConcurrentHashMap<String, BackendResolution> resolutionRegistry = new ConcurrentHashMap<>();

    /**
     * Get the most recent backend resolution for a given subprocess type.
     * @return the resolution, or null if this subprocess type hasn't been resolved yet
     */
    public static BackendResolution getLastResolution(String subprocessType) {
        return resolutionRegistry.get(subprocessType);
    }

    /**
     * Get all backend resolutions across all subprocess types.
     * @return unmodifiable map of subprocess type → resolution
     */
    public static Map<String, BackendResolution> getAllResolutions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolutionRegistry));
    }

    /**
     * Result of a backend resolution attempt. Contains all the information needed
     * to understand exactly what happened — no ambiguity.
     */
    public static final class BackendResolution {
        private final String resolvedBackend; // "CUDA" or "CPU"
        private final String subprocessType;  // e.g., "LLM_SERVING", "EMBEDDING"
        private final List<String> cudaJarsAdded;
        private final List<String> cpuEntriesRemoved;
        private final boolean cudaRequested;
        private final String reason; // why this resolution was made
        private final Instant resolvedAt;

        private BackendResolution(String resolvedBackend, String subprocessType,
                                  List<String> cudaJarsAdded, List<String> cpuEntriesRemoved,
                                  boolean cudaRequested, String reason) {
            this.resolvedBackend = resolvedBackend;
            this.subprocessType = subprocessType;
            this.cudaJarsAdded = cudaJarsAdded;
            this.cpuEntriesRemoved = cpuEntriesRemoved;
            this.cudaRequested = cudaRequested;
            this.reason = reason;
            this.resolvedAt = Instant.now();
        }

        public String getResolvedBackend() { return resolvedBackend; }
        public String getSubprocessType() { return subprocessType; }
        public List<String> getCudaJarsAdded() { return cudaJarsAdded; }
        public List<String> getCpuEntriesRemoved() { return cpuEntriesRemoved; }
        public boolean isCudaRequested() { return cudaRequested; }
        public String getReason() { return reason; }
        public boolean isCuda() { return "CUDA".equals(resolvedBackend); }
        public Instant getResolvedAt() { return resolvedAt; }

        /**
         * Convert to a serializable map for REST API responses.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("subprocessType", subprocessType);
            map.put("resolvedBackend", resolvedBackend);
            map.put("cudaRequested", cudaRequested);
            map.put("cudaJarsAdded", cudaJarsAdded);
            map.put("cpuEntriesRemoved", cpuEntriesRemoved);
            map.put("reason", reason);
            map.put("resolvedAt", resolvedAt.toString());
            return map;
        }

        /**
         * Log this resolution as an unambiguous banner that makes it impossible
         * to confuse which backend a subprocess is using.
         */
        public void logBanner() {
            // Register this resolution so it can be queried via REST API
            resolutionRegistry.put(subprocessType, this);

            String banner = String.format(
                    "\n" +
                    "╔══════════════════════════════════════════════════════════════╗\n" +
                    "║  SUBPROCESS BACKEND RESOLUTION                             ║\n" +
                    "╠══════════════════════════════════════════════════════════════╣\n" +
                    "║  Subprocess Type : %-40s ║\n" +
                    "║  Resolved Backend: %-40s ║\n" +
                    "║  CUDA Requested  : %-40s ║\n" +
                    "║  CUDA JARs Added : %-40s ║\n" +
                    "║  CPU Entries Removed: %-37s ║\n" +
                    "║  Reason          : %-40s ║\n" +
                    "╚══════════════════════════════════════════════════════════════╝",
                    subprocessType,
                    resolvedBackend,
                    cudaRequested ? "YES" : "NO",
                    String.valueOf(cudaJarsAdded.size()),
                    String.valueOf(cpuEntriesRemoved.size()),
                    reason
            );
            logger.info(banner);

            if (!cudaJarsAdded.isEmpty()) {
                for (String jar : cudaJarsAdded) {
                    logger.info("[BACKEND] +CUDA JAR: {}", jar);
                }
            }
            if (!cpuEntriesRemoved.isEmpty()) {
                for (String entry : cpuEntriesRemoved) {
                    logger.info("[BACKEND] -CPU entry: {}", entry);
                }
            }
        }
    }

    /**
     * Augment the classpath entries for CUDA backend if requested.
     *
     * @param entries      mutable set of classpath entries to modify in place
     * @param needsCuda    whether CUDA backend is requested for this subprocess
     * @param subprocessType human-readable subprocess type for logging (e.g., "LLM_SERVING", "EMBEDDING")
     * @return resolution result describing exactly what was done
     */
    public static BackendResolution augmentClasspathForBackend(Set<String> entries, boolean needsCuda, String subprocessType) {
        if (!needsCuda) {
            BackendResolution result = new BackendResolution(
                    "CPU", subprocessType, List.of(), List.of(), false,
                    "CUDA not requested via device routing");
            result.logBanner();
            return result;
        }

        // Check if CUDA is already on the classpath
        boolean hasCuda = entries.stream().anyMatch(e -> e.contains("nd4j-cuda"));
        if (hasCuda) {
            BackendResolution result = new BackendResolution(
                    "CUDA", subprocessType, List.of(), List.of(), true,
                    "CUDA JARs already present on classpath");
            result.logBanner();
            return result;
        }

        // Look for CUDA JARs in the Maven local repository
        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path cudaDir = m2Repo.resolve("org/eclipse/deeplearning4j/nd4j-cuda-12.9/1.0.0-SNAPSHOT");
        Path cudaPresetDir = m2Repo.resolve("org/eclipse/deeplearning4j/nd4j-cuda-12.9-preset/1.0.0-SNAPSHOT");

        if (!Files.isDirectory(cudaDir)) {
            BackendResolution result = new BackendResolution(
                    "CPU", subprocessType, List.of(), List.of(), true,
                    "CUDA requested but nd4j-cuda-12.9 not found at " + cudaDir);
            result.logBanner();
            logger.warn("[BACKEND] CUDA device routing configured for {} but nd4j-cuda-12.9 not found in Maven repo at {}. "
                    + "Build with CUDA backend first: mvn install -pl :nd4j-cuda-12.9", subprocessType, cudaDir);
            return result;
        }

        // Detect platform
        String platform = detectPlatform();
        List<Path> cudaJars = new ArrayList<>();

        // Add nd4j-cuda-12.9 base + platform JARs
        addJarIfExists(cudaJars, cudaDir, "nd4j-cuda-12.9-1.0.0-SNAPSHOT.jar");
        addJarIfExists(cudaJars, cudaDir, "nd4j-cuda-12.9-1.0.0-SNAPSHOT-" + platform + ".jar");

        // Add nd4j-cuda-12.9-preset base + platform JARs
        if (Files.isDirectory(cudaPresetDir)) {
            addJarIfExists(cudaJars, cudaPresetDir, "nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT.jar");
            addJarIfExists(cudaJars, cudaPresetDir, "nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT-" + platform + ".jar");
        }

        // Add nd4j-presets-common (transitive dep of nd4j-cuda-12.9-preset)
        Path presetsCommonDir = m2Repo.resolve("org/eclipse/deeplearning4j/nd4j-presets-common/1.0.0-SNAPSHOT");
        if (Files.isDirectory(presetsCommonDir)) {
            addJarIfExists(cudaJars, presetsCommonDir, "nd4j-presets-common-1.0.0-SNAPSHOT.jar");
        }

        // Add JavaCPP platform-specific JAR if not already on classpath
        boolean hasJavacppPlatform = entries.stream().anyMatch(e ->
                e.contains("javacpp") && e.contains(platform) && !e.contains("cuda"));
        if (!hasJavacppPlatform) {
            Path javacppDir = m2Repo.resolve("org/bytedeco/javacpp/1.5.12");
            if (Files.isDirectory(javacppDir)) {
                addJarIfExists(cudaJars, javacppDir, "javacpp-1.5.12-" + platform + ".jar");
            }
        }

        // Add JavaCPP CUDA runtime bindings (org.bytedeco:cuda)
        Path bytedecoCudaDir = m2Repo.resolve("org/bytedeco/cuda/12.9-9.10-1.5.12");
        if (Files.isDirectory(bytedecoCudaDir)) {
            addJarIfExists(cudaJars, bytedecoCudaDir, "cuda-12.9-9.10-1.5.12.jar");
            addJarIfExists(cudaJars, bytedecoCudaDir, "cuda-12.9-9.10-1.5.12-" + platform + ".jar");
        } else {
            // Try to find any CUDA 12.x bindings
            Path bytedecoCudaParent = m2Repo.resolve("org/bytedeco/cuda");
            if (Files.isDirectory(bytedecoCudaParent)) {
                try (var dirs = Files.list(bytedecoCudaParent)) {
                    dirs.filter(p -> p.getFileName().toString().startsWith("12."))
                            .sorted(Comparator.reverseOrder())
                            .findFirst()
                            .ifPresent(dir -> {
                                String version = dir.getFileName().toString();
                                addJarIfExists(cudaJars, dir, "cuda-" + version + ".jar");
                                addJarIfExists(cudaJars, dir, "cuda-" + version + "-" + platform + ".jar");
                                logger.info("[BACKEND] Using CUDA bindings version: {}", version);
                            });
                } catch (IOException e) {
                    logger.debug("[BACKEND] Could not scan bytedeco CUDA bindings: {}", e.getMessage());
                }
            }
        }

        if (cudaJars.isEmpty()) {
            BackendResolution result = new BackendResolution(
                    "CPU", subprocessType, List.of(), List.of(), true,
                    "CUDA requested but no CUDA JARs found in " + cudaDir);
            result.logBanner();
            logger.warn("[BACKEND] CUDA device routing configured for {} but no CUDA JARs found in {}", subprocessType, cudaDir);
            return result;
        }

        // Remove nd4j-native (CPU backend) entries to avoid backend conflicts.
        // IMPORTANT: Keep nd4j-native-api — it's the shared JNI/native bridge used by BOTH backends.
        List<String> nativeEntries = entries.stream()
                .filter(e -> (e.contains("nd4j-native") && !e.contains("nd4j-native-api"))
                        || e.contains("nd4j-cpu-backend-common"))
                .toList();
        List<String> removedEntries = new ArrayList<>(nativeEntries);
        if (!nativeEntries.isEmpty()) {
            entries.removeAll(new LinkedHashSet<>(nativeEntries));
        }

        // Add CUDA JARs
        List<String> addedJarNames = new ArrayList<>();
        for (Path jar : cudaJars) {
            entries.add(jar.toString());
            addedJarNames.add(jar.getFileName().toString());
        }

        BackendResolution result = new BackendResolution(
                "CUDA", subprocessType, addedJarNames, removedEntries, true,
                "CUDA JARs found and added from Maven local repo");
        result.logBanner();
        return result;
    }

    /**
     * Log the actual ND4J backend that was loaded at runtime. Call this AFTER Nd4j initialization
     * (e.g., after Nd4j.scalar(0.0f)) to produce an unmistakable banner showing exactly which
     * backend the subprocess is actually using.
     *
     * @param subprocessType human-readable subprocess type (e.g., "EMBEDDING", "LLM_SERVING")
     * @param backendClassName the simple class name of the loaded backend (e.g., "CpuBackend", "CudaBackend")
     */
    public static void logRuntimeBackendBanner(String subprocessType, String backendClassName) {
        String normalizedBackend = backendClassName != null ? backendClassName.toLowerCase(Locale.ROOT) : "";
        boolean isCuda = normalizedBackend.contains("cuda")
                || normalizedBackend.contains("gpu")
                || normalizedBackend.contains("jcublas")
                || normalizedBackend.contains("cublas");
        String backendLabel = isCuda ? "CUDA (GPU)" : "CPU";
        String banner = String.format(
                "\n" +
                "╔══════════════════════════════════════════════════════════════╗\n" +
                "║  SUBPROCESS ND4J BACKEND - RUNTIME VERIFICATION             ║\n" +
                "╠══════════════════════════════════════════════════════════════╣\n" +
                "║  Subprocess Type : %-40s ║\n" +
                "║  Backend Class   : %-40s ║\n" +
                "║  Backend Type    : %-40s ║\n" +
                "║  PID             : %-40s ║\n" +
                "║  Platform        : %-40s ║\n" +
                "╚══════════════════════════════════════════════════════════════╝",
                subprocessType,
                backendClassName != null ? backendClassName : "UNKNOWN",
                backendLabel,
                String.valueOf(ProcessHandle.current().pid()),
                detectPlatform()
        );
        logger.info(banner);
    }

    /**
     * Detect the current platform string for Maven classifier matching.
     */
    public static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "linux-arm64" : "linux-x86_64";
        } else if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "macosx-arm64" : "macosx-x86_64";
        } else if (os.contains("win")) {
            return "windows-x86_64";
        }
        return "linux-x86_64";
    }

    private static void addJarIfExists(List<Path> target, Path dir, String fileName) {
        Path jar = dir.resolve(fileName);
        if (Files.exists(jar) && Files.isRegularFile(jar)) {
            target.add(jar);
        }
    }
}
