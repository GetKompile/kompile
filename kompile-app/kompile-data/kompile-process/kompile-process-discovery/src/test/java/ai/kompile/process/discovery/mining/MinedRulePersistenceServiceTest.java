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

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.FileBackedAuditLog;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.rules.ActiveRulesIndex;
import ai.kompile.process.discovery.mining.rules.MinedRuleLineage;
import ai.kompile.process.discovery.mining.rules.MinedRulePersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4 rule-persistence tests: verifies that mined PSL rules are written to disk in a form the
 * {@code loadProjectPslRules} seam can read, that the active-rules index is maintained,
 * that a {@code RULE_CREATED} audit event is emitted, and that rule lineage is recorded.
 */
class MinedRulePersistenceServiceTest {

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static EventLog strongSequenceLog() {
        // a→b→c repeated 5× → strong causal arcs CAUSES a→b, b→c
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String cid = "c" + i;
            traces.add(new Trace(cid, List.of(
                    Event.of(cid, "approve", null, null),
                    Event.of(cid, "notify", null, null),
                    Event.of(cid, "close", null, null))));
        }
        return new EventLog(traces);
    }

    private MinedRulePersistenceService service(Path dataDir) {
        MinedRulePersistenceService svc = new MinedRulePersistenceService();
        ReflectionTestUtils.setField(svc, "dataDir", dataDir.toString());
        return svc;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    /** Mined rules must be persisted to <dataDir>/rules/<factSheetId>-mined.psl. */
    @Test
    void rulesPersistedToCorrectPath(@TempDir Path dataDir) throws IOException {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        assertFalse(model.pslRules().isEmpty(), "strong sequence must produce CAUSES/TRIGGERS rules");

        MinedRulePersistenceService svc = service(dataDir);
        MinedRulePersistenceService.PersistResult result = svc.persistCausalRules(42L, model);

        assertTrue(result.persisted(), "persist must succeed with a valid dataDir");
        assertEquals(42L, result.factSheetId());
        assertTrue(result.ruleCount() > 0);

        Path ruleFile = dataDir.resolve("rules").resolve("42-mined.psl");
        assertTrue(Files.exists(ruleFile), "rule file must exist at <dataDir>/rules/42-mined.psl");
    }

    /** Every line in the PSL file that is not a comment or blank must parse cleanly. */
    @Test
    void persistedFileIsWellFormedPsl(@TempDir Path dataDir) throws IOException {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        service(dataDir).persistCausalRules(99L, model);

        Path ruleFile = dataDir.resolve("rules").resolve("99-mined.psl");
        List<String> lines = Files.readAllLines(ruleFile);
        int parsedCount = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // Must parse without exception — this is exactly what loadProjectPslRules does
            assertDoesNotThrow(() -> PslRule.parse(trimmed),
                    "Rule line must be valid PSL: " + trimmed);
            parsedCount++;
        }
        assertEquals(model.pslRules().size(), parsedCount,
                "all generated rules must appear in the file");
    }

    /** The active-rules index must be created and contain an entry for the fact sheet. */
    @Test
    void activeRulesIndexIsUpdated(@TempDir Path dataDir) {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        service(dataDir).persistCausalRules(7L, model);

        Path rulesDir = dataDir.resolve("rules");
        ActiveRulesIndex index = ActiveRulesIndex.load(rulesDir);
        List<ActiveRulesIndex.RuleFileEntry> entries = index.entriesFor(7L);
        assertFalse(entries.isEmpty(), "active-rules index must have an entry for fact sheet 7");
        ActiveRulesIndex.RuleFileEntry entry = entries.get(0);
        assertEquals("7-mined.psl", entry.fileName);
        assertEquals(7L, entry.factSheetId);
        assertTrue(entry.ruleCount > 0);
        assertEquals(1, entry.version, "first write must be version 1");
    }

    /** A second persist call must increment the version in the index. */
    @Test
    void activeRulesIndexVersionIsIncremented(@TempDir Path dataDir) {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        MinedRulePersistenceService svc = service(dataDir);
        svc.persistCausalRules(7L, model);
        svc.persistCausalRules(7L, model);  // second write → version 2

        ActiveRulesIndex index = ActiveRulesIndex.load(dataDir.resolve("rules"));
        assertEquals(1, index.entriesFor(7L).size(), "only one entry per (factSheetId, fileName)");
        assertEquals(2, index.entriesFor(7L).get(0).version, "second write must be version 2");
    }

    /** A RULE_CREATED audit event must be appended to the fact sheet's audit JSONL. */
    @Test
    void ruleCreatedAuditEventIsEmitted(@TempDir Path dataDir) throws IOException {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        service(dataDir).persistCausalRules(55L, model);

        FileBackedAuditLog auditLog = new FileBackedAuditLog(dataDir, 55L);
        List<FactAuditEvent> events = auditLog.load(null, "RULE_CREATED");
        assertEquals(1, events.size(), "exactly one RULE_CREATED event must be appended");
        FactAuditEvent event = events.get(0);
        assertEquals("RULE_CREATED", event.eventType());
        assertEquals("factSheet:55", event.atomKey());
        assertEquals("PROCESS_MINER", event.actor());
        assertNotNull(event.ruleId(), "ruleId must encode the file name");
        assertTrue(event.ruleId().contains("55-mined.psl"), "ruleId must reference the rule file");
    }

    /** Rule lineage must be recorded: each rule traces back to its causal arc. */
    @Test
    void lineageSidecarIsWrittenWithArcEvidence(@TempDir Path dataDir) throws IOException {
        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        MinedRulePersistenceService.PersistResult result =
                service(dataDir).persistCausalRules(3L, model);

        // Check in-memory lineage
        assertFalse(result.lineage().isEmpty(), "lineage must not be empty");
        for (MinedRuleLineage entry : result.lineage()) {
            assertNotNull(entry.ruleText(), "every lineage entry must have the rule text");
            // Matched entries carry the arc evidence
            if (entry.fromActivity() != null) {
                assertFalse(entry.fromActivity().isBlank());
                assertFalse(entry.toActivity().isBlank());
                assertNotNull(entry.edgeType(), "edge type must be recorded");
                assertTrue(entry.forwardCount() > 0, "forward count must be positive for matched arcs");
            }
        }

        // Check the sidecar file exists and is non-empty
        Path lineageFile = dataDir.resolve("rules").resolve("3-mined-lineage.json");
        assertTrue(Files.exists(lineageFile), "lineage sidecar must exist");
        String json = Files.readString(lineageFile);
        assertTrue(json.trim().startsWith("["), "lineage sidecar must be a JSON array");
        assertTrue(json.contains("\"ruleText\""), "lineage JSON must contain ruleText field");
        assertTrue(json.contains("\"fromActivity\""), "lineage JSON must contain fromActivity field");
    }

    /** When dataDir is null the service skips persistence and returns PersistResult.empty. */
    @Test
    void noPersistenceWhenDataDirIsNull() {
        MinedRulePersistenceService svc = new MinedRulePersistenceService();
        ReflectionTestUtils.setField(svc, "dataDir", null);

        ProcessCausalAnalyzer.ProcessCausalModel model =
                ProcessCausalAnalyzer.analyze(strongSequenceLog());
        MinedRulePersistenceService.PersistResult result = svc.persistCausalRules(1L, model);

        assertFalse(result.persisted(), "must return empty result when dataDir is not configured");
        assertEquals(0, result.ruleCount());
        assertTrue(result.lineage().isEmpty());
    }

    /** RULE_CREATED audit event factory produces a well-formed FactAuditEvent. */
    @Test
    void ruleCreatedEventFactory() {
        FactAuditEvent event = MinedRulePersistenceService.ruleCreatedEvent(
                42L, 7, "42-mined.psl", "run-abc", 1);
        assertEquals("RULE_CREATED", event.eventType());
        assertEquals("factSheet:42", event.atomKey());
        assertEquals(7.0, event.valueAfter(), 1e-9);
        assertNotNull(event.eventId());
        assertTrue(event.ruleId().contains("42-mined.psl"));
        assertEquals("causal-mining:v1", event.derivationTrailRef());
    }

    /** ActiveRulesIndex round-trip: save then load produces the same entries. */
    @Test
    void activeRulesIndexRoundTrip(@TempDir Path dir) {
        Path rulesDir = dir.resolve("rules");
        ActiveRulesIndex index = new ActiveRulesIndex();
        index.upsert(10L, "10-mined.psl", 3, "causal-mining");
        index.upsert(20L, "20-mined.psl", 5, "causal-mining");
        index.save(rulesDir);

        ActiveRulesIndex loaded = ActiveRulesIndex.load(rulesDir);
        assertEquals(2, loaded.activeFiles().size());
        assertEquals(1, loaded.entriesFor(10L).size());
        assertEquals(3, loaded.entriesFor(10L).get(0).ruleCount);
        assertEquals(5, loaded.entriesFor(20L).get(0).ruleCount);
    }

    /** Deactivating a rule file removes it from the active list without deleting the file. */
    @Test
    void activeRulesIndexDeactivate(@TempDir Path dir) {
        Path rulesDir = dir.resolve("rules");
        ActiveRulesIndex index = new ActiveRulesIndex();
        index.upsert(11L, "11-mined.psl", 2, "causal-mining");
        index.save(rulesDir);

        ActiveRulesIndex loaded = ActiveRulesIndex.load(rulesDir);
        assertTrue(loaded.deactivate(11L, "11-mined.psl"), "deactivate must return true when entry found");
        assertEquals(0, loaded.entriesFor(11L).size(), "entry must be removed after deactivate");
        loaded.save(rulesDir);

        ActiveRulesIndex reloaded = ActiveRulesIndex.load(rulesDir);
        assertEquals(0, reloaded.entriesFor(11L).size(), "deactivated entry must not appear after reload");
    }

    /** MinedRuleLineage toJson/toJsonArray round-trips are readable and structurally correct. */
    @Test
    void lineageJsonSerialisation() {
        List<MinedRuleLineage> entries = List.of(
                new MinedRuleLineage("0.90: State(\"a\") & Link(\"a\",\"b\") -> State(\"b\") ^2",
                        "a", "b", 0.9, 25.0, true, 10L, 0L, "CAUSES", java.time.Instant.now()),
                new MinedRuleLineage("0.50: State(\"b\") & Link(\"b\",\"c\") -> State(\"c\") ^2",
                        "b", "c", 0.5, 4.5, true, 5L, 1L, "TRIGGERS", java.time.Instant.now())
        );
        String json = MinedRuleLineage.toJsonArray(entries);
        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("\"fromActivity\":\"a\""));
        assertTrue(json.contains("\"edgeType\":\"CAUSES\""));
        assertTrue(json.contains("\"forwardCount\":10"));
    }
}
