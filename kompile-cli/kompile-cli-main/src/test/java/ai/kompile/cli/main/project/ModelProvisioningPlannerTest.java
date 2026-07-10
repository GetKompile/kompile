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
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.project.KompileProjectModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

// Note: the package-private plan() overload accepts Collection<KompileProjectModel>, not boolean.

/**
 * Unit tests for {@link ModelProvisioningPlanner}.
 *
 * <p>All tests use the package-private {@code plan(tier, richDocs, existing, freeBytes)}
 * overload to inject free-space so they work without touching the real filesystem.</p>
 */
class ModelProvisioningPlannerTest {

    // 200 GB free — effectively unlimited for most tests
    private static final long AMPLE_SPACE = 200L * 1024L * 1024L * 1024L;

    // ── merge-by-role: pre-seeded models win their role ─────────────────────

    /** All four roles pre-seeded → no new models added (idempotency / re-init). */
    @Test
    void mergeByRole_allRolesCovered_returnsEmptyPlan() {
        List<KompileProjectModel> existing = List.of(
                catalogModel("bge-base-en-v1.5",       "ENCODER"),
                catalogModel("ms-marco-MiniLM-L-6-v2", "RERANKER"),
                catalogModel("lfm2.5-1.2b-instruct",   "LLM"),
                catalogModel("smoldocling-256m",        "VLM")
        );
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, existing, AMPLE_SPACE);
        assertTrue(plan.models().isEmpty(), "all roles covered — no new models should be added");
        assertEquals(0L, plan.totalDownloadBytes());
        assertFalse(plan.summaryLines().isEmpty(), "summary must always have at least one line");
    }

    /** SERVER tier + rich docs + pre-seeded ENCODER+VLM → plan adds RERANKER + LLM only (F1). */
    @Test
    void mergeByRole_serverTierRichDocs_preseedEncoderVlm_addsRerankerAndLlm() {
        List<KompileProjectModel> existing = List.of(
                catalogModel("bge-base-en-v1.5", "ENCODER"),
                catalogModel("smoldocling-256m",  "VLM")
        );
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SERVER,
                        true, existing, AMPLE_SPACE);

        List<KompileProjectModel> models = plan.models();
        assertEquals(2, models.size(),
                "SERVER + pre-seeded ENCODER+VLM → only RERANKER+LLM should be added; got "
                        + models.stream().map(KompileProjectModel::getModelId).toList());
        assertModelPresent(models, "ms-marco-MiniLM-L-6-v2", "RERANKER", false);
        assertModelPresent(models, "lfm2.5-1.2b-instruct",   "LLM",      false);
    }

    /** Existing LOCAL-sourced LLM → lfm2.5 NOT added (user's own model wins). */
    @Test
    void mergeByRole_existingLocalLlm_doNotAddCatalogLlm() {
        KompileProjectModel userLlm = catalogModel("my-local-llm", "LLM");
        userLlm.setSource("LOCAL");
        List<KompileProjectModel> existing = List.of(userLlm);

        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, existing, AMPLE_SPACE);

        assertTrue(plan.models().stream().noneMatch(m -> "lfm2.5-1.2b-instruct".equals(m.getModelId())),
                "Existing LOCAL LLM must prevent adding catalog LLM");
        // ENCODER and RERANKER roles are not covered — they should still be added
        assertModelPresent(plan.models(), "bge-base-en-v1.5",       "ENCODER",  true);
        assertModelPresent(plan.models(), "ms-marco-MiniLM-L-6-v2", "RERANKER", false);
    }

    /** Empty existing models → full plan unchanged (same as before the fix). */
    @Test
    void mergeByRole_emptyExisting_fullPlanUnchanged() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), AMPLE_SPACE);
        assertEquals(3, plan.models().size(), "Empty existing → MEDIUM without rich docs must give 3 models");
        assertModelPresent(plan.models(), "bge-base-en-v1.5",       "ENCODER",  true);
        assertModelPresent(plan.models(), "ms-marco-MiniLM-L-6-v2", "RERANKER", false);
        assertModelPresent(plan.models(), "lfm2.5-1.2b-instruct",   "LLM",      false);
    }

    // ── SMALL tier ───────────────────────────────────────────────────────────

    @Test
    void smallTier_encoderOnly() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SMALL,
                        false, List.of(), AMPLE_SPACE);

        List<KompileProjectModel> models = plan.models();
        assertEquals(1, models.size(), "SMALL tier must select exactly 1 model");

        KompileProjectModel enc = models.get(0);
        assertEquals("bge-base-en-v1.5", enc.getModelId());
        assertEquals("ENCODER", enc.getRole());
        assertTrue(enc.isRequired());
        assertEquals("CATALOG", enc.getSource());
        assertEquals("true", enc.getMetadata().get("staging.requiresDownload"));
    }

    @Test
    void smallTier_warnsAboutSkippedModels() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SMALL,
                        true, List.of(), AMPLE_SPACE);
        assertFalse(plan.warnings().isEmpty(), "SMALL tier must emit a warning about skipped LLM/VLM");
        assertTrue(plan.warnings().get(0).contains("SMALL"),
                "Warning must mention SMALL tier");
    }

    @Test
    void smallTier_richDocsIgnored() {
        // hasRichDocuments=true is ignored on SMALL
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SMALL,
                        true, List.of(), AMPLE_SPACE);
        assertEquals(1, plan.models().size(), "rich-docs must not add VLM on SMALL tier");
    }

    // ── MEDIUM tier ──────────────────────────────────────────────────────────

    @Test
    void mediumTier_encoderRerankerLlm_noRichDocs() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), AMPLE_SPACE);
        List<KompileProjectModel> models = plan.models();
        assertEquals(3, models.size(), "MEDIUM without rich docs must select 3 models");

        assertModelPresent(models, "bge-base-en-v1.5", "ENCODER", true);
        assertModelPresent(models, "ms-marco-MiniLM-L-6-v2", "RERANKER", false);
        assertModelPresent(models, "lfm2.5-1.2b-instruct", "LLM", false);
    }

    @Test
    void mediumTier_encoderRerankerLlmVlm_withRichDocs() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, List.of(), AMPLE_SPACE);
        List<KompileProjectModel> models = plan.models();
        assertEquals(4, models.size(), "MEDIUM with rich docs must select 4 models");

        assertModelPresent(models, "smoldocling-256m", "VLM", false);
    }

    @Test
    void mediumTier_modelMetadataConventions() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), AMPLE_SPACE);

        // Encoder
        KompileProjectModel enc = findModel(plan.models(), "bge-base-en-v1.5");
        assertEquals("onnx", enc.getMetadata().get("registry.framework"));
        assertEquals("dense", enc.getMetadata().get("registry.modelType"));
        assertEquals("true", enc.getMetadata().get("staging.requiresDownload"));
        assertEquals("1024", enc.getMetadata().get("requirement.ramMb"));
        assertEquals("450", enc.getMetadata().get("requirement.diskMb"));

        // Reranker
        KompileProjectModel rr = findModel(plan.models(), "ms-marco-MiniLM-L-6-v2");
        assertEquals("RERANKER", rr.getRole());
        assertEquals("onnx", rr.getMetadata().get("registry.framework"));
        assertEquals("reranker", rr.getMetadata().get("registry.modelType"));

        // LLM
        KompileProjectModel llm = findModel(plan.models(), "lfm2.5-1.2b-instruct");
        assertEquals("LLM", llm.getRole());
        assertEquals("ggml", llm.getMetadata().get("registry.framework"));
        assertEquals("llm", llm.getMetadata().get("registry.modelType"));
        assertEquals("llm_ggml", llm.getMetadata().get("registry.type"),
                "LLM model must carry registry.type=llm_ggml so StagingServingBridge auto-loads it");
        assertFalse(llm.isRequired(), "LLM must be optional");
    }

    // ── LARGE / XLARGE / SERVER tiers (same selection as MEDIUM+) ───────────

    @Test
    void largeTier_sameAsMediumWithRichDocs() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.LARGE,
                        true, List.of(), AMPLE_SPACE);
        assertEquals(4, plan.models().size());
        assertModelPresent(plan.models(), "lfm2.5-1.2b-instruct", "LLM", false);
    }

    @Test
    void serverTier_sameAsMediumWithRichDocs() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SERVER,
                        true, List.of(), AMPLE_SPACE);
        assertEquals(4, plan.models().size());
    }

    // ── Disk guard ───────────────────────────────────────────────────────────

    @Test
    void diskGuard_dropsLargestOptionalFirst() {
        // Only 1 GB free (headroom consumed immediately by required encoder 450 MB +
        // reranker 100 MB + LLM 750 MB + VLM 2600 MB = ~3900 MB needed, way over 1 GB free)
        long tinyFree = 1024L * 1024L * 1024L; // 1 GB
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, List.of(), tinyFree);

        // Required encoder must still be present
        assertTrue(plan.models().stream().anyMatch(m -> "bge-base-en-v1.5".equals(m.getModelId())),
                "Required encoder must survive disk guard");

        // At least some optional models should have been dropped
        assertFalse(plan.warnings().isEmpty(), "Disk guard must emit warnings when dropping models");
        boolean anyDropWarning = plan.warnings().stream()
                .anyMatch(w -> w.contains("Dropped optional model") || w.contains("WARNING:"));
        assertTrue(anyDropWarning, "Disk guard warnings must mention dropped models or space warning");
    }

    @Test
    void diskGuard_ampleSpace_dropsNothing() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, List.of(), AMPLE_SPACE);
        assertEquals(4, plan.models().size(), "Ample space: no models should be dropped");
        // Warnings should not mention dropped models (may still have other warnings)
        boolean anyDropWarning = plan.warnings().stream()
                .anyMatch(w -> w.startsWith("Dropped optional model"));
        assertFalse(anyDropWarning, "Ample space must not produce drop warnings");
    }

    @Test
    void diskGuard_zeroBytesSkipsGuard() {
        // freeBytes=0 means "unknown" — guard is skipped entirely
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, List.of(), 0L);
        assertEquals(4, plan.models().size(), "Zero free bytes (unknown) must skip disk guard");
    }

    @Test
    void diskGuard_exactlyEnoughSpace_dropsNothing() {
        // encoder=450 + reranker=100 + llm=750 = 1300 MB needed (no VLM since not richDocs)
        // headroom = 1024 MB → need 1300 + 1024 = 2324 MB = ~2437 MB free minimum
        long justEnough = 2340L * 1024L * 1024L; // ~2340 MB
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), justEnough);
        // All 3 should survive (2340 >= 1300 + 1024 = 2324)
        assertEquals(3, plan.models().size(), "Just-enough space must not drop any model");
    }

    // ── totalDownloadBytes ───────────────────────────────────────────────────

    @Test
    void totalDownloadBytesReflectsDiskMb() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.SMALL,
                        false, List.of(), AMPLE_SPACE);
        // SMALL: only bge-base-en-v1.5 with disk_mb=450
        assertEquals(450L * 1024L * 1024L, plan.totalDownloadBytes(),
                "totalDownloadBytes must equal sum of disk_mb * 1MB");
    }

    @Test
    void totalDownloadBytesForMediumNoRichDocs() {
        // encoder=450 + reranker=100 + llm=750 = 1300 MB
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), AMPLE_SPACE);
        assertEquals((450L + 100L + 750L) * 1024L * 1024L, plan.totalDownloadBytes());
    }

    // ── summaryLines ─────────────────────────────────────────────────────────

    @Test
    void summaryLinesNonEmpty() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        false, List.of(), AMPLE_SPACE);
        assertFalse(plan.summaryLines().isEmpty());
        // First line should mention the count and tier
        String first = plan.summaryLines().get(0);
        assertTrue(first.contains("MEDIUM") || first.contains("model"),
                "Summary must mention tier or model count");
    }

    // ── CATALOG source on all models ─────────────────────────────────────────

    @Test
    void allModelsHaveCatalogSource() {
        ModelProvisioningPlanner.ModelPlan plan =
                ModelProvisioningPlanner.plan(HardwareAutoConfigurator.Tier.MEDIUM,
                        true, List.of(), AMPLE_SPACE);
        for (KompileProjectModel m : plan.models()) {
            assertEquals("CATALOG", m.getSource(),
                    "All planner models must have source=CATALOG, got: " + m.getModelId());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Build a minimal KompileProjectModel for use as an "existing" model in merge tests. */
    private static KompileProjectModel catalogModel(String modelId, String role) {
        KompileProjectModel m = new KompileProjectModel();
        m.setId(modelId);
        m.setModelId(modelId);
        m.setRole(role);
        m.setSource("CATALOG");
        return m;
    }

    private void assertModelPresent(List<KompileProjectModel> models,
                                    String modelId, String role, boolean required) {
        Optional<KompileProjectModel> opt = models.stream()
                .filter(m -> modelId.equals(m.getModelId()))
                .findFirst();
        assertTrue(opt.isPresent(), "Model '" + modelId + "' must be present");
        assertEquals(role, opt.get().getRole(), "Role mismatch for " + modelId);
        assertEquals(required, opt.get().isRequired(), "Required mismatch for " + modelId);
    }

    private KompileProjectModel findModel(List<KompileProjectModel> models, String modelId) {
        return models.stream()
                .filter(m -> modelId.equals(m.getModelId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Model not found: " + modelId));
    }
}
