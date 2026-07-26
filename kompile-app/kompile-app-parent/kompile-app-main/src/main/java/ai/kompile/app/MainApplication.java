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

import ai.kompile.app.runtime.KompileServerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;
import java.util.Properties;

import ai.kompile.orchestrator.config.OrchestratorAutoConfiguration;
import ai.kompile.pipeline.management.config.PipelineManagementAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The Kompile <b>admin console</b> process (:8080) — and the host for every subprocess main.
 *
 * <p>End-user surfaces live in their own processes: {@code kompile-app-chat} (:8081) and
 * {@code kompile-app-crawl-manager} (:8082). The persona boundary is the <i>classpath</i>, not
 * {@code scanBasePackages}: all four web modules share the package
 * {@code ai.kompile.app.web.controllers}, so an app can only mount the controllers whose module it
 * depends on. See {@code docs/architecture/app-persona-boundary.md}.
 *
 * <p>Startup that is common to all three processes lives in {@link KompileServerRuntime}. What stays
 * here is the part that is genuinely app-main-only: {@code --subprocess=} dispatch, which needs the
 * subprocess mains on the classpath.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableConfigurationProperties({}) // Keep if other @ConfigurationProperties are used elsewhere
@EnableScheduling
@Import({OrchestratorAutoConfiguration.class, PipelineManagementAutoConfiguration.class})
public class MainApplication {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MainApplication() {}


    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    public static void main(String[] args) throws Exception {
        // Ahead of the subprocess check: a subprocess main opens Lucene indexes too, and the mmap
        // provider has to be chosen before the first MMapDirectory either way.
        KompileServerRuntime.ensureLuceneRuntime();

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

        // Multipart limits, log4j provider, native-library resolution, JavaCPP native-image paths,
        // ND4J backend load and the persisted ND4J environment — identical for every persona
        // process, so it lives in kompile-app-web-shared rather than being copied per app.
        KompileServerRuntime.bootstrap(args);

        ConfigurableApplicationContext context;
        try {
            SpringApplication app = new SpringApplication(MainApplication.class);
            // ApplicationFailedEvent fires BEFORE context close — the only spot
            // that reliably beats the Nd4j cleanup handler's 2s halt (which kills
            // the process mid-unwind, before the catch below can execute).
            app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationFailedEvent>) ev -> {
                System.err.println("=== APPLICATION FAILED (pre-close event; raw stack) ===");
                if (ev.getException() != null) {
                    ev.getException().printStackTrace(System.err);
                }
                System.err.flush();
            });
            context = app.run(args);
            KompileServerRuntime.markStartupCompleted();
        } catch (Throwable t) {
            // The ND4J cleanup/exit shutdown handler races Spring's own failure
            // reporter AND stops logback before this catch runs — logger.error here
            // goes nowhere. Raw stderr survives; repeatedly cost hours of
            // native-image diagnosis before this line existed.
            System.err.println("=== Application startup failed (raw stack, logging may be down) ===");
            t.printStackTrace(System.err);
            logger.error("Application startup failed", t);
            throw t;
        }
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
     *   <li>{@code serving} → {@link ai.kompile.app.subprocess.ServingSubprocessMain}</li>
     *   <li>{@code graph-matrix} → {@link ai.kompile.app.subprocess.GraphMatrixSubprocessMain}</li>
     *   <li>{@code learning} → {@link ai.kompile.app.learning.subprocess.LearningSubprocessMain}</li>
     *   <li>{@code pipeline-serving} → {@link ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain}</li>
     *   <li>{@code training} → ai.kompile.staging.subprocess.TrainingSubprocessMain (via reflection;
     *       kompile-model-staging must NEVER become a compile dependency of kompile-app-main)</li>
     * </ul>
     *
     * @param type the subprocess type (kebab-case)
     * @param args remaining arguments to forward
     */
    private static void dispatchSubprocess(String type, String[] args) throws Exception {
        // Configure JavaCPP for native image before any ND4J usage in subprocesses
        KompileServerRuntime.configureJavaCppForNativeImage();

        // Log4j bypass for native image subprocesses too
        System.setProperty("log4j.provider", "org.apache.logging.slf4j.SLF4JProvider");

        logger.info("Dispatching to subprocess: {} with {} args", type, args.length);

        // Map subprocess type to its main class.
        // Direct static references are used for all compile-time dependencies.
        // Only "training" uses reflection since kompile-model-staging must NEVER be a
        // compile dependency of kompile-app-main (hard project mandate).
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
            case "serving":
                ai.kompile.app.subprocess.ServingSubprocessMain.main(args);
                break;
            case "graph-matrix":
                ai.kompile.app.subprocess.GraphMatrixSubprocessMain.main(args);
                break;
            case "learning":
                ai.kompile.app.learning.subprocess.LearningSubprocessMain.main(args);
                break;
            case "pipeline-serving":
                ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain.main(args);
                break;
            case "training":
                // kompile-model-staging must NEVER be a compile dependency of kompile-app-main;
                // training dispatch only works when staging is on a JVM classpath.
                invokeSubprocessMainByReflection("ai.kompile.staging.subprocess.TrainingSubprocessMain", args);
                break;
            default:
                logger.error("Unknown subprocess type: '{}'. Supported types: ingest, vector-population, "
                        + "embedding, model-init, vlm-test, serving, graph-matrix, learning, "
                        + "pipeline-serving, training", type);
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
}
