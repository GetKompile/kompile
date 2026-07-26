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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The plan is what a caller reads back: which groups exist, who owns a subject, and what the
 * grouping cost. It answers those questions without the caller re-deriving anything.
 */
@DisplayName("GroupingPlan")
class GroupingPlanTest {

    private static final EntityGroup PAIR = EntityGroup.of(List.of("acme", "zeta"));
    private static final EntityGroup LONER = EntityGroup.ofSingle("mint");

    private static GroupingPlan planOf(List<EntityGroup> groups, List<String> bridges) {
        return new GroupingPlan(GroupingPolicy.defaults(), groups, bridges, List.of());
    }

    @Nested
    @DisplayName("Finding a subject")
    class Finding {

        private final GroupingPlan plan = planOf(List.of(PAIR, LONER), List.of());

        @Test
        @DisplayName("a subject is owned by exactly one group")
        void aSubjectHasOneOwner() {
            assertEquals(PAIR, plan.owner("acme").orElseThrow());
            assertEquals(LONER, plan.owner("mint").orElseThrow());
        }

        @Test
        @DisplayName("a subject nobody covers has no owner")
        void anUncoveredSubjectHasNoOwner() {
            assertTrue(plan.owner("elsewhere").isEmpty());
            assertTrue(plan.groupsFor("elsewhere").isEmpty());
        }

        @Test
        @DisplayName("asking about nothing returns nothing rather than failing")
        void aBlankSubjectIsNotAnError() {
            assertTrue(plan.owner(null).isEmpty());
            assertTrue(plan.owner("   ").isEmpty());
            assertTrue(plan.groupsFor(null).isEmpty());
            assertTrue(plan.groupsFor("").isEmpty());
        }

        @Test
        @DisplayName("a subject is found however it was spelled around the edges")
        void lookupsAreTrimmed() {
            assertEquals(PAIR, plan.owner("  acme  ").orElseThrow());
            assertEquals(List.of(PAIR), plan.groupsFor("  acme  "));
        }
    }

    @Nested
    @DisplayName("Bridges in the plan")
    class Bridges {

        @Test
        @DisplayName("a shared bridge is read by every group that holds it")
        void aSharedBridgeIsFoundInEveryGroup() {
            GroupingPlan plan = planOf(
                    List.of(PAIR.sharing("hub"), LONER.sharing("hub")), List.of("hub"));

            assertEquals(2, plan.groupsFor("hub").size());
            assertTrue(plan.owner("hub").isEmpty());
            assertTrue(plan.isBridge("hub"));
        }

        @Test
        @DisplayName("the cost of sharing is counted, not hidden")
        void duplicatedReadsAreCounted() {
            GroupingPlan plan = planOf(
                    List.of(PAIR.sharing("hub"), LONER.sharing("hub")), List.of("hub"));

            assertEquals(2, plan.duplicatedReads());
        }

        @Test
        @DisplayName("a plan with no sharing costs nothing extra")
        void nothingSharedCostsNothing() {
            assertEquals(0, planOf(List.of(PAIR, LONER), List.of()).duplicatedReads());
        }

        @Test
        @DisplayName("a subject that is not a bridge is not reported as one")
        void onlyBridgesAreBridges() {
            GroupingPlan plan = planOf(List.of(PAIR), List.of("hub"));

            assertFalse(plan.isBridge("acme"));
            assertFalse(plan.isBridge(null));
            assertTrue(plan.isBridge("  hub  "));
        }
    }

    @Nested
    @DisplayName("What the plan covers")
    class Coverage {

        @Test
        @DisplayName("coverage counts subjects, not reads")
        void aSharedSubjectIsCountedOnce() {
            GroupingPlan plan = planOf(
                    List.of(PAIR.sharing("hub"), LONER.sharing("hub")), List.of("hub"));

            assertEquals(4, plan.subjectsCovered());
        }

        @Test
        @DisplayName("a plan with no groups covers nothing and says so")
        void anEmptyPlanIsEmpty() {
            GroupingPlan plan = GroupingPlan.empty(GroupingPolicy.defaults());

            assertTrue(plan.isEmpty());
            assertEquals(0, plan.subjectsCovered());
            assertEquals(0, plan.duplicatedReads());
            assertTrue(plan.groups().isEmpty());
            assertTrue(plan.bridges().isEmpty());
            assertTrue(plan.notes().isEmpty());
        }

        @Test
        @DisplayName("an empty plan still remembers the policy that produced it")
        void anEmptyPlanKeepsItsPolicy() {
            GroupingPolicy policy = GroupingPolicy.perEntity();

            assertEquals(policy, GroupingPlan.empty(policy).policy());
        }
    }

    @Test
    @DisplayName("a plan describes its size, its coverage and its bridges")
    void describeCoversTheShapeOfThePlan() {
        String described = planOf(List.of(PAIR, LONER), List.of("hub")).describe();

        assertTrue(described.contains("2 groups"));
        assertTrue(described.contains("3 subjects"));
        assertTrue(described.contains("1 bridge"));
    }
}
