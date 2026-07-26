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

package ai.kompile.core.crawl.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.grouping.BridgePolicy;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The configuration surface for the partition pass.
 *
 * <p>The behaviour worth protecting is the version discipline. A tuned knob must never file its
 * claim under the stock policy's name, because the policy version is the entire basis for saying
 * two coverage claims are comparable — and an untouched configuration must reproduce the stock
 * policies exactly, or turning the surface on would silently re-partition every existing corpus.</p>
 */
@DisplayName("PartitionPassConfig")
class PartitionPassConfigTest {

    @Nested
    @DisplayName("A configuration nobody has touched")
    class Defaults {

        @Test
        @DisplayName("reproduces the stock grouping policy exactly, version included")
        void reproducesStockGrouping() {
            GroupingPolicy policy = PartitionPassConfig.defaults().groupingPolicy();

            assertEquals(GroupingPolicy.defaults(), policy);
            assertEquals(GroupingPolicy.HYBRID_VERSION, policy.version());
        }

        @Test
        @DisplayName("overrides no discovery policy, so the pass keeps the one it narrowed")
        void overridesNoDiscoveryPolicy() {
            // Null is load-bearing: returning the stock policy here would override the pass's own
            // narrowing when the vector index is not searchable, claiming coverage nobody searched.
            assertNull(PartitionPassConfig.defaults().discoveryOverride());
        }

        @Test
        @DisplayName("applies no batch caps")
        void appliesNoBatchCaps() {
            assertFalse(PartitionPassConfig.defaults().hasBatchCaps());
        }

        @Test
        @DisplayName("a config deserialised without the block still has one")
        void deserialisesWithoutTheBlock() throws Exception {
            GraphExtractionConfig config = new ObjectMapper()
                    .readValue("{\"maxTokens\":2048}", GraphExtractionConfig.class);

            assertEquals(GroupingPolicy.defaults(), config.getPartition().groupingPolicy());
        }

        @Test
        @DisplayName("a null block reads as the defaults rather than as a null")
        void nullBlockReadsAsDefaults() {
            GraphExtractionConfig config = GraphExtractionConfig.builder().partition(null).build();

            assertEquals(GroupingPolicy.defaults(), config.getPartition().groupingPolicy());
        }
    }

    @Nested
    @DisplayName("Naming a version")
    class NamedVersions {

        @Test
        @DisplayName("a named grouping version is used verbatim, so a re-grouping stays legible")
        void namedGroupingIsUsedVerbatim() {
            GroupingPolicy policy = PartitionPassConfig.builder()
                    .groupingVersion("grouping-2026-legal-review")
                    .maxGroupSize(4)
                    .build()
                    .groupingPolicy();

            assertEquals("grouping-2026-legal-review", policy.version());
            assertEquals(4, policy.maxGroupSize());
        }

        @Test
        @DisplayName("a named discovery version always overrides, even at stock settings")
        void namedDiscoveryAlwaysOverrides() {
            DiscoveryPolicy policy = PartitionPassConfig.builder()
                    .discoveryVersion("discovery-2026-wide")
                    .build()
                    .discoveryOverride();

            assertEquals("discovery-2026-wide", policy.version());
        }
    }

    @Nested
    @DisplayName("Moving a knob without renaming")
    class TunedVersions {

        @Test
        @DisplayName("a tuned grouping is a new partition, never a redefinition of the stock one")
        void tunedGroupingGetsItsOwnVersion() {
            GroupingPolicy tuned = PartitionPassConfig.builder().maxGroupSize(4).build()
                    .groupingPolicy();

            assertNotEquals(GroupingPolicy.HYBRID_VERSION, tuned.version());
            assertTrue(tuned.version().startsWith(GroupingPolicy.HYBRID_VERSION));
            assertTrue(tuned.version().contains(PartitionPassConfig.TUNED_MARKER));
        }

        @Test
        @DisplayName("a tuned discovery policy likewise")
        void tunedDiscoveryGetsItsOwnVersion() {
            DiscoveryPolicy tuned = PartitionPassConfig.builder().maxRounds(7).build()
                    .discoveryOverride();

            assertTrue(tuned.version().contains(PartitionPassConfig.TUNED_MARKER));
            assertEquals(7, tuned.maxRounds());
        }

        @Test
        @DisplayName("the derived version is stable across JVMs, so a partition id keeps its meaning")
        void derivedVersionIsStable() {
            String first = PartitionPassConfig.builder().maxGroupSize(4).build()
                    .groupingPolicy().version();
            String second = PartitionPassConfig.builder().maxGroupSize(4).build()
                    .groupingPolicy().version();

            assertEquals(first, second);
        }

        @Test
        @DisplayName("different settings fingerprint differently")
        void differentSettingsDiffer() {
            String four = PartitionPassConfig.builder().maxGroupSize(4).build()
                    .groupingPolicy().version();
            String five = PartitionPassConfig.builder().maxGroupSize(5).build()
                    .groupingPolicy().version();

            assertNotEquals(four, five);
        }

        @Test
        @DisplayName("restating a stock value is not a tuning")
        void restatingStockIsNotTuning() {
            GroupingPolicy policy = PartitionPassConfig.builder()
                    .maxGroupSize(GroupingPolicy.DEFAULT_MAX_GROUP_SIZE)
                    .bridgeDegree(GroupingPolicy.DEFAULT_BRIDGE_DEGREE)
                    .bridges(BridgePolicy.SEPARATE)
                    .build()
                    .groupingPolicy();

            assertEquals(GroupingPolicy.HYBRID_VERSION, policy.version());
            assertFalse(policy.version().contains(PartitionPassConfig.TUNED_MARKER));
        }
    }

    @Nested
    @DisplayName("Choosing channels")
    class Channels {

        @Test
        @DisplayName("names resolve case-insensitively")
        void namesResolveCaseInsensitively() {
            Set<DiscoveryChannel> resolved = PartitionPassConfig.builder()
                    .channels(List.of("semantic", "Direct_Identifier"))
                    .build()
                    .channelSet();

            assertEquals(Set.of(DiscoveryChannel.SEMANTIC, DiscoveryChannel.DIRECT_IDENTIFIER),
                    resolved);
        }

        @Test
        @DisplayName("an unrecognised channel is rejected rather than quietly dropped")
        void unknownChannelIsRejected() {
            PartitionPassConfig config = PartitionPassConfig.builder()
                    .channels(List.of("semantic", "telepathy"))
                    .build();

            // Dropping it would run one fewer way of looking and still call the result complete.
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, config::channelSet);
            assertTrue(thrown.getMessage().contains("telepathy"));
        }

        @Test
        @DisplayName("no channels named keeps the policy's own defaults")
        void noChannelsKeepsDefaults() {
            assertNull(PartitionPassConfig.defaults().channelSet());
        }

        @Test
        @DisplayName("a narrowed channel set is a tuned policy, not the stock one")
        void narrowedChannelsAreTuned() {
            DiscoveryPolicy policy = PartitionPassConfig.builder()
                    .channels(List.of("direct_identifier"))
                    .build()
                    .discoveryOverride();

            assertEquals(Set.of(DiscoveryChannel.DIRECT_IDENTIFIER), policy.channels());
            assertTrue(policy.version().contains(PartitionPassConfig.TUNED_MARKER));
        }
    }

    @Nested
    @DisplayName("Batch caps")
    class BatchCaps {

        @Test
        @DisplayName("either cap alone counts as configured")
        void eitherCapCounts() {
            assertTrue(PartitionPassConfig.builder().maxItemsPerBatch(50).build().hasBatchCaps());
            assertTrue(PartitionPassConfig.builder().targetCostPerBatch(1_000L).build()
                    .hasBatchCaps());
        }

        @Test
        @DisplayName("how work was batched does not change what was looked at, so no version moves")
        void batchingDoesNotMoveTheVersion() {
            PartitionPassConfig config = PartitionPassConfig.builder()
                    .maxItemsPerBatch(50)
                    .targetCostPerBatch(1_000L)
                    .build();

            assertEquals(GroupingPolicy.defaults(), config.groupingPolicy());
            assertNull(config.discoveryOverride());
        }
    }
}
