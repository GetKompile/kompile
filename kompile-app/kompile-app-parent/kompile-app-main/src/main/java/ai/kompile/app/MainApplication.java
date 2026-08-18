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
import ai.kompile.app.runtime.SubprocessDispatcher;
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
 * <p>Startup that is common to all three processes lives in {@link KompileServerRuntime}, and
 * {@code --subprocess=} routing in {@link SubprocessDispatcher}. What stays here is the part that is
 * genuinely app-main-only: the four subprocess types whose modules only this app depends on.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableConfigurationProperties({}) // Keep if other @ConfigurationProperties are used elsewhere
@EnableScheduling
@Import({OrchestratorAutoConfiguration.class, PipelineManagementAutoConfiguration.class})
public class MainApplication {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MainApplication() {}


    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    static {
        registerAppMainSubprocesses();
    }

    public static void main(String[] args) throws Exception {
        // Ahead of the subprocess check: a subprocess main opens Lucene indexes too, and the mmap
        // provider has to be chosen before the first MMapDirectory either way.
        KompileServerRuntime.ensureLuceneRuntime();

        // Route to subprocess if --subprocess=TYPE flag is present.
        // This enables the unified native executable approach: a single binary
        // that can act as the main web server OR any subprocess type.
        if (SubprocessDispatcher.dispatchIfRequested(args)) {
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
     * Contribute the subprocess types that only kompile-app-main can host.
     *
     * <p>{@link SubprocessDispatcher} already registers the six whose mains come in through
     * kompile-app-web-shared — ingest, vector-population, embedding, model-init,
     * graph-matrix — so every persona app can run those jobs from its own binary. These four are
     * the ones whose modules only this app depends on:
     *
     * <ul>
     *   <li>{@code serving} → {@link ai.kompile.app.subprocess.ServingSubprocessMain}</li>
     *   <li>{@code learning} → {@link ai.kompile.app.learning.subprocess.LearningSubprocessMain}</li>
     *   <li>{@code pipeline-serving} → {@link ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain}</li>
     *   <li>{@code training} → {@code ai.kompile.staging.subprocess.TrainingSubprocessMain}, by
     *       reflection: kompile-model-staging must NEVER become a compile dependency of
     *       kompile-app-main, so training only dispatches when staging is on a JVM classpath.</li>
     * </ul>
     *
     * <p>The first three are direct method references so GraalVM's static analysis keeps their
     * mains in the unified native image.
     */
    private static void registerAppMainSubprocesses() {
        SubprocessDispatcher.register("serving", ai.kompile.app.subprocess.ServingSubprocessMain::main);
        SubprocessDispatcher.register("learning", ai.kompile.app.learning.subprocess.LearningSubprocessMain::main);
        SubprocessDispatcher.register("pipeline-serving",
                ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain::main);
        SubprocessDispatcher.registerByReflection("training",
                "ai.kompile.staging.subprocess.TrainingSubprocessMain");
    }

    // ==================== End Subprocess Routing ====================
}
