package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.tui.KompileTui;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatRedrawCommandTest {
    @Test
    void standardRedrawKeepsSessionOpenAndOnlyRequestsDisplayRecovery() {
        for (boolean local : List.of(false, true)) {
            ChatRepl repl = mock(ChatRepl.class);
            KompileTui tui = mock(KompileTui.class);
            when(repl.getTui()).thenReturn(tui);
            assertTrue(router(repl, local).handleSlashCommand("/redraw"));
            verify(tui).requestFullRedraw();
            verify(repl, never()).requestNewConversation();
            verifyNoMoreInteractions(tui);
        }
        assertTrue(router(mock(ChatRepl.class), true).handleSlashCommand("/redraw"),
                "No attached TUI must not end the session");
        assertTrue(ChatCommandCatalog.isBuiltin("redraw"));
        assertEquals(ChatCommandCatalog.WebSupport.TERMINAL_REQUIRED,
                ChatCommandCatalog.webSupport("redraw"));
    }

    @Test
    void submittedRedrawRetainsImagePreviewWhileOtherCommandsCloseIt() throws Exception {
        for (boolean mainSelected : List.of(false, true)) for (String command : List.of("/redraw", "/REDRAW", "/help")) {
            ChatRepl repl = mock(ChatRepl.class, CALLS_REAL_METHODS);
            KompileTui tui = mock(KompileTui.class);
            var panel = mock(StandardChatActivityPanel.class);
            var permissions = mock(ai.kompile.cli.main.chat.permission.PermissionService.class);
            ChatCommandRouter router = mock(ChatCommandRouter.class);
            ReflectionTestUtils.setField(repl, "tui", tui);
            ReflectionTestUtils.setField(repl, "activityPanel", panel);
            ReflectionTestUtils.setField(repl, "permissionService", permissions);
            ReflectionTestUtils.setField(repl, "commandRouter", router);
            when(tui.isImagePreviewView()).thenReturn(true);
            when(panel.viewedSubagentId()).thenReturn("");
            when(panel.isViewingMain()).thenReturn(mainSelected);
            when(router.handleSlashCommand(command)).thenReturn(true);
            when(tui.runCommandOutput(any())).thenAnswer(invocation ->
                    ((java.util.function.BooleanSupplier) invocation.getArgument(0)).getAsBoolean());
            assertTrue(repl.submitInteractiveLine(command));
            verify(router).handleSlashCommand(command);
            if (command.equalsIgnoreCase("/redraw")) verify(tui, never()).showMainView();
            else verify(tui, atLeastOnce()).showMainView();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void managedRedrawUsesFullRepaintWithoutChangingConversationOrRenderMode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8);
             var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                     "redraw", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            System.setOut(capture);
            terminal.setSize(new org.jline.terminal.Size(120, 30));
            var managed = new EmulatedPassthroughCommand();
            KompileTui tui = mock(KompileTui.class);
            when(tui.scrollTop()).thenReturn(3);
            when(tui.scrollBottom()).thenReturn(20);
            ReflectionTestUtils.setField(managed, "tui", tui);
            ReflectionTestUtils.setField(managed, "terminal", terminal);
            ReflectionTestUtils.setField(managed, "drawLock", new Object());
            ReflectionTestUtils.setField(managed, "inputRows", 3);
            @SuppressWarnings("unchecked")
            List<String> transcript = (List<String>) ReflectionTestUtils.getField(managed, "scrollbackLines");
            transcript.add("RETAINED TRANSCRIPT");
            assertNull(managed.handleSlashCommand("/redraw", null, null, null));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\033[2J"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("RETAINED TRANSCRIPT"));
            assertEquals(List.of("RETAINED TRANSCRIPT"), transcript);
            assertEquals(false, ReflectionTestUtils.getField(managed, "newConversationRequested"));
            verify(tui).redrawBars();
            verify(tui).reestablishScrollRegion();
        } finally { System.setOut(previous); }
    }

    private ChatCommandRouter router(ChatRepl repl, boolean local) {
        return new ChatCommandRouter(repl, null, null, null, null, null, "redraw-test", local,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(), null);
    }
}
