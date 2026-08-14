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

package ai.kompile.app.runtime;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.utils.NativeImageInfo;
import io.anserini.search.LuceneRuntimeConfig;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Everything a Kompile Boot server must do <em>before</em> {@code SpringApplication.run}.
 *
 * <p>Each persona is its own process — {@code kompile-app-main} (admin, :8080),
 * {@code kompile-app-chat} (:8081) and {@code kompile-app-crawl-manager} (:8082) — but the
 * native side of startup is identical for all three: Lucene's mmap provider has to be chosen
 * before the first {@code MMapDirectory}, the native libraries have to be resolvable before the
 * first ND4J call, and the persisted ND4J environment has to be applied before any bean touches
 * SameDiff. Getting that ordering wrong fails at native-library load time with a message-less
 * {@code ExceptionInInitializerError}, so it lives here once rather than being copied per app.
 *
 * <p>This class deliberately does <em>not</em> own subprocess dispatch. Each persona entry point
 * routes {@code --subprocess=} before invoking this bootstrap, using the subprocess mains available
 * on that persona's classpath; app-main additionally registers its admin-only subprocess types.
 */
public final class KompileServerRuntime {

    private static final Logger logger = LoggerFactory.getLogger(KompileServerRuntime.class);

    /** Multipart limits are passed as {@code --kompile.multipart.*} args and read back as system properties. */
    public static final String MAX_FILE_SIZE_PROPERTY = "kompile.multipart.max-file-size";
    public static final String MAX_REQUEST_SIZE_PROPERTY = "kompile.multipart.max-request-size";
    public static final String DEFAULT_MAX_FILE_SIZE = "5000MB";
    public static final String DEFAULT_MAX_REQUEST_SIZE = "5000MB";

    /**
     * True once {@code SpringApplication.run} returned successfully. {@link Nd4jCleanupAndExitHandler}
     * halts immediately on a normal shutdown but must delay the halt on a startup failure, otherwise
     * it kills the process before the failure report prints.
     */
    private static volatile boolean startupCompleted = false;

    private KompileServerRuntime() {}

    /** Call from {@code main} the moment {@code SpringApplication.run} returns. */
    public static void markStartupCompleted() {
        startupCompleted = true;
    }

    /** @return whether startup got as far as a refreshed context; see {@link #markStartupCompleted()}. */
    public static boolean isStartupCompleted() {
        return startupCompleted;
    }

    /**
     * Runs the full pre-Spring bootstrap in the order the native stack requires.
     *
     * @param args the raw {@code main} arguments, scanned for the multipart overrides
     */
    public static void bootstrap(String[] args) {
        ensureLuceneRuntime();

        applyMultipartArgs(args);

        // In GraalVM native images, Log4j2 API's ServiceLoaderUtil uses MethodHandles
        // to invoke ServiceLoader.load() which is not supported. Bypass by directly
        // specifying the SLF4J bridge provider, avoiding ServiceLoader entirely.
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");

        // Resolve native libraries BEFORE any ND4J calls. The shared resolver owns the
        // complete native/JVM, CUDA/CPU side-loading policy; do not rebase its selected
        // flat lib/ directory in a persona-specific startup path.
        configureJavaCppForNativeImage();

        initializeNd4j();
    }

    /**
     * Pick Lucene's mmap provider for the runtime we're actually in (centralized auto-detect):
     * the native-image-safe legacy MappedByteBuffer provider inside a GraalVM native image, the
     * faster MemorySegment provider on a normal JVM. Both keep the index off-heap.
     *
     * <p>Must run before any {@code MMapDirectory} is constructed, which is earlier than
     * {@link #bootstrap} for a process that may instead dispatch to a subprocess main — hence it
     * is separately callable. {@code ensure()} is idempotent.
     */
    public static void ensureLuceneRuntime() {
        LuceneRuntimeConfig.ensure();
    }

    /**
     * Reads {@code --kompile.multipart.max-file-size} / {@code --kompile.multipart.max-request-size}
     * off the command line and republishes them as system properties, which is where the multipart
     * config reads them from. Defaults to 5GB either way.
     */
    private static void applyMultipartArgs(String[] args) {
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
        logger.info("Multipart limits: maxFileSize={}, maxRequestSize={}", maxFileSizeArg, maxRequestSizeArg);
    }

    /**
     * Loads the ND4J backend and applies the persisted environment configuration.
     *
     * <p>Skipped entirely during Spring AOT processing — there is no native backend at build time.
     * A failure here is logged, not fatal: embedding work then falls back to subprocess mode.
     */
    private static void initializeNd4j() {
        // ND4JClassLoading captures the context classloader in static state; under
        // native image a build-time-initialized copy holds a dead builder classloader
        // and ServiceLoader silently finds no Nd4jBackend providers. Point it at the
        // runtime classloader before the first Nd4j touch (harmless on the JVM).
        org.nd4j.common.config.ND4JClassLoading.setNd4jClassloader(KompileServerRuntime.class.getClassLoader());
        logger.info("Nd4jBackend discovery probe: services resource={}, classloader={}",
                KompileServerRuntime.class.getClassLoader()
                        .getResource("META-INF/services/org.nd4j.linalg.factory.Nd4jBackend"),
                KompileServerRuntime.class.getClassLoader());

        if (Boolean.getBoolean("spring.aot.processing")) {
            logger.info("Spring AOT processing mode - skipping ND4J initialization");
            return;
        }

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

            // CRITICAL: apply the persisted ND4J environment BEFORE the Spring context starts, so
            // that beans constructed early (embedding models in particular) already see the right
            // thread counts and tracking flags. ND4J environment setters must precede SameDiff use.
            loadAndApplyPersistedNd4jConfig();
            logger.info("ND4J environment configured: maxThreads={}, maxMasterThreads={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getEnvironment().maxMasterThreads(),
                    Nd4j.getEnvironment().isLifecycleTracking());
        } catch (Throwable e) {
            // Log the full chain: under native image the root cause is typically a
            // message-less ExceptionInInitializerError/NPE whose getMessage() is null.
            logger.warn("ND4J initialization failed (backend may not be available). " +
                    "Embedding operations will use subprocess mode. Error: {}", e.getMessage(), e);
            Throwable cause = e.getCause();
            while (cause != null) {
                logger.warn("  caused by: {}: {}", cause.getClass().getName(), cause.getMessage());
                cause = cause.getCause();
            }
        }
    }

    /**
     * Applies the persisted ND4J environment configuration.
     *
     * <p>The merge/apply/persist logic lives in {@link Nd4jEnvironmentConfigService}, which Spring
     * also runs from its own {@code @PostConstruct}. Constructing it directly here is what makes the
     * settings land before the context exists — there is exactly one copy of the apply logic, and
     * the later Spring pass is idempotent.
     *
     * <p>Config file: {@code ~/.kompile/config/nd4j-environment-config.json}, overlaid by
     * {@code <kompile.data.dir>/config/...} when that property is set. Manage it at runtime via
     * {@code GET/POST /api/nd4j/environment} on the admin console.
     */
    private static void loadAndApplyPersistedNd4jConfig() {
        logger.info("=== Loading Persisted ND4J Environment Configuration ===");
        String dataDir = System.getProperty("kompile.data.dir");
        new Nd4jEnvironmentConfigService(dataDir).syncWithPersistedConfig();
    }

    /**
     * Resolve and configure JavaCPP/ND4J native libraries before their first class
     * initialization. {@link NativeLibraryResolver} is the single owner of the
     * side-loading policy for both JVM and native-image execution.
     *
     * <p>In a distribution it uses {@link NativeImageInfo#getExecutablePath()} to
     * select the canonical sibling {@code lib/} directory. Do not overwrite that
     * cache root with {@code bin/}: JavaCPP would create {@code bin/lib} symlinks,
     * and a later launch would fail SharedCompilerRuntime's directory trust check.
     */
    public static void configureJavaCppForNativeImage() {
        NativeLibraryResolver.bootstrapOrThrow();
        if (NativeImageInfo.isRunningInNativeImage()) {
            logger.info("JavaCPP native image config: cachedir={}, sharedRuntimePath={}, pathsFirst={}",
                    System.getProperty("org.bytedeco.javacpp.cachedir"),
                    System.getProperty("org.nd4j.presets.sharedRuntimePath"),
                    System.getProperty("org.bytedeco.javacpp.pathsFirst"));
        } else {
            logger.debug("Running in JVM mode - JavaCPP configured by NativeLibraryResolver");
        }
    }
}
