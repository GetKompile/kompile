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
    void ignoresStaleEpochResetTime() {
        long pastEpoch = java.time.Instant.now().minus(Duration.ofHours(1))
                .getEpochSecond();
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached; resets at " + pastEpoch);
        assertTrue(verdict.arm());
        assertEquals(UsageLimitAutoContinue.DEFAULT_WINDOW, verdict.horizon());
    }

    @Test
    void rejectsHourScaleHintBeyondADay() {
        UsageLimitAutoContinue.Verdict verdict = classify(
                "usage limit reached; try again in 30 hours");
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

    @Test
    void humanizeFormatsDurations() {
        assertEquals("45m", UsageLimitAutoContinue.humanize(Duration.ofMinutes(45)));
        assertEquals("45m 30s", UsageLimitAutoContinue.humanize(
                Duration.ofMinutes(45).plusSeconds(30)));
        assertEquals("5h 0m", UsageLimitAutoContinue.humanize(Duration.ofHours(5)));
    }
}
