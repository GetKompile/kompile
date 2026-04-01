/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Shared ND4J startup utilities used by both MainApplication and LiteApplication.
 * Handles JavaCPP configuration for native image mode, subprocess routing helpers,
 * and Log4j bridging setup.
 */
public final class Nd4jStartup {

    private static final Logger logger = LoggerFactory.getLogger(Nd4jStartup.class);
    private static final String SUBPROCESS_FLAG = "--subprocess=";

    private Nd4jStartup() {}

    /**
     * Configure JavaCPP properties for GraalVM native image mode.
     * In native image mode, JavaCPP uses the same directory as the binary
     * for its native library cache. Native libraries (libnd4jcpu.so, etc.)
     * must be placed alongside the binary.
     *
     * This MUST be called before any ND4J/JavaCPP class initialization.
     */
    public static void configureJavaCppForNativeImage() {
        boolean isNativeImage = false;
        String execPath = null;
        try {
            Class<?> imageInfoClass = Class.forName("org.graalvm.nativeimage.ImageInfo");
            java.lang.reflect.Method inImageCode = imageInfoClass.getMethod("inImageCode");
            isNativeImage = (Boolean) inImageCode.invoke(null);
            if (isNativeImage) {
                try {
                    java.lang.reflect.Method getExecPath = imageInfoClass.getMethod("getExecutableName");
                    execPath = (String) getExecPath.invoke(null);
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException ignored) {
            // Not in a native image
        } catch (Exception e) {
            logger.debug("Error checking native image status: {}", e.getMessage());
        }

        if (isNativeImage) {
            logger.info("Running as GraalVM native image - configuring JavaCPP for native mode");

            Path binaryDir;
            if (execPath != null) {
                binaryDir = Paths.get(execPath).toAbsolutePath().getParent();
            } else {
                binaryDir = Paths.get(".").toAbsolutePath();
                logger.warn("Could not determine native executable path, using CWD: {}", binaryDir);
            }

            System.setProperty("org.bytedeco.javacpp.cachedir", binaryDir.toString());
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            String existingLibPath = System.getProperty("java.library.path", "");
            if (!existingLibPath.contains(binaryDir.toString())) {
                String newLibPath = binaryDir.toString() +
                        (existingLibPath.isEmpty() ? "" : ":" + existingLibPath);
                System.setProperty("java.library.path", newLibPath);
            }

            System.setProperty("org.bytedeco.javacpp.platform.resourcedir", binaryDir.toString());

            logger.info("JavaCPP native image config: cachedir={}, pathsFirst=true", binaryDir);

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
     * Set up Log4j2 to SLF4J bridging for native image compatibility.
     */
    public static void configureLog4jBridge() {
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");
    }

    /**
     * Initialize ND4J backend and apply persisted environment configuration.
     * Should be called before Spring context starts.
     */
    public static void initializeNd4j() {
        if (Boolean.getBoolean("spring.aot.processing")) {
            logger.info("Spring AOT processing mode - skipping ND4J initialization");
            return;
        }

        try {
            org.nd4j.linalg.factory.Nd4j.getEnvironment().setDebug(true);
            org.nd4j.linalg.factory.Nd4j.getEnvironment().setVerbose(true);

            org.nd4j.imports.converters.DifferentialFunctionClassHolder.initInstance();

            org.nd4j.linalg.factory.Nd4jBackend backend = org.nd4j.linalg.factory.Nd4jBackend.load();
            org.nd4j.linalg.factory.Nd4j.backend = backend;
            logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

            org.nd4j.nativeblas.NativeOps nativeOps = org.nd4j.nativeblas.NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.initializeDevicesAndFunctions();

            logger.info("ND4J initialized successfully");
        } catch (Throwable e) {
            logger.warn("ND4J initialization failed (backend may not be available). " +
                    "Embedding operations will use subprocess mode. Error: {}", e.getMessage());
        }
    }

    /**
     * Extracts the subprocess type from command-line arguments.
     *
     * @param args command-line arguments
     * @return the subprocess type string (e.g. "ingest", "embedding"), or null if not a subprocess invocation
     */
    public static String extractSubprocessType(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(SUBPROCESS_FLAG)) {
                return arg.substring(SUBPROCESS_FLAG.length()).trim().toLowerCase();
            }
        }
        return null;
    }

    /**
     * Strips the --subprocess= flag from args, returning the remaining arguments.
     */
    public static String[] stripSubprocessFlag(String[] args) {
        return Arrays.stream(args)
                .filter(a -> !a.startsWith(SUBPROCESS_FLAG))
                .toArray(String[]::new);
    }
}
