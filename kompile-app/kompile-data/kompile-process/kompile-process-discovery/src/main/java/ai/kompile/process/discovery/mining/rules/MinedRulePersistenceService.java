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

package ai.kompile.process.discovery.mining.rules;

import ai.kompile.graph.reasoning.domain.CausalEdgeType;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.FileBackedAuditLog;
import ai.kompile.process.discovery.mining.causal.CausalDependency;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Closes the L4 gap: mined PSL causal rules are persisted to disk so that the existing
 * {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator#loadProjectPslRules}
 * seam picks them up and they propagate into the next cascade automatically.
 *
 * <h2>What this service writes</h2>
 * <ul>
 *   <li>{@code <dataDir>/rules/<factSheetId>-mined.psl} — the rule file (UTF-8, one rule per line,
 *       {@code #}-comment header with metadata). Replaces on each mining run.</li>
 *   <li>{@code <dataDir>/rules/<factSheetId>-mined-lineage.json} — the lineage sidecar: for every
 *       rule, records which causal arc / χ²-evidence it came from.</li>
 *   <li>{@code <dataDir>/rules/active-rules.json} — the active-rules version index (upserted).</li>
 *   <li>{@code <dataDir>/data/graph/inferred/factsheet-<id>-audit.jsonl} — a {@code RULE_CREATED}
 *       audit event so rule creation is a tracked, traceable decision.</li>
 * </ul>
 *
 * <h2>Propagation</h2>
 * No orchestrator changes are needed. The loader already reads all {@code *.psl} files from
 * {@code <dataDir>/rules/}; after this service writes the file the rules are available on the next
 * cascade invocation.
 */
@Component
public class MinedRulePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MinedRulePersistenceService.class);

    /** Project-scoped data directory, resolved from Spring environment. */
    @Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Persist causal PSL rules to disk and emit a {@code RULE_CREATED} audit event.
     *
     * <p>A no-op (warn + return {@link PersistResult#empty}) when {@code dataDir} is not configured.
     *
     * @param factSheetId  fact sheet the rules were mined from
     * @param causalModel  result of {@link ProcessCausalAnalyzer#analyze}
     * @return a result record describing what was written
     */
    public PersistResult persistCausalRules(long factSheetId,
                                             ProcessCausalAnalyzer.ProcessCausalModel causalModel) {
        if (dataDir == null || dataDir.isBlank()) {
            log.debug("MinedRulePersistenceService: dataDir not configured — rule persistence skipped");
            return PersistResult.empty(factSheetId);
        }

        List<String> rules = causalModel.pslRules();
        if (rules.isEmpty()) {
            log.debug("MinedRulePersistenceService: no CAUSES/TRIGGERS arcs in fact sheet {} — nothing to persist",
                    factSheetId);
            return PersistResult.empty(factSheetId);
        }

        Path rulesDir = Path.of(dataDir, "rules");
        String fileName = factSheetId + "-mined.psl";
        Path ruleFile = rulesDir.resolve(fileName);
        Path lineageFile = rulesDir.resolve(factSheetId + "-mined-lineage.json");

        try {
            Files.createDirectories(rulesDir);

            // 1. Write the PSL rule file
            writePslFile(ruleFile, factSheetId, rules, causalModel.dependencies());

            // 2. Write the lineage sidecar
            List<MinedRuleLineage> lineage = buildLineage(rules, causalModel.dependencies());
            writeLineageFile(lineageFile, lineage);

            // 3. Update the active-rules index
            ActiveRulesIndex index = ActiveRulesIndex.load(rulesDir);
            ActiveRulesIndex.RuleFileEntry entry =
                    index.upsert(factSheetId, fileName, rules.size(), "causal-mining");
            index.save(rulesDir);

            // 4. Emit RULE_CREATED audit event
            String runId = "mine-" + UUID.randomUUID();
            emitRuleCreatedAudit(factSheetId, rules.size(), fileName, runId, entry.version);

            log.info("MinedRulePersistenceService: persisted {} PSL rules for fact sheet {} → {} (v{})",
                    rules.size(), factSheetId, ruleFile, entry.version);
            return new PersistResult(factSheetId, fileName, rules.size(), entry.version, lineage, true);

        } catch (IOException e) {
            log.error("MinedRulePersistenceService: failed to persist rules for fact sheet {} — {}",
                    factSheetId, e.getMessage());
            return PersistResult.empty(factSheetId);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────

    /**
     * Write a well-formed PSL rule file: a comment header with metadata, then one rule per line.
     * Blank lines and {@code #}-prefixed comment lines are skipped by the loader, so the header
     * is safe to include.
     */
    private void writePslFile(Path ruleFile, long factSheetId,
                               List<String> rules, List<CausalDependency> dependencies) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mined PSL rules for fact sheet ").append(factSheetId).append("\n");
        sb.append("# Generated: ").append(Instant.now()).append("\n");
        sb.append("# Rule count: ").append(rules.size()).append("\n");
        sb.append("# Source: ProcessCausalAnalyzer (chi-squared-significant directly-follows arcs)\n");
        sb.append("# Arcs used:\n");
        for (CausalDependency d : dependencies) {
            if (d.type() == CausalEdgeType.CAUSES || d.type() == CausalEdgeType.TRIGGERS) {
                sb.append(String.format("#   %s -> %s  dep=%.3f  chi2=%.3f  sig=%b  type=%s  fwd=%d  rev=%d%n",
                        d.from(), d.to(), d.dependency(), d.chiSquare(),
                        d.significant(), d.type().name(), d.forward(), d.reverse()));
            }
        }
        sb.append("#\n");
        for (String rule : rules) {
            sb.append(rule).append("\n");
        }

        Files.writeString(ruleFile, sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Build lineage entries: for each PSL rule, find the matching arc in the dependency list
     * (by matching the arc's sanitised activity names inside the rule text) and create a
     * {@link MinedRuleLineage} record.
     */
    private List<MinedRuleLineage> buildLineage(List<String> rules, List<CausalDependency> dependencies) {
        List<MinedRuleLineage> lineage = new ArrayList<>();
        for (String rule : rules) {
            // Match the rule to the arc whose (from,to) activities appear in it.
            // Rule format: `0.73: State("A") & Link("A","B") -> State("B") ^2`
            CausalDependency matched = null;
            for (CausalDependency dep : dependencies) {
                if ((dep.type() == CausalEdgeType.CAUSES || dep.type() == CausalEdgeType.TRIGGERS)
                        && rule.contains("\"" + sanitize(dep.from()) + "\"")
                        && rule.contains("\"" + sanitize(dep.to()) + "\"")) {
                    matched = dep;
                    break;
                }
            }
            if (matched != null) {
                lineage.add(MinedRuleLineage.from(rule, matched));
            } else {
                // Fallback: unmatched rule still gets a partial lineage entry
                lineage.add(new MinedRuleLineage(rule, null, null,
                        Double.NaN, Double.NaN, false, 0L, 0L, null, Instant.now()));
            }
        }
        return lineage;
    }

    private void writeLineageFile(Path lineageFile, List<MinedRuleLineage> lineage) throws IOException {
        Files.writeString(lineageFile, MinedRuleLineage.toJsonArray(lineage),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Append a {@code RULE_CREATED} audit event to the fact sheet's audit log.
     * Uses the same {@link FileBackedAuditLog} path as KbCorrectionService
     * ({@code <dataDir>/data/graph/inferred/factsheet-<id>-audit.jsonl}).
     */
    private void emitRuleCreatedAudit(long factSheetId, int ruleCount,
                                       String fileName, String runId, int version) {
        try {
            Path dataDirPath = Path.of(dataDir);
            FileBackedAuditLog auditLog = new FileBackedAuditLog(dataDirPath, factSheetId);
            FactAuditEvent event = ruleCreatedEvent(factSheetId, ruleCount, fileName, runId, version);
            auditLog.append(event);
        } catch (Exception e) {
            // Audit failure is non-fatal — log and continue
            log.warn("MinedRulePersistenceService: could not emit RULE_CREATED audit for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }
    }

    /**
     * Build a {@code RULE_CREATED} {@link FactAuditEvent}.
     *
     * <p>We reuse the {@code WEIGHT_TUNED} factory (which already carries a {@code ruleId} field)
     * and override the {@code eventType} to {@code "RULE_CREATED"} via direct construction so the
     * event is distinctly queryable without modifying the record.
     * The {@code ruleId} field carries: {@code "<fileName>@v<version> count=<n>"}.
     */
    public static FactAuditEvent ruleCreatedEvent(long factSheetId, int ruleCount,
                                                   String fileName, String runId, int version) {
        return new FactAuditEvent(
                UUID.randomUUID().toString(),
                "RULE_CREATED",
                "factSheet:" + factSheetId,   // atomKey = scoping key
                Instant.now(),
                "PROCESS_MINER",              // actor
                runId,                        // sessionId = run traceability
                Double.NaN,                   // valueBefore — not applicable
                ruleCount,                    // valueAfter — number of rules written
                Double.NaN,                   // confidenceBefore
                Double.NaN,                   // confidenceAfter
                null,                         // strengthLayerBefore
                null,                         // strengthLayerAfter
                runId,                        // runId
                "causal-mining:v" + version,  // derivationTrailRef
                false,                        // pinnedAfter
                fileName + "@v" + version + " count=" + ruleCount,  // ruleId
                Double.NaN,                   // weightBefore
                Double.NaN,                   // weightAfter
                "causal-mining from fact sheet " + factSheetId   // correctionReason
        );
    }

    /** Mirror of ProcessCausalAnalyzer.sanitize to keep arc matching stable. */
    private static String sanitize(String label) {
        return label.replace('"', ' ').trim();
    }

    // ── Result type ───────────────────────────────────────────────────────────────

    /**
     * Result of a {@link #persistCausalRules} call.
     *
     * @param factSheetId the fact sheet the rules were mined from
     * @param fileName    the rule file name (relative to rulesDir), or {@code null} if skipped
     * @param ruleCount   number of rules written
     * @param version     version of this rule file entry in the active-rules index
     * @param lineage     per-rule lineage entries (empty when skipped)
     * @param persisted   {@code true} if rules were actually written to disk
     */
    public record PersistResult(
            long factSheetId,
            String fileName,
            int ruleCount,
            int version,
            List<MinedRuleLineage> lineage,
            boolean persisted
    ) {
        static PersistResult empty(long factSheetId) {
            return new PersistResult(factSheetId, null, 0, 0, List.of(), false);
        }
    }
}
