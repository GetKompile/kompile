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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration properties for knowledge graph entity/relationship extraction.
 * These properties control how entities and relationships are extracted during
 * document indexing and stored in the knowledge graph.
 */
@Configuration
@ConfigurationProperties(prefix = "kompile.graph")
public class GraphExtractionProperties {

    private Extraction extraction = new Extraction();
    private Deduplication deduplication = new Deduplication();
    private Neo4j neo4j = new Neo4j();

    public Extraction getExtraction() {
        return extraction;
    }

    public void setExtraction(Extraction extraction) {
        this.extraction = extraction;
    }

    public Deduplication getDeduplication() {
        return deduplication;
    }

    public void setDeduplication(Deduplication deduplication) {
        this.deduplication = deduplication;
    }

    public Neo4j getNeo4j() {
        return neo4j;
    }

    public void setNeo4j(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    /**
     * Entity/relationship extraction settings.
     */
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getSchemaEnforcement() {
            return schemaEnforcement;
        }

        public void setSchemaEnforcement(String schemaEnforcement) {
            this.schemaEnforcement = schemaEnforcement;
        }

        public List<String> getEntityTypes() {
            return entityTypes;
        }

        public void setEntityTypes(List<String> entityTypes) {
            this.entityTypes = entityTypes;
        }

        public List<String> getRelationshipTypes() {
            return relationshipTypes;
        }

        public void setRelationshipTypes(List<String> relationshipTypes) {
            this.relationshipTypes = relationshipTypes;
        }

        public int getMaxEntitiesPerChunk() {
            return maxEntitiesPerChunk;
        }

        public void setMaxEntitiesPerChunk(int maxEntitiesPerChunk) {
            this.maxEntitiesPerChunk = maxEntitiesPerChunk;
        }

        public int getMaxRelationshipsPerChunk() {
            return maxRelationshipsPerChunk;
        }

        public void setMaxRelationshipsPerChunk(int maxRelationshipsPerChunk) {
            this.maxRelationshipsPerChunk = maxRelationshipsPerChunk;
        }
    }

    /**
     * Entity deduplication settings.
     */
    public static class Deduplication {
        /**
         * Enable entity deduplication across documents.
         */
        private boolean enabled = true;

        /**
         * Similarity threshold for entity deduplication (0.0-1.0).
         */
        private double similarityThreshold = 0.85;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }

    /**
     * Neo4j connection settings.
     */
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }
    }
}
