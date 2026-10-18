package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ProviderCompactionCapabilities;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.CompactionService.Attachment;
import ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TemporaryUserHome
class LocalContextInventoryTest {
    @TempDir Path directory;

    @Test
    void textEstimatesFollowCompactionConventionWithoutLeakingContentsOrMixingUsage() {
        var entries = List.of(ConversationEntry.system("s"), ConversationEntry.user("private-user"),
                ConversationEntry.assistant("private-assistant"),
                ConversationEntry.toolCall("private-tool", "call-1", "args"),
                ConversationEntry.toolResult("private-tool", "call-1", "private-result"));
        var report = LocalContextInventory.describe(entries, 17L,
                new LocalContextInventory.SchemaSize(2, 20, false), false);
        var compaction = new CompactionService(JsonUtils.standardMapper());
        assertTrue(report.get("History (current active)").contains(
                "estimated " + compaction.estimateTokens(entries) + " text tokens"));
        assertEquals("call arguments=4; results=14", report.get("Tool history (chars)"));
        assertTrue(report.get("Prefix (last assembled)").contains("17 chars; estimated 5 text tokens"));
        assertTrue(report.get("Schemas (last assembled)").contains("not wire JSON"));
        String output = report.toString();
        assertTrue(output.contains("not request/billed tokens"));
        assertTrue(output.contains("not additive"));
        assertFalse(output.contains("private-"));
        assertFalse(output.contains("call-1"));
        assertEquals(9, report.size());
        assertTrue(output.length() < 1800);
    }

    @Test
    void unobservedAssemblyIsNotZeroAndNativeSessionIsExplicitlyOpaque() {
        var report = LocalContextInventory.describe(List.of(), null, null, true);
        assertEquals("unobserved (not zero)", report.get("Prefix (last assembled)"));
        assertEquals("unobserved (not zero)", report.get("Schemas (last assembled)"));
        assertTrue(report.get("History (current active)").contains("0/0 entries; 0 chars; estimated 0"));
        assertTrue(report.get("Unattributed").contains("Claude Code session/tools opaque, provider-owned (not zero)"));
        assertTrue(report.get("Unattributed").contains("not native input"));
        assertEquals(report, LocalContextInventory.describe(null, null, null, true));
    }

    @Test
    void largeInputsHaveBoundedWorkAndOutputAndDoNotEstimateAttachmentPayloads() {
        var entry = ConversationEntry.user("x".repeat(1_000_000), List.of(
                new Attachment("secret-path", "image/png", true, "secret-digest", Long.MAX_VALUE)));
        var entries = Collections.nCopies(LocalContextInventory.MAX_ENTRIES + 3, entry);
        var report = LocalContextInventory.describe(entries, 0L, null, false);
        assertTrue(report.get("History (current active)").contains("10000/10003 entries; 10000000000 chars"));
        assertTrue(report.get("History (current active)").contains("partial lower bound"));
        assertTrue(report.get("Inventory coverage").contains("attachments=10000"));
        assertTrue(report.get("Inventory coverage").contains("payload excluded"));
        assertFalse(report.toString().contains("secret-"));
        assertTrue(report.toString().length() < 1800);
        assertEquals(LocalContextInventory.MAX_ENTRIES + 3, entries.size());
    }

    @Test
    void schemaCounterCountsOnlyLocalTextAndStopsAtNodeAndDepthBudgets() {
        var mapper = JsonUtils.standardMapper();
        var schema = mapper.createArrayNode();
        schema.addObject().put("description", "secret-text").put("limit", 42);
        var size = LocalContextInventory.schemaSize(schema);
        assertEquals(1, size.definitions());
        assertEquals("description".length() + "secret-text".length() + "limit".length(), size.textChars());
        assertFalse(size.partial());
        var large = mapper.createArrayNode();
        for (int i = 0; i < 25_000; i++) large.add("x");
        var bounded = LocalContextInventory.schemaSize(large);
        assertTrue(bounded.partial());
        assertTrue(bounded.textChars() < 20_000);
        var deep = mapper.createArrayNode();
        var cursor = deep;
        for (int i = 0; i < 100; i++) cursor = cursor.addArray();
        assertTrue(LocalContextInventory.schemaSize(deep).partial());
        assertNull(LocalContextInventory.schemaSize(null));
    }

    @Test
    void actualLoopSnapshotDoesNotCallProviderCountOrChatOrMutateToolPairings() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var client = mock(DirectLlmClient.class);
        when(client.getChatConfig()).thenReturn(new ChatConfig("openai", "unused", "test", null));
        when(client.compactionCapabilities(null)).thenReturn(ProviderCompactionCapabilities.claudeCodeSession());
        var registry = new ToolRegistry(mapper);
        var loop = new AgenticChatLoop(null, mapper, registry,
                new PermissionService(), new AgentRegistry(), directory, client, null);
        var ledger = (ConversationLedger) field("conversationLedger").get(loop);
        ledger.append(ConversationEntry.user("private-user"));
        ledger.append(ConversationEntry.toolCall("private-tool", "paired-id", "args"));
        ledger.append(ConversationEntry.toolResult("private-tool", "paired-id", "private-result"));
        var before = ledger.snapshot();
        field("lastReportedInputTokens").set(loop, 999_999L);
        clearInvocations(client);

        var first = loop.contextInventory();
        assertEquals(first, loop.contextInventory());
        var after = ledger.snapshot();
        assertEquals(before.version(), after.version());
        assertEquals(before.allEvents(), after.allEvents());
        assertEquals(before.checkpoint(), after.checkpoint());
        assertEquals(before.activeEntries().stream().map(e -> e.toolCallId).toList(),
                after.activeEntries().stream().map(e -> e.toolCallId).toList(),
                "diagnostics must not rewrite tool call/result pairings");
        assertEquals(999_999L, loop.lastReportedInputTokens());
        assertFalse(first.toString().contains("999999"));
        assertTrue(first.get("Unattributed").contains("opaque"));
        verify(client, times(2)).compactionCapabilities(null); // Pure local routing metadata only.
        verifyNoMoreInteractions(client); // In particular: no chat, token counting, or wire projection.
    }

    @Test
    void realAssemblyObservationsAreUsedWithoutRebuildingAtStatusTime() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var registry = mock(ToolRegistry.class);
        var definitions = mapper.createArrayNode().addObject().put("name", "private-tool");
        var schemas = mapper.createArrayNode().add(definitions);
        when(registry.buildToolDefinitions(any())).thenReturn(schemas);
        var agents = new AgentRegistry();
        var loop = new AgenticChatLoop(null, mapper, registry,
                new PermissionService(), agents, directory);
        String prefix = loop.buildSystemPrompt(agents.getDefault());
        var method = AgenticChatLoop.class.getDeclaredMethod("toolDefinitions", AgentConfig.class, boolean.class);
        method.setAccessible(true);
        method.invoke(loop, agents.getDefault(), false);
        clearInvocations(registry);

        var report = loop.contextInventory();
        assertTrue(report.get("Prefix (last assembled)").startsWith(prefix.length() + " chars; estimated "));
        assertTrue(report.get("Schemas (last assembled)").startsWith("1 definitions; "));
        assertFalse(report.toString().contains("private-tool"));
        verifyNoInteractions(registry);
    }

    private static Field field(String name) throws Exception {
        Field field = AgenticChatLoop.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
