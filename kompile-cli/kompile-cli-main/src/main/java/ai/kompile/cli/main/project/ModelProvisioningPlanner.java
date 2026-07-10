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

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Selects the default model set for a project based on hardware tier and
 * project characteristics. Returns manifest-ready {@link KompileProjectModel}
 * entries whose source=CATALOG so the existing
 * {@code autoStageProjectModels} serve-time loop can stage them via
 * {@code POST /api/staging/stage/catalog/<modelId>?autoPromote=true}.
 *
 * <p>Called by {@code kompile project init} (another workstream wires the call).
 * The public API is intentionally minimal and stable.</p>
 *
 * <h3>Selection rules (defaults ON)</h3>
 * <ul>
 *   <li>Always: embedding {@code bge-base-en-v1.5}, role ENCODER, required=true
 *       (unless {@code hasExistingModels} is true).</li>
 *   <li>MEDIUM+: reranker {@code ms-marco-MiniLM-L-6-v2}, role RERANKER, required=false.</li>
 *   <li>MEDIUM+: LLM {@code lfm2.5-1.2b-instruct}, role LLM, required=false
 *       (enables local chat lane; StagingServingBridge auto-loads llm_ggml).</li>
 *   <li>MEDIUM+ with rich documents: VLM {@code smoldocling-256m}, role VLM, required=false.</li>
 *   <li>SMALL tier: embedding only + warning that LLM/VLM were skipped.</li>
 *   <li>Disk guard: if free space on projectRoot is tight, optional models are
 *       dropped largest-first until the plan fits (required models are kept with
 *       a loud warning).</li>
 * </ul>
 */
public final class ModelProvisioningPlanner {

    private ModelProvisioningPlanner() { }

    // ── Requirements table ──────────────────────────────────────────────────
    //
    // Mirrors model-sources.yml metadata fields ram_mb / vram_mb / disk_mb.
    // Keep in sync when model-sources.yml changes.

    private record ModelRequirements(String modelId, int ramMb, int diskMb) { }

    private static final Map<String, ModelRequirements> REQUIREMENTS;

    static {
        Map<String, ModelRequirements> m = new LinkedHashMap<>();
        m.put("bge-base-en-v1.5",          new ModelRequirements("bge-base-en-v1.5",        1024,  450));
        m.put("ms-marco-MiniLM-L-6-v2",    new ModelRequirements("ms-marco-MiniLM-L-6-v2",   512,  100));
        m.put("lfm2.5-1.2b-instruct",      new ModelRequirements("lfm2.5-1.2b-instruct",    3072,  750));
        m.put("smoldocling-256m",           new ModelRequirements("smoldocling-256m",         4096, 2600));
        REQUIREMENTS = Collections.unmodifiableMap(m);
    }

    // ── Public result type ───────────────────────────────────────────────────

    /**
     * The result of model provisioning planning.
     *
     * @param models              manifest-ready model entries (source=CATALOG)
     * @param warnings            human-readable warnings (tier skips, disk drops, etc.)
     * @param totalDownloadBytes  sum of disk_mb for all selected models in bytes
     * @param summaryLines        short human-readable summary lines for CLI output
     */
    public record ModelPlan(
            List<KompileProjectModel> models,
            List<String> warnings,
            long totalDownloadBytes,
            List<String> summaryLines
    ) { }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Plan the model set for a project, merging with any models already registered.
     *
     * <p>Instead of skipping when models already exist, the planner builds the
     * tier-appropriate candidate list as normal and then filters out candidates whose
     * <em>role</em> (ENCODER/RERANKER/LLM/VLM) is already covered by an existing model
     * OR whose model ID is already present.  This allows {@code applyScenario} to
     * pre-seed ENCODER + VLM for a rich-documents project and still get the RERANKER and
     * LLM added by the planner.</p>
     *
     * <p>Idempotency: running {@code project init} a second time on the same project
     * passes the full manifest model list as {@code existingModels}; every candidate
     * will be filtered out (role already covered) and the plan returns an empty list.</p>
     *
     * @param tier           hardware tier (from {@link HardwareAutoConfigurator#resolveTier})
     * @param hasRichDocuments true when PDFs / images were detected (enables VLM)
     * @param existingModels models already registered in the manifest (may be empty)
     * @param projectRoot    used for disk-space guard (may be null; guard skipped if null)
     */
    public static ModelPlan plan(
            HardwareAutoConfigurator.Tier tier,
            boolean hasRichDocuments,
            Collection<KompileProjectModel> existingModels,
            Path projectRoot) {
        long freeBytes = resolveFreeBytes(projectRoot);
        return plan(tier, hasRichDocuments, existingModels, freeBytes);
    }

    /**
     * Package-private overload that injects free bytes for unit tests
     * (avoids touching the real filesystem in tests).
     */
    static ModelPlan plan(
            HardwareAutoConfigurator.Tier tier,
            boolean hasRichDocuments,
            Collection<KompileProjectModel> existingModels,
            long freeBytesOverride) {

        List<KompileProjectModel> models = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Build sets of already-covered roles and model IDs so candidates can be filtered.
        Collection<KompileProjectModel> existing =
                (existingModels != null) ? existingModels : List.of();
        Set<String> coveredRoles = existing.stream()
                .map(m -> m.getRole() != null ? m.getRole().toUpperCase(Locale.ROOT) : "")
                .filter(r -> !r.isEmpty())
                .collect(Collectors.toSet());
        Set<String> coveredIds = existing.stream()
                .map(KompileProjectModel::getModelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ── Build candidate list (tier-appropriate, full set) ───────────────

        List<KompileProjectModel> candidates = new ArrayList<>();

        // Always: base encoder
        candidates.add(catalogModel("bge-base-en-v1.5", "ENCODER", true,
                "onnx", "dense", null));

        boolean isMediumOrAbove = tier != HardwareAutoConfigurator.Tier.SMALL;

        if (isMediumOrAbove) {
            // Reranker: role string "RERANKER" maps to registry type "cross_encoder"
            // (mirrors ProjectModelCommand.registryTypeForRole() and
            //  ProjectModelCommand.defaultMetadataModelType() which returns "reranker")
            candidates.add(catalogModel("ms-marco-MiniLM-L-6-v2", "RERANKER", false,
                    "onnx", "reranker", null));

            // Local LLM: type llm_ggml — StagingServingBridge auto-loads these at serve time
            candidates.add(catalogModel("lfm2.5-1.2b-instruct", "LLM", false,
                    "ggml", "llm", "llm_ggml"));

            if (hasRichDocuments) {
                // VLM for PDF/image OCR; id matches the existing smoldocling preset
                candidates.add(catalogModel("smoldocling-256m", "VLM", false,
                        "onnx", "vlm", null));
            }
        } else {
            warnings.add("SMALL tier (<8 GB RAM): LLM and VLM models were skipped. "
                    + "Upgrade to MEDIUM (8+ GB) to enable local chat and document OCR.");
        }

        // ── Merge: skip candidates whose role or id is already covered ───────
        //
        // A user-provided LOCAL model covering a role wins over the catalog default
        // (e.g. a LOCAL LLM means we never add lfm2.5). An existing CATALOG model
        // for the same id is also a no-op (idempotency across re-init runs).

        List<String> keptExisting = new ArrayList<>();

        for (KompileProjectModel candidate : candidates) {
            String role = candidate.getRole() != null
                    ? candidate.getRole().toUpperCase(Locale.ROOT) : "";
            boolean roleAlreadyCovered = !role.isEmpty() && coveredRoles.contains(role);
            boolean idAlreadyPresent   = candidate.getModelId() != null
                    && coveredIds.contains(candidate.getModelId());

            if (roleAlreadyCovered || idAlreadyPresent) {
                // Find the existing model that covers this role for the summary
                String existingId = existing.stream()
                        .filter(m -> role.equalsIgnoreCase(m.getRole())
                                || (candidate.getModelId() != null
                                    && candidate.getModelId().equals(m.getModelId())))
                        .map(KompileProjectModel::getModelId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("(existing)");
                keptExisting.add(role + "→" + existingId + " (kept)");
            } else {
                models.add(candidate);
            }
        }

        // ── Disk guard ───────────────────────────────────────────────────────

        if (freeBytesOverride > 0) {
            applyDiskGuard(models, warnings, freeBytesOverride);
        }

        // ── Build summary ────────────────────────────────────────────────────

        long totalBytes = models.stream()
                .mapToLong(m -> {
                    String diskStr = m.getMetadata().get("requirement.diskMb");
                    if (diskStr == null) return 0L;
                    try { return Long.parseLong(diskStr) * 1024L * 1024L; }
                    catch (NumberFormatException e) { return 0L; }
                })
                .sum();

        List<String> summary = new ArrayList<>();
        if (!keptExisting.isEmpty()) {
            summary.add(String.format(
                    "Model plan (tier %s): adding %d new, keeping %d existing by role:",
                    tier, models.size(), keptExisting.size()));
            for (String kept : keptExisting) {
                summary.add("  ~ " + kept);
            }
        } else {
            summary.add(String.format("Selected %d model(s) for tier %s:", models.size(), tier));
        }
        for (KompileProjectModel m : models) {
            summary.add(String.format("  + %-30s  role=%-8s  required=%s  source=%s",
                    m.getModelId(), m.getRole(), m.isRequired(), m.getSource()));
        }
        if (models.isEmpty() && keptExisting.isEmpty()) {
            summary.add("  (no models to add — project fully provisioned)");
        }
        if (!warnings.isEmpty()) {
            summary.add("Warnings:");
            for (String w : warnings) {
                summary.add("  [!] " + w);
            }
        }

        return new ModelPlan(Collections.unmodifiableList(models),
                Collections.unmodifiableList(warnings),
                totalBytes,
                Collections.unmodifiableList(summary));
    }

    // ── Disk guard implementation ─────────────────────────────────────────────

    /**
     * Drops optional models largest-disk-first until the total fits in free space
     * with 1 GB headroom. Required models are never dropped — they get a loud warning.
     */
    private static void applyDiskGuard(
            List<KompileProjectModel> models,
            List<String> warnings,
            long freeBytes) {

        final long headroomBytes = 1024L * 1024L * 1024L; // 1 GB

        // Sort a snapshot of optional models descending by disk_mb for greedy drop
        List<KompileProjectModel> optional = new ArrayList<>();
        for (KompileProjectModel m : models) {
            if (!m.isRequired()) optional.add(m);
        }
        optional.sort((a, b) -> {
            int da = diskMbOf(a);
            int db = diskMbOf(b);
            return Integer.compare(db, da); // descending
        });

        // Keep dropping until we fit
        for (KompileProjectModel candidate : optional) {
            long totalDisk = totalDiskBytes(models);
            if (totalDisk + headroomBytes <= freeBytes) break;
            models.remove(candidate);
            warnings.add(String.format(
                    "Dropped optional model '%s' (%d MB) due to insufficient free disk space.",
                    candidate.getModelId(), diskMbOf(candidate)));
        }

        // Check if even required models exceed space
        long remaining = totalDiskBytes(models);
        if (remaining + headroomBytes > freeBytes && freeBytes > 0) {
            warnings.add(String.format(
                    "WARNING: required models need ~%d MB disk but only ~%d MB is free. "
                    + "Free disk space before starting or models will fail to download.",
                    remaining / (1024L * 1024L),
                    freeBytes / (1024L * 1024L)));
        }
    }

    private static long totalDiskBytes(List<KompileProjectModel> models) {
        return models.stream()
                .mapToLong(m -> (long) diskMbOf(m) * 1024L * 1024L)
                .sum();
    }

    private static int diskMbOf(KompileProjectModel m) {
        String v = m.getMetadata().get("requirement.diskMb");
        if (v == null) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    // ── Model builder ────────────────────────────────────────────────────────

    /**
     * Build a CATALOG-sourced {@link KompileProjectModel} with the metadata
     * conventions that the existing {@code autoStageProjectModels} loop expects,
     * mirroring {@code ProjectCommand.defaultEncoderModel()} and
     * {@code vlmOcrModel()}.
     *
     * @param modelId       catalog model id
     * @param role          role string (ENCODER / RERANKER / LLM / VLM)
     * @param required      whether this model is required for basic operation
     * @param framework     registry.framework value (onnx / ggml / samediff)
     * @param modelType     registry.modelType value (dense / reranker / llm / vlm)
     * @param registryType  registry.type override, or null to derive from role
     */
    private static KompileProjectModel catalogModel(
            String modelId,
            String role,
            boolean required,
            String framework,
            String modelType,
            String registryType) {

        ModelRequirements req = REQUIREMENTS.get(modelId);

        KompileProjectModel m = new KompileProjectModel();
        m.setId(modelId);
        m.setModelId(modelId);
        m.setRole(role);
        m.setSource("CATALOG");
        m.setRegistryModelId(modelId);
        m.setRequired(required);
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());

        // Metadata conventions mirror ProjectCommand.defaultEncoderModel()
        // and ProjectModelCommand.buildModelEntry() / stagingModelEntryJson()
        m.getMetadata().put("registry.framework", framework);
        m.getMetadata().put("registry.modelType", modelType);
        String supportedLanguages = defaultSupportedLanguages(modelId);
        if (supportedLanguages != null) {
            m.getMetadata().put("registry.supportedLanguages", supportedLanguages);
        }
        if (registryType != null) {
            m.getMetadata().put("registry.type", registryType);
        }
        m.getMetadata().put("staging.requiresDownload", "true");

        // Resource requirements from the mirrored table (matches model-sources.yml)
        if (req != null) {
            m.getMetadata().put("requirement.ramMb", String.valueOf(req.ramMb()));
            m.getMetadata().put("requirement.diskMb", String.valueOf(req.diskMb()));
        }

        return m;
    }

    private static String defaultSupportedLanguages(String modelId) {
        if (modelId == null) {
            return null;
        }
        return switch (modelId) {
            case "bge-base-en-v1.5", "ms-marco-MiniLM-L-6-v2", "ms-marco-MiniLM-L-12-v2" -> "en";
            default -> null;
        };
    }

    // ── Disk detection ───────────────────────────────────────────────────────

    private static long resolveFreeBytes(Path projectRoot) {
        if (projectRoot == null) return 0L;
        try {
            Path check = Files.exists(projectRoot) ? projectRoot : projectRoot.getParent();
            if (check == null) return 0L;
            FileStore store = Files.getFileStore(check);
            return store.getUsableSpace();
        } catch (IOException e) {
            return 0L;
        }
    }
}
