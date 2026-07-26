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

package ai.kompile.core.graphrag.partition.reuse;

import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When two extractions are the same work.
 *
 * <p>Every claim here is about what may be reused for what. Too strict and the layer never fires;
 * too loose and one answer is served for a question nobody asked.</p>
 */
class ExtractionKeyTest {

    private static final String PROFILE = "lfm2.5/entities-v3";

    private static PartitionMember member(String chunkId, String version) {
        ChunkCandidate candidate = ChunkCandidate.of(chunkId, DiscoveryChannel.SEMANTIC, 0.7);
        return PartitionMember.admit(
                version == null ? candidate : candidate.withVersion(version),
                MembershipState.DISCOVERED, 1);
    }

    @Nested
    @DisplayName("Keying on content")
    class Content {

        @Test
        @DisplayName("the same text is the same work")
        void theSameTextIsTheSameWork() {
            assertEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(),
                    ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow());
        }

        @Test
        @DisplayName("rewrapping a paragraph does not make it a new question")
        void whitespaceIsNormalised() {
            // A chunker that wraps differently produces different bytes and the same paragraph.
            // No extractor answers those two differently, so paying twice for them is waste.
            ExtractionKey wrapped =
                    ExtractionKey.ofText("Acme employs\n   Alice.", PROFILE).orElseThrow();
            ExtractionKey flat = ExtractionKey.ofText("  Acme employs Alice.  ", PROFILE)
                    .orElseThrow();

            assertEquals(flat, wrapped);
        }

        @Test
        @DisplayName("case is left alone, because a reader would not leave it alone")
        void caseIsNotNormalised() {
            // Capitalisation is one of the strongest signals a name is a name. Folding it would
            // merge two texts an extractor genuinely reads differently.
            assertNotEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(),
                    ExtractionKey.ofText("acme employs alice.", PROFILE).orElseThrow());
        }

        @Test
        @DisplayName("different text is different work")
        void differentTextIsDifferentWork() {
            assertNotEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(),
                    ExtractionKey.ofText("Acme employs Bob.", PROFILE).orElseThrow());
        }

        @Test
        @DisplayName("blank text identifies nothing")
        void blankTextIdentifiesNothing() {
            assertTrue(ExtractionKey.ofText(null, PROFILE).isEmpty());
            assertTrue(ExtractionKey.ofText("", PROFILE).isEmpty());
            assertTrue(ExtractionKey.ofText("   \n ", PROFILE).isEmpty());
        }

        @Test
        @DisplayName("a content key says so")
        void aContentKeySaysSo() {
            ExtractionKey key = ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow();

            assertEquals(ExtractionKey.Basis.CONTENT, key.basis());
            assertTrue(key.isContentDerived());
        }
    }

    @Nested
    @DisplayName("Keying on a versioned identity")
    class VersionedIdentity {

        @Test
        @DisplayName("the same chunk at the same version is the same work")
        void sameChunkSameVersion() {
            assertEquals(ExtractionKey.ofVersionedId("c1", "v7", PROFILE).orElseThrow(),
                    ExtractionKey.ofVersionedId("c1", "v7", PROFILE).orElseThrow());
        }

        @Test
        @DisplayName("a chunk whose source moved is new work")
        void aChangedSourceIsNewWork() {
            // This is why the version is in the key: the id alone would happily serve the old
            // answer for text that no longer exists.
            assertNotEquals(ExtractionKey.ofVersionedId("c1", "v7", PROFILE).orElseThrow(),
                    ExtractionKey.ofVersionedId("c1", "v8", PROFILE).orElseThrow());
        }

        @Test
        @DisplayName("a bare chunk id is not an identity")
        void aBareChunkIdIsNotAnIdentity() {
            assertTrue(ExtractionKey.ofVersionedId("c1", null, PROFILE).isEmpty());
            assertTrue(ExtractionKey.ofVersionedId("c1", "  ", PROFILE).isEmpty());
            assertTrue(ExtractionKey.ofVersionedId(null, "v7", PROFILE).isEmpty());
        }

        @Test
        @DisplayName("two chunks holding the same words are still separate work here")
        void identicalTextUnderDifferentIdsIsNotMatched() {
            // The weaker basis: it can save the same chunk being read for a second partition, but
            // it cannot see that two chunks say the same thing.
            assertNotEquals(ExtractionKey.ofVersionedId("c1", "v1", PROFILE).orElseThrow(),
                    ExtractionKey.ofVersionedId("c2", "v1", PROFILE).orElseThrow());
            assertFalse(ExtractionKey.ofVersionedId("c1", "v1", PROFILE).orElseThrow()
                    .isContentDerived());
        }
    }

    @Nested
    @DisplayName("Keying a member")
    class TheLadder {

        @Test
        @DisplayName("text wins, because it matches more work")
        void textWins() {
            ExtractionKey key = ExtractionKey
                    .forMember(member("c1", "v1"), "Acme employs Alice.", PROFILE).orElseThrow();

            assertEquals(ExtractionKey.Basis.CONTENT, key.basis());
            assertEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(), key);
        }

        @Test
        @DisplayName("without text, the same chunk can still be recognised")
        void withoutTextTheVersionedIdentityIsUsed() {
            ExtractionKey key =
                    ExtractionKey.forMember(member("c1", "v1"), null, PROFILE).orElseThrow();

            assertEquals(ExtractionKey.Basis.VERSIONED_ID, key.basis());
            assertEquals(ExtractionKey.ofVersionedId("c1", "v1", PROFILE).orElseThrow(), key);
        }

        @Test
        @DisplayName("work that cannot be identified is not claimed to be identical to anything")
        void unidentifiableWorkHasNoKey() {
            assertTrue(ExtractionKey.forMember(member("c1", null), null, PROFILE).isEmpty());
            assertTrue(ExtractionKey.forMember(member("c1", null), "  ", PROFILE).isEmpty());
            assertTrue(ExtractionKey.forMember(null, "Acme employs Alice.", PROFILE).isEmpty());
        }
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("a key without an extractor is refused rather than defaulted")
        void aProfileIsRequired() {
            assertThrows(IllegalArgumentException.class,
                    () -> ExtractionKey.ofText("Acme employs Alice.", null));
            assertThrows(IllegalArgumentException.class,
                    () -> ExtractionKey.ofText("Acme employs Alice.", "  "));
            assertThrows(IllegalArgumentException.class,
                    () -> new ExtractionKey(null, "abc", PROFILE));
            assertThrows(IllegalArgumentException.class,
                    () -> new ExtractionKey(ExtractionKey.Basis.CONTENT, " ", PROFILE));
        }

        @Test
        @DisplayName("another extractor's answer is another question's answer")
        void theProfileIsPartOfTheIdentity() {
            assertNotEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(),
                    ExtractionKey.ofText("Acme employs Alice.", "lfm2.5/entities-v4").orElseThrow());
            assertNotEquals(
                    ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow().id(),
                    ExtractionKey.ofText("Acme employs Alice.", "other").orElseThrow().id());
        }

        @Test
        @DisplayName("the id is stable and survives being written down")
        void theIdIsStable() {
            String first = ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow().id();
            String again = ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow().id();

            assertEquals(first, again);
            assertTrue(first.startsWith(PROFILE + "/content:"));
        }

        @Test
        @DisplayName("the two bases never collide")
        void theBasesNeverCollide() {
            Optional<ExtractionKey> byContent = ExtractionKey.ofText("c1@v1", PROFILE);
            Optional<ExtractionKey> byId = ExtractionKey.ofVersionedId("c1", "v1", PROFILE);

            assertNotEquals(byContent.orElseThrow().id(), byId.orElseThrow().id());
        }

        @Test
        @DisplayName("describing a key is short enough for a note")
        void describeIsShort() {
            String described =
                    ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow().describe();

            assertTrue(described.startsWith(PROFILE + "/content:"));
            assertTrue(described.length() < PROFILE.length() + 24, described);
        }

        @Test
        @DisplayName("surrounding space in a profile is not a different extractor")
        void profilesAreTrimmed() {
            assertEquals(ExtractionKey.ofText("Acme employs Alice.", PROFILE).orElseThrow(),
                    ExtractionKey.ofText("Acme employs Alice.", "  " + PROFILE + " ")
                            .orElseThrow());
        }
    }
}
