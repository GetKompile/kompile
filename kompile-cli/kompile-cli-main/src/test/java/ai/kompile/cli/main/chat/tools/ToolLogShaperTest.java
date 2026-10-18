package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolLogShaperTest {
    @Test
    void repeatedLinesAndTerminalRewritesAreFilteredDeterministically() {
        String input = "progress 10%\rprogress 50%\rprogress complete\n"
                + "ordinary repeated build detail with useful identity\n".repeat(100) + "done\n";
        String shaped = ToolLogShaper.shape("bash", input);
        assertTrue(shaped.startsWith("progress complete\n"));
        assertTrue(shaped.contains("[same line repeated 99 more times]"));
        assertTrue(shaped.endsWith("done\n"));
        assertEquals(shaped, ToolLogShaper.shape("bash", input));
        assertEquals(shaped, ToolLogShaper.shape("bash", shaped));
    }

    @Test
    void criticalEvidenceSurvivesEvenAnOverwrittenTerminalLine() {
        for (String diagnostic : new String[]{"ERROR", "warning", "CVE-2026-12345", "authentication bypass",
                "-----BEGIN PRIVATE KEY-----", "BUILD FAILED", "cancelled"}) {
            String input = diagnostic + "\rprogress complete\n" + "detail\n".repeat(100);
            assertEquals(input, ToolLogShaper.shape("bash", input), diagnostic);
        }
    }

    @Test
    void sourceAndUnknownToolOutputsAreNeverLogFiltered() {
        String source = "        nested source line\n".repeat(100);
        assertEquals(source, ToolLogShaper.shape("read", source));
        assertEquals(source, ToolLogShaper.shape("custom-unknown", source));
    }

    @Test
    void deepLogBoilerplateAndBlankRunsAreExplicitlyMarked() {
        StringBuilder input = new StringBuilder("begin\n\n\n\n");
        for (int i = 0; i < 20; i++) input.append("        nested log detail ").append(i).append('\n');
        input.append("done\n");
        String shaped = ToolLogShaper.shape("process", input.toString());
        assertTrue(shaped.contains("18 deeply indented log lines omitted"));
        assertTrue(shaped.contains("nested log detail 0"));
        assertTrue(shaped.contains("nested log detail 19"));
        assertFalse(shaped.contains("\n\n\n"));
        assertTrue(shaped.length() < input.length());
    }

    @Test
    void shapingNeverExpandsShortOrUnchangedLogs() {
        for (String input : new String[]{"x\nx\nx", "", "short log\n", "a\nb\nc\n"}) {
            assertTrue(ToolLogShaper.shape("bash", input).length() <= input.length());
        }
    }
}
