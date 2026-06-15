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

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Bridge service for handling database schema migrations that Hibernate's
 * automatic schema update cannot handle properly.
 *
 * This service runs migrations BEFORE Hibernate's schema update to avoid
 * issues like adding NOT NULL columns to tables with existing data.
 *
 * Common issues this service solves:
 * - Adding NOT NULL columns with default values to existing tables
 * - Renaming columns
 * - Complex data migrations
 * - Index modifications
 */
@Slf4j
public class SchemaMigrationBridgeService {

    private final DataSource dataSource;

    public SchemaMigrationBridgeService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Run all pending migrations. This should be called before Hibernate
     * initializes its EntityManagerFactory.
     */
    public void runMigrations() {
        log.info("Running database schema bridge migrations...");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            // Run staging_service_config migrations
            migrateStagingServiceConfig(conn);

            log.info("Database schema bridge migrations completed successfully");
        } catch (SQLException e) {
            log.error("Failed to run database migrations", e);
            throw new RuntimeException("Database migration failed", e);
        }
    }

    /**
     * Migrate staging_service_config table to add new columns with proper defaults.
     */
    private void migrateStagingServiceConfig(Connection conn) throws SQLException {
        String tableName = "STAGING_SERVICE_CONFIG";

        boolean tableExists = tableExistsH2(conn, tableName);
        log.info("Checking table {}: exists={}", tableName, tableExists);

        if (!tableExists) {
            log.info("Table {} does not exist yet, skipping migration (Hibernate will create it)", tableName);
            return;
        }

        // List of column migrations: (columnName, columnType, defaultValue)
        // Add new migrations here when adding NOT NULL columns to existing tables
        List<ColumnMigration> migrations = List.of(
            new ColumnMigration("RETRYPOLLINTERVALSECONDS", "INTEGER", "30")
        );

        for (ColumnMigration migration : migrations) {
            addColumnWithDefaultIfMissing(conn, tableName, migration);
        }
    }

    /**
     * Check if a table exists in H2 database using direct SQL query.
     * This is more reliable than DatabaseMetaData for H2.
     */
    private boolean tableExistsH2(Connection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = 'PUBLIC' AND UPPER(TABLE_NAME) = UPPER('" + tableName + "')";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                int count = rs.getInt(1);
                log.debug("Table existence check for {}: count={}", tableName, count);
                return count > 0;
            }
        } catch (SQLException e) {
            log.warn("Error checking if table {} exists: {}", tableName, e.getMessage());
            // Try alternative approach - just try to select from the table
            return tableExistsBySelect(conn, tableName);
        }
        return false;
    }

    /**
     * Fallback check - try to select from the table.
     */
    private boolean tableExistsBySelect(Connection conn, String tableName) {
        String sql = "SELECT 1 FROM " + tableName + " WHERE 1=0";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return true;
        } catch (SQLException e) {
            log.debug("Table {} does not exist (SELECT check): {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a column exists in H2 database using direct SQL query.
     */
    private boolean columnExistsH2(Connection conn, String tableName, String columnName) {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = 'PUBLIC' " +
                     "AND UPPER(TABLE_NAME) = UPPER('" + tableName + "') " +
                     "AND UPPER(COLUMN_NAME) = UPPER('" + columnName + "')";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                int count = rs.getInt(1);
                log.debug("Column existence check for {}.{}: count={}", tableName, columnName, count);
                return count > 0;
            }
        } catch (SQLException e) {
            log.warn("Error checking if column {}.{} exists: {}", tableName, columnName, e.getMessage());
        }
        return false;
    }

    /**
     * Add a column with a default value if it doesn't exist.
     * This avoids the "NULL not allowed" error when Hibernate tries to add NOT NULL columns.
     */
    private void addColumnWithDefaultIfMissing(Connection conn, String tableName, ColumnMigration migration)
            throws SQLException {

        boolean columnExists = columnExistsH2(conn, tableName, migration.columnName);
        log.info("Checking column {}.{}: exists={}", tableName, migration.columnName, columnExists);

        if (columnExists) {
            log.info("Column {}.{} already exists, skipping migration", tableName, migration.columnName);
            return;
        }

        log.info("Adding column {}.{} with type {} and default value {}",
                 tableName, migration.columnName, migration.columnType, migration.defaultValue);

        // For H2 database, add the column with a default value
        // Using the format that works with H2's ALTER TABLE syntax
        String addColumnSql = String.format(
            "ALTER TABLE %s ADD COLUMN %s %s DEFAULT %s NOT NULL",
            tableName, migration.columnName, migration.columnType, migration.defaultValue
        );

        try (Statement stmt = conn.createStatement()) {
            log.info("Executing SQL: {}", addColumnSql);
            stmt.execute(addColumnSql);
            log.info("Successfully added column {}.{}", tableName, migration.columnName);
        } catch (SQLException e) {
            // If "column already exists" error, that's fine
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                log.info("Column {}.{} already exists (detected via exception)", tableName, migration.columnName);
            } else {
                log.error("Failed to add column {}.{}: {}", tableName, migration.columnName, e.getMessage());
                throw e;
            }
        }

        // Verify the column was added
        if (columnExistsH2(conn, tableName, migration.columnName)) {
            log.info("Verified column {}.{} exists after migration", tableName, migration.columnName);
        } else {
            log.error("Column {}.{} was not created! This will cause errors.", tableName, migration.columnName);
            throw new SQLException("Failed to create column " + tableName + "." + migration.columnName);
        }
    }

    /**
     * Helper record for column migration definitions.
     */
    private record ColumnMigration(String columnName, String columnType, String defaultValue) {}
}
