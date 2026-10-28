/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.DirectLlmClient.FailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session model ladder: which failures may switch, which rung is chosen and
 * why, and the guarantee that failover state never becomes user intent.
 */
class SessionModelLadderTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    private static SessionModelLadder ladder(Path file, SessionModelLadder.ModelRung... rungs)
            throws IOException {
        StringBuilder json = new StringBuilder("{\"enabled\":true,\"rungs\":[");
        for (int i = 0; i < rungs.length; i++) {
            SessionModelLadder.ModelRung rung = rungs[i];
            if (i > 0) json.append(',');
            json.append('{');
            if (rung.provider() != null) {
                json.append("\"provider\":\"").append(rung.provider()).append("\",");
            }
            json.append("\"model\":\"").append(rung.model()).append('"');
            if (rung.credentialName() != null) {
                json.append(",\"credentialName\":\"").append(rung.credentialName()).append('"');
            }
            if (rung.label() != null) {
                json.append(",\"label\":\"").append(rung.label()).append('"');
            }
            json.append('}');
        }
        json.append("]}");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.toString());
        return SessionModelLadder.load(java.util.List.of(file), FIXED);
    }

    // --- selection -------------------------------------------------------

    @Test
    void prefersSameProviderDifferentModelBeforeCrossingProviders(@TempDir Path dir) throws IOException {
        // Configured order puts the cross-provider rung FIRST. The tier-0 rung
        // (same provider, same credential => model-only change, which keeps the
        // provider session and prompt cache) must still win.
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung("openai", "gpt-5", null, null),
                new SessionModelLadder.ModelRung("anthropic", "claude-sonnet", null, null));

        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.QUOTA_EXHAUSTED, "out of quota",
                "anthropic", "claude-opus", "session-a", Set.of(), 0);

        assertTrue(decision.isPresent());
        assertEquals("anthropic", decision.get().rung().provider());
        assertEquals("claude-sonnet", decision.get().rung().model());
    }

    @Test
    void crossesProvidersOnlyWhenNoCheaperTierIsUsable(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung("openai", "gpt-5", null, null),
                new SessionModelLadder.ModelRung("anthropic", "claude-sonnet", null, null));
        SessionModelLadder.ModelRung sameProvider =
                new SessionModelLadder.ModelRung("anthropic", "claude-sonnet", null, null);

        // Once the tier-0 rung has been tried this turn, the next step is the
        // cross-provider rung.
        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.QUOTA_EXHAUSTED, "", "anthropic", "claude-opus", "session-a",
                Set.of(sameProvider.key("anthropic", "session-a")), 1);

        assertTrue(decision.isPresent());
        assertEquals("openai", decision.get().rung().provider());
    }

    @Test
    void configuredOrderWinsInsideOneTier(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "haiku", null, null),
                new SessionModelLadder.ModelRung(null, "sonnet", null, null));

        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.QUOTA_EXHAUSTED, "", "anthropic", "opus", null, Set.of(), 0);
        assertTrue(decision.isPresent());
        assertEquals("haiku", decision.get().rung().model());
    }

    @Test
    void skipsCurrentModelAndAlreadyTriedRungs(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "a", null, null),
                new SessionModelLadder.ModelRung(null, "b", null, null));
        SessionModelLadder.ModelRung b = new SessionModelLadder.ModelRung(null, "b", null, null);

        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.QUOTA_EXHAUSTED, "", "anthropic", "a", null,
                Set.of(b.key("anthropic", null)), 0);

        assertTrue(decision.isEmpty(), "every remaining rung was tried or is current");
    }

    @Test
    void respectsPerTurnRungCap(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "a", null, null));
        assertTrue(ladder.next(FailureKind.QUOTA_EXHAUSTED, "", "p", "cur", null, Set.of(), 0).isPresent());
        assertTrue(ladder.next(FailureKind.QUOTA_EXHAUSTED, "", "p", "cur", null, Set.of(), 3).isEmpty());
    }

    // --- eligibility -----------------------------------------------------

    @Test
    void quotaExhaustionClimbsTheLadder() throws IOException {
        SessionModelLadder ladder = ladder(Files.createTempDirectory("ladder")
                .resolve("ladder.json"), new SessionModelLadder.ModelRung(null, "b", null, null));
        assertTrue(ladder.isSwitchEligible(FailureKind.QUOTA_EXHAUSTED));
    }

    @Test
    void safetyRefusalNeverSwitches() throws Exception {
        // Switching models to dodge a refusal is circumvention, not resilience.
        SessionModelLadder ladder = ladder(Files.createTempDirectory("ladder")
                .resolve("ladder.json"), new SessionModelLadder.ModelRung(null, "b", null, null));
        assertFalse(ladder.isSwitchEligible(FailureKind.REFUSAL));
        assertTrue(ladder.next(FailureKind.REFUSAL, "refused", "p", "a", null, Set.of(), 0).isEmpty());
    }

    @Test
    void contextOverflowStaysWithCompaction() throws Exception {
        SessionModelLadder ladder = ladder(Files.createTempDirectory("ladder")
                .resolve("ladder.json"), new SessionModelLadder.ModelRung(null, "b", null, null));
        assertFalse(ladder.isSwitchEligible(FailureKind.CONTEXT_OVERFLOW));
    }

    @Test
    void rateLimitSwitchesOnlyWhenOptedIn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ladder.json");
        SessionModelLadder off = ladder(file, new SessionModelLadder.ModelRung(null, "b", null, null));
        assertFalse(off.isSwitchEligible(FailureKind.RATE_LIMITED));

        Files.writeString(file, "{\"enabled\":true,\"failoverOnRateLimit\":true,"
                + "\"rungs\":[{\"model\":\"b\"}]}");
        SessionModelLadder on = SessionModelLadder.load(List.of(file), FIXED);
        assertTrue(on.isSwitchEligible(FailureKind.RATE_LIMITED));
    }

    @Test
    void authFailureRequiresADifferentIdentity(@TempDir Path dir) throws IOException {
        // Same provider + same credential would fail identically; only a
        // different account (or provider) can clear an auth failure.
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "same-account-model", null, null),
                new SessionModelLadder.ModelRung(null, "other-account-model", "session-b", null));

        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.AUTHENTICATION, "401", "anthropic", "opus", "session-a", Set.of(), 0);

        assertTrue(decision.isPresent());
        assertEquals("other-account-model", decision.get().rung().model());
    }

    // --- cooldowns -------------------------------------------------------

    @Test
    void quotaCooldownUsesTheProvidersResetHint(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"));
        Duration cooldown = ladder.cooldownFor(
                FailureKind.QUOTA_EXHAUSTED, "usage limit reached; try again in 45 minutes");
        assertEquals(Duration.ofMinutes(45), cooldown);
    }

    @Test
    void quotaCooldownFallsBackToTheFiveHourWindow(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"));
        assertEquals(Duration.ofHours(5),
                ladder.cooldownFor(FailureKind.QUOTA_EXHAUSTED, "usage limit reached"));
    }

    @Test
    void aCoolingRungIsSkippedUntilItExpires(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ladder.json");
        SessionModelLadder ladder = ladder(file,
                new SessionModelLadder.ModelRung(null, "a", null, null),
                new SessionModelLadder.ModelRung(null, "b", null, null));
        SessionModelLadder.ModelRung a = new SessionModelLadder.ModelRung(null, "a", null, null);

        ladder.markFailed(a, FailureKind.QUOTA_EXHAUSTED, "usage limit reached",
                "anthropic", null);
        String key = a.key("anthropic", null);
        assertTrue(ladder.isInCooldown(key));

        Optional<SessionModelLadder.Decision> decision = ladder.next(
                FailureKind.QUOTA_EXHAUSTED, "", "anthropic", "current", null, Set.of(), 0);
        assertTrue(decision.isPresent());
        assertEquals("b", decision.get().rung().model(), "a is cooling down");
    }

    // --- state vs intent -------------------------------------------------

    @Test
    void userSelectionClearsFailoverState(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "b", null, null));
        SessionModelLadder.ModelRung b = new SessionModelLadder.ModelRung(null, "b", null, null);

        ladder.recordSwitch("a", b, FailureKind.QUOTA_EXHAUSTED, Duration.ofHours(5),
                "anthropic", null);
        assertTrue(ladder.activeRung().isPresent());
        assertFalse(ladder.isInCooldown(b.key("anthropic", null)), "the healthy target is not exhausted");

        ladder.noteUserSelection();

        assertTrue(ladder.activeRung().isEmpty());
        assertFalse(ladder.isInCooldown(b.key("anthropic", null)));
    }

    @Test
    void stateRoundTripsThroughDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ladder.json");
        SessionModelLadder ladder = ladder(file,
                new SessionModelLadder.ModelRung(null, "b", "session-b", "backup account"));
        SessionModelLadder.ModelRung b = ladder.rungs().get(0);

        ladder.recordSwitch("a", b, FailureKind.QUOTA_EXHAUSTED, Duration.ofHours(5),
                "anthropic", "session-a");
        ladder.save();

        SessionModelLadder reloaded = SessionModelLadder.load(List.of(file), FIXED);
        assertEquals(1, reloaded.rungs().size());
        assertEquals("session-b", reloaded.rungs().get(0).credentialName());
        assertTrue(reloaded.activeRung().isPresent());
        assertEquals("b", reloaded.activeRung().get().model());
        assertFalse(reloaded.isInCooldown(b.key("anthropic", "session-a")));
        assertEquals(1, reloaded.history().size());
        assertEquals(FailureKind.QUOTA_EXHAUSTED.name(), reloaded.history().get(0).reason());
    }

    @Test
    void thinkingAndAuthenticationRoundTripWithoutCredentialValues(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ladder.json");
        SessionModelLadder ladder = ladder(file);
        ladder.addRung(new SessionModelLadder.ModelRung("openai", "gpt-backup", "work", "Backup", "high", "api-key"));
        ladder.save();
        var restored = SessionModelLadder.load(List.of(file), FIXED).rungs().get(0);
        assertEquals("high", restored.thinking());
        assertEquals("api-key", restored.authenticationMethod());
        assertEquals("work", restored.credentialName());
        assertFalse(Files.readString(file).contains("apiKey"));
    }

    @Test
    void unknownFailureKindNeverSwitches(@TempDir Path dir) throws IOException {
        SessionModelLadder ladder = ladder(dir.resolve("ladder.json"),
                new SessionModelLadder.ModelRung(null, "b", null, null));
        assertFalse(ladder.isSwitchEligible(FailureKind.NONE));
        assertFalse(ladder.isSwitchEligible(null));
    }

    @Test
    void anEmptyLadderCannotFailOver(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("ladder.json");
        Files.writeString(file, "{\"enabled\":true,\"rungs\":[]}");
        SessionModelLadder ladder = SessionModelLadder.load(List.of(file), FIXED);
        assertTrue(ladder.isEmpty());
        assertTrue(ladder.next(FailureKind.QUOTA_EXHAUSTED, "", "p", "a", null, Set.of(), 0).isEmpty());
    }

    @Test
    void malformedConfigIsIgnoredRatherThanFatal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ladder.json");
        Files.createDirectories(dir);
        Files.writeString(file, "{ this is not json ");
        SessionModelLadder ladder = SessionModelLadder.load(List.of(file), FIXED);
        assertTrue(ladder.isEmpty(), "an unreadable ladder degrades to no failover");
    }

    @Test
    void sessionLayerOverridesProjectLadder(@TempDir Path dir) throws IOException {
        Path global = dir.resolve("global.json");
        Path project = dir.resolve("project.json");
        Path session = dir.resolve("session.json");
        Files.writeString(global, "{\"rungs\":[{\"model\":\"global-model\"}]}");
        Files.writeString(project, "{\"rungs\":[{\"model\":\"project-model\"}]}");
        Files.writeString(session, "{\"rungs\":[{\"model\":\"session-model\"}]}");

        SessionModelLadder ladder = SessionModelLadder.load(List.of(global, project, session), FIXED);

        assertEquals(1, ladder.rungs().size());
        assertEquals("session-model", ladder.rungs().get(0).model());
    }
}
