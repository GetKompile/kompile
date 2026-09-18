/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-continue watchdog for provider usage-limit windows (the rolling five-hour
 * quota used by subscription plans such as Claude Pro/Max and ChatGPT).
 *
 * <p>The in-band connectivity retry loop in {@code DirectLlmClient} bridges only
 * seconds-scale transport failures with a bounded attempt budget; a multi-hour
 * quota window always ends the turn with a terminal 429-style failure. When a
 * terminal failure matches the usage-limit family, this component schedules a
 * daemon wake for the provider's reset horizon — parsed from the failure text
 * when present ("try again in 45 minutes", epoch "resets at"), otherwise the
 * default five-hour window — notifies the user, and on expiry re-dispatches the
 * failed message through the ordinary chat dispatch path.</p>
 *
 * <p>Lifecycle rules:
 * <ul>
 *   <li>Any user-submitted message disarms a pending wake (the user is driving).</li>
 *   <li>A wake that fires after any turn has succeeded is stale and dropped.</li>
 *   <li>After {@value #MAX_CONSECUTIVE_RESUMES} consecutive auto-resumes that
 *       still hit the limit, the watchdog gives up and says so instead of
 *       silently looping for hours.</li>
 *   <li>Per-minute/per-second throttles, billing/credit exhaustion and
 *       weekly/monthly windows are explicitly NOT auto-continued: the first are
 *       transient, the latter two need human action or a multi-day wait.</li>
 * </ul></p>
 *
 * <p>Disable with {@code -Dkompile.chat.autocontinue=false} or environment
 * variable {@code KOMPILE_CHAT_AUTO_CONTINUE=false}.</p>
 */
public final class UsageLimitAutoContinue {

    /** Decision produced from a terminal failure message. */
    record Verdict(boolean arm, Duration horizon, String reason) {
        static final Verdict NONE = new Verdict(false, Duration.ZERO, "not a usage-limit window");
    }

    static final Duration DEFAULT_WINDOW = Duration.ofHours(5);
    /** Upper bound on parsed reset hints; anything longer needs human attention. */
    private static final Duration MAX_HINT_WINDOW = Duration.ofHours(24);
    static final int MAX_CONSECUTIVE_RESUMES = 3;
    private static final String ENABLE_PROPERTY = "kompile.chat.autocontinue";
    private static final String ENABLE_ENV = "KOMPILE_CHAT_AUTO_CONTINUE";

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "usage-limit-auto-continue");
                thread.setDaemon(true);
                return thread;
            });

    private final Object lock = new Object();
    private final Consumer<String> resumeAction;
    private final Consumer<String> notices;
    private ScheduledFuture<?> pending;
    private String armedMessage;
    private long successfulTurns;
    private long successfulTurnsAtArm;
    private int consecutiveResumes;

    public UsageLimitAutoContinue(Consumer<String> resumeAction, Consumer<String> notices) {
        this.resumeAction = Objects.requireNonNull(resumeAction, "resumeAction");
        this.notices = Objects.requireNonNull(notices, "notices");
    }

    /**
     * Inspect a terminal turn failure and, when it is a usage-limit window,
     * arm the auto-continue wake for {@code originalMessage}. Inert when
     * {@code interactive} is false (headless one-shot runs must not sleep
     * for hours) or the feature is disabled.
     */
    public void onTurnFailure(String failureDetail, String originalMessage, boolean interactive) {
        if (!interactive || !enabled()) return;
        Verdict verdict = classify(failureDetail);
        if (!verdict.arm()) return;

        String armedNotice;
        synchronized (lock) {
            // A different message means the user moved on; restart the budget.
            if (originalMessage == null || !originalMessage.equals(armedMessage)) {
                consecutiveResumes = 0;
            }
            if (consecutiveResumes >= MAX_CONSECUTIVE_RESUMES) {
                cancelPendingLocked();
                armedMessage = null;
                armedNotice = "⚠ Usage limit still active after " + MAX_CONSECUTIVE_RESUMES
                        + " auto-continue attempts — auto-continue stopped. "
                        + "Retry manually once the provider window resets.";
            } else {
                long delayMillis = Math.max(1_000L, verdict.horizon().toMillis());
                cancelPendingLocked();
                consecutiveResumes++;
                armedMessage = originalMessage;
                successfulTurnsAtArm = successfulTurns;
                long armedAt = successfulTurnsAtArm;
                pending = SCHEDULER.schedule(
                        () -> fire(originalMessage, armedAt), delayMillis, TimeUnit.MILLISECONDS);
                armedNotice = "⏳ " + verdict.reason() + " detected. Auto-continue armed — will resend "
                        + "your last message in " + humanize(verdict.horizon()) + " (at "
                        + CLOCK.format(Instant.now().plusMillis(delayMillis)) + "). "
                        + "Send any message to take over; disable with -D"
                        + ENABLE_PROPERTY + "=false.";
            }
        }
        notices.accept(armedNotice);
    }

    /** A turn produced a model response: the provider is healthy again. */
    public void noteTurnSucceeded() {
        synchronized (lock) {
            successfulTurns++;
            consecutiveResumes = 0;
        }
    }

    /** Cancel any pending wake; the user (or shutdown) is taking over. */
    public void disarm() {
        synchronized (lock) {
            cancelPendingLocked();
            armedMessage = null;
        }
    }

    /** Disarm and drop all retry budget; the owning session is closing. */
    public void shutdown() {
        disarm();
    }

    private void fire(String message, long armedAtSuccessCount) {
        boolean stale;
        synchronized (lock) {
            pending = null;
            armedMessage = null;
            stale = successfulTurns != armedAtSuccessCount;
        }
        if (stale || message == null) return;
        resumeAction.accept(message);
    }

    private void cancelPendingLocked() {
        ScheduledFuture<?> scheduled = pending;
        pending = null;
        if (scheduled != null) scheduled.cancel(false);
    }

    static boolean enabled() {
        String property = System.getProperty(ENABLE_PROPERTY);
        if (property != null) {
            String value = property.trim();
            return !value.isEmpty() && !"false".equalsIgnoreCase(value) && !"0".equals(value)
                    && !"off".equalsIgnoreCase(value);
        }
        String env = System.getenv(ENABLE_ENV);
        return env == null || env.isBlank() || !"false".equalsIgnoreCase(env.trim());
    }

    /**
     * Classify a terminal provider failure. Arms only for usage-limit/quota
     * windows that a bounded sleep can bridge.
     */
    static Verdict classify(String failureDetail) {
        if (failureDetail == null || failureDetail.isBlank()) return Verdict.NONE;
        String normalized = failureDetail.toLowerCase(Locale.ROOT).replace('-', '_');

        // Long-horizon windows (days) are not bridgeable by an overnight wake.
        if (normalized.contains("weekly") || normalized.contains("monthly")
                || normalized.contains("annual") || normalized.contains("yearly")) {
            return Verdict.NONE;
        }
        // Per-minute/per-second throttles are transient and already covered by
        // the in-band connectivity retry loop.
        if (normalized.contains("per minute") || normalized.contains("per second")
                || normalized.contains("requests_per") || normalized.contains("tokens_per")
                || normalized.contains("_rpm") || normalized.contains("_tpm")) {
            return Verdict.NONE;
        }
        // Billing exhaustion needs human action, not a wait.
        if (normalized.contains("billing") || normalized.contains("credit")) {
            return Verdict.NONE;
        }
        // Benign hint ("You have 1 usage limit reset available") is not a failure.
        if (normalized.contains("reset available")) {
            return Verdict.NONE;
        }

        Duration hint = parseResetHint(normalized);
        if (hint != null && hint.compareTo(MAX_HINT_WINDOW) > 0) return Verdict.NONE;
        boolean hourScaleHint = hint != null && hint.compareTo(Duration.ofHours(1)) >= 0;

        boolean usageLimitPhrase =
                normalized.contains("usage limit") || normalized.contains("usage_limit")
                        || normalized.contains("limit reached")
                        || normalized.contains("reached your limit")
                        || normalized.contains("hit your limit")
                        || normalized.contains("exceeded your limit");
        boolean statusLimited =
                (normalized.contains("429") || normalized.contains("rate limit"))
                        && hourScaleHint;

        if (!usageLimitPhrase && !statusLimited) return Verdict.NONE;
        return new Verdict(true, hint != null ? hint : DEFAULT_WINDOW,
                hourScaleHint ? "Provider usage-limit window with explicit reset time"
                        : "Provider usage-limit window (default five-hour horizon)");
    }

    /**
     * Parse an explicit reset horizon from failure text: relative durations
     * ("try again in 45 minutes", "resets in 2h 30m") or epoch timestamps
     * ("resets at 1750000000"). Returns null when no parsable hint exists.
     */
    static Duration parseResetHint(String normalized) {
        Duration duration = durationHint(normalized);
        if (duration != null) return duration;
        return epochHint(normalized);
    }

    private static final Pattern RESET_HINT =
            Pattern.compile("(?:resets?[\\s_]?in|try[\\s_]?again[\\s_]?in|retry[\\s_]?in)([^.;]{0,60})");
    private static final Pattern DURATION_PART =
            Pattern.compile("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b");

    private static Duration durationHint(String normalized) {
        Matcher hint = RESET_HINT.matcher(normalized);
        if (!hint.find()) return null;
        Matcher parts = DURATION_PART.matcher(hint.group(1));
        Duration total = Duration.ZERO;
        boolean any = false;
        while (parts.find()) {
            long value;
            try {
                value = Long.parseLong(parts.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String unit = parts.group(2);
            if (unit.startsWith("h")) {
                total = total.plus(Duration.ofHours(value));
            } else if (unit.startsWith("m")) {
                total = total.plus(Duration.ofMinutes(value));
            } else {
                total = total.plus(Duration.ofSeconds(value));
            }
            any = true;
        }
        return any ? total : null;
    }

    private static final Pattern EPOCH_HINT =
            Pattern.compile("resets?[\\s_]*(?:at|time)?[\\s_:]*(\\d{10,13})\\b");

    private static Duration epochHint(String normalized) {
        Matcher hint = EPOCH_HINT.matcher(normalized);
        if (!hint.find()) return null;
        long raw;
        try {
            raw = Long.parseLong(hint.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        Instant resetAt = raw >= 1_000_000_000_000L
                ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        Duration remaining = Duration.between(Instant.now(), resetAt);
        // A past epoch is stale metadata, not a horizon.
        return remaining.isNegative() || remaining.isZero() ? null : remaining;
    }

    static String humanize(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            long remainder = seconds % 60;
            return remainder == 0 ? minutes + "m" : minutes + "m " + remainder + "s";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
