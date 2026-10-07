package ai.kompile.cli.main.chat.tools;

import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeToolViewportTest {
    @Test
    void largeListFitsShortTerminalAndNumbersOnlyResolveVisibleRows() throws Exception {
        try (Fixture fixture = new Fixture(80, 20, 60)) {
            fixture.render();
            assertEquals(1, fixture.invoke("pageSize"));
            assertEquals(60, fixture.invoke("pageCount"));
            assertTrue(fixture.screen().contains("Page 1 of 60 (60 conversations)"));
            assertFalse(fixture.screen().contains("Conversation 2"));
            assertNotNull(fixture.invoke("resolveSessionId", "1"));
            assertNull(fixture.invoke("resolveSessionId", "2"));
            fixture.assertFits();
        }
    }

    @Test
    void lastPageAndResizeKeepPageInRange() throws Exception {
        try (Fixture fixture = new Fixture(120, 24, 61)) {
            fixture.render();
            fixture.invoke("goToPage", "31");
            fixture.render();
            assertTrue(fixture.screen().contains("Page 31 of 31 (61 conversations)"));
            assertNotNull(fixture.invoke("resolveSessionId", "1"));
            assertNull(fixture.invoke("resolveSessionId", "2"));
            fixture.terminal.setSize(new Size(40, 18));
            fixture.render();
            assertTrue(fixture.screen().contains("Page 61 of 61"));
            fixture.assertFits();
            fixture.terminal.setSize(new Size(120, 50));
            fixture.render();
            assertTrue(fixture.screen().contains("Showing 61-61 of 61 conversations"));
            fixture.assertFits();
        }
    }

    @Test
    void navigationStartsAfterTheLastRenderedRowWhenResizedAtThePrompt() throws Exception {
        try (Fixture fixture = new Fixture(80, 20, 60)) {
            fixture.render();
            fixture.invoke("nextPage");
            fixture.render();
            assertEquals("00000000-0000-0000-0000-000000000002",
                    fixture.invoke("resolveSessionId", "1"));

            fixture.terminal.setSize(new Size(120, 50));
            fixture.invoke("nextPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Showing 3-11 of 60 conversations"));
            assertEquals("00000000-0000-0000-0000-000000000003",
                    fixture.invoke("resolveSessionId", "1"));

            fixture.terminal.setSize(new Size(40, 18));
            fixture.invoke("nextPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Page 12 of 60"));
            assertEquals("00000000-0000-0000-0000-000000000012",
                    fixture.invoke("resolveSessionId", "1"));
            fixture.assertFits();
        }
    }

    @Test
    void previousPageEndsBeforeTheRenderedPageAcrossGrowAndShrink() throws Exception {
        try (Fixture fixture = new Fixture(80, 20, 60)) {
            fixture.render();
            fixture.invoke("nextPage");
            fixture.render();
            fixture.terminal.setSize(new Size(120, 50));
            fixture.invoke("prevPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Showing 1-1 of 60 conversations"));
            assertEquals("00000000-0000-0000-0000-000000000001",
                    fixture.invoke("resolveSessionId", "1"));
            assertNull(fixture.invoke("resolveSessionId", "2"));
            fixture.assertFits();
        }

        try (Fixture fixture = new Fixture(120, 50, 60)) {
            fixture.render();
            fixture.invoke("nextPage");
            fixture.render();
            fixture.terminal.setSize(new Size(40, 18));
            fixture.invoke("prevPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Page 9 of 60"));
            assertEquals("00000000-0000-0000-0000-000000000009",
                    fixture.invoke("resolveSessionId", "1"));
            assertNull(fixture.invoke("resolveSessionId", "2"));
            fixture.assertFits();
        }
    }

    @Test
    void consecutiveNavigationCommandsUpdateTheLogicalWindowWithoutRendering() throws Exception {
        try (Fixture fixture = new Fixture(80, 20, 60)) {
            fixture.render();
            fixture.invoke("nextPage");
            fixture.invoke("nextPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Page 3 of 60 (60 conversations)"));
            assertEquals("00000000-0000-0000-0000-000000000003",
                    fixture.invoke("resolveSessionId", "1"));

            fixture.invoke("prevPage");
            fixture.invoke("prevPage");
            fixture.render();
            assertTrue(fixture.screen().contains("Page 1 of 60 (60 conversations)"));
            assertEquals("00000000-0000-0000-0000-000000000001",
                    fixture.invoke("resolveSessionId", "1"));
        }
    }

    @Test
    void filterCommandInitializesSelectionWindowBeforeTheNextRender() throws Exception {
        try (Fixture fixture = new Fixture(80, 20, 60)) {
            fixture.render();
            fixture.invoke("nextPage");
            fixture.invoke("processCommand", "search Conversation");
            assertNotNull(fixture.invoke("resolveSessionId", "1"));
        }
    }

    @Test
    void emptyListHasOnePageAndCannotResolveARow() throws Exception {
        try (Fixture fixture = new Fixture(40, 18, 0)) {
            fixture.render();
            assertEquals(1, fixture.invoke("pageCount"));
            assertNull(fixture.invoke("resolveSessionId", "1"));
            fixture.assertFits();
        }
    }

    @Test
    void oversizedTitlesWrapFullyIntoNativeScrollbackWithoutTerminalInjection() throws Exception {
        try (Fixture fixture = new Fixture(32, 18, 40)) {
            List<ResumeTool.ConversationSummary> entries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                entries.add(new ResumeTool.ConversationSummary("session-" + i + "\033D",
                        "wide title 界界界\nsecond line\rthird\ttab\b\033]52;c;clipboard\u0007"
                                + "\033[31mINJECTED\u009b31m" + "x".repeat(100) + " TITLE_END", 
                        "2026-09-01T00:00:00Z\u009b2J", "codex\b", "kompile\033Ppayload\033\\",
                        "2026-09-01T00:00:00Z\033D", i, 10,
                        "/tmp/resume-viewport\033]0;title\u0007"));
            }
            fixture.set("allConversations", entries);
            fixture.set("filteredConversations", entries);
            fixture.render();
            assertFalse(fixture.screen().contains("\nsecond line"));
            String raw = fixture.rawScreen();
            assertFalse(raw.contains("\033]52"));
            assertFalse(raw.contains("\033D"));
            assertFalse(raw.contains("\033[31mINJECTED"));
            assertFalse(raw.contains("\u0007"));
            assertFalse(raw.contains("\u009b"));
            assertFalse(raw.contains("\b"));
            assertTrue(fixture.screen().contains("TITLE_END"));
            assertTrue(fixture.screen().contains("q back"));
            fixture.assertWidthFits();
        }
    }

    static final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final LineDisciplineTerminal terminal;
        final ResumeTool tool;

        Fixture(int width, int height, int count) throws Exception {
            terminal = new LineDisciplineTerminal("resume-viewport", "xterm", output, StandardCharsets.UTF_8);
            terminal.setSize(new Size(width, height));
            tool = new ResumeTool(terminal, null, null, null, null, null);
            List<ResumeTool.ConversationSummary> entries = entries(count);
            set("allConversations", entries);
            set("filteredConversations", entries);
        }

        static List<ResumeTool.ConversationSummary> entries(int count) {
            List<ResumeTool.ConversationSummary> entries = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                entries.add(new ResumeTool.ConversationSummary(String.format("00000000-0000-0000-0000-%012d", i),
                        "Conversation " + i, "2026-09-01T00:00:00Z", "codex", "kompile",
                        "2026-09-01T00:00:00Z", i, 10, "/tmp/resume-viewport"));
            }
            return entries;
        }

        void set(String name, Object value) throws Exception {
            Field field = ResumeTool.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(tool, value);
        }

        Object invoke(String name, Object... args) throws Exception {
            Method method = ResumeTool.class.getDeclaredMethod(name,
                    args.length == 0 ? new Class<?>[0] : new Class<?>[]{String.class});
            method.setAccessible(true);
            return method.invoke(tool, args);
        }

        void render() throws Exception {
            output.reset();
            invoke("renderMainView");
            terminal.writer().flush();
        }

        String screen() {
            return rawScreen()
                    .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "").replace("\r", "");
        }

        String rawScreen() {
            return output.toString(StandardCharsets.UTF_8);
        }

        void assertFits() {
            String[] lines = screen().split("\n");
            assertTrue(lines.length < terminal.getHeight(), "must leave a row for the prompt: " + screen());
            assertWidthFits();
        }

        void assertWidthFits() {
            for (String line : screen().split("\n")) {
                assertTrue(new org.jline.utils.AttributedString(line).columnLength() < terminal.getWidth(), line);
            }
        }

        @Override
        public void close() throws Exception {
            terminal.close();
        }
    }
}
