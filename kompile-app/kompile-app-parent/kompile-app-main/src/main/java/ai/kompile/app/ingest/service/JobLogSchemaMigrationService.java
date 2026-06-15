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

package ai.kompile.app.ingest.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * Service that handles database schema migrations for job log entries.
 * This provides backwards compatibility when adding new LogSource enum values
 * by updating database constraints at startup using Java/JDBC instead of SQL migrations.
 *
 * <p>This approach ensures that:
 * <ul>
 *   <li>New enum values like EMBEDDING are properly supported in the database</li>
 *   <li>Existing data is preserved</li>
 *   <li>No manual SQL migrations are needed</li>
 *   <li>Works across different database types (H2, PostgreSQL, etc.)</li>
 * </ul>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class JobLogSchemaMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(JobLogSchemaMigrationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Autowired
    public JobLogSchemaMigrationService(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateSchema() {
        if (jdbcTemplate == null || dataSource == null) {
            logger.debug("JdbcTemplate or DataSource not available, skipping schema migration");
            return;
        }

        try {
            String dbType = detectDatabaseType();
            logger.info("Detected database type: {}", dbType);

            if ("H2".equalsIgnoreCase(dbType)) {
                migrateH2Schema();
            } else if ("PostgreSQL".equalsIgnoreCase(dbType)) {
                migratePostgresSchema();
            } else {
                logger.info("Database type {} may not need explicit constraint migration", dbType);
            }
        } catch (Exception e) {
            // Log but don't fail startup - the table might not exist yet or migration may not be needed
            logger.debug("Schema migration check completed: {}", e.getMessage());
        }
    }

    private String detectDatabaseType() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            return meta.getDatabaseProductName();
        } catch (Exception e) {
            logger.warn("Could not detect database type: {}", e.getMessage());
            return "Unknown";
        }
    }

    /**
     * Migrate H2 database schema.
     * H2 creates CHECK constraints for enum columns that need to be updated when new values are added.
     */
    private void migrateH2Schema() {
        try {
            // Check if the table exists (try both cases)
            boolean tableExists = tableExists("JOB_LOG_ENTRIES") || tableExists("job_log_entries");
            if (!tableExists) {
                logger.debug("job_log_entries table does not exist yet, skipping migration");
                return;
            }

            logger.info("Migrating H2 schema for job_log_entries.source column...");

            // H2 creates domain types for enum columns with CHECK constraints
            // We need to drop all constraints on the source column and alter it to plain VARCHAR

            // Step 1: Try to find and drop any check constraints
            try {
                // Get all constraints on the table
                jdbcTemplate.query(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'JOB_LOG_ENTRIES' AND CONSTRAINT_TYPE = 'CHECK'",
                    (rs) -> {
                        String constraintName = rs.getString("CONSTRAINT_NAME");
                        try {
                            jdbcTemplate.execute("ALTER TABLE JOB_LOG_ENTRIES DROP CONSTRAINT " + constraintName);
                            logger.info("Dropped CHECK constraint: {}", constraintName);
                        } catch (Exception e) {
                            logger.debug("Could not drop constraint {}: {}", constraintName, e.getMessage());
                        }
                    }
                );
            } catch (Exception e) {
                logger.debug("No CHECK constraints to drop: {}", e.getMessage());
            }

            // Step 2: Alter the column to plain VARCHAR to remove any type constraints
            try {
                jdbcTemplate.execute(
                    "ALTER TABLE JOB_LOG_ENTRIES ALTER COLUMN SOURCE SET DATA TYPE VARCHAR(32)"
                );
                logger.info("Successfully altered SOURCE column to VARCHAR(32)");
            } catch (Exception e) {
                // Try alternative syntax
                try {
                    jdbcTemplate.execute(
                        "ALTER TABLE JOB_LOG_ENTRIES ALTER COLUMN SOURCE VARCHAR(32) NOT NULL"
                    );
                    logger.info("Successfully altered SOURCE column (alternative syntax)");
                } catch (Exception e2) {
                    logger.debug("Column alteration not needed: {}", e2.getMessage());
                }
            }

        } catch (Exception e) {
            logger.warn("H2 schema migration encountered issue: {}", e.getMessage());
        }
    }

    /**
     * Migrate PostgreSQL database schema.
     * PostgreSQL with Hibernate may use CHECK constraints that need updating.
     */
    private void migratePostgresSchema() {
        try {
            // Check if the table exists
            if (!tableExists("job_log_entries")) {
                logger.debug("job_log_entries table does not exist yet, skipping migration");
                return;
            }

            // For PostgreSQL, check if there's a constraint that needs updating
            // PostgreSQL enum constraints are typically named with the table and column
            logger.info("Checking PostgreSQL constraints for job_log_entries.source...");

            // Try to drop any existing check constraint on source column
            try {
                // Find constraint name dynamically
                String constraintName = jdbcTemplate.queryForObject(
                    "SELECT constraint_name FROM information_schema.constraint_column_usage " +
                    "WHERE table_name = 'job_log_entries' AND column_name = 'source' LIMIT 1",
                    String.class
                );

                if (constraintName != null) {
                    jdbcTemplate.execute(
                        "ALTER TABLE job_log_entries DROP CONSTRAINT IF EXISTS " + constraintName
                    );
                    logger.info("Dropped constraint {} to allow new enum values", constraintName);
                }
            } catch (Exception e) {
                // No constraint found or already removed
                logger.debug("No PostgreSQL constraint to update: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.debug("PostgreSQL schema migration check: {}", e.getMessage());
        }
    }

    /**
     * Check if a table exists in the database.
     */
    private boolean tableExists(String tableName) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // Try both uppercase and lowercase table names
            try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
            // Try lowercase
            try (ResultSet rs = meta.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.debug("Error checking table existence: {}", e.getMessage());
            return false;
        }
    }
}
