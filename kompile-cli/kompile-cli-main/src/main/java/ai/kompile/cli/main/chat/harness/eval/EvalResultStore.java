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

package ai.kompile.cli.main.chat.harness.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists {@link EvalRunResult} records to ~/.kompile/eval-results.json.
 * Supports querying past runs for trend analysis and comparison.
 */
public class EvalResultStore {

    private static final Path DEFAULT_STORE_FILE = Path.of(System.getProperty("user.home"),
            ".kompile", "eval-results.json");

    private static final int MAX_RUNS = 500;
    private static final int MAX_AGE_DAYS = 90;

    private final ObjectMapper mapper;
    private final Path storeFile;
    private List<EvalRunResult> runs;

    public EvalResultStore() {
        this(DEFAULT_STORE_FILE);
    }

    /** Constructor with custom store file path (for testing). */
    public EvalResultStore(Path storeFile) {
        this.storeFile = storeFile;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.runs = new ArrayList<>();
    }

    /** Load existing results from disk. */
    public void load() {
        try {
            if (Files.exists(storeFile)) {
                StoreData data = mapper.readValue(storeFile.toFile(), StoreData.class);
                if (data.runs != null) {
                    this.runs = new ArrayList<>(data.runs);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load eval results: " + e.getMessage());
            this.runs = new ArrayList<>();
        }
        prune();
    }

    /** Save results to disk. */
    public void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            StoreData data = new StoreData();
            data.runs = runs;
            data.savedAt = Instant.now().toString();
            mapper.writerWithDefaultPrettyPrinter().writeValue(storeFile.toFile(), data);
        } catch (IOException e) {
            System.err.println("Warning: Could not save eval results: " + e.getMessage());
        }
    }

    /** Record a new eval run result. */
    public void record(EvalRunResult result) {
        runs.add(result);
        prune();
        save();
    }

    /** Get the N most recent runs, optionally filtered by suite name. */
    public List<EvalRunResult> getRecentRuns(String suiteName, int limit) {
        List<EvalRunResult> filtered = runs.stream()
                .filter(r -> suiteName == null || suiteName.equals(r.getSuiteName()))
                .toList();
        int start = Math.max(0, filtered.size() - limit);
        return filtered.subList(start, filtered.size());
    }

    /** Get the latest run for a specific suite. */
    public EvalRunResult getLatestRun(String suiteName) {
        List<EvalRunResult> recent = getRecentRuns(suiteName, 1);
        return recent.isEmpty() ? null : recent.get(0);
    }

    /** Get pass rate trend for a suite over the last N runs. */
    public double[] getPassRateTrend(String suiteName, int buckets) {
        List<EvalRunResult> suiteRuns = runs.stream()
                .filter(r -> suiteName.equals(r.getSuiteName()))
                .toList();
        if (suiteRuns.isEmpty()) return new double[0];

        int n = Math.min(buckets, suiteRuns.size());
        double[] trend = new double[n];
        int start = suiteRuns.size() - n;
        for (int i = 0; i < n; i++) {
            trend[i] = suiteRuns.get(start + i).getPassRate();
        }
        return trend;
    }

    /** Get all distinct suite names. */
    public List<String> getSuiteNames() {
        return runs.stream()
                .map(EvalRunResult::getSuiteName)
                .distinct()
                .toList();
    }

    /** Get a specific run by its run ID. */
    public EvalRunResult getRunById(String runId) {
        if (runId == null) return null;
        return runs.stream()
                .filter(r -> runId.equals(r.getRunId()))
                .findFirst()
                .orElse(null);
    }

    /** Get runs filtered by suite file path. */
    public List<EvalRunResult> getRunsByFile(String suiteFile) {
        if (suiteFile == null) return List.of();
        return runs.stream()
                .filter(r -> suiteFile.equals(r.getSuiteFile()))
                .toList();
    }

    /** Delete a run by its run ID. Returns true if found and removed. */
    public boolean deleteRun(String runId) {
        if (runId == null) return false;
        boolean removed = runs.removeIf(r -> runId.equals(r.getRunId()));
        if (removed) save();
        return removed;
    }

    public int size() {
        return runs.size();
    }

    private void prune() {
        Instant cutoff = Instant.now().minus(MAX_AGE_DAYS, ChronoUnit.DAYS);
        runs.removeIf(r -> r.getStartTime() != null && r.getStartTime().isBefore(cutoff));
        while (runs.size() > MAX_RUNS) {
            runs.remove(0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StoreData {
        @JsonProperty public List<EvalRunResult> runs;
        @JsonProperty public String savedAt;
    }
}
