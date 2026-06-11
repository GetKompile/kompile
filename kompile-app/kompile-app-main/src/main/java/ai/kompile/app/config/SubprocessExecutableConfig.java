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

package ai.kompile.app.config;

import ai.kompile.cli.common.util.NativeImageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for subprocess executable paths.
 *
 * <p>This configuration manages how subprocesses are launched, supporting both:</p>
 * <ul>
 *   <li><b>JVM mode</b>: Subprocesses are launched using {@code java -cp <classpath> <MainClass>}</li>
 *   <li><b>Native image mode</b>: Subprocesses are launched using the native executable with flags</li>
 * </ul>
 *
 * <h3>Configuration Properties</h3>
 * <pre>
 * kompile.subprocess.executable.mode=auto|jvm|native
 * kompile.subprocess.executable.native-path=/path/to/kompile-app
 * kompile.subprocess.executable.ingest-path=/path/to/kompile-ingest-subprocess
 * kompile.subprocess.executable.vector-population-path=/path/to/kompile-vector-subprocess
 * kompile.subprocess.executable.embedding-path=/path/to/kompile-embedding-subprocess
 * kompile.subprocess.executable.model-init-path=/path/to/kompile-model-init-subprocess
 * </pre>
 *
 * <h3>Mode Selection</h3>
 * <ul>
 *   <li><b>auto</b> (default): Detects whether running in native image and chooses appropriately</li>
 *   <li><b>jvm</b>: Always use JVM classpath mode (requires java.class.path)</li>
 *   <li><b>native</b>: Always use native executable mode</li>
 * </ul>
 *
 * <h3>Native Image Subprocess Launching</h3>
 * <p>When in native mode, subprocesses can be launched in two ways:</p>
 * <ol>
 *   <li><b>Unified executable with subprocess type</b>: {@code /path/to/kompile-app --subprocess=ingest <args>}</li>
 *   <li><b>Separate executables</b>: Each subprocess type has its own native image</li>
 * </ol>
 *
 * <h3>Example: Using Unified Native Executable</h3>
 * <pre>
 * kompile.subprocess.executable.mode=native
 * kompile.subprocess.executable.native-path=/opt/kompile/kompile-app
 * </pre>
 *
 * <h3>Example: Using Separate Native Executables</h3>
 * <pre>
 * kompile.subprocess.executable.mode=native
 * kompile.subprocess.executable.ingest-path=/opt/kompile/kompile-ingest
 * kompile.subprocess.executable.vector-population-path=/opt/kompile/kompile-vector
 * kompile.subprocess.executable.embedding-path=/opt/kompile/kompile-embedding
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "kompile.subprocess.executable")
public class SubprocessExecutableConfig {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessExecutableConfig.class);

    /**
     * Subprocess launch mode: auto, jvm, or native.
     */
    @Value("${kompile.subprocess.executable.mode:auto}")
    private String mode;

    /**
     * Path to the unified native executable.
     * Used when launching subprocesses with --subprocess=TYPE flag.
     */
    @Value("${kompile.subprocess.executable.native-path:}")
    private String nativePath;

    /**
     * Path to the ingest subprocess executable (optional).
     * If not set and mode=native, falls back to unified executable with --subprocess=ingest.
     */
    @Value("${kompile.subprocess.executable.ingest-path:}")
    private String ingestPath;

    /**
     * Path to the vector population subprocess executable (optional).
     */
    @Value("${kompile.subprocess.executable.vector-population-path:}")
    private String vectorPopulationPath;

    /**
     * Path to the embedding subprocess executable (optional).
     */
    @Value("${kompile.subprocess.executable.embedding-path:}")
    private String embeddingPath;

    /**
     * Path to the model init subprocess executable (optional).
     */
    @Value("${kompile.subprocess.executable.model-init-path:}")
    private String modelInitPath;

    /**
     * Path to the training subprocess executable (optional).
     */
    @Value("${kompile.subprocess.executable.training-path:}")
    private String trainingPath;

    /**
     * Path to the VLM test subprocess executable (optional).
     */
    @Value("${kompile.subprocess.executable.vlm-test-path:}")
    private String vlmTestPath;

    /**
     * Path to the CUDA-backend native executable (for backend-routed subprocess launching).
     * When a subprocess requires CUDA (per DeviceRoutingConfig), this executable is used instead.
     * If not set, falls back to classpath augmentation via SubprocessBackendResolver.
     */
    @Value("${kompile.subprocess.executable.cuda-path:}")
    private String cudaExecutablePath;

    /**
     * Path to the CPU-backend native executable (for backend-routed subprocess launching).
     * When a subprocess is routed to CPU, this executable is used.
     * If not set, uses the default native-path or classpath mode.
     */
    @Value("${kompile.subprocess.executable.cpu-path:}")
    private String cpuExecutablePath;

    /**
     * Path to the CUDA-backend fat JAR (for JVM mode backend routing).
     * When a subprocess requires CUDA in JVM mode, this JAR's classpath is used.
     * Typically: target/myapp-cuda.jar or ~/.kompile/lib/kompile-app-cuda.jar
     */
    @Value("${kompile.subprocess.executable.cuda-jar-path:}")
    private String cudaJarPath;

    /**
     * Path to the CPU-backend fat JAR (for JVM mode backend routing).
     * When a subprocess is routed to CPU in JVM mode, this JAR's classpath is used.
     */
    @Value("${kompile.subprocess.executable.cpu-jar-path:}")
    private String cpuJarPath;

    /**
     * Subprocess type flag prefix for unified executable mode.
     * Default: "--subprocess="
     */
    @Value("${kompile.subprocess.executable.type-flag:--subprocess=}")
    private String subprocessTypeFlag;

    private LaunchMode resolvedLaunchMode;
    private String resolvedNativePath;

    @PostConstruct
    public void init() {
        resolveLaunchMode();
        logConfiguration();
    }

    private void resolveLaunchMode() {
        switch (mode.toLowerCase()) {
            case "jvm":
                resolvedLaunchMode = LaunchMode.JVM_CLASSPATH;
                break;
            case "native":
                resolvedLaunchMode = LaunchMode.NATIVE_EXECUTABLE;
                break;
            case "auto":
            default:
                // Auto-detect based on runtime context
                NativeImageInfo.SubprocessLaunchMode recommended = NativeImageInfo.getRecommendedLaunchMode();
                resolvedLaunchMode = switch (recommended) {
                    case JVM_CLASSPATH -> LaunchMode.JVM_CLASSPATH;
                    case NATIVE_EXECUTABLE -> LaunchMode.NATIVE_EXECUTABLE;
                };
                break;
        }

        // Resolve native path
        if (resolvedLaunchMode == LaunchMode.NATIVE_EXECUTABLE) {
            resolvedNativePath = resolveNativePath();
        }
    }

    private String resolveNativePath() {
        // Priority 1: Explicit configuration
        if (nativePath != null && !nativePath.isBlank()) {
            Path path = Paths.get(nativePath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            logger.warn("Configured native-path does not exist or is not executable: {}", nativePath);
        }

        // Priority 2: Detect from current native image
        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            if (execPath != null) {
                return execPath;
            }
        }

        // Priority 3: Look in standard locations
        List<String> searchPaths = new ArrayList<>(List.of(
            System.getProperty("user.home") + "/.kompile/bin/kompile-app",
            "/opt/kompile/kompile-app",
            "/usr/local/bin/kompile-app",
            "./kompile-app"
        ));

        // Also search relative to working directory for development builds
        String userDir = System.getProperty("user.dir", ".");
        searchPaths.add(userDir + "/target/kompile-app");
        searchPaths.add(userDir + "/kompile-app/kompile-app-main/target/kompile-app");
        searchPaths.add(userDir + "/kompile-app-main/target/kompile-app");

        for (String searchPath : searchPaths) {
            try {
                Path path = Paths.get(searchPath);
                if (Files.exists(path) && Files.isExecutable(path)) {
                    logger.info("Found native executable at: {}", path.toAbsolutePath());
                    return path.toAbsolutePath().toString();
                }
            } catch (Exception e) {
                // Skip invalid paths
            }
        }

        logger.warn("No native executable found. Subprocess launching in native mode may fail.");
        logger.warn("Searched: {}", searchPaths);
        return null;
    }

    private void logConfiguration() {
        logger.info("Subprocess executable configuration:");
        logger.info("  Mode: {} (configured: {})", resolvedLaunchMode, mode);
        logger.info("  Running in native image: {}", NativeImageInfo.isRunningInNativeImage());
        logger.info("  Has classpath: {}", NativeImageInfo.hasClasspath());

        if (resolvedLaunchMode == LaunchMode.NATIVE_EXECUTABLE) {
            logger.info("  Native executable path: {}", resolvedNativePath);
            if (ingestPath != null && !ingestPath.isBlank()) {
                logger.info("  Ingest subprocess path: {}", ingestPath);
            }
            if (vectorPopulationPath != null && !vectorPopulationPath.isBlank()) {
                logger.info("  Vector population subprocess path: {}", vectorPopulationPath);
            }
            if (embeddingPath != null && !embeddingPath.isBlank()) {
                logger.info("  Embedding subprocess path: {}", embeddingPath);
            }
            if (modelInitPath != null && !modelInitPath.isBlank()) {
                logger.info("  Model init subprocess path: {}", modelInitPath);
            }
            if (trainingPath != null && !trainingPath.isBlank()) {
                logger.info("  Training subprocess path: {}", trainingPath);
            }
        }
    }

    /**
     * Get the resolved launch mode.
     */
    public LaunchMode getLaunchMode() {
        return resolvedLaunchMode;
    }

    /**
     * Check if using native executable mode.
     */
    public boolean isNativeMode() {
        return resolvedLaunchMode == LaunchMode.NATIVE_EXECUTABLE;
    }

    /**
     * Check if using JVM classpath mode.
     */
    public boolean isJvmMode() {
        return resolvedLaunchMode == LaunchMode.JVM_CLASSPATH;
    }

    /**
     * Build the command for launching an ingest subprocess.
     *
     * @param argsFile Path to the subprocess arguments file
     * @param heapSize Heap size for JVM mode (ignored in native mode)
     * @param javaPath Java executable path for JVM mode (ignored in native mode)
     * @param classpath Classpath for JVM mode (ignored in native mode)
     * @return List of command arguments
     */
    public List<String> buildIngestCommand(Path argsFile, String heapSize, String javaPath, String classpath) {
        return buildSubprocessCommand(SubprocessType.INGEST, argsFile, heapSize, javaPath, classpath,
                "ai.kompile.app.subprocess.IngestSubprocessMain");
    }

    /**
     * Build the command for launching a vector population subprocess.
     */
    public List<String> buildVectorPopulationCommand(Path argsFile, String heapSize, String javaPath, String classpath) {
        return buildSubprocessCommand(SubprocessType.VECTOR_POPULATION, argsFile, heapSize, javaPath, classpath,
                "ai.kompile.app.subprocess.VectorPopulationSubprocessMain");
    }

    /**
     * Build the command for launching an embedding subprocess.
     */
    public List<String> buildEmbeddingCommand(String heapSize, String javaPath, String classpath) {
        List<String> command = new ArrayList<>();

        if (isNativeMode()) {
            String execPath = getExecutablePathForType(SubprocessType.EMBEDDING);
            if (execPath == null) {
                throw new IllegalStateException("Native mode enabled but no executable path configured for embedding subprocess");
            }
            command.add(execPath);
            if (useUnifiedExecutable(SubprocessType.EMBEDDING)) {
                command.add(subprocessTypeFlag + "embedding");
            }
        } else {
            // JVM classpath mode
            command.add(javaPath);
            command.add("-Xmx" + heapSize);
            command.add("-Xms" + Math.min(parseHeapMb(heapSize) / 2, 1024) + "m");
            command.add("-XX:+UseG1GC");
            command.add("-XX:MaxGCPauseMillis=100");
            command.add("-cp");
            command.add(classpath);
            command.add("ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain");
        }

        return command;
    }

    /**
     * Build the command for launching a model init subprocess.
     */
    public List<String> buildModelInitCommand(Path argsFile, String heapSize, String javaPath, String classpath) {
        return buildSubprocessCommand(SubprocessType.MODEL_INIT, argsFile, heapSize, javaPath, classpath,
                "ai.kompile.app.subprocess.model.ModelInitSubprocessMain");
    }

    /**
     * Build the command for launching a training subprocess.
     */
    public List<String> buildTrainingCommand(Path argsFile, String heapSize, String javaPath, String classpath) {
        return buildSubprocessCommand(SubprocessType.TRAINING, argsFile, heapSize, javaPath, classpath,
                "ai.kompile.staging.subprocess.TrainingSubprocessMain");
    }

    /**
     * Build the command for launching a VLM test subprocess.
     */
    public List<String> buildVlmTestCommand(Path argsFile, String heapSize, String javaPath, String classpath) {
        return buildVlmTestCommand(argsFile, heapSize, javaPath, classpath, 2);
    }

    /**
     * Build the command for launching a VLM test subprocess with configurable off-heap multiplier.
     * @param offHeapMultiplier Multiplier for off-heap memory relative to heap size (default 2)
     */
    public List<String> buildVlmTestCommand(Path argsFile, String heapSize, String javaPath, String classpath, int offHeapMultiplier) {
        return buildSubprocessCommand(SubprocessType.VLM_TEST, argsFile, heapSize, javaPath, classpath,
                "ai.kompile.app.subprocess.VlmTestSubprocessMain", offHeapMultiplier);
    }

    private List<String> buildSubprocessCommand(SubprocessType type, Path argsFile, String heapSize,
                                                 String javaPath, String classpath, String mainClass) {
        return buildSubprocessCommand(type, argsFile, heapSize, javaPath, classpath, mainClass, 2);
    }

    private List<String> buildSubprocessCommand(SubprocessType type, Path argsFile, String heapSize,
                                                 String javaPath, String classpath, String mainClass, int offHeapMultiplier) {
        List<String> command = new ArrayList<>();

        if (isNativeMode()) {
            String execPath = getExecutablePathForType(type);
            if (execPath == null) {
                throw new IllegalStateException("Native mode enabled but no executable path configured for " + type);
            }
            command.add(execPath);
            if (useUnifiedExecutable(type)) {
                command.add(subprocessTypeFlag + type.name().toLowerCase().replace("_", "-"));
            }
            command.add(argsFile.toString());
        } else {
            // JVM classpath mode
            command.add(javaPath);
            command.add("-Xmx" + heapSize);

            // Additional JVM options based on subprocess type
            int heapMb = parseHeapMb(heapSize);
            command.add("-Xms" + Math.min(heapMb / 2, 1024) + "m");
            command.add("-XX:+UseG1GC");
            command.add("-XX:MaxGCPauseMillis=200");
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dorg.bytedeco.javacpp.pathsFirst=true");
            command.add("-Dorg.bytedeco.javacpp.logger.debug=false");
            // Disable JavaCPP pointer GC — critical for ND4J CUDA performance.
            // Without this, JavaCPP's deallocator thread creates massive GC pressure
            // (1M+ collection events observed). ND4J manages its own memory via pools.
            command.add("-Dorg.bytedeco.javacpp.nopointergc=true");

            // DSP performance flags (cublasTf32, batchedGemm, tritonTf32, etc.) are applied
            // natively via Nd4j.getEnvironment().applyOptimalLLMConfig() inside the subprocess
            // after ND4J loads. JVM system properties do NOT reach the C++ native layer.

            // Off-heap memory (JavaCPP) - includes pinned host memory shared with VRAM
            long offHeapBytes = (long) heapMb * 1024 * 1024 * offHeapMultiplier;
            command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
            command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + offHeapBytes);

            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);
            command.add(argsFile.toString());
        }

        return command;
    }

    private String getExecutablePathForType(SubprocessType type) {
        String specificPath = switch (type) {
            case INGEST -> ingestPath;
            case VECTOR_POPULATION -> vectorPopulationPath;
            case EMBEDDING -> embeddingPath;
            case MODEL_INIT -> modelInitPath;
            case TRAINING -> trainingPath;
            case VLM_TEST -> vlmTestPath;
        };

        if (specificPath != null && !specificPath.isBlank()) {
            Path path = Paths.get(specificPath);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
            logger.warn("Specific subprocess path for {} does not exist: {}", type, specificPath);
        }

        // Fall back to unified executable
        return resolvedNativePath;
    }

    private boolean useUnifiedExecutable(SubprocessType type) {
        String specificPath = switch (type) {
            case INGEST -> ingestPath;
            case VECTOR_POPULATION -> vectorPopulationPath;
            case EMBEDDING -> embeddingPath;
            case MODEL_INIT -> modelInitPath;
            case TRAINING -> trainingPath;
            case VLM_TEST -> vlmTestPath;
        };

        // Use unified executable if no specific path is configured
        return specificPath == null || specificPath.isBlank();
    }

    private int parseHeapMb(String heapSize) {
        if (heapSize == null || heapSize.isBlank()) {
            return 4096;
        }
        String lower = heapSize.toLowerCase().trim();
        try {
            if (lower.endsWith("g")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1)) * 1024;
            } else if (lower.endsWith("m")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1));
            } else {
                return Integer.parseInt(lower);
            }
        } catch (NumberFormatException e) {
            return 4096;
        }
    }

    // Getters and setters for Spring binding

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getNativePath() {
        return nativePath;
    }

    public void setNativePath(String nativePath) {
        this.nativePath = nativePath;
    }

    public String getIngestPath() {
        return ingestPath;
    }

    public void setIngestPath(String ingestPath) {
        this.ingestPath = ingestPath;
    }

    public String getVectorPopulationPath() {
        return vectorPopulationPath;
    }

    public void setVectorPopulationPath(String vectorPopulationPath) {
        this.vectorPopulationPath = vectorPopulationPath;
    }

    public String getEmbeddingPath() {
        return embeddingPath;
    }

    public void setEmbeddingPath(String embeddingPath) {
        this.embeddingPath = embeddingPath;
    }

    public String getModelInitPath() {
        return modelInitPath;
    }

    public void setModelInitPath(String modelInitPath) {
        this.modelInitPath = modelInitPath;
    }

    public String getTrainingPath() {
        return trainingPath;
    }

    public void setTrainingPath(String trainingPath) {
        this.trainingPath = trainingPath;
    }

    public String getVlmTestPath() {
        return vlmTestPath;
    }

    public void setVlmTestPath(String vlmTestPath) {
        this.vlmTestPath = vlmTestPath;
    }

    public String getSubprocessTypeFlag() {
        return subprocessTypeFlag;
    }

    public void setSubprocessTypeFlag(String subprocessTypeFlag) {
        this.subprocessTypeFlag = subprocessTypeFlag;
    }

    // --- Backend-routed subprocess launching ---

    /**
     * Resolve the executable or JAR path for a subprocess that requires a specific backend.
     * Used by subprocess launchers that consult DeviceRoutingConfigService.
     *
     * <p>Resolution order for native mode:</p>
     * <ol>
     *   <li>Backend-specific executable (cudaExecutablePath / cpuExecutablePath)</li>
     *   <li>Subprocess-type-specific executable (e.g., embeddingPath)</li>
     *   <li>Unified native executable (nativePath)</li>
     * </ol>
     *
     * <p>Resolution order for JVM mode:</p>
     * <ol>
     *   <li>Backend-specific JAR (cudaJarPath / cpuJarPath) — used to derive classpath</li>
     *   <li>SubprocessBackendResolver classpath augmentation (fallback)</li>
     * </ol>
     *
     * @param requiresCuda whether the subprocess needs CUDA backend (from DeviceRoutingConfig)
     * @return resolved path, or null if no backend-specific path is configured
     */
    public String resolveBackendExecutablePath(boolean requiresCuda) {
        if (isNativeMode()) {
            String path = requiresCuda ? cudaExecutablePath : cpuExecutablePath;
            if (path != null && !path.isBlank()) {
                Path resolved = Paths.get(path);
                if (Files.exists(resolved) && Files.isExecutable(resolved)) {
                    return resolved.toAbsolutePath().toString();
                }
                logger.warn("Backend executable path configured but not found: {} (requiresCuda={})", path, requiresCuda);
            }
            return null; // Fall back to type-specific or unified executable
        } else {
            // JVM mode: return the JAR path (launcher will extract classpath from it)
            String jarPath = requiresCuda ? cudaJarPath : cpuJarPath;
            if (jarPath != null && !jarPath.isBlank()) {
                Path resolved = Paths.get(jarPath);
                if (Files.exists(resolved)) {
                    return resolved.toAbsolutePath().toString();
                }
                logger.warn("Backend JAR path configured but not found: {} (requiresCuda={})", jarPath, requiresCuda);
            }
            return null; // Fall back to SubprocessBackendResolver classpath augmentation
        }
    }

    /**
     * Check if backend-specific executable/JAR paths are configured.
     * When true, subprocess launchers can use {@link #resolveBackendExecutablePath(boolean)}
     * instead of the legacy SubprocessBackendResolver classpath manipulation.
     */
    public boolean hasBackendPaths() {
        return (cudaExecutablePath != null && !cudaExecutablePath.isBlank())
                || (cpuExecutablePath != null && !cpuExecutablePath.isBlank())
                || (cudaJarPath != null && !cudaJarPath.isBlank())
                || (cpuJarPath != null && !cpuJarPath.isBlank());
    }

    // Getters/setters for backend paths

    public String getCudaExecutablePath() { return cudaExecutablePath; }
    public void setCudaExecutablePath(String v) { this.cudaExecutablePath = v; }
    public String getCpuExecutablePath() { return cpuExecutablePath; }
    public void setCpuExecutablePath(String v) { this.cpuExecutablePath = v; }
    public String getCudaJarPath() { return cudaJarPath; }
    public void setCudaJarPath(String v) { this.cudaJarPath = v; }
    public String getCpuJarPath() { return cpuJarPath; }
    public void setCpuJarPath(String v) { this.cpuJarPath = v; }

    /**
     * Launch mode enumeration.
     */
    public enum LaunchMode {
        JVM_CLASSPATH,
        NATIVE_EXECUTABLE
    }

    /**
     * Subprocess type enumeration.
     */
    public enum SubprocessType {
        INGEST,
        VECTOR_POPULATION,
        EMBEDDING,
        MODEL_INIT,
        TRAINING,
        VLM_TEST
    }
}
