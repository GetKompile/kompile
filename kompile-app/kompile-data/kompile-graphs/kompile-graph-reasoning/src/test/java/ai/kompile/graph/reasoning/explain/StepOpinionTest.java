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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E3 — Step.opinion / Step.meta new components, copy-with helpers, JSON emission, serialization.
 */
@DisplayName("E3 Step opinion + meta")
class StepOpinionTest {

    private static final Opinion THIN = new Opinion(0.2, 0.1, 0.7, 0.5);    // u=0.7 → uncertain
    private static final Opinion CONTESTED = new Opinion(0.4, 0.4, 0.2, 0.5); // b=0.4, d=0.4 → contested

    // ── Old factories produce null opinion and empty meta ──────────────────────

    @Test
    void factFactory_nullOpinion_emptyMeta() {
        ReasoningTrace.Step s = ReasoningTrace.Step.fact("x", 0.9, "src");
        assertNull(s.opinion(), "old fact() should produce null opinion");
        assertNotNull(s.meta());
        assertTrue(s.meta().isEmpty(), "old fact() should produce empty meta");
    }

    @Test
    void assumptionFactory_nullOpinion_emptyMeta() {
        ReasoningTrace.Step s = ReasoningTrace.Step.assumption("x", 0.9);
        assertNull(s.opinion());
        assertTrue(s.meta().isEmpty());
    }

    @Test
    void derivedFactory_nullOpinion_emptyMeta() {
        ReasoningTrace.Step s = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE, "y", "rule", 0.8, List.of());
        assertNull(s.opinion());
        assertTrue(s.meta().isEmpty());
    }

    @Test
    void derivedVarargs_nullOpinion_emptyMeta() {
        ReasoningTrace.Step s = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE, "y", "rule", 0.8);
        assertNull(s.opinion());
        assertTrue(s.meta().isEmpty());
    }

    // ── New fact overload with opinion ─────────────────────────────────────────

    @Test
    void factWithOpinionOverload_carriesOpinion() {
        ReasoningTrace.Step s = ReasoningTrace.Step.fact("evidence", 0.9, "doc-1", THIN);
        assertSame(THIN, s.opinion());
        assertEquals("evidence", s.conclusion());
        assertEquals("doc-1", s.source());
    }

    // ── New derived overload with opinion + meta ───────────────────────────────

    @Test
    void derivedWithOpinionAndMeta_carriesAll() {
        Map<String, String> meta = Map.of("runId", "r1", "model", "psl");
        ReasoningTrace.Step s = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE, "result", "psl-run", 0.75,
                CONTESTED, meta, List.of());
        assertSame(CONTESTED, s.opinion());
        assertEquals("r1", s.meta().get("runId"));
        assertEquals("psl", s.meta().get("model"));
    }

    // ── withOpinion copy-with ──────────────────────────────────────────────────

    @Test
    void withOpinion_copiesAllFields_replacesOpinion() {
        ReasoningTrace.Step base = ReasoningTrace.Step.fact("x", 0.5, "src");
        ReasoningTrace.Step enriched = ReasoningTrace.Step.withOpinion(base, CONTESTED);

        assertSame(CONTESTED, enriched.opinion());
        assertEquals(base.kind(), enriched.kind());
        assertEquals(base.conclusion(), enriched.conclusion());
        assertEquals(base.operation(), enriched.operation());
        assertEquals(base.confidence(), enriched.confidence());
        assertEquals(base.source(), enriched.source());
        assertEquals(base.premises(), enriched.premises());
        assertEquals(base.meta(), enriched.meta());
    }

    // ── withMeta copy-with ─────────────────────────────────────────────────────

    @Test
    void withMeta_copiesAllFields_replacesMeta() {
        ReasoningTrace.Step base = ReasoningTrace.Step.withOpinion(
                ReasoningTrace.Step.fact("x", 0.5, "src"), THIN);
        Map<String, String> newMeta = Map.of("k", "v");
        ReasoningTrace.Step enriched = ReasoningTrace.Step.withMeta(base, newMeta);

        assertEquals("v", enriched.meta().get("k"));
        assertSame(THIN, enriched.opinion(), "opinion should be preserved by withMeta");
        assertEquals(base.confidence(), enriched.confidence());
    }

    @Test
    void withMeta_null_producesEmptyMeta() {
        ReasoningTrace.Step base = ReasoningTrace.Step.fact("x", 0.5, "src");
        ReasoningTrace.Step enriched = ReasoningTrace.Step.withMeta(base, null);
        assertNotNull(enriched.meta());
        assertTrue(enriched.meta().isEmpty());
    }

    // ── toJson omits opinion/meta when absent ─────────────────────────────────

    @Test
    void toJson_omitsOpinionAndMeta_whenAbsent() {
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.fact("x", 0.8, "src"));
        String json = trace.toJson();
        assertFalse(json.contains("\"opinion\""), "JSON must not contain opinion key when null");
        assertFalse(json.contains("\"meta\""), "JSON must not contain meta key when empty");
    }

    // ── toJson emits opinion and meta when present ─────────────────────────────

    @Test
    void toJson_emitsOpinion_whenPresent() {
        ReasoningTrace.Step step = ReasoningTrace.Step.fact("x", THIN.expectation(), "src", THIN);
        ReasoningTrace trace = ReasoningTrace.of(step);
        String json = trace.toJson();
        assertTrue(json.contains("\"opinion\""), "JSON must contain opinion key");
        assertTrue(json.contains("\"b\":"), "JSON must contain belief field");
        assertTrue(json.contains("\"d\":"), "JSON must contain disbelief field");
        assertTrue(json.contains("\"u\":"), "JSON must contain uncertainty field");
        assertTrue(json.contains("\"a\":"), "JSON must contain baseRate field");
    }

    @Test
    void toJson_emitsMeta_whenPresent_sortedKeys() {
        Map<String, String> meta = Map.of("zKey", "zVal", "aKey", "aVal");
        ReasoningTrace.Step step = ReasoningTrace.Step.withMeta(
                ReasoningTrace.Step.fact("x", 0.8, "src"), meta);
        String json = ReasoningTrace.of(step).toJson();
        assertTrue(json.contains("\"meta\""), "JSON must contain meta key");
        // aKey must appear before zKey (sorted)
        int aPos = json.indexOf("\"aKey\"");
        int zPos = json.indexOf("\"zKey\"");
        assertTrue(aPos >= 0 && zPos >= 0 && aPos < zPos, "meta keys must be sorted in JSON");
    }

    // ── OpinionTree → trace carries LEAF opinion ───────────────────────────────

    @Test
    void opinionTree_leafOpinionCarriedToStep() {
        Opinion leafOp = new Opinion(0.6, 0.2, 0.2, 0.5);
        OpinionTree leaf = OpinionTree.leaf("evidence", leafOp, "cal-1", List.of("ref-a"));
        ReasoningTrace trace = leaf.toReasoningTrace();

        ReasoningTrace.Step step = trace.conclusion();
        assertNotNull(step.opinion(), "LEAF step must carry the leafOpinion");
        assertEquals(leafOp.belief(), step.opinion().belief(), 1e-9);
        assertEquals(leafOp.disbelief(), step.opinion().disbelief(), 1e-9);
    }

    @Test
    void opinionTree_calibrationIdInMeta_evenWhenSourceRefsPresent() {
        Opinion leafOp = new Opinion(0.6, 0.2, 0.2, 0.5);
        // E3 fix: calibrationId must appear in meta even when sourceRefs is non-empty
        OpinionTree leaf = OpinionTree.leaf("evidence", leafOp, "cal-99", List.of("ref-a", "ref-b"));
        ReasoningTrace trace = leaf.toReasoningTrace();

        ReasoningTrace.Step step = trace.conclusion();
        assertNotNull(step.meta(), "meta must not be null");
        assertEquals("cal-99", step.meta().get("calibrationId"),
                "calibrationId must be in meta even when sourceRefs is present");
        // source should contain the sourceRefs join (not calibrationId)
        assertEquals("ref-a,ref-b", step.source());
    }

    @Test
    void opinionTree_internalNode_noLeafOpinionOnStep() {
        Opinion leafOp = new Opinion(0.6, 0.2, 0.2, 0.5);
        OpinionTree tree = OpinionTree.fuseIndependent("fused",
                List.of(OpinionTree.leaf("a", leafOp), OpinionTree.leaf("b", leafOp)));
        ReasoningTrace trace = tree.toReasoningTrace();
        // Root is a FUSION node — it has no leafOpinion
        assertNull(trace.conclusion().opinion(),
                "FUSION (non-LEAF) step must not carry an opinion");
    }

    @Test
    void opinionTree_discountTrust_inMeta() {
        Opinion leafOp = new Opinion(0.6, 0.2, 0.2, 0.5);
        OpinionTree discountTree = OpinionTree.discount("discounted", 0.75, OpinionTree.leaf("a", leafOp));
        ReasoningTrace trace = discountTree.toReasoningTrace();
        ReasoningTrace.Step step = trace.conclusion();
        assertNotNull(step.meta());
        assertEquals("0.75", step.meta().get("discountTrust"),
                "discountTrust must appear in step meta for DISCOUNT nodes");
    }

    // ── Java serialization round-trip ─────────────────────────────────────────

    @Test
    void serializationRoundTrip_withOpinionAndMeta() throws Exception {
        Map<String, String> meta = Map.of("runId", "r1", "q", "is alice at nyc?");
        ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("alice_in_nyc", THIN.expectation(), "graph", THIN);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE, "answer", "psl-run", 0.7,
                CONTESTED, meta, List.of(leaf));
        ReasoningTrace original = ReasoningTrace.of(root);

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        // Deserialize
        ReasoningTrace restored;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            restored = (ReasoningTrace) ois.readObject();
        }

        ReasoningTrace.Step rRoot = restored.conclusion();
        assertNotNull(rRoot.opinion(), "opinion must survive round-trip");
        assertEquals(CONTESTED.belief(), rRoot.opinion().belief(), 1e-9);
        assertEquals(CONTESTED.uncertainty(), rRoot.opinion().uncertainty(), 1e-9);
        assertEquals("r1", rRoot.meta().get("runId"), "meta must survive round-trip");

        ReasoningTrace.Step rLeaf = rRoot.premises().get(0);
        assertNotNull(rLeaf.opinion(), "leaf opinion must survive round-trip");
        assertEquals(THIN.uncertainty(), rLeaf.opinion().uncertainty(), 1e-9);
    }

    // ── New StepKind variants compile and are enumerated ──────────────────────

    @Test
    void newStepKinds_rebuttalAndRevision_exist() {
        ReasoningTrace.Step rebuttal = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.REBUTTAL, "counterEvidence", "defeats-rule1", 0.6, List.of());
        ReasoningTrace.Step revision = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.REVISION, "belief-updated", "belief-reviser", 0.7, List.of());

        assertEquals(ReasoningTrace.StepKind.REBUTTAL, rebuttal.kind());
        assertEquals(ReasoningTrace.StepKind.REVISION, revision.kind());
    }
}
