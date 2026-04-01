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

import ai.kompile.staging.web.dto.BenchmarkInfo;
import ai.kompile.staging.web.dto.DatasetInfo;
import ai.kompile.staging.web.dto.DatasetStats;
import ai.kompile.staging.web.dto.PreloadDatasetRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.eval.benchmark.*;
import org.eclipse.deeplearning4j.llm.eval.dataset.*;
import org.eclipse.deeplearning4j.llm.eval.metrics.EvalMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing training datasets: upload, listing, preview, statistics, and deletion.
 */
@Service
public class DatasetService {
    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    @Value("${kompile.staging.datasets-dir:#{systemProperties['user.home'] + '/.kompile/datasets'}}")
    private String datasetsDir;

    private final ObjectMapper objectMapper;

    private static final Map<String, BenchmarkTask> BENCHMARKS = new LinkedHashMap<>();

    static {
        BENCHMARKS.put("arc_easy", new ArcBenchmark(ArcBenchmark.Subset.EASY));
        BENCHMARKS.put("arc_challenge", new ArcBenchmark(ArcBenchmark.Subset.CHALLENGE));
        BENCHMARKS.put("mmlu", new MMLUBenchmark());
        BENCHMARKS.put("hellaswag", new HellaSwagBenchmark());
        BENCHMARKS.put("gsm8k", new Gsm8kBenchmark());
        BENCHMARKS.put("truthfulqa", new TruthfulQABenchmark());
        BENCHMARKS.put("winogrande", new WinograndeBenchmark());
    }

    public DatasetService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Upload a new dataset file and create associated metadata.
     *
     * @param name           human-readable dataset name
     * @param format         file format (csv, jsonl, parquet, tsv, etc.)
     * @param task           training task type (sft, classification, preference, etc.)
     * @param inputColumn    name of the input/prompt column
     * @param outputColumn   name of the output/completion column
     * @param chosenColumn   name of the chosen column (for preference datasets)
     * @param rejectedColumn name of the rejected column (for preference datasets)
     * @param trainSplit     fraction of data to use for training (0.0-1.0)
     * @param file           the uploaded file
     * @return DatasetInfo with metadata about the stored dataset
     */
    public DatasetInfo uploadDataset(String name, String format, String task,
                                     String inputColumn, String outputColumn,
                                     String chosenColumn, String rejectedColumn,
                                     double trainSplit, MultipartFile file) {
        String id = UUID.randomUUID().toString();
        File datasetDir = new File(datasetsDir, id);
        datasetDir.mkdirs();

        try {
            // Save the uploaded file
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isEmpty()) {
                originalFilename = "data." + (format != null ? format : "csv");
            }
            File dataFile = new File(datasetDir, originalFilename);
            file.transferTo(dataFile);

            // Compute basic stats
            long totalLines = countLines(dataFile);
            // Subtract header line for CSV/TSV
            long totalSamples = totalLines;
            if ("csv".equalsIgnoreCase(format) || "tsv".equalsIgnoreCase(format)) {
                totalSamples = Math.max(0, totalLines - 1);
            }

            long trainSamples = (long) (totalSamples * trainSplit);
            long valSamples = totalSamples - trainSamples;

            DatasetStats stats = DatasetStats.builder()
                    .totalSamples(totalSamples)
                    .trainSamples(trainSamples)
                    .valSamples(valSamples)
                    .avgTokenLength(0)
                    .maxTokenLength(0)
                    .minTokenLength(0)
                    .labelDistribution(Collections.emptyMap())
                    .build();

            DatasetInfo info = DatasetInfo.builder()
                    .id(id)
                    .name(name != null ? name : originalFilename)
                    .format(format != null ? format : "csv")
                    .task(task != null ? task : "sft")
                    .sizeBytes(dataFile.length())
                    .totalSamples(totalSamples)
                    .createdAt(Instant.now().toString())
                    .filePath(dataFile.getAbsolutePath())
                    .stats(stats)
                    .build();

            // Write meta.json
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", id);
            meta.put("name", info.getName());
            meta.put("format", info.getFormat());
            meta.put("task", info.getTask());
            meta.put("sizeBytes", info.getSizeBytes());
            meta.put("totalSamples", info.getTotalSamples());
            meta.put("createdAt", info.getCreatedAt());
            meta.put("filePath", info.getFilePath());
            meta.put("inputColumn", inputColumn);
            meta.put("outputColumn", outputColumn);
            meta.put("chosenColumn", chosenColumn);
            meta.put("rejectedColumn", rejectedColumn);
            meta.put("trainSplit", trainSplit);
            meta.put("stats", stats);

            File metaFile = new File(datasetDir, "meta.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, meta);

            log.info("Dataset uploaded: id={}, name={}, samples={}, size={}",
                    id, info.getName(), totalSamples, info.getSizeBytes());

            return info;

        } catch (Exception e) {
            log.error("Failed to upload dataset: {}", e.getMessage(), e);
            // Clean up on failure
            deleteDirectoryQuietly(datasetDir);
            throw new RuntimeException("Failed to upload dataset: " + e.getMessage(), e);
        }
    }

    /**
     * List all stored datasets by scanning the datasets directory for meta.json files.
     *
     * @return list of DatasetInfo for all stored datasets
     */
    public List<DatasetInfo> listDatasets() {
        File dir = new File(datasetsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs == null) {
            return Collections.emptyList();
        }

        List<DatasetInfo> datasets = new ArrayList<>();
        for (File subdir : subdirs) {
            File metaFile = new File(subdir, "meta.json");
            if (metaFile.exists()) {
                try {
                    DatasetInfo info = readMetaJson(metaFile);
                    if (info != null) {
                        datasets.add(info);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read meta.json from {}: {}", subdir.getName(), e.getMessage());
                }
            }
        }

        return datasets;
    }

    /**
     * Get metadata for a specific dataset by ID.
     *
     * @param id the dataset identifier
     * @return DatasetInfo or null if not found
     */
    public DatasetInfo getDataset(String id) {
        File metaFile = new File(new File(datasetsDir, id), "meta.json");
        if (!metaFile.exists()) {
            return null;
        }

        try {
            return readMetaJson(metaFile);
        } catch (Exception e) {
            log.error("Failed to read dataset {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a dataset and all its associated files.
     *
     * @param id the dataset identifier
     */
    public void deleteDataset(String id) {
        File datasetDir = new File(datasetsDir, id);
        if (!datasetDir.exists()) {
            log.warn("Dataset directory not found for deletion: {}", id);
            return;
        }

        deleteDirectoryQuietly(datasetDir);
        log.info("Dataset deleted: {}", id);
    }

    /**
     * Preview the first N rows of a dataset file.
     *
     * @param id   the dataset identifier
     * @param rows number of rows to preview
     * @return list of maps representing each row's columns and values
     */
    public List<Map<String, Object>> previewDataset(String id, int rows) {
        DatasetInfo info = getDataset(id);
        if (info == null) {
            return Collections.emptyList();
        }

        File dataFile = new File(info.getFilePath());
        if (!dataFile.exists()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String format = info.getFormat();
            if ("jsonl".equalsIgnoreCase(format) || "json".equalsIgnoreCase(format)) {
                result = previewJsonl(dataFile, rows);
            } else if ("csv".equalsIgnoreCase(format)) {
                result = previewDelimited(dataFile, rows, ",");
            } else if ("tsv".equalsIgnoreCase(format)) {
                result = previewDelimited(dataFile, rows, "\t");
            } else {
                // Fallback: return raw lines
                result = previewRawLines(dataFile, rows);
            }
        } catch (Exception e) {
            log.error("Failed to preview dataset {}: {}", id, e.getMessage());
        }

        return result;
    }

    /**
     * Compute and update statistics for a dataset.
     *
     * @param id the dataset identifier
     * @return updated DatasetStats or null if dataset not found
     */
    public DatasetStats computeStats(String id) {
        DatasetInfo info = getDataset(id);
        if (info == null) {
            return null;
        }

        File dataFile = new File(info.getFilePath());
        if (!dataFile.exists()) {
            return null;
        }

        try {
            long totalLines = countLines(dataFile);
            String format = info.getFormat();
            long totalSamples = totalLines;
            if ("csv".equalsIgnoreCase(format) || "tsv".equalsIgnoreCase(format)) {
                totalSamples = Math.max(0, totalLines - 1);
            }

            // Compute token length statistics by reading lines
            int maxTokenLen = 0;
            int minTokenLen = Integer.MAX_VALUE;
            long totalTokenLen = 0;
            long sampleCount = 0;
            Map<String, Long> labelDistribution = new LinkedHashMap<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(dataFile))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    // Skip header for CSV/TSV
                    if (firstLine && ("csv".equalsIgnoreCase(format) || "tsv".equalsIgnoreCase(format))) {
                        firstLine = false;
                        continue;
                    }
                    firstLine = false;

                    // Approximate token count by splitting on whitespace
                    int tokenCount = line.split("\\s+").length;
                    totalTokenLen += tokenCount;
                    maxTokenLen = Math.max(maxTokenLen, tokenCount);
                    minTokenLen = Math.min(minTokenLen, tokenCount);
                    sampleCount++;

                    // For JSONL, try to extract a label field
                    if ("jsonl".equalsIgnoreCase(format) || "json".equalsIgnoreCase(format)) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = objectMapper.readValue(line, Map.class);
                            Object label = row.get("label");
                            if (label != null) {
                                labelDistribution.merge(label.toString(), 1L, Long::sum);
                            }
                        } catch (Exception ignored) {
                            // Not valid JSON, skip label extraction
                        }
                    }
                }
            }

            if (sampleCount == 0) {
                minTokenLen = 0;
            }

            double avgTokenLen = sampleCount > 0 ? (double) totalTokenLen / sampleCount : 0;

            // Estimate train/val split from existing meta
            double trainSplit = 0.8;
            try {
                File metaFile = new File(new File(datasetsDir, id), "meta.json");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = objectMapper.readValue(metaFile, Map.class);
                Object split = meta.get("trainSplit");
                if (split instanceof Number) {
                    trainSplit = ((Number) split).doubleValue();
                }
            } catch (Exception ignored) {
            }

            long trainSamples = (long) (totalSamples * trainSplit);
            long valSamples = totalSamples - trainSamples;

            DatasetStats stats = DatasetStats.builder()
                    .totalSamples(totalSamples)
                    .trainSamples(trainSamples)
                    .valSamples(valSamples)
                    .avgTokenLength(avgTokenLen)
                    .maxTokenLength(maxTokenLen)
                    .minTokenLength(minTokenLen)
                    .labelDistribution(labelDistribution)
                    .build();

            // Update meta.json with new stats
            try {
                File metaFile = new File(new File(datasetsDir, id), "meta.json");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = objectMapper.readValue(metaFile, Map.class);
                meta.put("stats", stats);
                meta.put("totalSamples", totalSamples);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, meta);
            } catch (Exception e) {
                log.warn("Failed to update meta.json stats for dataset {}: {}", id, e.getMessage());
            }

            log.info("Computed stats for dataset {}: {} samples, avg token length {:.1f}",
                    id, totalSamples, avgTokenLen);
            return stats;

        } catch (Exception e) {
            log.error("Failed to compute stats for dataset {}: {}", id, e.getMessage());
            return null;
        }
    }

    // ==================== Benchmark / HuggingFace Preloading ====================

    /**
     * Preload a dataset from a built-in benchmark or HuggingFace.
     */
    public DatasetInfo preloadDataset(PreloadDatasetRequest request) {
        if ("benchmark".equalsIgnoreCase(request.getSource())) {
            return preloadBenchmarkDataset(request.getBenchmarkName(),
                    request.getMaxSamples() > 0 ? request.getMaxSamples() : -1,
                    request.getName());
        } else if ("huggingface".equalsIgnoreCase(request.getSource())) {
            return preloadHuggingFaceDataset(request.getHuggingfaceRepo(),
                    request.getSubset(), request.getSplit(),
                    request.getMaxSamples() > 0 ? request.getMaxSamples() : -1,
                    request.getName());
        } else {
            throw new IllegalArgumentException("Unknown source: " + request.getSource()
                    + ". Supported: benchmark, huggingface");
        }
    }

    /**
     * Preload a dataset from a built-in benchmark, serialize to JSONL, and register metadata.
     */
    public DatasetInfo preloadBenchmarkDataset(String benchmarkName, int maxSamples, String displayName) {
        String key = benchmarkName.toLowerCase();
        BenchmarkTask benchmark = BENCHMARKS.get(key);
        if (benchmark == null) {
            throw new IllegalArgumentException("Unknown benchmark: " + benchmarkName
                    + ". Available: " + String.join(", ", BENCHMARKS.keySet()));
        }

        try {
            EvalDataset dataset = benchmark.loadDataset();
            return saveEvalDatasetAsJsonl(dataset, benchmark.name(),
                    displayName != null ? displayName : benchmark.name(),
                    maxSamples, "benchmark");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load benchmark dataset " + benchmarkName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Preload a dataset from HuggingFace, serialize to JSONL, and register metadata.
     */
    public DatasetInfo preloadHuggingFaceDataset(String repo, String subset, String split,
                                                  int maxSamples, String displayName) {
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException("HuggingFace repo must be specified");
        }

        try {
            HuggingFaceDataset.HfFieldMapping mapping = HuggingFaceDataset.HfFieldMapping.builder().build();
            int limit = maxSamples > 0 ? maxSamples : Integer.MAX_VALUE;
            HuggingFaceDataset dataset = HuggingFaceDataset.create(
                    repo,
                    subset != null ? subset : "",
                    split != null ? split : "test",
                    mapping,
                    limit);

            String name = displayName != null ? displayName : repo.replace("/", "_");
            return saveEvalDatasetAsJsonl(dataset, repo, name, maxSamples, "huggingface");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load HuggingFace dataset " + repo + ": " + e.getMessage(), e);
        }
    }

    /**
     * List available benchmarks for preloading.
     */
    public List<BenchmarkInfo> listAvailableBenchmarks() {
        List<BenchmarkInfo> result = new ArrayList<>();
        for (Map.Entry<String, BenchmarkTask> entry : BENCHMARKS.entrySet()) {
            BenchmarkTask benchmark = entry.getValue();
            result.add(BenchmarkInfo.builder()
                    .name(entry.getKey())
                    .description(descriptionForBenchmark(entry.getKey()))
                    .outputType(benchmark.outputType().name())
                    .defaultFewShot(benchmark.defaultFewShot())
                    .defaultMaxNewTokens(benchmark.defaultMaxNewTokens())
                    .metrics(benchmark.allMetrics().stream()
                            .map(EvalMetric::name)
                            .collect(Collectors.toList()))
                    .build());
        }
        return result;
    }

    /**
     * Save an EvalDataset as a JSONL file and register it as a managed dataset.
     */
    private DatasetInfo saveEvalDatasetAsJsonl(EvalDataset dataset, String source,
                                                String displayName, int maxSamples, String sourceType) {
        String id = UUID.randomUUID().toString();
        File datasetDir = new File(datasetsDir, id);
        datasetDir.mkdirs();

        try {
            File dataFile = new File(datasetDir, "data.jsonl");
            int count = 0;
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile))) {
                for (EvalSample sample : dataset) {
                    if (maxSamples > 0 && count >= maxSamples) break;

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", sample.getId());
                    row.put("input", sample.getInput());
                    if (sample.getChoices() != null && !sample.getChoices().isEmpty()) {
                        row.put("choices", sample.getChoices());
                    }
                    if (sample.getReferences() != null && !sample.getReferences().isEmpty()) {
                        row.put("references", sample.getReferences());
                    }
                    if (sample.getSubject() != null) {
                        row.put("subject", sample.getSubject());
                    }
                    if (sample.getMetadata() != null && !sample.getMetadata().isEmpty()) {
                        row.put("metadata", sample.getMetadata());
                    }

                    writer.write(objectMapper.writeValueAsString(row));
                    writer.newLine();
                    count++;
                }
            }

            long totalSamples = count;

            DatasetStats stats = DatasetStats.builder()
                    .totalSamples(totalSamples)
                    .trainSamples(0)
                    .valSamples(totalSamples)
                    .avgTokenLength(0)
                    .maxTokenLength(0)
                    .minTokenLength(0)
                    .labelDistribution(Collections.emptyMap())
                    .build();

            DatasetInfo info = DatasetInfo.builder()
                    .id(id)
                    .name(displayName)
                    .format("jsonl")
                    .task("evaluation")
                    .sizeBytes(dataFile.length())
                    .totalSamples(totalSamples)
                    .createdAt(Instant.now().toString())
                    .filePath(dataFile.getAbsolutePath())
                    .stats(stats)
                    .build();

            // Write meta.json
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", id);
            meta.put("name", info.getName());
            meta.put("format", "jsonl");
            meta.put("task", "evaluation");
            meta.put("sizeBytes", info.getSizeBytes());
            meta.put("totalSamples", totalSamples);
            meta.put("createdAt", info.getCreatedAt());
            meta.put("filePath", info.getFilePath());
            meta.put("sourceType", sourceType);
            meta.put("source", source);
            meta.put("trainSplit", 0.0);
            meta.put("stats", stats);

            File metaFile = new File(datasetDir, "meta.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, meta);

            log.info("Preloaded dataset: id={}, name={}, source={}, samples={}",
                    id, displayName, source, totalSamples);
            return info;

        } catch (Exception e) {
            log.error("Failed to preload dataset from {}: {}", source, e.getMessage(), e);
            deleteDirectoryQuietly(datasetDir);
            throw new RuntimeException("Failed to preload dataset: " + e.getMessage(), e);
        }
    }

    private String descriptionForBenchmark(String key) {
        switch (key) {
            case "arc_easy": return "AI2 Reasoning Challenge (Easy) - elementary science questions";
            case "arc_challenge": return "AI2 Reasoning Challenge (Challenge) - harder science questions";
            case "mmlu": return "Massive Multitask Language Understanding - 57 subjects";
            case "hellaswag": return "HellaSwag - commonsense NLI (sentence completion)";
            case "gsm8k": return "Grade School Math 8K - math word problems";
            case "truthfulqa": return "TruthfulQA - measures truthfulness of model answers";
            case "winogrande": return "Winogrande - commonsense coreference resolution";
            default: return key;
        }
    }

    // ==================== Internal Helpers ====================

    private DatasetInfo readMetaJson(File metaFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = objectMapper.readValue(metaFile, Map.class);

        DatasetStats stats = null;
        Object statsObj = meta.get("stats");
        if (statsObj != null) {
            stats = objectMapper.convertValue(statsObj, DatasetStats.class);
        }

        return DatasetInfo.builder()
                .id((String) meta.get("id"))
                .name((String) meta.get("name"))
                .format((String) meta.get("format"))
                .task((String) meta.get("task"))
                .sizeBytes(meta.get("sizeBytes") instanceof Number ? ((Number) meta.get("sizeBytes")).longValue() : 0)
                .totalSamples(meta.get("totalSamples") instanceof Number ? ((Number) meta.get("totalSamples")).longValue() : 0)
                .createdAt((String) meta.get("createdAt"))
                .filePath((String) meta.get("filePath"))
                .stats(stats)
                .build();
    }

    private long countLines(File file) throws IOException {
        try (Stream<String> lines = Files.lines(file.toPath())) {
            return lines.count();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> previewJsonl(File file, int maxRows) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxRows) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> row = objectMapper.readValue(line, Map.class);
                    result.add(row);
                    count++;
                } catch (Exception e) {
                    // Skip malformed lines
                    log.debug("Skipping malformed JSONL line: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> previewDelimited(File file, int maxRows, String delimiter) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return result;
            }

            String[] headers = headerLine.split(delimiter, -1);
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxRows) {
                String[] values = line.split(delimiter, -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i].trim(), i < values.length ? values[i].trim() : "");
                }
                result.add(row);
                count++;
            }
        }
        return result;
    }

    private List<Map<String, Object>> previewRawLines(File file, int maxRows) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("line", count + 1);
                row.put("content", line);
                result.add(row);
                count++;
            }
        }
        return result;
    }

    private void deleteDirectoryQuietly(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        deleteDirectoryQuietly(f);
                    } else {
                        f.delete();
                    }
                }
            }
            dir.delete();
        } catch (Exception e) {
            log.warn("Failed to delete directory {}: {}", dir.getAbsolutePath(), e.getMessage());
        }
    }
}
