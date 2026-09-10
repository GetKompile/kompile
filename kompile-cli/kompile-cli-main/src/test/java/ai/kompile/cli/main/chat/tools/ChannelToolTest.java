package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelToolTest {

    @Test
    void schemaExposesOnlyNonSecretHarnessActions() {
        ChannelTool tool = new ChannelTool("http://localhost:8080", new ObjectMapper());
        var properties = tool.parameterSchema().path("properties");

        assertTrue(properties.has("action"));
        assertTrue(properties.has("provider"));
        assertTrue(properties.has("name"));
        assertTrue(properties.has("target"));
        assertTrue(properties.has("message"));
        assertFalse(properties.has("secrets"));
        assertFalse(properties.has("settings"));
        assertTrue(tool.mcpAnnotations().openWorldHint());
    }

    @Test
    void interactiveRegistryIncludesChannelTool() {
        ToolRegistry registry = ToolRegistryFactory.create(
                new ObjectMapper(), "http://localhost:8080", new AgentRegistry(),
                null, null, null);

        assertNotNull(registry.get("channel"));
        assertTrue(registry.getDynamicToolManager().activateGroup("integrations")
                .contains("channel"));
    }
}
