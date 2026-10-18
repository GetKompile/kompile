package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmulatedResourceMonitorTest {
    @Test void commandIsInterceptedEvenWithoutProviderOrRuntime() {
        var command = new EmulatedPassthroughCommand();
        ReflectionTestUtils.setField(command, "renderer", new TerminalRenderer(false));
        ReflectionTestUtils.setField(command, "drawLock", new Object());
        var terminal = org.mockito.Mockito.mock(org.jline.terminal.Terminal.class);
        org.mockito.Mockito.when(terminal.getWidth()).thenReturn(80);
        ReflectionTestUtils.setField(command, "terminal", terminal);
        assertNull(command.handleSlashCommand("/resource-monitor help", null, null, null));
        assertNull(ReflectionTestUtils.getField(command, "tuiProcess"), "must not launch/forward to a provider");
    }

    @Test void monitorScrollUsesItsOwnViewportNotTranscriptOrMirror() {
        var command = new EmulatedPassthroughCommand();
        ReflectionTestUtils.setField(command, "drawLock", new Object());
        var terminal = org.mockito.Mockito.mock(org.jline.terminal.Terminal.class);
        org.mockito.Mockito.when(terminal.getWidth()).thenReturn(80);
        org.mockito.Mockito.when(terminal.getHeight()).thenReturn(24);
        ReflectionTestUtils.setField(command, "terminal", terminal);
        ReflectionTestUtils.setField(command, "resourceMonitorVisible", true);
        ReflectionTestUtils.setField(command, "resourceMonitorLines", java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> "sample row " + i).toList());
        ReflectionTestUtils.setField(command, "scrollBottom", 10);
        ReflectionTestUtils.setField(command, "mirrorRender", true);
        ReflectionTestUtils.setField(command, "scrollViewportOffset", 7);
        var originalOut = System.out;
        try (var output = new java.io.PrintStream(new java.io.ByteArrayOutputStream())) {
            System.setOut(output);
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(command, "scrollTranscriptBy", -5));
            assertEquals(5, ReflectionTestUtils.getField(command, "resourceMonitorOffset"));
            assertEquals(7, ReflectionTestUtils.getField(command, "scrollViewportOffset"));
            ReflectionTestUtils.invokeMethod(command, "scrollTranscriptToBottom");
            assertEquals(50, ReflectionTestUtils.getField(command, "resourceMonitorOffset"));
            ReflectionTestUtils.invokeMethod(command, "scrollTranscriptToTop");
            assertEquals(0, ReflectionTestUtils.getField(command, "resourceMonitorOffset"));
        } finally { System.setOut(originalOut); }
    }
}
