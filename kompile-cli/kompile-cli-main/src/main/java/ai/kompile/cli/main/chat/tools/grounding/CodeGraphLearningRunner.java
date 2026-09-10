/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.codeindex.CodeGraphReasoningConfig;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphMutationJournal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase 2 bridge: runs the project-local learning lifecycle (KGE + FOL/PSL/MEBN)
 * over a projected code graph so the hybrid reasoner has real signals to fuse
 * over code entities.
 *
 * <p>Mirrors the crawl path's final learning pass but is driven by
 * {@link CodeGraphReasoningConfig} instead of crawl JSON. The learning itself
 * re-execs the installed kompile-app binary with {@code --subprocess=learning}
 * via {@link ProjectLocalLearningSubprocessExecutor}; the learned artifacts
 * (KGE vector layers, PSL weights, MEBN theory JSON, consensus targets) land
 * inside the {@code .kgraph} archive, and the lifecycle stamps the
 * {@code reasoningLearning.*} metadata the ask_graph_* tools read.</p>
 *
 * <p>Heavy and opt-in: gated on {@link CodeGraphReasoningConfig#isEnabled()}
 * and {@code minGraphSize}, serialized per graph before the executor's lock.</p>
 */
public final class CodeGraphLearningRunner {

    private static final int RECEIPT_SCHEMA_VERSION = 1;
    private static final ObjectMapper RECEIPT_MAPPER = JsonUtils.newStandardMapper();
    private static final Map<Path, ReentrantLock> LEARNING_LOCKS = new ConcurrentHashMap<>();

    private final LearningExecutor executor;

    @FunctionalInterface
    interface LearningExecutor {
        ProjectLocalLearningSubprocessExecutor.Result learn(
                UnifiedGraph graph,
                ProjectLocalLearningSubprocessExecutor.Plan plan,
                Path workingDirectory,
                String crawlJobId) throws Exception;
    }

    /** Convenience constructor using the standard JSON mapper. */
    public CodeGraphLearningRunner() {
        this(new ProjectLocalLearningSubprocessExecutor(
                JsonUtils.newStandardMapper()));
    }

    public CodeGraphLearningRunner(ProjectLocalLearningSubprocessExecutor executor) {
        this(Objects.requireNonNull(executor, "executor")::learn);
    }

    CodeGraphLearningRunner(LearningExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Result of one learning run — for tool output and tests. */
    public record LearningSummary(
            String status,          // COMPLETED / DISABLED / SKIPPED_* / SUPERSEDED
            int entities,
            int relations,
            boolean kge,
            boolean folPsl,
            boolean mebn,
            int pslRules,
            int mebnFragments,
            int modelsTrained,
            int consensusRounds) {
        LearningSummary withStatus(String replacement) {
            return new LearningSummary(replacement, entities, relations, kge, folPsl, mebn,
                    pslRules, mebnFragments, modelsTrained, consensusRounds);
        }
    }

    /** Non-fatal result used by indexing entry points where structural indexing must still succeed. */
    public record ConfiguredResult(LearningSummary summary, String error) {
        public String status() {
            return error == null ? summary.status() : "FAILED";
        }
    }

    /**
     * Load the effective project config and run only when it enables the requested trigger.
     * Failures are returned to the caller instead of invalidating a successful structural index.
     */
    public ConfiguredResult runConfigured(Path projectRoot, Path graphPath, String trigger) {
        CodeGraphReasoningConfig config = CodeGraphReasoningConfig.loadEffective(projectRoot);
        if (!config.triggersOn(trigger)) {
            return new ConfiguredResult(
                    new LearningSummary("DISABLED", 0, 0, false, false, false, 0, 0, 0, 0),
                    null);
        }
        try {
            return new ConfiguredResult(
                    runAutomatic(graphPath, config, "local_code_index"), null);
        } catch (InterruptedException cancellation) {
            Thread.currentThread().interrupt();
            String message = cancellation.getMessage();
            return new ConfiguredResult(null,
                    message == null || message.isBlank()
                            ? cancellation.getClass().getSimpleName() : message);
        } catch (Exception failure) {
            String message = failure.getMessage();
            return new ConfiguredResult(null,
                    message == null || message.isBlank()
                            ? failure.getClass().getSimpleName() : message);
        }
    }

    /**
     * Run learning over the graph at {@code graphPath} (mutated in place, then
     * saved atomically). Synchronous; can take minutes per the configured knobs.
     */
    public LearningSummary run(Path graphPath, CodeGraphReasoningConfig config) throws Exception {
        return runInternal(graphPath, config, "code_graph", true);
    }

    /**
     * Automatic/indexing entry point. A valid receipt skips unchanged work without loading the
     * graph or starting the learning child. Manual {@link #run(Path, CodeGraphReasoningConfig)}
     * deliberately bypasses this reuse check.
     */
    public LearningSummary runAutomatic(
            Path graphPath, CodeGraphReasoningConfig config, String provenanceTrigger)
            throws Exception {
        return runInternal(graphPath, config, provenanceTrigger, false);
    }

    private LearningSummary runInternal(
            Path graphPath,
            CodeGraphReasoningConfig config,
            String provenanceTrigger,
            boolean force)
            throws Exception {
        Objects.requireNonNull(graphPath, "graphPath");
        Objects.requireNonNull(config, "config");
        config.normalize();
        checkInterrupted("before learning");
        Path normalizedGraph = graphPath.toAbsolutePath().normalize();
        String configFingerprint = configFingerprint(config);
        if (!force) {
            LearningSummary unchanged = unchangedReceipt(normalizedGraph, configFingerprint);
            checkInterrupted("after receipt check");
            if (unchanged != null) return unchanged;
        }

        ReentrantLock learningLock = LEARNING_LOCKS.computeIfAbsent(
                normalizedGraph, ignored -> new ReentrantLock(true));
        checkInterrupted("before learning lock");
        learningLock.lockInterruptibly();
        try {
            checkInterrupted("after learning lock");
            // Another automatic request may have published while this request waited for the
            // per-graph serial lock. Re-check before materializing or launching the child.
            if (!force) {
                LearningSummary unchanged = unchangedReceipt(normalizedGraph, configFingerprint);
                checkInterrupted("after receipt check");
                if (unchanged != null) return unchanged;
            }

            checkInterrupted("before graph capture");
            CapturedSnapshot captured = LocalProjectGraphBackend.withGraphWriteLock(
                    normalizedGraph,
                    () -> {
                        checkInterrupted("during graph capture");
                        // UnifiedGraph.load overlays the journal. Compact it first while holding
                        // the canonical writer lock so the snapshot has no separately-consumed
                        // mutations to replay after publication.
                        UnifiedGraphMutationJournal.compact(normalizedGraph);
                        checkInterrupted("after journal compaction");
                        Fingerprint baseline = fingerprint(normalizedGraph);
                        UnifiedGraph snapshot = UnifiedGraph.load(normalizedGraph);
                        checkInterrupted("after graph capture");
                        return new CapturedSnapshot(snapshot, baseline);
                    });
            checkInterrupted("after graph capture");

            ProjectLocalLearningSubprocessExecutor.Plan plan =
                    new ProjectLocalLearningSubprocessExecutor.Plan(
                            kgeConfig(config),
                            reasoningConfig(config),
                            "CODE_GRAPH_BUILD");
            LearningSummary skipped = checkGates(captured.graph(), config);
            checkInterrupted("after learning gates");
            if (skipped != null) {
                return publishReceiptIfUnchanged(
                        normalizedGraph, captured.baseline(), configFingerprint, skipped);
            }

            checkInterrupted("before learning work");
            // learn() returns the learned graph (the subprocess round-trips the archive and
            // validates reasoning artifacts when reasoning is enabled). This expensive operation
            // intentionally runs outside the canonical graph writer lock.
            ProjectLocalLearningSubprocessExecutor.Result result =
                    executor.learn(
                            captured.graph(),
                            plan,
                            parentDirectory(normalizedGraph),
                            null);
            checkInterrupted("after learning work");
            UnifiedGraph learned = result.graph();
            LearningSummary completed = new LearningSummary(
                    "COMPLETED",
                    learned.entities().size(),
                    learned.relations().size(),
                    plan.embedding().enabled(),
                    true,
                    plan.reasoning().enabled(),
                    intMeta(learned, "reasoningLearning.pslRules"),
                    intMeta(learned, "reasoningLearning.mebnFragments"),
                    intMeta(learned, "reasoningLearning.modelsTrained"),
                    intMeta(learned, "reasoningLearning.consensusRounds"));

            checkInterrupted("before learning publication");
            return LocalProjectGraphBackend.withGraphWriteLock(
                    normalizedGraph,
                    () -> {
                        checkInterrupted("during learning publication");
                        // Never overwrite a graph that changed while learning. This comparison
                        // includes both the archive and its optional mutation journal.
                        if (!captured.baseline().equals(fingerprint(normalizedGraph))) {
                            invalidateReceiptIfMatches(normalizedGraph, captured.baseline());
                            return superseded(completed);
                        }
                        checkInterrupted("before learning metadata");
                        stampLearningMetadata(learned, provenanceTrigger, plan.embedding().enabled(), plan.reasoning().enabled());
                        checkInterrupted("before graph save");
                        saveAtomic(learned, normalizedGraph);
                        Fingerprint published = fingerprint(normalizedGraph);
                        checkInterrupted("before receipt publication");
                        writeReceipt(normalizedGraph, published, configFingerprint, completed);
                        return completed;
                    });
        } finally {
            learningLock.unlock();
        }
    }

    static Path receiptPath(Path graphPath) {
        Path normalized = graphPath.toAbsolutePath().normalize();
        return normalized.resolveSibling(normalized.getFileName() + ".learning-receipt.json");
    }

    private static Path parentDirectory(Path graphPath) {
        Path parent = graphPath.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    private static LearningSummary unchangedReceipt(Path graphPath, String configFingerprint)
            throws Exception {
        checkInterrupted("before unchanged receipt");
        LearningSummary result = LocalProjectGraphBackend.withGraphWriteLock(
                graphPath, () -> unchangedReceiptUnlocked(graphPath, configFingerprint));
        checkInterrupted("after unchanged receipt");
        return result;
    }

    private static LearningSummary unchangedReceiptUnlocked(
            Path graphPath, String configFingerprint) throws IOException {
        Receipt receipt = readReceipt(graphPath);
        if (receipt == null || !receipt.configFingerprint().equals(configFingerprint)) return null;
        if (!receipt.fingerprint().equals(fingerprint(graphPath))) return null;
        return receipt.summary().withStatus("SKIPPED_UNCHANGED");
    }

    private static LearningSummary publishReceiptIfUnchanged(
            Path graphPath,
            Fingerprint baseline,
            String configFingerprint,
            LearningSummary summary)
            throws Exception {
        checkInterrupted("before receipt publication");
        return LocalProjectGraphBackend.withGraphWriteLock(
                graphPath,
                () -> {
                    checkInterrupted("during receipt publication");
                    if (!baseline.equals(fingerprint(graphPath))) {
                        invalidateReceiptIfMatches(graphPath, baseline);
                        return superseded(summary);
                    }
                    Fingerprint published = fingerprint(graphPath);
                    checkInterrupted("before receipt publication");
                    writeReceipt(graphPath, published, configFingerprint, summary);
                    return summary;
                });
    }

    private static void invalidateReceiptIfMatches(Path graphPath, Fingerprint fingerprint)
            throws IOException {
        Receipt receipt = readReceipt(graphPath);
        if (receipt != null && receipt.fingerprint().equals(fingerprint)) {
            Files.deleteIfExists(receiptPath(graphPath));
        }
    }

    private static void checkInterrupted(String phase) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Code graph learning cancelled " + phase);
        }
    }

    private static LearningSummary superseded(LearningSummary summary) {
        return summary.withStatus("SUPERSEDED");
    }

    private static void stampLearningMetadata(UnifiedGraph learned, String provenanceTrigger,
                                              boolean kgeEnabled, boolean reasoningEnabled) {
        // Provenance: which trigger produced these artifacts. The reasoning lifecycle already
        // wrote reasoningLearning.status/folPsl/mebn/pslRules/mebnFragments; KGE layers and
        // models/kge.json are written by the KGE lifecycle.
        learned.meta("reasoningLearning.phase", "CODE_GRAPH_BUILD");
        learned.meta("reasoningLearning.trigger", provenanceTrigger);
        learned.meta("reasoningLearning.codeGraphStatus", "READY");
        learned.meta("reasoningLearning.codeGraphCompletedAt", Instant.now().toString());
        if (reasoningEnabled) learned.meta("learning.reasoningStale", false);
        if (kgeEnabled) learned.meta("learning.kgeStale", false);
        Map<String, Object> learnedMetadata = new LinkedHashMap<>(learned.meta());
        learnedMetadata.forEach((key, value) -> {
            if (key.startsWith("codeIndexGeneration.")) {
                String codeProjectId = key.substring("codeIndexGeneration.".length());
                if (reasoningEnabled) learned.meta("codeLearningGeneration." + codeProjectId, value);
                if (kgeEnabled) learned.meta("codeKgeGeneration." + codeProjectId, value);
                learned.meta("phase.codeLearning." + codeProjectId, "COMPLETED");
            }
        });
    }

    private static String configFingerprint(CodeGraphReasoningConfig config) throws IOException {
        return sha256((RECEIPT_SCHEMA_VERSION + "\n" + config.toJson())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Fingerprint fingerprint(Path graphPath) throws IOException {
        String archive = sha256File(graphPath, false);
        String journalHash = journalSha256(graphPath);
        return new Fingerprint(archive, journalHash);
    }

    private static String journalSha256(Path graphPath) throws IOException {
        Path journal = UnifiedGraphMutationJournal.pathFor(graphPath);
        if (!Files.isRegularFile(journal)) return null;
        Path lock = journal.resolveSibling("." + journal.getFileName() + ".lock");
        if (lock.getParent() != null) Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(
                lock, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
             FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            return Files.isRegularFile(journal) ? sha256File(journal, true) : null;
        }
    }

    private static String sha256File(Path path, boolean allowEmpty) throws IOException {
        if (!Files.isRegularFile(path)) {
            if (allowEmpty) return null;
            throw new IOException("KGraph archive is missing: " + path);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static Receipt readReceipt(Path graphPath) {
        Path receiptPath = receiptPath(graphPath);
        if (!Files.isRegularFile(receiptPath)) return null;
        try {
            JsonNode node = RECEIPT_MAPPER.readTree(receiptPath.toFile());
            if (node == null || !node.isObject()
                    || !node.has("schemaVersion")
                    || !node.get("schemaVersion").isIntegralNumber()
                    || node.get("schemaVersion").asInt(-1) != RECEIPT_SCHEMA_VERSION) {
                return null;
            }
            String archiveSha256 = requiredText(node, "archiveSha256");
            String configFingerprint = requiredText(node, "configFingerprint");
            String status = requiredText(node, "status");
            if (!("COMPLETED".equals(status) || status.startsWith("SKIPPED_"))) return null;
            JsonNode journal = node.get("journalSha256");
            String journalSha256 = null;
            if (journal != null && !journal.isNull()) {
                if (!journal.isTextual() || journal.asText().isBlank()) {
                    throw new IOException("Invalid learning receipt field: journalSha256");
                }
                journalSha256 = journal.asText();
            }
            return new Receipt(
                    new Fingerprint(archiveSha256, journalSha256),
                    configFingerprint,
                    new LearningSummary(
                            status,
                            requiredInt(node, "entities"),
                            requiredInt(node, "relations"),
                            requiredBoolean(node, "kge"),
                            requiredBoolean(node, "folPsl"),
                            requiredBoolean(node, "mebn"),
                            requiredInt(node, "pslRules"),
                            requiredInt(node, "mebnFragments"),
                            requiredInt(node, "modelsTrained"),
                            requiredInt(node, "consensusRounds")));
        } catch (Exception corrupt) {
            // A missing or corrupt receipt must conservatively trigger a fresh run.
            return null;
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Invalid learning receipt field: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Invalid learning receipt field: " + field);
        }
        return value.asInt();
    }

    private static boolean requiredBoolean(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IOException("Invalid learning receipt field: " + field);
        }
        return value.asBoolean();
    }

    private static void writeReceipt(
            Path graphPath,
            Fingerprint fingerprint,
            String configFingerprint,
            LearningSummary summary)
            throws IOException {
        Path target = receiptPath(graphPath);
        Path parent = parentDirectory(target);
        Files.createDirectories(parent);
        ObjectNode receipt = RECEIPT_MAPPER.createObjectNode();
        receipt.put("schemaVersion", RECEIPT_SCHEMA_VERSION);
        receipt.put("archiveSha256", fingerprint.archiveSha256());
        if (fingerprint.journalSha256() == null) receipt.putNull("journalSha256");
        else receipt.put("journalSha256", fingerprint.journalSha256());
        receipt.put("configFingerprint", configFingerprint);
        receipt.put("status", summary.status());
        receipt.put("entities", summary.entities());
        receipt.put("relations", summary.relations());
        receipt.put("kge", summary.kge());
        receipt.put("folPsl", summary.folPsl());
        receipt.put("mebn", summary.mebn());
        receipt.put("pslRules", summary.pslRules());
        receipt.put("mebnFragments", summary.mebnFragments());
        receipt.put("modelsTrained", summary.modelsTrained());
        receipt.put("consensusRounds", summary.consensusRounds());
        receipt.put("completedAt", Instant.now().toString());
        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            RECEIPT_MAPPER.writeValue(temporary.toFile(), receipt);
            moveAtomic(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Returns a skip summary when gates block learning, or null when it should proceed. */
    private static LearningSummary checkGates(UnifiedGraph graph, CodeGraphReasoningConfig config) {
        int entities = graph.entities().size();
        int relations = graph.relations().size();
        if (entities + relations < config.getMinGraphSize()) {
            return new LearningSummary(
                    "SKIPPED_BELOW_MIN_GRAPH_SIZE",
                    entities, relations, false, false, false, 0, 0, 0, 0);
        }
        if (relations == 0) {
            return new LearningSummary(
                    "SKIPPED_NO_RELATIONS",
                    entities, relations, false, false, false, 0, 0, 0, 0);
        }
        return null;
    }

    private static UnifiedGraphKgeLifecycle.Config kgeConfig(CodeGraphReasoningConfig config) {
        // Mirror TrainingRequest fallbacks: warm-start = epochs, fixed seed.
        return new UnifiedGraphKgeLifecycle.Config(
                config.isKgeTraining(),
                config.getKgeAlgorithm(),
                config.getKgeDim(),
                config.getKgeEpochs(),
                config.getKgeLearningRate(),
                config.getKgeEpochs(),
                1234L);
    }

    private static UnifiedGraphReasoningLifecycle.Config reasoningConfig(
            CodeGraphReasoningConfig config) {
        return new UnifiedGraphReasoningLifecycle.Config(
                true,
                config.getPslSteps(),
                config.getMebnEpochs(),
                config.getConsensusRounds(),
                config.getConsensusWeight(),
                config.getMaxRelationTypes());
    }

    private static int intMeta(UnifiedGraph graph, String key) {
        Object value = graph.meta().get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void saveAtomic(UnifiedGraph graph, Path graphPath) throws IOException {
        Path parent = graphPath.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".kgraph-write-", ".tmp");
        try {
            graph.save(tmp, KGraphCompatibilityPolicy.COMPACT_V3);
            moveAtomic(tmp, graphPath);
            // The captured snapshot already applied any journal. Do not leave the consumed
            // overlay around for the next load to replay stale facts or opinions.
            UnifiedGraphMutationJournal.clear(graphPath);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private record Fingerprint(String archiveSha256, String journalSha256) {}

    private record CapturedSnapshot(UnifiedGraph graph, Fingerprint baseline) {}

    private record Receipt(
            Fingerprint fingerprint,
            String configFingerprint,
            LearningSummary summary) {}
}