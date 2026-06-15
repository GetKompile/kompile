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
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared utility for building a comprehensive classpath string for subprocess launching.
 *
 * <p>When kompile runs from a Spring Boot fat JAR ({@code java -jar app.jar}), the
 * {@code java.class.path} system property only contains the fat JAR itself.  Passing
 * that to a subprocess via {@code java -cp <classpath>} fails because all application
 * classes are nested under {@code BOOT-INF/}.  This utility handles:</p>
 * <ol>
 *   <li>Collecting entries from {@code java.class.path}</li>
 *   <li>Walking the {@code URLClassLoader} hierarchy (including Spring Boot classloaders
 *       accessed via reflection)</li>
 *   <li>Adding {@code target/classes} directories for IDE / Maven dev-mode runs</li>
 *   <li>Detecting Spring Boot fat JARs and extracting their {@code BOOT-INF/classes/}
 *       and {@code BOOT-INF/lib/*.jar} contents to a sibling
 *       {@code .boot-inf-extracted/} directory</li>
 * </ol>
 */
public final class SubprocessClasspathBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessClasspathBuilder.class);

    private SubprocessClasspathBuilder() {}

    /**
     * Build a comprehensive classpath string suitable for passing to a subprocess via
     * {@code java -cp <result> MainClass}.
     *
     * <p>Handles plain JVM runs, Spring Boot fat-JAR runs, and IDE / Maven dev-mode
     * runs transparently.</p>
     *
     * @return path-separator–delimited classpath string
     */
    public static String buildClasspath() {
        Set<String> classpathEntries = new LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        // 1. Start with java.class.path (may be incomplete under Spring Boot)
        String systemClasspath = System.getProperty("java.class.path");
        if (systemClasspath != null && !systemClasspath.isBlank()) {
            for (String entry : systemClasspath.split(pathSeparator)) {
                if (!entry.isBlank()) {
                    classpathEntries.add(entry);
                }
            }
        }

        // 2. Extract URLs from classloader hierarchy (handles Spring Boot classloaders)
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = SubprocessClasspathBuilder.class.getClassLoader();
        }

        while (classLoader != null) {
            if (classLoader instanceof URLClassLoader urlClassLoader) {
                for (java.net.URL url : urlClassLoader.getURLs()) {
                    try {
                        String path = url.toURI().getPath();
                        if (path != null && !path.isBlank()) {
                            classpathEntries.add(path);
                        }
                    } catch (Exception e) {
                        String urlStr = url.toString();
                        if (urlStr.startsWith("file:")) {
                            classpathEntries.add(urlStr.substring(5));
                        }
                    }
                }
            }

            // Check for Spring Boot's specialized classloaders via reflection
            // (RestartClassLoader, LaunchedURLClassLoader both expose getURLs())
            try {
                java.lang.reflect.Method getUrlsMethod = classLoader.getClass().getMethod("getURLs");
                Object result = getUrlsMethod.invoke(classLoader);
                if (result instanceof java.net.URL[] urls) {
                    for (java.net.URL url : urls) {
                        try {
                            String path = url.toURI().getPath();
                            if (path != null && !path.isBlank()) {
                                classpathEntries.add(path);
                            }
                        } catch (Exception e) {
                            // Ignore individual URL conversion failures
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // Classloader doesn't have getURLs method, skip
            } catch (Exception e) {
                logger.debug("Error extracting URLs from classloader {}: {}",
                        classLoader.getClass().getName(), e.getMessage());
            }

            classLoader = classLoader.getParent();
        }

        // 3. Check for target/classes directories (IDE/Maven runs)
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            String[] possibleClassDirs = {
                    userDir + "/target/classes",
                    userDir + "/target/test-classes",
                    userDir + "/../kompile-ocr-models/target/classes",
                    userDir + "/../kompile-ocr-integration/target/classes",
                    userDir + "/../kompile-model-manager/target/classes",
                    userDir + "/../kompile-app-core/target/classes"
            };

            for (String dir : possibleClassDirs) {
                Path dirPath = Path.of(dir).normalize();
                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    classpathEntries.add(dirPath.toString());
                    logger.debug("Added target/classes directory to classpath: {}", dirPath);
                }
            }
        }

        // 4. Handle Spring Boot fat JAR: extract BOOT-INF/lib and BOOT-INF/classes
        // When running via 'java -jar app.jar', the classpath only contains the fat JAR.
        // A subprocess can't use a Spring Boot fat JAR with -cp because classes are under
        // BOOT-INF/. We need to extract the nested JARs as classpath entries.
        Set<String> fatJarExpanded = new LinkedHashSet<>();
        for (String entry : classpathEntries) {
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
            // Replace the fat JAR entry with the expanded entries
            classpathEntries.addAll(fatJarExpanded);
            logger.info("Added {} BOOT-INF entries from fat JAR to classpath", fatJarExpanded.size());
        }

        logger.info("Built subprocess classpath with {} entries from classloader hierarchy", classpathEntries.size());
        return String.join(pathSeparator, classpathEntries);
    }

    /**
     * Check if a JAR file is a Spring Boot fat JAR by looking for {@code BOOT-INF/lib/}
     * or {@code BOOT-INF/classes/} entries.
     *
     * @param jarPath absolute path to the JAR file to inspect
     * @return {@code true} if the JAR contains Spring Boot fat-JAR structure
     */
    public static boolean isSpringBootFatJar(String jarPath) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            return jarFile.getEntry("BOOT-INF/lib/") != null
                    || jarFile.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract {@code BOOT-INF/lib/*.jar} and {@code BOOT-INF/classes/} from a Spring
     * Boot fat JAR into a sibling {@code .boot-inf-extracted/} directory, adding the
     * resulting paths to {@code outputEntries}.
     *
     * <p>Already-extracted files are skipped if they exist and have the same size /
     * modification time, so repeated calls are cheap.</p>
     *
     * @param fatJarPath   absolute path to the fat JAR
     * @param outputEntries set to which extracted classpath entries are added
     * @throws IOException if extraction fails
     */
    public static void extractBootInfClasspath(String fatJarPath, Set<String> outputEntries) throws IOException {
        Path fatJar = Path.of(fatJarPath).toAbsolutePath();
        // Create extraction directory next to the fat JAR
        Path extractDir = fatJar.getParent().resolve(".boot-inf-extracted");
        Path libDir = extractDir.resolve("lib");
        Path classesDir = extractDir.resolve("classes");

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(fatJarPath)) {
            // Extract BOOT-INF/classes/ if present
            if (jarFile.getEntry("BOOT-INF/classes/") != null) {
                Files.createDirectories(classesDir);
                java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/classes/") && !entry.isDirectory()) {
                        String relativePath = entry.getName().substring("BOOT-INF/classes/".length());
                        Path targetFile = classesDir.resolve(relativePath);
                        Files.createDirectories(targetFile.getParent());
                        if (!Files.exists(targetFile)
                                || Files.getLastModifiedTime(targetFile).toMillis() < entry.getTime()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
                outputEntries.add(classesDir.toString());
            }

            // Extract BOOT-INF/lib/*.jar
            if (jarFile.getEntry("BOOT-INF/lib/") != null) {
                Files.createDirectories(libDir);
                java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                        String jarName = entry.getName().substring("BOOT-INF/lib/".length());
                        Path targetJar = libDir.resolve(jarName);
                        if (!Files.exists(targetJar) || Files.size(targetJar) != entry.getSize()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        outputEntries.add(targetJar.toString());
                    }
                }
            }
        }
        logger.info("Extracted BOOT-INF entries to {}", extractDir);
    }
}
