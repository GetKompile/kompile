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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the planner promises: subjects that share evidence are read together, a hub never collapses
 * the corpus into one partition, groups stay small enough to finish, and the same corpus always
 * produces the same partition ids.
 */
@DisplayName("EntityGroupPlanner")
class EntityGroupPlannerTest {

    /** A hub linked to six spokes, two of which are also linked to each other. */
    private static final List<String> HUB_AND_SPOKES =
            List.of("hub", "s1", "s2", "s3", "s4", "s5", "s6");

    private static List<EntityLink> hubLinks() {
        List<EntityLink> links = new ArrayList<>();
        for (int spoke = 1; spoke <= 6; spoke++) {
            links.add(EntityLink.between("hub", "s" + spoke));
        }
        links.add(EntityLink.between("s1", "s2"));
        return links;
    }

    private static EntityGroupPlanner withBridges(BridgePolicy bridges) {
        return new EntityGroupPlanner(GroupingPolicy.defaults().withBridges(bridges));
    }

    private static List<String> idsOf(GroupingPlan plan) {
        return plan.groups().stream().map(EntityGroup::id).toList();
    }

    private static boolean noted(GroupingPlan plan, String fragment) {
        return plan.notes().stream().anyMatch(note -> note.contains(fragment));
    }

    @Nested
    @DisplayName("Deciding what belongs together")
    class Grouping {

        @Test
        @DisplayName("subjects with nothing pulling them together get a partition each")
        void unlinkedSubjectsAreReadSeparately() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("acme", "zeta", "mint"));

            assertEquals(List.of("acme", "mint", "zeta"), idsOf(plan));
            assertTrue(plan.groups().stream().allMatch(EntityGroup::isSingleton));
            assertTrue(plan.notes().isEmpty());
        }

        @Test
        @DisplayName("two linked subjects are read together, under one id")
        void linkedSubjectsShareAPartition() {
            GroupingPlan plan = EntityGroupPlanner.hybrid()
                    .plan(List.of("acme", "zeta"), List.of(EntityLink.between("acme", "zeta")));

            assertEquals(List.of("acme+1"), idsOf(plan));
            assertEquals(List.of("acme", "zeta"), plan.groups().get(0).core());
        }

        @Test
        @DisplayName("a chain of links is one neighbourhood, not three pairs")
        void transitiveLinksFormOneGroup() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b", "c"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("b", "c")));

            assertEquals(List.of("a+2"), idsOf(plan));
            assertEquals(3, plan.subjectsCovered());
        }

        @Test
        @DisplayName("neighbourhoods that never touch stay separate partitions")
        void disjointNeighbourhoodsStaySeparate() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b", "c", "d"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("c", "d")));

            assertEquals(List.of("a+1", "c+1"), idsOf(plan));
        }

        @Test
        @DisplayName("a subject in no link is not left out of the plan")
        void anIsolatedSubjectIsStillCovered() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b", "z"),
                    List.of(EntityLink.between("a", "b")));

            assertEquals(List.of("a+1", "z"), idsOf(plan));
            assertEquals(3, plan.subjectsCovered());
        }

        @Test
        @DisplayName("nothing to group is an empty plan, not a group of nothing")
        void nothingToGroupIsAnEmptyPlan() {
            assertTrue(EntityGroupPlanner.hybrid().plan((List<String>) null).isEmpty());
            assertTrue(EntityGroupPlanner.hybrid().plan(List.of()).isEmpty());
            assertTrue(EntityGroupPlanner.hybrid().plan(Arrays.asList(null, "", "  ")).isEmpty());
        }

        @Test
        @DisplayName("the same subject written two ways is one subject")
        void subjectsAreCleanedBeforeGrouping() {
            GroupingPlan plan = EntityGroupPlanner.hybrid()
                    .plan(Arrays.asList("acme", "  acme  ", "", null, "zeta"));

            assertEquals(List.of("acme", "zeta"), idsOf(plan));
        }
    }

    @Nested
    @DisplayName("Deciding which links count")
    class WhichLinksCount {

        /** Everything here is judged against the same floor. */
        private final EntityGroupPlanner planner = new EntityGroupPlanner(
                GroupingPolicy.defaults().withMinLinkStrength(0.5));

        @Test
        @DisplayName("a link too weak to matter does not pull two subjects together")
        void aWeakLinkDoesNotGroup() {
            GroupingPlan plan = planner.plan(List.of("a", "b"),
                    List.of(EntityLink.between("a", "b", 0.2)));

            assertEquals(List.of("a", "b"), idsOf(plan));
            assertTrue(noted(plan, "did not pull"));
        }

        @Test
        @DisplayName("several weak observations of the same pair add up to one that counts")
        void repeatedObservationsAccumulateBeforeTheFloor() {
            GroupingPlan plan = planner.plan(List.of("a", "b"),
                    List.of(EntityLink.between("a", "b", 0.3), EntityLink.between("b", "a", 0.3)));

            assertEquals(List.of("a+1"), idsOf(plan));
            assertTrue(plan.notes().isEmpty());
        }

        @Test
        @DisplayName("a link exactly at the floor counts")
        void theFloorIsInclusive() {
            GroupingPlan plan = planner.plan(List.of("a", "b"),
                    List.of(EntityLink.between("a", "b", 0.5)));

            assertEquals(List.of("a+1"), idsOf(plan));
        }

        @Test
        @DisplayName("a link naming a subject nobody asked to cover does not widen the plan")
        void linksToUnknownSubjectsAreIgnored() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b"),
                    List.of(EntityLink.between("a", "outsider")));

            assertEquals(2, plan.subjectsCovered());
            assertFalse(plan.groups().stream().anyMatch(group -> group.contains("outsider")));
            assertTrue(noted(plan, "not being grouped"));
        }

        @Test
        @DisplayName("no links at all is not a failure")
        void missingLinksAreTreatedAsNone() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b"), null);

            assertEquals(List.of("a", "b"), idsOf(plan));
        }

        @Test
        @DisplayName("a null link in the middle of real ones is skipped, not fatal")
        void aNullLinkIsSkipped() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(List.of("a", "b"),
                    Arrays.asList(EntityLink.between("a", "b"), null));

            assertEquals(List.of("a+1"), idsOf(plan));
        }
    }

    @Nested
    @DisplayName("Bridges")
    class Bridges {

        @Test
        @DisplayName("a hub does not glue the corpus into a single partition")
        void aHubNeverGluesEverythingTogether() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());

            assertTrue(plan.isBridge("hub"));
            assertEquals(List.of("hub"), plan.bridges());
            assertEquals(6, plan.groups().size());
            assertFalse(plan.groups().stream()
                    .anyMatch(group -> group.contains("s3") && group.contains("s4")));
            assertTrue(noted(plan, "treated as bridges"));
        }

        @Test
        @DisplayName("spokes that share a link of their own are still read together")
        void linksBetweenSpokesStillGroupThem() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());

            assertEquals(List.of("s1", "s2"), plan.owner("s1").orElseThrow().core());
        }

        @Test
        @DisplayName("by default a bridge is read once, on its own")
        void aSeparatedBridgeIsItsOwnPartition() {
            GroupingPlan plan = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());

            assertTrue(plan.owner("hub").orElseThrow().isSingleton());
            assertEquals(0, plan.duplicatedReads());
            assertEquals(7, plan.subjectsCovered());
        }

        @Test
        @DisplayName("an attached bridge joins the group it pulls hardest towards")
        void anAttachedBridgeJoinsItsStrongestNeighbourhood() {
            GroupingPlan plan = withBridges(BridgePolicy.ATTACH).plan(HUB_AND_SPOKES, hubLinks());

            EntityGroup home = plan.owner("hub").orElseThrow();
            assertEquals(List.of("hub", "s1", "s2"), home.core());
            assertEquals("hub+2", home.id());
            assertEquals(0, plan.duplicatedReads());
            assertTrue(plan.isBridge("hub"));
        }

        @Test
        @DisplayName("a shared bridge is read by every group that needs it, and the bill is visible")
        void aSharedBridgeIsReadByEveryGroupThatTouchesIt() {
            GroupingPlan plan = withBridges(BridgePolicy.SHARE).plan(HUB_AND_SPOKES, hubLinks());

            assertEquals(5, plan.groupsFor("hub").size());
            assertTrue(plan.owner("hub").isEmpty());
            assertEquals(5, plan.duplicatedReads());
            assertEquals(7, plan.subjectsCovered());
        }

        @Test
        @DisplayName("sharing a bridge into a group does not rename the group")
        void aSharedBridgeDoesNotChangeGroupIdentity() {
            GroupingPlan separate = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());
            GroupingPlan shared = withBridges(BridgePolicy.SHARE).plan(HUB_AND_SPOKES, hubLinks());

            assertEquals(separate.owner("s1").orElseThrow().id(),
                    shared.owner("s1").orElseThrow().id());
            assertTrue(shared.owner("s1").orElseThrow().sharedBridges().contains("hub"));
        }

        @Test
        @DisplayName("a bridge with no neighbourhood to join runs alone, and says so")
        void aBridgeWithNowhereToGoRunsAlone() {
            GroupingPolicy allBridges = GroupingPolicy.defaults()
                    .withBridges(BridgePolicy.ATTACH)
                    .withBridgeDegree(2);
            GroupingPlan plan = new EntityGroupPlanner(allBridges).plan(List.of("a", "b", "c"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("b", "c"),
                            EntityLink.between("a", "c")));

            assertEquals(List.of("a", "b", "c"), plan.bridges());
            assertEquals(List.of("a", "b", "c"), idsOf(plan));
            assertTrue(noted(plan, "no group to join"));
        }

        @Test
        @DisplayName("a bridge never reaches a neighbourhood through another bridge")
        void bridgesDoNotChainIntoEachOther() {
            // a-b-c is the only real neighbourhood; h1..h4 are hubs linked to each other, and only
            // h1 touches the neighbourhood. If a placed bridge could anchor the next one, all four
            // would end up inside a-b-c: one group again, by the back door.
            GroupingPolicy policy = GroupingPolicy.defaults()
                    .withBridges(BridgePolicy.ATTACH)
                    .withBridgeDegree(3);
            GroupingPlan plan = new EntityGroupPlanner(policy)
                    .plan(List.of("a", "b", "c", "h1", "h2", "h3", "h4"),
                            List.of(EntityLink.between("a", "b"), EntityLink.between("b", "c"),
                                    EntityLink.between("h1", "a"), EntityLink.between("h1", "c"),
                                    EntityLink.between("h1", "h2"), EntityLink.between("h1", "h3"),
                                    EntityLink.between("h1", "h4"), EntityLink.between("h2", "h3"),
                                    EntityLink.between("h2", "h4"), EntityLink.between("h3", "h4")));

            assertEquals(List.of("h1", "h2", "h3", "h4"), plan.bridges());
            assertEquals(List.of("a", "b", "c", "h1"), plan.owner("h1").orElseThrow().core());
            assertTrue(plan.owner("h2").orElseThrow().isSingleton());
            assertTrue(plan.owner("h3").orElseThrow().isSingleton());
            assertTrue(plan.owner("h4").orElseThrow().isSingleton());
            assertTrue(noted(plan, "no group to join"));
        }

        @Test
        @DisplayName("a bridge cannot be attached into a group that is already full")
        void aFullGroupTurnsAnAttachedBridgeAway() {
            GroupingPolicy tight = GroupingPolicy.defaults()
                    .withBridges(BridgePolicy.ATTACH)
                    .withBridgeDegree(3)
                    .withMaxGroupSize(2);
            GroupingPlan plan = new EntityGroupPlanner(tight).plan(List.of("hub", "a", "b", "c"),
                    List.of(EntityLink.between("hub", "a"), EntityLink.between("hub", "b"),
                            EntityLink.between("hub", "c"), EntityLink.between("a", "b")));

            assertTrue(plan.owner("hub").orElseThrow().isSingleton());
            assertTrue(noted(plan, "full group"));
        }

        @Test
        @DisplayName("nothing is a bridge when bridge detection is switched off")
        void bridgeDetectionCanBeSwitchedOff() {
            GroupingPlan plan = new EntityGroupPlanner(GroupingPolicy.defaults().withBridgeDegree(0))
                    .plan(HUB_AND_SPOKES, hubLinks());

            assertTrue(plan.bridges().isEmpty());
            assertEquals(List.of("hub+6"), idsOf(plan));
        }
    }

    @Nested
    @DisplayName("The size cap")
    class TheSizeCap {

        /** a-b-c-d-e: one neighbourhood, too big to be read in one pass. */
        private GroupingPlan splitChain(int cap) {
            GroupingPolicy policy = GroupingPolicy.defaults()
                    .withMaxGroupSize(cap)
                    .withBridgeDegree(0);
            return new EntityGroupPlanner(policy).plan(List.of("a", "b", "c", "d", "e"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("b", "c"),
                            EntityLink.between("c", "d"), EntityLink.between("d", "e")));
        }

        @Test
        @DisplayName("a neighbourhood over the cap is cut into groups that fit")
        void anOversizeNeighbourhoodIsSplit() {
            GroupingPlan plan = splitChain(3);

            assertEquals(2, plan.groups().size());
            assertTrue(plan.groups().stream().allMatch(group -> group.size() <= 3));
        }

        @Test
        @DisplayName("the split keeps the densest subjects together")
        void theDensestSubjectsStayTogether() {
            GroupingPlan plan = splitChain(3);

            assertEquals(List.of("a+1", "b+2"), idsOf(plan));
            assertEquals(List.of("b", "c", "d"), plan.owner("c").orElseThrow().core());
        }

        @Test
        @DisplayName("splitting loses links, so the plan says it happened")
        void theSplitIsReported() {
            assertTrue(noted(splitChain(3), "split into 2 groups"));
        }

        @Test
        @DisplayName("splitting drops nobody and duplicates nobody")
        void everySubjectLandsInExactlyOneGroup() {
            GroupingPlan plan = splitChain(3);

            assertEquals(5, plan.subjectsCovered());
            assertEquals(5, plan.groups().stream().mapToInt(EntityGroup::size).sum());
        }

        @Test
        @DisplayName("a neighbourhood inside the cap is left whole")
        void aGroupWithinTheCapIsNotTouched() {
            GroupingPlan plan = splitChain(5);

            assertEquals(List.of("a+4"), idsOf(plan));
            assertTrue(plan.notes().isEmpty());
        }
    }

    @Nested
    @DisplayName("Repeatability")
    class Repeatability {

        @Test
        @DisplayName("the order the corpus arrives in does not change the partitions")
        void inputOrderDoesNotChangeThePlan() {
            GroupingPlan forwards = EntityGroupPlanner.hybrid().plan(List.of("a", "b", "c", "d"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("c", "d")));
            GroupingPlan backwards = EntityGroupPlanner.hybrid().plan(List.of("d", "c", "b", "a"),
                    List.of(EntityLink.between("d", "c"), EntityLink.between("b", "a")));

            assertEquals(forwards.groups(), backwards.groups());
        }

        @Test
        @DisplayName("re-running over an unchanged corpus produces the same partition ids")
        void aRerunProducesTheSameIds() {
            GroupingPlan first = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());
            GroupingPlan second = EntityGroupPlanner.hybrid().plan(HUB_AND_SPOKES, hubLinks());

            assertEquals(idsOf(first), idsOf(second));
            assertEquals(first.describe(), second.describe());
        }
    }

    @Nested
    @DisplayName("Policies")
    class Policies {

        @Test
        @DisplayName("the hybrid default is what a planner without a policy uses")
        void aPlannerWithoutAPolicyIsHybrid() {
            assertEquals(GroupingPolicy.defaults(), new EntityGroupPlanner(null).policy());
            assertEquals(GroupingPolicy.HYBRID_VERSION,
                    EntityGroupPlanner.hybrid().plan(List.of("a")).policy().version());
        }

        @Test
        @DisplayName("per-entity grouping reads every subject on its own, whatever the links say")
        void perEntityGroupingNeverGroups() {
            GroupingPlan plan = EntityGroupPlanner.perEntity().plan(List.of("a", "b", "c"),
                    List.of(EntityLink.between("a", "b"), EntityLink.between("b", "c")));

            assertEquals(GroupingPolicy.PER_ENTITY_VERSION, plan.policy().version());
            assertEquals(List.of("a", "b", "c"), idsOf(plan));
            assertTrue(plan.groups().stream().allMatch(EntityGroup::isSingleton));
        }

        @Test
        @DisplayName("the policy that produced a plan travels with it")
        void thePlanCarriesItsPolicy() {
            GroupingPolicy policy = GroupingPolicy.defaults().withMaxGroupSize(4);
            GroupingPlan plan = new EntityGroupPlanner(policy).plan(List.of("a"));

            assertEquals(policy, plan.policy());
        }
    }
}
