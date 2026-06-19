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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Cross-session performance data store.
 * Persists to ~/.kompile/perf-data.json with aggregated per-model/per-task summaries.
 */
public class ModelPerformanceStore {

    private static final Path STORE_FILE = Path.of(System.getProperty("user.home"),
            ".kompile", "perf-data.json");

    private final ObjectMapper mapper;
    private final List<ModelPerformanceRecord> records;
    private final int maxRecordAge;
    private final int maxRecords;

    /** Number of records added since last flush to disk. */
    private volatile int dirtyCount;
    /** Auto-flush threshold: save after this many new records. 0 = disabled. */
    private volatile int autoFlushInterval = 5;

    /** Tracks whether loadFromFile() has been called — enables deferred loading. */
    private volatile boolean loaded;
    /** The file path to load from when deferred loading triggers. */
    private volatile Path deferredLoadPath;

    public ModelPerformanceStore(int maxRecordAge, int maxRecords) {
        this.mapper = JsonUtils.standardMapper();
        this.records = new CopyOnWriteArrayList<>();
        this.maxRecordAge = maxRecordAge;
        this.maxRecords = maxRecords;
    }

    public ModelPerformanceStore() {
        this(90, 10_000);
    }

    /**
     * Ensure deferred file load has completed before reading records.
     */
    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            Path file = deferredLoadPath != null ? deferredLoadPath : STORE_FILE;
            doLoadFromFile(file);
            loaded = true;
        }
    }

    /**
     * Set auto-flush interval. After this many new records, the store
     * automatically persists to disk. Set to 0 to disable auto-flush.
     */
    public void setAutoFlushInterval(int interval) {
        this.autoFlushInterval = Math.max(0, interval);
    }

    public int getAutoFlushInterval() {
        return autoFlushInterval;
    }

    /**
     * Record a new performance observation.
     */
    public void record(ModelPerformanceRecord rec) {
        ensureLoaded();
        records.add(rec);
        dirtyCount++;
        if (records.size() > maxRecords) {
            doPrune();
        }
        autoFlushIfNeeded();
    }

    /**
     * Flush to disk if dirty and auto-flush threshold reached.
     */
    private void autoFlushIfNeeded() {
        if (autoFlushInterval > 0 && dirtyCount >= autoFlushInterval) {
            flush();
        }
    }

    /**
     * Persist to disk if any records have been added since last flush.
     */
    public void flush() {
        if (dirtyCount > 0) {
            ensureLoaded();
            saveToFile();
            dirtyCount = 0;
        }
    }

    /**
     * Whether there are unsaved records.
     */
    public boolean isDirty() {
        return dirtyCount > 0;
    }

    /**
     * Find the most recent record matching the given session and agent.
     * Returns null if not found.
     */
    public ModelPerformanceRecord findLatestRecord(String sessionId, String agentName) {
        ensureLoaded();
        for (int i = records.size() - 1; i >= 0; i--) {
            ModelPerformanceRecord r = records.get(i);
            if (sessionId != null && sessionId.equals(r.getSessionId())
                    && (agentName == null || agentName.equals(r.getAgentName()))) {
                return r;
            }
        }
        return null;
    }

    /**
     * Find the most recent record matching the given session, agent, and model.
     * Returns null if not found.
     */
    public ModelPerformanceRecord findLatestRecord(String sessionId, String agentName, String model) {
        ensureLoaded();
        for (int i = records.size() - 1; i >= 0; i--) {
            ModelPerformanceRecord r = records.get(i);
            if (sessionId != null && sessionId.equals(r.getSessionId())
                    && (agentName == null || agentName.equals(r.getAgentName()))
                    && (model == null || model.equals(r.getModel()))) {
                return r;
            }
        }
        return null;
    }

    /**
     * Get the best-performing model for a given task type, optionally filtered by provider.
     * Returns null if no data exists.
     */
    public String getBestModelForTask(String taskType, String provider) {
        ensureLoaded();
        Map<String, List<Float>> scoresByModel = new HashMap<>();

        for (ModelPerformanceRecord r : records) {
            if (r.getQualityScore() <= 0) continue;
            if (!taskType.equals(r.getTaskType())) continue;
            if (provider != null && !provider.equals(r.getProvider())) continue;

            scoresByModel.computeIfAbsent(r.getModel(), k -> new ArrayList<>())
                    .add(r.getQualityScore());
        }

        if (scoresByModel.isEmpty()) return null;

        return scoresByModel.entrySet().stream()
                .max(Comparator.comparingDouble(e -> avg(e.getValue())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Get the most recent N quality scores for a given model and agent.
     */
    public List<Float> getRecentScores(String model, String agentName, int n) {
        ensureLoaded();
        List<Float> scores = new ArrayList<>();
        // Iterate in reverse to get most recent first
        for (int i = records.size() - 1; i >= 0 && scores.size() < n; i--) {
            ModelPerformanceRecord r = records.get(i);
            if (r.getQualityScore() <= 0) continue;
            if (model.equals(r.getModel()) && agentName.equals(r.getAgentName())) {
                scores.add(r.getQualityScore());
            }
        }
        Collections.reverse(scores);
        return scores;
    }

    /**
     * Get the last N composite scores for a model across all agent names.
     * Useful for sparkline rendering.
     *
     * @param model model name
     * @param n     max number of scores
     * @return scores in chronological order (oldest first)
     */
    public double[] getModelScoreTrend(String model, int n) {
        ensureLoaded();
        List<Float> scores = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && scores.size() < n; i--) {
            ModelPerformanceRecord r = records.get(i);
            if (r.getQualityScore() <= 0) continue;
            if (model.equals(r.getModel())) {
                scores.add(r.getQualityScore());
            }
        }
        Collections.reverse(scores);
        double[] result = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) result[i] = scores.get(i);
        return result;
    }

    /**
     * Get a per-task-type score matrix for a given model.
     * Returns a map of taskType → average score for that model.
     */
    public Map<String, Float> getModelTaskScores(String model, int maxDaysAgo) {
        ensureLoaded();
        Instant cutoff = Instant.now().minus(maxDaysAgo, ChronoUnit.DAYS);
        Map<String, List<Float>> byTask = new LinkedHashMap<>();
        for (ModelPerformanceRecord r : records) {
            if (r.getQualityScore() <= 0) continue;
            if (!model.equals(r.getModel())) continue;
            if (r.getTimestamp() != null && r.getTimestamp().isBefore(cutoff)) continue;
            byTask.computeIfAbsent(
                    r.getTaskType() != null ? r.getTaskType() : "general",
                    k -> new ArrayList<>()
            ).add(r.getQualityScore());
        }
        Map<String, Float> result = new LinkedHashMap<>();
        byTask.forEach((task, scores) -> result.put(task, avg(scores)));
        return result;
    }

    /**
     * Get average judge dimensions for a model (correctness, completeness,
     * design_quality, thinking_coherence). Returns -1 for dimensions with no data.
     */
    public float[] getModelJudgeDimensions(String model, int maxDaysAgo) {
        ensureLoaded();
        Instant cutoff = Instant.now().minus(maxDaysAgo, ChronoUnit.DAYS);
        List<Float> correctness = new ArrayList<>();
        List<Float> completeness = new ArrayList<>();
        List<Float> designQuality = new ArrayList<>();
        List<Float> thinkingCoherence = new ArrayList<>();

        for (ModelPerformanceRecord r : records) {
            if (!model.equals(r.getModel())) continue;
            if (r.getTimestamp() != null && r.getTimestamp().isBefore(cutoff)) continue;
            if (r.getJudgeCorrectness() > 0) correctness.add(r.getJudgeCorrectness());
            if (r.getJudgeCompleteness() > 0) completeness.add(r.getJudgeCompleteness());
            if (r.getJudgeDesignQuality() > 0) designQuality.add(r.getJudgeDesignQuality());
            if (r.getJudgeThinkingScore() > 0) thinkingCoherence.add(r.getJudgeThinkingScore());
        }

        return new float[]{
                avg(correctness),
                avg(completeness),
                avg(designQuality),
                avg(thinkingCoherence)
        };
    }

    /**
     * Get all distinct model names observed.
     */
    public Set<String> getModels() {
        ensureLoaded();
        return records.stream()
                .map(ModelPerformanceRecord::getModel)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Get task-level summary for all models, sorted by score descending.
     */
    public List<TaskModelSummary> getLeaderboard(String taskType, int maxDaysAgo) {
        ensureLoaded();
        Instant cutoff = Instant.now().minus(maxDaysAgo, ChronoUnit.DAYS);
        Map<String, List<ModelPerformanceRecord>> byModel = new HashMap<>();

        for (ModelPerformanceRecord r : records) {
            if (r.getQualityScore() <= 0) continue;
            if (taskType != null && !taskType.equals(r.getTaskType())) continue;
            if (r.getTimestamp() != null && r.getTimestamp().isBefore(cutoff)) continue;

            byModel.computeIfAbsent(r.getModel(), k -> new ArrayList<>()).add(r);
        }

        return byModel.entrySet().stream()
                .map(e -> {
                    List<ModelPerformanceRecord> recs = e.getValue();
                    float avgScore = avg(recs.stream()
                            .map(ModelPerformanceRecord::getQualityScore)
                            .collect(Collectors.toList()));
                    double avgLatency = recs.stream()
                            .mapToLong(ModelPerformanceRecord::getLatencyMs)
                            .average().orElse(0);
                    int escapes = (int) recs.stream()
                            .filter(ModelPerformanceRecord::isHadEscape).count();
                    float escapeRate = recs.isEmpty() ? 0f : (float) escapes / recs.size();
                    return new TaskModelSummary(e.getKey(), avgScore, (long) avgLatency,
                            recs.size(), escapes, escapeRate);
                })
                .sorted(Comparator.comparingDouble(TaskModelSummary::avgScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get all distinct task types observed.
     */
    public Set<String> getTaskTypes() {
        ensureLoaded();
        return records.stream()
                .map(ModelPerformanceRecord::getTaskType)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Get recommendations: best model per task type.
     */
    public Map<String, String> getRecommendations() {
        Map<String, String> recommendations = new LinkedHashMap<>();
        for (String taskType : getTaskTypes()) {
            String best = getBestModelForTask(taskType, null);
            if (best != null) {
                recommendations.put(taskType, best);
            }
        }
        return recommendations;
    }

    /**
     * Remove records older than maxRecordAge days or trim to maxRecords.
     */
    public void pruneOldRecords() {
        ensureLoaded();
        doPrune();
    }

    /** Internal prune — safe to call during load (no ensureLoaded guard). */
    private void doPrune() {
        Instant cutoff = Instant.now().minus(maxRecordAge, ChronoUnit.DAYS);
        records.removeIf(r -> r.getTimestamp() != null && r.getTimestamp().isBefore(cutoff));

        // If still over limit, remove oldest
        while (records.size() > maxRecords) {
            records.remove(0);
        }
    }

    public List<ModelPerformanceRecord> getRecords() {
        ensureLoaded();
        return Collections.unmodifiableList(records);
    }

    public int size() {
        ensureLoaded();
        return records.size();
    }

    /**
     * Mark that records should be loaded from the default file on first access.
     * Actual deserialization is deferred until data is needed.
     */
    public void loadFromFile() {
        loadFromFile(STORE_FILE);
    }

    /**
     * Mark that records should be loaded from the given file on first access.
     * Actual deserialization is deferred until data is needed.
     */
    public void loadFromFile(Path file) {
        this.deferredLoadPath = file;
        this.loaded = false;
    }

    /**
     * Perform the actual file deserialization. Called once by ensureLoaded().
     * Uses doPrune() directly to avoid re-entering ensureLoaded().
     */
    private void doLoadFromFile(Path file) {
        if (!Files.exists(file)) return;
        try {
            StoreData data = mapper.readValue(file.toFile(), StoreData.class);
            if (data.records != null) {
                records.addAll(data.records);
            }
            doPrune();
        } catch (IOException e) {
            System.err.println("Warning: Failed to load performance data: " + e.getMessage());
        }
    }

    /**
     * Save to disk.
     */
    public void saveToFile() {
        saveToFile(STORE_FILE);
    }

    public void saveToFile(Path file) {
        try {
            Files.createDirectories(file.getParent());
            StoreData data = new StoreData();
            data.records = new ArrayList<>(records);
            data.savedAt = Instant.now().toString();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            System.err.println("Warning: Failed to save performance data: " + e.getMessage());
        }
    }

    /**
     * Clear all records, optionally for a specific model only.
     */
    public void clear(String model) {
        ensureLoaded();
        if (model == null) {
            records.clear();
        } else {
            records.removeIf(r -> model.equals(r.getModel()));
        }
    }

    public static Path getStoreFilePath() {
        return STORE_FILE;
    }

    private static float avg(List<Float> values) {
        if (values == null || values.isEmpty()) return 0;
        float sum = 0;
        for (float v : values) sum += v;
        return sum / values.size();
    }

    // Serialization wrapper
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StoreData {
        @JsonProperty public List<ModelPerformanceRecord> records;
        @JsonProperty public String savedAt;
    }

    /**
     * Get outcome statistics for a model over the given window.
     * Returns counts per TaskOutcome value.
     */
    public Map<String, Integer> getOutcomeStats(String model, int maxDaysAgo) {
        ensureLoaded();
        Instant cutoff = Instant.now().minus(maxDaysAgo, ChronoUnit.DAYS);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ModelPerformanceRecord r : records) {
            if (r.getTimestamp() != null && r.getTimestamp().isBefore(cutoff)) continue;
            if (model != null && !model.equals(r.getModel())) continue;
            String outcome = r.getTaskOutcome();
            if (outcome == null || outcome.isEmpty()) outcome = "UNKNOWN";
            counts.merge(outcome, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Get the success rate (COMPLETED / total with known outcomes) for a model.
     * Returns -1 if no outcome data exists.
     */
    public float getSuccessRate(String model, int maxDaysAgo) {
        Map<String, Integer> stats = getOutcomeStats(model, maxDaysAgo);
        int total = stats.values().stream().mapToInt(Integer::intValue).sum();
        int unknown = stats.getOrDefault("UNKNOWN", 0);
        int withOutcome = total - unknown;
        if (withOutcome == 0) return -1;
        int completed = stats.getOrDefault("COMPLETED", 0);
        return (float) completed / withOutcome;
    }

    /**
     * Get all records for a specific session.
     */
    public List<ModelPerformanceRecord> getSessionRecords(String sessionId) {
        ensureLoaded();
        return records.stream()
                .filter(r -> sessionId.equals(r.getSessionId()))
                .toList();
    }

    // Summary record for leaderboard display
    public record TaskModelSummary(String model, float avgScore, long avgLatencyMs,
                                    int callCount, int escapeCount, float escapeRate) {}
}
