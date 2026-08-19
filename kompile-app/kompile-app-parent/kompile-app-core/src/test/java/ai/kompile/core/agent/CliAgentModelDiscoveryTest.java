package ai.kompile.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAgentModelDiscoveryTest {

    @Test
    void keepsModelIdsOpaqueAndRejectsCliNoise() {
        assertEquals("provider/model-with:punctuation",
                CliAgentModelDiscovery.parseModelListLine("  provider/model-with:punctuation  ").orElseThrow());
        assertEquals("provider/model",
                CliAgentModelDiscovery.parseModelListLine("- provider/model").orElseThrow());
        assertTrue(CliAgentModelDiscovery.parseModelListLine("Available models").isEmpty());
        assertTrue(CliAgentModelDiscovery.parseModelListLine("provider:").isEmpty());
        assertTrue(CliAgentModelDiscovery.parseModelListLine("not a model label").isEmpty());
    }

    @Test
    void usesTheRegistryCommandWithoutAStaticCatalog() {
        AgentProvider provider = AgentProvider.builder()
                .name("test-agent")
                .command("test-agent")
                .modelListCommand(List.of("sh", "-c",
                        "printf '%s\\n' provider/model-a provider/model-b"))
                .build();

        assertEquals(List.of("provider/model-a", "provider/model-b"),
                CliAgentModelDiscovery.discover(provider));
    }
}
