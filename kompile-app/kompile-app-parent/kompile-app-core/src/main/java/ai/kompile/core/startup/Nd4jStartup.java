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

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.utils.NativeImageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Configure JavaCPP properties before native-image code initializes ND4J.
     * The canonical resolver uses {@link NativeImageInfo} to select a distribution's
     * sibling {@code lib/} directory and publishes the exact trusted compiler-runtime
     * path. Keeping that decision centralized prevents a later startup path from
     * accidentally rebasing JavaCPP onto {@code bin/}.
     */
    public static void configureJavaCppForNativeImage() {
        if (!NativeImageInfo.isRunningInNativeImage()) {
            logger.debug("Running in JVM mode - using default JavaCPP configuration");
            return;
        }

        NativeLibraryResolver.bootstrapOrThrow();
        logger.info("JavaCPP native image config: cachedir={}, sharedRuntimePath={}, pathsFirst={}",
                System.getProperty("org.bytedeco.javacpp.cachedir"),
                System.getProperty("org.nd4j.presets.sharedRuntimePath"),
                System.getProperty("org.bytedeco.javacpp.pathsFirst"));
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
            // ND4J debug/verbose emit per-op native [DSP_DIAG]/KernelDispatch traces — ~969k lines per crawl
            // (45% of the log), drowning real activity and adding ~10s/embed. Default OFF; opt-in for diagnosis
            // via -Dkompile.nd4j.debug=true / -Dkompile.nd4j.verbose=true.
            boolean nd4jDebug = Boolean.getBoolean("kompile.nd4j.debug");
            boolean nd4jVerbose = Boolean.getBoolean("kompile.nd4j.verbose");
            org.nd4j.linalg.factory.Nd4j.getEnvironment().setDebug(nd4jDebug);
            org.nd4j.linalg.factory.Nd4j.getEnvironment().setVerbose(nd4jVerbose);
            if (nd4jDebug || nd4jVerbose) {
                logger.warn("ND4J debug={} verbose={} ENABLED — expect heavy per-op [DSP_DIAG] log volume",
                        nd4jDebug, nd4jVerbose);
            }

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
