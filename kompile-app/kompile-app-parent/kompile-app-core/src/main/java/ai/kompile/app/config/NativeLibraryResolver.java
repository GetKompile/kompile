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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
 * <b>Native image path:</b> No classpath at runtime, so resolution uses the
 * filesystem:
 * <ol>
 *   <li>{@code KOMPILE_NATIVE_LIB_DIR} env var — explicit override</li>
 *   <li>{@code <binary-dir>/lib/} — assembly created at build time</li>
 *   <li>{@code ~/.javacpp/cache/} — populated by prior JVM-mode runs</li>
 *   <li>{@code ~/.kompile/native-libs/<platform>/} — kompile extraction cache</li>
 *   <li>On-demand scan of {@code ~/.m2/repository/} for classifier JARs</li>
 * </ol>
 */
public class NativeLibraryResolver {

    private static final Logger logger = Logger.getLogger(NativeLibraryResolver.class.getName());

    /** Platform string in JavaCPP format (e.g. "linux-x86_64"). */
    private static final String PLATFORM = detectPlatform();

    /** Default JavaCPP cache directory under $HOME. */
    private static final String JAVACPP_CACHE_DEFAULT = ".javacpp/cache";

    /** Kompile cache subdirectory under ~/.kompile/ */
    private static final String KOMPILE_NATIVE_LIBS = "native-libs";

    private NativeLibraryResolver() {}

    // ======================== Public API ========================

    /**
     * Full bootstrap: resolve native libs + configure JavaCPP properties.
     * Call from MainApplication.main() BEFORE any ND4J/JavaCPP class init.
     *
     * @return true if native libraries are available (found or not needed)
     */
    public static boolean bootstrap() {
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
        configureJavaCpp(libDirs);
        return true;
    }

    /**
     * Resolves native library directories from the filesystem.
     * Used for native image mode or when classifier JARs aren't on classpath.
     */
    public static List<Path> resolve() {
        // 1. Explicit override
        String envDir = System.getenv("KOMPILE_NATIVE_LIB_DIR");
        if (envDir != null && !envDir.isBlank()) {
            Path envPath = Path.of(envDir);
            if (containsAnyNativeLib(envPath)) {
                logger.info("Using native libs from KOMPILE_NATIVE_LIB_DIR: " + envPath);
                return List.of(envPath);
            }
            logger.warning("KOMPILE_NATIVE_LIB_DIR=" + envPath + " but no native libs found there");
        }

        // 2. Adjacent lib/ directory (assembly layout)
        //    Checks both <binary-dir>/lib/ and <binary-dir>/../lib/
        //    so the dist layout (bin/kompile-server + lib/) works.
        Path binaryDir = getBinaryDirectory();
        if (binaryDir != null) {
            Path adjacentLib = binaryDir.resolve("lib");
            if (containsAnyNativeLib(adjacentLib)) {
                logger.info("Using native libs from adjacent lib/: " + adjacentLib);
                return List.of(adjacentLib);
            }
            Path parentLib = binaryDir.getParent() != null ? binaryDir.getParent().resolve("lib") : null;
            if (parentLib != null && !parentLib.equals(adjacentLib) && containsAnyNativeLib(parentLib)) {
                logger.info("Using native libs from dist lib/: " + parentLib);
                return List.of(parentLib);
            }
        }

        // 3. JavaCPP cache
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

        logger.warning("No native libraries found. Options:\n"
                + "  1. Set KOMPILE_NATIVE_LIB_DIR=/path/to/libs\n"
                + "  2. Place lib/ directory next to the binary\n"
                + "  3. Run once in JVM mode to populate ~/.javacpp/cache/");
        return List.of();
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

        String existing = System.getProperty("java.library.path", "");
        if (!existing.contains(pathStr)) {
            String newPath = pathStr + (existing.isEmpty() ? "" : File.pathSeparator + existing);
            System.setProperty("java.library.path", newPath);
        }

        if (libDirs.size() == 1) {
            System.setProperty("org.bytedeco.javacpp.cachedir",
                    libDirs.get(0).toAbsolutePath().toString());
        }

        logger.info("JavaCPP configured: pathsFirst=true, library.path=" + pathStr);
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
