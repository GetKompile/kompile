package ai.kompile.app.chat.boundary;

import ai.kompile.app.chat.ChatApplication;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.sync.scheduler.NoteSyncScheduler;
import ai.kompile.app.sync.service.NoteSyncRunWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Chat persona instead of only scanning its controller classpath.
 *
 * <p>The static boundary test proves which classes are present. This test proves that the same
 * classpath can satisfy Spring's constructor, repository, MVC, and shared bootstrap contracts.</p>
 */
@SpringBootTest(
        classes = ChatApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "kompile.runtime.force-halt-on-shutdown=false",
                "kompile.staging.auto-start=false",
                "kompile.models.auto-init.enabled=false",
                "spring.quartz.auto-startup=false",
                "spring.task.scheduling.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:kompile-chat-context;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.main.web-application-type=servlet",
                "server.port=0"
        })
@AutoConfigureMockMvc
class ChatApplicationContextTest {

    @TempDir
    static Path tempProject;

    @DynamicPropertySource
    static void isolatedProject(DynamicPropertyRegistry registry) {
        registry.add("kompile.data.dir", () -> tempProject.toString());
        registry.add("kompile.project.root", () -> tempProject.toString());
    }

    @MockBean
    private SubprocessConfigService subprocessConfigService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void realChatApplicationContextLoads() {
        assertNotNull(applicationContext.getBean(ChatApplication.class));
        assertNotNull(applicationContext.getBean("dataSource"));
        assertNotNull(applicationContext.getBean("entityManagerFactory"));
        assertNotNull(applicationContext.getBean("transactionManager"));
        assertNotNull(applicationContext.getBean("objectMapper"));
        assertTrue(applicationContext.getBeansOfType(NoteSyncScheduler.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(NoteSyncRunWorker.class).isEmpty());
    }

    @Test
    void runtimeMappingsStayOnTheChatPersona() {
        Set<String> paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/chat")));
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/graph/aggregate")));
        assertTrue(paths.stream().anyMatch(path ->
                under(path, "/api/channel-integrations/browser-sessions")));
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/oauth")),
                "source OAuth is owned by crawl-manager, not chat");
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/sync")),
                "source sync is owned by crawl-manager, not chat");
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/source-providers")),
                "source discovery is owned by crawl-manager, not chat");
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/unified-crawl")));
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/tool-permissions")));
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/agent-bundles")));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
