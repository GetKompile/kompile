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

import ai.kompile.graph.reasoning.learning.MebnWeightSerializer;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 (no Spring) tests for {@link MebnWeightPersistenceAdapter}.
 */
class MebnWeightPersistenceAdapterTest {

    @TempDir
    Path tempDir;

    /**
     * Build a small MTheory with edge strengths, persist, reload and verify the JSON is
     * equivalent (since MTheory does not define value equality).
     */
    @Test
    void persistAndLoadRoundTrip() throws Exception {
        MebnWeightPersistenceAdapter adapter = adapterFor(tempDir.toString());

        MTheory theory = buildTheoryWithEdges();
        String jsonBefore = MebnWeightSerializer.strengthsToJson(theory);

        adapter.persist(42L, theory);

        // Build a fresh theory with same structure to apply loaded strengths onto
        MTheory theoryForLoad = buildTheoryWithEdges();
        boolean loaded = adapter.load(42L, theoryForLoad);
        assertTrue(loaded, "load() must return true when a file exists");

        String jsonAfterLoad = MebnWeightSerializer.strengthsToJson(theoryForLoad);
        assertEquals(jsonBefore, jsonAfterLoad,
                "Strengths JSON must be identical after persist+load round-trip");
    }

    /**
     * Loading from an empty temp dir must return false without throwing.
     */
    @Test
    void loadReturnsEmptyWhenFileAbsent() throws Exception {
        MebnWeightPersistenceAdapter adapter = adapterFor(tempDir.toString());

        MTheory theory = buildTheoryWithEdges();
        boolean result = adapter.load(99L, theory);
        assertFalse(result, "load() must return false when no file exists");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MebnWeightPersistenceAdapter adapterFor(String dataDirPath) throws Exception {
        MebnWeightPersistenceAdapter adapter = new MebnWeightPersistenceAdapter();
        Field f = MebnWeightPersistenceAdapter.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(adapter, dataDirPath);
        return adapter;
    }

    /**
     * Build a simple MTheory with one MFrag containing an edge with a non-trivial strength.
     * The MFrag has one resident RV ("isActive") and one parent edge ("cause" → "isActive", 0.7).
     */
    private MTheory buildTheoryWithEdges() {
        EntityType thing = new EntityType("Thing");
        RandomVariable cause = new RandomVariable("cause", List.of(thing),
                RandomVariable.NodeRole.RESIDENT);
        RandomVariable isActive = new RandomVariable("isActive", List.of(thing),
                RandomVariable.NodeRole.RESIDENT);

        MFrag frag = new MFrag("TestFrag")
                .addResidentNode(cause)
                .addResidentNode(isActive)
                .addParentEdge("cause", "isActive", 0.7);

        MTheory theory = new MTheory("TestTheory");
        theory.addEntityType(thing);
        theory.addMFrag(frag);
        return theory;
    }
}
