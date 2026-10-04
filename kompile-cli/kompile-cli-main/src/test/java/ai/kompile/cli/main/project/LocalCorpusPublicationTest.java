/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Offline synthetic corpus tests: no model, service, graph, or inference dependencies. */
class LocalCorpusPublicationTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KB = "fixture";

    private record Fixture(Path root, Path sources, Path output, Path markdown,
                           String retainedDocument, String retainedChunk, byte[] retainedMarkdown) { }

    private Fixture fixture() throws Exception {
        Path root = temp.toRealPath();
        Path sources = Files.createDirectories(root.resolve("inputs"));
        Path output = Files.createDirectories(root.resolve("data/crawls/" + KB));
        Path markdown = Files.createDirectories(root.resolve("data/markdown/" + KB));
        Files.writeString(sources.resolve("a.txt"), "selected source new content\n");
        Files.writeString(sources.resolve("b.txt"), "unrelated source\n");
        String retained = "  " + JSON.writeValueAsString(document(root, sources.resolve("b.txt"), "b")) + "\r\n";
        String retainedChunk = " " + JSON.writeValueAsString(chunk("b", "retained exact chunk")) + "\r\n";
        Files.writeString(output.resolve("documents.jsonl"), retained
                + JSON.writeValueAsString(document(root, sources.resolve("a.txt"), "a")) + "\n");
        Files.writeString(output.resolve("chunks.jsonl"), retainedChunk
                + JSON.writeValueAsString(chunk("a", "old selected chunk")) + "\n");
        Files.writeString(output.resolve("crawl-result.json"), "{\"documentCount\":2}\n");
        Files.writeString(output.resolve("analysis.json"), "{\"wordCount\":7}\n");
        byte[] retainedMarkdown = "# Unrelated\r\n日本語  EXACT  \r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(markdown.resolve("b.md"), retainedMarkdown);
        Files.writeString(markdown.resolve("a.md"), "# Old selected\n");
        return new Fixture(root, sources, output, markdown, retained, retainedChunk, retainedMarkdown);
    }

    private static ObjectNode document(Path root, Path source, String id) throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("documentId", id); row.put("source", source.toString());
        row.put("relativePath", root.relativize(source).toString().replace('\\', '/'));
        row.put("sizeBytes", Files.size(source));
        row.put("markdownPath", "data/markdown/" + KB + "/" + id + ".md");
        row.put("extractionStatus", "EXTRACTED"); row.put("pipelineId", "standard-text");
        row.put("pipelineType", "STANDARD_TEXT"); row.put("arbitraryRetainedField", "retain me exactly");
        return row;
    }

    private static ObjectNode chunk(String id, String text) {
        ObjectNode row = JSON.createObjectNode();
        row.put("chunkId", id + "#chunk-0"); row.put("documentId", id); row.put("index", 0); row.put("text", text);
        return row;
    }

    private ObjectNode request(Fixture fixture) {
        ObjectNode request = JSON.createObjectNode();
        request.put("corpusUpdate", LocalCorpusPublication.UPDATE);
        request.put("strictSteps", true); request.put("deriveOntology", false);
        request.putObject("reasoningLearning").put("enabled", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putArray("steps").add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING").add("LEXICAL_INDEX");
        ObjectNode selection = request.putArray("documents").addObject();
        selection.put("path", fixture.sources.toString()); selection.put("sourceType", "DIRECTORY");
        selection.put("pipelineId", "standard-text"); selection.putArray("includePatterns").add("a.txt");
        return request;
    }

    private LocalCorpusPublication.Context context(JsonNode request) {
        return new LocalCorpusPublication.Context("job-" + UUID.randomUUID(), request, action -> action.call());
    }

    private LocalCorpusPublication.StagedExtractor extractor(Fixture fixture) {
        return (generation, markdown, selected) -> {
            assertEquals(List.of(fixture.sources.resolve("a.txt")), selected);
            assertTrue(Files.readString(fixture.output.resolve("chunks.jsonl")).contains("old selected chunk"));
            try {
                Files.writeString(generation.resolve("documents.jsonl"),
                        JSON.writeValueAsString(document(fixture.root, selected.get(0), "a")) + "\n");
                Files.writeString(generation.resolve("chunks.jsonl"),
                        JSON.writeValueAsString(chunk("a", "replacement selected chunk")) + "\n");
                Files.writeString(markdown.resolve("a.md"), "# Replacement selected\n");
            } catch (Exception e) { throw new IOException(e); }
        };
    }

    private JsonNode publish(Fixture fixture, ObjectNode request) throws IOException {
        return LocalCorpusPublication.publish(fixture.root, KB, request, context(request), extractor(fixture));
    }

    private Map<Path, byte[]> snapshot(Fixture fixture) throws IOException {
        Map<Path, byte[]> bytes = new LinkedHashMap<>();
        for (String name : List.of("documents.jsonl", "chunks.jsonl", "crawl-result.json", "analysis.json", "corpus-current.json")) {
            Path path = fixture.output.resolve(name);
            if (Files.exists(path)) bytes.put(path, Files.readAllBytes(path));
        }
        for (String name : List.of("a.md", "b.md")) {
            Path path = fixture.markdown.resolve(name);
            if (Files.exists(path)) bytes.put(path, Files.readAllBytes(path));
        }
        return bytes;
    }

    private static void assertSnapshot(Map<Path, byte[]> before) throws IOException {
        for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
    }

    @Test void preservesSummarySelectionAndRejectsChangedOrNonlexicalRequests() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        ObjectNode altered = request.deepCopy(); altered.put("name", "different submission");
        assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB,
                request, context(altered), extractor(fixture)));
        ObjectNode training = request.deepCopy(); ((ObjectNode) training.path("embeddingTraining")).put("enabled", true);
        assertThrows(IOException.class, () -> publish(fixture, training));
        ObjectNode missing = request.deepCopy(); missing.remove("embeddingTraining");
        assertThrows(IOException.class, () -> publish(fixture, missing));
        JsonNode receipt = LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                (generation, markdown, selected) -> {
                    extractor(fixture).extract(generation, markdown, selected);
                    Files.writeString(generation.resolve("crawl-result.json"),
                            "{\"name\":\"fixture summary\",\"loaderName\":\"plain-text\"}");
                });
        JsonNode summary = JSON.readTree(fixture.output.resolve("crawl-result.json").toFile());
        assertEquals("fixture summary", summary.path("name").asText());
        assertEquals("plain-text", summary.path("loaderName").asText());
        assertEquals(KB, summary.path("profileId").asText());
        assertEquals(receipt.path("jobId"), summary.path("jobId"));
        assertEquals(receipt.path("generationId"), summary.path("generationId"));
        assertEquals(request.path("documents"), summary.path("sourceConfigurations"));
        assertEquals(request.path("documents").get(0).path("includePatterns"), summary.path("includePatterns"));
        assertEquals(fixture.sources.toString(), summary.path("sources").get(0).asText());
        assertEquals("data/crawls/fixture/analysis.json", summary.path("analysisPath").asText());
    }

    @Test void missingUnrelatedMarkdownFailsBeforePublication() throws Exception {
        Fixture fixture = fixture(); Map<Path, byte[]> before = snapshot(fixture);
        Files.delete(fixture.markdown.resolve("b.md")); before.remove(fixture.markdown.resolve("b.md"));
        assertThrows(IOException.class, () -> publish(fixture, request(fixture)));
        assertSnapshot(before);
        assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
    }

    @Test void retainsExactUnrelatedRowsAndMarkdownAndReplacementIsIdempotent() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        var retainedMtime = Files.getLastModifiedTime(fixture.markdown.resolve("b.md"));
        JsonNode receipt = publish(fixture, request);
        assertEquals("kompile-local-corpus-receipt/v1", receipt.path("schema").asText());
        assertEquals("CORPUS_COMMITTED", receipt.path("status").asText());
        assertEquals("LEXICAL_ONLY", receipt.path("scope").asText());
        assertEquals(2, receipt.path("counts").path("documentCount").asInt());
        assertEquals(1, receipt.path("counts").path("retainedDocumentCount").asInt());
        assertEquals(1, receipt.path("counts").path("selectedDocumentCount").asInt());
        assertTrue(receipt.path("baseline").path("generationId").isNull());
        String documents = Files.readString(fixture.output.resolve("documents.jsonl"));
        String chunks = Files.readString(fixture.output.resolve("chunks.jsonl"));
        assertTrue(documents.startsWith(fixture.retainedDocument));
        assertTrue(chunks.startsWith(fixture.retainedChunk));
        assertFalse(chunks.contains("old selected chunk"));
        assertArrayEquals(fixture.retainedMarkdown, Files.readAllBytes(fixture.markdown.resolve("b.md")));
        assertEquals(retainedMtime, Files.getLastModifiedTime(fixture.markdown.resolve("b.md")));
        Path first = LocalCorpusPublication.resolveReadDirectory(fixture.root, KB);
        assertEquals(receipt.path("generationId").asText(), first.getFileName().toString());
        assertArrayEquals(fixture.retainedMarkdown, Files.readAllBytes(first.resolve("markdown/b.md")));
        // On the next call the extraction observer expects the newly current selected content.
        JsonNode second = LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                (generation, markdown, selected) -> {
                    Files.writeString(generation.resolve("documents.jsonl"), JSON.writeValueAsString(document(fixture.root, selected.get(0), "a")) + "\n");
                    Files.writeString(generation.resolve("chunks.jsonl"), JSON.writeValueAsString(chunk("a", "replacement selected chunk")) + "\n");
                    Files.writeString(markdown.resolve("a.md"), "# Replacement selected\n");
                });
        assertEquals(receipt.path("generationId").asText(), second.path("baseline").path("generationId").asText());
        assertEquals(documents, Files.readString(fixture.output.resolve("documents.jsonl")));
        assertEquals(chunks, Files.readString(fixture.output.resolve("chunks.jsonl")));
        assertNotEquals(first, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        assertEquals(chunks, Files.readString(first.resolve("chunks.jsonl")), "prior generation remains immutable");
        assertFalse(receipt.path("artifacts").has("data/crawls/" + KB + "/corpus-current.json"));
        assertFalse(receipt.path("artifacts").has("data/crawls/" + KB + "/corpus-generations/" + receipt.path("generationId").asText() + "/corpus-receipt.json"));
    }

    @Test void canonicalDigestSortsObjectsOnlyAndFreezesSubmission() throws Exception {
        ObjectNode a = (ObjectNode) JSON.readTree("{\"z\":[2,1],\"b\":{\"x\":3,\"a\":4},\"async\":true}");
        ObjectNode b = (ObjectNode) JSON.readTree("{\"b\":{\"a\":4,\"x\":3},\"z\":[2,1],\"_asyncJobId\":\"worker\"}");
        assertEquals(LocalCorpusPublication.requestDigest(a), LocalCorpusPublication.requestDigest(b));
        b.putArray("z").add(1).add(2);
        assertNotEquals(LocalCorpusPublication.requestDigest(a), LocalCorpusPublication.requestDigest(b));
        LocalCorpusPublication.Context frozen = context(a); a.put("mutated", true);
        assertFalse(frozen.submittedRequest().has("mutated"));
        ((ObjectNode) frozen.submittedRequest()).put("mutated", true);
        assertFalse(frozen.submittedRequest().has("mutated"));
    }

    @Test void commandRequiresContextAndIgnoresBroadProfileSelectionInSafeMode() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        String literal = "{\"text\":\"Literal  rule\\t日本語\"}\n\n  Continued\tline\n";
        Files.writeString(fixture.sources.resolve("a.txt"), literal);
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId(KB); profile.setSources(List.of(fixture.sources.toString()));
        profile.setIncludePatterns(List.of("*.txt"));
        ProjectCrawlCommand.ModelPipelineExecutor forbidden = (root, source, pipeline, text) -> {
            throw new AssertionError("No model may run for this lexical request");
        };
        assertThrows(IOException.class, () -> ProjectCrawlCommand.executeLocalCrawl(profile, fixture.root, false, request, forbidden));
        var dry = ProjectCrawlCommand.executeLocalCrawl(profile, fixture.root, true, request, forbidden);
        assertTrue(dry.dryRun()); assertNull(dry.corpusReceipt());
        var result = ProjectCrawlCommand.executeLocalCrawl(profile, fixture.root, false, request, forbidden, context(request));
        assertEquals(1, result.corpusReceipt().path("counts").path("selectedDocumentCount").asInt());
        assertEquals(2, result.documentCount()); assertArrayEquals(fixture.retainedMarkdown, Files.readAllBytes(fixture.markdown.resolve("b.md")));
        assertFalse(Files.exists(fixture.markdown.resolve("a.md")), "replaced source's obsolete ID artifact removed");
        JsonNode selected = Files.readAllLines(fixture.output.resolve("documents.jsonl")).stream()
                .map(line -> { try { return JSON.readTree(line); } catch (IOException e) { throw new AssertionError(e); } })
                .filter(row -> row.path("source").asText().equals(fixture.sources.resolve("a.txt").toString())).findFirst().orElseThrow();
        assertTrue(Files.readString(fixture.root.resolve(selected.path("markdownPath").asText())).contains(literal),
                "literal JSON-string whitespace and text must survive the lexical extraction unchanged");
    }

    @Test void rejectsMalformedDuplicateAndOrphanBaselinesWithoutPromotion() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        Path documents = fixture.output.resolve("documents.jsonl"), chunks = fixture.output.resolve("chunks.jsonl");
        byte[] originalDocs = Files.readAllBytes(documents), originalChunks = Files.readAllBytes(chunks);
        List<String> invalidDocs = List.of("{broken\n", "{\"documentId\":\"a\",\"documentId\":\"b\"}\n",
                Files.readString(documents) + fixture.retainedDocument,
                Files.readString(documents).replace("data/markdown/fixture/b.md", "../outside.md"),
                Files.readString(documents).replace("b.txt", "A.TXT"));
        for (String invalid : invalidDocs) {
            Files.writeString(documents, invalid); Map<Path, byte[]> before = snapshot(fixture);
            assertThrows(IOException.class, () -> publish(fixture, request)); assertSnapshot(before);
            assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
        }
        Files.write(documents, originalDocs);
        Files.writeString(chunks, JSON.writeValueAsString(chunk("missing", "orphan")) + "\n");
        Map<Path, byte[]> before = snapshot(fixture);
        assertThrows(IOException.class, () -> publish(fixture, request)); assertSnapshot(before);
        Files.write(chunks, originalChunks);
        Files.writeString(documents, fixture.retainedDocument.stripTrailing());
        Files.writeString(chunks, fixture.retainedChunk);
        // Exact raw retention cannot append a replacement after an unterminated unrelated row.
        assertThrows(IOException.class, () -> publish(fixture, request));
    }

    @Test void rejectsBoundedRowOverflowAndSourceCollisionAndMissingSelection() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        String original = Files.readString(fixture.output.resolve("documents.jsonl"));
        Files.writeString(fixture.output.resolve("documents.jsonl"), "{\"large\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}\n");
        assertThrows(IOException.class, () -> publish(fixture, request));
        Files.writeString(fixture.output.resolve("documents.jsonl"), original);
        Files.writeString(fixture.sources.resolve("A.TXT"), "alias collision\n");
        ((ObjectNode) request.path("documents").get(0)).putArray("includePatterns").add("a.txt").add("A.TXT");
        assertThrows(IOException.class, () -> publish(fixture, request));
        ((ObjectNode) request.path("documents").get(0)).putArray("includePatterns").add("missing.txt");
        assertThrows(IOException.class, () -> publish(fixture, request));
        assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
    }

    @Test void sourceChangeExtractorFailureAndEmptyExtractionDoNotPromote() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture); Map<Path, byte[]> before = snapshot(fixture);
        assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                (generation, markdown, selected) -> { throw new IOException("synthetic extraction failure"); }));
        assertSnapshot(before);
        assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                (generation, markdown, selected) -> {
                    extractor(fixture).extract(generation, markdown, selected);
                    Files.writeString(selected.get(0), "changed while extraction was in flight\n");
                }));
        assertSnapshot(before);
        assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                (generation, markdown, selected) -> {
                    Files.writeString(generation.resolve("documents.jsonl"), ""); Files.writeString(generation.resolve("chunks.jsonl"), "");
                }));
        assertSnapshot(before); assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
        Files.writeString(fixture.sources.resolve("a.txt"), "   \n");
        assertThrows(IOException.class, () -> publish(fixture, request));
    }

    @Test void cancellationFenceAndInterruptedExtractionPreserveCurrentBytes() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture); Map<Path, byte[]> before = snapshot(fixture);
        try {
            var cancelled = new LocalCorpusPublication.Context("cancelled-job", request, action -> {
                throw new InterruptedException("cancel wins the fence");
            });
            assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, cancelled, extractor(fixture)));
            assertTrue(Thread.interrupted()); assertSnapshot(before);
            assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, context(request),
                    (generation, markdown, selected) -> {
                        extractor(fixture).extract(generation, markdown, selected); Thread.currentThread().interrupt();
                    }));
            assertTrue(Thread.interrupted()); assertSnapshot(before);
            assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
        } finally { Thread.interrupted(); }
    }

    @Test void publicationIoFailureRollsBackAllProjectionsAndPriorMarker() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture); publish(fixture, request);
        Map<Path, byte[]> before = snapshot(fixture); Path prior = LocalCorpusPublication.resolveReadDirectory(fixture.root, KB);
        AtomicReference<Path> staged = new AtomicReference<>();
        var failAfterStaging = new LocalCorpusPublication.Context("io-failure-job", request, action -> {
            Files.delete(staged.get().resolve("markdown/a.md")); // force publication I/O failure after JSONL projections
            return action.call();
        });
        assertThrows(IOException.class, () -> LocalCorpusPublication.publish(fixture.root, KB, request, failAfterStaging,
                (generation, markdown, selected) -> {
                    staged.set(generation);
                    Files.writeString(generation.resolve("documents.jsonl"), JSON.writeValueAsString(document(fixture.root, selected.get(0), "a")) + "\n");
                    Files.writeString(generation.resolve("chunks.jsonl"), JSON.writeValueAsString(chunk("a", "later replacement")) + "\n");
                    Files.writeString(markdown.resolve("a.md"), "newer selected text\n");
                }));
        assertSnapshot(before); assertEquals(prior, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        assertFalse(Files.exists(fixture.output.resolve(".corpus-publication.json")));
    }

    @Test void pointerReceiptAndGenerationTamperFailClosedWhileProjectionTamperDoesNotRedirectReaders() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture); publish(fixture, request);
        Path current = LocalCorpusPublication.resolveReadDirectory(fixture.root, KB);
        Files.writeString(fixture.output.resolve("chunks.jsonl"), "tampered projection\n");
        assertEquals(current, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        assertThrows(IOException.class, () -> publish(fixture, request), "a writer must not overwrite divergent projections");
        Path immutable = current.resolve("chunks.jsonl"); byte[] bytes = Files.readAllBytes(immutable);
        Files.writeString(immutable, "tampered immutable\n");
        assertThrows(IOException.class, () -> LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        Files.write(immutable, bytes);
        Path receipt = current.resolve("corpus-receipt.json"); byte[] receiptBytes = Files.readAllBytes(receipt);
        Files.writeString(receipt, "{}\n");
        assertThrows(IOException.class, () -> LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        Files.write(receipt, receiptBytes);
        Path marker = fixture.output.resolve("corpus-current.json");
        Files.writeString(marker, "{\"schema\":\"bad\"}\n");
        assertThrows(IOException.class, () -> LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void interruptedJournal(Fixture fixture, boolean priorMarker) throws Exception {
        if (priorMarker) publish(fixture, request(fixture));
        String generation = "gen-" + UUID.randomUUID();
        Path transaction = Files.createDirectories(fixture.output.resolve("corpus-transactions/" + generation));
        Path beforeDirectory = Files.createDirectories(transaction.resolve("before"));
        ObjectNode journal = JSON.createObjectNode(); journal.put("schema", "kompile-local-corpus-journal/v1");
        journal.put("generationId", generation); journal.put("beforeDirectory", fixture.root.relativize(beforeDirectory).toString());
        var targets = journal.putArray("targets"); var beforeHashes = journal.putObject("beforeHashes");
        int index = 0;
        for (String name : List.of("documents.jsonl", "chunks.jsonl", "crawl-result.json", "analysis.json", "corpus-current.json")) {
            Path target = fixture.output.resolve(name);
            ObjectNode entry = targets.addObject(); entry.put("path", fixture.root.relativize(target).toString());
            if (Files.exists(target)) {
                byte[] bytes = Files.readAllBytes(target); Path backup = transaction.resolve("backup-" + index);
                Files.write(backup, bytes); entry.put("backup", fixture.root.relativize(backup).toString()); entry.put("sha256", sha(bytes));
                if (!name.equals("corpus-current.json")) { Files.write(beforeDirectory.resolve(name), bytes); beforeHashes.put(name, sha(bytes)); }
            } else entry.putNull("backup");
            index++;
        }
        Files.writeString(fixture.output.resolve(".corpus-publication.json"), JSON.writeValueAsString(journal));
    }

    @Test void firstPublicationCrashReadersUseFrozenBackupAndNextLockedWriterRecovers() throws Exception {
        Fixture fixture = fixture(); Map<Path, byte[]> before = snapshot(fixture);
        interruptedJournal(fixture, false);
        Files.writeString(fixture.output.resolve("documents.jsonl"), "partial new documents\n");
        Path read = LocalCorpusPublication.resolveReadDirectory(fixture.root, KB);
        assertTrue(read.endsWith("before"));
        assertTrue(Files.readString(read.resolve("documents.jsonl")).startsWith(fixture.retainedDocument));
        try (var ignored = LocalCorpusPublication.lock(fixture.root, KB)) { assertSnapshot(before); }
        assertFalse(Files.exists(fixture.output.resolve(".corpus-publication.json")));
        assertEquals(fixture.output, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
    }

    @Test void laterCrashReadersUsePriorImmutableGenerationAndNextWriterRecovers() throws Exception {
        Fixture fixture = fixture(); interruptedJournal(fixture, true);
        Path prior = LocalCorpusPublication.resolveReadDirectory(fixture.root, KB); Map<Path, byte[]> before = snapshot(fixture);
        Files.writeString(fixture.output.resolve("chunks.jsonl"), "partial new chunks\n");
        assertEquals(prior, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
        try (var ignored = LocalCorpusPublication.lock(fixture.root, KB)) { assertSnapshot(before); }
        assertEquals(prior, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
    }

    @Test void symlinksAndPathEscapesFailClosed() throws Exception {
        Fixture fixture = fixture(); ObjectNode request = request(fixture);
        ((ObjectNode) request.path("documents").get(0)).putArray("includePatterns").add("../escape.txt");
        assertThrows(IOException.class, () -> publish(fixture, request));
        ((ObjectNode) request.path("documents").get(0)).putArray("includePatterns").add("*.txt");
        assertThrows(IOException.class, () -> publish(fixture, request));
        final ObjectNode validRequest = request(fixture);
        Files.delete(fixture.sources.resolve("a.txt")); Files.createSymbolicLink(fixture.sources.resolve("a.txt"), fixture.sources.resolve("b.txt"));
        assertThrows(IOException.class, () -> publish(fixture, validRequest));
        Files.delete(fixture.sources.resolve("a.txt")); Files.writeString(fixture.sources.resolve("a.txt"), "restored selected\n");
        Files.delete(fixture.markdown.resolve("b.md")); Files.createSymbolicLink(fixture.markdown.resolve("b.md"), fixture.sources.resolve("b.txt"));
        assertThrows(IOException.class, () -> publish(fixture, validRequest));
        assertThrows(IOException.class, () -> LocalCorpusPublication.resolveReadDirectory(fixture.root, ".."));
    }

    @Test void persistentJvmLockSerializesWritersAndLegacyInvalidatesMarker() throws Exception {
        Fixture fixture = fixture(); publish(fixture, request(fixture));
        CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try (var ignored = LocalCorpusPublication.lock(fixture.root, KB)) {
                acquired.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (Throwable e) { failure.set(e); }
        });
        try (var lock = LocalCorpusPublication.lock(fixture.root, KB)) {
            waiter.start();
            try {
                assertFalse(acquired.await(100, TimeUnit.MILLISECONDS));
                // The owned lock remains valid throughout legacy invalidation.
                LocalCorpusPublication.invalidateCurrent(fixture.root, KB);
                assertFalse(Files.exists(fixture.output.resolve("corpus-current.json")));
                assertEquals(fixture.output, LocalCorpusPublication.resolveReadDirectory(fixture.root, KB));
            } finally { release.countDown(); }
        }
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
    }
}
