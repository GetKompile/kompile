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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The policy is the whole of what makes a plan reproducible, so it is versioned and every setting is
 * a value rather than a mutable knob.
 */
@DisplayName("GroupingPolicy")
class GroupingPolicyTest {

    @Nested
    @DisplayName("The shipped policies")
    class ShippedPolicies {

        @Test
        @DisplayName("the default is hybrid: capped groups, bridges kept out of them")
        void theDefaultIsHybrid() {
            GroupingPolicy policy = GroupingPolicy.defaults();

            assertEquals(GroupingPolicy.HYBRID_VERSION, policy.version());
            assertEquals(GroupingPolicy.DEFAULT_MAX_GROUP_SIZE, policy.maxGroupSize());
            assertEquals(GroupingPolicy.DEFAULT_BRIDGE_DEGREE, policy.bridgeDegree());
            assertEquals(0.0, policy.minLinkStrength());
            assertEquals(BridgePolicy.SEPARATE, policy.bridges());
        }

        @Test
        @DisplayName("per-entity grouping caps a group at one subject")
        void perEntityCapsAtOne() {
            GroupingPolicy policy = GroupingPolicy.perEntity();

            assertEquals(1, policy.maxGroupSize());
            assertEquals(0, policy.bridgeDegree());
        }

        @Test
        @DisplayName("per-entity coverage is never compared to grouped coverage")
        void perEntityCarriesItsOwnVersion() {
            assertNotEquals(GroupingPolicy.defaults().version(),
                    GroupingPolicy.perEntity().version());
        }

        @Test
        @DisplayName("the bridge degree is half the group cap")
        void theBridgeDegreeFollowsTheGroupCap() {
            assertEquals(GroupingPolicy.DEFAULT_MAX_GROUP_SIZE / 2,
                    GroupingPolicy.DEFAULT_BRIDGE_DEGREE);
        }
    }

    @Nested
    @DisplayName("Nonsense settings")
    class Nonsense {

        @Test
        @DisplayName("a negative cap means no cap, not a group that can never hold anything")
        void aNegativeCapMeansUnbounded() {
            GroupingPolicy policy = GroupingPolicy.defaults().withMaxGroupSize(-3);

            assertEquals(0, policy.maxGroupSize());
            assertTrue(policy.hasRoom(1000));
        }

        @Test
        @DisplayName("a negative floor admits everything")
        void aNegativeFloorIsNoFloor() {
            assertEquals(0.0, GroupingPolicy.defaults().withMinLinkStrength(-1.0).minLinkStrength());
        }

        @Test
        @DisplayName("an unmeasurable floor is no floor")
        void aNonFiniteFloorIsNoFloor() {
            assertEquals(0.0, GroupingPolicy.defaults().withMinLinkStrength(Double.NaN)
                    .minLinkStrength());
        }

        @Test
        @DisplayName("a missing version falls back to hybrid rather than being blank")
        void aMissingVersionFallsBack() {
            assertEquals(GroupingPolicy.HYBRID_VERSION,
                    GroupingPolicy.defaults().withVersion("   ").version());
            assertEquals(GroupingPolicy.HYBRID_VERSION,
                    GroupingPolicy.defaults().withVersion(null).version());
        }

        @Test
        @DisplayName("a missing bridge policy keeps bridges separate")
        void aMissingBridgePolicyIsSeparate() {
            assertEquals(BridgePolicy.SEPARATE, GroupingPolicy.defaults().withBridges(null).bridges());
        }
    }

    @Nested
    @DisplayName("The questions a policy answers")
    class Questions {

        @Test
        @DisplayName("a link exactly at the floor is admitted")
        void theFloorIsInclusive() {
            GroupingPolicy policy = GroupingPolicy.defaults().withMinLinkStrength(0.5);

            assertTrue(policy.admits(0.5));
            assertTrue(policy.admits(0.9));
            assertFalse(policy.admits(0.49));
        }

        @Test
        @DisplayName("a subject at the bridge degree is a bridge")
        void theBridgeDegreeIsInclusive() {
            GroupingPolicy policy = GroupingPolicy.defaults().withBridgeDegree(3);

            assertTrue(policy.isBridge(3));
            assertTrue(policy.isBridge(9));
            assertFalse(policy.isBridge(2));
        }

        @Test
        @DisplayName("a bridge degree of zero switches bridge detection off entirely")
        void degreeZeroMeansNoBridges() {
            GroupingPolicy policy = GroupingPolicy.defaults().withBridgeDegree(0);

            assertFalse(policy.isBridge(0));
            assertFalse(policy.isBridge(100));
        }

        @Test
        @DisplayName("a group at the cap has no room left")
        void theCapIsExclusive() {
            GroupingPolicy policy = GroupingPolicy.defaults().withMaxGroupSize(3);

            assertTrue(policy.hasRoom(2));
            assertFalse(policy.hasRoom(3));
            assertFalse(policy.hasRoom(4));
        }
    }

    @Nested
    @DisplayName("Changing a setting")
    class Changes {

        @Test
        @DisplayName("changing one setting leaves the others and the original alone")
        void changesAreCopies() {
            GroupingPolicy original = GroupingPolicy.defaults();
            GroupingPolicy changed = original.withMaxGroupSize(4);

            assertEquals(GroupingPolicy.DEFAULT_MAX_GROUP_SIZE, original.maxGroupSize());
            assertEquals(4, changed.maxGroupSize());
            assertEquals(original.version(), changed.version());
            assertEquals(original.bridgeDegree(), changed.bridgeDegree());
            assertEquals(original.bridges(), changed.bridges());
        }

        @Test
        @DisplayName("two policies built the same way are the same policy")
        void equalSettingsAreEqualPolicies() {
            assertEquals(GroupingPolicy.defaults().withBridgeDegree(4),
                    GroupingPolicy.defaults().withBridgeDegree(4));
        }
    }

    @Nested
    @DisplayName("How bridges are handled")
    class Bridges {

        @Test
        @DisplayName("only sharing costs a second read of the same subject")
        void onlySharingDuplicatesReads() {
            assertFalse(BridgePolicy.SEPARATE.duplicates());
            assertFalse(BridgePolicy.ATTACH.duplicates());
            assertTrue(BridgePolicy.SHARE.duplicates());
        }

        @Test
        @DisplayName("a separated bridge is the only one that ends up in no group but its own")
        void onlySeparationLeavesABridgeAlone() {
            assertFalse(BridgePolicy.SEPARATE.joinsAGroup());
            assertTrue(BridgePolicy.ATTACH.joinsAGroup());
            assertTrue(BridgePolicy.SHARE.joinsAGroup());
        }
    }

    @Test
    @DisplayName("a policy describes every setting that shapes a plan")
    void describeCoversEverySetting() {
        String described = GroupingPolicy.defaults().describe();

        assertTrue(described.contains(GroupingPolicy.HYBRID_VERSION));
        assertTrue(described.contains(String.valueOf(GroupingPolicy.DEFAULT_MAX_GROUP_SIZE)));
        assertTrue(described.contains(String.valueOf(GroupingPolicy.DEFAULT_BRIDGE_DEGREE)));
        assertTrue(described.contains(BridgePolicy.SEPARATE.name()));
    }

    @Test
    @DisplayName("an uncapped policy says so rather than printing a zero")
    void describeSaysWhenThereIsNoCap() {
        String described = GroupingPolicy.defaults().withMaxGroupSize(0).withBridgeDegree(0)
                .describe();

        assertTrue(described.contains("any size"));
        assertTrue(described.contains("never"));
    }
}
