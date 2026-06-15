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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.training;

import ai.kompile.staging.web.dto.DatasetDownloadStatus;
import ai.kompile.staging.web.dto.EvalSuitePresetInfo;
import ai.kompile.staging.web.dto.PreloadDatasetRequest;
import ai.kompile.staging.web.dto.StandardDatasetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Catalog of standard LLM evaluation datasets from HuggingFace and built-in benchmarks.
 * Provides one-click access to commonly used evaluation datasets and curated preset suites.
 */
@Service
public class StandardDatasetCatalog {
    private static final Logger log = LoggerFactory.getLogger(StandardDatasetCatalog.class);

    @Value("${kompile.staging.datasets-dir:#{systemProperties['user.home'] + '/.kompile/datasets'}}")
    private String datasetsDir;

    private static final List<StandardDatasetInfo> CATALOG = new ArrayList<>();
    private static final List<EvalSuitePresetInfo> SUITE_PRESETS = new ArrayList<>();

    static {
        // ==================== Open LLM Leaderboard v1 Benchmarks ====================
        CATALOG.add(StandardDatasetInfo.builder()
                .id("arc_challenge")
                .name("ARC Challenge")
                .description("AI2 Reasoning Challenge - grade-school science questions (hard subset). Tests scientific reasoning and world knowledge.")
                .huggingFaceId("allenai/ai2_arc")
                .subset("ARC-Challenge")
                .split("test")
                .sampleCount(1172)
                .category("reasoning")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("arc_easy")
                .name("ARC Easy")
                .description("AI2 Reasoning Challenge - grade-school science questions (easy subset).")
                .huggingFaceId("allenai/ai2_arc")
                .subset("ARC-Easy")
                .split("test")
                .sampleCount(2376)
                .category("reasoning")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("hellaswag")
                .name("HellaSwag")
                .description("Commonsense NLI benchmark. Given a scenario, pick the most plausible continuation from four choices.")
                .huggingFaceId("Rowan/hellaswag")
                .split("validation")
                .sampleCount(10042)
                .category("commonsense")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("mmlu")
                .name("MMLU")
                .description("Massive Multitask Language Understanding - 57 subjects spanning STEM, humanities, social sciences, and more. The standard knowledge benchmark.")
                .huggingFaceId("cais/mmlu")
                .subset("all")
                .split("test")
                .sampleCount(14042)
                .category("knowledge")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("truthfulqa")
                .name("TruthfulQA")
                .description("Tests whether models generate truthful answers to questions that humans might answer incorrectly due to misconceptions.")
                .huggingFaceId("truthfulqa/truthful_qa")
                .subset("multiple_choice")
                .split("validation")
                .sampleCount(817)
                .category("truthfulness")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("winogrande")
                .name("WinoGrande")
                .description("Large-scale Winograd Schema Challenge. Fill-in-the-blank coreference resolution requiring commonsense reasoning.")
                .huggingFaceId("allenai/winogrande")
                .subset("winogrande_xl")
                .split("validation")
                .sampleCount(1267)
                .category("commonsense")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("gsm8k")
                .name("GSM8K")
                .description("Grade School Math 8K - math word problems requiring multi-step arithmetic reasoning. Tests chain-of-thought mathematical ability.")
                .huggingFaceId("openai/gsm8k")
                .subset("main")
                .split("test")
                .sampleCount(1319)
                .category("math")
                .build());

        // ==================== Extended Benchmarks ====================
        CATALOG.add(StandardDatasetInfo.builder()
                .id("mmlu_pro")
                .name("MMLU-Pro")
                .description("Harder version of MMLU with 10 answer choices instead of 4, more reasoning-focused questions, and reduced sensitivity to prompt format.")
                .huggingFaceId("TIGER-Lab/MMLU-Pro")
                .split("test")
                .sampleCount(12032)
                .category("knowledge")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("gpqa")
                .name("GPQA Diamond")
                .description("Graduate-level Google-Proof Q&A. Expert-written questions in biology, physics, and chemistry that are difficult even for domain experts.")
                .huggingFaceId("Idavidrein/gpqa")
                .subset("gpqa_diamond")
                .split("train")
                .sampleCount(198)
                .category("expert_knowledge")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("ifeval")
                .name("IFEval")
                .description("Instruction Following Evaluation. Tests whether models can follow specific formatting and content constraints in their outputs.")
                .huggingFaceId("google/IFEval")
                .split("train")
                .sampleCount(541)
                .category("instruction_following")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("bbh")
                .name("BIG-Bench Hard")
                .description("Subset of BIG-Bench tasks where prior LLMs failed to outperform average human raters. Challenging reasoning problems.")
                .huggingFaceId("lukaemon/bbh")
                .split("test")
                .sampleCount(6511)
                .category("reasoning")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("math")
                .name("MATH")
                .description("Competition mathematics problems covering algebra, geometry, number theory, counting/probability, and more. Requires multi-step mathematical reasoning.")
                .huggingFaceId("lighteval/MATH")
                .split("test")
                .sampleCount(5000)
                .category("math")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("humaneval")
                .name("HumanEval")
                .description("Code generation benchmark. Given a function signature and docstring, generate the function body. Tests programming ability in Python.")
                .huggingFaceId("openai/openai_humaneval")
                .split("test")
                .sampleCount(164)
                .category("coding")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("mbpp")
                .name("MBPP")
                .description("Mostly Basic Python Problems. Short Python programming tasks with test cases. Tests basic programming and problem-solving skills.")
                .huggingFaceId("google-research-datasets/mbpp")
                .split("test")
                .sampleCount(500)
                .category("coding")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("piqa")
                .name("PIQA")
                .description("Physical Intuition QA. Tests physical commonsense reasoning - understanding how the physical world works.")
                .huggingFaceId("ybisk/piqa")
                .split("validation")
                .sampleCount(1838)
                .category("commonsense")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("boolq")
                .name("BoolQ")
                .description("Boolean questions - yes/no reading comprehension questions derived from real Google search queries.")
                .huggingFaceId("google/boolq")
                .split("validation")
                .sampleCount(3270)
                .category("reading_comprehension")
                .build());

        CATALOG.add(StandardDatasetInfo.builder()
                .id("openbookqa")
                .name("OpenBookQA")
                .description("Science questions modeled after open-book exams. Requires combining a core scientific fact with broad commonsense knowledge.")
                .huggingFaceId("allenai/openbookqa")
                .split("test")
                .sampleCount(500)
                .category("reasoning")
                .build());

        // ==================== Suite Presets ====================
        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("open_llm_leaderboard_v1")
                .name("Open LLM Leaderboard v1")
                .description("The original HuggingFace Open LLM Leaderboard benchmark suite. The standard set for comparing open-source language models.")
                .benchmarks(List.of("arc_challenge", "hellaswag", "mmlu", "truthfulqa", "winogrande", "gsm8k"))
                .build());

        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("reasoning")
                .name("Reasoning Suite")
                .description("Focused on logical and scientific reasoning capabilities.")
                .benchmarks(List.of("arc_challenge", "arc_easy", "hellaswag", "winogrande", "piqa", "openbookqa"))
                .build());

        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("knowledge")
                .name("Knowledge Suite")
                .description("Tests world knowledge and factual accuracy across domains.")
                .benchmarks(List.of("mmlu", "truthfulqa", "boolq"))
                .build());

        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("math_and_code")
                .name("Math & Code Suite")
                .description("Tests mathematical reasoning and code generation abilities.")
                .benchmarks(List.of("gsm8k", "math", "humaneval", "mbpp"))
                .build());

        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("quick")
                .name("Quick Eval")
                .description("A fast evaluation suite for rapid iteration. Uses smaller benchmarks that run quickly.")
                .benchmarks(List.of("arc_easy", "truthfulqa", "boolq"))
                .build());

        SUITE_PRESETS.add(EvalSuitePresetInfo.builder()
                .id("comprehensive")
                .name("Comprehensive Suite")
                .description("Thorough evaluation across all capabilities: reasoning, knowledge, math, code, commonsense, and instruction following.")
                .benchmarks(List.of("arc_challenge", "hellaswag", "mmlu", "truthfulqa", "winogrande", "gsm8k", "bbh", "piqa", "humaneval"))
                .build());
    }

    /**
     * Get all datasets in the catalog, with download status filled in.
     */
    public List<StandardDatasetInfo> listAll() {
        List<StandardDatasetInfo> result = new ArrayList<>();
        for (StandardDatasetInfo template : CATALOG) {
            StandardDatasetInfo info = StandardDatasetInfo.builder()
                    .id(template.getId())
                    .name(template.getName())
                    .description(template.getDescription())
                    .huggingFaceId(template.getHuggingFaceId())
                    .subset(template.getSubset())
                    .split(template.getSplit())
                    .sampleCount(template.getSampleCount())
                    .category(template.getCategory())
                    .downloaded(isDownloaded(template.getId()))
                    .localPath(getLocalPath(template.getId()))
                    .build();
            result.add(info);
        }
        return result;
    }

    /**
     * Get datasets in a specific category.
     */
    public List<StandardDatasetInfo> listByCategory(String category) {
        List<StandardDatasetInfo> all = listAll();
        if (category == null || category.isEmpty()) return all;
        List<StandardDatasetInfo> filtered = new ArrayList<>();
        for (StandardDatasetInfo info : all) {
            if (category.equalsIgnoreCase(info.getCategory())) {
                filtered.add(info);
            }
        }
        return filtered;
    }

    /**
     * Get a specific dataset by ID.
     */
    public StandardDatasetInfo get(String id) {
        for (StandardDatasetInfo template : CATALOG) {
            if (template.getId().equals(id)) {
                return StandardDatasetInfo.builder()
                        .id(template.getId())
                        .name(template.getName())
                        .description(template.getDescription())
                        .huggingFaceId(template.getHuggingFaceId())
                        .subset(template.getSubset())
                        .split(template.getSplit())
                        .sampleCount(template.getSampleCount())
                        .category(template.getCategory())
                        .downloaded(isDownloaded(template.getId()))
                        .localPath(getLocalPath(template.getId()))
                        .build();
            }
        }
        return null;
    }

    /**
     * Get all available suite presets.
     */
    public List<EvalSuitePresetInfo> listPresets() {
        return Collections.unmodifiableList(SUITE_PRESETS);
    }

    /**
     * Get all unique categories across all catalog entries.
     */
    public List<String> listCategories() {
        Set<String> categories = new LinkedHashSet<>();
        for (StandardDatasetInfo info : CATALOG) {
            categories.add(info.getCategory());
        }
        return new ArrayList<>(categories);
    }

    // ==================== Async Download Management ====================

    private final Map<String, DatasetDownloadStatus> activeDownloads = new ConcurrentHashMap<>();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);

    /**
     * Start an async download for a standard dataset.
     * Returns immediately with a QUEUED status. Poll via getDownloadStatus().
     */
    public DatasetDownloadStatus startAsyncDownload(String datasetId, DatasetService datasetService) {
        StandardDatasetInfo catalogEntry = get(datasetId);
        if (catalogEntry == null) {
            return DatasetDownloadStatus.builder()
                    .datasetId(datasetId)
                    .phase("FAILED")
                    .error("Dataset not found in catalog: " + datasetId)
                    .build();
        }

        if (catalogEntry.isDownloaded()) {
            return DatasetDownloadStatus.builder()
                    .datasetId(datasetId)
                    .name(catalogEntry.getName())
                    .phase("COMPLETED")
                    .progressPercent(100)
                    .totalSamples(catalogEntry.getSampleCount())
                    .samplesDownloaded(catalogEntry.getSampleCount())
                    .message("Already downloaded")
                    .build();
        }

        // Check if already downloading
        DatasetDownloadStatus existing = activeDownloads.get(datasetId);
        if (existing != null && !"COMPLETED".equals(existing.getPhase()) && !"FAILED".equals(existing.getPhase())) {
            return existing;
        }

        long now = System.currentTimeMillis();
        DatasetDownloadStatus status = DatasetDownloadStatus.builder()
                .datasetId(datasetId)
                .name(catalogEntry.getName())
                .phase("QUEUED")
                .progressPercent(0)
                .totalSamples(catalogEntry.getSampleCount())
                .samplesDownloaded(0)
                .message("Queued for download")
                .startedAtMs(now)
                .elapsedMs(0)
                .build();
        activeDownloads.put(datasetId, status);

        downloadExecutor.submit(() -> executeDownload(datasetId, catalogEntry, datasetService));

        return status;
    }

    /**
     * Get the current download status for a dataset.
     */
    public DatasetDownloadStatus getDownloadStatus(String datasetId) {
        DatasetDownloadStatus status = activeDownloads.get(datasetId);
        if (status != null) {
            status.setElapsedMs(System.currentTimeMillis() - status.getStartedAtMs());
            return status;
        }

        // Check if already downloaded (no active download tracked)
        if (isDownloaded(datasetId)) {
            StandardDatasetInfo info = get(datasetId);
            return DatasetDownloadStatus.builder()
                    .datasetId(datasetId)
                    .name(info != null ? info.getName() : datasetId)
                    .phase("COMPLETED")
                    .progressPercent(100)
                    .message("Downloaded")
                    .build();
        }

        return null;
    }

    /**
     * Get download status for all active downloads.
     */
    public Map<String, DatasetDownloadStatus> getAllDownloadStatuses() {
        long now = System.currentTimeMillis();
        Map<String, DatasetDownloadStatus> result = new LinkedHashMap<>();
        for (Map.Entry<String, DatasetDownloadStatus> entry : activeDownloads.entrySet()) {
            DatasetDownloadStatus status = entry.getValue();
            status.setElapsedMs(now - status.getStartedAtMs());
            result.put(entry.getKey(), status);
        }
        return result;
    }

    private void executeDownload(String datasetId, StandardDatasetInfo catalogEntry, DatasetService datasetService) {
        try {
            updateStatus(datasetId, "CONNECTING", 5, "Connecting to HuggingFace...");

            PreloadDatasetRequest preloadRequest = new PreloadDatasetRequest();
            preloadRequest.setSource("huggingface");
            preloadRequest.setHuggingfaceRepo(catalogEntry.getHuggingFaceId());
            preloadRequest.setSubset(catalogEntry.getSubset());
            preloadRequest.setSplit(catalogEntry.getSplit());
            preloadRequest.setName(catalogEntry.getName());
            preloadRequest.setMaxSamples(-1);

            updateStatus(datasetId, "DOWNLOADING", 15,
                    String.format("Downloading %s from %s...", catalogEntry.getName(), catalogEntry.getHuggingFaceId()));

            datasetService.preloadDataset(preloadRequest);

            updateStatus(datasetId, "WRITING", 85, "Writing JSONL to disk...");

            // Brief pause to let file system sync
            Thread.sleep(100);

            DatasetDownloadStatus finalStatus = activeDownloads.get(datasetId);
            if (finalStatus != null) {
                finalStatus.setPhase("COMPLETED");
                finalStatus.setProgressPercent(100);
                finalStatus.setSamplesDownloaded(catalogEntry.getSampleCount());
                finalStatus.setMessage(String.format("Downloaded %s (%d samples)",
                        catalogEntry.getName(), catalogEntry.getSampleCount()));
                finalStatus.setElapsedMs(System.currentTimeMillis() - finalStatus.getStartedAtMs());
            }

            log.info("Standard dataset download completed: {} ({})", datasetId, catalogEntry.getName());

        } catch (Exception e) {
            log.error("Failed to download standard dataset {}: {}", datasetId, e.getMessage(), e);
            DatasetDownloadStatus status = activeDownloads.get(datasetId);
            if (status != null) {
                status.setPhase("FAILED");
                status.setError(e.getMessage());
                status.setMessage("Download failed: " + e.getMessage());
                status.setElapsedMs(System.currentTimeMillis() - status.getStartedAtMs());
            }
        }
    }

    private void updateStatus(String datasetId, String phase, int percent, String message) {
        DatasetDownloadStatus status = activeDownloads.get(datasetId);
        if (status != null) {
            status.setPhase(phase);
            status.setProgressPercent(percent);
            status.setMessage(message);
            status.setElapsedMs(System.currentTimeMillis() - status.getStartedAtMs());
        }
    }

    private boolean isDownloaded(String datasetId) {
        File dir = new File(datasetsDir, "standard-" + datasetId);
        File metaFile = new File(dir, "meta.json");
        return metaFile.exists();
    }

    private String getLocalPath(String datasetId) {
        File dir = new File(datasetsDir, "standard-" + datasetId);
        File dataFile = new File(dir, "data.jsonl");
        if (dataFile.exists()) {
            return dataFile.getAbsolutePath();
        }
        return null;
    }
}
