package ai.kompile.app.crawlmanager.boundary;

import ai.kompile.app.crawlmanager.CrawlManagerApplication;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.sync.scheduler.NoteSyncScheduler;
import ai.kompile.app.sync.service.NoteSyncRunWorker;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.source.provider.SourceProviderRegistry;
import ai.kompile.kclaw.gateway.integration.ChannelIntegrationService;
import ai.kompile.loader.email.inbox.EmailInboxLoaderImpl;
import ai.kompile.source.confluence.ConfluenceDocumentLoader;
import ai.kompile.source.jira.JiraDocumentLoader;
import ai.kompile.source.notion.NotionDocumentLoader;
import ai.kompile.source.reddit.RedditDocumentLoader;
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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the real crawl-manager persona and verifies its runtime MVC boundary. */
@SpringBootTest(
        classes = CrawlManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "kompile.runtime.force-halt-on-shutdown=false",
                "kompile.staging.auto-start=false",
                "kompile.models.auto-init.enabled=false",
                "spring.quartz.auto-startup=false",
                "spring.task.scheduling.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:kompile-crawl-context;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.main.web-application-type=servlet",
                "server.port=0"
        })
@AutoConfigureMockMvc
class CrawlManagerApplicationContextTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void realCrawlManagerApplicationContextLoads() {
        assertNotNull(applicationContext.getBean(CrawlManagerApplication.class));
        assertNotNull(applicationContext.getBean("dataSource"));
        assertNotNull(applicationContext.getBean("entityManagerFactory"));
        assertNotNull(applicationContext.getBean("transactionManager"));
        assertNotNull(applicationContext.getBean("objectMapper"));
        assertTrue(applicationContext.getBeansOfType(ChannelIntegrationService.class).isEmpty(),
                "crawl-manager must not restore admin-owned channel runtimes");
        assertNotNull(applicationContext.getBean(NoteSyncScheduler.class));
        assertNotNull(applicationContext.getBean(NoteSyncRunWorker.class));
        assertTrue(applicationContext.getBeansOfType(DocumentLoader.class).values().stream()
                        .anyMatch(ConfluenceDocumentLoader.class::isInstance),
                "crawl-manager must package the Confluence loader, not only its UI descriptor");
        assertTrue(applicationContext.getBeansOfType(DocumentLoader.class).values().stream()
                        .anyMatch(JiraDocumentLoader.class::isInstance),
                "crawl-manager must package the Jira loader, not only its UI descriptor");
        assertTrue(applicationContext.getBeansOfType(DocumentLoader.class).values().stream()
                        .anyMatch(RedditDocumentLoader.class::isInstance),
                "crawl-manager must package the Reddit loader, not only its UI descriptor");
        assertTrue(applicationContext.getBeansOfType(DocumentLoader.class).values().stream()
                        .anyMatch(NotionDocumentLoader.class::isInstance),
                "crawl-manager must package the Notion loader, not only its UI descriptor");
        SourceProviderRegistry providerRegistry = applicationContext.getBean(SourceProviderRegistry.class);
        assertNotNull(providerRegistry.getProvider("jira"));
        assertNotNull(providerRegistry.getProvider("reddit"));
        assertNotNull(providerRegistry.getProvider("notion"));
        assertTrue(applicationContext.getBeansOfType(DocumentLoader.class).values().stream()
                        .anyMatch(EmailInboxLoaderImpl.class::isInstance),
                "crawl-manager must package local MBOX/Maildir/PST/EMLX ingestion");
    }

    @Test
    void runtimeMappingsStayOnTheCrawlPersona() {
        Set<String> paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/unified-crawl")));
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/oauth")),
                "crawl-manager owns OAuth setup, callbacks, and token refresh");
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/sync")),
                "crawl-manager owns Notion, Obsidian, folder, and Git sync");
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/source-providers")),
                "crawl-manager owns live source-provider discovery");
        assertTrue(paths.contains("/api/documents/add-jira"));
        assertTrue(paths.contains("/api/documents/add-reddit"));
        assertTrue(paths.stream().anyMatch(path -> under(path, "/api/graph/aggregate")));
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/chat")));
        assertTrue(paths.stream().noneMatch(path -> under(path, "/api/tool-permissions")));
        assertTrue(paths.stream().anyMatch(path ->
                under(path, "/api/channel-integrations/browser-sessions")));
        assertTrue(paths.stream().noneMatch(path ->
                path.equals("/api/channel-integrations")),
                "crawl-manager may exchange browser auth but must not mount channel lifecycle CRUD");
    }

    @Test
    void managedPersonaPreflightAllowsCredentialedSourceRequests() throws Exception {
        mockMvc.perform(options("/api/sync/connections")
                        .header("Origin", "http://localhost:8080")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "X-Kompile-Channel-CSRF,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
