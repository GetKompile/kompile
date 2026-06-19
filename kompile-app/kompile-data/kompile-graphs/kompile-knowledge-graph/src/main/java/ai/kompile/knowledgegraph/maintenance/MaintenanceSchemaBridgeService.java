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
package ai.kompile.knowledgegraph.maintenance;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Bridge service that adds graph maintenance columns to existing tables on startup.
 * Ensures the six maintenance lifecycle columns (last_verified_at, valid_until,
 * user_pinned, stale, stale_at, observed_at) exist on both graph_nodes and
 * graph_edges tables, along with the required indexes.
 *
 * <p>This complements {@code spring.jpa.hibernate.ddl-auto=update} for databases
 * where Hibernate's schema update may not apply cleanly (e.g., existing tables
 * with constraints, production PostgreSQL, etc.).
 */
@Service
public class MaintenanceSchemaBridgeService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceSchemaBridgeService.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public MaintenanceSchemaBridgeService(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initializeSchema() {
        log.info("Initializing graph maintenance schema columns...");
        try {
            addMaintenanceColumns("graph_nodes");
            addMaintenanceColumns("graph_edges");
            createIndexes("graph_nodes", "node");
            createIndexes("graph_edges", "edge");
            log.info("Graph maintenance schema initialization completed");
        } catch (Exception e) {
            log.warn("Failed to initialize maintenance schema (may not be needed): {}", e.getMessage());
        }
    }

    private void addMaintenanceColumns(String tableName) {
        Set<String> existing = getExistingColumns(tableName);

        addColumnIfMissing(existing, tableName, "last_verified_at", "TIMESTAMP");
        addColumnIfMissing(existing, tableName, "valid_until", "TIMESTAMP");
        addColumnIfMissing(existing, tableName, "observed_at", "TIMESTAMP");
        addColumnIfMissing(existing, tableName, "stale_at", "TIMESTAMP");

        // Boolean columns with NOT NULL + DEFAULT
        if (!existing.contains("user_pinned")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName
                        + " ADD COLUMN user_pinned BOOLEAN NOT NULL DEFAULT FALSE");
                log.info("Added user_pinned column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column user_pinned may already exist on {}: {}", tableName, e.getMessage());
            }
        }

        if (!existing.contains("stale")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName
                        + " ADD COLUMN stale BOOLEAN NOT NULL DEFAULT FALSE");
                log.info("Added stale column to {}", tableName);
            } catch (Exception e) {
                log.debug("Column stale may already exist on {}: {}", tableName, e.getMessage());
            }
        }
    }

    private void addColumnIfMissing(Set<String> existing, String tableName,
                                    String columnName, String columnType) {
        if (!existing.contains(columnName)) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName
                        + " ADD COLUMN " + columnName + " " + columnType);
                log.info("Added {} column to {}", columnName, tableName);
            } catch (Exception e) {
                log.debug("Column {} may already exist on {}: {}", columnName, tableName, e.getMessage());
            }
        }
    }

    private void createIndexes(String tableName, String prefix) {
        safeCreateIndex("idx_" + prefix + "_valid_until", tableName, "valid_until");
        safeCreateIndex("idx_" + prefix + "_stale", tableName, "stale");
        safeCreateIndex("idx_" + prefix + "_last_verified", tableName, "last_verified_at");
    }

    private void safeCreateIndex(String indexName, String tableName, String columnName) {
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName
                    + " ON " + tableName + "(" + columnName + ")");
        } catch (Exception e) {
            // Fallback for databases that don't support IF NOT EXISTS
            try {
                jdbcTemplate.execute("CREATE INDEX " + indexName
                        + " ON " + tableName + "(" + columnName + ")");
            } catch (Exception ignored) {
                log.debug("Index {} may already exist: {}", indexName, ignored.getMessage());
            }
        }
    }

    private Set<String> getExistingColumns(String tableName) {
        Set<String> columns = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
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
}
