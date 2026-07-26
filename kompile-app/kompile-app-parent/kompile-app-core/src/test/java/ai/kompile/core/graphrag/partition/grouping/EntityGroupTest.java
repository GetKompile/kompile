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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A group is a durable claim: its id ends up on a partition, so the id has to follow from the
 * subjects the group owns and from nothing else.
 */
@DisplayName("EntityGroup")
class EntityGroupTest {

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("a group of one is named after its subject")
        void aSingletonIsNamedAfterItsSubject() {
            assertEquals("acme", EntityGroup.ofSingle("acme").id());
            assertEquals("acme", EntityGroup.idFor(List.of("acme")));
        }

        @Test
        @DisplayName("a group of several is named after its smallest, plus a count")
        void aGroupIsNamedAfterItsAnchor() {
            EntityGroup group = EntityGroup.of(List.of("zeta", "acme", "mint"));

            assertEquals("acme+2", group.id());
            assertEquals("acme", group.anchor());
        }

        @Test
        @DisplayName("the id does not depend on the order the subjects arrived in")
        void theIdIsOrderIndependent() {
            assertEquals(EntityGroup.of(List.of("a", "b", "c")).id(),
                    EntityGroup.of(List.of("c", "a", "b")).id());
        }

        @Test
        @DisplayName("a group needs an id and at least one subject to own")
        void aGroupCannotBeAnonymousOrEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityGroup("  ", List.of("a"), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityGroup("a", List.of(), List.of()));
            assertThrows(IllegalArgumentException.class, () -> EntityGroup.idFor(List.of()));
        }
    }

    @Nested
    @DisplayName("Membership")
    class Membership {

        @Test
        @DisplayName("the core is sorted, trimmed and de-duplicated")
        void theCoreIsCleaned() {
            EntityGroup group = EntityGroup.of(Arrays.asList("zeta", "  acme  ", "acme", "", null));

            assertEquals(List.of("acme", "zeta"), group.core());
            assertEquals(2, group.size());
        }

        @Test
        @DisplayName("a group reads its core first and its shared bridges after")
        void membersAreCoreThenBridges() {
            EntityGroup group = EntityGroup.of(List.of("b", "a")).sharing("hub");

            assertEquals(List.of("a", "b", "hub"), group.members());
            assertEquals(3, group.size());
        }

        @Test
        @DisplayName("owning a subject and merely reading one are different things")
        void owningIsNotTheSameAsReading() {
            EntityGroup group = EntityGroup.of(List.of("a", "b")).sharing("hub");

            assertTrue(group.owns("a"));
            assertFalse(group.owns("hub"));
            assertTrue(group.contains("hub"));
            assertFalse(group.contains("elsewhere"));
        }

        @Test
        @DisplayName("a group of exactly one subject knows it is alone")
        void aSingletonKnowsIt() {
            assertTrue(EntityGroup.ofSingle("acme").isSingleton());
            assertFalse(EntityGroup.of(List.of("a", "b")).isSingleton());
            assertFalse(EntityGroup.ofSingle("acme").sharing("hub").isSingleton());
        }
    }

    @Nested
    @DisplayName("Sharing a bridge")
    class Sharing {

        @Test
        @DisplayName("sharing a bridge does not rename the group")
        void sharingKeepsTheId() {
            EntityGroup group = EntityGroup.of(List.of("a", "b"));

            assertEquals(group.id(), group.sharing("hub").id());
            assertEquals(group.core(), group.sharing("hub").core());
        }

        @Test
        @DisplayName("sharing the same bridge twice adds it once")
        void sharingIsIdempotent() {
            EntityGroup shared = EntityGroup.of(List.of("a")).sharing("hub").sharing("hub");

            assertEquals(List.of("hub"), shared.sharedBridges());
        }

        @Test
        @DisplayName("a subject the group already owns is not also shared into it")
        void anOwnedSubjectIsNotShared() {
            EntityGroup group = EntityGroup.of(List.of("a", "b"));

            assertSame(group, group.sharing("a"));
        }

        @Test
        @DisplayName("sharing nothing changes nothing")
        void sharingNothingIsANoop() {
            EntityGroup group = EntityGroup.of(List.of("a"));

            assertSame(group, group.sharing(null));
            assertSame(group, group.sharing("   "));
        }

        @Test
        @DisplayName("shared bridges are cleaned like the core is")
        void sharedBridgesAreCleaned() {
            EntityGroup group = new EntityGroup("a", List.of("a"),
                    Arrays.asList("  hub  ", "hub", "", null));

            assertEquals(List.of("hub"), group.sharedBridges());
        }
    }

    @Test
    @DisplayName("a group describes what it owns and what it only reads")
    void describeSeparatesOwnedFromShared() {
        String described = EntityGroup.of(List.of("a", "b")).sharing("hub").describe();

        assertTrue(described.contains("a+1"));
        assertTrue(described.contains("2 subjects"));
        assertTrue(described.contains("hub"));
    }
}
