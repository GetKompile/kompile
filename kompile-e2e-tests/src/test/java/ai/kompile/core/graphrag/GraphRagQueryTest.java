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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.SearchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GraphRagQuery builder and model.
 */
@DisplayName("GraphRagQuery Tests")
public class GraphRagQueryTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create query with all fields")
        void builder_shouldCreateQueryWithAllFields() {
            Graph graph = createTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .conversationId("conv-123")
                    .build();

            assertEquals("What is TechCorp?", query.getQuery());
            assertSame(graph, query.getGraph());
            assertEquals(SearchType.LOCAL, query.getSearchType());
            assertEquals(10, query.getK());
            assertEquals("conv-123", query.getConversationId());
        }

        @Test
        @DisplayName("Builder should use default conversation ID")
        void builder_shouldUseDefaultConversationId() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .searchType(SearchType.LOCAL)
                    .build();

            assertEquals("default", query.getConversationId());
        }

        @Test
        @DisplayName("Builder should allow null graph")
        void builder_shouldAllowNullGraph() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .graph(null)
                    .searchType(SearchType.GLOBAL)
                    .build();

            assertNull(query.getGraph());
        }

        @Test
        @DisplayName("Builder should allow null search type")
        void builder_shouldAllowNullSearchType() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .searchType(null)
                    .build();

            assertNull(query.getSearchType());
        }

        @Test
        @DisplayName("Builder should allow zero k")
        void builder_shouldAllowZeroK() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .k(0)
                    .build();

            assertEquals(0, query.getK());
        }
    }

    @Nested
    @DisplayName("Search Type Tests")
    class SearchTypeTests {

        @Test
        @DisplayName("LOCAL search type should be set correctly")
        void localSearchType_shouldBeSetCorrectly() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test")
                    .searchType(SearchType.LOCAL)
                    .build();

            assertEquals(SearchType.LOCAL, query.getSearchType());
        }

        @Test
        @DisplayName("GLOBAL search type should be set correctly")
        void globalSearchType_shouldBeSetCorrectly() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test")
                    .searchType(SearchType.GLOBAL)
                    .build();

            assertEquals(SearchType.GLOBAL, query.getSearchType());
        }
    }

    @Nested
    @DisplayName("Equality and HashCode Tests")
    class EqualityTests {

        @Test
        @DisplayName("Queries with same values should be equal")
        void queriesWithSameValues_shouldBeEqual() {
            GraphRagQuery query1 = GraphRagQuery.builder()
                    .query("Test query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId("conv-1")
                    .build();

            GraphRagQuery query2 = GraphRagQuery.builder()
                    .query("Test query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId("conv-1")
                    .build();

            assertEquals(query1, query2);
            assertEquals(query1.hashCode(), query2.hashCode());
        }

        @Test
        @DisplayName("Queries with different values should not be equal")
        void queriesWithDifferentValues_shouldNotBeEqual() {
            GraphRagQuery query1 = GraphRagQuery.builder()
                    .query("Test query 1")
                    .searchType(SearchType.LOCAL)
                    .build();

            GraphRagQuery query2 = GraphRagQuery.builder()
                    .query("Test query 2")
                    .searchType(SearchType.LOCAL)
                    .build();

            assertNotEquals(query1, query2);
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain query text")
        void toString_shouldContainQueryText() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .build();

            String str = query.toString();
            assertTrue(str.contains("What is TechCorp?"));
        }

        @Test
        @DisplayName("toString should contain search type")
        void toString_shouldContainSearchType() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test")
                    .searchType(SearchType.GLOBAL)
                    .build();

            String str = query.toString();
            assertTrue(str.contains("GLOBAL"));
        }
    }

    // Helper method
    private Graph createTestGraph() {
        Graph graph = new Graph();
        List<Entity> entities = new ArrayList<>();

        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("TechCorp");
        entity.setType("ORGANIZATION");
        entities.add(entity);

        graph.setEntities(entities);
        graph.setRelationships(new ArrayList<>());
        return graph;
    }
}
