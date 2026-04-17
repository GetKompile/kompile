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

package ai.kompile.knowledgegraph.resolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionEntityStateTest {

    private SessionEntityState state;

    @BeforeEach
    void setUp() {
        state = new SessionEntityState(5); // Small capacity for testing eviction
    }

    @Test
    void testTrackAndRetrieveEntity() {
        state.trackEntity("id1", "Acme Corp", "ORGANIZATION", List.of("Acme"), 1, "node1");
        assertEquals(1, state.size());

        List<SessionEntityState.TrackedEntity> recent = state.getRecentEntities();
        assertEquals(1, recent.size());
        assertEquals("Acme Corp", recent.get(0).name());
        assertEquals("ORGANIZATION", recent.get(0).type());
    }

    @Test
    void testResolveByName() {
        state.trackEntity("id1", "Acme Corp", "ORGANIZATION", List.of(), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("acme corp");
        assertNotNull(resolved);
        assertEquals("Acme Corp", resolved.name());
    }

    @Test
    void testResolveByTypePrefix() {
        state.trackEntity("id1", "Acme Corp", "ORGANIZATION", List.of(), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("that company");
        assertNotNull(resolved);
        assertEquals("Acme Corp", resolved.name());
    }

    @Test
    void testResolveByAlias() {
        state.trackEntity("id1", "Acme Corporation", "ORGANIZATION", List.of("Acme", "ACME Inc"), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("acme");
        assertNotNull(resolved);
        assertEquals("Acme Corporation", resolved.name());
    }

    @Test
    void testResolveCEO() {
        state.trackEntity("id1", "John Smith", "PERSON", List.of(), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("the CEO");
        assertNotNull(resolved);
        assertEquals("John Smith", resolved.name());
    }

    @Test
    void testResolveNoMatch() {
        state.trackEntity("id1", "Acme Corp", "ORGANIZATION", List.of(), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("something unrelated");
        assertNull(resolved);
    }

    @Test
    void testResolveNullAndBlank() {
        assertNull(state.resolveReference(null));
        assertNull(state.resolveReference(""));
        assertNull(state.resolveReference("  "));
    }

    @Test
    void testEviction() {
        // Capacity is 5, add 6 entities
        for (int i = 0; i < 6; i++) {
            state.trackEntity("id" + i, "Entity " + i, "CONCEPT", List.of(), i, "node" + i);
        }

        assertEquals(5, state.size());
    }

    @Test
    void testGetEntitiesByType() {
        state.trackEntity("id1", "Acme", "ORGANIZATION", List.of(), 1, "node1");
        state.trackEntity("id2", "John", "PERSON", List.of(), 1, "node2");
        state.trackEntity("id3", "BigCo", "ORGANIZATION", List.of(), 2, "node3");

        List<SessionEntityState.TrackedEntity> orgs = state.getEntitiesByType("ORGANIZATION");
        assertEquals(2, orgs.size());
    }

    @Test
    void testGetTrackedEntityNames() {
        state.trackEntity("id1", "Acme", "ORGANIZATION", List.of(), 1, "node1");
        state.trackEntity("id2", "John", "PERSON", List.of(), 1, "node2");

        Set<String> names = state.getTrackedEntityNames();
        assertTrue(names.contains("Acme"));
        assertTrue(names.contains("John"));
    }

    @Test
    void testBuildEntityContext() {
        state.trackEntity("id1", "Acme", "ORGANIZATION", List.of(), 1, "node1");
        state.trackEntity("id2", "John", "PERSON", List.of(), 2, "node2");

        String context = state.buildEntityContext(10);
        assertNotNull(context);
        assertTrue(context.contains("Acme"));
        assertTrue(context.contains("John"));
        assertTrue(context.contains("ORGANIZATION"));
        assertTrue(context.contains("PERSON"));
    }

    @Test
    void testBuildEntityContextEmpty() {
        String context = state.buildEntityContext(10);
        assertEquals("", context);
    }

    @Test
    void testBuildEntityContextLimited() {
        state.trackEntity("id1", "Entity1", "CONCEPT", List.of(), 1, "node1");
        state.trackEntity("id2", "Entity2", "CONCEPT", List.of(), 2, "node2");
        state.trackEntity("id3", "Entity3", "CONCEPT", List.of(), 3, "node3");

        String context = state.buildEntityContext(1);
        // Should only contain 1 entity
        long semicolons = context.chars().filter(c -> c == ';').count();
        assertEquals(0, semicolons, "Should only have 1 entity (no semicolons)");
    }

    @Test
    void testClear() {
        state.trackEntity("id1", "Acme", "ORGANIZATION", List.of(), 1, "node1");
        assertEquals(1, state.size());

        state.clear();
        assertEquals(0, state.size());
    }

    @Test
    void testMostRecentFirst() {
        state.trackEntity("id1", "First", "CONCEPT", List.of(), 1, "node1");
        state.trackEntity("id2", "Second", "CONCEPT", List.of(), 2, "node2");
        state.trackEntity("id3", "Third", "CONCEPT", List.of(), 3, "node3");

        List<SessionEntityState.TrackedEntity> recent = state.getRecentEntities();
        assertEquals("Third", recent.get(0).name());
    }

    @Test
    void testResolveLocationReference() {
        state.trackEntity("id1", "New York", "LOCATION", List.of("NYC"), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("the city");
        assertNotNull(resolved);
        assertEquals("New York", resolved.name());
    }

    @Test
    void testResolveProductReference() {
        state.trackEntity("id1", "Widget Pro", "PRODUCT", List.of(), 1, "node1");

        SessionEntityState.TrackedEntity resolved = state.resolveReference("that product");
        assertNotNull(resolved);
        assertEquals("Widget Pro", resolved.name());
    }
}
