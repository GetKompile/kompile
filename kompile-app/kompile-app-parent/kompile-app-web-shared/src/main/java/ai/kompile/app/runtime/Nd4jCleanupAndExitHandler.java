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

import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Component to handle ND4J cleanup and force exit
 * Using lowest order to run last (after all other @PreDestroy)
 *
 * <p>Lives in {@code kompile-app-web-shared} so every persona process gets it. Without this the
 * surviving native OpenBLAS/MKL worker threads keep the JVM alive after Spring has stopped, and
 * the process has to be killed by hand — which would have been a new bug in the chat and
 * crawl-manager apps, since this used to be a nested class of {@code MainApplication}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class Nd4jCleanupAndExitHandler {

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

        // Use Runtime.halt(0) rather than System.exit(0) here.
        // We are already executing inside a Spring @PreDestroy callback, which itself runs
        // inside the JVM shutdown-hook sequence.  System.exit() would attempt to re-enter
        // that sequence and deadlock waiting for the same hooks to complete.  halt() bypasses
        // all pending shutdown hooks and terminates the process immediately, which is exactly
        // what we want here: all Spring beans have already been destroyed by the time this
        // handler fires (it is @Order(LOWEST_PRECEDENCE)), and the only remaining threads are
        // native OpenBLAS/MKL worker threads that never self-terminate.
        //
        // EXCEPTION — startup failure: when the context fails to refresh, this
        // @PreDestroy runs from handleRunFailure's close() BEFORE Spring's failure
        // reporter and the app's own main catch get to print the stack.
        // An immediate halt therefore hides every startup error (repeatedly cost
        // hours of native-image diagnosis). Delay the halt so the report lands;
        // still halt afterwards because the surviving native BLAS threads would
        // otherwise keep the failed process alive forever.
        if (KompileServerRuntime.isStartupCompleted()) {
            log.info("=== Cleanup complete. Terminating JVM now. ===");
            Runtime.getRuntime().halt(0);
        } else {
            log.warn("=== Cleanup complete, but startup FAILED — delaying halt 15s so the failure report can print. ===");
            Thread delayed = new Thread(() -> {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ignored) {
                }
                Runtime.getRuntime().halt(1);
            }, "delayed-halt-after-startup-failure");
            delayed.setDaemon(true);
            delayed.start();
        }
    }
}
