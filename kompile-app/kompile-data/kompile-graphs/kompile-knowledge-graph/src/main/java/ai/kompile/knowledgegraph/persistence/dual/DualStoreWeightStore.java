/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence.dual;

import ai.kompile.graph.reasoning.learning.WeightStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JPA-backed {@link WeightStore} implementation for the dual-store grounding persistence layer.
 *
 * <p>This class is NOT a Spring {@code @Component}; it is instantiated per fact-sheet scope by
 * {@link DualStoreGroundingFactory}. Each instance is bound to a specific {@code factSheetId}
 * (or null for global scope) which is encoded into every {@code programKey} written to the
 * database — ensuring that different fact sheets never collide on the same weight row.</p>
 *
 * <h3>Key scoping</h3>
 * <p>The {@code programKey} stored in the DB is computed by {@link #computeKey(String)}:
 * <ul>
 *   <li>fact-sheet-scoped: {@code "fs:<factSheetId>:<programId>"}</li>
 *   <li>global (factSheetId == null): {@code programId}</li>
 * </ul>
 * This allows multiple fact sheets to store weights under the same logical {@code programId}
 * without row collisions, while the raw programId can still be used as a global key when
 * no factSheetId is set.
 * </p>
 *
 * <h3>Version semantics</h3>
 * <p>Each call to {@link #save(String, Map)} increments the version for the scoped key by 1,
 * starting at 1. Prior versions are retained (the table accumulates rows over time) and are
 * addressable via {@link #get(String, int)}.</p>
 */
public class DualStoreWeightStore implements WeightStore {

    private static final Logger log = LoggerFactory.getLogger(DualStoreWeightStore.class);

    private final PslWeightRowRepository repo;

    /**
     * Fact-sheet scope. Null means global (project-level) scope.
     */
    @Nullable
    private final Long factSheetId;

    /**
     * Construct a fact-sheet-scoped or global weight store.
     *
     * @param repo        the JPA repository backing this store
     * @param factSheetId the fact-sheet scope, or null for global scope
     */
    public DualStoreWeightStore(PslWeightRowRepository repo, @Nullable Long factSheetId) {
        this.repo = repo;
        this.factSheetId = factSheetId;
    }

    // ── WeightStore implementation ────────────────────────────────────────────────

    @Override
    public int save(String programId, Map<String, Double> weights) {
        String key = computeKey(programId);
        int nextVersion = nextVersion(key);
        Instant now = Instant.now();

        List<PslWeightRow> rows = new ArrayList<>(weights.size());
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            rows.add(PslWeightRow.builder()
                    .programKey(key)
                    .version(nextVersion)
                    .ruleDisplay(entry.getKey())
                    .weight(entry.getValue())
                    .factSheetId(factSheetId)
                    .savedAt(now)
                    .build());
        }
        repo.saveAll(rows);
        log.debug("DualStoreWeightStore: saved {} weight rows for programKey='{}' at version={}",
                rows.size(), key, nextVersion);
        return nextVersion;
    }

    @Override
    public Optional<Map<String, Double>> latest(String programId) {
        String key = computeKey(programId);
        Integer maxVer = repo.findMaxVersionByProgramKey(key);
        if (maxVer == null) {
            return Optional.empty();
        }
        List<PslWeightRow> rows = repo.findByProgramKeyAndVersionOrderByRuleDisplayAsc(key, maxVer);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rowsToMap(rows));
    }

    @Override
    public int latestVersion(String programId) {
        String key = computeKey(programId);
        Integer maxVer = repo.findMaxVersionByProgramKey(key);
        return (maxVer == null) ? 0 : maxVer;
    }

    @Override
    public Optional<Map<String, Double>> get(String programId, int version) {
        String key = computeKey(programId);
        List<PslWeightRow> rows = repo.findByProgramKeyAndVersionOrderByRuleDisplayAsc(key, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rowsToMap(rows));
    }

    @Override
    public List<Integer> versions(String programId) {
        String key = computeKey(programId);
        return repo.findVersionsByProgramKey(key);
    }

    @Override
    public Set<String> programIds() {
        return repo.findDistinctProgramKeys();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Compute the DB-level program key by prepending the fact-sheet scope prefix.
     *
     * @param programId the logical program identifier
     * @return the scoped key stored in {@link PslWeightRow#getProgramKey()}
     */
    String computeKey(String programId) {
        return (factSheetId != null) ? "fs:" + factSheetId + ":" + programId : programId;
    }

    /**
     * Compute the next version number for the given (already-scoped) key.
     * Returns 1 if no rows exist yet.
     */
    private int nextVersion(String key) {
        Integer maxVer = repo.findMaxVersionByProgramKey(key);
        return (maxVer == null) ? 1 : maxVer + 1;
    }

    /**
     * Convert a list of {@link PslWeightRow}s at the same version to a ruleDisplay→weight map.
     */
    private static Map<String, Double> rowsToMap(List<PslWeightRow> rows) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (PslWeightRow row : rows) {
            result.put(row.getRuleDisplay(), row.getWeight());
        }
        return result;
    }
}
