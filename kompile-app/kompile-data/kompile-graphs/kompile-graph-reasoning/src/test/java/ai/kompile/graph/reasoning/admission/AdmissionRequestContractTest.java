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
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionRequestContractTest {

    @Test
    void ballotIsDefensivelyCopiedAndDeduplicatedByCandidateId() {
        AdmissionCandidate selected = new AdmissionCandidate("selected", "person:acme");
        AdmissionCandidate rival = new AdmissionCandidate("rival", "person:globex");
        List<AdmissionCandidate> supplied = new ArrayList<>(List.of(selected, rival, selected));

        AdmissionRequest request = new AdmissionRequest(
                "group", selected, supplied, "snapshot:v1", "policy:v1", new UnifiedGraph());
        supplied.clear();

        assertEquals(2, request.ballot().size());
        assertTrue(request.ballot().contains(selected));
        assertEquals(request.ballotFingerprint(), request.ballotFingerprint());
    }

    @Test
    void conflictingCanonicalKeysForOneCandidateIdAreRejected() {
        AdmissionCandidate selected = new AdmissionCandidate("selected", "person:acme");
        AdmissionCandidate conflicting = new AdmissionCandidate("selected", "company:acme");

        assertThrows(IllegalArgumentException.class, () -> new AdmissionRequest(
                "group", selected, List.of(selected, conflicting),
                "snapshot:v1", "policy:v1", new UnifiedGraph()));
    }

    @Test
    void requestCarriesTheSnapshotRevisionAndPolicyVersionVerbatim() {
        AdmissionCandidate selected = new AdmissionCandidate("selected", "person:acme");
        AdmissionRequest request = new AdmissionRequest(
                " group ", selected, List.of(selected), " snapshot:v7 ", " policy:v3 ", new UnifiedGraph());

        assertEquals("group", request.decisionGroupId());
        assertEquals("snapshot:v7", request.snapshotId());
        assertEquals("policy:v3", request.policyVersion());
    }
}
