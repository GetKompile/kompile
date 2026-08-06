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

package ai.kompile.app.chat;

import ai.kompile.app.runtime.KompileServerRuntime;
import ai.kompile.app.runtime.SubprocessDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Kompile <b>chat</b> process (:8081) — an end-user surface: agent chat, project browsing, and
 * read-only fact-sheet and graph exploration.
 *
 * <p><b>Why {@code scanBasePackages = "ai.kompile"} still yields a boundary.</b> Every web module
 * ({@code web-shared}, {@code web-chat}, {@code web-graph}, {@code web-crawl}, {@code web-admin})
 * publishes into the same package, {@code ai.kompile.app.web.controllers}, so narrowing the scan
 * cannot separate personas — and narrowing it further would starve the controllers of the service
 * beans they autowire. The boundary is the <b>classpath</b>: {@code kompile-app-web-admin} and
 * {@code kompile-app-web-crawl} are simply not dependencies of this module, so their handlers are
 * not present to be mapped. A {@code PersonaBoundaryTest} asserts the mounted path set in both
 * directions. See {@code docs/architecture/app-persona-boundary.md}.
 *
 * <p>Startup mirrors {@code MainApplication}: the pre-Spring native bootstrap (Lucene mmap provider,
 * native-library resolution, JavaCPP paths, ND4J backend and the persisted ND4J environment) runs
 * through {@link KompileServerRuntime} before any bean touches ND4J, and {@code --subprocess=}
 * routing goes through {@link SubprocessDispatcher}. This app launches graph-matrix and model-init
 * jobs like any other, and as a native image {@code ManagedSubprocessLauncher} runs those by
 * re-execing <i>this</i> binary — so it has to route the flag rather than boot a second web app on
 * an already-bound port. It hosts the six subprocess types web-shared carries; the four that need
 * admin-only modules stay with {@code MainApplication}.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableScheduling
public class ChatApplication {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ChatApplication() {}

    private static final Logger logger = LoggerFactory.getLogger(ChatApplication.class);

    public static void main(String[] args) throws Exception {
        // Ahead of the subprocess check: a subprocess main opens Lucene indexes too, and the mmap
        // provider has to be chosen before the first MMapDirectory either way.
        KompileServerRuntime.ensureLuceneRuntime();

        if (SubprocessDispatcher.dispatchIfRequested(args)) {
            return; // Subprocess has exited, do not start Spring Boot
        }

        KompileServerRuntime.bootstrap(args);

        try {
            SpringApplication app = new SpringApplication(ChatApplication.class);
            // ApplicationFailedEvent fires BEFORE context close — the only spot that reliably beats
            // Nd4jCleanupAndExitHandler's halt, which kills the process mid-unwind before the catch
            // below can execute.
            app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationFailedEvent>) ev -> {
                System.err.println("=== CHAT APP FAILED (pre-close event; raw stack) ===");
                if (ev.getException() != null) {
                    ev.getException().printStackTrace(System.err);
                }
                System.err.flush();
            });
            app.run(args);
            KompileServerRuntime.markStartupCompleted();
        } catch (Throwable t) {
            // The ND4J cleanup/exit shutdown handler stops logback before this catch runs, so
            // logger.error alone goes nowhere. Raw stderr survives.
            System.err.println("=== Chat application startup failed (raw stack, logging may be down) ===");
            t.printStackTrace(System.err);
            logger.error("Chat application startup failed", t);
            throw t;
        }

        logger.info("Kompile Chat is running.");
    }
}
