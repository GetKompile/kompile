package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatUiConfigTest {
    @TempDir Path root;

    @Test void imageProtocolIsValidatedAndPersistsWithoutChangingLayout() throws Exception {
        ChatUiConfig config = ChatUiConfig.classic();
        assertEquals("auto", config.getImageProtocol());
        for (String protocol : List.of("kitty", "off", "auto")) {
            config.set("images.protocol", protocol);
            Path target = root.resolve("images.json");
            config.saveTo(target);
            assertEquals(protocol, ChatUiConfig.load(List.of(target)).getImageProtocol());
            assertEquals("inline", config.getLayoutMode());
        }
        assertThrows(IllegalArgumentException.class, () -> config.set("images.protocol", "sixel"));
    }

    @Test void retentionLimitsAreValidatedLayeredAndPersisted() throws Exception {
        ChatUiConfig config = ChatUiConfig.classic();
        assertEquals(256, config.getSidebarMaxItems());
        assertEquals(2 * 1024 * 1024, config.getSidebarMaxChars());
        for (String value : List.of("0", "-1", "4097", "bad", "2147483648"))
            assertThrows(IllegalArgumentException.class, () -> config.set("sidebar.maxItems", value));
        for (String value : List.of("0", "1023", "67108865", "bad", "2147483648"))
            assertThrows(IllegalArgumentException.class, () -> config.set("sidebar.maxChars", value));
        Path lower = root.resolve("limits.json"), higher = root.resolve("override.json");
        config.set("sidebar.maxItems", "512");
        config.set("sidebar.maxChars", "4194304");
        config.saveTo(lower);
        ChatUiConfig override = ChatUiConfig.classic();
        override.set("sidebar.maxItems", "128");
        override.saveTo(higher);
        ChatUiConfig restored = ChatUiConfig.load(List.of(lower, higher)).copy();
        assertEquals(128, restored.getSidebarMaxItems());
        assertEquals(4194304, restored.getSidebarMaxChars());
        restored.applyPreset("focused");
        assertEquals(128, restored.getSidebarMaxItems(), "Changing presets must preserve explicit limits");
        assertTrue(String.join("\n", restored.describe()).contains("128"));
        Files.writeString(higher, "{\"sidebar\":{\"maxChars\":4297064448}}");
        assertEquals(4194304, ChatUiConfig.load(List.of(lower, higher)).getSidebarMaxChars(),
                "An overflowing JSON integer must not wrap into a valid budget");
    }

    @Test void legacyDefaultsStayClassic() {
        ChatUiConfig config = ChatUiConfig.classic();
        assertEquals("inline", config.getLayoutMode());
        assertEquals(new ChatUiConfig.Policy("full", "off"), config.policy("tool", "read"));
        assertFalse(config.hasContentOverrides());
    }
    @Test void focusedAndToolOverridesAreIndependentOfLayout() {
        ChatUiConfig config = ChatUiConfig.classic();
        config.applyPreset("focused");
        assertEquals("auto", config.getLayoutMode());
        assertEquals(new ChatUiConfig.Policy("none", "collapsed"), config.policy("tool", "read"));
        assertEquals("full", config.policy("thinking", null).inline());
        config.set("toolOverrides.bash.inline", "summary");
        config.set("content.diff.sidebar", "expanded");
        config.set("layout.mode", "inline");
        assertEquals("summary", config.policy("tool", "mcp__kompile__bash").inline());
        assertEquals("none", config.policy("tool", "read").inline());
        assertEquals("expanded", config.sectionPolicy("diff", "read", config.policy("tool", "read")).sidebar());
    }
    @Test void layersMergeFieldsAndRespectFalseAndEmptyLists() throws Exception {
        Path global = root.resolve("global.json"), project = root.resolve("project.json"), session = root.resolve("session.json");
        Files.writeString(global, "{\"preset\":\"focused\",\"sidebar\":{\"wrap\":true},\"content\":{\"tool\":{\"inline\":\"summary\",\"sidebar\":\"collapsed\"}}}");
        Files.writeString(project, "{\"sidebar\":{\"wrap\":false},\"content\":{\"tool\":{\"inline\":\"none\"}}}");
        Files.writeString(session, "{\"sidebar\":{\"sections\":[]},\"layout\":{\"sidebarWidthPercent\":40}}");
        ChatUiConfig config = ChatUiConfig.load(List.of(global, project, session));
        assertEquals("focused", config.getPreset());
        assertFalse(config.isSidebarWrap());
        assertEquals(List.of(), config.getSidebarSections());
        assertEquals(40, config.getSidebarWidthPercent());
        assertEquals(new ChatUiConfig.Policy("none", "collapsed"), config.policy("tool", null));
    }
    @Test void invalidLayerDoesNotEraseValidLowerLayer() throws Exception {
        Path lower = root.resolve("lower.json"), invalid = root.resolve("invalid.json");
        Files.writeString(lower, "{\"preset\":\"focused\"}");
        Files.writeString(invalid, "{\"sidebar\":{\"wrap\":\"false\"}}");
        assertEquals("focused", ChatUiConfig.load(List.of(lower, invalid)).getPreset());
    }
    @Test void savingOnlyExplicitChangesPreservesScopeAndExistingFields() throws Exception {
        Path inherited = root.resolve("global.json"), target = root.resolve("project.json");
        Files.writeString(inherited, "{\"preset\":\"focused\",\"layout\":{\"sidebarWidthPercent\":45}}");
        Files.writeString(target, "{\"sidebar\":{\"wrap\":false}}");
        ChatUiConfig config = ChatUiConfig.load(List.of(inherited, target));
        config.set("content.tool.inline", "summary");
        config.saveTo(target);
        String stored = Files.readString(target);
        assertFalse(stored.contains("sidebarWidthPercent"));
        assertFalse(stored.contains("preset"));
        assertFalse(ChatUiConfig.load(List.of(target)).isSidebarWrap());
        assertEquals("summary", ChatUiConfig.load(List.of(target)).policy("tool", "read").inline());
        assertTrue(Files.readString(inherited).contains("45"));
    }
    @Test void automaticSavesUseHighestValidScopeAndPreserveEveryDisplaySetting() throws Exception {
        Path global = root.resolve("global.json"), project = root.resolve("project.json"), session = root.resolve("session.json");
        Files.writeString(global, "{\"preset\":\"classic\"}");
        Files.writeString(project, "{\"layout\":{\"mode\":\"inline\"}}");
        Files.writeString(session, "{\"sidebar\":{\"follow\":\"manual\"}}");
        ChatUiConfig config = ChatUiConfig.load(List.of(global, project, session)).copy();
        config.applyPreset("focused");
        config.set("layout.mode", "split");
        config.set("layout.sidebarWidthPercent", "45");
        config.set("layout.minChatColumns", "50");
        config.set("sidebar.visible", "false");
        config.set("sidebar.wrap", "false");
        config.set("sidebar.sections", "custom,tools");
        config.set("content.tool.inline", "summary");
        config.set("content.diff.sidebar", "expanded");
        config.set("toolOverrides.read.sidebar", "expanded");
        config.set("bindings.toggle", "ctrl-x z");
        config.set("bindings.focus", "ctrl-x y");
        config.saveDefault();
        ChatUiConfig restored = ChatUiConfig.load(List.of(global, project, session));
        assertEquals(config.describe(), restored.describe());
        assertEquals(config.policy("tool", "read"), restored.policy("tool", "read"));
        assertEquals(config.sectionPolicy("diff", "read", config.policy("tool", "read")), restored.sectionPolicy("diff", "read", restored.policy("tool", "read")));
        assertEquals("{\"preset\":\"classic\"}", Files.readString(global));
        assertEquals("{\"layout\":{\"mode\":\"inline\"}}", Files.readString(project));
        assertEquals(session, restored.persistencePath());
    }

    @Test void missingOrInvalidHigherLayerDoesNotRedirectAutomaticSaves() throws Exception {
        Path global = root.resolve("global.json"), project = root.resolve("project.json"), session = root.resolve("session.json");
        ChatUiConfig initial = ChatUiConfig.load(List.of(global, project, session));
        assertEquals(global, initial.persistencePath());
        initial.applyPreset("focused"); initial.saveDefault();
        assertEquals("focused", ChatUiConfig.load(List.of(global)).getPreset());
        Files.writeString(project, "{\"sidebar\":{\"wrap\":\"invalid\"}}");
        ChatUiConfig loaded = ChatUiConfig.load(List.of(global, project, session));
        loaded.set("layout.mode", "split"); loaded.saveDefault();
        assertEquals("split", ChatUiConfig.load(List.of(global)).getLayoutMode());
        assertFalse(Files.exists(session));
        assertTrue(Files.readString(project).contains("invalid"));
    }

    @Test void explicitlyChosenSaveScopeIsKeptByCopiesAndSubsequentSaves() throws Exception {
        Path global = root.resolve("global.json"), project = root.resolve("project.json");
        ChatUiConfig config = ChatUiConfig.load(List.of(global));
        config.applyPreset("focused"); config.saveTo(project);
        ChatUiConfig next = config.copy(); next.set("layout.mode", "split"); next.saveDefault();
        assertEquals("split", ChatUiConfig.load(List.of(project)).getLayoutMode());
        assertFalse(Files.exists(global));
    }

    @Test void validatesValuesAndCopiesWithoutMutationLeaks() {
        ChatUiConfig config = ChatUiConfig.classic();
        for (String value : List.of("0", "100", "bad")) assertThrows(IllegalArgumentException.class, () -> config.set("layout.sidebarWidthPercent", value));
        assertThrows(IllegalArgumentException.class, () -> config.set("content.tool.inline", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> config.set("sidebar.sections", "tools,tools"));
        assertThrows(IllegalArgumentException.class, () -> config.set("bindings.toggle", "ctrl-x r"));
        assertThrows(IllegalArgumentException.class, () -> config.set("bindings.toggle", "ctrl-x p"));
        assertEquals("ctrl-x s", config.getBinding("toggle"));
        ChatUiConfig copy = config.copy(); copy.applyPreset("focused");
        assertEquals("classic", config.getPreset());
        assertThrows(IllegalArgumentException.class, () -> ChatUiConfig.sessionPath("../evil"));
    }
}
