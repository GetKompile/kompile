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

package ai.kompile.app.crawlmanager;

import ai.kompile.app.runtime.KompileServerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Kompile <b>crawl manager</b> process (:8082) — an end-user surface: the unified crawl UI,
 * ingestion and indexing, fact sheets, the documents and index browser, note sync / connections,
 * and read-only graph viewing.
 *
 * <p><b>Why {@code scanBasePackages = "ai.kompile"} still yields a boundary.</b> Every web module
 * ({@code web-shared}, {@code web-chat}, {@code web-graph}, {@code web-crawl}, {@code web-admin})
 * publishes into the same package, {@code ai.kompile.app.web.controllers}, so narrowing the scan
 * cannot separate personas — and narrowing it further would starve the controllers of the service
 * beans they autowire. The boundary is the <b>classpath</b>: {@code kompile-app-web-admin} and
 * {@code kompile-app-web-chat} are simply not dependencies of this module, so their handlers are
 * not present to be mapped. A {@code PersonaBoundaryTest} asserts the mounted path set in both
 * directions. See {@code docs/architecture/app-persona-boundary.md}.
 *
 * <p>{@link org.springframework.scheduling.annotation.EnableScheduling} is load-bearing here rather
 * than decorative: crawl schedules ({@code /api/schedules}) are driven by Spring's scheduler.
 *
 * <p>Startup mirrors {@code MainApplication}: the pre-Spring native bootstrap (Lucene mmap provider,
 * native-library resolution, JavaCPP paths, ND4J backend and the persisted ND4J environment) runs
 * through {@link KompileServerRuntime} before any bean touches ND4J. What this app deliberately does
 * NOT carry is subprocess dispatch — only app-main has the subprocess mains on its classpath, so a
 * crawl step that needs one launches it through app-main's installed server binary as before.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableScheduling
public class CrawlManagerApplication {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected CrawlManagerApplication() {}

    private static final Logger logger = LoggerFactory.getLogger(CrawlManagerApplication.class);

    public static void main(String[] args) {
        KompileServerRuntime.ensureLuceneRuntime();
        KompileServerRuntime.bootstrap(args);

        try {
            SpringApplication app = new SpringApplication(CrawlManagerApplication.class);
            // ApplicationFailedEvent fires BEFORE context close — the only spot that reliably beats
            // Nd4jCleanupAndExitHandler's halt, which kills the process mid-unwind before the catch
            // below can execute.
            app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationFailedEvent>) ev -> {
                System.err.println("=== CRAWL MANAGER FAILED (pre-close event; raw stack) ===");
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
            System.err.println("=== Crawl manager startup failed (raw stack, logging may be down) ===");
            t.printStackTrace(System.err);
            logger.error("Crawl manager startup failed", t);
            throw t;
        }

        logger.info("Kompile Crawl Manager is running.");
    }
}
