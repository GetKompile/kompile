/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.core.reasoning.ReasoningLearningExecutor;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.learning.FileWeightStore;
import ai.kompile.graph.reasoning.learning.InMemoryWeightStore;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.knowledgegraph.audit.PinGuard;
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.persistence.FileBackedWeightStore;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreGroundingFactory;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreWeightStore;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STAGE_MEBN_LEARNING;
import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STAGE_PROGRAM_BUILD;
import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STAGE_PSL_LEARNING;
import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STAGE_WEIGHT_RELOAD;
import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STATUS_DONE;
import static ai.kompile.knowledgegraph.grounding.GroundingProgressEvent.STATUS_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grounding cascade's learning steps when a {@link ReasoningLearningExecutor} is wired
 * (app-main): PSL and MEBN learning run only in the learning subprocess. A failed run reports
 * STATUS_ERROR and leaves the current weights in place; it is never retried in this JVM, where
 * ND4J must not run. A successful run's weights are read back and carried into the next cascade.
 *
 * <p>A fake executor stands in for the subprocess and writes exactly what
 * {@code LearningSubprocessMain} writes: a {@link FileWeightStore} version keyed by rule display
 * text for PSL, and {@code {"<edgeKey>":strength}} JSON for MEBN. The programs here stay under the
 * scalar-solver threshold, so nothing in this test touches ND4J.</p>
 */
@Timeout(60)
class LearningSubprocessNoInJvmRetryTest {

    private static final long FS_ID = 77L;
    private static final String PROGRAM_KEY = FS_ID + ":cascade";
    private static final String EDGE_KEY = "TestFrag|cause->effect";

    @TempDir
    Path tempDir;

    private String savedUserHome;
    private KbGroundingService groundingService;
    private FileBackedWeightStore fileStore;
    private MebnWeightPersistenceAdapter mebnAdapter;
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final FakeLearningSubprocess subprocess = new FakeLearningSubprocess();
    private MFrag frag;
    private MTheory theory;

    @BeforeEach
    void setUp() {
        // With no data dir configured, the MEBN adapter writes under user.home/.kompile.
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());

        groundingService = new KbGroundingService();
        fileStore = new FileBackedWeightStore(tempDir.resolve("weights").toString());
        mebnAdapter = new MebnWeightPersistenceAdapter();

        EntityType thing = new EntityType("Thing");
        RandomVariable cause = new RandomVariable("cause", List.of(thing), RandomVariable.NodeRole.RESIDENT);
        RandomVariable effect = new RandomVariable("effect", List.of(thing), RandomVariable.NodeRole.RESIDENT);
        frag = new MFrag("TestFrag")
                .addResidentNode(cause)
                .addResidentNode(effect)
                .addParentEdge("cause", "effect", 0.5);
        theory = new MTheory("TestTheory");
        theory.addEntityType(thing);
        theory.addMFrag(frag);
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", savedUserHome);
    }

    @Test
    @DisplayName("A failed PSL subprocess run reports ERROR and trains nothing in this JVM")
    void failedPslRunIsNotRetriedInJvm() throws IOException {
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, null);
        subprocess.pslFailure = "simulated OOM kill";
        observe("effect(x)");

        runCascade(orchestrator);

        assertEquals(1, subprocess.pslCalls.size(), "PSL learning went to the subprocess once");
        assertEquals("PSL learning failed: learning subprocess: simulated OOM kill",
                single(STAGE_PSL_LEARNING, STATUS_ERROR).getMessage());
        assertTrue(progress(STAGE_PSL_LEARNING, STATUS_DONE).isEmpty(), this::describe);
        assertEquals(List.of(), weightFiles(), "no in-JVM retry saved weights");
        assertFalse(modelTrained("psl"));
        // The PSL failure does not stop the MEBN step.
        assertEquals(1, subprocess.mebnCalls.size());
    }

    @Test
    @DisplayName("A failed MEBN subprocess run reports ERROR and leaves the strengths unpersisted")
    void failedMebnRunIsNotRetriedInJvm() {
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, null);
        subprocess.mebnFailure = "simulated native crash";
        observe("effect(x)");

        runCascade(orchestrator);

        assertEquals(1, subprocess.mebnCalls.size(), "MEBN learning went to the subprocess once");
        assertEquals("MEBN step failed: learning subprocess: simulated native crash",
                single(STAGE_MEBN_LEARNING, STATUS_ERROR).getMessage());
        assertTrue(progress(STAGE_MEBN_LEARNING, STATUS_DONE).isEmpty(), this::describe);
        assertFalse(Files.exists(mebnAdapter.mebnArtifactPath(FS_ID)), "no in-JVM retry persisted strengths");
        assertEquals(0.5, frag.getEdgeStrength("cause", "effect"), 0.0);
        assertFalse(modelTrained("mebn"));
        // The MEBN failure does not undo the PSL step.
        assertEquals(Boolean.TRUE, single(STAGE_PSL_LEARNING, STATUS_DONE).getData().get("subprocess"));
    }

    @Test
    @DisplayName("Weights the subprocess learned are applied, reloaded and trained on in the next cascade")
    void learnedWeightsCarryIntoTheNextCascade() {
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, null);
        observe("effect(x)");

        runCascade(orchestrator);

        int rules = rulesCount();
        assertTrue(rules > 0, this::describe);
        assertEquals(1, subprocess.pslCalls.size());
        FakeLearningSubprocess.PslCall pslCall = subprocess.pslCalls.get(0);
        assertEquals(PROGRAM_KEY, pslCall.programKey());
        assertEquals(fileStore.fileWeightStoreFor(String.valueOf(FS_ID)).baseDir().toString(),
                pslCall.weightStoreDirPath());
        assertEquals(rules, pslCall.ruleTexts().size());
        assertAllWeights(0.8, pslCall.ruleTexts());
        Map<String, Object> pslDone = single(STAGE_PSL_LEARNING, STATUS_DONE).getData();
        assertEquals(Boolean.TRUE, pslDone.get("subprocess"));
        assertEquals(1, pslDone.get("savedVersion"));
        assertEquals(rules, pslDone.get("rulesUpdated"), "every rule took the learned weight");
        ModelTrainedEvent pslTrained = trainedEvent("psl");
        assertTrue(Files.isRegularFile(pslTrained.getArtifactPath()),
                "the PSL artifact is the file the subprocess wrote: " + pslTrained.getArtifactPath());

        assertEquals(1, subprocess.mebnCalls.size());
        FakeLearningSubprocess.MebnCall mebnCall = subprocess.mebnCalls.get(0);
        assertEquals(List.of(EDGE_KEY), mebnCall.edgeKeys());
        assertEquals(List.of(0.5), mebnCall.currentStrengths());
        assertEquals(1, mebnCall.pParent().length);
        assertEquals(1, mebnCall.pParent()[0].length);
        assertEquals(1, mebnCall.target().length);
        assertEquals(1, mebnCall.target()[0].length);
        assertEquals(mebnAdapter.mebnArtifactPath(FS_ID).toString(), mebnCall.outputPath());
        Map<String, Object> mebnDone = single(STAGE_MEBN_LEARNING, STATUS_DONE).getData();
        assertEquals(Boolean.TRUE, mebnDone.get("subprocess"));
        assertEquals(1, mebnDone.get("edgesUpdated"));
        assertEquals(0.66, frag.getEdgeStrength("cause", "effect"), 1e-12);
        assertTrue(modelTrained("mebn"));

        subprocess.learnedRuleWeight = 0.52;
        subprocess.learnedEdgeStrength = 0.71;
        runCascade(orchestrator);

        assertEquals(rules, single(STAGE_WEIGHT_RELOAD, STATUS_DONE).getData().get("weightsReloaded"));
        assertEquals(2, subprocess.pslCalls.size());
        assertAllWeights(0.37, subprocess.pslCalls.get(1).ruleTexts());
        pslDone = single(STAGE_PSL_LEARNING, STATUS_DONE).getData();
        assertEquals(2, pslDone.get("savedVersion"));
        assertEquals(rules, pslDone.get("rulesUpdated"));
        assertEquals(2, subprocess.mebnCalls.size());
        assertEquals(0.66, subprocess.mebnCalls.get(1).currentStrengths().get(0), 1e-12);
        assertEquals(0.71, frag.getEdgeStrength("cause", "effect"), 1e-12);
    }

    @Test
    @DisplayName("With no observation on a MEBN edge the subprocess is skipped and the strengths persist unchanged")
    void mebnStepWithNothingToLearnPersistsUnchangedStrengths() throws IOException {
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, null);
        observe("other(x)");

        runCascade(orchestrator);

        assertEquals(0, subprocess.mebnCalls.size());
        assertEquals(Boolean.FALSE, single(STAGE_MEBN_LEARNING, STATUS_DONE).getData().get("subprocess"));
        assertEquals(0.5, frag.getEdgeStrength("cause", "effect"), 0.0);
        assertEquals(Map.of(EDGE_KEY, 0.5), mebnAdapter.readRawStrengths(FS_ID));
    }

    @Test
    @DisplayName("With the dual store wired, the subprocess's weights are saved there and reloaded from it")
    void subprocessWeightsReachTheDualStore() {
        InMemoryDualStoreWeightStore dualWeights = new InMemoryDualStoreWeightStore();
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, dualStoreOver(dualWeights));
        observe("effect(x)");

        runCascade(orchestrator);

        int rules = rulesCount();
        assertEquals(1, dualWeights.latestVersion(PROGRAM_KEY), this::describe);
        Map<String, Double> saved = dualWeights.latest(PROGRAM_KEY).orElseThrow();
        assertEquals(rules, saved.size());
        saved.values().forEach(weight -> assertEquals(0.37, weight, 1e-12));
        assertEquals(1, single(STAGE_PSL_LEARNING, STATUS_DONE).getData().get("savedVersion"));
        assertFalse(modelTrained("psl"), "the dual store is not a file artifact for staging");

        subprocess.learnedRuleWeight = 0.52;
        runCascade(orchestrator);

        assertEquals(rules, single(STAGE_WEIGHT_RELOAD, STATUS_DONE).getData().get("weightsReloaded"));
        assertAllWeights(0.37, subprocess.pslCalls.get(1).ruleTexts());
        assertEquals(2, dualWeights.latestVersion(PROGRAM_KEY));
    }

    @Test
    @DisplayName("A subprocess that reports success but writes no new version is an ERROR, not a stale reload")
    void successWithoutNewWeightsIsAnError() {
        IncrementalReasoningOrchestrator orchestrator = orchestrator(fileStore, null);
        observe("effect(x)");
        runCascade(orchestrator);
        assertEquals(1, single(STAGE_PSL_LEARNING, STATUS_DONE).getData().get("savedVersion"));

        subprocess.pslWritesWeights = false;
        runCascade(orchestrator);

        String message = single(STAGE_PSL_LEARNING, STATUS_ERROR).getMessage();
        assertTrue(message.contains("wrote no new weights"), message);
        assertTrue(progress(STAGE_PSL_LEARNING, STATUS_DONE).isEmpty(), this::describe);
        assertEquals(1, fileStore.fileWeightStoreFor(String.valueOf(FS_ID)).latestVersion(PROGRAM_KEY));
    }

    @Test
    @DisplayName("Without a file store for the subprocess to write into, PSL learning fails closed")
    void missingFileStoreFailsClosed() {
        InMemoryDualStoreWeightStore dualWeights = new InMemoryDualStoreWeightStore();
        IncrementalReasoningOrchestrator orchestrator = orchestrator(null, dualStoreOver(dualWeights));
        observe("effect(x)");

        runCascade(orchestrator);

        String message = single(STAGE_PSL_LEARNING, STATUS_ERROR).getMessage();
        assertTrue(message.contains("no file-backed weight store"), message);
        assertEquals(0, subprocess.pslCalls.size());
        assertEquals(0, dualWeights.latestVersion(PROGRAM_KEY), "no in-JVM learner saved weights");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private IncrementalReasoningOrchestrator orchestrator(FileBackedWeightStore weights,
                                                          DualStoreGroundingFactory dualStore) {
        IncrementalReasoningOrchestrator orchestrator = new IncrementalReasoningOrchestrator(
                groundingService, events::add, null, new PinGuard(), null, weights, mebnAdapter, dualStore);
        orchestrator.reasoningLearningExecutor = subprocess;
        orchestrator.registerMTheory(FS_ID, theory, new MutableReasoningGraph());
        return orchestrator;
    }

    private static DualStoreGroundingFactory dualStoreOver(DualStoreWeightStore weights) {
        return new DualStoreGroundingFactory(null, null, null) {
            @Override
            public DualStoreWeightStore weightStoreFor(Long factSheetId) {
                return weights;
            }
        };
    }

    private void observe(String atomKey) {
        groundingService.assertFact(FS_ID, Fact.observed(atomKey, "s1"));
    }

    private void runCascade(IncrementalReasoningOrchestrator orchestrator) {
        events.clear();
        orchestrator.runFullReground(FS_ID);
    }

    private List<GroundingProgressEvent> progress(String stage, String status) {
        List<GroundingProgressEvent> matches = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof GroundingProgressEvent p
                    && stage.equals(p.getStage()) && status.equals(p.getStatus())) {
                matches.add(p);
            }
        }
        return matches;
    }

    private GroundingProgressEvent single(String stage, String status) {
        List<GroundingProgressEvent> matches = progress(stage, status);
        assertEquals(1, matches.size(), () -> stage + "/" + status + " events in " + describe());
        return matches.get(0);
    }

    private int rulesCount() {
        return (Integer) single(STAGE_PROGRAM_BUILD, STATUS_DONE).getData().get("rulesCount");
    }

    private boolean modelTrained(String modelType) {
        return events.stream().anyMatch(e -> e instanceof ModelTrainedEvent m && modelType.equals(m.getModelType()));
    }

    private ModelTrainedEvent trainedEvent(String modelType) {
        return events.stream()
                .filter(e -> e instanceof ModelTrainedEvent m && modelType.equals(m.getModelType()))
                .map(ModelTrainedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ModelTrainedEvent(" + modelType + ") in " + describe()));
    }

    private List<Path> weightFiles() throws IOException {
        Path dir = fileStore.fileWeightStoreFor(String.valueOf(FS_ID)).baseDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        }
    }

    private static void assertAllWeights(double expected, List<String> ruleTexts) {
        for (String ruleText : ruleTexts) {
            assertEquals(expected, PslRule.parse(ruleText).weight(), 1e-9, ruleText);
        }
    }

    private String describe() {
        return events.stream()
                .filter(GroundingProgressEvent.class::isInstance)
                .map(GroundingProgressEvent.class::cast)
                .map(p -> p.getStage() + "/" + p.getStatus() + ": " + p.getMessage())
                .collect(Collectors.joining("; ", "[", "]"));
    }

    /** The dual store's weight API over an in-memory store, so no JPA repository is needed. */
    static final class InMemoryDualStoreWeightStore extends DualStoreWeightStore {
        private final InMemoryWeightStore delegate = new InMemoryWeightStore();

        InMemoryDualStoreWeightStore() {
            super(null, FS_ID);
        }

        @Override
        public int save(String programId, Map<String, Double> weights) {
            return delegate.save(programId, weights);
        }

        @Override
        public Optional<Map<String, Double>> latest(String programId) {
            return delegate.latest(programId);
        }

        @Override
        public int latestVersion(String programId) {
            return delegate.latestVersion(programId);
        }

        @Override
        public Optional<Map<String, Double>> get(String programId, int version) {
            return delegate.get(programId, version);
        }

        @Override
        public List<Integer> versions(String programId) {
            return delegate.versions(programId);
        }

        @Override
        public Set<String> programIds() {
            return delegate.programIds();
        }
    }

    /**
     * Stands in for the learning subprocess: records each call and, on success, writes what
     * {@code LearningSubprocessMain} writes.
     */
    static final class FakeLearningSubprocess implements ReasoningLearningExecutor {

        record PslCall(String programKey, String weightStoreDirPath, List<String> ruleTexts) {}

        record MebnCall(List<String> edgeKeys, List<Double> currentStrengths,
                        double[][] pParent, double[][] target, String outputPath) {}

        final List<PslCall> pslCalls = new CopyOnWriteArrayList<>();
        final List<MebnCall> mebnCalls = new CopyOnWriteArrayList<>();
        volatile double learnedRuleWeight = 0.37;
        volatile double learnedEdgeStrength = 0.66;
        /** When set, the PSL run fails with this reason. */
        volatile String pslFailure;
        /** When false, the PSL run reports success without writing a weight version. */
        volatile boolean pslWritesWeights = true;
        /** When set, the MEBN run fails with this reason. */
        volatile String mebnFailure;

        @Override
        public LearningResult runPslLearning(String crawlJobId, long factSheetId, List<String> ruleTexts,
                                             Map<String, Double> observedAtoms, List<String> targetAtoms,
                                             Map<String, Double> groundTruthLabels, String programKey,
                                             String weightStoreDirPath, int maxEpochs, double learningRate,
                                             double tolerance, int batchSize, long seed,
                                             double weightPriorStrength, double weightPriorMean,
                                             double[] perRuleMeans, ProgressCallback callback) {
            pslCalls.add(new PslCall(programKey, weightStoreDirPath, List.copyOf(ruleTexts)));
            if (pslFailure != null) {
                return LearningResult.failure(pslFailure);
            }
            if (pslWritesWeights) {
                PslProgram program = new PslProgram();
                for (String ruleText : ruleTexts) {
                    program.addRule(ruleText);
                }
                Map<String, Double> weights = new LinkedHashMap<>();
                for (PslRule rule : program.rules()) {
                    PslRule learned = new PslRule(learnedRuleWeight, rule.hard(), rule.squared(),
                            rule.body(), rule.head(), rule.distinct());
                    weights.put(learned.toString(), learned.weight());
                }
                new FileWeightStore(Path.of(weightStoreDirPath)).save(programKey, weights);
            }
            return LearningResult.success(0.1, 1);
        }

        @Override
        public LearningResult runMebnLearning(String crawlJobId, long factSheetId, List<String> edgeKeys,
                                              List<Double> currentStrengths, double[][] pParentMatrix,
                                              double[][] targetMatrix, String mebnWeightsOutputPath,
                                              int maxEpochs, double learningRate, ProgressCallback callback) {
            mebnCalls.add(new MebnCall(List.copyOf(edgeKeys), List.copyOf(currentStrengths),
                    pParentMatrix, targetMatrix, mebnWeightsOutputPath));
            if (mebnFailure != null) {
                return LearningResult.failure(mebnFailure);
            }
            String json = edgeKeys.stream()
                    .map(key -> "\"" + key + "\":" + String.format(Locale.ROOT, "%.17g", learnedEdgeStrength))
                    .collect(Collectors.joining(",", "{", "}"));
            try {
                Path output = Path.of(mebnWeightsOutputPath);
                Files.createDirectories(output.getParent());
                Files.writeString(output, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return LearningResult.success(0.05, 1);
        }
    }
}
