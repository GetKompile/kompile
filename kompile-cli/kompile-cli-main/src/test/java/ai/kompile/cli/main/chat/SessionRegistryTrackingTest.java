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

package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the crash-recovery session tracking core: the persisted resume
 * config and the session registry that backs {@code resume-all} and the
 * {@code resume} tool's {@code recent}/{@code resume_all} actions.
 */
class SessionRegistryTrackingTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void useIsolatedHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    // ── ResumeConfig ─────────────────────────────────────────────────────────

    @Test
    void resumeConfigDefaultsToTenWithoutFile() {
        ResumeConfig config = ResumeConfig.load();
        assertEquals(ResumeConfig.DEFAULT_RECENT_SESSIONS, config.getRecentSessions());
        assertEquals(10, config.getRecentSessions());
    }

    @Test
    void resumeConfigRoundTripsAndRejectsNonPositive() {
        ResumeConfig config = ResumeConfig.load();
        config.setRecentSessions(25);
        assertTrue(config.save());

        assertTrue(Files.exists(ResumeConfig.configFile()),
                "resume.json should be written under the isolated home");

        ResumeConfig reloaded = ResumeConfig.load();
        assertEquals(25, reloaded.getRecentSessions());

        reloaded.setRecentSessions(0);
        assertEquals(10, reloaded.getRecentSessions(),
                "non-positive values must fall back to the default");
    }

    @Test
    void setRecentReportsPersistenceFailure() throws Exception {
        Path configDirectory = ResumeConfig.configFile().getParent();
        Files.createDirectories(configDirectory.getParent());
        Files.writeString(configDirectory, "not a directory");

        assertEquals(1, ResumeAllCommand.executeInline("--set-recent 20"));
        assertFalse(Files.exists(ResumeConfig.configFile()));
    }

    @Test
    void resumePickerConfirmsAllOrSelectsByNumberAndFullUuid() {
        List<SessionEntry> entries = pickerEntries();
        assertEquals(entries, ResumeAllCommand.selectSessions(entries, (lines, prompt) -> "YES"));
        var replies = new java.util.ArrayDeque<>(List.of("no", "2, " + entries.get(0).getKompileSessionId() + ", 2"));
        var screens = new java.util.ArrayList<String>();
        assertEquals(entries, ResumeAllCommand.selectSessions(entries, (lines, prompt) -> {
            screens.addAll(lines);
            return replies.remove();
        }));
        String menu = String.join("\n", screens);
        assertTrue(menu.contains(entries.get(0).getKompileSessionId()));
        assertTrue(menu.contains("First session title"));
        assertTrue(menu.contains("2026-09-01T12:30:00Z"));
        assertTrue(menu.contains("2026-08-31T10:00:00Z"), "missing end falls back to start");
        assertTrue(menu.contains("(untitled)"));
    }

    @Test
    void resumePickerRejectsInvalidInputWithoutAcceptingPartialSelection() {
        List<SessionEntry> entries = pickerEntries();
        var replies = new java.util.ArrayDeque<>(List.of("maybe", "", "1,99", "0", "bad-uuid", "2"));
        var screens = new java.util.ArrayList<String>();
        assertEquals(List.of(entries.get(1)), ResumeAllCommand.selectSessions(entries, (lines, prompt) -> {
            screens.addAll(lines);
            return replies.remove();
        }));
        assertTrue(screens.stream().anyMatch(line -> line.contains("Please answer")));
        assertTrue(screens.stream().anyMatch(line -> line.contains("Invalid selection")));
        assertTrue(replies.isEmpty());
    }

    @Test
    void resumePickerCancelsAndHandlesLegacyMissingMetadata() {
        assertTrue(ResumeAllCommand.selectSessions(List.of(), (lines, prompt) -> {
            throw new AssertionError("Empty candidates must not prompt");
        }).isEmpty());
        assertTrue(ResumeAllCommand.selectSessions(pickerEntries(), (lines, prompt) -> null).isEmpty());
        assertTrue(ResumeAllCommand.selectSessions(pickerEntries(), (lines, prompt) -> "q").isEmpty());
        var replies = new java.util.ArrayDeque<>(List.of("n", ""));
        assertTrue(ResumeAllCommand.selectSessions(pickerEntries(), (lines, prompt) -> replies.remove()).isEmpty());
        SessionEntry legacy = SessionEntry.builder().conversationId("native-id").startedAt("invalid").build();
        var legacyReplies = new java.util.ArrayDeque<>(List.of("n", "native-id"));
        var screens = new java.util.ArrayList<String>();
        assertEquals(List.of(legacy), ResumeAllCommand.selectSessions(List.of(legacy), (lines, prompt) -> {
            screens.addAll(lines);
            return legacyReplies.remove();
        }));
        assertTrue(String.join("\n", screens).contains("native-id | (untitled) | unknown"));
    }

    @Test
    void resumeAllClaimsOnlySelectedSessionsAfterPrompt() throws Exception {
        registerPickerEntries();
        var replies = new java.util.ArrayDeque<>(List.of("n", "2"));
        try (var launchers = org.mockito.Mockito.mockConstruction(TerminalLauncher.class)) {
            assertEquals(0, ResumeAllCommand.executeInline("--recent 2", (lines, prompt) -> {
                assertEquals(2, SessionRegistry.load().getResumable().size(), "no claims before selection");
                return replies.remove();
            }));
            assertEquals(1, launchers.constructed().size());
            org.mockito.Mockito.verify(launchers.constructed().get(0)).launch(
                    org.mockito.ArgumentMatchers.argThat(command -> command.contains(pickerEntries().get(1).getKompileSessionId())),
                    org.mockito.ArgumentMatchers.eq(tempDir), org.mockito.ArgumentMatchers.anyString());
        }
        assertEquals("exited", SessionRegistry.load().get(pickerEntries().get(0).getKompileSessionId()).orElseThrow().getStatus());
        assertEquals("resuming", SessionRegistry.load().get(pickerEntries().get(1).getKompileSessionId()).orElseThrow().getStatus());
    }

    @Test
    void cancelledInterruptedAndPreviewResumeNeverClaimOrLaunch() {
        registerPickerEntries();
        try (var launchers = org.mockito.Mockito.mockConstruction(TerminalLauncher.class)) {
            assertEquals(0, ResumeAllCommand.executeInline("", (lines, prompt) -> "cancel"));
            assertEquals(0, ResumeAllCommand.executeInline("", (lines, prompt) -> {
                throw new org.jline.reader.UserInterruptException("");
            }));
            assertEquals(0, ResumeAllCommand.executeInline("", (lines, prompt) -> {
                throw new org.jline.reader.EndOfFileException();
            }));
            assertEquals(0, ResumeAllCommand.executeInline("--list", (lines, prompt) -> {
                throw new AssertionError("Listing must not prompt");
            }));
            assertTrue(launchers.constructed().isEmpty());
            assertEquals(0, ResumeAllCommand.executeInline("--dry-run", (lines, prompt) -> {
                throw new AssertionError("Dry run must not prompt");
            }));
            org.mockito.Mockito.verify(launchers.constructed().get(0), org.mockito.Mockito.never())
                    .launch(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(2, SessionRegistry.load().getResumable().size());
    }

    @Test
    void explicitYesSkipsPromptAndConcurrentClaimDoesNotSubstituteOtherSessions() throws Exception {
        registerPickerEntries();
        try (var launchers = org.mockito.Mockito.mockConstruction(TerminalLauncher.class)) {
            assertEquals(0, ResumeAllCommand.executeInline("--yes --recent 1", (lines, prompt) -> {
                throw new AssertionError("Explicit yes must not prompt");
            }));
            org.mockito.Mockito.verify(launchers.constructed().get(0)).launch(
                    org.mockito.ArgumentMatchers.argThat(command -> command.contains(pickerEntries().get(0).getKompileSessionId())),
                    org.mockito.ArgumentMatchers.eq(tempDir), org.mockito.ArgumentMatchers.anyString());
        }
        registerPickerEntries();
        try (var launchers = org.mockito.Mockito.mockConstruction(TerminalLauncher.class)) {
            assertEquals(0, ResumeAllCommand.executeInline("--recent 1", (lines, prompt) -> {
                SessionRegistry.load().claimResumable(
                        entry -> entry.getKompileSessionId().equals(pickerEntries().get(0).getKompileSessionId()),
                        null, 1, "other-invocation");
                return "yes";
            }));
            assertTrue(launchers.constructed().isEmpty(), "must not launch the unshown second session");
        }
    }

    private List<SessionEntry> pickerEntries() {
        return List.of(
                SessionEntry.builder().kompileSessionId("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa")
                        .title("First session title").status("exited").projectDirectory(tempDir.toString())
                        .startedAt("2026-09-01T10:00:00Z").endedAt("2026-09-01T12:30:00Z").build(),
                SessionEntry.builder().kompileSessionId("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb")
                        .status("exited").projectDirectory(tempDir.toString())
                        .startedAt("2026-08-31T10:00:00Z").build());
    }

    private void registerPickerEntries() {
        SessionRegistry registry = SessionRegistry.load();
        for (SessionEntry entry : pickerEntries()) {
            registry.register(entry.getKompileSessionId(), "kompile", tempDir.toString(), "local", 0L);
            registry.markExited(entry.getKompileSessionId());
            SessionEntry stored = registry.get(entry.getKompileSessionId()).orElseThrow();
            stored.setStartedAt(entry.getStartedAt());
            stored.setEndedAt(entry.getEndedAt());
            stored.setTitle(entry.getTitle());
            registry.save();
        }
    }

    // ── SessionRegistry ──────────────────────────────────────────────────────

    @Test
    void registerPersistsEntryWithRunningStatus() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("session-a", "kompile", "/work/project", "local", 12345L);

        SessionRegistry reloaded = SessionRegistry.load();
        assertEquals(1, reloaded.size());
        SessionEntry entry = reloaded.get("session-a").orElseThrow();
        assertEquals("running", entry.getStatus());
        assertEquals("kompile", entry.getAgent());
        assertEquals("/work/project", entry.getProjectDirectory());
        assertEquals(12345L, entry.getPid());
        assertTrue(entry.getPid() > 0);
    }

    @Test
    void managedPassthroughRegistersItsModeAndExitsItsOwnRow() {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        command.agent = "codex";
        command.workingDir = tempDir.toString();

        command.registerManagedSession("managed-session");

        SessionEntry running = SessionRegistry.load().get("managed-session").orElseThrow();
        assertEquals("running", running.getStatus());
        assertEquals("passthrough", running.getLaunchMode());
        assertEquals("codex", running.getAgent());
        assertEquals(ProcessHandle.current().pid(), running.getPid());

        command.markManagedSessionExited("managed-session");
        assertEquals("exited", SessionRegistry.load().get("managed-session")
                .orElseThrow().getStatus());
    }

    @Test
    void registerIsUpsertSafeForResumedSessions() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("session-a", "kompile", "/work/project", "local", 1L);
        registry.setConversationId("session-a", "native-uuid-1");
        registry.setTitle("session-a", "Fix the parser");

        // Re-register the same transcript (a resumed session)
        SessionEntry updated = SessionRegistry.load().register(
                "session-a", "kompile", "/work/project", "local", 2L);

        assertEquals("native-uuid-1", updated.getConversationId(),
                "re-registration must keep the harvested conversation ID");
        assertEquals("Fix the parser", updated.getTitle(),
                "re-registration must keep the title");

        SessionRegistry reloaded = SessionRegistry.load();
        assertEquals(1, reloaded.size(), "no duplicate rows");
        assertEquals("running", reloaded.get("session-a").orElseThrow().getStatus());
    }

    @Test
    void markExitedAndRecentOrdering() throws Exception {
        SessionRegistry registry = SessionRegistry.load();
        long deadPid = spawnDeadProcess();
        registry.register("old-session", "kompile", "/work", "local", deadPid);
        Thread.sleep(5); // startedAt is millisecond-precision ISO instants
        registry.register("new-session", "kompile", "/work", "local",
                ProcessHandle.current().pid());
        registry.markExited("old-session");

        SessionRegistry reloaded = SessionRegistry.load();
        assertEquals("exited", reloaded.get("old-session").orElseThrow().getStatus());
        assertEquals("running", reloaded.get("new-session").orElseThrow().getStatus(),
                "own live PID stays running");

        List<SessionEntry> recent = reloaded.getRecent(1);
        assertEquals(1, recent.size());
        assertEquals("new-session", recent.get(0).getKompileSessionId(),
                "getRecent orders newest first");

        // getResumable() returns EXITED sessions (finished or crash-flipped);
        // the live-PID entry stays "running" and is correctly not resumable yet.
        reloaded.refreshStatuses(); // no-op: old-session already exited, new-session alive
        assertEquals(1, reloaded.getResumable().size());
        assertEquals("old-session", reloaded.getResumable().get(0).getKompileSessionId());
    }

    @Test
    void unparsableStartedAtSortsLastInRecent() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("bad-ts", "kompile", "/work", "local", 0L);
        registry.register("good-ts", "kompile", "/work", "local", 0L);
        registry.get("bad-ts").orElseThrow().setStartedAt("not-a-timestamp");
        registry.save();

        SessionRegistry reloaded = SessionRegistry.load();
        List<SessionEntry> recent = reloaded.getRecent(10);
        assertEquals("good-ts", recent.get(0).getKompileSessionId());
        assertEquals("bad-ts", recent.get(recent.size() - 1).getKompileSessionId());
    }

    @Test
    void deadProcessIsDetectedAsExitedViaRefresh() {
        SessionRegistry registry = SessionRegistry.load();
        long deadPid = spawnDeadProcess();
        // Simulate crash: entry stays "running" while its process is gone
        registry.register("crashed-session", "kompile", "/work", "local", deadPid);
        registry.refreshStatuses();

        SessionEntry crashed = registry.get("crashed-session").orElseThrow();
        assertEquals("exited", crashed.getStatus(),
                "dead-PID refresh must flip crashed sessions to exited (resumable)");
        assertTrue(crashed.getEndedAt() == null || crashed.getEndedAt().isBlank(),
                "detecting a dead PID must not invent a last-activity timestamp");
    }

    @Test
    void staleRegistryInstancesDoNotDropEachOthersSessions() {
        SessionRegistry first = SessionRegistry.load();
        SessionRegistry second = SessionRegistry.load();

        first.register("first-session", "kompile", "/work", "local", 0L);
        second.register("second-session", "kompile", "/work", "local", 0L);

        SessionRegistry reloaded = SessionRegistry.load();
        assertTrue(reloaded.get("first-session").isPresent());
        assertTrue(reloaded.get("second-session").isPresent());
        assertEquals(2, reloaded.size());
    }

    @Test
    void corruptRegistryFailsClosedWithoutOverwritingOriginalBytes() throws Exception {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("preserved-session", "kompile", "/work", "local", 0L);
        String corrupt = "{not valid json";
        Files.writeString(SessionRegistry.registryFile(), corrupt);

        SessionRegistry tolerantRead = SessionRegistry.load();
        assertTrue(tolerantRead.getAll().isEmpty());
        assertThrows(IllegalStateException.class, () -> tolerantRead.register(
                "must-not-overwrite", "kompile", "/work", "local", 0L));
        assertEquals(corrupt, Files.readString(SessionRegistry.registryFile()));
    }

    @Test
    void conversationOnlyRowsHaveDistinctResumeIdentities() {
        SessionEntry first = SessionEntry.builder()
                .kompileSessionId("").conversationId("native-a").agent("codex").build();
        SessionEntry second = SessionEntry.builder()
                .kompileSessionId("").conversationId("native-b").agent("codex").build();

        assertNotEquals(SessionRegistry.resumeIdentity(first),
                SessionRegistry.resumeIdentity(second));
    }

    @Test
    void resumeClaimPreventsDuplicateSelectionAndCanBeReleased() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("claim-session", "kompile", "/work", "local", 0L);
        registry.markExited("claim-session");

        List<SessionEntry> first = registry.claimResumable(
                ignored -> true, (left, right) -> 0, 1, "claim-a");
        List<SessionEntry> second = SessionRegistry.load().claimResumable(
                ignored -> true, (left, right) -> 0, 1, "claim-b");

        assertEquals(1, first.size());
        assertTrue(second.isEmpty(), "a claimed row must not be selected twice");
        registry.releaseResumeClaim(SessionRegistry.resumeIdentity(first.get(0)), "claim-a");
        assertEquals(1, SessionRegistry.load().getResumable().size());
    }

    @Test
    void staleProcessCannotExitReplacementOwner() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("shared-transcript", "kompile", "/work", "local", 111L);
        registry.register("shared-transcript", "kompile", "/work", "local", 222L);

        assertFalse(registry.markExited("shared-transcript", 111L));
        SessionEntry replacement = SessionRegistry.load().get("shared-transcript").orElseThrow();
        assertEquals("running", replacement.getStatus());
        assertEquals(222L, replacement.getPid());
        assertTrue(registry.markExited("shared-transcript", 222L));
        assertEquals("exited", SessionRegistry.load().get("shared-transcript")
                .orElseThrow().getStatus());
    }

    @Test
    void newerTranscriptOnSamePidSupersedesOlderConversation() throws Exception {
        long pid = ProcessHandle.current().pid();
        SessionRegistry registry = SessionRegistry.load();
        registry.register("old-transcript", "kompile", "/work", "local", pid);
        Thread.sleep(5);
        registry.register("new-transcript", "kompile", "/work", "local", pid);

        SessionRegistry reloaded = SessionRegistry.load();
        assertEquals("exited", reloaded.get("old-transcript").orElseThrow().getStatus());
        assertEquals("running", reloaded.get("new-transcript").orElseThrow().getStatus());

        // Simulate a registry written by the pre-fix /clear lifecycle and ensure
        // refresh repairs it without waiting for the still-live JVM to exit.
        reloaded.get("old-transcript").orElseThrow().setStatus("running");
        reloaded.get("old-transcript").orElseThrow().setEndedAt(null);
        reloaded.save();
        reloaded.refreshStatuses();
        assertEquals("exited", reloaded.get("old-transcript").orElseThrow().getStatus());
        assertEquals("running", reloaded.get("new-transcript").orElseThrow().getStatus());
    }

    @Test
    void resumeAllListMatchesDryRunAndLegacyBlankAgentUsesAutoTarget() throws Exception {
        long deadPid = spawnDeadProcess();
        SessionRegistry registry = SessionRegistry.load();
        registry.register("oldest-exited-session", "", tempDir.toString(), "local", deadPid);
        registry.markExited("oldest-exited-session");
        Thread.sleep(5);
        registry.register("older-exited-session", "", tempDir.toString(), "local", deadPid);
        registry.markExited("older-exited-session");
        Thread.sleep(5);
        registry.register("running-session", "", tempDir.toString(), "local",
                ProcessHandle.current().pid());
        Thread.sleep(5);
        registry.register("newer-exited-session", "", tempDir.toString(), "local", deadPid);
        registry.setConversationId("newer-exited-session", "native-provider-session");
        registry.markExited("newer-exited-session");
        registry.get("oldest-exited-session").orElseThrow()
                .setStartedAt("not-a-timestamp");
        registry.save();

        String listing = captureStdout(() -> assertEquals(
                0, ResumeAllCommand.executeInline("--list --recent 2 --agent kompile")));
        String dryRun = captureStdout(() -> assertEquals(
                0, ResumeAllCommand.executeInline("--dry-run --recent 2")));

        assertTrue(listing.contains("newer-exited"));
        assertTrue(listing.contains("older-exited"));
        assertFalse(listing.contains("running-sess"),
                "--list must show the same resumable set as the launch path");
        assertFalse(listing.contains("oldest-exit"),
                "recent limiting must happen after resumable filtering and sorting");
        assertTrue(dryRun.contains("newer-exited-session"));
        assertTrue(dryRun.contains("older-exited-session"));
        assertFalse(dryRun.contains("native-provider-session"),
                "the wrapper ID must preserve provider auto-resolution metadata");
        assertFalse(dryRun.contains("--agent"),
                "legacy blank agent metadata must not produce an invalid option");
    }

    @Test
    void activeWithinUsesLastActivityAndDoesNotApplyTheDefaultCountCap() throws Exception {
        SessionRegistry registry = SessionRegistry.load();
        ResumeConfig config = ResumeConfig.load();
        config.setRecentSessions(1);
        assertTrue(config.save());

        for (int i = 0; i < 12; i++) {
            String sessionId = String.format("recent-session-%02d", i);
            registry.register(sessionId, "kompile", tempDir.toString(), "local", 0L);
            registry.markExited(sessionId);
        }
        registry.register("recent-start-fallback", "kompile", tempDir.toString(), "local", 0L);
        registry.markExited("recent-start-fallback");
        registry.register("stale-session", "kompile", tempDir.toString(), "local", 0L);
        registry.markExited("stale-session");
        registry.register("recent-crashed-session", "kompile", tempDir.toString(), "local", 0L);
        registry.register("old-crashed-session", "kompile", tempDir.toString(), "local", 0L);

        Instant now = Instant.now();
        for (int i = 0; i < 12; i++) {
            SessionEntry recent = registry.get(String.format("recent-session-%02d", i)).orElseThrow();
            recent.setStartedAt(now.minus(Duration.ofHours(2)).toString());
            recent.setEndedAt(now.minus(Duration.ofMinutes(5)).plusSeconds(i).toString());
        }
        SessionEntry fallback = registry.get("recent-start-fallback").orElseThrow();
        fallback.setStartedAt(now.minus(Duration.ofMinutes(2)).toString());
        fallback.setEndedAt("");
        SessionEntry stale = registry.get("stale-session").orElseThrow();
        stale.setStartedAt(now.minus(Duration.ofHours(2)).toString());
        stale.setEndedAt(now.minus(Duration.ofMinutes(31)).toString());
        SessionEntry recentCrash = registry.get("recent-crashed-session").orElseThrow();
        recentCrash.setStartedAt(now.minus(Duration.ofMinutes(3)).toString());
        recentCrash.setEndedAt("");
        SessionEntry oldCrash = registry.get("old-crashed-session").orElseThrow();
        oldCrash.setStartedAt(now.minus(Duration.ofHours(3)).toString());
        oldCrash.setEndedAt("");
        registry.save();

        String dryRun = captureStdout(() -> assertEquals(0,
                ResumeAllCommand.executeInline("--dry-run --active-within 30")));

        for (int i = 0; i < 12; i++) {
            assertTrue(dryRun.contains(String.format("recent-session-%02d", i)));
        }
        assertTrue(dryRun.contains("recent-start-fallback"),
                "rows without an end timestamp should use their recent start");
        assertTrue(dryRun.contains("recent-crashed-session"),
                "a recently started crash should become resumable inside the window");
        assertFalse(dryRun.contains("stale-session"));
        assertFalse(dryRun.contains("old-crashed-session"),
                "status refresh must not make an old crash look recently active");
        assertTrue(dryRun.contains("Dry run — 14 sessions would be resumed"),
                "the configured count must not cap an activity window");

        String repeated = captureStdout(() -> assertEquals(0,
                ResumeAllCommand.executeInline("--dry-run --active-within 30")));
        assertFalse(repeated.contains("old-crashed-session"),
                "an old crash must remain outside the window on repeated invocations");
        assertTrue(repeated.contains("Dry run — 14 sessions would be resumed"));

        String capped = captureStdout(() -> assertEquals(0,
                ResumeAllCommand.executeInline("--dry-run --active-within 30 --recent 2")));
        assertTrue(capped.contains("Dry run — 2 sessions would be resumed"),
                "an explicit recent count may cap activity-window matches");
    }

    @Test
    void activeWithinIncludesTheExactCutoffBoundary() {
        Instant cutoff = Instant.parse("2026-09-04T00:00:00Z");
        SessionEntry entry = SessionEntry.builder()
                .startedAt(cutoff.minus(Duration.ofHours(1)).toString())
                .endedAt(cutoff.toString())
                .build();

        assertTrue(ResumeAllCommand.wasActiveAtOrAfter(entry, cutoff));
    }

    @Test
    void activeWithinUsesInferredSupersessionBoundaryOnFirstInvocation() throws Exception {
        long livePid = ProcessHandle.current().pid();
        Instant now = Instant.now();
        SessionRegistry registry = SessionRegistry.load();
        registry.register("legacy-old-transcript", "kompile", tempDir.toString(), "local", livePid);
        registry.register("current-transcript", "kompile", tempDir.toString(), "local", livePid);

        // Simulate the pre-fix /clear shape: two rows still claim the same live
        // process. Refresh can infer that the older transcript stopped when the
        // newer one began, and that inferred boundary is recent activity.
        SessionEntry older = registry.get("legacy-old-transcript").orElseThrow();
        older.setStartedAt(now.minus(Duration.ofHours(2)).toString());
        older.setEndedAt("");
        older.setStatus("running");
        SessionEntry current = registry.get("current-transcript").orElseThrow();
        current.setStartedAt(now.minus(Duration.ofMinutes(5)).toString());
        registry.save();

        String firstRun = captureStdout(() -> assertEquals(0,
                ResumeAllCommand.executeInline("--dry-run --active-within 30")));

        assertTrue(firstRun.contains("legacy-old-transcript"),
                "the first invocation should use the timestamp-safe refresh boundary");
        assertFalse(firstRun.contains("current-transcript"),
                "the current live transcript is not resumable");
        assertTrue(firstRun.contains("Dry run — 1 sessions would be resumed"));
    }

    @Test
    void inlineOptionsSupportQuotedPathsAndRejectInvalidLimits() throws Exception {
        Path projectWithSpaces = tempDir.resolve("project with spaces");
        Files.createDirectories(projectWithSpaces);
        long deadPid = spawnDeadProcess();
        SessionRegistry registry = SessionRegistry.load();
        registry.register("quoted-project-session", "kompile",
                projectWithSpaces.toString(), "local", deadPid);
        registry.markExited("quoted-project-session");

        String listing = captureStdout(() -> assertEquals(0,
                ResumeAllCommand.executeInline(
                        "--list --project \"" + projectWithSpaces + "\"")));
        assertTrue(listing.contains("quoted-proje"));
        assertEquals(1, ResumeAllCommand.executeInline("--dry-run --recent 0"));
        assertEquals(1, ResumeAllCommand.executeInline("--dry-run --recent -2"));
        assertEquals(1, ResumeAllCommand.executeInline("--dry-run --active-within 0"));
        assertEquals(1, ResumeAllCommand.executeInline("--dry-run --all --recent 2"));
        assertThrows(IllegalArgumentException.class,
                () -> ResumeAllCommand.tokenizeInlineArgs("--project \"unterminated"));
    }

    @Test
    void clearResumeLockForcesStuckClaimedRowBackToResumable() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("stuck-claim", "kompile", "/work", "local", 0L);
        registry.markExited("stuck-claim");
        // Simulate a crashed resume-all: the row was claimed and never released
        registry.claimResumable(ignored -> true, (left, right) -> 0, 10, "crashed-claim");

        assertFalse(SessionRegistry.load().getResumable().stream()
                        .anyMatch(e -> "stuck-claim".equals(e.getKompileSessionId())),
                "precondition: a claimed row is not resumable");

        assertTrue(registry.clearResumeLock("stuck-claim"));
        SessionEntry repaired = SessionRegistry.load().get("stuck-claim").orElseThrow();
        assertEquals("exited", repaired.getStatus());
        assertEquals(1, SessionRegistry.load().getResumable().size(),
                "the forced row must be selectable by resume-all again");
        assertFalse(registry.clearResumeLock("no-such-session"),
                "clearing an unknown ID must report failure");
    }

    @Test
    void clearResumeLockMatchesNativeConversationId() {
        SessionRegistry registry = SessionRegistry.load();
        registry.register("wrapper-row", "codex", "/work", "passthrough", 0L);
        registry.setConversationId("wrapper-row", "native-abc");
        registry.markExited("wrapper-row");
        registry.claimResumable(ignored -> true, (left, right) -> 0, 10, "crashed-claim");

        assertTrue(registry.clearResumeLock("native-abc"),
                "the native conversation ID must address the same row");
        assertEquals("exited", SessionRegistry.load().get("wrapper-row").orElseThrow().getStatus());
    }

    @Test
    void clearAllResumeLocksRepairsDeadRowsAndSparesLiveSessions() {
        long deadPid = spawnDeadProcess();
        SessionRegistry registry = SessionRegistry.load();
        registry.register("crashed-row", "kompile", "/work", "local", deadPid);
        registry.register("claimed-row", "kompile", "/work", "local", 0L);
        registry.markExited("claimed-row");
        registry.claimResumable(ignored -> true, (left, right) -> 0, 10, "crashed-claim");
        registry.register("live-row", "kompile", "/work", "local", ProcessHandle.current().pid());

        int repaired = registry.clearAllResumeLocks();
        assertEquals(2, repaired,
                "the dead-PID row and the abandoned claim are repaired; the live row is not");

        SessionRegistry reloaded = SessionRegistry.load();
        assertEquals("exited", reloaded.get("crashed-row").orElseThrow().getStatus());
        assertEquals("exited", reloaded.get("claimed-row").orElseThrow().getStatus());
        assertEquals("running", reloaded.get("live-row").orElseThrow().getStatus(),
                "a genuinely live session must never be force-stamped resumable");
        assertEquals(2, reloaded.getResumable().size());
    }

    private static String captureStdout(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream capture = new java.io.PrintStream(
                output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(original);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Spawn a short-lived process and return its PID after it has exited, so
     * ProcessHandle.of(pid) reports not-alive deterministically.
     */
    private static long spawnDeadProcess() {
        try {
            String java = ProcessHandle.current().info().command()
                    .orElseThrow(() -> new IllegalStateException("Current Java command unavailable"));
            Process process = new ProcessBuilder(java, "-version").start();
            process.waitFor();
            return process.pid();
        } catch (Exception e) {
            throw new IllegalStateException("Could not spawn a dead process", e);
        }
    }
}
