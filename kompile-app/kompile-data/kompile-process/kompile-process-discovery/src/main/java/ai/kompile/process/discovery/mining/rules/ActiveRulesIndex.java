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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A small JSON index of active rule files written to {@code <rulesDir>/active-rules.json}.
 *
 * <p>Tracking active rule files + versions makes rule creation versioned and reversible:
 * each entry records the file name, fact sheet ID, rule count, and the timestamp it was created.
 * Deactivating a file (e.g. for rollback) removes it from the {@code activeFiles} list;
 * the physical file is left on disk so it can be reactivated later.
 *
 * <p>{@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator#loadProjectPslRules}
 * scans all {@code *.psl} files regardless of this index, so the index is an advisory record only —
 * it does not gate what the orchestrator loads today, but provides the seam for P3 "read-from-index"
 * evolution without breaking backward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActiveRulesIndex {

    private static final Logger log = LoggerFactory.getLogger(ActiveRulesIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX_FILE = "active-rules.json";

    /** One entry per active rule file. */
    @JsonProperty("activeFiles")
    private List<RuleFileEntry> activeFiles = new ArrayList<>();

    /** Schema version for future migrations. */
    @JsonProperty("schemaVersion")
    private int schemaVersion = 1;

    // ── Entry type ────────────────────────────────────────────────────────────────

    /** An entry in the active-rules index: one per persisted rule file. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleFileEntry {
        /** File name relative to the rules directory (e.g. {@code 42-mined.psl}). */
        @JsonProperty("fileName")
        public String fileName;
        /** The fact sheet this rule file was mined from. */
        @JsonProperty("factSheetId")
        public long factSheetId;
        /** Number of rules written to this file. */
        @JsonProperty("ruleCount")
        public int ruleCount;
        /** ISO-8601 timestamp when this rule file was created/updated. */
        @JsonProperty("createdAt")
        public String createdAt;
        /** How the rules were produced (e.g. {@code "causal-mining"}). */
        @JsonProperty("source")
        public String source;
        /** Monotonically-increasing version counter per fact sheet. 1 = first mine. */
        @JsonProperty("version")
        public int version;

        /** Default constructor for Jackson. */
        public RuleFileEntry() {}

        public RuleFileEntry(String fileName, long factSheetId, int ruleCount,
                             Instant createdAt, String source, int version) {
            this.fileName = fileName;
            this.factSheetId = factSheetId;
            this.ruleCount = ruleCount;
            this.createdAt = createdAt.toString();
            this.source = source;
            this.version = version;
        }

        @Override
        public String toString() {
            return "RuleFileEntry{file=" + fileName + ", fs=" + factSheetId +
                    ", rules=" + ruleCount + ", v=" + version + "}";
        }
    }

    /** Default constructor for Jackson. */
    public ActiveRulesIndex() {}

    // ── Factory ───────────────────────────────────────────────────────────────────

    /**
     * Load the index from {@code rulesDir/active-rules.json}, or return an empty index if the
     * file does not exist yet.
     */
    public static ActiveRulesIndex load(Path rulesDir) {
        Path indexFile = rulesDir.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return new ActiveRulesIndex();
        }
        try {
            return MAPPER.readValue(indexFile.toFile(), ActiveRulesIndex.class);
        } catch (IOException e) {
            log.warn("ActiveRulesIndex: could not load {} — starting empty. Cause: {}", indexFile, e.getMessage());
            return new ActiveRulesIndex();
        }
    }

    // ── Mutation ──────────────────────────────────────────────────────────────────

    /**
     * Register or update an entry for the given fact sheet.
     * If an entry for this fact sheet already exists its version is incremented;
     * otherwise a new entry with version=1 is added.
     *
     * @param factSheetId the fact sheet the rules were mined from
     * @param fileName    the rule file name (basename only, not the full path)
     * @param ruleCount   how many rules are in the file
     * @param source      human-readable source tag (e.g. {@code "causal-mining"})
     * @return the new or updated {@link RuleFileEntry}
     */
    public RuleFileEntry upsert(long factSheetId, String fileName, int ruleCount, String source) {
        // Find existing entry for this factSheetId+fileName pair
        for (int i = 0; i < activeFiles.size(); i++) {
            RuleFileEntry e = activeFiles.get(i);
            if (e.factSheetId == factSheetId && fileName.equals(e.fileName)) {
                RuleFileEntry updated = new RuleFileEntry(
                        fileName, factSheetId, ruleCount, Instant.now(), source, e.version + 1);
                activeFiles.set(i, updated);
                return updated;
            }
        }
        // New entry
        RuleFileEntry entry = new RuleFileEntry(fileName, factSheetId, ruleCount, Instant.now(), source, 1);
        activeFiles.add(entry);
        return entry;
    }

    /**
     * Deactivate (remove from the active list) any entry matching the given fact sheet and file name.
     * The physical file is NOT deleted.
     */
    public boolean deactivate(long factSheetId, String fileName) {
        return activeFiles.removeIf(e -> e.factSheetId == factSheetId && fileName.equals(e.fileName));
    }

    /** Return all active entries. Unmodifiable view. */
    public List<RuleFileEntry> activeFiles() {
        return List.copyOf(activeFiles);
    }

    /** Return active entries for a specific fact sheet. */
    public List<RuleFileEntry> entriesFor(long factSheetId) {
        return activeFiles.stream()
                .filter(e -> e.factSheetId == factSheetId)
                .toList();
    }

    // ── Persistence ───────────────────────────────────────────────────────────────

    /**
     * Write this index to {@code rulesDir/active-rules.json}.
     *
     * @param rulesDir the rules directory (created if absent)
     */
    public void save(Path rulesDir) {
        try {
            Files.createDirectories(rulesDir);
            Path indexFile = rulesDir.resolve(INDEX_FILE);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), this);
        } catch (IOException e) {
            log.warn("ActiveRulesIndex: could not save index to {} — {}", rulesDir, e.getMessage());
        }
    }

    // ── Jackson accessors (needed for serialisation) ──────────────────────────────

    public List<RuleFileEntry> getActiveFiles() { return activeFiles; }
    public void setActiveFiles(List<RuleFileEntry> activeFiles) { this.activeFiles = activeFiles; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
}
