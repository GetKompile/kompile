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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Generalized native library extractor for GraalVM Native Image.
 * Handles extraction and loading of native libraries from classpath resources.
 */
public class NativeLibraryExtractor {
    private static final Logger logger = Logger.getLogger(NativeLibraryExtractor.class.getName());

    // Track loaded libraries to avoid double-loading
    private static final Map<String, Boolean> loadedLibraries = new ConcurrentHashMap<>();

    // Platform detection
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase();

    // Library configurations based on your detected resources
    private static final List<LibraryConfig> LIBRARY_CONFIGS = new ArrayList<>();

    static {
        // Initialize library configurations based on your runtime hints
        initializeLibraryConfigurations();
    }

    private static void initializeLibraryConfigurations() {
        // JavaCPP tokenizers library
        LIBRARY_CONFIGS.add(new LibraryConfig(
                "jnitokenizers",
                "/ai/kompile/bindings/linux-x86_64/libjnitokenizers.so",
                "linux", "x86_64"
        ));

        // Tokenizers wrapper libraries
        LIBRARY_CONFIGS.add(new LibraryConfig(
                "tokenizers_wrapper",
                "/ai/kompile/tokenizers/lib64/libtokenizers_wrapper.so",
                "linux", "x86_64"
        ));

        LIBRARY_CONFIGS.add(new LibraryConfig(
                "tokenizers_wrapper",
                "/ai/kompile/tokenizers/linux-x86_64/libtokenizers_wrapper.so",
                "linux", "x86_64"
        ));

        // Add more libraries here as needed
        // Example for other platforms:
        // LIBRARY_CONFIGS.add(new LibraryConfig(
        //     "jnitokenizers",
        //     "/ai/kompile/bindings/darwin-x86_64/libjnitokenizers.dylib",
        //     "mac", "x86_64"
        // ));
    }

    /**
     * Load all compatible native libraries for the current platform
     */
    public static void loadAllLibraries() {
        String currentPlatform = detectPlatform();
        String currentArch = detectArchitecture();

        logger.info("Loading native libraries for platform: " + currentPlatform + "-" + currentArch);

        for (LibraryConfig config : LIBRARY_CONFIGS) {
            if (config.isCompatible(currentPlatform, currentArch)) {
                try {
                    loadLibrary(config);
                } catch (Exception e) {
                    logger.warning("Failed to load library " + config.name + ": " + e.getMessage());
                    // Continue with other libraries - some may be optional
                }
            }
        }
    }

    /**
     * Load a specific library by name
     */
    public static void loadLibrary(String libraryName) {
        String currentPlatform = detectPlatform();
        String currentArch = detectArchitecture();

        for (LibraryConfig config : LIBRARY_CONFIGS) {
            if (config.name.equals(libraryName) && config.isCompatible(currentPlatform, currentArch)) {
                loadLibrary(config);
                return;
            }
        }

        throw new UnsatisfiedLinkError("No configuration found for library: " + libraryName);
    }

    private static void loadLibrary(LibraryConfig config) {
        String key = config.name + ":" + config.resourcePath;

        // Check if already loaded
        if (loadedLibraries.getOrDefault(key, false)) {
            logger.fine("Library already loaded: " + config.name);
            return;
        }

        try {
            // First try standard system loading
            try {
                System.loadLibrary(config.name);
                loadedLibraries.put(key, true);
                logger.info("Successfully loaded library from system: " + config.name);
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.fine("System loading failed for " + config.name + ", trying resource extraction");
            }

            // Extract and load from resources
            extractAndLoadFromResource(config);
            loadedLibraries.put(key, true);
            logger.info("Successfully loaded library from resources: " + config.name);

        } catch (Exception e) {
            String error = "Failed to load library " + config.name + " from " + config.resourcePath + ": " + e.getMessage();
            logger.severe(error);
            throw new UnsatisfiedLinkError(error);
        }
    }

    private static void extractAndLoadFromResource(LibraryConfig config) throws IOException {
        // Check if resource exists
        try (InputStream is = NativeLibraryExtractor.class.getResourceAsStream(config.resourcePath)) {
            if (is == null) {
                throw new UnsatisfiedLinkError("Library resource not found: " + config.resourcePath);
            }

            // Extract to executable directory so GraalVM can find it
            Path executableDir = getExecutableDirectory();
            if (executableDir == null) {
                throw new UnsatisfiedLinkError("Cannot determine executable directory");
            }

            // Create target file in executable directory
            String fileName = Paths.get(config.resourcePath).getFileName().toString();
            Path targetFile = executableDir.resolve(fileName);

            // Only extract if file doesn't exist or is different
            if (!Files.exists(targetFile)) {
                logger.fine("Extracting " + config.resourcePath + " to " + targetFile);
                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);

                // Set executable permissions on Unix-like systems
                if (!isWindows()) {
                    targetFile.toFile().setExecutable(true);
                    targetFile.toFile().setReadable(true);
                }
            }

            // Now use System.loadLibrary() - GraalVM will find it in executable directory
            System.loadLibrary(config.name);
        }
    }

    private static Path getExecutableDirectory() {
        // Get the directory containing the current running executable/JAR
        try {
            String classPath = NativeLibraryExtractor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            return Paths.get(classPath).getParent();
        } catch (Exception e) {
            // Fallback: use current working directory
            return Paths.get(".");
        }
    }

    private static String detectPlatform() {
        if (OS_NAME.contains("win")) return "windows";
        if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) return "mac";
        if (OS_NAME.contains("linux")) return "linux";
        return "unknown";
    }

    private static String detectArchitecture() {
        String arch = OS_ARCH.toLowerCase();
        if (arch.contains("64")) {
            if (arch.contains("aarch") || arch.contains("arm")) return "aarch64";
            return "x86_64";
        }
        if (arch.contains("arm")) return "arm";
        return "x86";
    }

    private static boolean isWindows() {
        return detectPlatform().equals("windows");
    }

    /**
     * Configuration for a native library
     */
    private static class LibraryConfig {
        final String name;
        final String resourcePath;
        final String platform;
        final String architecture;

        LibraryConfig(String name, String resourcePath, String platform, String architecture) {
            this.name = name;
            this.resourcePath = resourcePath;
            this.platform = platform;
            this.architecture = architecture;
        }

        boolean isCompatible(String currentPlatform, String currentArch) {
            return (platform.equals("*") || platform.equals(currentPlatform)) &&
                    (architecture.equals("*") || architecture.equals(currentArch));
        }

        @Override
        public String toString() {
            return name + " (" + platform + "-" + architecture + ") -> " + resourcePath;
        }
    }

    /**
     * Utility method to add custom library configurations at runtime
     */
    public static void addLibraryConfig(String name, String resourcePath, String platform, String architecture) {
        LIBRARY_CONFIGS.add(new LibraryConfig(name, resourcePath, platform, architecture));
    }

    /**
     * Get information about all configured libraries
     */
    public static List<String> getLibraryInfo() {
        List<String> info = new ArrayList<>();
        String currentPlatform = detectPlatform();
        String currentArch = detectArchitecture();

        info.add("Current platform: " + currentPlatform + "-" + currentArch);
        info.add("Configured libraries:");

        for (LibraryConfig config : LIBRARY_CONFIGS) {
            String status = config.isCompatible(currentPlatform, currentArch) ? "COMPATIBLE" : "INCOMPATIBLE";
            info.add("  " + config.toString() + " [" + status + "]");
        }

        return info;
    }
}