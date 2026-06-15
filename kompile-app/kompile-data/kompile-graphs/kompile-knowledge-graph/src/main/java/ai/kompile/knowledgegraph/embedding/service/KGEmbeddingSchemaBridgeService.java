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

package ai.kompile.knowledgegraph.embedding.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Bridge service that adds KG embedding columns to existing tables on startup.
 * This avoids the need for Flyway migrations and handles schema evolution gracefully.
 */
@Service
@ConditionalOnClass(name = "ai.kompile.knowledgegraph.embedding.service.KGEmbeddingSchemaBridgeService")
@ConditionalOnProperty(name = "kompile.kg-embedding.enabled", havingValue = "true", matchIfMissing = true)
public class KGEmbeddingSchemaBridgeService {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingSchemaBridgeService.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public KGEmbeddingSchemaBridgeService(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initializeSchema() {
        log.info("Initializing KG Embedding schema...");

        try {
            // Add columns to graph_nodes table
            addColumnsToGraphNodes();

            // Add columns to graph_edges table
            addColumnsToGraphEdges();

            // Create kg_embedding_jobs table if not exists
            createKGEmbeddingJobsTable();

            log.info("KG Embedding schema initialization completed");
        } catch (Exception e) {
            log.warn("Failed to initialize KG Embedding schema (may not be needed): {}", e.getMessage());
        }
    }

    private void addColumnsToGraphNodes() {
        String tableName = "graph_nodes";
        Set<String> existingColumns = getExistingColumns(tableName);

        // Add kg_embedding column (BLOB for storing INDArray)
        if (!existingColumns.contains("kg_embedding")) {
            try {
                String columnType = getBlobColumnType();
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding " + columnType);
                log.info("Added kg_embedding column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding may already exist: {}", e.getMessage());
            }
        }

        // Add kg_embedding_algorithm column
        if (!existingColumns.contains("kg_embedding_algorithm")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding_algorithm VARCHAR(32)");
                log.info("Added kg_embedding_algorithm column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding_algorithm may already exist: {}", e.getMessage());
            }
        }

        // Add kg_embedding_version column
        if (!existingColumns.contains("kg_embedding_version")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding_version BIGINT");
                log.info("Added kg_embedding_version column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding_version may already exist: {}", e.getMessage());
            }
        }

        // Add kg_embedding_updated_at column
        if (!existingColumns.contains("kg_embedding_updated_at")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding_updated_at TIMESTAMP");
                log.info("Added kg_embedding_updated_at column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding_updated_at may already exist: {}", e.getMessage());
            }
        }

        // Create index on version for efficient filtering
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_graph_nodes_kg_version ON " + tableName + "(kg_embedding_version)");
        } catch (Exception e) {
            // Index may already exist or DB doesn't support IF NOT EXISTS
            log.debug("Index creation skipped: {}", e.getMessage());
        }
    }

    private void addColumnsToGraphEdges() {
        String tableName = "graph_edges";
        Set<String> existingColumns = getExistingColumns(tableName);

        // Add kg_relation_embedding column
        if (!existingColumns.contains("kg_relation_embedding")) {
            try {
                String columnType = getBlobColumnType();
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_relation_embedding " + columnType);
                log.info("Added kg_relation_embedding column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_relation_embedding may already exist: {}", e.getMessage());
            }
        }

        // Add kg_embedding_algorithm column
        if (!existingColumns.contains("kg_embedding_algorithm")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding_algorithm VARCHAR(32)");
                log.info("Added kg_embedding_algorithm column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding_algorithm may already exist: {}", e.getMessage());
            }
        }

        // Add kg_embedding_version column
        if (!existingColumns.contains("kg_embedding_version")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN kg_embedding_version BIGINT");
                log.info("Added kg_embedding_version column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column kg_embedding_version may already exist: {}", e.getMessage());
            }
        }

        // Create index
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_graph_edges_kg_version ON " + tableName + "(kg_embedding_version)");
        } catch (Exception e) {
            log.debug("Index creation skipped: {}", e.getMessage());
        }
    }

    private void createKGEmbeddingJobsTable() {
        String tableName = "kg_embedding_jobs";

        // Check if table exists
        if (tableExists(tableName)) {
            log.debug("Table {} already exists", tableName);
            return;
        }

        String blobType = getBlobColumnType();
        String createTableSql = """
            CREATE TABLE %s (
                job_id VARCHAR(36) PRIMARY KEY,
                fact_sheet_id BIGINT NOT NULL,
                algorithm VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                embedding_dim INTEGER,
                epochs INTEGER,
                learning_rate DOUBLE PRECISION,
                batch_size INTEGER,
                margin DOUBLE PRECISION,
                negative_samples INTEGER,
                current_epoch INTEGER,
                current_loss DOUBLE PRECISION,
                total_triples INTEGER,
                embedding_version BIGINT,
                entities_embedded INTEGER,
                relations_embedded INTEGER,
                created_at TIMESTAMP NOT NULL,
                started_at TIMESTAMP,
                completed_at TIMESTAMP,
                error_message TEXT
            )
            """.formatted(tableName);

        try {
            jdbcTemplate.execute(createTableSql);
            log.info("Created table {}", tableName);

            // Create indexes
            jdbcTemplate.execute("CREATE INDEX idx_kg_jobs_fact_sheet ON " + tableName + "(fact_sheet_id)");
            jdbcTemplate.execute("CREATE INDEX idx_kg_jobs_status ON " + tableName + "(status)");
        } catch (Exception e) {
            log.debug("Table {} may already exist: {}", tableName, e.getMessage());
        }
    }

    private Set<String> getExistingColumns(String tableName) {
        Set<String> columns = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            // Try both lowercase and uppercase table names
            ResultSet rs = metaData.getColumns(null, null, tableName, null);
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            if (columns.isEmpty()) {
                rs = metaData.getColumns(null, null, tableName.toUpperCase(), null);
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get columns for table {}: {}", tableName, e.getMessage());
        }
        return columns;
    }

    private boolean tableExists(String tableName) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTables(null, null, tableName, new String[]{"TABLE"});
            if (rs.next()) return true;
            rs = metaData.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"});
            return rs.next();
        } catch (SQLException e) {
            log.warn("Failed to check table existence: {}", e.getMessage());
            return false;
        }
    }

    private String getBlobColumnType() {
        try (Connection conn = dataSource.getConnection()) {
            String dbName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (dbName.contains("postgresql") || dbName.contains("postgres")) {
                return "BYTEA";
            } else if (dbName.contains("mysql") || dbName.contains("mariadb")) {
                return "LONGBLOB";
            } else {
                // H2, HSQLDB, etc.
                return "BLOB";
            }
        } catch (SQLException e) {
            return "BLOB";
        }
    }
}
