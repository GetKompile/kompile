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

package ai.kompile.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves JavaCPP/ND4J native libraries dynamically for both JVM and
 * GraalVM native image modes.
 * <p>
 * <b>JVM / dev path:</b> Scans {@code java.class.path} entries (JARs and
 * directories) for native libraries matching the current platform.  Any JAR
 * on the classpath that contains platform-specific native libs gets its libs
 * extracted to the cache.  Zero hardcoded artifact IDs — works with whatever
 * dependencies are on the classpath.
 * <p>
 * <b>Native image path:</b> No classpath or embedded native payload exists at
 * runtime. Resolution is deliberately limited to the distribution contract:
 * <ol>
 *   <li>{@code KOMPILE_NATIVE_LIB_DIR} env var — explicit side-loaded override</li>
 *   <li>{@code <binary-dir>/lib/} or {@code <binary-dir>/../lib/} — packaged distribution</li>
 *   <li>{@code <binary-dir>} — the image-matched GraalVM shim libraries</li>
 * </ol>
 * Native images never fall back to mutable JavaCPP caches or a developer Maven
 * repository; doing so would hide an incomplete or backend-mismatched distribution.
 */
public class NativeLibraryResolver {

    private static final Logger logger = Logger.getLogger(NativeLibraryResolver.class.getName());

    /** Platform string in JavaCPP format (e.g. "linux-x86_64"). */
    private static final String PLATFORM = detectPlatform();

    /** Default JavaCPP cache directory under $HOME. */
    private static final String JAVACPP_CACHE_DEFAULT = ".javacpp/cache";

    /** Kompile cache subdirectory under ~/.kompile/ */
    private static final String KOMPILE_NATIVE_LIBS = "native-libs";

    /** Runtime path propagated through Kompile's backend-isolated child launchers. */
    static final String ND4J_SHARED_RUNTIME_PATH =
            "org.nd4j.presets.sharedRuntimePath";

    /** Runtime path consumed directly by JavaCPP presets in the current process. */
    static final String JAVACPP_LIBRARY_PATH =
            "org.bytedeco.javacpp.library.path";

    /** Backend priority properties consumed by ND4J's service loader. */
    static final String ND4J_CPU_PRIORITY = "org.nd4j.cpu.priority";
    static final String ND4J_GPU_PRIORITY = "org.nd4j.gpu.priority";

    static final String SHARED_RUNTIME_MANIFEST = "shared-runtime-manifest.txt";
    private static final String SHARED_RUNTIME_MANIFEST_FORMAT =
            "# nd4j-shared-runtime-manifest-v1";
    private static final String RUNTIME_COUNT_PREFIX = "# runtime-count=";

    /** Producer-generated list of direct JNI payloads safe to load at process bootstrap. */
    static final String JNI_ENTRYPOINT_MANIFEST = "jni-entrypoint-manifest.txt";
    private static final String JNI_ENTRYPOINT_MANIFEST_FORMAT =
            "# kompile-jni-entrypoint-manifest-v1";
    private static final String JNI_ENTRYPOINT_COUNT_PREFIX = "# entry-count=";

    /**
     * JNI bridges that must be loaded from the selected side-loaded runtime before
     * JavaCPP or ND4J initializes. The names are platform-neutral; file-name
     * normalization handles lib*.so, lib*.dylib, and *.dll.
     */
    private static final List<String> COMMON_JNI_LOAD_ORDER = List.of("jvm", "jnijavacpp");
    private static final Map<String, List<String>> BACKEND_JNI_LOAD_ORDER = Map.of(
            "jnind4jcuda", List.of("jnicudart", "jnicublas", "jnind4jcuda"),
            "jnind4jzluda", List.of("jnicudart", "jnicublas", "jnind4jzluda"),
            "jnind4jvulkan", List.of("jnind4jvulkan"),
            "jnind4jtpu", List.of("jnind4jtpu"),
            "jnind4jhexagon", List.of("jnind4jhexagon"),
            "jnind4jcpu", List.of("jniopenblas_nolapack", "jniopenblas", "jnimkl_rt", "jnind4jcpu"));
    private static final List<String> BACKEND_PREFERENCE = List.of(
            "jnind4jcuda", "jnind4jzluda", "jnind4jvulkan",
            "jnind4jtpu", "jnind4jhexagon", "jnind4jcpu");
    private static final Set<Path> LOADED_SIDE_LOADED_JNI = new LinkedHashSet<>();

    private NativeLibraryResolver() {}

    /** Defines which distribution-owned JNI closure an executable owns. */
    public enum BootstrapMode {
        /** CLI/orchestrator dependencies declared by the generated JNI entrypoint manifest. */
        CORE,
        /** JavaCPP plus the selected ND4J backend, without application-level direct JNI. */
        MODEL_EXECUTION,
        /** Backward-compatible application scope: model execution plus direct JNI dependencies. */
        FULL
    }

    // ======================== Public API ========================

    /**
     * Full bootstrap: resolve native libs + configure JavaCPP properties.
     * Call from MainApplication.main() BEFORE any ND4J/JavaCPP class init.
     *
     * @return true if native libraries are available (found or not needed)
     */
    public static boolean bootstrap() {
        return bootstrap(BootstrapMode.FULL);
    }

    /**
     * Bootstraps only the native closure owned by the calling executable.
     * Core orchestrators must not initialize model backends that belong to worker
     * subprocesses; model executors retain the complete JavaCPP/ND4J bootstrap.
     */
    public static boolean bootstrap(BootstrapMode mode) {
        if (!NativeImageInfo.isRunningInNativeImage() && hasClassifierJarsOnClasspath()) {
            // JVM mode with classifier JARs present — JavaCPP Loader handles
            // extraction internally.  But we still extract to cache so the
            // library path is set correctly for any native libs JavaCPP
            // doesn't know about (tokenizers, etc.)
            List<Path> dirs = resolveFromClasspath();
            if (!dirs.isEmpty()) {
                configureJavaCpp(dirs);
            }
            return true;
        }

        // Native image mode, or JVM mode without classifier JARs
        List<Path> libDirs = resolve();
        if (libDirs.isEmpty()) {
            return false;
        }
        boolean nativeImage = NativeImageInfo.isRunningInNativeImage();
        if (nativeImage && mode != BootstrapMode.CORE) {
            validateSideLoadedRuntime(libDirs);
        }
        configureJavaCpp(libDirs);
        if (nativeImage) {
            if (mode != BootstrapMode.CORE) {
                configureNd4jBackendPriorities(libDirs);
            }
            loadSideLoadedJniLibraries(libDirs, mode);
        }
        return true;
    }

    /**
     * Configures native libraries and fails immediately when a native executable was
     * published without its required side-loaded library tree. JVM development keeps
     * the existing classpath/cache behavior.
     */
    public static void bootstrapOrThrow() {
        bootstrapOrThrow(BootstrapMode.FULL);
    }

    /** Bootstraps the direct JNI closure used by the CLI and other orchestrators. */
    public static void bootstrapCoreOrThrow() {
        bootstrapOrThrow(BootstrapMode.CORE);
    }

    /** Bootstraps only JavaCPP and the selected ND4J backend for model workers. */
    public static void bootstrapModelExecutionOrThrow() {
        bootstrapOrThrow(BootstrapMode.MODEL_EXECUTION);
    }

    /** Bootstraps the requested native closure and fails loudly for incomplete installs. */
    public static void bootstrapOrThrow(BootstrapMode mode) {
        if (!bootstrap(mode) && NativeImageInfo.isRunningInNativeImage()) {
            throw new IllegalStateException("Native Kompile executable has no side-loaded native libraries. "
                    + "Install the matching distribution lib/ directory or set KOMPILE_NATIVE_LIB_DIR.");
        }
    }

    /**
     * Resolves native library directories from the filesystem.
     * Used for native image mode or when classifier JARs aren't on classpath.
     */
    public static List<Path> resolve() {
        Path binaryDir = getBinaryDirectory();

        // 1. Explicit override
        String envDir = System.getenv("KOMPILE_NATIVE_LIB_DIR");
        if (envDir != null && !envDir.isBlank()) {
            Path envPath = Path.of(envDir);
            if (containsAnyNativeLib(envPath)) {
                logger.info("Using native libs from KOMPILE_NATIVE_LIB_DIR: " + envPath);
                return withNativeImageBinaryDirectory(List.of(envPath), binaryDir);
            }
            logger.warning("KOMPILE_NATIVE_LIB_DIR=" + envPath + " but no native libs found there");
        }

        // 2. Adjacent lib/ directory (assembly layout)
        //    Checks both <binary-dir>/lib/ and <binary-dir>/../lib/
        //    so the dist layout (bin/kompile-server + lib/) works. Handles both
        //    flat lib/ (build-dist.sh) and nested jar-path layouts (assembly unpack).
        if (binaryDir != null) {
            Path adjacentLib = binaryDir.resolve("lib");
            List<Path> fromAdjacent = libDirsUnder(adjacentLib, "adjacent lib/");
            if (!fromAdjacent.isEmpty()) {
                return withNativeImageBinaryDirectory(fromAdjacent, binaryDir);
            }
            Path parentLib = binaryDir.getParent() != null ? binaryDir.getParent().resolve("lib") : null;
            if (parentLib != null && !parentLib.equals(adjacentLib)) {
                List<Path> fromParent = libDirsUnder(parentLib, "dist lib/");
                if (!fromParent.isEmpty()) {
                    return withNativeImageBinaryDirectory(fromParent, binaryDir);
                }
            }
        }

        if (NativeImageInfo.isRunningInNativeImage()) {
            logger.warning("Native image could not resolve its packaged side-loaded lib/ directory");
            return List.of();
        }

        // 3. JavaCPP cache (JVM/development only)
        Path javacppCache = getJavaCppCacheDir();
        if (javacppCache != null && Files.isDirectory(javacppCache)) {
            List<Path> cachedDirs = findInJavaCppCache(javacppCache);
            if (!cachedDirs.isEmpty()) {
                logger.info("Using native libs from JavaCPP cache (" + cachedDirs.size() + " dirs)");
                return cachedDirs;
            }
        }

        // 4. Kompile cache (may have been populated by a prior run)
        Path kompileLibs = getKompileNativeLibDir();
        if (kompileLibs != null && containsAnyNativeLib(kompileLibs)) {
            logger.info("Using native libs from kompile cache: " + kompileLibs);
            return List.of(kompileLibs);
        }

        // 5. Try classpath scanning (works in JVM mode even without classifier JARs
        //    on the class path — picks up directory entries, fat JARs, etc.)
        List<Path> classpathDirs = resolveFromClasspath();
        if (!classpathDirs.isEmpty()) {
            return classpathDirs;
        }

        // 6. Scan Maven local repo for any classifier JARs
        if (kompileLibs != null) {
            List<Path> extracted = scanMavenRepoAndExtract(kompileLibs);
            if (!extracted.isEmpty()) {
                logger.info("Extracted native libs from Maven repo to: " + kompileLibs);
                return extracted;
            }
        }

        logger.warning("No native libraries found for JVM/development execution. Options:\n"
                + "  1. Set KOMPILE_NATIVE_LIB_DIR=/path/to/libs\n"
                + "  2. Use classifier JARs on the classpath\n"
                + "  3. Run once in JVM mode to populate ~/.javacpp/cache/");
        return List.of();
    }

    private static List<Path> withNativeImageBinaryDirectory(
            List<Path> libDirs, Path binaryDir) {
        if (!NativeImageInfo.isRunningInNativeImage()
                || binaryDir == null || !containsAnyNativeLib(binaryDir)) {
            return libDirs;
        }
        LinkedHashSet<Path> resolved = new LinkedHashSet<>();
        resolved.add(binaryDir.toAbsolutePath().normalize());
        for (Path libDir : libDirs) {
            resolved.add(libDir.toAbsolutePath().normalize());
        }
        return List.copyOf(resolved);
    }

    /**
     * Configures JavaCPP system properties for the given library directories.
     */
    public static void configureJavaCpp(List<Path> libDirs) {
        if (libDirs.isEmpty()) return;

        System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

        String pathStr = libDirs.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));

        // Publish one exact runtime selection through both contracts. Kompile
        // launchers propagate the ND4J property to backend-isolated children,
        // while JavaCPP presets consume their native library-path property in
        // the current process. Both deliberately point at the packaged lib/ tree.
        System.setProperty(ND4J_SHARED_RUNTIME_PATH, pathStr);
        System.setProperty(JAVACPP_LIBRARY_PATH, pathStr);

        String existing = System.getProperty("java.library.path", "");
        if (!existing.contains(pathStr)) {
            String newPath = pathStr + (existing.isEmpty() ? "" : File.pathSeparator + existing);
            System.setProperty("java.library.path", newPath);
        }

        if (libDirs.size() == 1) {
            System.setProperty("org.bytedeco.javacpp.cachedir",
                    libDirs.get(0).toAbsolutePath().toString());
        }

        logger.info("JavaCPP configured: pathsFirst=true, sharedRuntimePath=" + pathStr
                + ", library.path=" + pathStr);
    }

    /**
     * Selects the ND4J service-loader backend from the native runtime that the
     * distribution actually packaged. ND4J gives CPU and GPU equal priority by
     * default, so classpath order could otherwise select CPU even for a CUDA-only
     * side-loaded closure. Explicit user priorities always win.
     */
    static void configureNd4jBackendPriorities(List<Path> libDirs) {
        if (System.getProperty(ND4J_CPU_PRIORITY) != null
                || System.getProperty(ND4J_GPU_PRIORITY) != null) {
            return;
        }

        String backend = selectedSideLoadedBackend(libDirs);
        if (backend == null) {
            return;
        }

        boolean cpu = "jnind4jcpu".equals(backend);
        System.setProperty(ND4J_CPU_PRIORITY, cpu ? "100" : "0");
        System.setProperty(ND4J_GPU_PRIORITY, cpu ? "0" : "100");
        logger.info("Selected ND4J " + (cpu ? "CPU" : "accelerator")
                + " backend from packaged JNI bridge " + backend);
    }

    static String selectedSideLoadedBackend(List<Path> libDirs) {
        Set<String> libraries = new LinkedHashSet<>();
        for (Path libDir : libDirs) {
            if (libDir == null || !Files.isDirectory(libDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(libDir)) {
                files.filter(Files::isRegularFile)
                        .map(path -> nativeLibraryBaseName(path.getFileName().toString()))
                        .filter(Objects::nonNull)
                        .forEach(libraries::add);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot inspect side-loaded native directory " + libDir, e);
            }
        }
        for (String backend : BACKEND_PREFERENCE) {
            if (libraries.contains(backend)) {
                return backend;
            }
        }
        return null;
    }

    /**
     * Loads the JavaCPP runtime and selected ND4J backend JNI bridges directly
     * from the distribution's side-loaded library tree. This is intentionally
     * performed only in native-image mode: JVM execution keeps JavaCPP's normal
     * classifier extraction behavior.
     *
     * <p>Using absolute paths here is important. It executes each bridge's
     * {@code JNI_OnLoad}, preserves the distribution-owned backend selection,
     * and lets the bridge resolve its native runtime through its packaged
     * {@code $ORIGIN} runpath. No process environment mutation is involved.</p>
     */
    static void loadSideLoadedJniLibraries(List<Path> libDirs) {
        loadSideLoadedJniLibraries(libDirs, BootstrapMode.FULL);
    }

    static void loadSideLoadedJniLibraries(List<Path> libDirs, BootstrapMode mode) {
        for (Path library : sideLoadedJniLoadPlan(libDirs, mode)) {
            Path normalized = library.toAbsolutePath().normalize();
            synchronized (LOADED_SIDE_LOADED_JNI) {
                if (LOADED_SIDE_LOADED_JNI.contains(normalized)) {
                    continue;
                }
                try {
                    System.load(normalized.toString());
                    LOADED_SIDE_LOADED_JNI.add(normalized);
                    logger.info("Loaded side-loaded native payload: " + normalized.getFileName());
                } catch (UnsatisfiedLinkError | SecurityException e) {
                    throw new IllegalStateException(
                            "Cannot load side-loaded native payload " + normalized
                                    + ". Install the matching Kompile distribution native runtime.", e);
                }
            }
        }
    }

    /** Returns the deterministic, backend-specific JNI load plan without loading it. */
    static List<Path> sideLoadedJniLoadPlan(List<Path> libDirs) {
        return sideLoadedJniLoadPlan(libDirs, BootstrapMode.FULL);
    }

    /** Returns the deterministic JNI load plan owned by the requested executable scope. */
    static List<Path> sideLoadedJniLoadPlan(List<Path> libDirs, BootstrapMode mode) {
        if (mode == BootstrapMode.CORE) {
            return new ArrayList<>(directJniEntrypoints(libDirs));
        }

        Map<String, Path> libraries = new LinkedHashMap<>();
        for (Path libDir : libDirs) {
            if (libDir == null || !Files.isDirectory(libDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(libDir)) {
                files.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            String name = nativeLibraryBaseName(path.getFileName().toString());
                            if (name != null) {
                                libraries.putIfAbsent(name, path.toAbsolutePath().normalize());
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot inspect side-loaded native directory " + libDir, e);
            }
        }

        String selectedBackend = null;
        for (String backend : BACKEND_PREFERENCE) {
            if (libraries.containsKey(backend)) {
                selectedBackend = backend;
                break;
            }
        }

        // JavaCPP and ND4J need a strict bridge order. Other dependencies do not:
        // they still need System.load(), because exposing a symbol through a search
        // path or LD_PRELOAD does not execute JNI_OnLoad with this image's JavaVM.
        boolean javaCppRuntime = libraries.containsKey("jnijavacpp")
                || selectedBackend != null;
        List<String> required = new ArrayList<>();
        if (javaCppRuntime) {
            required.addAll(COMMON_JNI_LOAD_ORDER);
        }
        if (selectedBackend != null) {
            required.addAll(BACKEND_JNI_LOAD_ORDER.get(selectedBackend));
        }
        List<Path> plan = new ArrayList<>();
        for (String name : required) {
            Path library = libraries.get(name);
            if (library == null) {
                if (javaCppRuntime && (name.equals("jvm") || name.equals("jnijavacpp")
                        || name.startsWith("jnind4j")
                        || name.equals("jnicudart") || name.equals("jnicublas"))) {
                    throw new IllegalStateException(
                            "Side-loaded native runtime is missing required JNI bridge '"
                                    + name + "' in " + libDirs);
                }
                continue;
            }
            if (!plan.contains(library)) {
                plan.add(library);
            }
        }

        // Direct JNI dependencies are discovered from their exported symbols by
        // the distribution stager. JavaCPP-generated preset bridges deliberately
        // stay out of this manifest: they are loaded lazily by JavaCPP after its
        // core bridge and the selected ND4J backend have initialized. Eagerly
        // loading every .so is unsafe and also mistakes support runtimes for JNI
        // libraries. The manifest is generic (sqlite-jdbc, JNA, JLine, Snappy,
        // Zstd, and future dependencies) without any artifact-specific rules.
        if (mode == BootstrapMode.FULL) {
            for (Path library : directJniEntrypoints(libDirs)) {
                if (!plan.contains(library)) {
                    plan.add(library);
                }
            }
        }
        return plan;
    }

    private static List<Path> directJniEntrypoints(List<Path> libDirs) {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (Path libDir : libDirs) {
            if (libDir == null || !Files.isDirectory(libDir)) {
                continue;
            }
            Path manifest = libDir.resolve(JNI_ENTRYPOINT_MANIFEST);
            if (!Files.isRegularFile(manifest)) {
                continue;
            }

            int declaredCount;
            int actualCount = 0;
            try (BufferedReader reader = Files.newBufferedReader(
                    manifest, StandardCharsets.UTF_8)) {
                String format = reader.readLine();
                if (!JNI_ENTRYPOINT_MANIFEST_FORMAT.equals(format)) {
                    throw new IllegalStateException(
                            "Unsupported JNI entrypoint manifest format in " + manifest);
                }
                String countLine = reader.readLine();
                if (countLine == null
                        || !countLine.startsWith(JNI_ENTRYPOINT_COUNT_PREFIX)) {
                    throw new IllegalStateException(
                            "Missing entry-count in " + manifest);
                }
                try {
                    declaredCount = Integer.parseInt(countLine.substring(
                            JNI_ENTRYPOINT_COUNT_PREFIX.length()));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "Invalid entry-count in " + manifest + ": " + countLine, e);
                }
                if (declaredCount < 0) {
                    throw new IllegalStateException(
                            "Negative entry-count in " + manifest);
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    String fileName = line.trim();
                    if (fileName.isEmpty()) {
                        continue;
                    }
                    if (fileName.startsWith("#")
                            || fileName.contains("/")
                            || fileName.contains("\\")
                            || !fileName.equals(Path.of(fileName).getFileName().toString())) {
                        throw new IllegalStateException(
                                "Invalid JNI entrypoint '" + fileName + "' in " + manifest);
                    }
                    Path library = libDir.resolve(fileName).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(library)) {
                        throw new IllegalStateException(
                                "Manifest-declared JNI entrypoint is missing: " + library);
                    }
                    if (!entries.add(library)) {
                        throw new IllegalStateException(
                                "Duplicate JNI entrypoint '" + fileName + "' in " + manifest);
                    }
                    actualCount++;
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot read JNI entrypoint manifest " + manifest, e);
            }

            if (actualCount != declaredCount) {
                throw new IllegalStateException(
                        "JNI entrypoint manifest count mismatch in " + manifest
                                + ": declared " + declaredCount + " but found "
                                + actualCount);
            }
        }
        return List.copyOf(entries);
    }

    private static String nativeLibraryBaseName(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("lib")) {
            normalized = normalized.substring(3);
        }
        int suffix = normalized.indexOf(".so");
        if (suffix < 0) suffix = normalized.indexOf(".dylib");
        if (suffix < 0) suffix = normalized.indexOf(".jnilib");
        if (suffix < 0) suffix = normalized.indexOf(".dll");
        return suffix > 0 ? normalized.substring(0, suffix) : null;
    }

    /**
     * Validates the producer-owned ND4J compiler-runtime closure before any
     * JavaCPP or ND4J class can initialize. The distribution stager places the
     * selected classifier manifest beside its native libraries; this method
     * deliberately does not rediscover dependencies from the host system.
     */
    static void validateSideLoadedRuntime(List<Path> libDirs) {
        List<Path> manifests = new ArrayList<>();
        boolean nd4jRuntimePresent = false;
        for (Path libDir : libDirs) {
            if (libDir == null || !Files.isDirectory(libDir)) {
                continue;
            }
            Path manifest = libDir.resolve(SHARED_RUNTIME_MANIFEST);
            if (Files.isRegularFile(manifest)) {
                manifests.add(manifest.toAbsolutePath().normalize());
            }
            try (Stream<Path> files = Files.list(libDir)) {
                if (files.anyMatch(path -> isNd4jRuntime(path.getFileName().toString()))) {
                    nd4jRuntimePresent = true;
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot inspect side-loaded native directory " + libDir, e);
            }
        }

        if (!nd4jRuntimePresent) {
            return;
        }
        if (manifests.size() != 1) {
            throw new IllegalStateException(
                    "Side-loaded ND4J runtime requires exactly one "
                            + SHARED_RUNTIME_MANIFEST + ", found " + manifests.size()
                            + " in " + libDirs);
        }
        validateSharedRuntimeManifest(manifests.get(0));
    }

    private static void validateSharedRuntimeManifest(Path manifest) {
        int declaredCount;
        Set<String> runtimeNames = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(
                manifest, StandardCharsets.UTF_8)) {
            String format = reader.readLine();
            if (!SHARED_RUNTIME_MANIFEST_FORMAT.equals(format)) {
                throw new IllegalStateException(
                        "Unsupported ND4J shared-runtime manifest format in " + manifest);
            }
            String countLine = reader.readLine();
            if (countLine == null || !countLine.startsWith(RUNTIME_COUNT_PREFIX)) {
                throw new IllegalStateException(
                        "Missing runtime-count in " + manifest);
            }
            try {
                declaredCount = Integer.parseInt(
                        countLine.substring(RUNTIME_COUNT_PREFIX.length()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Invalid runtime-count in " + manifest + ": " + countLine, e);
            }
            if (declaredCount < 0) {
                throw new IllegalStateException(
                        "Negative runtime-count in " + manifest);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String runtimeName = line.trim();
                if (runtimeName.isEmpty()) {
                    continue;
                }
                if (runtimeName.startsWith("#")
                        || runtimeName.contains("/")
                        || runtimeName.contains("\\")
                        || !runtimeName.equals(Path.of(runtimeName).getFileName().toString())) {
                    throw new IllegalStateException(
                            "Invalid runtime entry '" + runtimeName + "' in " + manifest);
                }
                if (!runtimeNames.add(runtimeName)) {
                    throw new IllegalStateException(
                            "Duplicate runtime entry '" + runtimeName + "' in " + manifest);
                }
                Path runtime = manifest.getParent().resolve(runtimeName);
                if (!Files.isRegularFile(runtime)) {
                    throw new IllegalStateException(
                            "Manifest-declared runtime is missing: " + runtime);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read ND4J shared-runtime manifest " + manifest, e);
        }

        if (runtimeNames.size() != declaredCount) {
            throw new IllegalStateException(
                    "ND4J shared-runtime manifest count mismatch in " + manifest
                            + ": declared " + declaredCount + " but found "
                            + runtimeNames.size());
        }
    }

    private static boolean isNd4jRuntime(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.contains("nd4jcpu")
                || normalized.contains("nd4jcuda")
                || normalized.contains("nd4jvulkan")
                || normalized.contains("nd4jzluda");
    }

    // ======================== Classpath scanning ========================

    /**
     * Scans every entry on java.class.path for native libraries matching
     * the current platform.  For JARs, extracts matching native libs to
     * the kompile cache.  For directories, adds them directly if they
     * contain native libs.
     * <p>
     * This is the primary resolution path for dev/JVM mode — it's fully
     * dynamic, works with any set of dependencies, and requires zero
     * configuration.
     */
    private static List<Path> resolveFromClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isEmpty()) return List.of();

        Path cacheDir = getKompileNativeLibDir();
        Set<Path> resultDirs = new LinkedHashSet<>();
        String[] entries = classpath.split(File.pathSeparator);
        int extractedCount = 0;

        for (String entry : entries) {
            Path entryPath = Path.of(entry);
            if (!Files.exists(entryPath)) continue;

            if (Files.isDirectory(entryPath)) {
                // Directory classpath entry — check for native libs directly
                // (e.g. target/classes with native libs in a platform subdir)
                List<Path> nativeDirs = findPlatformNativeLibDirs(entryPath);
                resultDirs.addAll(nativeDirs);
            } else if (entry.endsWith(".jar")) {
                // JAR entry — scan for native libs matching our platform
                int count = extractNativeLibsFromJar(entryPath, cacheDir);
                if (count > 0) {
                    extractedCount += count;
                }
            }
        }

        if (extractedCount > 0 && cacheDir != null) {
            resultDirs.add(cacheDir);
            logger.info("Extracted " + extractedCount + " native libs from "
                    + entries.length + " classpath entries to " + cacheDir);
        }

        return new ArrayList<>(resultDirs);
    }

    /**
     * Finds subdirectories within a classpath directory entry that contain
     * native libs for the current platform.  Handles layouts like:
     * <pre>
     *   target/classes/org/bytedeco/cuda/linux-x86_64/libjnicudart.so
     *   target/classes/org/nd4j/.../linux-x86_64/libjnind4jcuda.so
     * </pre>
     */
    private static List<Path> findPlatformNativeLibDirs(Path root) {
        List<Path> dirs = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 10, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isNativeLib(file.getFileName().toString())) {
                        // Only include if the path contains the platform string
                        if (file.toString().contains(PLATFORM)) {
                            Path parent = file.getParent();
                            if (parent != null && !dirs.contains(parent)) {
                                dirs.add(parent);
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.log(Level.FINE, "Error scanning classpath dir: " + root, e);
        }
        return dirs;
    }

    // ======================== JAR extraction ========================

    /**
     * Extracts native libs from a JAR that match the current platform.
     * Scans every entry — no assumptions about internal package structure.
     *
     * @return number of files extracted
     */
    private static int extractNativeLibsFromJar(Path jarPath, Path targetDir) {
        if (targetDir == null) return 0;

        int count = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();

                // Must be for our platform and be a native lib
                if (!name.contains(PLATFORM)) continue;
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (!isNativeLib(fileName)) continue;

                // Ensure target dir exists (lazy create)
                if (count == 0) {
                    try {
                        Files.createDirectories(targetDir);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Cannot create cache dir: " + targetDir, e);
                        return 0;
                    }
                }

                Path target = targetDir.resolve(fileName);
                if (Files.exists(target)) {
                    count++; // Already extracted, still counts as available
                    continue;
                }

                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setExecutable(true);
                }
                logger.fine("Extracted: " + fileName + " from " + jarPath.getFileName());
                count++;
            }
        } catch (IOException e) {
            // Not a valid JAR or can't read — skip silently
            logger.log(Level.FINE, "Skipping " + jarPath.getFileName(), e);
        }
        return count;
    }

    // ======================== JavaCPP cache scanning ========================

    /**
     * Scans the JavaCPP cache for directories containing platform native libs.
     */
    private static List<Path> findInJavaCppCache(Path cacheDir) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> topLevel = Files.list(cacheDir)) {
            for (Path entry : topLevel.collect(Collectors.toList())) {
                if (!Files.isDirectory(entry)) continue;
                if (!entry.getFileName().toString().contains(PLATFORM)) continue;
                result.addAll(findNativeLibDirectories(entry));
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Error scanning JavaCPP cache: " + cacheDir, e);
        }

        // Sort: javacpp base first, then blas, then everything else
        result.sort((a, b) -> {
            int ao = libPathOrder(a.toString());
            int bo = libPathOrder(b.toString());
            return Integer.compare(ao, bo);
        });

        return result;
    }

    private static int libPathOrder(String path) {
        if (path.contains("javacpp") && !path.contains("nd4j")) return 0;
        if (path.contains("openblas")) return 1;
        if (path.contains("mkl")) return 2;
        return 3;
    }

    // ======================== Maven repo scanning ========================

    /**
     * Scans Maven local repo for classifier JARs containing native libs.
     * Finds any JAR whose name contains the platform string and extracts
     * native libs from it.
     */
    private static List<Path> scanMavenRepoAndExtract(Path targetDir) {
        Path m2Repo = getMavenLocalRepo();
        if (m2Repo == null || !Files.isDirectory(m2Repo)) return List.of();

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot create target dir: " + targetDir, e);
            return List.of();
        }

        // Find all JARs with the platform in their name
        List<Path> classifierJars = new ArrayList<>();
        String platformSuffix = "-" + PLATFORM + ".jar";
        try {
            Files.walkFileTree(m2Repo, EnumSet.noneOf(FileVisitOption.class), 10, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(platformSuffix)) {
                        classifierJars.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(m2Repo)) return FileVisitResult.CONTINUE;
                    String n = dir.getFileName().toString();
                    if (n.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error scanning Maven repo", e);
        }

        if (classifierJars.isEmpty()) return List.of();

        // Sort: javacpp first
        classifierJars.sort(Comparator.comparing(p -> {
            String n = p.getFileName().toString();
            return n.contains("javacpp") ? "0" + n : "1" + n;
        }));

        logger.info("Found " + classifierJars.size() + " classifier JARs in Maven repo");
        boolean any = false;
        for (Path jar : classifierJars) {
            if (extractNativeLibsFromJar(jar, targetDir) > 0) {
                any = true;
            }
        }

        return any ? List.of(targetDir) : List.of();
    }

    // ======================== Utility ========================

    private static List<Path> findNativeLibDirectories(Path root) {
        List<Path> dirs = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 10, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isNativeLib(file.getFileName().toString())) {
                        Path parent = file.getParent();
                        if (parent != null && !dirs.contains(parent)) {
                            dirs.add(parent);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.log(Level.FINE, "Error walking " + root, e);
        }
        return dirs;
    }

    private static boolean isNativeLib(String fileName) {
        return fileName.endsWith(".so") || fileName.contains(".so.")
                || fileName.endsWith(".dylib") || fileName.endsWith(".dll");
    }

    private static boolean containsAnyNativeLib(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> isNativeLib(f.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Native-lib directories under a dist lib/ root: the root itself when the
     * layout is flat, else every nested directory holding natives (assembly
     * unpack layouts preserve jar-internal paths).
     */
    private static List<Path> libDirsUnder(Path libRoot, String label) {
        if (libRoot == null || !Files.isDirectory(libRoot)) return List.of();
        List<Path> directories = new ArrayList<>();
        if (containsAnyNativeLib(libRoot)) {
            logger.info("Using native libs from " + label + ": " + libRoot);
            directories.add(libRoot);
        }
        List<Path> nested = findNativeLibDirectories(libRoot);
        if (!nested.isEmpty()) {
            logger.info("Using native libs from " + label + " (nested, " + nested.size() + " dirs): " + libRoot);
        }
        for (Path directory : nested) {
            if (!directories.contains(directory)) {
                directories.add(directory);
            }
        }
        return directories;
    }

    private static boolean hasClassifierJarsOnClasspath() {
        String cp = System.getProperty("java.class.path", "");
        return cp.contains(PLATFORM + ".jar");
    }

    private static Path getBinaryDirectory() {
        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            if (execPath != null) {
                return Path.of(execPath).toAbsolutePath().getParent();
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path getJavaCppCacheDir() {
        // System property → env var → default
        String prop = System.getProperty("org.bytedeco.javacpp.cachedir");
        if (prop != null) return Path.of(prop);
        String env = System.getenv("JAVACPP_CACHEDIR");
        if (env != null) return Path.of(env);
        String home = System.getProperty("user.home");
        return home != null ? Path.of(home, JAVACPP_CACHE_DEFAULT) : null;
    }

    private static Path getKompileNativeLibDir() {
        // System property → env var → default
        String dataDir = System.getProperty("kompile.data.dir");
        if (dataDir == null) dataDir = System.getenv("KOMPILE_DATA_DIR");
        if (dataDir == null) dataDir = System.getProperty("user.home") + "/.kompile";
        return Path.of(dataDir, KOMPILE_NATIVE_LIBS, PLATFORM);
    }

    private static Path getMavenLocalRepo() {
        // 1. Explicit system property
        String prop = System.getProperty("maven.repo.local");
        if (prop != null && Files.isDirectory(Path.of(prop))) return Path.of(prop);

        // 2. MAVEN_REPO_LOCAL env var
        String envRepo = System.getenv("MAVEN_REPO_LOCAL");
        if (envRepo != null && Files.isDirectory(Path.of(envRepo))) return Path.of(envRepo);

        // 3. M2_HOME env var → $M2_HOME/repository
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null) {
            Path repo = Path.of(m2Home, "repository");
            if (Files.isDirectory(repo)) return repo;
        }

        // 4. MAVEN_HOME env var → $MAVEN_HOME/repository
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null) {
            Path repo = Path.of(mavenHome, "repository");
            if (Files.isDirectory(repo)) return repo;
        }

        // 5. Default: ~/.m2/repository
        String home = System.getProperty("user.home");
        if (home != null) {
            Path repo = Path.of(home, ".m2", "repository");
            if (Files.isDirectory(repo)) return repo;
        }

        return null;
    }

    private static String detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();

        String os;
        if (osName.contains("linux")) os = "linux";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "macosx";
        else if (osName.contains("win")) os = "windows";
        else os = osName.replaceAll("\\s+", "").toLowerCase();

        String arch;
        if (osArch.contains("aarch64") || osArch.contains("arm64")) arch = "arm64";
        else if (osArch.contains("amd64") || osArch.contains("x86_64")) arch = "x86_64";
        else arch = osArch;

        return os + "-" + arch;
    }
}
