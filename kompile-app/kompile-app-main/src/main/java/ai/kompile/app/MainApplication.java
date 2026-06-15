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

package ai.kompile.app;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.cli.common.util.NativeImageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

import ai.kompile.orchestrator.config.OrchestratorAutoConfiguration;
import ai.kompile.pipeline.management.config.PipelineManagementAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableConfigurationProperties({}) // Keep if other @ConfigurationProperties are used elsewhere
@EnableScheduling
@Import({OrchestratorAutoConfiguration.class, PipelineManagementAutoConfiguration.class})
public class MainApplication {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MainApplication() {}


    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    // Define constants for our custom command-line properties
    public static final String MAX_FILE_SIZE_PROPERTY = "kompile.multipart.max-file-size";
    public static final String MAX_REQUEST_SIZE_PROPERTY = "kompile.multipart.max-request-size";
    // Default values if not provided via command line
    public static final String DEFAULT_MAX_FILE_SIZE = "5000MB"; // Your 5GB default
    public static final String DEFAULT_MAX_REQUEST_SIZE = "5000MB"; // Your 5GB default

    // ND4J environment config constants
    private static final String ND4J_CONFIG_FILENAME = "nd4j-environment-config.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // Disable Lucene MemorySegment-based MMapDirectory — Arena.ofShared is not supported in GraalVM native image
        System.setProperty("org.apache.lucene.store.MMapDirectory.enableMemorySegments", "false");

        // Route to subprocess if --subprocess=TYPE flag is present.
        // This enables the unified native executable approach: a single binary
        // that can act as the main web server OR any subprocess type.
        String subprocessType = extractSubprocessType(args);
        if (subprocessType != null) {
            // Strip the --subprocess= flag from args before forwarding
            String[] forwardArgs = stripSubprocessFlag(args);
            dispatchSubprocess(subprocessType, forwardArgs);
            return; // Subprocess has exited, do not start Spring Boot
        }

        String maxFileSizeArg = DEFAULT_MAX_FILE_SIZE;
        String maxRequestSizeArg = DEFAULT_MAX_REQUEST_SIZE;
        for (String arg : args) {
            if (arg.startsWith("--" + MAX_FILE_SIZE_PROPERTY + "=")) {
                maxFileSizeArg = arg.substring(("--" + MAX_FILE_SIZE_PROPERTY + "=").length());
            } else if (arg.startsWith("--" + MAX_REQUEST_SIZE_PROPERTY + "=")) {
                maxRequestSizeArg = arg.substring(("--" + MAX_REQUEST_SIZE_PROPERTY + "=").length());
            }
        }


        System.setProperty(MAX_FILE_SIZE_PROPERTY, maxFileSizeArg);
        System.setProperty(MAX_REQUEST_SIZE_PROPERTY, maxRequestSizeArg);

        // In GraalVM native images, Log4j2 API's ServiceLoaderUtil uses MethodHandles
        // to invoke ServiceLoader.load() which is not supported. Bypass by directly
        // specifying the SLF4J bridge provider, avoiding ServiceLoader entirely.
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");

        // Resolve native libraries BEFORE any ND4J calls.
        // NativeLibraryResolver handles all modes: native image, JVM, CUDA, CPU.
        // Resolution chain: KOMPILE_NATIVE_LIB_DIR → lib/ → ~/.javacpp/cache → ~/.kompile/native-libs → ~/.m2/repository
        ai.kompile.app.config.NativeLibraryResolver.bootstrap();
        // Also configure JavaCPP paths for native image mode (cachedir, pathsFirst, etc.)
        configureJavaCppForNativeImage();

        // Skip ND4J initialization during Spring AOT processing (no native backend available at build time)
        if (!Boolean.getBoolean("spring.aot.processing")) {
            try {
                Nd4j.getEnvironment().setDebug(true);
                Nd4j.getEnvironment().setVerbose(true);

                DifferentialFunctionClassHolder.initInstance();

                // Use built-in backend discovery - automatically finds CUDA, CPU, or other available backends
                Nd4jBackend backend = Nd4jBackend.load();
                Nd4j.backend = backend;
                logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

                // NativeOps is automatically initialized by backend loading
                NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
                nativeOps.initializeDevicesAndFunctions();

                // CRITICAL: Load and apply persisted ND4J environment configuration BEFORE
                // Spring context starts.
                // This ensures that persisted settings (threads, lifecycle tracking, etc.) are
                // applied
                // before any beans (like embedding models) start using ND4J.
                loadAndApplyPersistedNd4jConfig();
                logger.info("ND4J environment configured: maxThreads={}, maxMasterThreads={}, lifecycleTracking={}",
                        Nd4j.getEnvironment().maxThreads(),
                        Nd4j.getEnvironment().maxMasterThreads(),
                        Nd4j.getEnvironment().isLifecycleTracking());
            } catch (Throwable e) {
                logger.warn("ND4J initialization failed (backend may not be available). " +
                        "Embedding operations will use subprocess mode. Error: {}", e.getMessage());
            }
        } else {
            logger.info("Spring AOT processing mode - skipping ND4J initialization");
        }

        logger.info("Attempting to set Max File Size (from command line or default) to: {}", maxFileSizeArg);
        logger.info("Attempting to set Max Request Size (from command line or default) to: {}", maxRequestSizeArg);

        // NOTE: JavaCPP properties (logger.debug, pathsFirst) are now configured via
        // loadAndApplyPersistedNd4jConfig() above, loaded from persisted ND4J config

        ConfigurableApplicationContext context = SpringApplication.run(MainApplication.class, args);
        logger.info("RAG MCP Assistant (Multi-Module) is running!");

        logger.info("\n--- Final System Properties (includes multipart config if passed) ---");
        Properties systemProperties = System.getProperties();
        for (Map.Entry<Object, Object> entry : systemProperties.entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith("kompile.multipart") || key.startsWith("java.runtime") || key.startsWith("os.name")) { // Filter
                                                                                                                      // for
                                                                                                                      // relevance
                logger.info("{}: {}", key, entry.getValue());
            }
        }
    }

    // ==================== Subprocess Routing ====================

    /**
     * Subprocess type flag prefix. Must match SubprocessExecutableConfig.subprocessTypeFlag.
     */
    private static final String SUBPROCESS_FLAG = "--subprocess=";

    /**
     * Extracts the subprocess type from command-line arguments.
     *
     * @param args command-line arguments
     * @return the subprocess type string (e.g. "ingest", "embedding"), or null if not a subprocess invocation
     */
    private static String extractSubprocessType(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(SUBPROCESS_FLAG)) {
                return arg.substring(SUBPROCESS_FLAG.length()).trim().toLowerCase();
            }
        }
        return null;
    }

    /**
     * Strips the --subprocess= flag from args, returning the remaining arguments
     * to forward to the subprocess main class.
     */
    private static String[] stripSubprocessFlag(String[] args) {
        return java.util.Arrays.stream(args)
                .filter(a -> !a.startsWith(SUBPROCESS_FLAG))
                .toArray(String[]::new);
    }

    /**
     * Dispatches to the appropriate subprocess main class based on the type.
     * This enables the unified native executable approach where a single binary
     * can serve as the main app or any subprocess type.
     *
     * <p>Supported subprocess types:</p>
     * <ul>
     *   <li>{@code ingest} → {@link ai.kompile.app.subprocess.IngestSubprocessMain}</li>
     *   <li>{@code vector-population} → {@link ai.kompile.app.subprocess.VectorPopulationSubprocessMain}</li>
     *   <li>{@code embedding} → {@link ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain}</li>
     *   <li>{@code model-init} → {@link ai.kompile.app.subprocess.model.ModelInitSubprocessMain}</li>
     *   <li>{@code vlm-test} → {@link ai.kompile.app.subprocess.VlmTestSubprocessMain}</li>
     *   <li>{@code training} → {@link ai.kompile.staging.subprocess.TrainingSubprocessMain}</li>
     * </ul>
     *
     * @param type the subprocess type (kebab-case)
     * @param args remaining arguments to forward
     */
    private static void dispatchSubprocess(String type, String[] args) throws Exception {
        // Configure JavaCPP for native image before any ND4J usage in subprocesses
        configureJavaCppForNativeImage();

        // Log4j bypass for native image subprocesses too
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");

        logger.info("Dispatching to subprocess: {} with {} args", type, args.length);

        // Map subprocess type to its main class.
        // Most are direct references; training uses reflection since
        // kompile-model-staging may not be on the classpath.
        switch (type) {
            case "ingest":
                ai.kompile.app.subprocess.IngestSubprocessMain.main(args);
                break;
            case "vector-population":
                ai.kompile.app.subprocess.VectorPopulationSubprocessMain.main(args);
                break;
            case "embedding":
                ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain.main(args);
                break;
            case "model-init":
                ai.kompile.app.subprocess.model.ModelInitSubprocessMain.main(args);
                break;
            case "vlm-test":
                ai.kompile.app.subprocess.VlmTestSubprocessMain.main(args);
                break;
            case "training":
                invokeSubprocessMainByReflection("ai.kompile.staging.subprocess.TrainingSubprocessMain", args);
                break;
            default:
                logger.error("Unknown subprocess type: {}. Supported types: ingest, vector-population, embedding, model-init, vlm-test, training", type);
                System.exit(1);
        }
    }

    /**
     * Invokes a subprocess main class by reflection. Used for subprocess types
     * whose module may not be a compile-time dependency of kompile-app-main.
     */
    private static void invokeSubprocessMainByReflection(String className, String[] args) throws Exception {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (ClassNotFoundException e) {
            logger.error("Subprocess class not available: {}. Ensure the module is on the classpath.", className);
            System.exit(1);
        }
    }

    // ==================== End Subprocess Routing ====================

    /**
     * Load and apply persisted ND4J environment configuration.
     * This is called BEFORE Spring context starts, ensuring all persisted settings
     * are applied before any beans (like embedding models) or SameDiff use ND4J.
     *
     * CRITICAL: ND4J environment setters MUST be called before SameDiff usage.
     * This method ensures that happens by running in main() before Spring context
     * starts.
     *
     * The configuration is loaded from:
     * ~/.kompile/config/nd4j-environment-config.json
     * If no persisted config exists, default values are used AND persisted to disk,
     * allowing users to modify the file for subsequent runs.
     *
     * To manage settings at runtime:
     * - GET /api/nd4j/environment - View current configuration
     * - POST /api/nd4j/environment - Update configuration (persisted to disk)
     * - POST /api/nd4j/environment/preset/{name} - Apply a preset (minimal,
     * balanced, detailed, performance)
     */
    private static void loadAndApplyPersistedNd4jConfig() {
        logger.info("=== Loading Persisted ND4J Environment Configuration ===");
        logger.info("IMPORTANT: ND4J environment must be configured before SameDiff usage");

        // Determine config file path (same logic as Nd4jEnvironmentConfigService)
        String dataDir = System.getProperty("kompile.data.dir",
                System.getProperty("user.home") + "/.kompile");
        Path configFilePath = Paths.get(dataDir, "config", ND4J_CONFIG_FILENAME);

        Nd4jEnvironmentConfig config = Nd4jEnvironmentConfig.defaults();
        boolean needsPersist = false;

        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                logger.info("Found persisted ND4J config at: {} ({} bytes)", configFilePath, json.length());

                Nd4jEnvironmentConfig loaded = OBJECT_MAPPER.readValue(json, Nd4jEnvironmentConfig.class);
                // Merge with defaults to ensure all fields have values
                config = Nd4jEnvironmentConfig.defaults().merge(loaded);
                logger.info("Successfully loaded persisted ND4J environment configuration");
            } catch (IOException e) {
                // CRITICAL: Do NOT set needsPersist = true here!
                // If file exists but reading fails (temporary lock, I/O error, etc.),
                // we should NOT overwrite the user's config with defaults.
                // This was causing the "random reset" bug where transient read failures
                // would permanently destroy user settings.
                logger.error("Failed to load persisted ND4J config from {}: {} - using defaults for THIS SESSION ONLY",
                        configFilePath, e.getMessage());
                logger.warn("The existing config file will NOT be overwritten. Fix the underlying I/O issue.");
                // needsPersist remains false - do not overwrite user's config!
            }
        } else {
            logger.info("No persisted ND4J config found at {} - will create with defaults", configFilePath);
            needsPersist = true;
        }

        // CRITICAL: Apply the configuration BEFORE any SameDiff operations
        applyNd4jEnvironmentConfig(config);

        // Persist config file if it doesn't exist or was corrupt
        // This ensures users always have a file they can modify
        if (needsPersist) {
            persistNd4jConfig(configFilePath, config);
        }

        logger.info("ND4J config file location: {}", configFilePath);
        logger.info("Manage ND4J environment settings via: GET/POST /api/nd4j/environment");
    }

    /**
     * Persist ND4J environment configuration to disk.
     * Creates parent directories if they don't exist.
     * Uses atomic write (write to temp file, then rename) for safety.
     * Creates a backup of existing config before overwriting.
     *
     * @param configFilePath Path to the config file
     * @param config         Configuration to persist
     */
    private static void persistNd4jConfig(Path configFilePath, Nd4jEnvironmentConfig config) {
        try {
            // Ensure parent directory exists
            Path parentDir = configFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                logger.info("Created config directory: {}", parentDir);
            }

            // Create backup of existing config if it exists
            if (Files.exists(configFilePath)) {
                Path backupPath = configFilePath.resolveSibling(
                        configFilePath.getFileName().toString() + ".backup");
                try {
                    Files.copy(configFilePath, backupPath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("Created backup at: {}", backupPath);
                } catch (IOException e) {
                    logger.warn("Failed to create backup of config: {}", e.getMessage());
                    // Continue anyway - backup is best-effort
                }
            }

            // Write config with pretty printing for easy manual editing
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);

            // Atomic write: write to temp file, then rename
            Path tempFile = configFilePath.resolveSibling(
                    configFilePath.getFileName().toString() + ".tmp");
            Files.writeString(tempFile, json);
            Files.move(tempFile, configFilePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            logger.info("Persisted ND4J environment config to: {}", configFilePath);
            logger.info("You can edit this file to change ND4J settings for the next startup");
        } catch (IOException e) {
            logger.error("Failed to persist ND4J config to {}: {}", configFilePath, e.getMessage());
            logger.warn("Settings will still be applied but won't persist across restarts");
        }
    }

    /**
     * Configure JavaCPP properties for GraalVM native image mode.
     * In native image mode, JavaCPP uses the same directory as the binary
     * for its native library cache. Native libraries (libnd4jcpu.so, etc.)
     * must be placed alongside the binary.
     *
     * This MUST be called before any ND4J/JavaCPP class initialization.
     */
    private static void configureJavaCppForNativeImage() {
        if (NativeImageInfo.isRunningInNativeImage()) {
            logger.info("Running as GraalVM native image - configuring JavaCPP for native mode");

            // Get the directory containing the native executable
            String execPath = NativeImageInfo.getExecutablePath();
            Path binaryDir;
            if (execPath != null) {
                binaryDir = Paths.get(execPath).toAbsolutePath().getParent();
            } else {
                // Fallback: use current working directory
                binaryDir = Paths.get(".").toAbsolutePath();
                logger.warn("Could not determine native executable path, using CWD: {}", binaryDir);
            }

            // Set JavaCPP cache directory to the binary's directory.
            // In native image mode, JavaCPP looks for native libraries here.
            System.setProperty("org.bytedeco.javacpp.cachedir", binaryDir.toString());

            // Ensure pathsFirst is set so JavaCPP checks the cache dir first
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            // Also set the platform-specific library path so System.loadLibrary can find natives
            String existingLibPath = System.getProperty("java.library.path", "");
            if (!existingLibPath.contains(binaryDir.toString())) {
                String newLibPath = binaryDir.toString() +
                        (existingLibPath.isEmpty() ? "" : ":" + existingLibPath);
                System.setProperty("java.library.path", newLibPath);
            }

            // Set the ND4J resource directory to binary directory as well
            System.setProperty("org.bytedeco.javacpp.platform.resourcedir", binaryDir.toString());

            logger.info("JavaCPP native image config: cachedir={}, pathsFirst=true, java.library.path includes binary dir",
                    binaryDir);

            // Also check for a 'natives' subdirectory alongside the binary
            Path nativesDir = binaryDir.resolve("natives");
            if (Files.isDirectory(nativesDir)) {
                String libPath = System.getProperty("java.library.path", "");
                if (!libPath.contains(nativesDir.toString())) {
                    System.setProperty("java.library.path",
                            nativesDir.toString() + ":" + libPath);
                }
                logger.info("Found natives/ directory alongside binary: {}", nativesDir);
            }
        } else {
            logger.debug("Running in JVM mode - using default JavaCPP configuration");
        }
    }

    /**
     * Apply ND4J environment configuration settings.
     * This is a static method that can be called before Spring context starts.
     *
     * IMPORTANT: This must be called BEFORE any SameDiff operations.
     */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        logger.info("Applying ND4J environment configuration (BEFORE SameDiff usage)...");

        try {
            // === CORE SETTINGS (must be set first) ===
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
                logger.debug("Set enableBlas to {}", config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
                logger.debug("Set helpersAllowed to {}", config.helpersAllowed());
            }

            // === THREAD CONFIGURATION ===
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
                logger.debug("Set maxThreads to {}", config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
                logger.debug("Set maxMasterThreads to {}", config.maxMasterThreads());
            }

            // === DEBUG/VERBOSE MODES ===
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(true);
                logger.debug("Set debug to {}", config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
                logger.debug("Set verbose to {}", config.verbose());
            }
            if (config.profiling() != null) {
                Nd4j.getEnvironment().setProfiling(config.profiling());
                logger.debug("Set profiling to {}", config.profiling());
            }
            if (config.leaksDetector() != null) {
                Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
                logger.debug("Set leaksDetector to {}", config.leaksDetector());
            }

            // === PERFORMANCE THRESHOLDS ===
            if (config.tadThreshold() != null) {
                Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
                logger.debug("Set tadThreshold to {}", config.tadThreshold());
            }
            if (config.elementwiseThreshold() != null) {
                Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
                logger.debug("Set elementwiseThreshold to {}", config.elementwiseThreshold());
            }

            // === MEMORY LIMITS ===
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) {
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
                logger.debug("Set maxPrimaryMemory to {}", config.maxPrimaryMemory());
            }
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) {
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
                logger.debug("Set maxSpecialMemory to {}", config.maxSpecialMemory());
            }
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) {
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
                logger.debug("Set maxDeviceMemory to {}", config.maxDeviceMemory());
            }

            // === LIFECYCLE TRACKING MASTER SWITCH ===
            if (config.lifecycleTracking() != null) {
                Nd4j.getEnvironment().setLifecycleTracking(config.lifecycleTracking());
                logger.debug("Set lifecycleTracking to {}", config.lifecycleTracking());
            }

            // === LIFECYCLE TRACKING SUB-OPTIONS ===
            if (config.trackViews() != null) {
                Nd4j.getEnvironment().setTrackViews(config.trackViews());
            }
            if (config.trackDeletions() != null) {
                Nd4j.getEnvironment().setTrackDeletions(config.trackDeletions());
            }
            if (config.snapshotFiles() != null) {
                Nd4j.getEnvironment().setSnapshotFiles(config.snapshotFiles());
            }
            if (config.trackOperations() != null) {
                Nd4j.getEnvironment().setTrackOperations(config.trackOperations());
            }

            // === LIFECYCLE TRACKING PARAMETERS ===
            if (config.stackDepth() != null) {
                Nd4j.getEnvironment().setStackDepth(config.stackDepth());
            }
            if (config.reportInterval() != null) {
                Nd4j.getEnvironment().setReportInterval(config.reportInterval());
            }
            if (config.maxDeletionHistory() != null) {
                Nd4j.getEnvironment().setMaxDeletionHistory(config.maxDeletionHistory());
            }

            // === INDIVIDUAL TRACKER TOGGLES ===
            if (config.ndArrayTracking() != null) {
                Nd4j.getEnvironment().setNDArrayTracking(config.ndArrayTracking());
            }
            if (config.dataBufferTracking() != null) {
                Nd4j.getEnvironment().setDataBufferTracking(config.dataBufferTracking());
            }
            if (config.tadCacheTracking() != null) {
                Nd4j.getEnvironment().setTADCacheTracking(config.tadCacheTracking());
            }
            if (config.shapeCacheTracking() != null) {
                Nd4j.getEnvironment().setShapeCacheTracking(config.shapeCacheTracking());
            }
            if (config.opContextTracking() != null) {
                Nd4j.getEnvironment().setOpContextTracking(config.opContextTracking());
            }

            // === ADVANCED DEBUGGING - FUNCTION TRACING ===
            if (config.funcTracePrintAllocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForAllocate(config.funcTracePrintAllocate());
            }
            if (config.funcTracePrintDeallocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForDeallocate(config.funcTracePrintDeallocate());
            }
            if (config.funcTracePrintJavaOnly() != null) {
                Nd4j.getEnvironment().setFuncTracePrintJavaOnly(config.funcTracePrintJavaOnly());
            }

            // === ADVANCED DEBUGGING - OTHER ===
            if (config.logNativeNDArrayCreation() != null) {
                Nd4j.getEnvironment().setLogNativeNDArrayCreation(config.logNativeNDArrayCreation());
            }
            if (config.logNDArrayEvents() != null) {
                Nd4j.getEnvironment().setLogNDArrayEvents(config.logNDArrayEvents());
            }
            if (config.truncateNDArrayLogStrings() != null) {
                Nd4j.getEnvironment().setTruncateLogStrings(config.truncateNDArrayLogStrings());
            }
            if (config.checkInputChange() != null) {
                Nd4j.getEnvironment().setCheckInputChange(config.checkInputChange());
            }
            if (config.checkOutputChange() != null) {
                Nd4j.getEnvironment().setCheckOutputChange(config.checkOutputChange());
            }
            if (config.trackWorkspaceOpenClose() != null) {
                Nd4j.getEnvironment().setTrackWorkspaceOpenClose(config.trackWorkspaceOpenClose());
            }
            if (config.deleteShapeInfo() != null) {
                Nd4j.getEnvironment().setDeleteShapeInfo(config.deleteShapeInfo());
            }
            if (config.deletePrimary() != null) {
                Nd4j.getEnvironment().setDeletePrimary(config.deletePrimary());
            }
            if (config.deleteSpecial() != null) {
                Nd4j.getEnvironment().setDeleteSpecial(config.deleteSpecial());
            }
            if (config.variableTracingEnabled() != null) {
                Nd4j.getEnvironment().setVariableTracingEnabled(config.variableTracingEnabled());
            }

            // === JAVACPP SETTINGS (system properties) ===
            if (config.javacppLoggerDebug() != null) {
                System.setProperty("org.bytedeco.javacpp.logger.debug", config.javacppLoggerDebug().toString());
            }
            if (config.javacppPathsFirst() != null) {
                System.setProperty("org.bytedeco.javacpp.pathsFirst", config.javacppPathsFirst().toString());
            }

            // === OMP THREADS (OpenMP parallelism) ===
            if (config.ompNumThreads() != null && config.ompNumThreads() > 0) {
                try {
                    NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
                    nativeOps.setOmpNumThreads(config.ompNumThreads());
                    logger.info("Set OpenMP threads to {} (persisted setting)", config.ompNumThreads());
                } catch (Exception e) {
                    logger.warn("Failed to set OMP threads to {}: {}", config.ompNumThreads(), e.getMessage());
                }
            }

            // === CUDA CONFIGURATION ===
            // Only apply CUDA settings if running on CUDA backend
            if (!Nd4j.getEnvironment().isCPU()) {
                logger.info("CUDA backend detected - applying CUDA configuration settings");
                var env = Nd4j.getEnvironment();
                if (config.cudaCurrentDevice() != null) {
                    env.setCudaCurrentDevice(config.cudaCurrentDevice());
                }
                if (config.cudaMemoryPinned() != null) {
                    env.setCudaMemoryPinned(config.cudaMemoryPinned());
                }
                if (config.cudaUseManagedMemory() != null) {
                    env.setCudaUseManagedMemory(config.cudaUseManagedMemory());
                }
                if (config.cudaMemoryPoolSize() != null) {
                    env.setCudaMemoryPoolSize(config.cudaMemoryPoolSize());
                }
                if (config.cudaForceP2P() != null) {
                    env.setCudaForceP2P(config.cudaForceP2P());
                }
                if (config.cudaAllocatorEnabled() != null) {
                    env.setCudaAllocatorEnabled(config.cudaAllocatorEnabled());
                }
                if (config.cudaMaxBlocks() != null) {
                    env.setCudaMaxBlocks(config.cudaMaxBlocks());
                }
                if (config.cudaMaxThreadsPerBlock() != null) {
                    env.setCudaMaxThreadsPerBlock(config.cudaMaxThreadsPerBlock());
                }
                if (config.cudaAsyncExecution() != null) {
                    env.setCudaAsyncExecution(config.cudaAsyncExecution());
                }
                if (config.cudaStreamLimit() != null) {
                    env.setCudaStreamLimit(config.cudaStreamLimit());
                }
                if (config.cudaUseDeviceHost() != null) {
                    env.setCudaUseDeviceHost(config.cudaUseDeviceHost());
                }
                if (config.cudaEventLimit() != null) {
                    env.setCudaEventLimit(config.cudaEventLimit());
                }
                if (config.cudaCachingAllocatorLimit() != null) {
                    env.setCudaCachingAllocatorLimit(config.cudaCachingAllocatorLimit());
                }
                if (config.cudaUseUnifiedMemory() != null) {
                    env.setCudaUseUnifiedMemory(config.cudaUseUnifiedMemory());
                }
                if (config.cudaPrefetchSize() != null) {
                    env.setCudaPrefetchSize(config.cudaPrefetchSize());
                }
                if (config.cudaGraphOptimization() != null) {
                    env.setCudaGraphOptimization(config.cudaGraphOptimization());
                }
                if (config.cudaTensorCoreEnabled() != null) {
                    env.setCudaTensorCoreEnabled(config.cudaTensorCoreEnabled());
                }
                if (config.cudaBlockingSync() != null) {
                    env.setCudaBlockingSync(config.cudaBlockingSync());
                }
                if (config.cudaDeviceSchedule() != null) {
                    env.setCudaDeviceSchedule(config.cudaDeviceSchedule());
                }
                if (config.cudaStackSize() != null) {
                    env.setCudaStackSize(config.cudaStackSize());
                }
                if (config.cudaMallocHeapSize() != null) {
                    env.setCudaMallocHeapSize(config.cudaMallocHeapSize());
                }
                if (config.cudaPrintfFifoSize() != null) {
                    env.setCudaPrintfFifoSize(config.cudaPrintfFifoSize());
                }
                if (config.cudaDevRuntimeSyncDepth() != null) {
                    env.setCudaDevRuntimeSyncDepth(config.cudaDevRuntimeSyncDepth());
                }
                if (config.cudaDevRuntimePendingLaunchCount() != null) {
                    env.setCudaDevRuntimePendingLaunchCount(config.cudaDevRuntimePendingLaunchCount());
                }
                if (config.cudaMaxL2FetchGranularity() != null) {
                    env.setCudaMaxL2FetchGranularity(config.cudaMaxL2FetchGranularity());
                }
                if (config.cudaPersistingL2CacheSize() != null) {
                    env.setCudaPersistingL2CacheSize(config.cudaPersistingL2CacheSize());
                }
                logger.info("Applied CUDA-specific configuration settings");

                // === TRITON COMPILER CONFIGURATION (GPU only) ===
                if (config.tritonBuildThreads() != null) {
                    env.setTritonBuildThreads(config.tritonBuildThreads());
                }
                if (config.tritonCacheEnabled() != null) {
                    env.setTritonCacheEnabled(config.tritonCacheEnabled());
                }
                if (config.tritonVerbose() != null) {
                    env.setTritonVerbose(config.tritonVerbose());
                }
                if (config.tritonAlwaysCompile() != null) {
                    env.setTritonAlwaysCompile(config.tritonAlwaysCompile());
                }
                if (config.tritonNumWarps() != null) {
                    env.setTritonNumWarps(config.tritonNumWarps());
                }
                if (config.tritonNumStages() != null) {
                    env.setTritonNumStages(config.tritonNumStages());
                }
                if (config.tritonNumCTAs() != null) {
                    env.setTritonNumCTAs(config.tritonNumCTAs());
                }
                if (config.tritonEnableFpFusion() != null) {
                    env.setTritonEnableFpFusion(config.tritonEnableFpFusion());
                }
                if (config.tritonCacheDir() != null) {
                    env.setTritonCacheDir(config.tritonCacheDir());
                }
                if (config.tritonDumpDir() != null) {
                    env.setTritonDumpDir(config.tritonDumpDir());
                }
                if (config.tritonOverrideArch() != null) {
                    env.setTritonOverrideArch(config.tritonOverrideArch());
                }
                logger.info("Applied Triton compiler configuration settings");
            }

            logger.info("ND4J environment configuration applied successfully");
            logger.info("  enableBlas={}, helpersAllowed={}, maxThreads={}, maxMasterThreads={}",
                    Nd4j.getEnvironment().isEnableBlas(),
                    Nd4j.getEnvironment().helpersAllowed(),
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getEnvironment().maxMasterThreads());
            logger.info("  debug={}, verbose={}, profiling={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().isDebug(),
                    Nd4j.getEnvironment().isVerbose(),
                    Nd4j.getEnvironment().isProfiling(),
                    Nd4j.getEnvironment().isLifecycleTracking());
            logger.info("  ompNumThreads={} (from config: {})",
                    config.ompNumThreads() != null ? config.ompNumThreads() : "default",
                    config.ompNumThreads());

        } catch (Exception e) {
            logger.error("Error applying ND4J environment configuration: {}", e.getMessage(), e);
            logger.warn("Falling back to ND4J default settings");
        }
    }

    /**
     * Component to initiate graceful shutdown of embedding models EARLY in the
     * shutdown process.
     * This runs BEFORE the Nd4jCleanupAndExitHandler to ensure that:
     * 1. New encoding operations are rejected
     * 2. Active encoding operations are allowed to complete
     * 3. Native tokenizer resources are freed AFTER all operations complete
     *
     * Using HIGHEST_PRECEDENCE to run FIRST (before other @PreDestroy handlers).
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class EmbeddingModelGracefulShutdownHandler {
        private static final Logger log = LoggerFactory.getLogger(EmbeddingModelGracefulShutdownHandler.class);

        private ai.kompile.core.embeddings.EmbeddingModel embeddingModel;

        public EmbeddingModelGracefulShutdownHandler(
                @org.springframework.beans.factory.annotation.Autowired(required = false) ai.kompile.core.embeddings.EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        @PreDestroy
        public void initiateGracefulShutdown() {
            log.info("=== Initiating graceful shutdown of embedding models ===");

            if (embeddingModel == null) {
                log.info("No embedding model configured, skipping graceful shutdown");
                return;
            }

            // Check if this is an AnseriniEmbeddingModelImpl which supports graceful
            // shutdown
            if (embeddingModel instanceof ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl) {
                ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl anseriniModel = (ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl) embeddingModel;

                log.info("Initiating graceful shutdown for Anserini embedding model");
                try {
                    anseriniModel.initiateShutdown();
                    log.info("Graceful shutdown initiated - new operations will be rejected");
                } catch (Exception e) {
                    log.warn("Error initiating graceful shutdown for embedding model", e);
                }
            } else {
                log.info("Embedding model does not support graceful shutdown: {}",
                        embeddingModel.getClass().getName());
            }

            log.info("=== Embedding model graceful shutdown initiated ===");
        }
    }

    /**
     * Component to handle ND4J cleanup and force exit
     * Using lowest order to run last (after all other @PreDestroy)
     */
    @Component
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static class Nd4jCleanupAndExitHandler {
        private static final Logger log = LoggerFactory.getLogger(Nd4jCleanupAndExitHandler.class);

        @PreDestroy
        public void cleanupAndExit() {
            log.info("Starting comprehensive ND4J native resource cleanup");

            try {
                // 1. Destroy workspaces for current thread
                Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
                log.info("Destroyed ND4J workspaces for shutdown thread");

                // 2. Release memory context
                Nd4j.getMemoryManager().releaseCurrentContext();
                log.info("Released ND4J memory context");

                // 3. Trigger native memory deallocation
                try {
                    Nd4j.getMemoryManager().invokeGc();
                    log.info("Invoked ND4J garbage collection");
                } catch (Exception e) {
                    log.debug("Could not invoke ND4J GC (may not be available)", e);
                }

                // 4. Try to tear down NativeOps (may not have public API)
                try {
                    NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
                    if (nativeOps != null) {
                        // Call tearDown if available via reflection
                        try {
                            nativeOps.getClass().getMethod("tearDown").invoke(nativeOps);
                            log.info("Called NativeOps tearDown via reflection");
                        } catch (NoSuchMethodException e) {
                            log.debug("NativeOps.tearDown() not available");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not tear down NativeOps", e);
                }

                // 5. Try to shut down BLAS thread pool via JavaCPP
                try {
                    // This attempts to call openblas_set_num_threads(1) then
                    // openblas_set_num_threads(0)
                    // to force OpenBLAS to shut down its thread pool
                    Class<?> openblasClass = Class.forName("org.bytedeco.javacpp.openblas");
                    java.lang.reflect.Method setThreadsMethod = openblasClass.getMethod("blas_set_num_threads",
                            int.class);
                    setThreadsMethod.invoke(null, 1);
                    Thread.sleep(100);
                    setThreadsMethod.invoke(null, 0);
                    log.info("Attempted to shut down OpenBLAS thread pool");
                } catch (ClassNotFoundException e) {
                    log.debug("OpenBLAS class not available (may be using MKL or other BLAS)");
                } catch (Exception e) {
                    log.debug("Could not shut down OpenBLAS threads via JavaCPP", e);
                }

                // 6. Force a system GC to cleanup any remaining references
                try {
                    System.gc();
                    log.info("Forced system garbage collection");
                } catch (Exception e) {
                    log.debug("Could not force system GC", e);
                }

                log.info("ND4J native resource cleanup completed");

            } catch (Throwable e) {
                log.warn("Error during ND4J native resource cleanup (ND4J may not be initialized): {}", e.getMessage());
            }

            // CRITICAL: Cleanup order matters!
            // 1. First close scalar INDArrays (may trigger TAD/Shape allocations during
            // cleanup)
            // 2. Then clear TAD cache (cleans up TADs created during scalar cleanup)
            // 3. Then clear Shape cache (cleans up shapes created during scalar cleanup)
            // 4. Finally trigger leak check

            try {
                // Step 1: DifferentialFunctionClassHolder cleanup
                // CRITICAL: Operation prototypes hold scalar INDArrays with native memory
                // These must be explicitly closed before leak detection or they will be
                // reported as leaks
                try {
                    log.info("Step 1: Cleaning up DifferentialFunctionClassHolder operation prototypes...");
                    org.nd4j.imports.converters.DifferentialFunctionClassHolder.cleanup();
                    log.info("Operation prototypes cleaned up successfully");
                } catch (Throwable e) {
                    log.warn("Error cleaning up operation prototypes: {}", e.getMessage());
                }

                // Step 2: Clear native TAD cache (after scalar cleanup to catch any TADs
                // created during close)
                try {
                    log.info("Step 2: Clearing native TAD cache after scalar cleanup...");
                    Nd4j.getNativeOps().clearTADCache();
                    log.info("TAD cache cleared");
                } catch (Throwable e) {
                    log.warn("Error clearing TAD cache: {}", e.getMessage());
                }

                // Step 3: Clear native Shape cache (after scalar cleanup to catch any shapes
                // created during close)
                try {
                    log.info("Step 3: Clearing native Shape cache after scalar cleanup...");
                    Nd4j.getNativeOps().clearShapeCache();
                    log.info("Shape cache cleared");
                } catch (Throwable e) {
                    log.warn("Error clearing Shape cache: {}", e.getMessage());
                }

                try {
                    log.warn("All cleanup complete. Triggering leak check...");
                    Nd4j.getNativeOps().triggerLeakCheck();
                } catch (Throwable e) {
                    log.warn("Could not trigger leak check: {}", e.getMessage());
                }
            } catch (Throwable e) {
                log.warn("ND4J cleanup steps skipped (ND4J may not be initialized): {}", e.getMessage());
            }

            log.info("=== Cleanup complete. External process will terminate JVM in 2 seconds. ===");
        }
    }
}
