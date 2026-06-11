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

package ai.kompile.cli.main.chat.tools;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Ambient memory gardening service. Proactively maintains the memory graph
 * by consolidating duplicates, pruning stale entries, detecting conflicts,
 * and strengthening connections. Inspired by jcode's ambient mode.
 *
 * Runs as a background daemon that wakes periodically (or on-demand) to:
 * 1. Garden: consolidate duplicates, prune stale memories, verify facts
 * 2. Scout: analyze recent sessions and git history for extractable knowledge
 * 3. Report: surface findings for user/agent review
 */
public class AmbientMemoryGardener {

    private final Path memoryDir;
    private final ScheduledExecutorService executor;
    private volatile boolean running = false;
    private final List<GardenReport> reports = new CopyOnWriteArrayList<>();
    private static final long DEFAULT_INTERVAL_MINUTES = 30;
    private static final int MAX_REPORTS = 50;
    private static final int STALE_DAYS = 90;

    public AmbientMemoryGardener(Path memoryDir) {
        this.memoryDir = memoryDir;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ambient-memory-gardener");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the ambient gardening loop.
     */
    public void start() {
        start(DEFAULT_INTERVAL_MINUTES);
    }

    public void start(long intervalMinutes) {
        if (running) return;
        running = true;
        executor.scheduleAtFixedRate(this::gardenCycle,
            5, intervalMinutes, TimeUnit.MINUTES); // First run after 5 min
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    /**
     * Run a single garden cycle on-demand.
     */
    public GardenReport gardenNow() {
        return gardenCycle();
    }

    /**
     * Get recent garden reports.
     */
    public List<GardenReport> getReports(int limit) {
        int size = reports.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(reports.subList(from, size));
    }

    public boolean isRunning() { return running; }

    // ── Garden Cycle ──────────────────────────────────────────────────────

    private GardenReport gardenCycle() {
        GardenReport report = new GardenReport(Instant.now());
        try {
            if (!Files.isDirectory(memoryDir)) {
                report.addNote("Memory directory does not exist: " + memoryDir);
                return report;
            }

            List<MemoryFile> memoryFiles = scanMemoryFiles();
            report.totalFiles = memoryFiles.size();

            // 1. Detect duplicates (similar names or content)
            detectDuplicates(memoryFiles, report);

            // 2. Find stale memories (not modified in STALE_DAYS)
            findStaleMemories(memoryFiles, report);

            // 3. Detect conflicts (memories with contradictory content)
            detectConflicts(memoryFiles, report);

            // 4. Check MEMORY.md index integrity
            checkIndexIntegrity(memoryFiles, report);

            // 5. Suggest consolidations
            suggestConsolidations(memoryFiles, report);

        } catch (Exception e) {
            report.addNote("Error during garden cycle: " + e.getMessage());
        }

        reports.add(report);
        while (reports.size() > MAX_REPORTS) reports.remove(0);
        return report;
    }

    private List<MemoryFile> scanMemoryFiles() throws IOException {
        List<MemoryFile> files = new ArrayList<>();
        if (!Files.isDirectory(memoryDir)) return files;

        try (var stream = Files.walk(memoryDir, 3)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          String type = "unknown";
                          String name = p.getFileName().toString();

                          if (content.startsWith("---")) {
                              int end = content.indexOf("---", 3);
                              if (end > 0) {
                                  String fm = content.substring(3, end);
                                  for (String line : fm.split("\n")) {
                                      line = line.trim();
                                      if (line.startsWith("type:")) type = line.substring(5).trim();
                                      if (line.startsWith("name:")) name = line.substring(5).trim();
                                  }
                              }
                          }

                          long lastModified = Files.getLastModifiedTime(p).toMillis();
                          files.add(new MemoryFile(p, name, type, content, lastModified));
                      } catch (IOException ignored) {}
                  });
        }
        return files;
    }

    private void detectDuplicates(List<MemoryFile> files, GardenReport report) {
        Map<String, List<MemoryFile>> byName = files.stream()
            .collect(Collectors.groupingBy(f -> f.name.toLowerCase()));

        for (Map.Entry<String, List<MemoryFile>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> paths = entry.getValue().stream()
                    .map(f -> f.path.getFileName().toString())
                    .collect(Collectors.toList());
                report.addDuplicate("Possible duplicates for '" + entry.getKey() + "': " + paths);
            }
        }
    }

    private void findStaleMemories(List<MemoryFile> files, GardenReport report) {
        Instant threshold = Instant.now().minus(STALE_DAYS, ChronoUnit.DAYS);
        for (MemoryFile f : files) {
            Instant modified = Instant.ofEpochMilli(f.lastModified);
            if (modified.isBefore(threshold)) {
                long daysOld = ChronoUnit.DAYS.between(modified, Instant.now());
                report.addStale(f.path.getFileName().toString() + " (" + daysOld + " days old, type: " + f.type + ")");
            }
        }
    }

    private void detectConflicts(List<MemoryFile> files, GardenReport report) {
        // Group by type and look for potential conflicts
        Map<String, List<MemoryFile>> byType = files.stream()
            .collect(Collectors.groupingBy(f -> f.type));

        for (Map.Entry<String, List<MemoryFile>> entry : byType.entrySet()) {
            List<MemoryFile> typeFiles = entry.getValue();
            for (int i = 0; i < typeFiles.size(); i++) {
                for (int j = i + 1; j < typeFiles.size(); j++) {
                    // Simple conflict detection: same type, overlapping subject words
                    Set<String> words1 = extractKeyWords(typeFiles.get(i).content);
                    Set<String> words2 = extractKeyWords(typeFiles.get(j).content);
                    Set<String> overlap = new HashSet<>(words1);
                    overlap.retainAll(words2);
                    double jaccard = (double) overlap.size() /
                        (words1.size() + words2.size() - overlap.size() + 1);
                    if (jaccard > 0.5 && !typeFiles.get(i).name.equals(typeFiles.get(j).name)) {
                        report.addConflict("Potential overlap between "
                            + typeFiles.get(i).path.getFileName() + " and "
                            + typeFiles.get(j).path.getFileName()
                            + " (similarity: " + String.format("%.0f%%", jaccard * 100) + ")");
                    }
                }
            }
        }
    }

    private void checkIndexIntegrity(List<MemoryFile> files, GardenReport report) {
        Path indexFile = memoryDir.resolve("MEMORY.md");
        if (!Files.exists(indexFile)) {
            report.addNote("MEMORY.md index file not found");
            return;
        }

        try {
            String indexContent = Files.readString(indexFile);
            Set<String> indexedFiles = new HashSet<>();
            for (String line : indexContent.split("\n")) {
                // Extract file references like [Title](filename.md)
                int start = line.indexOf("](");
                int end = line.indexOf(")", start + 2);
                if (start > 0 && end > start) {
                    indexedFiles.add(line.substring(start + 2, end));
                }
            }

            // Check for unindexed files
            for (MemoryFile f : files) {
                String fileName = f.path.getFileName().toString();
                if (!indexedFiles.contains(fileName)) {
                    report.addNote("Memory file not indexed in MEMORY.md: " + fileName);
                }
            }

            // Check for broken references
            for (String ref : indexedFiles) {
                boolean found = files.stream().anyMatch(f -> f.path.getFileName().toString().equals(ref));
                if (!found) {
                    report.addNote("Broken reference in MEMORY.md: " + ref + " (file not found)");
                }
            }
        } catch (IOException e) {
            report.addNote("Could not read MEMORY.md: " + e.getMessage());
        }
    }

    private void suggestConsolidations(List<MemoryFile> files, GardenReport report) {
        // Suggest merging small files of the same type
        Map<String, List<MemoryFile>> byType = files.stream()
            .collect(Collectors.groupingBy(f -> f.type));

        for (Map.Entry<String, List<MemoryFile>> entry : byType.entrySet()) {
            List<MemoryFile> smallFiles = entry.getValue().stream()
                .filter(f -> f.content.length() < 200)
                .collect(Collectors.toList());
            if (smallFiles.size() >= 3) {
                report.addConsolidation("Consider merging " + smallFiles.size()
                    + " small " + entry.getKey() + " memories into fewer files");
            }
        }
    }

    private Set<String> extractKeyWords(String text) {
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
            .filter(w -> w.length() >= 4)
            .collect(Collectors.toSet());
    }

    // ── Data Types ────────────────────────────────────────────────────────

    public static class MemoryFile {
        public final Path path;
        public final String name;
        public final String type;
        public final String content;
        public final long lastModified;

        public MemoryFile(Path path, String name, String type, String content, long lastModified) {
            this.path = path;
            this.name = name;
            this.type = type;
            this.content = content;
            this.lastModified = lastModified;
        }
    }

    public static class GardenReport {
        public final Instant timestamp;
        public int totalFiles;
        public final List<String> duplicates = new ArrayList<>();
        public final List<String> staleEntries = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public final List<String> consolidations = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();

        public GardenReport(Instant timestamp) { this.timestamp = timestamp; }

        void addDuplicate(String msg) { duplicates.add(msg); }
        void addStale(String msg) { staleEntries.add(msg); }
        void addConflict(String msg) { conflicts.add(msg); }
        void addConsolidation(String msg) { consolidations.add(msg); }
        void addNote(String msg) { notes.add(msg); }

        public boolean hasIssues() {
            return !duplicates.isEmpty() || !staleEntries.isEmpty()
                || !conflicts.isEmpty() || !consolidations.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Garden Report ").append(timestamp).append(" ===\n");
            sb.append("Total memory files: ").append(totalFiles).append("\n\n");

            if (!duplicates.isEmpty()) {
                sb.append("DUPLICATES (").append(duplicates.size()).append("):\n");
                duplicates.forEach(d -> sb.append("  * ").append(d).append("\n"));
                sb.append("\n");
            }
            if (!staleEntries.isEmpty()) {
                sb.append("STALE (").append(staleEntries.size()).append("):\n");
                staleEntries.forEach(s -> sb.append("  o ").append(s).append("\n"));
                sb.append("\n");
            }
            if (!conflicts.isEmpty()) {
                sb.append("CONFLICTS (").append(conflicts.size()).append("):\n");
                conflicts.forEach(c -> sb.append("  x ").append(c).append("\n"));
                sb.append("\n");
            }
            if (!consolidations.isEmpty()) {
                sb.append("CONSOLIDATION SUGGESTIONS:\n");
                consolidations.forEach(c -> sb.append("  -> ").append(c).append("\n"));
                sb.append("\n");
            }
            if (!notes.isEmpty()) {
                sb.append("NOTES:\n");
                notes.forEach(n -> sb.append("  - ").append(n).append("\n"));
            }
            if (!hasIssues() && notes.isEmpty()) {
                sb.append("Memory garden is clean -- no issues found.\n");
            }
            return sb.toString();
        }
    }
}
