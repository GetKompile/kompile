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

package ai.kompile.app.services.diffpolicy;

import ai.kompile.app.services.diffindex.DiffIndexEntry;
import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.cli.common.enforcer.DiffPatternEvaluator;
import ai.kompile.core.llm.chat.LLMChat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Scans captured agent file-changes ({@code DiffIndexEntry}) against policy and
 * records scored violations — the audit/governance layer over "how agents change
 * files". Two deterministic detectors today (an LLM-judge detector is additive):
 * <ul>
 *   <li><b>path</b> — flag any edit to a file of concern ({@link PathRule} globs,
 *       e.g. {@code **}{@code /*.env}), regardless of content.</li>
 *   <li><b>rule</b> — flag banned content patterns inside the diff, reusing the
 *       exact enforcer engine {@link DiffPatternEvaluator} (relocated to
 *       kompile-cli-common so the server and the CLI enforcer share one rule set).</li>
 * </ul>
 * Violations are persisted and queryable by file (glob), agent, session, time,
 * severity, and detector.
 */
@Service
public class DiffPolicyService {

    private static final Logger log = LoggerFactory.getLogger(DiffPolicyService.class);

    private static final String DEFAULT_CONTENT_RULES = String.join("\n",
            "# Kompile default diff content policy.",
            "# BAN_DIFF = error, STOP_DIFF = critical; *_REGEX variants are Java regex on added lines.",
            "STOP_DIFF_REGEX: (?i)(api[_-]?key|secret|password|passwd|token|private[_-]?key)\\s*[:=]\\s*[\"'][^\"']{6,}[\"']",
            "BAN_DIFF: System.exit(",
            "BAN_DIFF_REGEX: (?i)\\bTODO\\b.*\\bremove\\b");

    /** Reused judge-style system prompt for the optional LLM detector. */
    private static final String LLM_SYSTEM_PROMPT = String.join("\n",
            "You are a strict code-change policy auditor reviewing a single file change made by an AI coding agent.",
            "Identify concrete POLICY VIOLATIONS or likely MISTAKES introduced by the change — e.g. hardcoded secrets,",
            "dangerous calls, disabling tests or security checks, editing files that should not be touched, or clear bugs.",
            "Only report real, defensible issues; if the change looks fine, return an empty list. Do not invent problems.",
            "Respond with ONLY a JSON object, no prose, no markdown:",
            "{\"violations\":[{\"severity\":\"critical|error|warning|info\",\"message\":\"<short specific reason>\"}]}");

    /** Hard cap on diffs sent to the (slow/costly) LLM detector per scan. */
    private static final int LLM_SCAN_CAP = 100;

    private final DiffIndexService diffIndexService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path policyDir;

    private volatile List<PathRule> pathRules = new ArrayList<>();
    private volatile String contentRulesText = DEFAULT_CONTENT_RULES;
    private final Map<String, DiffPolicyViolation> violations = new ConcurrentHashMap<>();

    /** Optional LLM detector backend (the server's chat pool); absent when no LLM is configured. */
    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired
    public DiffPolicyService(@Autowired(required = false) DiffIndexService diffIndexService) {
        this.diffIndexService = diffIndexService;
        this.policyDir = Paths.get(System.getProperty("user.home"), ".kompile", "agent-state", "diff-policy");
    }

    /** Test seam for injecting (or clearing) the optional LLM detector backend. */
    void setLlmChat(LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    /** Whether an LLM detector backend is wired (surfaced so the UI can enable the toggle). */
    public boolean isLlmAvailable() {
        return llmChat != null;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(policyDir);
            loadConfig();
            loadViolations();
            log.info("DiffPolicyService initialized: {} path rules, {} stored violations",
                    pathRules.size(), violations.size());
        } catch (Exception e) {
            log.warn("DiffPolicyService init failed: {}", e.getMessage());
        }
    }

    // ── Rules config ─────────────────────────────────────────────────

    public List<PathRule> getPathRules() {
        return new ArrayList<>(pathRules);
    }

    public String getContentRulesText() {
        return contentRulesText;
    }

    public synchronized void saveRules(List<PathRule> newPathRules, String newContentRulesText) {
        if (newPathRules != null) {
            this.pathRules = new ArrayList<>(newPathRules);
        }
        if (newContentRulesText != null) {
            this.contentRulesText = newContentRulesText;
        }
        persistConfig();
    }

    // ── Scan ─────────────────────────────────────────────────────────

    /**
     * Evaluate the (optionally filtered) captured diffs against the current policy.
     * Violations for the scanned diffs are recomputed (idempotent re-scan).
     */
    public Map<String, Object> scan(String agent, String filePath, String sessionId,
                                    String since, String until, Integer limit) {
        return scan(agent, filePath, sessionId, since, until, limit, false);
    }

    /**
     * Evaluate the (optionally filtered) captured diffs against the current policy.
     * Violations for the scanned diffs are recomputed (idempotent re-scan). When
     * {@code useLlm} is set and an LLM backend is wired, an LLM judge pass runs on up
     * to {@link #LLM_SCAN_CAP} diffs in addition to the deterministic detectors.
     */
    public Map<String, Object> scan(String agent, String filePath, String sessionId,
                                    String since, String until, Integer limit, boolean useLlm) {
        List<DiffIndexEntry> entries = diffIndexService == null ? List.of()
                : diffIndexService.search(agent, null, filePath, null, null, since, until,
                        limit != null && limit > 0 ? limit : 5000);

        DiffPatternEvaluator evaluator = DiffPatternEvaluator.fromText(contentRulesText, mapper);
        String now = Instant.now().toString();
        boolean llm = useLlm && llmChat != null;
        int llmEvaluated = 0;
        boolean llmTruncated = false;

        List<DiffPolicyViolation> found = new ArrayList<>();
        for (DiffIndexEntry e : entries) {
            found.addAll(evaluateEntry(e, evaluator, now));
            if (llm) {
                if (llmEvaluated < LLM_SCAN_CAP) {
                    found.addAll(evaluateWithLlm(e, now));
                    llmEvaluated++;
                } else {
                    llmTruncated = true;
                }
            }
        }

        // Idempotent: drop prior violations for the scanned diffs, then add fresh ones.
        Set<String> scannedIds = entries.stream().map(DiffIndexEntry::getId).collect(Collectors.toSet());
        violations.values().removeIf(v -> scannedIds.contains(v.getDiffEntryId()));
        for (DiffPolicyViolation v : found) {
            violations.put(v.getId(), v);
        }
        persistViolations();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scannedDiffs", entries.size());
        summary.put("violations", found.size());
        summary.put("bySeverity", countBy(found, DiffPolicyViolation::getSeverity));
        summary.put("byDetector", countBy(found, DiffPolicyViolation::getDetector));
        summary.put("llmAvailable", llmChat != null);
        summary.put("llmEvaluated", llmEvaluated);
        if (llmTruncated) {
            summary.put("llmTruncatedAt", LLM_SCAN_CAP);
        }
        summary.put("scannedAt", now);
        return summary;
    }

    private List<DiffPolicyViolation> evaluateEntry(DiffIndexEntry e, DiffPatternEvaluator evaluator, String now) {
        List<DiffPolicyViolation> out = new ArrayList<>();

        // Path-of-concern rules: any edit to a matching file.
        for (PathRule pr : pathRules) {
            if (pr.getGlob() == null || pr.getGlob().isBlank()) continue;
            if (PathGlobMatcher.matches(e.getFilePath(), pr.getGlob())) {
                String sev = severityOrDefault(pr.getSeverity(), "critical");
                String msg = pr.getDescription() != null && !pr.getDescription().isBlank()
                        ? pr.getDescription() : "Edit to a path of concern: " + pr.getGlob();
                out.add(build(e, "path", pr.getGlob(), sev, msg, 0, null, now));
            }
        }

        // Content rules: banned patterns inside the diff (reused enforcer engine).
        String unified = e.getUnifiedDiff();
        if (unified != null && !unified.isBlank() && evaluator.isAvailable()) {
            DiffPatternEvaluator.DiffEvaluation eval = evaluator.evaluate(unified);
            for (DiffPatternEvaluator.DiffViolation v : eval.violations()) {
                String sev = severityOrDefault(v.rule().getSeverity(), "error");
                String msg = v.rule().getDescription() != null && !v.rule().getDescription().isBlank()
                        ? v.rule().getDescription() : v.rule().getPattern();
                out.add(build(e, "rule", v.rule().getPattern(), sev, msg, v.lineNumber(), v.matchedLine(), now));
            }
        }
        return out;
    }

    // ── LLM judge detector (optional, reuses the server's LLMChat pool) ──

    private List<DiffPolicyViolation> evaluateWithLlm(DiffIndexEntry e, String now) {
        if (llmChat == null) return List.of();
        String diff = llmDiffText(e);
        if (diff.isBlank()) return List.of();
        try {
            String response = llmChat.prompt()
                    .system(LLM_SYSTEM_PROMPT)
                    .user(buildLlmUserPrompt(e, diff))
                    .call()
                    .content();
            return parseLlmViolations(e, response, now);
        } catch (Exception ex) {
            log.debug("LLM judge failed for {}: {}", e.getId(), ex.getMessage());
            return List.of();
        }
    }

    /** Map an LLM judge response (lenient JSON) to violations. Package-visible for testing. */
    List<DiffPolicyViolation> parseLlmViolations(DiffIndexEntry e, String response, String now) {
        List<DiffPolicyViolation> out = new ArrayList<>();
        JsonNode root = parseJsonLenient(response);
        if (root == null) return out;
        JsonNode arr = root.get("violations");
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode v : arr) {
            String message = v.path("message").asText("");
            if (message.isBlank()) continue;
            String sev = severityOrDefault(v.path("severity").asText(null), "warning");
            out.add(build(e, "llm", "llm-judge", sev, message, 0, null, now));
        }
        return out;
    }

    private String buildLlmUserPrompt(DiffIndexEntry e, String diff) {
        StringBuilder sb = new StringBuilder();
        if (contentRulesText != null && !contentRulesText.isBlank()) {
            sb.append("PROJECT POLICY RULES (for guidance):\n").append(contentRulesText).append("\n\n");
        }
        sb.append("FILE: ").append(e.getFilePath() != null ? e.getFilePath() : "(unknown)").append("\n");
        sb.append("AGENT: ").append(e.getAgent() != null ? e.getAgent() : "(unknown)").append("\n");
        sb.append("CHANGE:\n").append(diff);
        return sb.toString();
    }

    /** Diff text for the LLM, capped to keep the prompt bounded. */
    private static String llmDiffText(DiffIndexEntry e) {
        String text = e.getUnifiedDiff();
        if (text == null || text.isBlank()) {
            String oldS = e.getOldString() != null ? e.getOldString() : "";
            String newS = e.getNewString() != null ? e.getNewString() : "";
            text = (oldS.isBlank() && newS.isBlank()) ? "" : "--- before ---\n" + oldS + "\n--- after ---\n" + newS;
        }
        if (text.length() > 8000) {
            text = text.substring(0, 8000) + "\n…(truncated)";
        }
        return text;
    }

    private JsonNode parseJsonLenient(String response) {
        if (response == null || response.isBlank()) return null;
        try { return mapper.readTree(response); } catch (Exception ignore) { }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try { return mapper.readTree(response.substring(start, end + 1)); } catch (Exception ignore) { }
        }
        return null;
    }

    private DiffPolicyViolation build(DiffIndexEntry e, String detector, String ruleId, String severity,
                                      String message, int line, String matchedLine, String now) {
        String id = e.getId() + "|" + detector + "|" + Integer.toHexString(Objects.hash(ruleId, line, message));
        return DiffPolicyViolation.builder()
                .id(id)
                .diffEntryId(e.getId())
                .detector(detector)
                .ruleId(ruleId)
                .severity(severity)
                .riskScore(riskScore(severity))
                .agent(e.getAgent())
                .source(e.getSource())
                .sessionId(e.getSessionId())
                .filePath(e.getFilePath())
                .lineNumber(line)
                .matchedLine(matchedLine)
                .message(message)
                .toolName(e.getToolName())
                .timestamp(e.getTimestamp())
                .detectedAt(now)
                .build();
    }

    // ── Query ────────────────────────────────────────────────────────

    public List<DiffPolicyViolation> listViolations(String filePath, String agent, String sessionId,
                                                     String detector, String severity,
                                                     String since, String until, Integer limit) {
        Long sinceMs = toEpochMillis(since);
        Long untilMs = toEpochMillis(until);
        int max = limit != null && limit > 0 ? limit : 500;
        return violations.values().stream()
                .filter(v -> filePath == null || PathGlobMatcher.matches(v.getFilePath(), filePath))
                .filter(v -> agent == null || agent.equals(v.getAgent()))
                .filter(v -> sessionId == null || sessionId.equals(v.getSessionId()))
                .filter(v -> detector == null || detector.equalsIgnoreCase(v.getDetector()))
                .filter(v -> severity == null || severity.equalsIgnoreCase(v.getSeverity()))
                .filter(v -> sinceMs == null || withinSince(v.getTimestamp(), sinceMs))
                .filter(v -> untilMs == null || withinUntil(v.getTimestamp(), untilMs))
                .sorted(Comparator.comparingDouble(DiffPolicyViolation::getRiskScore).reversed()
                        .thenComparing(v -> v.getTimestamp() == null ? "" : v.getTimestamp(), Comparator.reverseOrder()))
                .limit(max)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getStats() {
        List<DiffPolicyViolation> all = new ArrayList<>(violations.values());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("bySeverity", countBy(all, DiffPolicyViolation::getSeverity));
        stats.put("byDetector", countBy(all, DiffPolicyViolation::getDetector));
        stats.put("byAgent", countBy(all, DiffPolicyViolation::getAgent));
        stats.put("uniqueFiles", all.stream().map(DiffPolicyViolation::getFilePath).distinct().count());
        return stats;
    }

    public synchronized void clearViolations() {
        violations.clear();
        persistViolations();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String severityOrDefault(String s, String def) {
        return s != null && !s.isBlank() ? s.toLowerCase() : def;
    }

    private static double riskScore(String severity) {
        if (severity == null) return 0.5;
        switch (severity.toLowerCase()) {
            case "critical": return 1.0;
            case "error": return 0.75;
            case "warning": return 0.5;
            case "info": return 0.25;
            default: return 0.5;
        }
    }

    private static Map<String, Long> countBy(List<DiffPolicyViolation> list,
                                             java.util.function.Function<DiffPolicyViolation, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DiffPolicyViolation v : list) {
            String k = key.apply(v);
            if (k == null) k = "unknown";
            counts.merge(k, 1L, Long::sum);
        }
        return counts;
    }

    private static boolean withinSince(String ts, long boundMs) {
        Long t = toEpochMillis(ts);
        return t == null || t >= boundMs;
    }

    private static boolean withinUntil(String ts, long boundMs) {
        Long t = toEpochMillis(ts);
        return t == null || t <= boundMs;
    }

    private static Long toEpochMillis(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String s = ts.trim();
        try { return java.time.Instant.parse(s).toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignore) { }
        try { return java.time.LocalDate.parse(s).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignore) { }
        return null;
    }

    // ── Persistence ──────────────────────────────────────────────────

    private void loadConfig() {
        Path pathRulesFile = policyDir.resolve("path-rules.json");
        Path contentRulesFile = policyDir.resolve("content-rules.txt");
        try {
            if (Files.exists(pathRulesFile)) {
                this.pathRules = mapper.readValue(Files.readString(pathRulesFile, StandardCharsets.UTF_8),
                        new TypeReference<List<PathRule>>() {});
            } else {
                this.pathRules = defaultPathRules();
            }
            if (Files.exists(contentRulesFile)) {
                this.contentRulesText = Files.readString(contentRulesFile, StandardCharsets.UTF_8);
            } else {
                this.contentRulesText = DEFAULT_CONTENT_RULES;
            }
            // Seed default files on first run so they are discoverable/editable on disk.
            if (!Files.exists(pathRulesFile) || !Files.exists(contentRulesFile)) {
                persistConfig();
            }
        } catch (Exception e) {
            log.warn("Failed to load diff policy config, using defaults: {}", e.getMessage());
            this.pathRules = defaultPathRules();
            this.contentRulesText = DEFAULT_CONTENT_RULES;
        }
    }

    private void persistConfig() {
        try {
            Files.writeString(policyDir.resolve("path-rules.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pathRules), StandardCharsets.UTF_8);
            Files.writeString(policyDir.resolve("content-rules.txt"), contentRulesText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist diff policy config: {}", e.getMessage());
        }
    }

    private void loadViolations() {
        Path file = policyDir.resolve("violations.json");
        if (!Files.exists(file)) return;
        try {
            List<DiffPolicyViolation> stored = mapper.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<DiffPolicyViolation>>() {});
            for (DiffPolicyViolation v : stored) {
                if (v.getId() != null) violations.put(v.getId(), v);
            }
        } catch (Exception e) {
            log.warn("Failed to load stored violations: {}", e.getMessage());
        }
    }

    private void persistViolations() {
        try {
            Files.writeString(policyDir.resolve("violations.json"),
                    mapper.writeValueAsString(new ArrayList<>(violations.values())), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist violations: {}", e.getMessage());
        }
    }

    private static List<PathRule> defaultPathRules() {
        List<PathRule> rules = new ArrayList<>();
        rules.add(PathRule.builder().glob("**/*.env").severity("critical").description("Edit to a dotenv/secret file").build());
        rules.add(PathRule.builder().glob("**/secrets/**").severity("critical").description("Edit under a secrets directory").build());
        rules.add(PathRule.builder().glob("**/*.pem").severity("critical").description("Edit to a private key or certificate").build());
        rules.add(PathRule.builder().glob("**/id_rsa*").severity("critical").description("Edit to an SSH private key").build());
        rules.add(PathRule.builder().glob("**/*.lock").severity("warning").description("Edit to a dependency lockfile").build());
        return rules;
    }
}
