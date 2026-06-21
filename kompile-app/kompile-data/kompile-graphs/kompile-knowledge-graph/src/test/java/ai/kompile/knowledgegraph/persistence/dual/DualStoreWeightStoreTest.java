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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit 5 / Mockito tests for {@link DualStoreWeightStore}.
 *
 * <p>No Spring context — only the store logic and the mocked repository are exercised.</p>
 */
@ExtendWith(MockitoExtension.class)
class DualStoreWeightStoreTest {

    @Mock
    private PslWeightRowRepository repo;

    private static final Long FACT_SHEET_ID = 42L;
    private static final String PROGRAM_ID = "my-program";

    // ── save() ───────────────────────────────────────────────────────────────────

    @Test
    void save_firstVersion_returnsOne() {
        // Arrange: no rows exist yet → max version = null
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(null);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        Map<String, Double> weights = Map.of("rule1", 0.8);

        // Act
        int version = store.save(PROGRAM_ID, weights);

        // Assert
        assertThat(version).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PslWeightRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<PslWeightRow> rows = captor.getValue();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
        assertThat(rows.get(0).getRuleDisplay()).isEqualTo("rule1");
        assertThat(rows.get(0).getWeight()).isEqualTo(0.8);
    }

    @Test
    void save_secondVersion_returnsTwo() {
        // Arrange: one row already exists at version 1
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(1);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        Map<String, Double> weights = Map.of("rule1", 0.9, "rule2", 0.7);

        // Act
        int version = store.save(PROGRAM_ID, weights);

        // Assert
        assertThat(version).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PslWeightRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<PslWeightRow> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        rows.forEach(r -> assertThat(r.getVersion()).isEqualTo(2));
    }

    // ── latest() ─────────────────────────────────────────────────────────────────

    @Test
    void latest_noRows_returnsEmpty() {
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(null);

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        Optional<Map<String, Double>> result = store.latest(PROGRAM_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void latest_rowsExist_returnsMap() {
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(3);

        PslWeightRow row1 = PslWeightRow.builder()
                .programKey("fs:42:my-program")
                .version(3)
                .ruleDisplay("rule-A")
                .weight(0.75)
                .factSheetId(FACT_SHEET_ID)
                .savedAt(java.time.Instant.now())
                .build();
        PslWeightRow row2 = PslWeightRow.builder()
                .programKey("fs:42:my-program")
                .version(3)
                .ruleDisplay("rule-B")
                .weight(0.55)
                .factSheetId(FACT_SHEET_ID)
                .savedAt(java.time.Instant.now())
                .build();
        when(repo.findByProgramKeyAndVersionOrderByRuleDisplayAsc(anyString(), eq(3)))
                .thenReturn(List.of(row1, row2));

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        Optional<Map<String, Double>> result = store.latest(PROGRAM_ID);

        assertThat(result).isPresent();
        Map<String, Double> weights = result.get();
        assertThat(weights).containsEntry("rule-A", 0.75)
                           .containsEntry("rule-B", 0.55);
    }

    // ── versions() ───────────────────────────────────────────────────────────────

    @Test
    void versions_delegatesToRepo() {
        String expectedKey = "fs:42:my-program";
        when(repo.findVersionsByProgramKey(expectedKey)).thenReturn(List.of(1, 2, 3));

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        List<Integer> versions = store.versions(PROGRAM_ID);

        assertThat(versions).containsExactly(1, 2, 3);
        verify(repo).findVersionsByProgramKey(expectedKey);
    }

    // ── Key isolation ─────────────────────────────────────────────────────────────

    @Test
    void projectIsolation_keysAreScopedToFactSheet() {
        DualStoreWeightStore storeFs1 = new DualStoreWeightStore(repo, 1L);
        DualStoreWeightStore storeFs2 = new DualStoreWeightStore(repo, 2L);

        String key1 = storeFs1.computeKey(PROGRAM_ID);
        String key2 = storeFs2.computeKey(PROGRAM_ID);

        assertThat(key1).startsWith("fs:1:");
        assertThat(key2).startsWith("fs:2:");
        assertThat(key1).isNotEqualTo(key2);

        // Global store (no factSheetId) uses the programId directly
        DualStoreWeightStore globalStore = new DualStoreWeightStore(repo, null);
        String globalKey = globalStore.computeKey(PROGRAM_ID);
        assertThat(globalKey).isEqualTo(PROGRAM_ID);
        assertThat(globalKey).doesNotContain("fs:");
    }

    // ── programIds() / latestVersion() ───────────────────────────────────────────

    @Test
    void programIds_delegatesToRepo() {
        when(repo.findDistinctProgramKeys()).thenReturn(Set.of("fs:42:p1", "fs:42:p2"));

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        Set<String> ids = store.programIds();

        assertThat(ids).containsExactlyInAnyOrder("fs:42:p1", "fs:42:p2");
        verify(repo).findDistinctProgramKeys();
    }

    @Test
    void latestVersion_noRows_returnsZero() {
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(null);

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        assertThat(store.latestVersion(PROGRAM_ID)).isEqualTo(0);
    }

    @Test
    void latestVersion_rowsExist_returnsMaxVersion() {
        when(repo.findMaxVersionByProgramKey(anyString())).thenReturn(5);

        DualStoreWeightStore store = new DualStoreWeightStore(repo, FACT_SHEET_ID);
        assertThat(store.latestVersion(PROGRAM_ID)).isEqualTo(5);
    }
}
