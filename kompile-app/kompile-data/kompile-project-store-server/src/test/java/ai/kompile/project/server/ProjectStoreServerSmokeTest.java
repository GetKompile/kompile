package ai.kompile.project.server;

import ai.kompile.project.server.git.GitRepoService;
import ai.kompile.project.server.model.ProjectRepository;
import ai.kompile.project.server.xet.XetCasService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the full Spring application (H2 + JPA + controllers + services) to validate wiring: CDI/bean
 * graph, entity scan, repositories, and the Xet/Git components all come up cleanly.
 */
@SpringBootTest
class ProjectStoreServerSmokeTest {

    @Autowired
    XetCasService xetCasService;

    @Autowired
    GitRepoService gitRepoService;

    @Autowired
    ProjectRepository projectRepository;

    @Test
    void contextLoads() {
        assertNotNull(xetCasService);
        assertNotNull(gitRepoService);
        assertNotNull(projectRepository);
    }
}
