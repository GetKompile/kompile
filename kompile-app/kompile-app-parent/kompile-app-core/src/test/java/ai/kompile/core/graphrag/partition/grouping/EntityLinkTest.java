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

package ai.kompile.core.graphrag.partition.grouping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A link is a weight between two subjects, and nothing else. What matters here is that the same pair
 * is always the same link however it was observed, and that a link can never make a subject look
 * more connected than it is.
 */
@DisplayName("EntityLink")
class EntityLinkTest {

    @Nested
    @DisplayName("Direction")
    class Direction {

        @Test
        @DisplayName("the ends are ordered, so the pair is the same whichever way it was seen")
        void endpointsAreOrdered() {
            EntityLink link = EntityLink.between("zeta", "acme");

            assertEquals("acme", link.from());
            assertEquals("zeta", link.to());
        }

        @Test
        @DisplayName("the same pair observed both ways is one link")
        void bothDirectionsAreEqual() {
            assertEquals(EntityLink.between("acme", "zeta"), EntityLink.between("zeta", "acme"));
        }

        @Test
        @DisplayName("either end can be asked for the other")
        void eitherEndFindsTheOther() {
            EntityLink link = EntityLink.between("acme", "zeta");

            assertEquals("zeta", link.other("acme"));
            assertEquals("acme", link.other("zeta"));
            assertTrue(link.touches("acme"));
            assertTrue(link.touches("zeta"));
        }

        @Test
        @DisplayName("a subject the link does not touch gets no far end")
        void anUntouchedSubjectHasNoFarEnd() {
            EntityLink link = EntityLink.between("acme", "zeta");

            assertNull(link.other("mint"));
            assertFalse(link.touches("mint"));
        }
    }

    @Nested
    @DisplayName("What it refuses to be")
    class Refusals {

        @Test
        @DisplayName("a link needs both ends")
        void bothEndsAreRequired() {
            assertThrows(IllegalArgumentException.class, () -> EntityLink.between(null, "zeta"));
            assertThrows(IllegalArgumentException.class, () -> EntityLink.between("acme", null));
            assertThrows(IllegalArgumentException.class, () -> EntityLink.between("acme", "   "));
        }

        @Test
        @DisplayName("a subject cannot be its own neighbour")
        void selfLinksAreRefused() {
            assertThrows(IllegalArgumentException.class, () -> EntityLink.between("acme", "acme"));
        }

        @Test
        @DisplayName("a self-link hiding behind whitespace is still a self-link")
        void selfLinksAreRefusedAfterTrimming() {
            assertThrows(IllegalArgumentException.class,
                    () -> EntityLink.between("acme", "  acme  "));
        }
    }

    @Nested
    @DisplayName("Strength")
    class Strength {

        @Test
        @DisplayName("relatedness with nothing measured is unit strength")
        void theDefaultIsUnitStrength() {
            assertEquals(1.0, EntityLink.between("acme", "zeta").strength());
        }

        @Test
        @DisplayName("a negative pull is no pull, not a repulsion")
        void negativeStrengthIsClamped() {
            assertEquals(0.0, EntityLink.between("acme", "zeta", -4.0).strength());
        }

        @Test
        @DisplayName("an unmeasurable strength is treated as none")
        void nonFiniteStrengthIsClamped() {
            assertEquals(0.0, EntityLink.between("acme", "zeta", Double.NaN).strength());
            assertEquals(0.0, EntityLink.between("acme", "zeta", Double.POSITIVE_INFINITY)
                    .strength());
        }

        @Test
        @DisplayName("more evidence for the same pair pulls harder")
        void observationsAddUp() {
            EntityLink stronger = EntityLink.between("acme", "zeta", 0.4).plus(0.3);

            assertEquals(0.7, stronger.strength(), 1e-9);
            assertEquals("acme", stronger.from());
            assertEquals("zeta", stronger.to());
        }

        @Test
        @DisplayName("adding negative evidence never weakens a link")
        void addingNegativeEvidenceChangesNothing() {
            assertEquals(0.4, EntityLink.between("acme", "zeta", 0.4).plus(-1.0).strength(), 1e-9);
        }
    }

    @Test
    @DisplayName("a link describes itself as a pair and a weight")
    void describeNamesBothEndsAndTheWeight() {
        String described = EntityLink.between("zeta", "acme", 0.5).describe();

        assertTrue(described.contains("acme"));
        assertTrue(described.contains("zeta"));
        assertTrue(described.contains("0.50"));
    }
}
