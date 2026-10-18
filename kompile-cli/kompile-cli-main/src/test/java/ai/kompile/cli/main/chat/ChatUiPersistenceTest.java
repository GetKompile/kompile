package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.KompileTui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatUiPersistenceTest {
    @TempDir Path root;

    @Test void commandsRestoreLastLayoutOnResumeAndNewChatsWithoutAnotherUiCommand() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", root.resolve("home").toString());
        List<ChatRepl> chats = new ArrayList<>();
        try {
            Path project = Files.createDirectories(root.resolve("project"));
            ChatRepl first = chat(project, "ui-original", chats);
            first.handleUiCommand("preset focused");
            first.handleUiCommand("layout split");
            first.handleUiCommand("set layout.sidebarWidthPercent 45");
            first.handleUiCommand("set layout.minChatColumns 50");
            first.handleUiCommand("set sidebar.wrap false");
            first.handleUiCommand("set sidebar.visible false");
            first.handleUiCommand("set content.tool.inline summary");
            first.handleUiCommand("set toolOverrides.read.sidebar expanded");
            first.handleUiCommand("set bindings.toggle ctrl-x z");
            ChatUiConfig expected = tui(first).getUiConfig();
            assertTrue(Files.exists(ChatUiConfig.globalPath()));
            for (String session : List.of("ui-original", "ui-fresh")) {
                ChatUiConfig restored = tui(chat(project, session, chats)).getUiConfig();
                assertEquals(expected.describe(), restored.describe());
                assertEquals(expected.policy("tool", "read"), restored.policy("tool", "read"));
            }
            Path otherProject = Files.createDirectories(root.resolve("other-project"));
            assertEquals(expected.describe(), tui(chat(otherProject, "ui-other", chats)).getUiConfig().describe());
            first.handleUiCommand("layout invalid");
            assertEquals(expected.describe(), ChatUiConfig.loadEffective(project, "ui-original").describe());
        } finally {
            close(chats);
            restoreHome(home);
        }
    }

    @Test void explicitProjectAndSessionScopesPersistWithoutOverwritingUserDefaults() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", root.resolve("home").toString());
        List<ChatRepl> chats = new ArrayList<>();
        try {
            Path project = Files.createDirectories(root.resolve("project"));
            ChatRepl first = chat(project, "ui-scoped", chats);
            first.handleUiCommand("preset focused");
            String global = Files.readString(ChatUiConfig.globalPath());
            first.handleUiCommand("save project");
            first.handleUiCommand("layout split");
            assertEquals("split", tui(chat(project, "ui-project-new", chats)).getUiConfig().getLayoutMode());
            assertEquals(global, Files.readString(ChatUiConfig.globalPath()));
            first.handleUiCommand("save session");
            first.handleUiCommand("layout inline");
            assertEquals("inline", tui(chat(project, "ui-scoped", chats)).getUiConfig().getLayoutMode());
            assertEquals("split", tui(chat(project, "ui-session-new", chats)).getUiConfig().getLayoutMode());
            assertEquals(global, Files.readString(ChatUiConfig.globalPath()));
        } finally {
            close(chats);
            restoreHome(home);
        }
    }

    @Test void sidebarCommandAndKeyboardToggleSaveVisibilityButNarrowDrawerDoesNot() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", root.resolve("home").toString());
        List<ChatRepl> chats = new ArrayList<>();
        try (var terminal = new org.jline.terminal.impl.LineDisciplineTerminal("ui-persistence", "xterm",
                new java.io.ByteArrayOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            try {
                Path project = Files.createDirectories(root.resolve("project"));
                ChatConfig config = new ChatConfig("custom", null, "test-model", "http://unused.invalid");
                config.setAuthenticationMethod("none");
                ChatRepl repl = new ChatRepl(null, null, "ui-sidebar", false, "default", false, config, project,
                        new ai.kompile.cli.main.chat.render.TerminalRenderer(true));
                chats.add(repl);
                terminal.setSize(new org.jline.terminal.Size(120, 24));
                var reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
                Field activeReader = ChatRepl.class.getDeclaredField("activeReader");
                activeReader.setAccessible(true); activeReader.set(repl, reader);
                tui(repl).start(terminal);
                repl.handleUiCommand("preset focused");
                assertTrue(tui(repl).isSidebarVisible());
                repl.handleUiCommand("sidebar toggle");
                assertFalse(ChatUiConfig.loadEffective(project, "ui-sidebar").isSidebarVisible());
                assertTrue(reader.getWidgets().get("kompile-ui-toggle").apply());
                assertTrue(ChatUiConfig.loadEffective(project, "ui-sidebar").isSidebarVisible());
                String saved = Files.readString(ChatUiConfig.globalPath());
                tui(repl).updateTemporaryWindow("UI preview", List.of("Choose a setting"));
                assertTrue(reader.getWidgets().get("kompile-ui-toggle").apply());
                assertEquals(saved, Files.readString(ChatUiConfig.globalPath()), "A picker shortcut must not commit a cancellable preview");
                assertTrue(tui(repl).getUiConfig().isSidebarVisible());
                tui(repl).closeTemporaryWindow();
                terminal.setSize(new org.jline.terminal.Size(60, 24)); tui(repl).handleResize();
                repl.handleUiCommand("sidebar toggle");
                assertTrue(tui(repl).isResultsView());
                assertEquals(saved, Files.readString(ChatUiConfig.globalPath()));
                repl.handleUiCommand("close");
                terminal.setSize(new org.jline.terminal.Size(120, 24)); tui(repl).handleResize();
                assertTrue(tui(repl).isSidebarVisible());
            } finally { close(chats); restoreHome(home); }
        }
    }

    private ChatRepl chat(Path project, String session, List<ChatRepl> chats) throws Exception {
        ChatConfig config = new ChatConfig("custom", null, "test-model", "http://unused.invalid");
        config.setAuthenticationMethod("none");
        ChatRepl repl = new ChatRepl(null, null, session, false, "default", false, config, project);
        chats.add(repl);
        return repl;
    }

    private static KompileTui tui(ChatRepl repl) throws Exception { return field(repl, "tui", KompileTui.class); }
    private static void close(List<ChatRepl> chats) throws Exception {
        for (ChatRepl repl : chats) {
            tui(repl).stop();
            field(repl, "processManager", BackgroundProcessManager.class).close();
            field(repl, "directClient", DirectLlmClient.class).close();
        }
        ChatCompleter.setActivity(null);
    }
    private static void restoreHome(String home) {
        if (home == null) System.clearProperty("user.home"); else System.setProperty("user.home", home);
    }
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
