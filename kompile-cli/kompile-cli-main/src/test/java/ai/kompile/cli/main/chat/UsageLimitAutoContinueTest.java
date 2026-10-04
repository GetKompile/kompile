/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageLimitAutoContinueTest {

    private static UsageLimitAutoContinue.Verdict classify(String failure) {
        return UsageLimitAutoContinue.classify(failure);
    }

    // ========================================================================
    // Classification
    // ========================================================================

    @Test
    void armsOnFiveHourUsageLimitWindow() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "[OpenAI Codex API error 429: usage limit reached]");
        assertTrue(verdict.arm(), verdict.reason());
        assertEquals(UsageLimitAutoContinue.DEFAULT_WINDOW, verdict.horizon());
    }

    @Test
    void armsOnAnthropicLimitReached() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "Anthropic API error 429: You have reached your usage limit");
        assertTrue(verdict.arm());
        assertEquals(UsageLimitAutoContinue.DEFAULT_WINDOW, verdict.horizon());
    }

    @Test
    void ignoresPerMinuteThrottle() {
        assertFalse(classify("429: rate limit exceeded for requests per minute").arm());
        assertFalse(classify("tokens_per_minute quota exceeded").arm());
    }

    @Test
    void ignoresBillingAndCreditExhaustion() {
        assertFalse(classify("429: billing_hard_limit_reached").arm());
        assertFalse(classify("insufficient_quota: your credits are exhausted").arm());
    }

    @Test
    void ignoresWeeklyAndMonthlyWindows() {
        assertFalse(classify("usage limit reached (weekly limit)").arm());
        assertFalse(classify("monthly limit reached").arm());
    }

    @Test
    void ignoresBenignResetHint() {
        assertFalse(classify("You have 1 usage limit reset available. Run /usage").arm());
    }

    @Test
    void ignoresOrdinaryErrors() {
        assertFalse(classify("[Anthropic API error 500: internal server error]").arm());
        assertFalse(classify("connection reset by peer").arm());
        assertFalse(classify(null).arm());
        assertFalse(classify("").arm());
    }

    // ========================================================================
    // Reset-hint parsing
    // ========================================================================

    @Test
    void parsesExplicitResetDuration() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "[Error 429: usage limit reached, resets in 2h 30m]");
        assertTrue(verdict.arm());
        assertEquals(Duration.ofHours(2).plusMinutes(30), verdict.horizon());
    }

    @Test
    void parsesTryAgainInMinutes() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached. Try again in 45 minutes");
        assertTrue(verdict.arm());
        assertEquals(Duration.ofMinutes(45), verdict.horizon());
    }

    @Test
    void fallsBackToDefaultWindowWithoutHint() {
        UsageLimitAutoContinue.Verdict verdict = classify("rate limit: usage limit reached");
        assertTrue(verdict.arm());
        assertEquals(UsageLimitAutoContinue.DEFAULT_WINDOW, verdict.horizon());
    }

    @Test
    void parsesEpochResetTime() {
        long futureEpoch = java.time.Instant.now().plus(Duration.ofMinutes(90))
                .getEpochSecond();
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached; resets at " + futureEpoch);
        assertTrue(verdict.arm());
        // The parsed horizon should be roughly 90 minutes from now.
        assertTrue(verdict.horizon().compareTo(Duration.ofMinutes(80)) > 0
                && verdict.horizon().compareTo(Duration.ofMinutes(91)) < 0,
                "parsed " + verdict.horizon());
    }

    @Test
    void elapsedEpochResetTimeRetriesWithoutAnotherDefaultWindow() {
        long pastEpoch = java.time.Instant.now().minus(Duration.ofHours(1))
                .getEpochSecond();
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached; resets at " + pastEpoch);
        assertTrue(verdict.arm());
        assertEquals(Duration.ZERO, verdict.horizon());
    }

    @Test
    void rejectsHintsBeyondAMonth() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached; try again in 800 hours");
        assertFalse(verdict.arm());
    }

    // ========================================================================
    // Watchdog lifecycle
    // ========================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void resumesAfterWaitAndNotifiesUser() throws Exception {
        List<String> resumes = new ArrayList<>();
        CountDownLatch resumed = new CountDownLatch(1);
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> {
                    synchronized (resumes) {
                        resumes.add(message);
                    }
                    resumed.countDown();
                },
                notices::add);

        // Explicit short horizon so the wake fits in a test; production failures
        // without hints use the five-hour default window.
        String failure = "[Error 429: usage limit reached, try again in 2 seconds]";
        watchdog.onTurnFailure(failure, "fix the failing test", true);
        assertFalse(resumed.await(300, TimeUnit.MILLISECONDS),
                "wake must not fire before the window");
        assertTrue(notices.stream().anyMatch(n -> n.contains("Auto-continue armed")));

        assertTrue(resumed.await(5, TimeUnit.SECONDS), "wake should fire");
        synchronized (resumes) {
            assertEquals(List.of("fix the failing test"), resumes);
        }
        watchdog.shutdown();
    }

    @Test
    void disarmCancelsPendingWake() throws Exception {
        List<String> resumes = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> resumes.add(message), notices::add);

        watchdog.onTurnFailure("429: usage limit reached", "original prompt", true);
        watchdog.disarm();
        Thread.sleep(300); // No scheduled wake must survive a disarm.
        assertTrue(resumes.isEmpty(), "disarmed wake must not resume");
        watchdog.shutdown();
    }

    @Test
    void successCancelsPendingWakeAsStale() throws Exception {
        List<String> resumes = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> resumes.add(message), notices::add);

        watchdog.onTurnFailure("429: usage limit reached", "original prompt", true);
        watchdog.noteTurnSucceeded(); // e.g. the user's queued turn succeeded.
        Thread.sleep(300);
        assertTrue(resumes.isEmpty(), "a wake after any success is stale");
        watchdog.shutdown();
    }

    @Test
    void headlessRunsAreInert() {
        List<String> resumes = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> resumes.add(message), notices::add);
        watchdog.onTurnFailure("429: usage limit reached", "prompt", false);
        assertTrue(notices.isEmpty());
        watchdog.shutdown();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void givesUpAfterConsecutiveFailedResumes() throws Exception {
        List<String> resumes = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> resumes.add(message), notices::add);

        String failure = "429: usage limit reached";
        for (int i = 0; i < UsageLimitAutoContinue.MAX_CONSECUTIVE_RESUMES; i++) {
            notices.clear();
            watchdog.onTurnFailure(failure, "original prompt", true);
            // Each re-arm consumed the previous wake; drain it.
            synchronized (resumes) { /* list grows via wake callbacks */ }
        }
        // First arm schedules wake #1 (not yet fired). MAX arms total are allowed;
        // the MAX+1th arm must refuse and emit the give-up notice.
        watchdog.onTurnFailure(failure, "original prompt", true);
        assertTrue(notices.stream().anyMatch(n ->
                        n.contains("auto-continue stopped")),
                "expected give-up notice, got: " + notices);
        watchdog.shutdown();
    }

    @Test
    void userMessageResetsResumeBudget() {
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                message -> { }, notices::add);

        for (int i = 0; i < UsageLimitAutoContinue.MAX_CONSECUTIVE_RESUMES; i++) {
            watchdog.onTurnFailure("429: usage limit reached", "prompt", true);
        }
        watchdog.onTurnFailure("429: usage limit reached", "a different prompt", true);
        assertTrue(notices.stream().anyMatch(n -> n.contains("Auto-continue armed")),
                "a user-authored message must reset the budget");
        watchdog.shutdown();
    }

    private static final java.time.Clock NOW = java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-02T16:00:00Z"), java.time.ZoneOffset.UTC);

    private static UsageLimitAutoContinue.Verdict vendor(String provider, String detail) {
        return UsageLimitAutoContinue.classify(provider, detail, NOW);
    }

    @Test
    void zaiFiveHourBusinessCodesKeepPlanResetDespiteExtraUsageBillingText() {
        for (String code : List.of("1308", "1316", "1318", "1320")) {
            var verdict = vendor("zai", "Provider credits or usage limit exhausted. Check billing "
                    + "(code=" + code + ", message=Usage limit reached for the past 5 hours. "
                    + "Extra usage is not available due to monthly spend limit. "
                    + "Resets at 2026-10-02 18:30:00)");
            assertTrue(verdict.arm(), code);
            assertEquals(Duration.ofMinutes(150), verdict.horizon(), code);
        }
    }

    @Test
    void zaiExpiredPlansAndBalanceDoNotResumeEvenWithResetMetadata() {
        for (String code : List.of("1113", "1309", "1314")) {
            assertFalse(vendor("zai", "Provider usage limit exhausted (code=" + code
                    + ", reset_at=1790964000)").arm(), code);
        }
        assertFalse(vendor("custom", "code=1318: insufficient balance; resets in 2h").arm());
    }

    @Test
    void weeklyLimitsNeedExplicitResetAndNeverUseFiveHourGuess() {
        for (String code : List.of("1310", "1317", "1319", "1321")) {
            assertFalse(vendor("zai", "usage limit reached (code=" + code + ")").arm());
            var verdict = vendor("zai", "Provider credits or usage limit exhausted "
                    + "(code=" + code + ", reset_at=2026-10-07T16:00:00Z)");
            assertTrue(verdict.arm());
            assertEquals(Duration.ofDays(5), verdict.horizon());
        }
    }

    @Test
    void codexStructuredResetSecondsAndEpochMillisecondsAreHonored() {
        var verdict = vendor("openai-codex", "Provider credits or usage limit exhausted "
                + "(type=usage_limit_reached, resets_in_seconds=4200)");
        assertTrue(verdict.arm());
        assertEquals(Duration.ofMinutes(70), verdict.horizon());
        long reset = NOW.instant().plusSeconds(7200).toEpochMilli();
        assertEquals(Duration.ofHours(2), vendor("openai-codex",
                "type=usage_limit_reached, resets_at=" + reset).horizon());
    }

    @Test
    void codexWeeklyResetIsAllowedOnlyWhenProviderReportsIt() {
        var verdict = vendor("openai-codex", "type=usage_limit_reached, resets_in_seconds=529498");
        assertTrue(verdict.arm());
        assertEquals(Duration.ofSeconds(529498), verdict.horizon());
    }

    @Test
    void claudeClockResetAndTimezoneAreHonored() {
        var verdict = vendor("claude", "You've hit your limit · resets 5pm (America/New_York)");
        assertTrue(verdict.arm());
        assertEquals(Duration.ofHours(5), verdict.horizon());
        assertEquals(Duration.ofMinutes(30), vendor("anthropic",
                "You’ve hit your usage limit · resets 4:30pm (UTC)").horizon());
        assertEquals(Duration.ofHours(13), vendor("claude",
                "You've hit your limit - resets 5am (UTC)").horizon());
    }

    @Test
    void absoluteOffsetTimestampsAndLatestBlockedWindowAreHonored() {
        assertEquals(Duration.ofMinutes(90), vendor("zai", "code=1308, "
                + "message=Your limit will reset at 2026-10-02T19:30:00+02:00").horizon());
        long first = NOW.instant().plusSeconds(3600).getEpochSecond();
        long second = NOW.instant().plusSeconds(7200).getEpochSecond();
        assertEquals(Duration.ofHours(2), vendor("anthropic", "429 rate limit "
                + "(reset_at=" + first + ", reset_at=" + second + ")").horizon());
    }

    @Test
    void elapsedExplicitResetIsDueNowRatherThanAnotherDefaultWindow() {
        long elapsed = NOW.instant().minusSeconds(2).getEpochSecond();
        assertEquals(Duration.ZERO, vendor("codex", "usage_limit_reached; resets_at=" + elapsed).horizon());
        assertEquals(Duration.ZERO, vendor("zai", "code=1308; resets at 2026-10-02T15:59:58Z").horizon());
    }

    @Test
    void malformedResetHintsDoNotCrashOrSchedule() {
        assertFalse(vendor("zai", "code=1308; resets in 999999999999999999 hours").arm());
        assertFalse(vendor("zai", "code=1308; resets in 9999999999999999999999999999 hours").arm());
        assertFalse(vendor("claude", "hit your limit; resets 25:99").arm());
        assertFalse(vendor("claude", "hit your limit; resets 0am").arm());
        assertFalse(vendor("zai", "code=1308; resets at 2026-99-02T18:00:00Z").arm());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void realAutomaticRetriesRetainBudgetInsteadOfLoopingForever() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger resumes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<UsageLimitAutoContinue> ref = new java.util.concurrent.atomic.AtomicReference<>();
        String failure = "usage limit reached; retry in 0 seconds";
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(message -> {
            resumes.incrementAndGet();
            ref.get().onTurnFailure(failure, message, true);
        }, notice -> { if (notice.contains("auto-continue stopped")) stopped.countDown(); });
        ref.set(watchdog);
        try {
            watchdog.onTurnFailure(failure, "original prompt", true);
            assertTrue(stopped.await(6, TimeUnit.SECONDS));
            assertEquals(UsageLimitAutoContinue.MAX_CONSECUTIVE_RESUMES, resumes.get());
        } finally { watchdog.shutdown(); }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void providerChangeDropsTheWake() throws Exception {
        var provider = new java.util.concurrent.atomic.AtomicReference<>("zai");
        CountDownLatch resumed = new CountDownLatch(1);
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(
                resume -> resumed.countDown(), notice -> {}, provider::get);
        try {
            watchdog.onTurnFailure("code=1308; retry in 0 seconds", "prompt", true);
            provider.set("openai-codex");
            assertFalse(resumed.await(1500, TimeUnit.MILLISECONDS));
        } finally { watchdog.shutdown(); }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void userTakeoverInvalidatesAnAlreadyFiredResumeTicket() throws Exception {
        var request = new java.util.concurrent.atomic.AtomicReference<UsageLimitAutoContinue.Resume>();
        CountDownLatch fired = new CountDownLatch(1);
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(resume -> {
            request.set(resume);
            fired.countDown();
        }, notice -> {}, () -> "claude");
        try {
            watchdog.onTurnFailure("hit your limit; retry in 0 seconds", "prompt", true);
            assertTrue(fired.await(3, TimeUnit.SECONDS));
            assertEquals("claude", request.get().provider());
            assertTrue(watchdog.isCurrent(request.get()));
            watchdog.disarm();
            assertFalse(watchdog.isCurrent(request.get()));
        } finally { watchdog.shutdown(); }
    }

    @Test
    void relativeMultiDayHintsIncludeAllUnitsAndAllBlockedWindows() {
        assertEquals(Duration.ofDays(7), vendor("zai", "code=1310; resets in 7 days").horizon());
        assertEquals(Duration.ofDays(7).plusHours(2),
                vendor("zai", "code=1310; resets in 7 days 2 hours").horizon());
        assertEquals(Duration.ofDays(7).plusHours(2), vendor("zai",
                "code=1310; resets in 2 hours, resets in 7 days and 2 hours").horizon());
        assertEquals(Duration.ofDays(7), vendor("zai", "code=1310; retry in 1 week").horizon());
        assertFalse(vendor("zai", "code=1310; resets in 40 days 2 hours").arm());
        assertFalse(vendor("zai", "code=1310; retry in 999999999999999999999 days 2 hours").arm());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancellationBetweenFailureCheckAndArmingRejectsTheOldTurn() throws Exception {
        CountDownLatch failureChecked = new CountDownLatch(1);
        CountDownLatch allowArming = new CountDownLatch(1);
        List<String> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(message -> {}, notices::add);
        UsageLimitAutoContinue.Turn turn = watchdog.beginTurn();
        Thread failing = new Thread(() -> {
            failureChecked.countDown(); // The handler passed its cancellation check.
            try {
                allowArming.await();
                watchdog.onTurnFailure("usage limit reached; retry in 0 seconds", "prompt", true, turn);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            failing.start();
            assertTrue(failureChecked.await(1, TimeUnit.SECONDS));
            watchdog.disarm(); // Escape/user takeover wins before failure arming.
            allowArming.countDown();
            failing.join(1_000);
            assertFalse(failing.isAlive());
            assertTrue(notices.isEmpty(), "cancelled turn must never arm a new ticket");
        } finally {
            allowArming.countDown();
            failing.interrupt();
            failing.join(1_000);
            watchdog.shutdown();
        }
    }

    @Test
    void providerChangeWhileTheRequestIsInFlightRejectsFailureArming() {
        var provider = new java.util.concurrent.atomic.AtomicReference<>("zai");
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(resume -> {}, notices::add, provider::get);
        try {
            UsageLimitAutoContinue.Turn turn = watchdog.beginTurn();
            provider.set("claude");
            watchdog.onTurnFailure("code=1308; retry in 0 seconds", "prompt", true, turn);
            assertTrue(notices.isEmpty());
        } finally { watchdog.shutdown(); }
    }

    @Test
    void shutdownPreventsRearming() {
        List<String> notices = new ArrayList<>();
        UsageLimitAutoContinue watchdog = new UsageLimitAutoContinue(message -> {}, notices::add);
        watchdog.shutdown();
        watchdog.onTurnFailure("usage limit reached", "prompt", true);
        assertTrue(notices.isEmpty());
    }

    @Test
    void humanizeFormatsDurations() {
        assertEquals("45m", UsageLimitAutoContinue.humanize(Duration.ofMinutes(45)));
        assertEquals("45m 30s", UsageLimitAutoContinue.humanize(
                Duration.ofMinutes(45).plusSeconds(30)));
        assertEquals("5h 0m", UsageLimitAutoContinue.humanize(Duration.ofHours(5)));
    }
}
