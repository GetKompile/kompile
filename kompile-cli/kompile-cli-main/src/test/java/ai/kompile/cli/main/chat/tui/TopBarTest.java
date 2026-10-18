package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.utils.AnsiConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopBarTest {
    @Test
    void labelsSessionUsageAndIncludesAllInputInItsArithmetic() {
        var metrics = new ChatSessionMetrics("top-bar");
        metrics.recordTokenUsage(100, 50, 800, 100);
        var bar = new TopBar(new Object());
        bar.setTokenSummary(metrics.compactTokenSummary());
        String row = tokenRow(bar, 100);
        assertTrue(row.contains("session tokens: ↑1,000 ↓50 Σ1,050"), row);
        assertEquals(99, row.length());
    }

    @Test
    void exactlyFittingUsageDoesNotLoseItsLastDigit() {
        var bar = new TopBar(new Object());
        bar.setTokenSummary("↑10 ↓5 Σ15");
        String expected = "session tokens: ↑10 ↓5 Σ15";
        assertEquals(expected, tokenRow(bar, expected.length() + 1));
    }

    @Test
    void longSessionAndJudgeTotalsStayInsideReservedRow() {
        var bar = new TopBar(new Object());
        bar.setTokenSummary("↑1,000,000 ↓500,000 Σ1,500,000 · 10 compact");
        bar.setJudgeTokenSummary("100 calls · ↑1,000,000 ↓500,000 Σ1,500,000");
        for (int width : new int[] {1, 20, 80}) {
            String row = tokenRow(bar, width);
            assertEquals(Math.max(1, width - 1), row.length(), row);
            assertTrue(row.endsWith("…"), row);
        }
    }

    @Test
    void contextWindowIsSeparateFromBillingAndTakesPriorityOnNarrowTerminals() {
        var bar = new TopBar(new Object());
        bar.setContextSummary("120,000/200,000 60% last input");
        bar.setTokenSummary("↑1,000,000 ↓500,000 Σ1,500,000");
        String wide = tokenRow(bar, 120);
        assertTrue(wide.contains("context: 120,000/200,000 60% last input"), wide);
        assertTrue(wide.contains("session tokens: ↑1,000,000 ↓500,000 Σ1,500,000"), wide);
        String narrow = tokenRow(bar, 60);
        assertTrue(narrow.startsWith("context: 120,000/200,000 60% last input"), narrow);
        assertEquals(59, narrow.length());
        assertTrue(narrow.endsWith("…"));
        bar.setAlert("Connection lost");
        assertTrue(tokenRow(bar, 80).contains("Connection lost"));
        bar.setAlert("");
        assertTrue(tokenRow(bar, 80).contains("context:"));
    }

    @Test
    void wrapsFullTitleAndAllowsScrollingWithoutTakingOverTheChat() {
        var title = new java.util.concurrent.atomic.AtomicReference<>("x".repeat(240) + " THE END");
        var bar = new TopBar(new Object());
        bar.setChatTitleSupplier(title::get);
        String full = AnsiConstants.stripAnsi(bar.render(40));
        assertTrue(full.contains("THE END"), full);
        assertTrue(bar.getHeight(40) > TopBar.TOP_HEIGHT + 1);
        bar.setMaxTitleRows(2);
        assertEquals(TopBar.TOP_HEIGHT + 2, bar.getHeight(40));
        assertTrue(bar.scrollTitle(1000, 40));
        assertTrue(AnsiConstants.stripAnsi(bar.render(40)).contains("THE END"));
        title.set("Renamed chat");
        assertEquals(TopBar.TOP_HEIGHT + 1, bar.getHeight(40));
        assertTrue(AnsiConstants.stripAnsi(bar.render(40)).contains("Chat: Renamed chat"));
        assertFalse(bar.scrollTitle(1, 40));
    }

    @Test
    void diagnosticControlsAndWideTextCannotEscapeTheNoticeRow() {
        var bar = new TopBar(new Object());
        bar.setAlert("\033[24;1H\b\u0007warning\r\n" + "界".repeat(100));
        for (int width : new int[] {1, 4, 20, 80}) {
            String row = tokenRow(bar, width);
            assertFalse(row.chars().anyMatch(Character::isISOControl), row);
            assertTrue(new org.jline.utils.AttributedString(row).columnLength() <= Math.max(0, width - 1), row);
        }
    }

    private static String tokenRow(TopBar bar, int width) {
        String frame = bar.render(width);
        int start = frame.indexOf("\u001b[2;1H") + "\u001b[2;1H".length();
        int end = frame.indexOf("\u001b[3;1H", start);
        return AnsiConstants.stripAnsi(frame.substring(start, end));
    }
}
