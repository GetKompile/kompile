package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class DirectSubagentResumeRoutingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static class Captured extends RuntimeException { }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "model", "auth"})
    void explicitNewTargetNeverInheritsSavedProviderModelOrAuth(String selector) throws Exception {
        String id = "subagent-route-" + selector;
        Path directory = KompileHome.homeDirectory().toPath().resolve("conversations");
        var service = new SubagentResumeService(directory, MAPPER);
        service.writeMetadata(SubagentResumeMetadata.create(id, null, "retired-agent", "old-parent", null, "claude-old")
                .withRoute("anthropic", "api-key"));
        var ledger = new SubagentLedger(SubagentLedger.fileFor(directory, id), MAPPER);
        try {
            ledger.open();
            ledger.append(CompactionService.ConversationEntry.user("old task"));
            ledger.append(CompactionService.ConversationEntry.assistant("old answer"));
        } finally { ledger.close(); }
        var config = new ChatConfig();
        config.setProvider("openai");
        config.setModel("caller-model");
        config.setBaseUrl("http://127.0.0.1:1");
        var permissions = new PermissionService();
        permissions.allowAll();
        var tools = new ToolRegistry(MAPPER);
        AtomicReference<ChatConfig> selected = new AtomicReference<>();
        var runner = new DirectSubagentRunner(config, MAPPER, tools, permissions, new TerminalRenderer(false)) {
            @Override protected DirectLlmClient createClient(ChatConfig target, ToolContext context) {
                selected.set(target);
                throw new Captured(); // Inspect routing before credentials or transport are touched.
            }
        };
        var target = AgentConfig.builder("new-agent").isSubagent(true);
        switch (selector) {
            case "provider" -> target.providerOverride("openai");
            case "model" -> target.modelOverride("replacement-model");
            case "auth" -> target.authenticationMethodOverride("native");
        }
        var context = new ToolContext("new-parent", AgentConfig.builder("parent").build(), permissions, directory, tools);
        assertThrows(Captured.class, () -> runner.resumeSubagent(target.build(), id, "new task", context));
        assertEquals("openai", selected.get().getProvider());
        assertEquals("model".equals(selector) ? "replacement-model" : "caller-model", selected.get().getModel());
        assertNotEquals("api-key", selected.get().getAuthenticationMethod(), "old provider's auth must not leak");
        assertFalse(service.isLocked(id));
        assertEquals(2, service.ledger(id).size());
    }
}
