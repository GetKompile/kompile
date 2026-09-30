package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebChatContextTest {

    @TempDir Path directory;

    @Test
    void theWorkflowTeamReachesTheWebChatServerOnlyWhenOneIsNamed() throws Exception {
        String real = directory.toRealPath().toString();
        List<String> base = List.of("-D" + WebChatContext.WORKING_DIRECTORY + "=" + real,
                "-D" + WebChatContext.CONFIG_SCOPE + "=project");
        assertEquals(base, WebChatContext.jvmArguments(directory, false));
        assertEquals(base, WebChatContext.jvmArguments(directory, false, " "));
        assertEquals(List.of(base.get(0), base.get(1), "-D" + WebChatContext.WORKFLOW + "=session-team"),
                WebChatContext.jvmArguments(directory, false, " session-team "));
        assertThrows(IllegalArgumentException.class, () -> WebChatContext.jvmArguments(directory, false, "a\nb"));
        assertThrows(IllegalArgumentException.class,
                () -> WebChatContext.jvmArguments(directory, false, "t".repeat(257)));
    }

    @Test
    void theServerReadsTheTeamNewSessionsStartWith() {
        String previous = System.getProperty(WebChatContext.WORKFLOW);
        try {
            System.clearProperty(WebChatContext.WORKFLOW);
            assertNull(WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, " ");
            assertNull(WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, " session-team ");
            assertEquals("session-team", WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, "a\tb");
            assertThrows(IllegalStateException.class, WebChatContext::workflow);
        } finally {
            if (previous == null) System.clearProperty(WebChatContext.WORKFLOW);
            else System.setProperty(WebChatContext.WORKFLOW, previous);
        }
    }
}
