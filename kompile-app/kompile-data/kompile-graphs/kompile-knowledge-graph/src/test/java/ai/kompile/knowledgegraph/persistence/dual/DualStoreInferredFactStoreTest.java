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

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain JUnit 5 / Mockito tests for {@link DualStoreInferredFactStore}.
 *
 * <p>No Spring context — only the store logic and the mocked repository/materializer
 * are exercised.</p>
 */
@ExtendWith(MockitoExtension.class)
class DualStoreInferredFactStoreTest {

    @Mock
    private InferredFactRowRepository repo;

    @Mock
    private InferredFactGraphMaterializer materializer;

    private static final Long FACT_SHEET_ID = 99L;

    /** Helper: build a simple InferredFact for tests. */
    private static InferredFact makeFact(String atomKey, double value, long version, String runId) {
        return new InferredFact(
                atomKey, value, value,
                List.of(),
                List.of("rule1"),
                runId,
                version,
                Instant.now());
    }

    @BeforeEach
    void stubHydrate() {
        // By default, return no pre-existing rows from the DB so the store starts empty.
        when(repo.findLatestByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of());
    }

    // ── store() ──────────────────────────────────────────────────────────────────

    @Test
    void store_persistsRowToRepo() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        InferredFact fact = makeFact("isAlive(Alice)", 0.9, 1L, "run-1");
        store.store(fact);

        ArgumentCaptor<InferredFactRow> captor = ArgumentCaptor.forClass(InferredFactRow.class);
        verify(repo).save(captor.capture());

        InferredFactRow row = captor.getValue();
        assertThat(row.getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        assertThat(row.getAtomKey()).isEqualTo("isAlive(Alice)");
        assertThat(row.getVersion()).isEqualTo(1L);
        assertThat(row.getValue()).isEqualTo(0.9);
        assertThat(row.getRunId()).isEqualTo("run-1");
        // provenanceJson must be the round-trippable JSON of the fact
        assertThat(row.getProvenanceJson()).isNotBlank();
        InferredFact parsed = InferredFact.fromJson(row.getProvenanceJson());
        assertThat(parsed.atomKey()).isEqualTo("isAlive(Alice)");
        assertThat(parsed.value()).isEqualTo(0.9);
    }

    @Test
    void store_callsMaterializerWhenWired() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, materializer);

        InferredFact fact = makeFact("isAlive(Bob)", 0.7, 1L, "run-x");
        store.store(fact);

        verify(materializer).materialize(eq(List.of(fact)), eq(FACT_SHEET_ID));
    }

    @Test
    void store_doesNotCallMaterializer_whenNotWired() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        InferredFact fact = makeFact("isAlive(Carol)", 0.5, 1L, "run-y");
        store.store(fact);

        // materializer is not wired — verify it is never called
        // (no interaction with the @Mock materializer field)
        verify(materializer, never()).materialize(any(), any());
    }

    // ── latest() ─────────────────────────────────────────────────────────────────

    @Test
    void latest_fromDelegate_returnsStoredFact() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        InferredFact fact = makeFact("hasAge(Alice, 30)", 1.0, 1L, "run-1");
        store.store(fact);

        Optional<InferredFact> result = store.latest("hasAge(Alice, 30)");
        assertThat(result).isPresent();
        assertThat(result.get().atomKey()).isEqualTo("hasAge(Alice, 30)");
    }

    @Test
    void latest_nonExistentKey_returnsEmpty() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        Optional<InferredFact> result = store.latest("nonExistent(X)");
        assertThat(result).isEmpty();
    }

    // ── purge() ───────────────────────────────────────────────────────────────────

    @Test
    void purge_removesFromDelegateAndDb() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        InferredFact fact = makeFact("toDelete(X)", 0.8, 1L, "run-2");
        store.store(fact);
        assertThat(store.size()).isEqualTo(1);

        store.purge("toDelete(X)");

        assertThat(store.size()).isEqualTo(0);
        assertThat(store.latest("toDelete(X)")).isEmpty();
        verify(repo).deleteByFactSheetIdAndAtomKey(eq(FACT_SHEET_ID), eq("toDelete(X)"));
    }

    // ── size() / isEmpty() ───────────────────────────────────────────────────────

    @Test
    void size_reflectsStoredFacts() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);
        assertThat(store.size()).isEqualTo(0);
        assertThat(store.isEmpty()).isTrue();

        store.store(makeFact("a(X)", 0.5, 1L, "r1"));
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.isEmpty()).isFalse();

        store.store(makeFact("b(Y)", 0.6, 1L, "r1"));
        assertThat(store.size()).isEqualTo(2);
    }

    // ── factSheetId isolation ─────────────────────────────────────────────────────

    @Test
    void factSheetIsolation_queriesUseScopedFactSheetId() {
        Long otherFactSheet = 77L;
        when(repo.findLatestByFactSheetId(otherFactSheet)).thenReturn(List.of());

        DualStoreInferredFactStore storeA =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);
        DualStoreInferredFactStore storeB =
                new DualStoreInferredFactStore(repo, otherFactSheet, null);

        InferredFact factInA = makeFact("atom(X)", 0.9, 1L, "run-a");
        storeA.store(factInA);

        // Store B was constructed separately and has its own in-memory delegate
        // — so it should not see facts stored in store A
        assertThat(storeB.latest("atom(X)")).isEmpty();

        // Verify purge uses the correct factSheetId
        storeA.purge("atom(X)");
        verify(repo).deleteByFactSheetIdAndAtomKey(eq(FACT_SHEET_ID), eq("atom(X)"));
        // storeB's factSheetId was never used in a purge call
        verify(repo, never()).deleteByFactSheetIdAndAtomKey(eq(otherFactSheet), anyString());
    }

    // ── hydration from DB ─────────────────────────────────────────────────────────

    @Test
    void hydration_loadsExistingRowsIntoDelegate() {
        InferredFact preExisting = makeFact("priorFact(Z)", 0.6, 3L, "run-old");
        InferredFactRow row = InferredFactRow.builder()
                .id(1L)
                .factSheetId(FACT_SHEET_ID)
                .atomKey("priorFact(Z)")
                .version(3L)
                .value(0.6)
                .confidence(0.6)
                .runId("run-old")
                .provenanceJson(preExisting.toJson())
                .inferredAt(Instant.now())
                .build();

        // Override the default stub to return one pre-existing row
        when(repo.findLatestByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(row));

        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        // Should be pre-loaded from DB
        assertThat(store.size()).isEqualTo(1);
        Optional<InferredFact> loaded = store.latest("priorFact(Z)");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().value()).isEqualTo(0.6);
    }
}
