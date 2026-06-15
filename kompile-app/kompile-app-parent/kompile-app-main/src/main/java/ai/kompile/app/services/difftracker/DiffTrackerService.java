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

package ai.kompile.app.services.difftracker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent per-session diff tracker for agent file edits.
 *
 * Diffs are stored as JSON files organized by session:
 * {@code ~/.kompile/agent-state/diffs/<sessionId>/diff-<id>.json}
 *
 * This ensures diffs survive context compaction (which loses tool call
 * history from the conversation) because agents can re-query their session's
 * diffs at any time. Compaction does NOT create a new MCP transport session,
 * so the agent's sessionId remains the same — it just needs to call
 * {@code list_diffs} again to recover awareness of prior changes.
 *
 * Cross-session queries are also supported (omit sessionId) for full audit.
 */
@Service
public class DiffTrackerService {

    private static final Logger log = LoggerFactory.getLogger(DiffTrackerService.class);

    private final Path baseDir;
    private final ObjectMapper mapper;
    // sessionId -> (diffId -> DiffRecord)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, DiffRecord>> sessionDiffs = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    private static final String GLOBAL_SESSION = "_global";

    public DiffTrackerService() {
        this.baseDir = Paths.get(System.getProperty("user.home"), ".kompile", "agent-state", "diffs");
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(baseDir);
            loadAllSessions();
            int total = sessionDiffs.values().stream().mapToInt(Map::size).sum();
            log.info("DiffTrackerService initialized with {} diffs across {} sessions from {}",
                    total, sessionDiffs.size(), baseDir);
        } catch (IOException e) {
            log.error("Failed to initialize diff storage at {}: {}", baseDir, e.getMessage(), e);
        }
    }

    // ── Record a diff ────────────────────────────────────────────────────────

    public DiffRecord record(String sessionId, String filePath, String beforeContent,
                             String afterContent, String unifiedDiff, String agentId,
                             String taskId, String description) {
        String sid = normalizeSession(sessionId);
        long id = idCounter.incrementAndGet();
        DiffRecord rec = new DiffRecord();
        rec.setId(id);
        rec.setSessionId(sid);
        rec.setFilePath(filePath);
        rec.setBeforeContent(beforeContent);
        rec.setAfterContent(afterContent);
        rec.setUnifiedDiff(unifiedDiff != null ? unifiedDiff : computeSimpleDiff(beforeContent, afterContent));
        rec.setAgentId(agentId);
        rec.setTaskId(taskId);
        rec.setDescription(description);
        rec.setTimestamp(Instant.now().toString());
        rec.setLinesAdded(countLines(rec.getUnifiedDiff(), "+"));
        rec.setLinesRemoved(countLines(rec.getUnifiedDiff(), "-"));

        sessionDiffs.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(id, rec);
        persist(sid, rec);
        return rec;
    }

    public DiffRecord get(long id) {
        for (ConcurrentHashMap<Long, DiffRecord> map : sessionDiffs.values()) {
            DiffRecord rec = map.get(id);
            if (rec != null) return rec;
        }
        return null;
    }

    public List<DiffRecord> listForSession(String sessionId, String filePathFilter,
                                            String agentIdFilter, String taskIdFilter,
                                            Integer limit) {
        String sid = normalizeSession(sessionId);
        ConcurrentHashMap<Long, DiffRecord> map = sessionDiffs.get(sid);
        if (map == null) return Collections.emptyList();
        int max = limit != null && limit > 0 ? limit : 50;
        return map.values().stream()
                .filter(d -> filePathFilter == null || d.getFilePath().contains(filePathFilter))
                .filter(d -> agentIdFilter == null || agentIdFilter.equals(d.getAgentId()))
                .filter(d -> taskIdFilter == null || taskIdFilter.equals(d.getTaskId()))
                .sorted(Comparator.comparingLong(DiffRecord::getId).reversed())
                .limit(max)
                .collect(Collectors.toList());
    }

    public List<DiffRecord> listAll(String filePathFilter, String agentIdFilter,
                                     String taskIdFilter, Integer limit) {
        int max = limit != null && limit > 0 ? limit : 50;
        return sessionDiffs.values().stream()
                .flatMap(m -> m.values().stream())
                .filter(d -> filePathFilter == null || d.getFilePath().contains(filePathFilter))
                .filter(d -> agentIdFilter == null || agentIdFilter.equals(d.getAgentId()))
                .filter(d -> taskIdFilter == null || taskIdFilter.equals(d.getTaskId()))
                .sorted(Comparator.comparingLong(DiffRecord::getId).reversed())
                .limit(max)
                .collect(Collectors.toList());
    }

    public List<DiffRecord> listByFile(String filePath, String sessionId) {
        Stream<DiffRecord> stream;
        if (sessionId != null && !sessionId.isBlank()) {
            ConcurrentHashMap<Long, DiffRecord> map = sessionDiffs.get(normalizeSession(sessionId));
            stream = map != null ? map.values().stream() : Stream.empty();
        } else {
            stream = sessionDiffs.values().stream().flatMap(m -> m.values().stream());
        }
        return stream
                .filter(d -> filePath.equals(d.getFilePath()))
                .sorted(Comparator.comparingLong(DiffRecord::getId))
                .collect(Collectors.toList());
    }

    public Map<String, Object> summary(String sessionId) {
        Stream<DiffRecord> stream;
        if (sessionId != null && !sessionId.isBlank()) {
            ConcurrentHashMap<Long, DiffRecord> map = sessionDiffs.get(normalizeSession(sessionId));
            stream = map != null ? map.values().stream() : Stream.empty();
        } else {
            stream = sessionDiffs.values().stream().flatMap(m -> m.values().stream());
        }
        List<DiffRecord> all = stream.collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDiffs", all.size());
        result.put("sessionCount", sessionDiffs.size());
        if (sessionId != null) result.put("sessionId", sessionId);

        Map<String, Long> byFile = all.stream()
                .collect(Collectors.groupingBy(DiffRecord::getFilePath, Collectors.counting()));
        result.put("fileCount", byFile.size());
        result.put("byFile", byFile);

        Map<String, Long> byAgent = all.stream()
                .filter(d -> d.getAgentId() != null)
                .collect(Collectors.groupingBy(DiffRecord::getAgentId, Collectors.counting()));
        result.put("byAgent", byAgent);

        Map<String, Long> bySession = all.stream()
                .collect(Collectors.groupingBy(d -> d.getSessionId() != null ? d.getSessionId() : GLOBAL_SESSION,
                        Collectors.counting()));
        result.put("bySession", bySession);

        long totalAdded = all.stream().mapToLong(DiffRecord::getLinesAdded).sum();
        long totalRemoved = all.stream().mapToLong(DiffRecord::getLinesRemoved).sum();
        result.put("totalLinesAdded", totalAdded);
        result.put("totalLinesRemoved", totalRemoved);

        return result;
    }

    public boolean delete(long id) {
        for (Map.Entry<String, ConcurrentHashMap<Long, DiffRecord>> entry : sessionDiffs.entrySet()) {
            DiffRecord removed = entry.getValue().remove(id);
            if (removed != null) {
                try {
                    Files.deleteIfExists(diffFile(entry.getKey(), id));
                } catch (IOException e) {
                    log.warn("Failed to delete diff file for id {}: {}", id, e.getMessage());
                }
                return true;
            }
        }
        return false;
    }

    public int clearSession(String sessionId) {
        String sid = normalizeSession(sessionId);
        ConcurrentHashMap<Long, DiffRecord> map = sessionDiffs.remove(sid);
        if (map == null) return 0;
        int count = map.size();
        Path sessionDir = baseDir.resolve(sid);
        for (Long id : map.keySet()) {
            try {
                Files.deleteIfExists(diffFile(sid, id));
            } catch (IOException e) {
                log.warn("Failed to delete diff file {}/{}: {}", sid, id, e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(sessionDir);
        } catch (IOException e) {
            log.debug("Could not remove session dir {}: {}", sessionDir, e.getMessage());
        }
        return count;
    }

    public List<String> listSessions() {
        return new ArrayList<>(sessionDiffs.keySet());
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private Path sessionDir(String sessionId) {
        // Sanitize sessionId to prevent path traversal
        String safe = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return baseDir.resolve(safe);
    }

    private Path diffFile(String sessionId, long id) {
        return sessionDir(sessionId).resolve("diff-" + id + ".json");
    }

    private void persist(String sessionId, DiffRecord rec) {
        try {
            Path dir = sessionDir(sessionId);
            Files.createDirectories(dir);
            Path tmpFile = dir.resolve("diff-" + rec.getId() + ".json.tmp");
            mapper.writeValue(tmpFile.toFile(), rec);
            Files.move(tmpFile, diffFile(sessionId, rec.getId()), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to persist diff {}/{}: {}", sessionId, rec.getId(), e.getMessage(), e);
        }
    }

    private void loadAllSessions() {
        try (var dirs = Files.list(baseDir)) {
            dirs.filter(Files::isDirectory).forEach(this::loadSession);
        } catch (IOException e) {
            log.debug("No existing diff sessions found in {}: {}", baseDir, e.getMessage());
        }
        // Also load any legacy flat files (pre-session migration)
        loadLegacyFlat();
    }

    private void loadSession(Path sessionDir) {
        String sid = sessionDir.getFileName().toString();
        try (var files = Files.list(sessionDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            DiffRecord rec = mapper.readValue(p.toFile(), DiffRecord.class);
                            if (rec.getSessionId() == null) rec.setSessionId(sid);
                            sessionDiffs.computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                                    .put(rec.getId(), rec);
                            if (rec.getId() >= idCounter.get()) {
                                idCounter.set(rec.getId());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to load diff from {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to list diffs in {}: {}", sessionDir, e.getMessage());
        }
    }

    private void loadLegacyFlat() {
        try (var files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("diff-") && p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            DiffRecord rec = mapper.readValue(p.toFile(), DiffRecord.class);
                            String sid = rec.getSessionId() != null ? rec.getSessionId() : GLOBAL_SESSION;
                            rec.setSessionId(sid);
                            sessionDiffs.computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                                    .put(rec.getId(), rec);
                            if (rec.getId() >= idCounter.get()) {
                                idCounter.set(rec.getId());
                            }
                            // Migrate: persist into session subdir, delete flat file
                            persist(sid, rec);
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to migrate legacy diff {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // ignore
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String normalizeSession(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : GLOBAL_SESSION;
    }

    private String computeSimpleDiff(String before, String after) {
        if (before == null) before = "";
        if (after == null) after = "";
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        int maxLen = Math.max(beforeLines.length, afterLines.length);
        for (int i = 0; i < maxLen; i++) {
            String bLine = i < beforeLines.length ? beforeLines[i] : null;
            String aLine = i < afterLines.length ? afterLines[i] : null;
            if (Objects.equals(bLine, aLine)) {
                sb.append(" ").append(bLine != null ? bLine : "").append("\n");
            } else {
                if (bLine != null) sb.append("-").append(bLine).append("\n");
                if (aLine != null) sb.append("+").append(aLine).append("\n");
            }
        }
        return sb.toString();
    }

    private long countLines(String diff, String prefix) {
        if (diff == null) return 0;
        return diff.lines()
                .filter(line -> line.startsWith(prefix) && !line.startsWith(prefix + prefix))
                .count();
    }

    // ── Diff model ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiffRecord {
        private long id;
        private String sessionId;
        private String filePath;
        private String beforeContent;
        private String afterContent;
        private String unifiedDiff;
        private String agentId;
        private String taskId;
        private String description;
        private String timestamp;
        private long linesAdded;
        private long linesRemoved;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getBeforeContent() { return beforeContent; }
        public void setBeforeContent(String beforeContent) { this.beforeContent = beforeContent; }
        public String getAfterContent() { return afterContent; }
        public void setAfterContent(String afterContent) { this.afterContent = afterContent; }
        public String getUnifiedDiff() { return unifiedDiff; }
        public void setUnifiedDiff(String unifiedDiff) { this.unifiedDiff = unifiedDiff; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public long getLinesAdded() { return linesAdded; }
        public void setLinesAdded(long linesAdded) { this.linesAdded = linesAdded; }
        public long getLinesRemoved() { return linesRemoved; }
        public void setLinesRemoved(long linesRemoved) { this.linesRemoved = linesRemoved; }
    }
}
