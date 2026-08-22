/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.render;

import org.jline.terminal.Terminal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes foreground activity to the real JLine terminal using OSC 2.
 *
 * Keeping the terminal reference here is important: writing to System.out can
 * target a different stream in tests, embedded shells, or redirected sessions.
 */
public final class TerminalTitleController {
    private static final String ESC = "\033]";
    private static final String BEL = "\007";
    private static final int MAX_TITLE_LENGTH = 120;
    private static final String[] BUSY_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧"};

    private final Object lock = new Object();
    private volatile Terminal terminal;
    private volatile String readyTitle = "kompile chat";
    private ScheduledExecutorService animator;
    private ScheduledFuture<?> busyAnimation;
    private String busyTitle = "";
    private int busyFrame;
    private long activityVersion;

    public void attach(Terminal terminal, String readyTitle) {
        synchronized (lock) {
            stopBusyLocked();
            this.terminal = terminal;
            if (readyTitle != null && !readyTitle.isBlank()) {
                this.readyTitle = sanitize(readyTitle);
            }
            writeLocked(this.readyTitle);
        }
    }

    public void detach() {
        synchronized (lock) {
            stopBusyLocked();
            writeLocked(this.readyTitle);
            terminal = null;
            if (animator != null) {
                animator.shutdownNow();
                animator = null;
            }
        }
    }

    public void update(ChatActivityPhase phase, String detail) {
        if (phase == null || phase == ChatActivityPhase.READY) {
            reset();
            return;
        }
        StringBuilder title = new StringBuilder(readyTitle).append(" · ").append(phase.label());
        if (detail != null && !detail.isBlank()) {
            title.append(" · ").append(detail.trim());
        }
        if (phase.isBusy()) {
            startBusy(sanitize(title.toString()));
        } else {
            writeNonBusy(sanitize(title.toString()));
        }
    }

    public void updateLabel(String label) {
        update(ChatActivityPhase.fromLabel(label), ChatActivityPhase.detailFromLabel(label));
    }

    /** Replace the idle/session title and immediately return to that ready state. */
    public void setReadyTitle(String title) {
        if (title == null || title.isBlank()) return;
        synchronized (lock) {
            stopBusyLocked();
            readyTitle = sanitize(title);
            writeLocked(readyTitle);
        }
    }

    public void setRawTitle(String title) {
        if (title == null || title.isBlank()) {
            reset();
            return;
        }
        writeNonBusy(sanitize(title));
    }

    public void reset() {
        synchronized (lock) {
            stopBusyLocked();
            writeLocked(readyTitle);
        }
    }

    private void writeNonBusy(String title) {
        synchronized (lock) {
            stopBusyLocked();
            writeLocked(title);
        }
    }

    private void startBusy(String title) {
        synchronized (lock) {
            stopBusyLocked();
            busyTitle = title;
            busyFrame = 0;
            long version = ++activityVersion;
            writeLocked(renderBusyTitle());
            ensureAnimatorLocked();
            busyAnimation = animator.scheduleAtFixedRate(
                    () -> advanceBusyFrame(version), 160, 160, TimeUnit.MILLISECONDS);
        }
    }

    private void ensureAnimatorLocked() {
        if (animator != null && !animator.isShutdown()) return;
        animator = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kompile-title-busy");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void advanceBusyFrame(long version) {
        synchronized (lock) {
            if (busyAnimation == null || version != activityVersion || terminal == null) return;
            busyFrame = (busyFrame + 1) % BUSY_FRAMES.length;
            writeLocked(renderBusyTitle());
        }
    }

    private String renderBusyTitle() {
        return BUSY_FRAMES[busyFrame % BUSY_FRAMES.length] + " " + busyTitle;
    }

    private void stopBusyLocked() {
        activityVersion++;
        if (busyAnimation != null) {
            busyAnimation.cancel(false);
            busyAnimation = null;
        }
        busyTitle = "";
        busyFrame = 0;
    }

    private void writeLocked(String title) {
        Terminal active = terminal;
        if (active == null || "dumb".equalsIgnoreCase(active.getType())) return;
        try {
            // Use the same ordered writer as JLine and KompileTui. Writing OSC
            // frames through Terminal.output() can interleave bytes with a
            // concurrent prompt repaint and corrupt both the title and cursor.
            active.writer().print(ESC + "2;" + title + BEL);
            active.writer().flush();
        } catch (RuntimeException ignored) {
            // A closing terminal must not interrupt the chat turn.
        }
    }

    private static String sanitize(String title) {
        String clean = title.replace("\033", "").replace("\007", "")
                .replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= MAX_TITLE_LENGTH
                ? clean
                : clean.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }
}
