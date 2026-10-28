package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HookManagerTest {
    @TempDir Path directory;
    HookManager manager() { return new HookManager(directory.resolve("project"), directory.resolve("home")); }
    JsonNode json(String text) throws Exception { return JsonUtils.standardMapper().readTree(text); }
    JsonNode add(HookManager manager, String scope, String id) throws Exception {
        return manager.manage(json("{\"action\":\"add\",\"scope\":\"" + scope + "\",\"id\":\"" + id
                + "\",\"event\":\"PreToolUse\",\"matcher\":\"^(write|edit)$\",\"command\":\"exit 0\"}"));
    }

    @Test void missingConfigurationIsReadOnlyAndReportsBothPaths() throws Exception {
        HookManager manager = manager();
        assertTrue(manager.load().isEmpty());
        JsonNode listed = manager.manage(json("{\"action\":\"list\"}"));
        assertEquals(manager.path("project").toString(), listed.path("projectPath").asText());
        assertFalse(Files.exists(manager.path("global")));
        assertFalse(Files.exists(manager.path("project")));
    }

    @Test void scopesCrudPartialEditsAndLiveReload() throws Exception {
        HookManager manager = manager();
        add(manager, "project", "p");
        add(manager, "global", "g");
        assertEquals("global", manager.load().get(0).scope());
        assertEquals("project", manager.load().get(1).scope());
        assertTrue(manager.load().get(1).matches("write"));
        assertFalse(manager.load().get(1).matches("Write"));
        manager.manage(json("{\"action\":\"update\",\"id\":\"p\",\"event\":\"PostToolUse\",\"timeout\":5}"));
        var edited = new HookManager(directory.resolve("project"), directory.resolve("home")).load().get(1);
        assertEquals("PostToolUse", edited.event());
        assertEquals("exit 0", edited.commands().get(0).command());
        assertEquals(5, edited.commands().get(0).timeout());
        manager.manage(json("{\"action\":\"disable\",\"id\":\"p\"}"));
        assertFalse(manager.load().get(1).matches("write"));
        manager.manage(json("{\"action\":\"enable\",\"id\":\"p\"}"));
        assertTrue(manager.load().get(1).matches("write"));
        manager.manage(json("{\"action\":\"delete\",\"id\":\"p\"}"));
        assertEquals(1, manager.load().size());
        assertThrows(IllegalArgumentException.class, () -> manager.manage(json("{\"action\":\"get\",\"id\":\"p\"}")));
    }

    @Test void invalidRequestsLeaveExistingConfigurationUnchanged() throws Exception {
        HookManager manager = manager();
        add(manager, "project", "p");
        String original = Files.readString(manager.path("project"));
        for (String fields : new String[]{"\"matcher\":\"[\"", "\"timeout\":0", "\"timeout\":601", "\"timeout\":1.5",
                "\"enabled\":\"true\"", "\"event\":\"Stop\"", "\"command\":\" \""}) {
            assertThrows(IllegalArgumentException.class, () -> manager.manage(json("{\"action\":\"update\",\"id\":\"p\"," + fields + "}")));
            assertEquals(original, Files.readString(manager.path("project")));
        }
        assertThrows(IllegalArgumentException.class, () -> add(manager, "project", "p"));
        assertThrows(IllegalArgumentException.class, () -> manager.manage(json("{\"action\":\"delete\",\"id\":\"missing\"}")));
        assertThrows(IllegalArgumentException.class, () -> manager.path("session"));
    }

    @Test void handWrittenClaudeShapePreservesSiblingsCommandsAndOrder() throws Exception {
        HookManager manager = manager();
        Files.createDirectories(manager.path("project").getParent());
        Files.writeString(manager.path("project"), """
                {"customSetting":42,"hooks":{"PreToolUse":[
                  {"matcher":"write","hooks":[{"type":"command","command":"true"},{"type":"command","command":"false"}]},
                  {"id":"second","matcher":"*","hooks":[{"type":"command","command":"true"}]}]}}
                """);
        String first = manager.load().get(0).id();
        manager.manage(json("{\"action\":\"update\",\"id\":\"" + first + "\",\"timeout\":10}"));
        assertEquals(first, manager.load().get(0).id());
        assertEquals(2, manager.load().get(0).commands().size());
        assertEquals(10, manager.load().get(0).commands().get(1).timeout());
        assertEquals("second", manager.load().get(1).id());
        assertEquals(42, json(Files.readString(manager.path("project"))).path("customSetting").asInt());
    }

    @Test void concurrentManagersDoNotLoseUpdates() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                String id = "hook-" + i;
                futures.add(executor.submit(() -> { try { add(manager(), "project", id); } catch (Exception e) { throw new RuntimeException(e); } }));
            }
            for (var future : futures) future.get();
            assertEquals(12, manager().load().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void malformedAndUnsupportedConfigurationIsNotSilentlyIgnored() throws Exception {
        HookManager manager = manager();
        Files.createDirectories(manager.path("project").getParent());
        for (String content : new String[]{"null", "{", "{\"hooks\":[]}", "{\"hooks\":{\"Stop\":[]}}",
                "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[{\"type\":\"prompt\",\"command\":\"x\"}]}]}}"}) {
            Files.writeString(manager.path("project"), content);
            assertThrows(Exception.class, manager::load, content);
        }
    }
}
