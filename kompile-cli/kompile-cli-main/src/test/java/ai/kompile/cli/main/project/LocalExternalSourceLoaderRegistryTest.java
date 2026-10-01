/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.auth.source.SourceCredentialResolver;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.FileDownloadingLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalExternalSourceLoaderRegistryTest {
    @TempDir Path tempDir;

    @Test
    void advertisesManagedConnectorTypesForLocalExecution() {
        assertTrue(LocalExternalSourceLoaderRegistry.supports("jira"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("reddit"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("notion"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("slack-history"));
        assertTrue(LocalExternalSourceLoaderRegistry.supports("google_workspace"));
        assertFalse(LocalExternalSourceLoaderRegistry.supports("file"));
        assertEquals(
                LocalExternalSourceLoaderRegistry.identityKey(
                        "NOTION", "01234567-89ab-cdef-0123-456789abcdef"),
                LocalExternalSourceLoaderRegistry.identityKey(
                        "notion", "https://www.notion.so/Project-0123456789abcdef0123456789abcdef?pvs=4"));
    }

    @Test
    void recursivelyRemovesCredentialMetadata() {
        Map<String, Object> sanitized = LocalExternalSourceLoaderRegistry.sanitizeMetadata(Map.of(
                "title", "Visible",
                "accessToken", "secret",
                "nested", Map.of("apiToken", "also-secret", "channel", "general"),
                "items", List.of(Map.of("password", "hidden", "name", "kept"))));

        assertEquals("Visible", sanitized.get("title"));
        assertFalse(sanitized.containsKey("accessToken"));
        assertEquals(Map.of("channel", "general"), sanitized.get("nested"));
        assertEquals(List.of(Map.of("name", "kept")), sanitized.get("items"));
    }

    @Test
    void locatorOptionalTypesAndMetadataIdentitySkipTheLocatorGate() {
        // Loader never reads the locator (mailbox/account-wide types).
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("GMAIL", Map.of()));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("IMAP", null));
        // Locator-centric types need either the locator or identity metadata.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator("GDRIVE", Map.of()));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", List.of("file-1", "file-2"))));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "NOTION", Map.of("pageIds", "page-1")));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "DISCORD", Map.of("guildId", "guild-9")));
        // A folder id identifies a Drive/OneDrive source without explicit item ids.
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("folderId", "folder-1")));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "ONEDRIVE", Map.of("folderId", "root")));
        // An empty metadata list does not constitute identity.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", List.of())));
        // Unknown identifier keys never substitute for the locator.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "CONFLUENCE", Map.of("spaceKey", "DEV")));
    }

    @Test
    void failedRefreshKeepsTheLastCompleteMaterializedSnapshot() throws Exception {
        Path export = Files.createDirectories(tempDir.resolve("export"));
        Files.writeString(export.resolve("page.html"),
                "<html><body>last-complete-snapshot</body></html>", StandardCharsets.UTF_8);
        Path target = tempDir.resolve("materialized");
        // An explicit apiToken satisfies resolveStoredCredentials' fail-closed check up front so
        // this test never falls through to the real local credential store or control plane.
        Map<String, Object> properties = Map.of("apiToken", "test-token");
        LocalExternalSourceLoaderRegistry.materialize(
                "CONFLUENCE", export.toString(), properties, target, 0,
                new com.fasterxml.jackson.databind.ObjectMapper());
        List<Path> original;
        try (var files = Files.list(target)) {
            original = files.toList();
        }
        assertEquals(1, original.size());
        assertFalse(original.get(0).getFileName().toString().startsWith("00001-"));

        Files.delete(export.resolve("page.html"));
        assertThrows(IllegalStateException.class, () ->
                LocalExternalSourceLoaderRegistry.materialize(
                        "CONFLUENCE", export.toString(), properties, target, 0,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        assertTrue(Files.isRegularFile(original.get(0)));
        assertTrue(Files.readString(original.get(0)).contains("last-complete-snapshot"));
    }

    // ── writeDocuments safety net (identity collisions must not collapse distinct docs) ──

    @Test
    void slackShapedDocumentsWithDistinctSourcePathsEachGetOwnFile() throws Exception {
        int count = 5;
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Document document = new Document("message body " + i);
            document.getMetadata().put("source", "slack");
            document.getMetadata().put("source_path", "slack://channel/C123/message/" + i);
            documents.add(document);
        }
        Path staging = Files.createDirectories(tempDir.resolve("staging-distinct-paths"));
        List<Path> files = LocalExternalSourceLoaderRegistry.writeDocuments(
                documents, "SLACK", "C123", staging, new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(count, files.size());
    }

    @Test
    void collidingIdentityWithDifferentTextIsDisambiguatedInsteadOfOverwritten() throws Exception {
        // Pre-fix shape: every message shares one identity because none carries a source_path
        // (or any other per-message identifier), so they would all hash to the same filename.
        int count = 3;
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Document document = new Document("distinct message body " + i);
            document.getMetadata().put("source", "slack");
            documents.add(document);
        }
        Path staging = Files.createDirectories(tempDir.resolve("staging-collision"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        List<Path> files;
        try {
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            files = LocalExternalSourceLoaderRegistry.writeDocuments(
                    documents, "SLACK", "C123", staging, new com.fasterxml.jackson.databind.ObjectMapper());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(count, files.size(),
                "documents that share an identity but not text must not collapse into one file");
        // The first document establishes the base filename; the other (count - 1) collide with it
        // and must be disambiguated. Exactly one warning is logged for the whole run, carrying the
        // total collision count — not one warning per collision.
        String warning = capturedErr.toString(StandardCharsets.UTF_8);
        int expectedCollisions = count - 1;
        assertEquals(1, warning.lines().filter(line -> line.contains("Warning:")).count(),
                "exactly one collision warning must be logged per run: " + warning);
        assertTrue(warning.contains(String.valueOf(expectedCollisions)),
                "warning should report " + expectedCollisions + " collision(s): " + warning);
    }

    @Test
    void trueDuplicatesWithSameIdentityAndTextCollapseIntoOneFile() throws Exception {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Document document = new Document("identical message body");
            document.getMetadata().put("source", "slack");
            documents.add(document);
        }
        Path staging = Files.createDirectories(tempDir.resolve("staging-duplicate"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        List<Path> files;
        try {
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            files = LocalExternalSourceLoaderRegistry.writeDocuments(
                    documents, "SLACK", "C123", staging, new com.fasterxml.jackson.databind.ObjectMapper());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, files.size(),
                "exact duplicates (same identity and same text) must collapse into one file");
        // A true duplicate is dropped silently, not counted as a disambiguation collision.
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8),
                "skipping a true duplicate must not log a collision warning");
    }

    @Test
    void duplicateOfAnAlreadyDisambiguatedTextIsRecognisedNotRewritten() throws Exception {
        // identity A, texts (a, b, b): "a" claims the base filename; the first "b" collides and
        // is written disambiguated; the second "b" must be recognised as a duplicate of that
        // FIRST "b" copy (not just of "a"), so it must not be written a third time.
        List<Document> documents = new ArrayList<>();
        Document a = new Document("a");
        a.getMetadata().put("source", "slack");
        documents.add(a);
        Document b1 = new Document("b");
        b1.getMetadata().put("source", "slack");
        documents.add(b1);
        Document b2 = new Document("b");
        b2.getMetadata().put("source", "slack");
        documents.add(b2);
        Path staging = Files.createDirectories(tempDir.resolve("staging-abb"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        List<Path> files;
        try {
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            files = LocalExternalSourceLoaderRegistry.writeDocuments(
                    documents, "SLACK", "C123", staging, new com.fasterxml.jackson.databind.ObjectMapper());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(2, files.size(),
                "the second \"b\" is a duplicate of the first \"b\" and must not get a third file");
        String warning = capturedErr.toString(StandardCharsets.UTF_8);
        assertEquals(1, warning.lines().filter(line -> line.contains("Warning:")).count(),
                "exactly one collision warning must be logged per run: " + warning);
        assertTrue(warning.contains("1"), "warning should report 1 collision(s): " + warning);
    }

    @Test
    void duplicateOfTheOriginalTextAfterACollisionIsRecognisedNotRewritten() throws Exception {
        // identity A, texts (a, b, a): "a" claims the base filename; "b" collides and is written
        // disambiguated; the second "a" must still be recognised as a duplicate of the ORIGINAL
        // "a" copy, so it must not be written a third time.
        List<Document> documents = new ArrayList<>();
        Document a1 = new Document("a");
        a1.getMetadata().put("source", "slack");
        documents.add(a1);
        Document b = new Document("b");
        b.getMetadata().put("source", "slack");
        documents.add(b);
        Document a2 = new Document("a");
        a2.getMetadata().put("source", "slack");
        documents.add(a2);
        Path staging = Files.createDirectories(tempDir.resolve("staging-aba"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        List<Path> files;
        try {
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            files = LocalExternalSourceLoaderRegistry.writeDocuments(
                    documents, "SLACK", "C123", staging, new com.fasterxml.jackson.databind.ObjectMapper());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(2, files.size(),
                "the second \"a\" is a duplicate of the original \"a\" and must not get a third file");
        String warning = capturedErr.toString(StandardCharsets.UTF_8);
        assertEquals(1, warning.lines().filter(line -> line.contains("Warning:")).count(),
                "exactly one collision warning must be logged per run: " + warning);
        assertTrue(warning.contains("1"), "warning should report 1 collision(s): " + warning);
    }

    // ── materializeFiles staging swap / cap / warning aggregation (F2) ──

    private static FakeFileDownloadingLoader singleFileLoader() {
        return new FakeFileDownloadingLoader((descriptor, destination, warnings) ->
                List.of(Files.writeString(destination.resolve("a.txt"), "a", StandardCharsets.UTF_8)));
    }

    @Test
    void materializeFilesStagingSwapRemovesStaleFilesFromThePriorRun() throws Exception {
        Path target = tempDir.resolve("gdrive-files");
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("accessToken", "dummy-token"));

        FakeFileDownloadingLoader firstRun = new FakeFileDownloadingLoader((descriptor, destination, warnings) -> {
            Path a = Files.writeString(destination.resolve("a.txt"), "a-content", StandardCharsets.UTF_8);
            Path b = Files.writeString(destination.resolve("b.txt"), "b-content", StandardCharsets.UTF_8);
            return List.of(a, b);
        });
        LocalExternalSourceLoaderRegistry.MaterializedFiles firstResult = LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", properties, target, 0, SourceCredentialResolver.create(), firstRun);
        assertEquals(2, firstResult.files().size());
        assertTrue(Files.exists(target.resolve("b.txt")));

        FakeFileDownloadingLoader secondRun = new FakeFileDownloadingLoader((descriptor, destination, warnings) ->
                List.of(Files.writeString(destination.resolve("a.txt"), "a-content-v2", StandardCharsets.UTF_8)));
        LocalExternalSourceLoaderRegistry.MaterializedFiles secondResult = LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", properties, target, 0, SourceCredentialResolver.create(), secondRun);

        assertEquals(1, secondResult.files().size());
        assertFalse(Files.exists(target.resolve("b.txt")), "a stale file from the first run must be swapped away");
        assertEquals("a-content-v2", Files.readString(target.resolve("a.txt")));
    }

    @Test
    void materializeFilesEnforcesPerFileCapWhenTheLoaderOverfetches() throws Exception {
        Path target = tempDir.resolve("gdrive-capped");
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("accessToken", "dummy-token"));
        FakeFileDownloadingLoader loader = new FakeFileDownloadingLoader((descriptor, destination, warnings) -> {
            Path a = Files.writeString(destination.resolve("a.txt"), "a", StandardCharsets.UTF_8);
            Path b = Files.writeString(destination.resolve("b.txt"), "b", StandardCharsets.UTF_8);
            Path c = Files.writeString(destination.resolve("c.txt"), "c", StandardCharsets.UTF_8);
            return List.of(a, b, c);
        });

        LocalExternalSourceLoaderRegistry.MaterializedFiles result = LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", properties, target, 2, SourceCredentialResolver.create(), loader);

        assertEquals(2, result.files().size(), "extra files beyond the cap must be discarded");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("cap of 2"), result.warnings().get(0));
    }

    @Test
    void materializeFilesRecordsAPerFileFailureAsAWarningWithoutFailingTheOthers() throws Exception {
        Path target = tempDir.resolve("gdrive-partial-failure");
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("accessToken", "dummy-token"));
        FakeFileDownloadingLoader loader = new FakeFileDownloadingLoader((descriptor, destination, warnings) -> {
            Path a = Files.writeString(destination.resolve("a.txt"), "a", StandardCharsets.UTF_8);
            warnings.accept("Failed to download b.txt: simulated transient error");
            return List.of(a);
        });

        LocalExternalSourceLoaderRegistry.MaterializedFiles result = LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", properties, target, 0, SourceCredentialResolver.create(), loader);

        assertEquals(1, result.files().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("simulated transient error"));
    }

    @Test
    void materializeFilesPropagatesTheFailureWhenTheLoaderThrowsForEveryFile() {
        Path target = tempDir.resolve("gdrive-all-fail-throws");
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("accessToken", "dummy-token"));
        FakeFileDownloadingLoader loader = new FakeFileDownloadingLoader((descriptor, destination, warnings) -> {
            throw new IOException("simulated total failure");
        });

        IOException error = assertThrows(IOException.class, () -> LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", properties, target, 0, SourceCredentialResolver.create(), loader));
        assertEquals("simulated total failure", error.getMessage());
        assertFalse(Files.exists(target), "a failed run must not leave a staging directory or partial target behind");
    }

    @Test
    void materializeFilesThrowsWhenTheLoaderReturnsNoFilesEvenWithoutThrowing() {
        Path target = tempDir.resolve("gdrive-all-fail-empty");
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("accessToken", "dummy-token"));
        FakeFileDownloadingLoader loader = new FakeFileDownloadingLoader((descriptor, destination, warnings) -> {
            warnings.accept("every file failed: simulated");
            return List.of();
        });

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                LocalExternalSourceLoaderRegistry.materializeFiles(
                        "GDRIVE", "folder-1", properties, target, 0, SourceCredentialResolver.create(), loader));
        assertTrue(error.getMessage().contains("simulated"), error.getMessage());
        assertFalse(Files.exists(target));
    }

    // ── bounded()/applyFileDownloadCap: a non-positive configured cap counts as unset (F3) ──

    @Test
    void materializeFilesTreatsANonPositiveConfiguredCapAsUnset() throws Exception {
        Path zeroTarget = tempDir.resolve("gdrive-cap-zero");
        FakeFileDownloadingLoader zeroLoader = singleFileLoader();
        LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", new LinkedHashMap<>(Map.of("accessToken", "dummy-token", "maxFiles", 0)),
                zeroTarget, 5, SourceCredentialResolver.create(), zeroLoader);
        assertEquals(5, zeroLoader.lastDescriptor().getMetadata().get("maxFiles"),
                "a configured maxFiles of 0 must count as unset, not floor the effective cap to 0");

        Path negativeTarget = tempDir.resolve("gdrive-cap-negative");
        FakeFileDownloadingLoader negativeLoader = singleFileLoader();
        LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", new LinkedHashMap<>(Map.of("accessToken", "dummy-token", "maxFiles", -10)),
                negativeTarget, 5, SourceCredentialResolver.create(), negativeLoader);
        assertEquals(5, negativeLoader.lastDescriptor().getMetadata().get("maxFiles"),
                "a negative configured maxFiles must also count as unset");
    }

    @Test
    void materializeFilesHonorsAPositiveConfiguredCapButNeverAboveTheCallersCeiling() throws Exception {
        Path belowTarget = tempDir.resolve("gdrive-cap-below-ceiling");
        FakeFileDownloadingLoader belowLoader = singleFileLoader();
        LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", new LinkedHashMap<>(Map.of("accessToken", "dummy-token", "maxFiles", 3)),
                belowTarget, 5, SourceCredentialResolver.create(), belowLoader);
        assertEquals(3, belowLoader.lastDescriptor().getMetadata().get("maxFiles"),
                "a positive configured cap below the caller's ceiling must be honored as-is");

        Path aboveTarget = tempDir.resolve("gdrive-cap-above-ceiling");
        FakeFileDownloadingLoader aboveLoader = singleFileLoader();
        LocalExternalSourceLoaderRegistry.materializeFiles(
                "GDRIVE", "folder-1", new LinkedHashMap<>(Map.of("accessToken", "dummy-token", "maxFiles", 50)),
                aboveTarget, 5, SourceCredentialResolver.create(), aboveLoader);
        assertEquals(5, aboveLoader.lastDescriptor().getMetadata().get("maxFiles"),
                "a configured cap above the caller's ceiling must be clamped down to that ceiling");
    }

    // ── incremental sync via materialize() (F4) ──

    private static Map<String, Object> discordProperties() {
        return new LinkedHashMap<>(Map.of("botToken", "dummy-token"));
    }

    private static Document discordMessage(String text, String sourcePath) {
        Document document = new Document(text);
        document.getMetadata().put("source", "discord");
        if (sourcePath != null) document.getMetadata().put("source_path", sourcePath);
        return document;
    }

    private static List<Path> regularFilesUnder(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void incrementalRunComputesSinceFromThePriorSyncStartMinusTheOverlapWindow() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-incremental-since");
        Map<String, Object> properties = discordProperties();

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(discordMessage("hello", "discord://guild/g1/channel/c1/message/1"))));

        Path syncFile = target.resolveSibling(target.getFileName() + ".sync.json");
        assertTrue(Files.isRegularFile(syncFile), "an incremental type must persist a sync-state sidecar");
        Instant knownPast = Instant.parse("2020-01-01T12:00:00Z");
        ObjectNode node = mapper.createObjectNode();
        node.put("sourceType", "DISCORD");
        node.put("lastSyncStartedAt", knownPast.toString());
        Files.writeString(syncFile, mapper.writeValueAsString(node), StandardCharsets.UTF_8);

        FakeDocumentLoader secondLoader = new FakeDocumentLoader(
                List.of(discordMessage("world", "discord://guild/g1/channel/c1/message/2")));
        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(), secondLoader);

        Object since = secondLoader.lastDescriptor().getMetadata().get("since");
        assertEquals(knownPast.minus(Duration.ofMinutes(10)).toString(), since,
                "the incremental window must start 10 minutes before the last recorded sync start");
    }

    @Test
    void incrementalMergeAddsNewFilesWithoutDeletingThePriorSnapshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-merge");
        Map<String, Object> properties = discordProperties();

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(
                        discordMessage("first message", "discord://guild/g1/channel/c1/message/1"))));
        List<Path> afterFirst = regularFilesUnder(target);
        assertEquals(1, afterFirst.size());

        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(
                        discordMessage("second message", "discord://guild/g1/channel/c1/message/2"))));

        assertTrue(result.incremental(), "a second run with prior sync state and no fullResync must be incremental");
        assertTrue(Files.exists(afterFirst.get(0)), "an incremental merge must keep files from the prior snapshot");
        assertEquals(2, regularFilesUnder(target).size(),
                "the merge must add the new message without removing the old one");
    }

    @Test
    void incrementalRunWithZeroNewDocumentsSucceedsAndKeepsThePriorSnapshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-zero-new");
        Map<String, Object> properties = discordProperties();

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(discordMessage("only message", "discord://guild/g1/channel/c1/message/1"))));
        List<Path> afterFirst = regularFilesUnder(target);
        assertEquals(1, afterFirst.size());

        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of()));

        assertTrue(result.incremental());
        assertEquals(0, result.fetchedDocuments());
        assertEquals(afterFirst, regularFilesUnder(target), "zero new documents must leave the prior snapshot untouched");
        try (var siblings = Files.list(target.getParent())) {
            assertTrue(siblings.noneMatch(p -> p.getFileName().toString().contains(".staging-")),
                    "a zero-new incremental run must clean up its staging directory");
        }
    }

    @Test
    void fullResyncForcesAFullFetchEvenWithPriorSyncState() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-full-resync");
        Map<String, Object> properties = discordProperties();

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(
                        discordMessage("original message", "discord://guild/g1/channel/c1/message/1"))));

        Map<String, Object> resyncProperties = discordProperties();
        resyncProperties.put("fullResync", true);
        FakeDocumentLoader secondLoader = new FakeDocumentLoader(List.of(
                discordMessage("full resync replacement message", "discord://guild/g1/channel/c1/message/2")));

        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", resyncProperties, target, 0, mapper, SourceCredentialResolver.create(), secondLoader);

        assertFalse(result.incremental(), "fullResync=true must force a full (non-incremental) fetch");
        assertNull(secondLoader.lastDescriptor().getMetadata().get("since"),
                "a forced full resync must not carry a since filter into the descriptor");
        List<Path> afterResync = regularFilesUnder(target);
        assertEquals(1, afterResync.size(), "a full resync replaces rather than merges");
        assertTrue(Files.readString(afterResync.get(0)).contains("full resync replacement message"));
    }

    @Test
    void missingSourcePathDuringAnIncrementalRunFallsBackToAFullFetch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-fallback");
        Map<String, Object> properties = discordProperties();

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(discordMessage("seed message", "discord://guild/g1/channel/c1/message/1"))));

        // The first (incremental-attempt) call returns a document with no source_path at all,
        // which must force exactly one fallback re-fetch rather than an infinite retry.
        FakeDocumentLoader fallbackLoader = new FakeDocumentLoader((descriptor, callNumber) -> callNumber == 1
                ? List.of(discordMessage("can't be merged safely", null))
                : List.of(discordMessage("full fetch after fallback", "discord://guild/g1/channel/c1/message/2")));

        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", properties, target, 0, mapper, SourceCredentialResolver.create(), fallbackLoader);

        assertEquals(2, fallbackLoader.callCount(), "a missing source_path must trigger exactly one fallback re-fetch");
        assertFalse(result.incremental(), "the fallback run must report itself as a full (non-incremental) fetch");
        List<Path> afterFallback = regularFilesUnder(target);
        assertEquals(1, afterFallback.size(), "the fallback replaces rather than merges");
        assertTrue(Files.readString(afterFallback.get(0)).contains("full fetch after fallback"));
    }

    // ── attachment path rewriting and pruning (F5) ──

    private static String soleMessageFileContent(Path target) throws IOException {
        List<Path> messageFiles;
        try (var files = Files.list(target)) {
            messageFiles = files.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, messageFiles.size(), "expected exactly one written message file directly under " + target);
        return Files.readString(messageFiles.get(0), StandardCharsets.UTF_8);
    }

    @Test
    void absoluteAttachmentPathsAreRewrittenRelativeToTheMaterializedRootAndReturned() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-attachments-absolute");
        FakeDocumentLoader loader = new FakeDocumentLoader((descriptor, callNumber) -> {
            Path attachmentDirectory = Path.of(String.valueOf(descriptor.getMetadata().get("attachmentDirectory")));
            Files.createDirectories(attachmentDirectory);
            Path attachmentFile = Files.writeString(
                    attachmentDirectory.resolve("report.pdf"), "pdf-bytes", StandardCharsets.UTF_8);
            Document document = discordMessage(
                    "message with an attachment", "discord://guild/g1/channel/c1/message/1");
            document.getMetadata().put("attachments", List.of(
                    new LinkedHashMap<>(Map.of("path", attachmentFile.toString(), "filename", "report.pdf"))));
            return List.of(document);
        });

        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", discordProperties(), target, 0, mapper, SourceCredentialResolver.create(), loader);

        Path attachmentFile = target.resolve("attachments").resolve("report.pdf");
        assertTrue(Files.isRegularFile(attachmentFile), "the attachment file itself must survive the staging swap");
        assertTrue(result.files().contains(attachmentFile),
                "materialize() must include attachment files in the returned file list");
        String written = soleMessageFileContent(target);
        assertTrue(written.contains("attachments/report.pdf"), written);
        assertFalse(written.contains(".staging-"), "a staging-directory path must never leak into the written file");
    }

    @Test
    void attachmentPathsAlreadyRelativeAreNormalizedUnderTheAttachmentsPrefix() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        Path bareTarget = tempDir.resolve("discord-relative-bare");
        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", discordProperties(), bareTarget, 0, mapper, SourceCredentialResolver.create(),
                relativeAttachmentPathLoader("note.txt"));
        assertTrue(soleMessageFileContent(bareTarget).contains("attachments/note.txt"));

        Path prefixedTarget = tempDir.resolve("discord-relative-prefixed");
        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", discordProperties(), prefixedTarget, 0, mapper, SourceCredentialResolver.create(),
                relativeAttachmentPathLoader("attachments/note.txt"));
        String prefixedWritten = soleMessageFileContent(prefixedTarget);
        assertTrue(prefixedWritten.contains("attachments/note.txt"), prefixedWritten);
        assertFalse(prefixedWritten.contains("attachments/attachments/"), prefixedWritten);
    }

    private static FakeDocumentLoader relativeAttachmentPathLoader(String rawPath) {
        return new FakeDocumentLoader((descriptor, callNumber) -> {
            Path attachmentDirectory = Path.of(String.valueOf(descriptor.getMetadata().get("attachmentDirectory")));
            Files.createDirectories(attachmentDirectory);
            Files.writeString(attachmentDirectory.resolve("note.txt"), "note", StandardCharsets.UTF_8);
            Document document = discordMessage(
                    "message with an already-relative attachment path", "discord://guild/g1/channel/c1/message/1");
            document.getMetadata().put("attachments", List.of(rawPath));
            return List.of(document);
        });
    }

    @Test
    void emptyAttachmentSubdirectoriesArePrunedButNonEmptyOnesSurvive() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-mixed-attachment-dir");
        FakeDocumentLoader loader = new FakeDocumentLoader((descriptor, callNumber) -> {
            Path attachmentDirectory = Path.of(String.valueOf(descriptor.getMetadata().get("attachmentDirectory")));
            Files.createDirectories(attachmentDirectory);
            Files.writeString(attachmentDirectory.resolve("kept.txt"), "kept", StandardCharsets.UTF_8);
            Files.createDirectories(attachmentDirectory.resolve("empty-subfolder"));
            return List.of(discordMessage(
                    "message with one real attachment and one failed one",
                    "discord://guild/g1/channel/c1/message/1"));
        });

        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", discordProperties(), target, 0, mapper, SourceCredentialResolver.create(), loader);

        assertTrue(Files.isRegularFile(target.resolve("attachments").resolve("kept.txt")),
                "a non-empty attachment file must survive");
        assertFalse(Files.exists(target.resolve("attachments").resolve("empty-subfolder")),
                "an attachment subdirectory left empty this run must be pruned");
        assertTrue(Files.isDirectory(target.resolve("attachments")),
                "the attachments root itself must survive since it still has real content");
    }

    // ── identityWithoutLocator / isTrue boolean semantics and type normalization (F6) ──

    @Test
    void slackHistoryNeverRequiresALocatorRegardlessOfLoadAllChannels() {
        // SLACK_HISTORY is locator-optional (DocumentSourceDescriptor#locatorOptional), so
        // identityWithoutLocator short-circuits to true regardless of loadAllChannels, before it
        // ever reaches the per-key identifierMetadataKeys loop. SlackHistoryLoaderImpl itself
        // validates that a channel, name, or loadAllChannels=true was supplied and throws its own
        // precise error otherwise; identifierMetadataKeys has no SLACK_HISTORY entry (F6) because
        // one would be unreachable here — locatorOptional already returns true first. This test
        // pins that short-circuit precedence so a future change has to consciously decide
        // SLACK_HISTORY's contract.
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "SLACK_HISTORY", Map.of("loadAllChannels", Boolean.TRUE)));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "SLACK_HISTORY", Map.of("loadAllChannels", Boolean.FALSE)));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("SLACK_HISTORY", Map.of()));
    }

    @Test
    void fileIdsBooleanFalseIsNotMisreadAsAnIdentifier() {
        // GDRIVE is NOT locator-optional, so identityWithoutLocator actually reaches the
        // identifierMetadataKeys loop here (unlike DISCORD/SLACK_HISTORY above). Without
        // identifiers()'s Boolean.FALSE guard, Boolean.FALSE.toString() = "false" would split
        // into a single bogus identifier "false" and incorrectly report identity.
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", Boolean.FALSE)));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "GDRIVE", Map.of("fileIds", "file-9")));
    }

    @Test
    void identityWithoutLocatorNormalizesRawCallerSuppliedTypeCasing() {
        // Both crawl-document call sites forward whatever casing/punctuation a user or agent
        // typed (e.g. "gdrive"), not the canonical enum spelling; identityWithoutLocator must
        // normalize before consulting locatorOptional and identifierMetadataKeys, exactly like
        // every other public entry point in this class (F6).
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "gdrive", Map.of("folderId", "folder-1")));
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator("gdrive", Map.of()));
        assertTrue(LocalExternalSourceLoaderRegistry.identityWithoutLocator("discord", Map.of()));
        assertFalse(LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                "FILE", Map.of("fileIds", List.of("file-1"))));
    }

    @Test
    void isTrueAcceptsOnlyRealOrCaseInsensitiveTrueStringsForFullResync() throws Exception {
        // fullResync is isTrue()'s only remaining caller: identityWithoutLocator's loadAllChannels
        // branch used to call it too, but that branch was unreachable (SLACK_HISTORY is already
        // locator-optional, so the per-key loop never ran for it) and was deleted in F6. Exercise
        // isTrue's string-form parsing directly via fullResync instead.
        assertFalse(secondRunIsIncrementalWithFullResync("resync-true", "true"),
                "string \"true\" must force a full (non-incremental) resync");
        assertFalse(secondRunIsIncrementalWithFullResync("resync-TRUE", "TRUE"),
                "isTrue's string parsing must be case-insensitive");
        assertTrue(secondRunIsIncrementalWithFullResync("resync-false", "false"),
                "string \"false\" must NOT force a full resync");
        assertTrue(secondRunIsIncrementalWithFullResync("resync-garbage", "not-a-boolean"),
                "a non-boolean string must NOT force a full resync");
    }

    private boolean secondRunIsIncrementalWithFullResync(String dirName, String fullResyncValue) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("discord-" + dirName);
        LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", discordProperties(), target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(discordMessage("seed", "discord://guild/g1/channel/c1/message/1"))));

        Map<String, Object> secondProperties = discordProperties();
        secondProperties.put("fullResync", fullResyncValue);
        LocalExternalSourceLoaderRegistry.MaterializedSource result = LocalExternalSourceLoaderRegistry.materialize(
                "DISCORD", "", secondProperties, target, 0, mapper, SourceCredentialResolver.create(),
                new FakeDocumentLoader(List.of(discordMessage("update", "discord://guild/g1/channel/c1/message/2"))));
        return result.incremental();
    }

    /**
     * Hand-written double for {@link DocumentLoader}/{@link FileDownloadingLoader} so F2/F3 tests
     * can exercise the real staging/cap/warning orchestration in {@code materializeFiles} without
     * ever constructing a real Google Drive/OneDrive client.
     */
    private static final class FakeFileDownloadingLoader implements DocumentLoader, FileDownloadingLoader {
        interface Behavior {
            List<Path> download(DocumentSourceDescriptor descriptor, Path destination, Consumer<String> warnings)
                    throws Exception;
        }

        private final Behavior behavior;
        private volatile DocumentSourceDescriptor lastDescriptor;

        FakeFileDownloadingLoader(Behavior behavior) {
            this.behavior = behavior;
        }

        DocumentSourceDescriptor lastDescriptor() {
            return lastDescriptor;
        }

        @Override
        public String getName() {
            return "fake-file-loader";
        }

        @Override
        public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
            return true;
        }

        @Override
        public List<Document> load(DocumentSourceDescriptor sourceDescriptor) {
            throw new UnsupportedOperationException("materializeFiles never calls load() on a FileDownloadingLoader");
        }

        @Override
        public List<Path> downloadTo(DocumentSourceDescriptor sourceDescriptor, Path destination) throws Exception {
            return downloadTo(sourceDescriptor, destination, warning -> { });
        }

        @Override
        public List<Path> downloadTo(
                DocumentSourceDescriptor sourceDescriptor, Path destination, Consumer<String> warnings)
                throws Exception {
            this.lastDescriptor = sourceDescriptor;
            return behavior.download(sourceDescriptor, destination, warnings);
        }
    }

    /**
     * Hand-written double for {@link DocumentLoader} so F4/F5 tests can exercise the incremental
     * sync, attachment-rewrite, and merge/replace orchestration in {@code materialize} without a
     * real network-backed loader. {@code behavior} may inspect {@code descriptor.getMetadata()}
     * (e.g. {@code since}, {@code attachmentDirectory}) and write attachment bytes as a real
     * attachment-capable loader would; {@code callNumber} lets a single test simulate the
     * "missing source_path forces exactly one fallback re-fetch" retry.
     */
    private static final class FakeDocumentLoader implements DocumentLoader {
        interface Behavior {
            List<Document> load(DocumentSourceDescriptor descriptor, int callNumber) throws Exception;
        }

        private final Behavior behavior;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile DocumentSourceDescriptor lastDescriptor;

        FakeDocumentLoader(Behavior behavior) {
            this.behavior = behavior;
        }

        FakeDocumentLoader(List<Document> fixed) {
            this((descriptor, callNumber) -> fixed);
        }

        int callCount() {
            return calls.get();
        }

        DocumentSourceDescriptor lastDescriptor() {
            return lastDescriptor;
        }

        @Override
        public String getName() {
            return "fake-document-loader";
        }

        @Override
        public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
            return true;
        }

        @Override
        public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
            this.lastDescriptor = sourceDescriptor;
            return behavior.load(sourceDescriptor, calls.incrementAndGet());
        }
    }
}
