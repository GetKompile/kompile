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

import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.app.subprocess.IngestSubprocessMain;
import ai.kompile.app.subprocess.VectorPopulationSubprocessMain;
import ai.kompile.app.subprocess.model.ModelInitSubprocessMain;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code --subprocess=<type>} routing, shared by every Kompile Boot app.
 *
 * <p>{@code ManagedSubprocessLauncher} forks a job one of two ways. On a JVM it runs
 * {@code java -cp <expanded classpath> <MainClass>}. Inside a GraalVM native image there is no
 * classpath to fork from, so it re-execs <i>the running binary</i> with
 * {@code --subprocess=<type>} and expects the binary to route to that job's main instead of
 * booting a web server. That contract has to hold for whichever app spawned the job — chat and
 * crawl-manager launch graph-matrix and model-init work exactly like admin does — so the routing
 * table lives here in {@code kompile-app-web-shared}, not in one app's {@code main()}.
 *
 * <p>The six types below are the ones whose mains are on <i>this module's</i> classpath, so they
 * are registered as direct method references: GraalVM's static analysis follows them and keeps
 * the mains in the image. An app that carries additional subprocess modules adds them at class-init
 * with {@link #register} (also a direct reference, also reachable) or, when the module must not
 * become a compile dependency, with {@link #registerByReflection} — that is how
 * {@code kompile-app-main} contributes serving, learning, pipeline-serving and training without
 * kompile-model-staging ever entering its dependency graph.
 */
public final class SubprocessDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessDispatcher.class);

    /** Subprocess type flag prefix. Must match {@code SubprocessExecutableConfig.subprocessTypeFlag}. */
    public static final String SUBPROCESS_FLAG = "--subprocess=";

    /** A subprocess entry point. Mirrors {@code main(String[])}, including its checked exceptions. */
    @FunctionalInterface
    public interface SubprocessMain {
        void run(String[] args) throws Exception;
    }

    private static final Map<String, SubprocessMain> REGISTRY = new LinkedHashMap<>();

    static {
        // Mains reachable from kompile-app-web-shared — available to all three apps.
        register("ingest", IngestSubprocessMain::main);
        register("vector-population", VectorPopulationSubprocessMain::main);
        register("embedding", EmbeddingSubprocessMain::main);
        register("model-init", ModelInitSubprocessMain::main);
        register("graph-matrix", GraphMatrixSubprocessMain::main);
    }

    private SubprocessDispatcher() {}

    /**
     * Register a subprocess main. Call from an app's static initialiser, before {@code main()}
     * reaches {@link #dispatchIfRequested}. Re-registering a type replaces the previous entry.
     */
    public static synchronized void register(String type, SubprocessMain main) {
        REGISTRY.put(normalize(type), main);
    }

    /**
     * Register a subprocess main that is resolved by name at dispatch time, for modules that must
     * not be compile dependencies of the registering app. The class is not loaded until the type is
     * actually dispatched, so registering a type whose module is absent costs nothing until someone
     * asks for it — at which point the failure names the missing class.
     */
    public static synchronized void registerByReflection(String type, String className) {
        register(type, args -> {
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Subprocess class not available: " + className
                        + ". Ensure the module is on the classpath.", e);
            }
            clazz.getMethod("main", String[].class).invoke(null, (Object) args);
        });
    }

    /** The subprocess types this process can host, in registration order. */
    public static synchronized Set<String> registeredTypes() {
        return Collections.unmodifiableSet(new LinkedHashMap<>(REGISTRY).keySet());
    }

    /**
     * Extract the subprocess type from command-line arguments.
     *
     * @return the type (e.g. {@code "ingest"}), or {@code null} when this is a normal app start
     */
    public static String extractType(String[] args) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith(SUBPROCESS_FLAG)) {
                return normalize(arg.substring(SUBPROCESS_FLAG.length()));
            }
        }
        return null;
    }

    /** Strip {@code --subprocess=} from args, leaving what the subprocess main should receive. */
    public static String[] stripFlag(String[] args) {
        if (args == null) {
            return new String[0];
        }
        return Arrays.stream(args)
                .filter(a -> a == null || !a.startsWith(SUBPROCESS_FLAG))
                .toArray(String[]::new);
    }

    /**
     * Run the requested subprocess, if these args request one.
     *
     * <p>Returns {@code true} when a subprocess ran and the caller must return from {@code main()}
     * <i>without</i> starting Spring — the process has already done its job. Returns {@code false}
     * when this is an ordinary app start.
     *
     * <p>An unknown type exits non-zero rather than falling through to a web server: falling
     * through would boot a second app on an already-bound port and report success while the job it
     * was asked to run never happened.
     */
    public static boolean dispatchIfRequested(String[] args) throws Exception {
        String type = extractType(args);
        if (type == null) {
            return false;
        }

        // JavaCPP has to be pointed at the extracted native libraries before any ND4J use, and the
        // log4j provider bypass has to be set before the first logger binding — both apply to a
        // subprocess exactly as they do to a server start.
        KompileServerRuntime.configureJavaCppForNativeImage();
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");

        String[] forwardArgs = stripFlag(args);
        SubprocessMain main;
        synchronized (SubprocessDispatcher.class) {
            main = REGISTRY.get(type);
        }
        if (main == null) {
            logger.error("Unknown subprocess type: '{}'. This process hosts: {}", type, registeredTypes());
            System.exit(1);
            return true;
        }

        logger.info("Dispatching to subprocess: {} with {} args", type, forwardArgs.length);
        main.run(forwardArgs);
        return true;
    }

    private static String normalize(String type) {
        return type == null ? null : type.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
