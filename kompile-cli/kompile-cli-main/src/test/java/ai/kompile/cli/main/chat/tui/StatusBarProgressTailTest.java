package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.ForegroundRequestProgress;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.AnsiConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering-level checks for the request-scoped foreground indicator:
 * the StatusBar foreground segment must show icon, working word, live token
 * tail, and elapsed time while a progress window is open — and revert to a
 * plain bar when it is closed.
 */
class StatusBarProgressTailTest {

    private BackgroundProcessManager processes;
    private StatusBar bar;
    private ChatUiSession session;
    private ChatUiSession.Binding binding;

    @AfterEach
    void tearDown() {
        try {
            ChatCompleter.setActivity(null);
        } finally {
            if (binding != null) binding.close();
            if (session != null) session.close();
            if (processes != null) processes.close();
        }
    }

    /** Construct the bar bound to a fresh session and keep the binding open for the test. */
    private StatusBar barFor() {
        session = new ChatUiSession();
        binding = session.bind();
        BackgroundProcessManager pm =
                new BackgroundProcessManager("status-progress-tail-test");
        processes = pm;
        return new StatusBar(
                new BackgroundTaskManager(), pm, null, new TerminalRenderer(true));
    }

    @Test
    void activeProgressRendersWordTokenTailAndElapsed() {
        bar = barFor();
        ForegroundRequestProgress progress = session.progress();

        AtomicLong clock = new AtomicLong(10_000);
        progress.setClockMillis(clock::get);
        progress.setWordSupplier(() -> "Pondering");

        long seq = progress.begin();
        progress.recordTextDelta("x".repeat(800)); // 200 est tokens

        ChatCompleter.setActivity("Thinking");
        String rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));

        assertTrue(rendered.contains("Pondering"), rendered);
        assertTrue(rendered.contains("~200"), rendered);
        assertTrue(rendered.contains("tokens"), rendered);

        clock.set(22_000);
        rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));
        assertTrue(rendered.contains("12s"), rendered);

        // Exact usage replaces even an overestimate: tilde disappears.
        progress.recordExactOutput(160);
        rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));
        assertTrue(rendered.contains("160"), rendered);
        assertFalse(rendered.contains("~"), rendered);

        // Finishing the request removes the tail on the next redraw.
        progress.finish(seq);
        rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));
        assertFalse(rendered.contains("tokens"), rendered);
    }

    @Test
    void terminalInterruptedActivityOmitsProgressTail() {
        bar = barFor();
        ForegroundRequestProgress progress = session.progress();

        progress.begin();
        ChatCompleter.setActivity("Thinking");
        ChatCompleter.markInterrupted();

        String rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));
        assertTrue(rendered.contains("Interrupted"), rendered);
        assertFalse(rendered.contains("tokens"), rendered);
    }

    @Test
    void noProgressWindowPlainBarUnchanged() {
        bar = barFor();
        ChatCompleter.setActivity("Thinking");

        String rendered = AnsiConstants.stripAnsi(bar.buildStatusContent(200));
        assertTrue(rendered.contains("Thinking"), rendered);
        assertFalse(rendered.contains("tokens"), rendered);
    }

    @Test
    void narrowTerminalTruncatesWithoutWrapping() {
        bar = barFor();
        ForegroundRequestProgress progress = session.progress();

        progress.begin();
        ChatCompleter.setActivity("Working: an intentionally very long tool invocation");
        String rendered = bar.buildStatusContent(20);

        assertFalse(rendered.contains("\n"), rendered);
        assertFalse(rendered.contains("\r"), rendered);
        assertEquals(19, AnsiConstants.stripAnsi(rendered).length());
    }
}
