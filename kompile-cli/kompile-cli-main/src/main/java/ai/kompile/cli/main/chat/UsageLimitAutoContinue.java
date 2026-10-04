/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 *       weekly/monthly windows without reset metadata are not guessed: the first are
 *       transient; billing needs human action. Multi-day windows resume only
 *       when the provider supplies an explicit reset.</li>
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
    private static final Duration MAX_HINT_WINDOW = Duration.ofDays(31);
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
    record Resume(String message, String provider, long ticket) { }
    /** Snapshot taken when the owner accepts a turn, before cancellation can race its failure. */
    record Turn(String provider, long generation) { }

    Turn beginTurn() {
        synchronized (lock) {
            return new Turn(currentProvider.get(), generation);
        }
    }

    private final Consumer<Resume> resumeAction;
    private final Supplier<String> currentProvider;
    private long generation;
    private boolean closed;
    private final Consumer<String> notices;
    private ScheduledFuture<?> pending;
    private String armedMessage;
    private long successfulTurns;
    private long successfulTurnsAtArm;
    private int consecutiveResumes;

    public UsageLimitAutoContinue(Consumer<String> resumeAction, Consumer<String> notices) {
        this(resume -> resumeAction.accept(resume.message()), notices, () -> "");
        Objects.requireNonNull(resumeAction, "resumeAction");
    }

    UsageLimitAutoContinue(Consumer<Resume> resumeAction, Consumer<String> notices,
                                 Supplier<String> currentProvider) {
        this.resumeAction = Objects.requireNonNull(resumeAction, "resumeAction");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.currentProvider = Objects.requireNonNull(currentProvider, "currentProvider");
    }

    /**
     * Inspect a terminal turn failure and, when it is a usage-limit window,
     * arm the auto-continue wake for {@code originalMessage}. Inert when
     * {@code interactive} is false (headless one-shot runs must not sleep
     * for hours) or the feature is disabled.
     */
    public void onTurnFailure(String failureDetail, String originalMessage, boolean interactive) {
        onTurnFailure(failureDetail, originalMessage, interactive, beginTurn());
    }

    void onTurnFailure(String failureDetail, String originalMessage, boolean interactive, Turn turn) {
        if (!interactive || !enabled() || turn == null) return;
        String provider = turn.provider();
        Verdict verdict = classify(provider, failureDetail, Clock.systemDefaultZone());
        if (!verdict.arm()) return;

        String armedNotice;
        synchronized (lock) {
            if (closed || turn.generation() != generation
                    || !Objects.equals(provider, currentProvider.get())) return;
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
                long ticket = generation;
                pending = SCHEDULER.schedule(
                        () -> fire(originalMessage, provider, armedAt, ticket), delayMillis, TimeUnit.MILLISECONDS);
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
            cancelPendingLocked();
            armedMessage = null;
            consecutiveResumes = 0;
        }
    }

    /** Cancel any pending wake; the user (or shutdown) is taking over. */
    public void disarm() {
        synchronized (lock) {
            cancelPendingLocked();
            armedMessage = null;
            consecutiveResumes = 0;
        }
    }

    /** Disarm and drop all retry budget; the owning session is closing. */
    public void shutdown() {
        synchronized (lock) {
            closed = true;
            cancelPendingLocked();
            armedMessage = null;
        }
    }

    private void fire(String message, String provider, long armedAtSuccessCount, long ticket) {
        synchronized (lock) {
            if (closed || ticket != generation || successfulTurns != armedAtSuccessCount
                    || !Objects.equals(provider, currentProvider.get()) || !enabled()) return;
            pending = null;
            // Keep the failed message and budget across automatic retries.
        }
        if (message != null) resumeAction.accept(new Resume(message, provider, ticket));
    }

    /** Recheck under the dispatch lock: user input may have won after the timer fired. */
    boolean isCurrent(Resume resume) {
        synchronized (lock) {
            return !closed && enabled() && resume.ticket() == generation
                    && Objects.equals(resume.message(), armedMessage)
                    && Objects.equals(resume.provider(), currentProvider.get());
        }
    }

    private void cancelPendingLocked() {
        generation++;
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
        return classify("", failureDetail, Clock.systemDefaultZone());
    }

    static Verdict classify(String provider, String failureDetail, Clock clock) {
        if (failureDetail == null || failureDetail.isBlank()) return Verdict.NONE;
        String normalized = failureDetail.toLowerCase(Locale.ROOT).replace('’', '\'');
        boolean zai = "zai".equalsIgnoreCase(provider);
        String code = businessCode(normalized);
        // These Z.AI business codes are billing/subscription failures, not windows.
        if (zai && (code.equals("1113") || code.equals("1309") || code.equals("1314"))) return Verdict.NONE;
        boolean zaiWindow = zai && (code.equals("1308") || code.equals("1310")
                || code.matches("131[6-9]|132[01]"));
        boolean structuredWindow = normalized.contains("usage_limit_reached") || zaiWindow;

        // Per-minute/per-second throttles are transient and already covered by
        // the in-band connectivity retry loop.
        if (normalized.contains("per minute") || normalized.contains("per second")
                || normalized.contains("requests_per") || normalized.contains("tokens_per")
                || normalized.contains("_rpm") || normalized.contains("_tpm")) {
            return Verdict.NONE;
        }
        // Billing exhaustion needs human action, not a wait.
        if (!structuredWindow && (normalized.contains("billing") || normalized.contains("credit")
                || normalized.contains("insufficient_quota") || normalized.contains("insufficient balance")
                || normalized.contains("subscription") && normalized.contains("expired"))) {
            return Verdict.NONE;
        }
        // Benign hint ("You have 1 usage limit reset available") is not a failure.
        if (normalized.contains("reset available")) {
            return Verdict.NONE;
        }

        Duration hint;
        try {
            hint = parseResetHint(normalized, clock);
        } catch (ArithmeticException | NumberFormatException | java.time.DateTimeException malformed) {
            return Verdict.NONE;
        }
        if (hint != null && hint.compareTo(MAX_HINT_WINDOW) > 0) return Verdict.NONE;
        // Multi-day windows need an actual reset, never the five-hour fallback.
        boolean longWindow = normalized.contains("weekly") || normalized.contains("monthly")
                || normalized.contains("7 days") || normalized.contains("7 day")
                || normalized.contains("annual") || normalized.contains("yearly")
                || zai && (code.equals("1310") || code.matches("131[79]|1321"));
        // "monthly spend limit" is only about extra usage; Z.AI's 5h plan still resets.
        if (zai && code.matches("131[68]|1320")) longWindow = false;
        if (longWindow && hint == null) return Verdict.NONE;
        boolean hourScaleHint = hint != null && hint.compareTo(Duration.ofHours(1)) >= 0;

        boolean usageLimitPhrase =
                normalized.contains("usage limit") || normalized.contains("usage_limit")
                        || normalized.contains("limit reached")
                        || normalized.contains("reached your limit")
                        || normalized.contains("hit your limit")
                        || normalized.contains("hit your usage limit")
                        || normalized.contains("exceeded your limit");
        boolean statusLimited =
                (normalized.contains("429") || normalized.contains("rate limit"))
                        && hourScaleHint;

        if (!structuredWindow && !usageLimitPhrase && !statusLimited) return Verdict.NONE;
        return new Verdict(true, hint != null ? hint : DEFAULT_WINDOW,
                hint != null ? "Provider usage-limit window with explicit reset time"
                        : "Provider usage-limit window (default five-hour horizon)");
    }

    private static String businessCode(String detail) {
        Matcher match = Pattern.compile("(?:code[\\s\"']*[:=][\\s\"']*)([0-9]{4})\\b").matcher(detail);
        return match.find() ? match.group(1) : "";
    }

    /**
     * Parse an explicit reset horizon from failure text: relative durations
     * ("try again in 45 minutes", "resets in 2h 30m") or epoch timestamps
     * ("resets at 1750000000"). Returns null when no parsable hint exists.
     */
    static Duration parseResetHint(String normalized) {
        return parseResetHint(normalized, Clock.systemDefaultZone());
    }

    private static Duration parseResetHint(String detail, Clock clock) {
        Duration latest = durationHint(detail);
        // Prefer the latest applicable reset when both 5h and weekly limits reject.
        Matcher epochs = EPOCH_HINT.matcher(detail);
        while (epochs.find()) {
            long raw = Long.parseLong(epochs.group(1));
            Instant at = raw >= 1_000_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
            Duration remaining = Duration.between(clock.instant(), at);
            // A reset that passed while the response was in flight is due now,
            // not a reason to fall back to another five-hour wait.
            latest = later(latest, remaining.isNegative() ? Duration.ZERO : remaining);
        }
        Matcher seconds = SECONDS_HINT.matcher(detail);
        while (seconds.find()) latest = later(latest, Duration.ofSeconds(Long.parseLong(seconds.group(1))));
        Matcher dates = DATE_HINT.matcher(detail);
        while (dates.find()) {
            String value = dates.group(1).replace(' ', 'T').toUpperCase(Locale.ROOT);
            Instant at = value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")
                    ? java.time.OffsetDateTime.parse(value).toInstant()
                    : LocalDateTime.parse(value).atZone(clock.getZone()).toInstant();
            Duration remaining = Duration.between(clock.instant(), at);
            // A reset that passed while the response was in flight is due now,
            // not a reason to fall back to another five-hour wait.
            latest = later(latest, remaining.isNegative() ? Duration.ZERO : remaining);
        }
        Matcher time = CLOCK_HINT.matcher(detail);
        if (latest == null && time.find()) {
            int hour = Integer.parseInt(time.group(1));
            int minute = time.group(2) == null ? 0 : Integer.parseInt(time.group(2));
            String period = time.group(3);
            if (period != null) {
                if (hour < 1 || hour > 12) throw new java.time.DateTimeException("Invalid reset clock hour");
                hour = hour % 12 + (period.equals("pm") ? 12 : 0);
            }
            ZoneId zone = clock.getZone();
            // Provider diagnostics sanitize parentheses to underscores. Preserve
            // the reported zone in both native and sanitized clock hints.
            String reported = time.group(4) != null ? time.group(4) : time.group(5);
            if (reported != null) {
                String canonical = ZoneId.getAvailableZoneIds().stream()
                        .filter(id -> id.equalsIgnoreCase(reported)).findFirst().orElse(reported);
                zone = ZoneId.of(canonical);
            }
            ZonedDateTime now = clock.instant().atZone(zone);
            ZonedDateTime at = now.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(zone);
            if (!at.isAfter(now)) at = at.plusDays(1);
            latest = Duration.between(clock.instant(), at.toInstant());
        }
        return latest;
    }

    private static Duration later(Duration a, Duration b) {
        return a == null || b.compareTo(a) > 0 ? b : a;
    }

    private static final String DURATION_UNIT =
            "weeks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s";
    private static final Pattern RESET_HINT = Pattern.compile(
            "(?:resets?[\\s_]?in|try[\\s_]?again[\\s_]?in|retry[\\s_]?in)\\s*"
                    + "((?:[0-9]+\\s*(?:" + DURATION_UNIT + ")\\b\\s*(?:,|and)?\\s*)+)");
    private static final Pattern DURATION_PART =
            Pattern.compile("([0-9]+)\\s*(" + DURATION_UNIT + ")\\b");

    private static Duration durationHint(String normalized) {
        Matcher hint = RESET_HINT.matcher(normalized);
        Duration latest = null;
        while (hint.find()) {
            Matcher parts = DURATION_PART.matcher(hint.group(1));
            Duration total = Duration.ZERO;
            while (parts.find()) {
                long value = Long.parseLong(parts.group(1));
                String unit = parts.group(2);
                if (unit.startsWith("w")) {
                    total = total.plus(Duration.ofDays(Math.multiplyExact(value, 7)));
                } else if (unit.startsWith("d")) {
                    total = total.plus(Duration.ofDays(value));
                } else if (unit.startsWith("h")) {
                    total = total.plus(Duration.ofHours(value));
                } else if (unit.startsWith("m")) {
                    total = total.plus(Duration.ofMinutes(value));
                } else {
                    total = total.plus(Duration.ofSeconds(value));
                }
            }
            latest = later(latest, total);
        }
        return latest;
    }

    private static final Pattern EPOCH_HINT =
            Pattern.compile("resets?[\\s_]*(?:at|time)?[\\s_:=\"']*(\\d{10,13})\\b");
    private static final Pattern SECONDS_HINT = Pattern.compile(
            "(?:resets_in_seconds|retry_after)[\\s:=\"']*([0-9]{1,9})(?![0-9])\\b");
    private static final Pattern DATE_HINT = Pattern.compile(
            "(?:resets?[\\s_]*(?:at|time)?|try again at)[\\s:=\"']*"
                    + "([0-9]{4}-[0-9]{2}-[0-9]{2}[t ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?(?:z|[+-][0-9]{2}:[0-9]{2})?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_HINT = Pattern.compile(
            "(?:resets?(?: at)?|try again at)\\s+([0-9]{1,2})(?::([0-9]{2}))?\\s*(am|pm)?"
                    + "(?:\\s*(?:\\(([A-Za-z_]+/[A-Za-z_]+|UTC|GMT)\\)"
                    + "|_([A-Za-z_]+/[A-Za-z_]+|UTC|GMT)_))?(?![A-Za-z0-9:])",
            Pattern.CASE_INSENSITIVE);

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
