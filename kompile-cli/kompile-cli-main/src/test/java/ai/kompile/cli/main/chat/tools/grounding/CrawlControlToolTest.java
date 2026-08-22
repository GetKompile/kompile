package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlControlToolTest {
    @TempDir
    Path projectRoot;

    @Test
    void strictLocalJobIdRoutesToDurableTranscriptBeforeConfiguredManager() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String jobId = LocalCrawlJobRegistry.newJobId();
        LocalCrawlJobStore.initialize(projectRoot, jobId, "notes", mapper.createObjectNode());

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        CrawlControlTool tool = new CrawlControlTool(
                new GroundingBackendClient("http://crawl", restTemplate), mapper);
        ToolContext context = context(mapper, "crawl_control");
        ObjectNode params = mapper.createObjectNode()
                .put("operation", "transcript")
                .put("jobId", jobId);

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains(jobId), result.getOutput());
        server.verify();
    }

    private ToolContext context(ObjectMapper mapper, String tool) {
        PermissionService permissions = new PermissionService();
        AgentConfig agent = AgentConfig.builder("tester").enabledTools(Set.of(tool)).build();
        return new ToolContext("local-routing-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
    }
}
