package ai.kompile.project.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Standalone Spring Boot application that hosts Kompile projects:
 * <ul>
 *   <li>Git Smart-HTTP transport ({@code /git/{namespace}/{slug}.git}) — clone/fetch/push;</li>
 *   <li>the Hugging Face Xet content-addressed storage protocol (token + reconstruction + xorb/shard
 *       upload + global dedupe), backed by a local-filesystem blob store;</li>
 *   <li>a project REST API ({@code /api/projects}).</li>
 * </ul>
 *
 * <p>Run with {@code mvn -pl kompile-app/kompile-data/kompile-project-store-server spring-boot:run}
 * (or build a jar). Large data tracked by git-xet is materialized by clients through the Xet endpoints.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile.project.server")
@EntityScan("ai.kompile.project.server.model")
@EnableJpaRepositories("ai.kompile.project.server.model")
public class ProjectStoreServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectStoreServerApplication.class, args);
    }
}
