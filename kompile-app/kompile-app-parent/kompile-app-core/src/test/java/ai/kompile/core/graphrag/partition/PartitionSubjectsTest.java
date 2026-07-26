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

package ai.kompile.core.graphrag.partition;

import ai.kompile.core.graphrag.partition.grouping.EntityGroup;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who a partition is about, and how the answer survives being written down. */
@DisplayName("Partition subjects")
class PartitionSubjectsTest {

    private static EntityPartition entityKeyed() {
        return EntityPartition.open(PartitionKey.forEntity("acme", "v1", "11"));
    }

    private static EntityPartition groupKeyed() {
        return EntityPartition.open(PartitionKey.forGroup("acme+2", "v1", "11"));
    }

    @Nested
    @DisplayName("Reading a partition")
    class Reading {

        @Test
        @DisplayName("an entity-keyed partition answers from its own key")
        void anEntityKeyedPartitionAnswersFromItsKey() {
            assertEquals(List.of("acme"), PartitionSubjects.resolve(entityKeyed()));
        }

        @Test
        @DisplayName("a group id names no entity, so an unpinned group partition says nothing")
        void aGroupKeyedPartitionWithoutAPinSaysNothing() {
            // Deliberately empty rather than ["acme+2"]: no node in any graph is called that, and
            // a channel that went looking for one would attribute whatever it found to a claim
            // nobody made.
            assertTrue(PartitionSubjects.resolve(groupKeyed()).isEmpty());
        }

        @Test
        @DisplayName("the pin wins over the key")
        void thePinWinsOverTheKey() {
            EntityPartition pinned =
                    PartitionSubjects.pinnedOn(entityKeyed(), List.of("acme", "beta"));
            assertEquals(List.of("acme", "beta"), PartitionSubjects.resolve(pinned));
        }

        @Test
        @DisplayName("pinned order is the order the group was built in")
        void pinnedOrderIsPreserved() {
            EntityPartition pinned =
                    PartitionSubjects.pinnedOn(groupKeyed(), List.of("zeta", "acme", "beta"));
            assertEquals(List.of("zeta", "acme", "beta"), PartitionSubjects.resolve(pinned));
        }

        @Test
        @DisplayName("a blank pin is not an answer; the key still is")
        void aBlankPinFallsBackToTheKey() {
            EntityPartition blank = entityKeyed().withPin(PartitionSubjects.SUBJECTS_PIN, "   ");
            assertEquals(List.of("acme"), PartitionSubjects.resolve(blank));
        }

        @Test
        @DisplayName("no partition, no subjects")
        void aNullPartitionResolvesToNothing() {
            assertTrue(PartitionSubjects.resolve(null).isEmpty());
            assertNull(PartitionSubjects.groupingVersion(null));
        }
    }

    @Nested
    @DisplayName("The pin itself")
    class ThePin {

        @Test
        @DisplayName("one subject per line")
        void subjectsAreWrittenOnePerLine() {
            assertEquals("acme\nbeta", PartitionSubjects.pin(List.of("acme", "beta")));
        }

        @Test
        @DisplayName("a comma in a title is not a delimiter")
        void aCommaInATitleIsNotADelimiter() {
            // The whole reason the delimiter is a newline: entity titles routinely contain commas.
            List<String> subjects = List.of("Acme, Inc.", "Beta Holdings, LLC");
            assertEquals(subjects, PartitionSubjects.parse(PartitionSubjects.pin(subjects)));
        }

        @Test
        @DisplayName("blanks and duplicates do not become subjects")
        void blanksAndDuplicatesAreDropped() {
            List<String> messy = Arrays.asList("acme", null, "  ", "acme", "  beta  ");
            assertEquals(List.of("acme", "beta"), PartitionSubjects.parse(
                    PartitionSubjects.pin(messy)));
        }

        @Test
        @DisplayName("a pin that came back through a store with different line endings still reads")
        void carriageReturnsSurviveTheRoundTrip() {
            assertEquals(List.of("acme", "beta"), PartitionSubjects.parse("acme\r\nbeta"));
        }

        @Test
        @DisplayName("nothing pinned is nothing read")
        void anEmptyPinParsesToNothing() {
            assertTrue(PartitionSubjects.parse(null).isEmpty());
            assertTrue(PartitionSubjects.parse("").isEmpty());
            assertTrue(PartitionSubjects.parse("\n\n").isEmpty());
            assertEquals("", PartitionSubjects.pin(List.of()));
        }
    }

    @Nested
    @DisplayName("Writing it onto a partition")
    class Writing {

        @Test
        @DisplayName("the members of a group are recorded on the partition itself")
        void aGroupsMembersAreRecordedOnThePartition() {
            EntityGroup group = EntityGroup.of(List.of("beta", "acme")).sharing("hub");
            EntityPartition pinned = PartitionSubjects.pinnedOn(groupKeyed(), group.members(),
                    GroupingPolicy.defaults().version());

            assertEquals(List.of("acme", "beta", "hub"), PartitionSubjects.resolve(pinned));
            assertEquals(GroupingPolicy.HYBRID_VERSION, PartitionSubjects.groupingVersion(pinned));
        }

        @Test
        @DisplayName("an empty membership is not written, so the key keeps answering")
        void anEmptyMembershipIsNotWritten() {
            EntityPartition untouched = PartitionSubjects.pinnedOn(entityKeyed(), List.of());
            assertEquals(entityKeyed().pins(), untouched.pins());
            assertEquals(List.of("acme"), PartitionSubjects.resolve(untouched));
        }

        @Test
        @DisplayName("the grouping is recorded even when the membership need not be written down")
        void theGroupingIsRecordedEvenWithoutAMembership() {
            // A per-entity grouping decides that a subject stands alone. That decision is worth
            // recording; the membership it produced is already in the key, so writing it again
            // would only risk erasing the better answer.
            EntityPartition alone = PartitionSubjects.pinnedOn(entityKeyed(), List.of(),
                    GroupingPolicy.PER_ENTITY_VERSION);
            assertEquals(GroupingPolicy.PER_ENTITY_VERSION,
                    PartitionSubjects.groupingVersion(alone));
            assertEquals(List.of("acme"), PartitionSubjects.resolve(alone));
        }

        @Test
        @DisplayName("a grouping nobody named is not recorded")
        void aBlankGroupingVersionIsNotRecorded() {
            EntityPartition blankVersion =
                    PartitionSubjects.pinnedOn(entityKeyed(), List.of("acme"), "  ");
            assertNull(PartitionSubjects.groupingVersion(blankVersion));
            assertEquals(List.of("acme"), PartitionSubjects.resolve(blankVersion));
        }

        @Test
        @DisplayName("pinning nothing onto nothing is nothing")
        void pinningOntoANullPartitionIsNull() {
            assertNull(PartitionSubjects.pinnedOn(null, List.of("acme")));
            assertNull(PartitionSubjects.pinnedOn(null, List.of("acme"), "grouping"));
        }
    }

    @Nested
    @DisplayName("Resolvers")
    class Resolvers {

        @Test
        @DisplayName("the pin-or-key resolver is the reading a channel gets by default")
        void theDefaultResolverReadsPinThenKey() {
            assertEquals(List.of("acme"),
                    PartitionSubjects.fromPinOrKey().apply(entityKeyed()));
            assertTrue(PartitionSubjects.fromPinOrKey().apply(groupKeyed()).isEmpty());
        }

        @Test
        @DisplayName("a fixed resolver answers for a caller that already holds the group")
        void aFixedResolverIgnoresThePartition() {
            assertEquals(List.of("acme", "beta"),
                    PartitionSubjects.fixed(List.of("acme", "beta")).apply(groupKeyed()));
            assertTrue(PartitionSubjects.fixed(null).apply(entityKeyed()).isEmpty());
        }
    }
}
