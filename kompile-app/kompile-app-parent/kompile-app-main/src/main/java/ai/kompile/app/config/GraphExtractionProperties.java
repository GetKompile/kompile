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

package ai.kompile.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration properties for knowledge graph entity/relationship extraction.
 * These properties control how entities and relationships are extracted during
 * document indexing and stored in the knowledge graph.
 */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "kompile.graph")
@Getter
@Setter
public class GraphExtractionProperties {

    private Extraction extraction = new Extraction();
    private Deduplication deduplication = new Deduplication();
    private Neo4j neo4j = new Neo4j();

    /**
     * Entity/relationship extraction settings.
     */
    @Getter
    @Setter
    public static class Extraction {
        /**
         * Enable automatic entity/relationship extraction during indexing.
         */
        private boolean enabled = false;

        /**
         * Batch size for entity extraction.
         */
        private int batchSize = 10;

        /**
         * Schema enforcement mode: NONE, LENIENT, or STRICT.
         */
        private String schemaEnforcement = "LENIENT";

        /**
         * Entity types to extract (comma-separated, or empty for all).
         */
        private List<String> entityTypes = List.of();

        /**
         * Relationship types to extract (comma-separated, or empty for all).
         */
        private List<String> relationshipTypes = List.of();

        /**
         * Maximum entities per chunk.
         */
        private int maxEntitiesPerChunk = 20;

        /**
         * Maximum relationships per chunk.
         */
        private int maxRelationshipsPerChunk = 30;

    }

    /**
     * Entity deduplication settings.
     */
    @Getter
    @Setter
    public static class Deduplication {
        /**
         * Enable entity deduplication across documents.
         */
        private boolean enabled = true;

        /**
         * Similarity threshold for entity deduplication (0.0-1.0).
         */
        private double similarityThreshold = 0.85;

    }

    /**
     * Neo4j connection settings.
     */
    @Getter
    @Setter
    public static class Neo4j {
        /**
         * Enable Neo4j graph store.
         */
        private boolean enabled = false;

        /**
         * Neo4j connection URI.
         */
        private String uri = "bolt://localhost:7687";

        /**
         * Neo4j username.
         */
        private String username = "neo4j";

        /**
         * Neo4j password.
         */
        private String password = "";

        /**
         * Neo4j database name.
         */
        private String database = "neo4j";

    }
}
