package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.activity.*;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Reads the existing session journal, including writes made by a separate MCP JVM.
 * No model cooperation, project-wide store scan, or second notification store is required. */
final class TestMilestoneDisplay implements AutoCloseable {
    private final ActivityJournalReader reader;
    private final ActivityIdentity identity;
    private final Consumer<ChatDisplayBlock> display;
    private final TerminalRenderer renderer;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "test-milestone-display");
        thread.setDaemon(true);
        return thread;
    });
    private long offset;
    private boolean closed;
    private boolean started;
    private boolean readWarning;

    TestMilestoneDisplay(ActivityStorage storage, ActivityIdentity identity,
                         TerminalRenderer renderer, Consumer<ChatDisplayBlock> display) {
        this.reader = new ActivityJournalReader(storage.eventsPath(identity));
        this.identity = identity;
        this.renderer = renderer;
        this.display = display;
    }

    synchronized void start() {
        if (closed || started) return;
        started = true;
        executor.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    synchronized void poll() {
        if (closed) return;
        try {
            var batch = reader.readFrom(offset, ActivityReadBudget.DEFAULT);
            for (ActivityEvent event : batch.events()) {
                if (!identity.conversationId().equals(event.identity().conversationId())) continue;
                if (event.eventType().equals("test.milestone.recorded")) {
                    display.accept(block(event));
                } else if (event.eventType().equals("test.milestone.notice_failed")
                        || event.eventType().equals("test.milestone.capture_failed")) {
                    display.accept(warning(event.operationId(), event.attributes().get("message")));
                }
            }
            offset = batch.nextOffset();
            readWarning = false;
        } catch (IOException | RuntimeException failure) {
            // Retry from the last acknowledged offset. Stable block ids make delivery idempotent.
            if (!readWarning) {
                readWarning = true;
                display.accept(warning("journal", "Cannot display test milestones: " + failure.getMessage()));
            }
        }
    }

    private ChatDisplayBlock block(ActivityEvent event) {
        Map<String, String> evidence = event.attributes();
        boolean automatic = "harness".equals(evidence.get("source"))
                || "test-process".equals(evidence.get("attribution"));
        String outcome = evidence.getOrDefault("outcome", "unknown");
        StringBuilder summary = new StringBuilder("Test milestone · ").append(event.operationId())
                .append(automatic ? " · automatic" : " · manual (agent-reported)")
                .append(" · ").append(outcome);
        for (String key : List.of("module", "totalTests", "passed", "failed", "errors", "skipped")) {
            if (evidence.containsKey(key)) summary.append(" · ").append(key).append('=').append(evidence.get(key));
        }
        String detail = clean(summary.toString()) + "\n" + clean(evidence.toString());
        String line = "  " + renderer.bold(renderer.cyan(clean(summary.toString())));
        return new ChatDisplayBlock("test-milestone:" + event.operationId(), "notification", "",
                line, line, List.of(new ChatDisplayBlock.Section("milestone", "notification",
                "Test milestone evidence", detail)), !outcome.equals("passed"), false);
    }

    private ChatDisplayBlock warning(String id, String message) {
        String line = "  " + renderer.yellow(clean(message));
        return new ChatDisplayBlock("test-milestone-warning:" + id, "notification", "",
                line, line, List.of(), true, false);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
    }
}
