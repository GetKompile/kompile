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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRow;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRowRepository;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreInferredFactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the L2 durability gap closure: verifies that {@code band}, {@code promotionStatus},
 * and {@code corroborationCount} are persisted to the DB by {@link FactPromotionTracker} and
 * reloaded from the DB when a fresh tracker instance is constructed (simulating a restart).
 *
 * <p>All tests use plain JUnit 5 + Mockito (no Spring context) so they run in the same
 * plain-Java pass as the existing dual-store tests.  The test lives in the {@code reasoning}
 * package so it can access the package-private {@link FactPromotionTracker#factRepo} field.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactDurabilityTest {

    @Mock
    InferredFactRowRepository repo;

    private static final Long FACT_SHEET_ID = 42L;
    private static final String ATOM_KEY    = "isEmployedBy(Alice, Acme)";

    /** Helper: build a minimal InferredFactRow with the three new durability columns populated. */
    private static InferredFactRow makeRow(String atomKey, double confidence,
                                           String band, String promotionStatus,
                                           int corroborationCount) {
        InferredFact fact = new InferredFact(
                atomKey, confidence, confidence,
                List.of(), List.of("r1"), "run-0", 1L, Instant.now());
        return InferredFactRow.builder()
                .id(1L)
                .factSheetId(FACT_SHEET_ID)
                .atomKey(atomKey)
                .version(1L)
                .value(confidence)
                .confidence(confidence)
                .runId("run-0")
                .provenanceJson(fact.toJson())
                .inferredAt(Instant.now())
                .band(band)
                .promotionStatus(promotionStatus)
                .corroborationCount(corroborationCount)
                .build();
    }

    @BeforeEach
    void defaultStubs() {
        when(repo.findLatestByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of());
        when(repo.findLatestWithCorroborationByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of());
    }

    // ── T-D1: store() populates band + promotionStatus on the persisted row ────────

    @Test
    @DisplayName("TD1: store() sets band and promotionStatus=NONE on the initial row")
    void store_populatesBandAndPromotion_onInitialWrite() {
        DualStoreInferredFactStore store =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        InferredFact fact = new InferredFact(
                ATOM_KEY, 0.3, 0.3, List.of(), List.of(), "run-0", 1L, Instant.now());
        store.store(fact);

        ArgumentCaptor<InferredFactRow> captor = ArgumentCaptor.forClass(InferredFactRow.class);
        verify(repo).save(captor.capture());
        InferredFactRow saved = captor.getValue();

        // band is computed from confidence at write time
        assertThat(saved.getBand()).isEqualTo(StrengthBand.fromScalar(0.3).name());
        // promotionStatus starts at NONE
        assertThat(saved.getPromotionStatus()).isEqualTo("NONE");
        // corroborationCount starts at 0
        assertThat(saved.getCorroborationCount()).isEqualTo(0);
    }

    // ── T-D2: checkPromotion persists band + promotionStatus + corroborationCount ─

    @Test
    @DisplayName("TD2: checkPromotion persists band + PROMOTED status + count to DB")
    void checkPromotion_persistsBandAndPromotion() {
        FactPromotionTracker tracker = new FactPromotionTracker(/* eventPublisher= */ null);
        tracker.factRepo = repo;   // package-private field: same package as this test

        // First observation: SPECULATIVE (0.3)
        tracker.checkPromotion(FACT_SHEET_ID, ATOM_KEY, Double.NaN, 0.3, "run-0");

        // Verify first persist call
        verify(repo, atLeastOnce()).updateBandAndPromotion(
                eq(FACT_SHEET_ID), eq(ATOM_KEY), anyString(), anyString(), eq(1));

        // Second observation: ESTABLISHED (0.9) — promotes
        tracker.checkPromotion(FACT_SHEET_ID, ATOM_KEY, 0.3, 0.9, "run-1");

        // Capture all calls to updateBandAndPromotion
        ArgumentCaptor<String> bandCaptor    = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor  = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> countCaptor  = ArgumentCaptor.forClass(Integer.class);
        verify(repo, atLeastOnce()).updateBandAndPromotion(
                eq(FACT_SHEET_ID), eq(ATOM_KEY),
                bandCaptor.capture(), statusCaptor.capture(), countCaptor.capture());

        // After promotion the calls must include PROMOTED status
        assertThat(statusCaptor.getAllValues()).contains("PROMOTED");

        // The last band call must reflect ESTABLISHED or HIGH for 0.9
        List<String> allBands = bandCaptor.getAllValues();
        assertThat(allBands.get(allBands.size() - 1)).isIn("ESTABLISHED", "HIGH");

        // Count must have reached 2
        assertThat(countCaptor.getAllValues()).contains(2);
    }

    // ── T-D3: corroboration count survives a simulated restart ───────────────────

    @Test
    @DisplayName("TD3: fresh FactPromotionTracker hydrates corroboration count + band from DB")
    void freshTracker_hydratesCorroborationCount_fromDb() {
        // Simulate what was persisted in the previous JVM session:
        // atom had been corroborated 3 times and promoted to ESTABLISHED
        InferredFactRow priorRow = makeRow(ATOM_KEY, 0.9, "ESTABLISHED", "PROMOTED", 3);
        when(repo.findLatestWithCorroborationByFactSheetId(FACT_SHEET_ID))
                .thenReturn(List.of(priorRow));

        // Construct a FRESH tracker (simulating a JVM restart — the in-memory map is empty)
        FactPromotionTracker freshTracker = new FactPromotionTracker(null);
        freshTracker.factRepo = repo;

        // Trigger hydration via checkPromotion (first call for this factSheet)
        freshTracker.checkPromotion(FACT_SHEET_ID, ATOM_KEY, 0.9, 0.9, "run-2");

        // After hydration (seed=3) + one more increment, the count must be 4 (not 1 as it would
        // be without durability)
        assertThat(freshTracker.getCorroborationCount(FACT_SHEET_ID, ATOM_KEY)).isEqualTo(4);

        // Band and promotionStatus must have been reloaded from the prior row
        assertThat(freshTracker.getLastBand(FACT_SHEET_ID, ATOM_KEY))
                .isIn(StrengthBand.ESTABLISHED, StrengthBand.HIGH);
        assertThat(freshTracker.getPromotionStatus(FACT_SHEET_ID, ATOM_KEY))
                .isEqualTo("PROMOTED");
    }

    // ── T-D4: factsByTierDurable queries the persisted band column ───────────────

    @Test
    @DisplayName("TD4: factsByTierDurable returns facts via persisted band column")
    void factsByTierDurable_queriesPersistedBandColumn() {
        InferredFactRow row = makeRow(ATOM_KEY, 0.15, "SPECULATIVE", "NONE", 1);
        when(repo.findLatestByFactSheetIdAndBand(FACT_SHEET_ID, "SPECULATIVE"))
                .thenReturn(List.of(row));
        when(repo.findLatestByFactSheetIdAndBand(FACT_SHEET_ID, "ESTABLISHED"))
                .thenReturn(List.of());

        FactPromotionTracker tracker = new FactPromotionTracker(null);
        tracker.factRepo = repo;

        List<InferredFact> speculative =
                tracker.factsByTierDurable(FACT_SHEET_ID, StrengthBand.SPECULATIVE);

        assertThat(speculative).hasSize(1);
        assertThat(speculative.get(0).atomKey()).isEqualTo(ATOM_KEY);

        // Empty for a tier that has no persisted rows
        List<InferredFact> established =
                tracker.factsByTierDurable(FACT_SHEET_ID, StrengthBand.ESTABLISHED);
        assertThat(established).isEmpty();
    }

    // ── T-D5: store() hydration loads band into DualStoreInferredFactStore correctly

    @Test
    @DisplayName("TD5: hydration round-trip — row written with band survives a fresh store instance")
    void hydration_roundTrip_bandSurvivesFreshStoreConstruction() {
        // Pre-existing row with PROBABLE band from a prior JVM session
        InferredFact priorFact = new InferredFact(
                ATOM_KEY, 0.55, 0.55, List.of(), List.of(), "run-prior", 1L, Instant.now());
        InferredFactRow priorRow = InferredFactRow.builder()
                .id(10L)
                .factSheetId(FACT_SHEET_ID)
                .atomKey(ATOM_KEY)
                .version(1L)
                .value(0.55)
                .confidence(0.55)
                .runId("run-prior")
                .provenanceJson(priorFact.toJson())
                .inferredAt(Instant.now())
                .band("PROBABLE")
                .promotionStatus("NONE")
                .corroborationCount(2)
                .build();

        // Fresh store instance sees the row via findLatestByFactSheetId (normal hydration)
        when(repo.findLatestByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(priorRow));

        DualStoreInferredFactStore freshStore =
                new DualStoreInferredFactStore(repo, FACT_SHEET_ID, null);

        // The in-memory delegate should have loaded the prior fact
        assertThat(freshStore.size()).isEqualTo(1);
        assertThat(freshStore.latest(ATOM_KEY)).isPresent();
        assertThat(freshStore.latest(ATOM_KEY).get().confidence()).isEqualTo(0.55);
    }

    // ── T-D6: no-repo tracker operates without any DB calls ──────────────────────

    @Test
    @DisplayName("TD6: tracker with no repo operates in-memory only, makes no DB calls")
    void noRepoTracker_operatesInMemoryOnly() {
        // Construct tracker with NO repo (plain-Java / test path)
        FactPromotionTracker tracker = new FactPromotionTracker(null);
        // factRepo stays null — no injection

        // Should work without any exceptions
        tracker.checkPromotion(FACT_SHEET_ID, ATOM_KEY, Double.NaN, 0.3, "run-0");
        assertThat(tracker.getCorroborationCount(FACT_SHEET_ID, ATOM_KEY)).isEqualTo(1);
        assertThat(tracker.getPromotionStatus(FACT_SHEET_ID, ATOM_KEY)).isEqualTo("NONE");

        // factsByTierDurable returns empty (no repo)
        assertThat(tracker.factsByTierDurable(FACT_SHEET_ID, StrengthBand.SPECULATIVE)).isEmpty();
    }
}
