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
package ai.kompile.knowledgegraph.persistence;

import ai.kompile.graph.reasoning.learning.FileWeightStore;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spring-managed {@link WeightStore} backed by the infra-free {@link FileWeightStore}.
 *
 * <p>Delegates all {@link WeightStore} operations to a {@link FileWeightStore} rooted at
 * {@code <dataDir>/data/graph/reasoning/}. Also exposes
 * {@link #fileWeightStoreFor(String)} so callers can obtain a fact-sheet-scoped sub-store
 * without going through the global weight-store path.</p>
 *
 * <p>Mirrors the construction pattern of {@link ai.kompile.knowledgegraph.grounding.FileBackedInferredFactStore}:
 * if {@code kompile.data.dir} is blank the home directory ({@code ~/.kompile}) is used.</p>
 */
@Component
public class FileBackedWeightStore implements WeightStore {

    @Value("${kompile.data.dir:}")
    private String dataDir;

    private final FileWeightStore delegate;
    private final Path reasoningBase;

    public FileBackedWeightStore(@Value("${kompile.data.dir:}") String dataDir) {
        this.dataDir = dataDir;
        Path base = resolveBase(dataDir);
        this.reasoningBase = base.resolve("data").resolve("graph").resolve("reasoning");
        this.delegate = new FileWeightStore(reasoningBase);
    }

    // ── WeightStore delegation ─────────────────────────────────────────────────

    @Override
    public int save(String programId, Map<String, Double> weights) {
        return delegate.save(programId, weights);
    }

    @Override
    public int save(String programId, List<PslRule> rules) {
        return delegate.save(programId, rules);
    }

    @Override
    public Optional<Map<String, Double>> latest(String programId) {
        return delegate.latest(programId);
    }

    @Override
    public int latestVersion(String programId) {
        return delegate.latestVersion(programId);
    }

    @Override
    public Optional<Map<String, Double>> get(String programId, int version) {
        return delegate.get(programId, version);
    }

    @Override
    public List<Integer> versions(String programId) {
        return delegate.versions(programId);
    }

    @Override
    public Set<String> programIds() {
        return delegate.programIds();
    }

    // ── Spring-aware helpers ──────────────────────────────────────────────────

    /**
     * Returns a {@link FileWeightStore} scoped to a specific fact sheet's PSL weights
     * directory: {@code <reasoningBase>/<factSheetId>/psl-weights/}.
     *
     * @param factSheetId the fact sheet identifier
     * @return a fact-sheet-scoped {@link FileWeightStore}
     */
    public FileWeightStore fileWeightStoreFor(String factSheetId) {
        Path dir = reasoningBase.resolve(factSheetId).resolve("psl-weights");
        return new FileWeightStore(dir);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Path resolveBase(String dataDirValue) {
        return (dataDirValue == null || dataDirValue.isBlank())
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDirValue);
    }
}
