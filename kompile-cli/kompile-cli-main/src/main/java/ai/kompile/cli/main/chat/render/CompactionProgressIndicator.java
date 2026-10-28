/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.utils.AnsiConstants;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Live, in-place progress UI for context compaction.
 * <p>
 * Compaction is a long, silent LLM call (seconds to minutes). This indicator
 * renders it the same way running tools are rendered: one replaceable
 * transcript block updated in place via {@link ChatCompleter#upsertDisplayBlock}
 * (or {@link ChatCompleter#upsertTranscriptBlock} for older hosts),
 * plus a status-bar activity label. Because the block is replaced rather than
 * appended, animated frames can never scroll through, overlap the input row,
 * or leave stale progress text in the transcript.
 * <p>
 * Rendering contract:
 * <ul>
 *   <li><b>Managed TUI:</b> an animated two-line block (spinner + phase, marquee
 *   bar + counts + elapsed) that collapses into a single-line result notice on
 *   completion.</li>
 *   <li><b>Fallback (headless / plain JLine):</b> exactly one start line through
 *   the caller's append-only sink and one final line at completion. No animation
 *   — carriage-return games corrupt prompt redraws.</li>
 * </ul>
 * <p>
 * The indicator never writes to stdout directly; every emission goes through
 * the transcript-block hook or the caller-provided sink.
 */
public final class CompactionProgressIndicator implements CompactionProgress {

    private static final long DEFAULT_TICK_MILLIS = 120L;
    private static final int BAR_CELLS = 20;
    private static final int BAR_WINDOW = 6;
    private static final int MAX_PHASE_CHARS = 60;

    /** Status-bar activity label shown while this indicator owns the status surface. */
    public static final String ACTIVITY_LABEL = "Compacting";

    private final String blockKey;
    private final TerminalRenderer renderer;
    private final Consumer<String> appendOnlySink;
    private final Consumer<String> activitySetter;
    private final long tickMillis;
    private final long startedNanos = System.nanoTime();
    private final int entriesBefore;
    private final long tokensBefore;
    private final String previousActivity;
    private final String provider;

    private final Object lock = new Object();
    private String phase = "Preparing checkpoint";
    private boolean activityOwned;
    private boolean finished;
    private boolean fallbackAnnounced;
    private int frame;
    private Thread animator;

    private CompactionProgressIndicator(String blockKey, TerminalRenderer renderer,
                                        int entriesBefore, long tokensBefore,
                                        Consumer<String> appendOnlySink,
                                        Consumer<String> activitySetter,
                                        long tickMillis, String provider) {
        this.provider = provider;
        this.blockKey = blockKey;
        this.renderer = renderer;
        this.entriesBefore = Math.max(0, entriesBefore);
        this.tokensBefore = Math.max(0, tokensBefore);
        this.appendOnlySink = appendOnlySink;
        this.activitySetter = activitySetter;
        this.tickMillis = Math.max(1L, tickMillis);
        this.previousActivity = ChatCompleter.getActivity();
    }

    /**
     * Create the indicator, show the status-bar activity label, publish the
     * first frame, and start the animator thread.
     */
    public static CompactionProgressIndicator start(String blockKey, TerminalRenderer renderer,
                                                    int entriesBefore, long tokensBefore,
                                                    Consumer<String> appendOnlySink,
                                                    Consumer<String> activitySetter) {
        CompactionProgressIndicator indicator = new CompactionProgressIndicator(
                blockKey, renderer, entriesBefore, tokensBefore,
                appendOnlySink, activitySetter, DEFAULT_TICK_MILLIS, null);
        indicator.begin();
        return indicator;
    }

    /** Native providers do not expose a portable history estimate or a post-compaction size. */
    public static CompactionProgressIndicator startProvider(String blockKey, TerminalRenderer renderer,
                                                            String provider,
                                                            Consumer<String> appendOnlySink,
                                                            Consumer<String> activitySetter) {
        return startProvider(blockKey, renderer, provider, 0, appendOnlySink, activitySetter);
    }

    /** Last measured request input is useful context, not an exact live compaction size. */
    public static CompactionProgressIndicator startProvider(String blockKey, TerminalRenderer renderer,
                                                            String provider, long lastInputTokens,
                                                            Consumer<String> appendOnlySink,
                                                            Consumer<String> activitySetter) {
        CompactionProgressIndicator indicator = new CompactionProgressIndicator(
                blockKey, renderer, 0, lastInputTokens,
                appendOnlySink, activitySetter, DEFAULT_TICK_MILLIS, provider);
        indicator.phase = provider + " is summarizing its session";
        indicator.begin();
        return indicator;
    }

    private void begin() {
        synchronized (lock) {
            if (activitySetter != null) {
                try {
                    activitySetter.accept(ACTIVITY_LABEL);
                    activityOwned = true;
                } catch (RuntimeException ignored) {
                    // The status surface must never break the compaction itself.
                }
            }
        }
        publishActiveFrame();
        animator = new Thread(ChatSessionContext.current().wrap(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(tickMillis);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lock) {
                    if (finished) return;
                    frame++;
                }
                publishActiveFrame();
            }
        }), "compaction-progress");
        animator.setDaemon(true);
        animator.start();
    }

    /** Update the phase label (e.g. "Summarizing conversation"); republishes immediately. */
    @Override
    public void phase(String label) {
        if (label == null || label.isBlank()) return;
        String trimmed = label.trim();
        if (trimmed.length() > MAX_PHASE_CHARS) {
            trimmed = trimmed.substring(0, MAX_PHASE_CHARS - 1) + "…";
        }
        synchronized (lock) {
            if (finished) return;
            if (phase.equals(trimmed)) return;
            phase = trimmed;
        }
        publishActiveFrame();
    }

    /** Publish the single-line success notice and stop animating. */
    public void complete(int tokensBefore, int tokensAfter, int preservedTurns) {
        publishFinished(renderCompleteNotice(tokensBefore, tokensAfter, preservedTurns));
    }

    /** Complete the same progress block without inventing provider context savings. */
    public void completeProvider(String trigger, long tokensBefore) {
        publishFinished(renderer.renderProviderCompactionNotice(provider, trigger, tokensBefore)
                + renderer.dim(" · in " + elapsed(System.nanoTime())));
    }

    /** Publish a dim one-line result for a compaction that had nothing to do. */
    public void noop(String message) {
        publishFinished(renderer.dim("  · " + safeMessage(message)));
    }

    /** Publish a yellow one-line notice for an unsupported compaction mode. */
    public void unsupported(String message) {
        publishFinished(renderer.yellow("  ◇ " + safeMessage(message)));
    }

    /** Publish a red one-line failure notice and stop animating. */
    public void failed(String message) {
        publishFinished(renderer.red("  ✗ " + safeMessage(message)), true);
    }

    /**
     * Finish the indicator if a caller return path skipped a terminal publish
     * (unexpected exception, cancellation race). No-op once finished.
     */
    public void abandonIfActive(String message) {
        boolean active;
        synchronized (lock) {
            active = !finished;
        }
        if (!active) return;
        if (message == null || message.isBlank()) {
            noop("Compaction ended");
        } else {
            failed(message);
        }
    }

    // ========================================================================
    // Publishing
    // ========================================================================

    private void publishActiveFrame() {
        synchronized (lock) {
            if (finished) return;
            String block = provider == null
                    ? renderActiveFrame(renderer, phase, frame, entriesBefore, tokensBefore,
                            elapsed(System.nanoTime()))
                    : renderFrame(renderer, phase, frame, tokensBefore > 0
                            ? "last reported input: " + formatTokens(tokensBefore) + " tokens"
                            : "provider context size not reported", elapsed(System.nanoTime()));
            // Serialize frames with terminal publication: no late tick may revive a finished block.
            boolean stillManaged = publishBlock(block, true, false);
            if (!stillManaged && !fallbackAnnounced && appendOnlySink != null) {
                // Unmanaged surface: never stream animated frames; one honest
                // start line, and the final line comes with the terminal state.
                fallbackAnnounced = true;
                appendOnlySink.accept(block);
            }
        }
    }

    private boolean publishBlock(String content, boolean running, boolean error) {
        String text = TerminalRenderer.printableToolOutput(content);
        return ChatCompleter.upsertDisplayBlock(new ChatDisplayBlock(blockKey, "notice", "", text, content,
                List.of(new ChatDisplayBlock.Section("body", "notice", "", text, content)), error, running))
                || ChatCompleter.upsertTranscriptBlock(blockKey, content);
    }

    private void publishFinished(String notice) {
        publishFinished(notice, false);
    }

    private void publishFinished(String notice, boolean error) {
        // Atomic claim: exactly one terminal publication per indicator, even
        // when complete/failed/abandonIfActive race on different threads.
        Thread animatorThread;
        synchronized (lock) {
            if (finished) return;
            finished = true;
            animatorThread = animator;
            animator = null;
        }
        if (animatorThread != null) {
            animatorThread.interrupt();
        }
        releaseActivity();
        boolean stillManaged = publishBlock(notice, false, error);
        if (!stillManaged && appendOnlySink != null) {
            appendOnlySink.accept(notice);
        }
    }

    private void releaseActivity() {
        synchronized (lock) {
            if (!activityOwned) return;
            activityOwned = false;
        }
        if (activitySetter != null) {
            try {
                // Restore whatever the status bar showed before compaction
                // ("Thinking" during a turn, null at a quiet prompt).
                activitySetter.accept(previousActivity);
            } catch (RuntimeException ignored) {
                // The status surface must never break the compaction itself.
            }
        }
    }

    // ========================================================================
    // Rendering (package-visible for tests)
    // ========================================================================

    /** Two-line animated frame: spinner + phase, marquee bar + counts + elapsed. */
    static String renderActiveFrame(TerminalRenderer renderer, String phase, int frame,
                                    int entries, long tokens, String elapsed) {
        return renderFrame(renderer, phase, frame, entries + " entr" + (entries == 1 ? "y" : "ies")
                + " · portable history estimate: ~" + formatTokens(tokens) + " tokens", elapsed);
    }

    private static String renderFrame(TerminalRenderer renderer, String phase, int frame,
                                      String counts, String elapsed) {
        String spinner = AnsiConstants.SPINNER_FRAMES[
                Math.floorMod(frame, AnsiConstants.SPINNER_FRAMES.length)];
        return "  " + renderer.yellow(spinner) + " " + renderer.cyan("Compacting context")
                + renderer.dim(" — " + phase + "…") + "\n"
                + "  " + renderBar(renderer, frame)
                + renderer.dim(" · " + counts + " · " + elapsed);
    }

    /** Indeterminate marquee bar: a lit window travels around the track. */
    static String renderBar(TerminalRenderer renderer, int frame) {
        StringBuilder bar = new StringBuilder(BAR_CELLS);
        int head = Math.floorMod(frame, BAR_CELLS);
        for (int i = 0; i < BAR_CELLS; i++) {
            int distance = Math.abs(i - head);
            distance = Math.min(distance, BAR_CELLS - distance);
            bar.append(distance < BAR_WINDOW ? renderer.cyan("▰") : renderer.dim("▱"));
        }
        return bar.toString();
    }

    String renderCompleteNotice(int tokensBeforeArg, int tokensAfter, int preservedTurns) {
        return "  " + renderer.green("✓ Context compacted")
                + renderer.dim(" · portable history estimate: ~" + formatTokens(tokensBeforeArg) + " → ~"
                + formatTokens(tokensAfter) + " tokens ("
                + savedPercent(tokensBeforeArg, tokensAfter) + "% history reduction)"
                + " · provider context not measured · in "
                + elapsed(System.nanoTime())
                + (preservedTurns > 0
                ? " · " + preservedTurns + " recent turn" + (preservedTurns == 1 ? "" : "s")
                + " preserved"
                : ""));
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "compaction ended" : message.trim();
    }

    static String formatTokens(long tokens) {
        return String.format(Locale.ROOT, "%,d", tokens);
    }

    static int savedPercent(int before, int after) {
        if (before <= 0 || after < 0) return 0;
        double ratio = 1.0d - ((double) after / before);
        return (int) Math.round(Math.max(0.0d, Math.min(1.0d, ratio)) * 100.0d);
    }

    String elapsed(long nowNanos) {
        double seconds = (nowNanos - startedNanos) / 1_000_000_000.0d;
        if (seconds < 10.0d) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        long totalSeconds = (long) seconds;
        if (totalSeconds < 60L) {
            return totalSeconds + "s";
        }
        return (totalSeconds / 60) + "m " + (totalSeconds % 60) + "s";
    }

    /** Whether a terminal state (complete/noop/unsupported/failed) was published. */
    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }
}
