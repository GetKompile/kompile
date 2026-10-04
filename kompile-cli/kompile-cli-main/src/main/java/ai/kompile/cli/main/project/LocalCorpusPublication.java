/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.project;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Opt-in, lexical-only selected-source replacement. The immutable marker is the read boundary;
 * compatibility projections are recoverable, not a multi-file atomic read boundary. Receipts prove
 * local integrity/lifecycle only, not authenticity against local actors or graph/model qualification.
 * Requires a local POSIX filesystem with anchored NOFOLLOW checks, secure directory streams,
 * directory fsync, and atomic same-directory rename; there is deliberately no move fallback.
 */
public final class LocalCorpusPublication {
    public static final String UPDATE = "preserve-unselected-v1";
    private static final String POINTER = "kompile-local-corpus-pointer/v1";
    private static final String RECEIPT = "kompile-local-corpus-receipt/v1";
    private static final String JOURNAL = "kompile-local-corpus-journal/v1";
    private static final String MARKER = "corpus-current.json";
    private static final String JOURNAL_FILE = ".corpus-publication.json";
    private static final List<String> PROJECTIONS = List.of(
            "documents.jsonl", "chunks.jsonl", "crawl-result.json", "analysis.json");
    private static final Set<String> STEPS = Set.of(
            "LOADING", "MARKDOWN_EXTRACTION", "CHUNKING", "LEXICAL_INDEX");
    private static final long MAX_FILE = 64L * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final int MAX_ROW = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_SELECTION = 1_000;
    // These JVM locks are never removed: deleting one while a waiter holds it splits the lock domain.
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static {
        JSON.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private LocalCorpusPublication() { }

    @FunctionalInterface
    public interface CommitFence {
        JsonNode commit(Callable<JsonNode> criticalSection) throws Exception;
    }

    /** Frozen submission and genuine registry job identity. Never synthesizes a job ID. */
    public record Context(String jobId, JsonNode submittedRequest, CommitFence fence) {
        public Context {
            if (jobId == null || jobId.isBlank() || jobId.length() > 256 || fence == null
                    || submittedRequest == null || !submittedRequest.isObject()) {
                throw new IllegalArgumentException("A real jobId, submission snapshot and cancellation fence are required");
            }
            submittedRequest = normalizedRequest(submittedRequest);
        }
        @Override public JsonNode submittedRequest() { return submittedRequest.deepCopy(); }
    }

    @FunctionalInterface
    interface StagedExtractor {
        void extract(Path generation, Path markdown, List<Path> selected) throws IOException;
    }

    /** Only transport/lifecycle controls are removed; array ordering remains significant. */
    public static JsonNode normalizedRequest(JsonNode request) {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("Request must be an object");
        ObjectNode copy = request.deepCopy();
        copy.remove(List.of("async", "waitForCompletion", "_asyncWorker", "_asyncJobId"));
        return canonical(copy, 0);
    }

    public static String requestDigest(JsonNode request) {
        return hash(jsonBytes(normalizedRequest(request)));
    }

    private static JsonNode canonical(JsonNode node, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON depth exceeds 64");
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> result.set(key, canonical(value, depth + 1)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonical(value, depth + 1)));
            return result;
        }
        return node.deepCopy();
    }

    public static boolean isRequested(JsonNode request) throws IOException {
        if (request == null || !request.has("corpusUpdate")) return false;
        require(UPDATE.equals(text(request, "corpusUpdate")), "Unknown corpusUpdate");
        return true;
    }

    /** Defense-in-depth for embedders; the host also calls this before accepting a job. No I/O. */
    public static void validateRequest(JsonNode request) throws IOException {
        require(request != null && request.isObject() && UPDATE.equals(text(request, "corpusUpdate")),
                "Expected preserve-unselected-v1 request");
        require(jsonBytes(request).length <= MAX_ROW, "Request too large");
        Set<String> allowed = Set.of("corpusUpdate", "documents", "steps", "strictSteps", "deriveOntology",
                "reasoningLearning", "embeddingTraining", "knowledgeBase", "name", "dryRun", "async", "waitForCompletion",
                "_asyncWorker", "_asyncJobId");
        for (var it = request.fieldNames(); it.hasNext();) {
            String field = it.next();
            require(allowed.contains(field), "Unsupported safe-mode request field: " + field);
        }
        require(request.path("strictSteps").isBoolean() && request.path("strictSteps").booleanValue(),
                "strictSteps must be true");
        require(request.path("deriveOntology").isBoolean() && !request.path("deriveOntology").booleanValue(),
                "deriveOntology must be false");
        JsonNode learning = request.path("reasoningLearning");
        require(learning.isObject() && learning.size() == 1 && learning.path("enabled").isBoolean()
                && !learning.path("enabled").booleanValue(), "reasoningLearning.enabled must be false, without options");
        JsonNode training = request.path("embeddingTraining");
        require(training.isObject() && training.size() == 1 && training.path("enabled").isBoolean()
                && !training.path("enabled").booleanValue(), "embeddingTraining.enabled must be false, without options");
        JsonNode steps = request.path("steps");
        require(steps.isArray() && steps.size() == STEPS.size(), "Exactly four lexical steps are required");
        Set<String> actualSteps = new HashSet<>();
        for (JsonNode step : steps) require(step.isTextual() && actualSteps.add(step.textValue()), "Duplicate/invalid step");
        require(actualSteps.equals(STEPS), "Only the four lexical steps are supported");
        JsonNode documents = request.path("documents");
        require(documents.isArray() && documents.size() == 1 && documents.get(0).isObject(),
                "Exactly one DIRECTORY selection is required");
        JsonNode document = documents.get(0);
        Set<String> documentFields = Set.of("path", "sourceType", "pipelineId", "includePatterns");
        for (var it = document.fieldNames(); it.hasNext();) {
            String field = it.next();
            require(documentFields.contains(field), "Unsupported selection option: " + field);
        }
        require("DIRECTORY".equals(text(document, "sourceType"))
                && "standard-text".equals(text(document, "pipelineId")), "Only DIRECTORY standard-text is supported");
        Path directory = absolute(text(document, "path"));
        require(directory.toString().equals(text(document, "path")), "Selection directory must be normalized absolute path");
        JsonNode includes = document.path("includePatterns");
        require(includes.isArray() && includes.size() > 0 && includes.size() <= MAX_SELECTION,
                "Bounded nonempty exact includePatterns required");
        Set<String> names = new HashSet<>();
        for (JsonNode include : includes) {
            require(include.isTextual(), "Selection must be textual");
            String value = include.textValue();
            Path relative = relative(value);
            require(!value.matches(".*[\\*?\\[\\]{}].*") && names.add(value), "Globs/duplicate selections are forbidden");
            String extension = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            require(extension.endsWith(".txt") || extension.endsWith(".md") || extension.endsWith(".markdown"),
                    "v1 selects plain UTF-8 text/markdown files only");
        }
    }

    static final class WriterLock implements AutoCloseable {
        private final ReentrantLock jvm;
        private final FileChannel channel;
        private final FileLock os;
        private WriterLock(ReentrantLock jvm, FileChannel channel, FileLock os) {
            this.jvm = jvm; this.channel = channel; this.os = os;
        }
        @Override public void close() throws IOException {
            try { os.release(); } finally { try { channel.close(); } finally { jvm.unlock(); } }
        }
    }

    /** Persistent per-KB OS lock plus interruptible in-JVM serialization, shared with legacy writes. */
    static WriterLock lock(Path projectRoot, String kb) throws IOException {
        Path root = root(projectRoot);
        Path output = output(root, kb);
        directories(root, output);
        ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(output, ignored -> new ReentrantLock());
        try { jvm.lockInterruptibly(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled waiting for corpus writer lock");
        }
        FileChannel channel = null;
        FileLock os = null;
        try {
            Path path = output.resolve(".corpus.lock");
            if (exists(path)) regular(root, path);
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            regular(root, path);
            os = channel.lock();
            WriterLock result = new WriterLock(jvm, channel, os);
            recover(root, kb);
            cleanTemporaryFiles(root, output);
            Path md = markdown(root, kb);
            if (exists(md)) cleanTemporaryFiles(root, md);
            return result;
        } catch (IOException | RuntimeException e) {
            try {
                if (os != null) os.release();
            } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            finally {
                try { if (channel != null) channel.close(); }
                catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
                finally { jvm.unlock(); }
            }
            throw e;
        }
    }

    static void invalidateCurrent(Path projectRoot, String kb) throws IOException {
        Path root = root(projectRoot);
        Path marker = output(root, kb).resolve(MARKER);
        if (exists(marker)) {
            regular(root, marker);
            Files.delete(marker);
            syncDirectory(marker.getParent());
        }
        // Legacy extraction must not follow symlinked projection or markdown targets either.
        for (String name : PROJECTIONS) {
            Path path = output(root, kb).resolve(name);
            if (exists(path)) regular(root, path);
        }
        Path markdown = markdown(root, kb);
        directories(root, markdown);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(markdown)) {
            for (Path path : stream) regular(root, path);
        }
    }

    static JsonNode publish(Path projectRoot, String kb, JsonNode request, Context context,
                            StagedExtractor extractor) throws IOException {
        validateRequest(request);
        require(context != null, "Non-dry safe publication requires the job registry Context");
        JsonNode effective = normalizedRequest(request);
        validateRequest(context.submittedRequest());
        require(effective.equals(context.submittedRequest()), "Submitted/effective corpus request mismatch");
        Path root = root(projectRoot);
        try (WriterLock ignored = lock(root, kb)) {
            Budget budget = new Budget();
            Path output = output(root, kb);
            Path fixedMarkdown = markdown(root, kb);
            directories(root, fixedMarkdown);
            Path baselineDirectory = resolveReadDirectory(root, kb);
            Corpus baseline = corpus(root, kb, baselineDirectory, budget, false);
            Map<String, String> before = baselineHashes(root, kb, baseline, budget);
            String baselineGeneration = exists(output.resolve(MARKER))
                    ? text(parse(read(root, output.resolve(MARKER), MAX_ROW, budget)), "generationId") : null;
            if (baselineGeneration != null) verifyProjections(root, kb, baselineDirectory, baseline);
            List<Pin> pins = pins(root, effective, budget);
            Set<String> selected = new LinkedHashSet<>();
            for (Pin pin : pins) selected.add(pin.path().toString());
            collisionCheck(baseline.documents, selected, pins);
            String generationId = "gen-" + UUID.randomUUID();
            Path generation = output.resolve("corpus-generations").resolve(generationId);
            directories(root, generation.resolve("markdown"));
            checkCancelled();
            extractor.extract(generation, generation.resolve("markdown"), pins.stream().map(Pin::path).toList());
            checkCancelled();
            verifyPins(root, pins);
            Corpus staged = corpus(root, kb, generation, budget, true);
            require(staged.documents.size() == selected.size(), "Missing exact selected extraction");
            Set<String> extracted = new HashSet<>(), chunkDocuments = new HashSet<>();
            for (Row chunk : staged.chunks) chunkDocuments.add(text(chunk.json, "documentId"));
            for (Row row : staged.documents) {
                require(selected.contains(text(row.json, "source")) && extracted.add(text(row.json, "source")),
                        "Extraction includes unexpected source");
                require("EXTRACTED".equals(text(row.json, "extractionStatus")), "Selected extraction failed/empty");
                require("STANDARD_TEXT".equals(text(row.json, "pipelineType"))
                        && "standard-text".equals(text(row.json, "pipelineId")), "Nonlexical extraction rejected");
                require(chunkDocuments.contains(text(row.json, "documentId")), "Selected source has no nonempty chunks");
                Pin pin = pins.stream().filter(p -> p.path.toString().equals(text(row.json, "source"))).findFirst().orElseThrow();
                require(row.json.path("sizeBytes").isIntegralNumber() && row.json.path("sizeBytes").asLong() == pin.size,
                        "Selected document size differs from source pin");
            }
            List<Row> documents = new ArrayList<>();
            Set<String> retainedIds = new HashSet<>();
            for (Row row : baseline.documents) {
                if (!selected.contains(text(row.json, "source"))) {
                    documents.add(row); retainedIds.add(text(row.json, "documentId"));
                    Path md = baseline.markdownFiles.get(text(row.json, "documentId"));
                    if (md != null) write(root, generation.resolve("markdown").resolve(md.getFileName()),
                            read(root, md, MAX_FILE, budget), false);
                }
            }
            int retainedCount = documents.size();
            documents.addAll(staged.documents);
            List<Row> chunks = new ArrayList<>();
            for (Row row : baseline.chunks) if (retainedIds.contains(text(row.json, "documentId"))) chunks.add(row);
            chunks.addAll(staged.chunks);
            write(root, generation.resolve("documents.jsonl"), rows(documents), true);
            write(root, generation.resolve("chunks.jsonl"), rows(chunks), true);
            // Revalidate merged identities/orphans and immutable markdown before touching projections.
            Corpus merged = corpus(root, kb, generation, new Budget(), true);
            Path stagedSummary = generation.resolve("crawl-result.json");
            JsonNode extractedSummary = exists(stagedSummary)
                    ? parse(read(root, stagedSummary, MAX_FILE, budget)) : JSON.createObjectNode();
            require(extractedSummary.isObject(), "Staged crawl summary must be an object");
            ObjectNode summary = ((ObjectNode) extractedSummary).deepCopy();
            summary.put("profileId", kb); summary.put("projectRoot", root.toString());
            summary.put("knowledgeBaseId", kb); summary.put("jobId", context.jobId());
            summary.put("generationId", generationId);
            summary.set("sources", JSON.createArrayNode().add(text(effective.path("documents").get(0), "path")));
            summary.set("includePatterns", effective.path("documents").get(0).path("includePatterns").deepCopy());
            summary.set("sourceConfigurations", effective.path("documents").deepCopy());
            summary.put("markdownPath", rel(root, fixedMarkdown));
            summary.put("analysisPath", rel(root, output.resolve("analysis.json")));
            summary.put("status", "COMPLETED"); summary.put("scope", "LEXICAL_ONLY");
            summary.put("corpusUpdate", UPDATE); summary.put("documentCount", merged.documents.size());
            summary.put("chunkCount", merged.chunks.size()); summary.put("markdownCount", merged.markdownFiles.size());
            summary.put("finishedAt", Instant.now().toString());
            write(root, generation.resolve("crawl-result.json"), jsonBytes(summary), true);
            ObjectNode analysis = summary.deepCopy();
            analysis.put("analysisScope", "CORPUS_COUNTS_ONLY_NO_REPROCESSING");
            write(root, generation.resolve("analysis.json"), jsonBytes(analysis), true);
            Map<Path, Path> projections = new LinkedHashMap<>();
            for (String name : PROJECTIONS) projections.put(output.resolve(name), generation.resolve(name));
            for (Row row : merged.documents) {
                String id = text(row.json, "documentId");
                Path md = merged.markdownFiles.get(id);
                if (md != null) projections.put(fixedMarkdown.resolve(id + ".md"), md);
            }
            // Old selected artifacts not represented by the replacement must be removed transactionally.
            for (Row row : baseline.documents) if (selected.contains(text(row.json, "source"))) {
                Path old = fixedMarkdown.resolve(text(row.json, "documentId") + ".md");
                if (!projections.containsKey(old) && exists(old)) projections.put(old, null);
            }
            ObjectNode receipt = receipt(root, kb, generationId, context, effective, baselineGeneration,
                    before, selected, merged, retainedCount, projections);
            Path receiptPath = generation.resolve("corpus-receipt.json");
            require(jsonBytes(receipt).length <= MAX_ROW, "Receipt exceeds bounded reader limit");
            write(root, receiptPath, jsonBytes(receipt), false);
            // Extractors close/rename markdown, but need not fsync its bytes. Flush every immutable
            // artifact before the marker can make this generation visible to readers.
            for (Path artifact : projections.values()) if (artifact != null) syncFile(root, artifact);
            syncDirectory(generation.resolve("markdown")); syncDirectory(generation);
            ObjectNode marker = JSON.createObjectNode();
            marker.put("schema", POINTER); marker.put("generationId", generationId); marker.put("jobId", context.jobId());
            marker.put("receiptPath", rel(root, receiptPath)); marker.put("receiptSha256", hash(jsonBytes(receipt)));
            try {
                return context.fence().commit(() -> {
                    checkCancelled();
                    verifyPins(root, pins);
                    for (var entry : before.entrySet()) {
                        require(hash(read(root, anchored(root, entry.getKey()), MAX_FILE, new Budget())).equals(entry.getValue()),
                                "Baseline changed while extracting");
                    }
                    commit(root, kb, generationId, projections, marker, baselineDirectory, baseline.markdownFiles, pins);
                    return receipt.deepCopy();
                });
            } catch (IOException e) { throw e; }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled before corpus commit");
            } catch (Exception e) { throw new IOException("Corpus commit fence rejected publication", e); }
        }
    }

    private static ObjectNode receipt(Path root, String kb, String generationId, Context context, JsonNode effective,
                                      String baselineGeneration, Map<String, String> before, Set<String> selected,
                                      Corpus merged, int retainedCount, Map<Path, Path> projections) throws IOException {
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schema", RECEIPT); receipt.put("corpusUpdate", UPDATE);
        receipt.put("status", "CORPUS_COMMITTED"); receipt.put("scope", "LEXICAL_ONLY");
        receipt.put("jobId", context.jobId()); receipt.put("projectRoot", root.toString());
        receipt.put("knowledgeBaseId", kb); receipt.put("generationId", generationId);
        receipt.put("committedAt", Instant.now().toString());
        receipt.set("submittedRequest", context.submittedRequest()); receipt.set("effectiveRequest", effective.deepCopy());
        receipt.put("submittedRequestSha256", requestDigest(context.submittedRequest()));
        receipt.put("effectiveRequestSha256", requestDigest(effective));
        ObjectNode baseline = receipt.putObject("baseline");
        if (baselineGeneration == null) baseline.putNull("generationId"); else baseline.put("generationId", baselineGeneration);
        ObjectNode hashes = baseline.putObject("artifactHashes"); before.forEach(hashes::put);
        ArrayNode sources = receipt.putArray("selectedSources"); selected.forEach(sources::add);
        ObjectNode artifacts = receipt.putObject("artifacts");
        Budget budget = new Budget();
        for (Map.Entry<Path, Path> projection : projections.entrySet()) {
            if (projection.getValue() == null) continue;
            String sha = hash(read(root, projection.getValue(), MAX_FILE, budget));
            artifacts.put(rel(root, projection.getKey()), sha);
            artifacts.put(rel(root, projection.getValue()), sha);
        }
        ObjectNode counts = receipt.putObject("counts");
        counts.put("documentCount", merged.documents.size()); counts.put("chunkCount", merged.chunks.size());
        counts.put("selectedDocumentCount", merged.documents.size() - retainedCount);
        counts.put("retainedDocumentCount", retainedCount);
        counts.put("markdownCount", merged.markdownFiles.size());
        return receipt;
    }

    /** Lock-free reader: never silently falls back from a corrupt pointer/receipt/generation. */
    public static Path resolveReadDirectory(Path projectRoot, String kb) throws IOException {
        Path root = root(projectRoot);
        Path output = output(root, kb);
        Path markerPath = output.resolve(MARKER);
        if (!exists(markerPath)) {
            Path journalPath = output.resolve(JOURNAL_FILE);
            if (exists(journalPath)) {
                JsonNode journal = journal(root, kb);
                Path before = anchored(root, text(journal, "beforeDirectory"));
                directory(root, before);
                // First-publication crash with no prior marker: read the frozen legacy backup,
                // never partially replaced compatibility projections.
                Budget budget = new Budget();
                for (JsonNode target : journal.path("targets")) {
                    if (target.path("backup").isTextual()) {
                        Path backup = anchored(root, text(target, "backup"));
                        require(hash(read(root, backup, MAX_FILE, budget)).equals(text(target, "sha256")),
                                "Recovery backup integrity failure");
                    }
                }
                for (var it = journal.path("beforeHashes").fields(); it.hasNext();) {
                    var entry = it.next();
                    require(hash(read(root, before.resolve(entry.getKey()), MAX_FILE, budget)).equals(entry.getValue().textValue()),
                            "Frozen pre-publication snapshot integrity failure");
                }
                return before;
            }
            if (exists(output)) directory(root, output);
            return output;
        }
        Budget budget = new Budget();
        JsonNode marker = parse(read(root, markerPath, MAX_ROW, budget));
        require(POINTER.equals(text(marker, "schema")), "Invalid corpus pointer schema");
        String generationId = text(marker, "generationId");
        require(generationId.matches("gen-[0-9a-f-]{36}"), "Invalid corpus generationId");
        Path generation = output.resolve("corpus-generations").resolve(generationId);
        Path receiptPath = generation.resolve("corpus-receipt.json");
        require(rel(root, receiptPath).equals(text(marker, "receiptPath")), "Pointer receipt path mismatch");
        byte[] receiptBytes = read(root, receiptPath, MAX_ROW, budget);
        require(hash(receiptBytes).equals(text(marker, "receiptSha256")), "Corpus receipt hash mismatch");
        JsonNode receipt = parse(receiptBytes);
        require(RECEIPT.equals(text(receipt, "schema")) && UPDATE.equals(text(receipt, "corpusUpdate"))
                && "CORPUS_COMMITTED".equals(text(receipt, "status")) && "LEXICAL_ONLY".equals(text(receipt, "scope"))
                && kb.equals(text(receipt, "knowledgeBaseId")) && root.toString().equals(text(receipt, "projectRoot"))
                && generationId.equals(text(receipt, "generationId"))
                && text(marker, "jobId").equals(text(receipt, "jobId")), "Corpus receipt/pointer identity mismatch");
        try {
            validateRequest(receipt.path("submittedRequest"));
            validateRequest(receipt.path("effectiveRequest"));
            require(normalizedRequest(receipt.path("submittedRequest")).equals(normalizedRequest(receipt.path("effectiveRequest"))),
                    "Receipt submitted/effective request mismatch");
            require(requestDigest(receipt.path("submittedRequest")).equals(text(receipt, "submittedRequestSha256"))
                    && requestDigest(receipt.path("effectiveRequest")).equals(text(receipt, "effectiveRequestSha256")),
                    "Receipt request digest mismatch");
        } catch (IllegalArgumentException malformed) { throw new IOException("Malformed receipt request snapshot", malformed); }
        JsonNode hashes = receipt.path("artifacts");
        require(hashes.isObject() && hashes.size() <= MAX_ROWS * 2 + 8, "Invalid receipt artifact map");
        Set<Path> immutableFiles = new HashSet<>();
        for (var it = hashes.fields(); it.hasNext();) {
            var entry = it.next();
            Path path = anchored(root, entry.getKey());
            require(entry.getValue().isTextual() && entry.getValue().textValue().matches("[0-9a-f]{64}"), "Invalid artifact digest");
            boolean immutable = path.startsWith(generation);
            boolean fixed = PROJECTIONS.stream().anyMatch(name -> path.equals(output.resolve(name)))
                    || path.getParent().equals(markdown(root, kb));
            require(immutable || fixed, "Unexpected receipt artifact path");
            if (immutable) {
                require(!path.equals(receiptPath) && !path.equals(generation.resolve(MARKER)), "Self-referential artifact hash");
                require(hash(read(root, path, MAX_FILE, budget)).equals(entry.getValue().textValue()),
                        "Immutable corpus artifact hash mismatch: " + entry.getKey());
                immutableFiles.add(path);
            }
        }
        for (String name : PROJECTIONS) require(immutableFiles.contains(generation.resolve(name)), "Missing immutable projection hash");
        directory(root, generation);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(generation)) {
            for (Path path : entries) {
                if (path.getFileName().toString().equals("markdown")) {
                    directory(root, path);
                    try (DirectoryStream<Path> md = Files.newDirectoryStream(path)) {
                        for (Path file : md) require(immutableFiles.contains(file), "Unhashed immutable markdown artifact");
                    }
                } else require(path.equals(receiptPath) || immutableFiles.contains(path), "Unhashed generation artifact");
            }
        }
        return generation;
    }

    private static void commit(Path root, String kb, String generationId, Map<Path, Path> projections,
                               ObjectNode marker, Path baselineDirectory, Map<String, Path> baselineMarkdown,
                               List<Pin> pins) throws IOException {
        Path output = output(root, kb);
        Path journalPath = output.resolve(JOURNAL_FILE);
        Path transaction = output.resolve("corpus-transactions").resolve(generationId);
        Path before = transaction.resolve("before");
        directories(root, before);
        ObjectNode journal = JSON.createObjectNode();
        journal.put("schema", JOURNAL); journal.put("generationId", generationId);
        journal.put("beforeDirectory", rel(root, before));
        ArrayNode targets = journal.putArray("targets");
        List<Path> paths = new ArrayList<>(projections.keySet());
        paths.add(output.resolve(MARKER));
        Budget budget = new Budget();
        int index = 0;
        for (Path target : paths) {
            ObjectNode entry = targets.addObject(); entry.put("path", rel(root, target));
            if (exists(target)) {
                byte[] bytes = read(root, target, MAX_FILE, budget);
                Path backup = transaction.resolve("backup-" + index);
                write(root, backup, bytes, false);
                entry.put("backup", rel(root, backup)); entry.put("sha256", hash(bytes));
            } else entry.putNull("backup");
            index++;
        }
        ObjectNode beforeHashes = journal.putObject("beforeHashes");
        for (String name : PROJECTIONS) {
            Path source = baselineDirectory.resolve(name);
            if (exists(source)) {
                byte[] bytes = read(root, source, MAX_FILE, new Budget());
                write(root, before.resolve(name), bytes, false); beforeHashes.put(name, hash(bytes));
            }
        }
        directories(root, before.resolve("markdown"));
        for (var entry : baselineMarkdown.entrySet()) {
            byte[] bytes = read(root, entry.getValue(), MAX_FILE, new Budget());
            String name = "markdown/" + entry.getKey() + ".md";
            write(root, before.resolve(name), bytes, false); beforeHashes.put(name, hash(bytes));
        }
        syncDirectory(before.resolve("markdown")); syncDirectory(before); syncDirectory(transaction);
        require(jsonBytes(journal).length <= MAX_ROW, "Journal exceeds bounded recovery limit");
        atomicWrite(root, journalPath, jsonBytes(journal));
        try {
            for (var entry : projections.entrySet()) {
                if (entry.getValue() == null) {
                    regular(root, entry.getKey()); Files.delete(entry.getKey()); syncDirectory(entry.getKey().getParent());
                } else {
                    byte[] bytes = read(root, entry.getValue(), MAX_FILE, new Budget());
                    // Retained markdown remains byte-identical without even changing its inode/mtime.
                    if (!exists(entry.getKey()) || !Arrays.equals(bytes, read(root, entry.getKey(), MAX_FILE, new Budget()))) {
                        atomicWrite(root, entry.getKey(), bytes);
                    }
                }
            }
            // Recheck source pins at the end of the I/O window, before the LAST rename.
            verifyPins(root, pins);
            // The fence remains held across every compatibility projection and this LAST rename.
            atomicWrite(root, output.resolve(MARKER), jsonBytes(marker));
        } catch (IOException | RuntimeException failure) {
            boolean interrupted = Thread.interrupted();
            try { rollback(root, kb, journal); } catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw failure;
        }
        // Committed marker is authoritative even if journal cleanup fails. Next locked writer finishes it.
        try { Files.delete(journalPath); syncDirectory(output); } catch (IOException ignored) { }
    }

    private static JsonNode journal(Path root, String kb) throws IOException {
        Path output = output(root, kb);
        JsonNode journal = parse(read(root, output.resolve(JOURNAL_FILE), MAX_ROW, new Budget()));
        String generation = text(journal, "generationId");
        require(JOURNAL.equals(text(journal, "schema")) && generation.matches("gen-[0-9a-f-]{36}"), "Invalid publication journal");
        Path transaction = output.resolve("corpus-transactions").resolve(generation);
        require(rel(root, transaction.resolve("before")).equals(text(journal, "beforeDirectory")), "Unsafe recovery directory");
        require(journal.path("targets").isArray() && journal.path("targets").size() <= MAX_ROWS + 5, "Invalid journal targets");
        require(journal.path("beforeHashes").isObject(), "Invalid frozen snapshot hashes");
        for (var it = journal.path("beforeHashes").fields(); it.hasNext();) {
            var entry = it.next();
            require((PROJECTIONS.contains(entry.getKey()) || entry.getKey().matches("markdown/[a-zA-Z0-9._-]{1,256}\\.md"))
                    && entry.getValue().isTextual() && entry.getValue().textValue().matches("[0-9a-f]{64}"),
                    "Unsafe frozen snapshot hash");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : journal.path("targets")) {
            Path target = anchored(root, text(entry, "path"));
            require(seen.add(target.toString()) && (target.getParent().equals(markdown(root, kb))
                    && target.getFileName().toString().matches("[a-zA-Z0-9._-]{1,256}\\.md")
                    || target.equals(output.resolve(MARKER))
                    || PROJECTIONS.stream().anyMatch(name -> target.equals(output.resolve(name)))), "Unsafe recovery target");
            if (!entry.path("backup").isNull()) {
                Path backup = anchored(root, text(entry, "backup"));
                require(backup.getParent().equals(transaction) && backup.getFileName().toString().matches("backup-[0-9]+")
                        && text(entry, "sha256").matches("[0-9a-f]{64}"), "Unsafe recovery backup");
            }
        }
        for (String name : PROJECTIONS) require(seen.contains(output.resolve(name).toString()), "Journal missing projection target");
        require(seen.contains(output.resolve(MARKER).toString()), "Journal missing marker target");
        return journal;
    }

    private static void recover(Path root, String kb) throws IOException {
        Path output = output(root, kb);
        if (!exists(output.resolve(JOURNAL_FILE))) return;
        JsonNode journal = journal(root, kb);
        Path marker = output.resolve(MARKER);
        if (exists(marker)) {
            JsonNode pointer = parse(read(root, marker, MAX_ROW, new Budget()));
            if (text(journal, "generationId").equals(text(pointer, "generationId"))) {
                resolveReadDirectory(root, kb); // Never bless a damaged committed generation.
                Files.delete(output.resolve(JOURNAL_FILE)); syncDirectory(output);
                return;
            }
        }
        rollback(root, kb, journal);
    }

    private static void rollback(Path root, String kb, JsonNode journal) throws IOException {
        // Validate EVERY backup before restoring any target. Leave journal on any failure for retry.
        Map<Path, byte[]> restore = new LinkedHashMap<>();
        Budget budget = new Budget();
        for (JsonNode target : journal.path("targets")) {
            byte[] bytes = null;
            if (target.path("backup").isTextual()) {
                bytes = read(root, anchored(root, text(target, "backup")), MAX_FILE, budget);
                require(hash(bytes).equals(text(target, "sha256")), "Recovery backup integrity failure");
            }
            restore.put(anchored(root, text(target, "path")), bytes);
        }
        for (var entry : restore.entrySet()) {
            if (entry.getValue() == null) {
                if (exists(entry.getKey())) { regular(root, entry.getKey()); Files.delete(entry.getKey()); syncDirectory(entry.getKey().getParent()); }
            } else atomicWrite(root, entry.getKey(), entry.getValue());
        }
        Files.delete(output(root, kb).resolve(JOURNAL_FILE)); syncDirectory(output(root, kb));
    }

    private record Row(JsonNode json, byte[] bytes) { }
    private record Corpus(List<Row> documents, List<Row> chunks, Map<String, Path> markdownFiles) { }
    private record Pin(Path path, Object fileKey, long size, String modified, String sha256) { }

    private static Corpus corpus(Path root, String kb, Path directory, Budget budget, boolean generation) throws IOException {
        Path docs = directory.resolve("documents.jsonl"), chunks = directory.resolve("chunks.jsonl");
        require(exists(docs) == exists(chunks), "Incomplete documents/chunks baseline");
        if (!exists(docs)) {
            require(!generation, "Extraction did not produce documents/chunks");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(markdown(root, kb))) {
                require(!entries.iterator().hasNext(), "Orphan markdown without corpus rows");
            }
            return new Corpus(List.of(), List.of(), Map.of());
        }
        List<Row> documents = jsonl(read(root, docs, MAX_FILE, budget));
        List<Row> chunkRows = jsonl(read(root, chunks, MAX_FILE, budget));
        Set<String> ids = new HashSet<>(), sources = new HashSet<>(), basenames = new HashSet<>();
        Map<String, Path> markdown = new LinkedHashMap<>();
        Set<Path> markdownPaths = new HashSet<>();
        for (Row row : documents) {
            String id = text(row.json, "documentId"), source = text(row.json, "source");
            require(id.matches("[a-zA-Z0-9._-]{1,256}") && !id.equals(".") && !id.equals("..") && ids.add(id),
                    "Invalid/duplicate documentId");
            Path sourcePath = absolute(source);
            require(sourcePath.getFileName() != null && source.equals(sourcePath.toString()) && sources.add(source),
                    "Invalid/duplicate source ID");
            require(basenames.add(basename(sourcePath)), "Normalized source basename collision");
            require(row.json.path("relativePath").isTextual(), "Document missing relativePath");
            relative(text(row.json, "relativePath"));
            JsonNode md = row.json.path("markdownPath");
            if (!md.isNull() && !md.isMissingNode()) {
                String fixed = rel(root, markdown(root, kb).resolve(id + ".md"));
                require(md.isTextual() && fixed.equals(md.textValue()), "Unsafe/noncanonical markdownPath");
                // Both committed generations and frozen recovery snapshots own their MD copies.
                Path path = !directory.equals(output(root, kb))
                        ? directory.resolve("markdown").resolve(id + ".md") : anchored(root, fixed);
                require(markdownPaths.add(path), "Duplicate markdown artifact");
                read(root, path, MAX_FILE, budget);
                markdown.put(id, path);
            } else require(!"EXTRACTED".equals(text(row.json, "extractionStatus")), "Extracted document missing markdown");
        }
        Set<String> chunkIds = new HashSet<>();
        for (Row row : chunkRows) {
            String id = text(row.json, "chunkId"), documentId = text(row.json, "documentId");
            require(!id.isBlank() && id.length() <= 512 && chunkIds.add(id), "Invalid/duplicate chunkId");
            require(ids.contains(documentId), "Orphan chunk");
            require(row.json.path("text").isTextual() && !row.json.path("text").textValue().isBlank(), "Empty/invalid chunk text");
        }
        Path mdDirectory = !directory.equals(output(root, kb))
                ? directory.resolve("markdown") : markdown(root, kb);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(mdDirectory)) {
            for (Path path : entries) require(markdownPaths.contains(path), "Orphan/unexpected markdown artifact");
        }
        return new Corpus(documents, chunkRows, markdown);
    }

    private static List<Row> jsonl(byte[] bytes) throws IOException {
        List<Row> rows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i != bytes.length && bytes[i] != '\n') continue;
            if (i == bytes.length && start == i) break;
            require(i - start > 0 && i - start <= MAX_ROW && rows.size() < MAX_ROWS, "Empty/oversized/too many JSONL rows");
            int end = i < bytes.length ? i + 1 : i;
            byte[] raw = Arrays.copyOfRange(bytes, start, end);
            JsonNode json = parse(Arrays.copyOfRange(bytes, start, i));
            require(json.isObject(), "JSONL row must be object");
            rows.add(new Row(json, raw)); start = end;
        }
        return rows;
    }

    private static byte[] rows(List<Row> rows) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 0; i < rows.size(); i++) {
            byte[] bytes = rows.get(i).bytes;
            // Do not silently alter an unrelated unterminated row to append another document.
            require(i == rows.size() - 1 || bytes[bytes.length - 1] == '\n', "Cannot append after unterminated retained JSONL row");
            require((long) result.size() + bytes.length <= MAX_FILE, "Merged JSONL too large");
            result.write(bytes);
        }
        return result.toByteArray();
    }

    private static Map<String, String> baselineHashes(Path root, String kb, Corpus corpus, Budget budget) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : PROJECTIONS) {
            Path path = output(root, kb).resolve(name);
            if (exists(path)) hashes.put(rel(root, path), hash(read(root, path, MAX_FILE, budget)));
        }
        for (Row row : corpus.documents) {
            Path path = markdown(root, kb).resolve(text(row.json, "documentId") + ".md");
            if (exists(path)) hashes.put(rel(root, path), hash(read(root, path, MAX_FILE, budget)));
        }
        return hashes;
    }

    private static void verifyProjections(Path root, String kb, Path generation, Corpus baseline) throws IOException {
        Budget budget = new Budget();
        for (String name : PROJECTIONS) {
            require(Arrays.equals(read(root, output(root, kb).resolve(name), MAX_FILE, budget),
                    read(root, generation.resolve(name), MAX_FILE, budget)), "Fixed corpus projection diverged from marker");
        }
        Set<Path> expectedMarkdown = new HashSet<>();
        for (var entry : baseline.markdownFiles.entrySet()) {
            Path path = markdown(root, kb).resolve(entry.getKey() + ".md"); expectedMarkdown.add(path);
            require(Arrays.equals(read(root, path, MAX_FILE, budget), read(root, entry.getValue(), MAX_FILE, budget)),
                    "Fixed markdown projection diverged from marker");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(markdown(root, kb))) {
            for (Path path : entries) require(expectedMarkdown.contains(path), "Orphan fixed markdown projection");
        }
    }

    private static List<Pin> pins(Path root, JsonNode request, Budget budget) throws IOException {
        JsonNode document = request.path("documents").get(0);
        Path directory = absolute(text(document, "path"));
        root(directory);
        require(directory.equals(directory.toRealPath()), "Source directory is not canonical realpath");
        List<Pin> pins = new ArrayList<>(); Set<String> basenames = new HashSet<>();
        Set<Object> fileKeys = new HashSet<>();
        for (JsonNode name : document.path("includePatterns")) {
            Path path = directory.resolve(relative(name.textValue()));
            require(path.startsWith(directory), "Source escapes selection");
            // Never read our own output as a selected source.
            require(!path.startsWith(root.resolve("data/crawls")) && !path.startsWith(root.resolve("data/markdown")),
                    "Corpus output cannot be a selected source");
            regular(directory.getRoot(), path);
            require(path.equals(path.toRealPath()) && basenames.add(basename(path)), "Source alias/basename collision");
            BasicFileAttributes attributes = attributes(path);
            require(fileKeys.add(attributes.fileKey()), "Selected sources alias the same inode");
            require(attributes.size() <= 8L * 1024 * 1024, "Selected source too large");
            byte[] bytes = read(directory.getRoot(), path, 8L * 1024 * 1024, budget);
            String text = utf8(bytes);
            require(!text.isBlank() && text.indexOf('\0') < 0, "Empty/binary selected source");
            pins.add(new Pin(path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toString(), hash(bytes)));
        }
        return pins;
    }

    private static void verifyPins(Path root, List<Pin> pins) throws IOException {
        Budget budget = new Budget();
        for (Pin pin : pins) {
            regular(pin.path.getRoot(), pin.path);
            BasicFileAttributes attributes = attributes(pin.path);
            require(pin.path.equals(pin.path.toRealPath()) && pin.fileKey.equals(attributes.fileKey())
                    && pin.size == attributes.size() && pin.modified.equals(attributes.lastModifiedTime().toString())
                    && pin.sha256.equals(hash(read(pin.path.getRoot(), pin.path, 8L * 1024 * 1024, budget))),
                    "Selected source changed while extracting: " + pin.path);
        }
    }

    private static void collisionCheck(List<Row> baseline, Set<String> selected, List<Pin> pins) throws IOException {
        Set<String> basenames = new HashSet<>();
        for (Row row : baseline) if (!selected.contains(text(row.json, "source"))) {
            basenames.add(basename(absolute(text(row.json, "source"))));
        }
        for (Pin pin : pins) require(basenames.add(basename(pin.path)), "Selection collides with retained source basename");
    }

    private static String basename(Path path) {
        return ProjectCrawlCommand.localArtifactId(Normalizer.normalize(path.getFileName().toString(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT));
    }

    private static JsonNode parse(byte[] bytes) throws IOException {
        String input = utf8(bytes);
        try (JsonParser parser = JSON.createParser(input)) {
            int depth = 0;
            for (JsonToken token; (token = parser.nextToken()) != null;) {
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) require(++depth <= 64, "JSON depth exceeds 64");
                else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--;
            }
        }
        JsonNode node = JSON.readTree(input);
        require(node != null, "Missing JSON value"); return node;
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
    }

    private static byte[] jsonBytes(JsonNode node) {
        try { return JSON.writeValueAsBytes(node); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
    }

    private static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static Path root(Path path) throws IOException {
        require(path != null && path.isAbsolute(), "Absolute projectRoot required");
        Path root = path.normalize(); directory(root.getRoot(), root);
        require(root.equals(root.toRealPath()), "Project root must be a canonical nonsymlink realpath");
        require("file".equals(root.getFileSystem().provider().getScheme())
                && Files.getFileStore(root).supportsFileAttributeView("posix"), "Unsafe/non-POSIX filesystem");
        String type = Files.getFileStore(root).type().toLowerCase(Locale.ROOT);
        require(Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "tmpfs", "overlay", "apfs").contains(type),
                "Unsupported local filesystem: " + type);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            require(stream instanceof SecureDirectoryStream<?>, "Secure directory operations unsupported");
        }
        return root;
    }

    private static Path output(Path root, String kb) throws IOException {
        require(kb != null && kb.matches("[a-zA-Z0-9._-]{1,128}") && !kb.equals(".") && !kb.equals(".."), "Unsafe KB ID");
        return root.resolve("data/crawls").resolve(kb);
    }
    private static Path markdown(Path root, String kb) throws IOException {
        output(root, kb); return root.resolve("data/markdown").resolve(kb);
    }
    private static Path absolute(String value) throws IOException {
        try {
            Path path = Path.of(value); require(path.isAbsolute() && path.equals(path.normalize()), "Noncanonical absolute path");
            return path;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid absolute path", e); }
    }
    private static Path relative(String value) throws IOException {
        try {
            Path path = Path.of(value);
            require(!value.isBlank() && value.length() <= 4096 && !value.contains("\\") && !path.isAbsolute()
                    && path.equals(path.normalize()) && !path.startsWith("..") && !path.toString().equals(".")
                    && value.equals(path.toString().replace('\\', '/')), "Unsafe/noncanonical relative path");
            return path;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid relative path", e); }
    }
    private static Path anchored(Path root, String value) throws IOException {
        Path path = root.resolve(relative(value)); require(path.startsWith(root), "Path escapes root"); return path;
    }
    private static String rel(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(!attributes.isSymbolicLink() && attributes.fileKey() != null, "Unsafe symlink/unidentifiable inode: " + path);
        return attributes;
    }
    private static void directory(Path anchor, Path path) throws IOException { checked(anchor, path, true); }
    private static void regular(Path anchor, Path path) throws IOException { checked(anchor, path, false); }
    private static void checked(Path anchor, Path path, boolean directory) throws IOException {
        require(path.isAbsolute() && path.normalize().equals(path) && path.startsWith(anchor), "Unanchored path");
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            BasicFileAttributes attrs = attributes(cursor);
            boolean last = cursor.equals(path);
            require((!last || directory) ? attrs.isDirectory() : attrs.isRegularFile(), "Unsafe path type: " + cursor);
        }
    }
    private static void directories(Path root, Path path) throws IOException {
        require(path.startsWith(root), "Directory escapes root"); directory(root.getRoot(), root);
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (!exists(cursor)) {
                try { Files.createDirectory(cursor); }
                catch (java.nio.file.FileAlreadyExistsException racedCreator) { directory(root, cursor); }
                syncDirectory(cursor.getParent());
            }
            directory(root, cursor);
            require(Files.getFileStore(cursor).equals(Files.getFileStore(root)), "Corpus directories cross filesystem boundary");
        }
    }
    private static final class Budget {
        long bytes;
        void add(long count) throws IOException { bytes += count; require(bytes <= MAX_TOTAL, "Corpus byte budget exceeded"); }
    }
    private static byte[] read(Path root, Path path, long limit, Budget budget) throws IOException {
        regular(root, path); BasicFileAttributes before = attributes(path);
        require(before.size() <= limit && before.size() <= Integer.MAX_VALUE, "Oversized corpus file: " + path);
        budget.add(before.size());
        byte[] result = new byte[(int) before.size()];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(result);
            while (buffer.hasRemaining()) require(channel.read(buffer) >= 0, "File truncated while reading");
            require(channel.read(ByteBuffer.allocate(1)) == -1, "File grew while reading");
        }
        BasicFileAttributes after = attributes(path);
        require(before.fileKey().equals(after.fileKey()) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()), "Corpus file changed while reading");
        return result;
    }
    private static void write(Path root, Path path, byte[] bytes, boolean replaceStaging) throws IOException {
        require(bytes.length <= MAX_FILE, "Oversized publication file"); directory(root, path.getParent());
        if (exists(path)) { require(replaceStaging, "Immutable file already exists"); regular(root, path); }
        try (FileChannel channel = replaceStaging
                ? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private static void atomicWrite(Path root, Path target, byte[] bytes) throws IOException {
        directory(root, target.getParent()); if (exists(target)) regular(root, target);
        Path temp = target.resolveSibling(".corpus-" + UUID.randomUUID() + ".tmp");
        write(root, temp, bytes, false);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(target.getParent());
        } finally { if (exists(temp)) Files.delete(temp); }
    }
    private static void cleanTemporaryFiles(Path root, Path directory) throws IOException {
        directory(root, directory);
        boolean changed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (path.getFileName().toString().matches("\\.corpus-[0-9a-f-]{36}\\.tmp")) {
                    regular(root, path); Files.delete(path); changed = true;
                }
            }
        }
        if (changed) syncDirectory(directory);
    }

    private static void syncFile(Path root, Path path) throws IOException {
        checkCancelled(); regular(root, path); BasicFileAttributes before = attributes(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        regular(root, path); BasicFileAttributes after = attributes(path);
        require(before.fileKey().equals(after.fileKey()) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()), "Corpus file changed while flushing");
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Corpus publication cancelled");
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
