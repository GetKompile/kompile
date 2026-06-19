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

package ai.kompile.crawler.sql;

import ai.kompile.core.crawler.*;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Crawler that discovers and ingests rows from SQL databases via JDBC.
 *
 * <p>Supports three modes of operation:</p>
 * <ul>
 *   <li><b>Table scan</b>: Crawl all rows from specified tables ({@code tables} property)</li>
 *   <li><b>Custom query</b>: Execute a user-provided SQL query ({@code query} property)</li>
 *   <li><b>Schema discovery</b>: If neither is specified, discovers all tables and crawls them</li>
 * </ul>
 *
 * <p>Each row is serialized to a temporary JSON file and emitted as a {@link CrawlItem}
 * with {@code SourceType.FILE}, identical to how the remote folder crawler handles
 * downloaded files. This lets the downstream ingest pipeline process them uniformly.</p>
 *
 * <p>Connection properties:</p>
 * <ul>
 *   <li>{@code jdbcUrl} (required) — JDBC connection URL</li>
 *   <li>{@code username} — database username</li>
 *   <li>{@code password} — database password</li>
 *   <li>{@code driver} — JDBC driver class name (auto-detected if omitted)</li>
 *   <li>{@code tables} — comma-separated table names to crawl (optional)</li>
 *   <li>{@code query} — custom SQL query (optional, overrides table scan)</li>
 *   <li>{@code schemaName} — schema to restrict table discovery (optional)</li>
 *   <li>{@code catalogName} — catalog to restrict table discovery (optional)</li>
 *   <li>{@code fetchSize} — JDBC fetch size hint (default: 500)</li>
 *   <li>{@code rowFormat} — output format per row: "json" (default) or "text"</li>
 * </ul>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Automatic table/schema discovery via JDBC metadata</li>
 *   <li>Primary key detection for row identification</li>
 *   <li>Incremental crawling via visited-row tracking</li>
 *   <li>Configurable fetch size for memory-efficient streaming</li>
 *   <li>Pause/resume/cancel support</li>
 * </ul>
 */
@Component
public class SqlCrawler extends AbstractCrawler {

    private static final Logger log = LoggerFactory.getLogger(SqlCrawler.class);

    @Override
    public String getId() {
        return "sql";
    }

    @Override
    public String getName() {
        return "SQL Database Crawler";
    }

    @Override
    public String getDescription() {
        return "Discovers and ingests rows from SQL databases (MySQL, PostgreSQL, SQLite, "
                + "SQL Server, Oracle, etc.) via JDBC with table discovery and custom query support";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.SQL);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = config.getProperties();
        if (props == null) props = Map.of();

        Object jdbcUrl = props.get("jdbcUrl");
        if (jdbcUrl == null || jdbcUrl.toString().isBlank()) {
            // Also accept the seed as a JDBC URL
            String seed = config.getSeed();
            if (seed == null || !seed.startsWith("jdbc:")) {
                errors.add("SQL crawler requires property 'jdbcUrl' or a seed starting with 'jdbc:'");
            }
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new SqlCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob job) throws Exception {
        SqlCrawlJob sqlJob = (SqlCrawlJob) job;
        CrawlConfig config = job.getConfig();
        Map<String, Object> props = config.getProperties() != null
                ? config.getProperties() : Map.of();

        // Resolve JDBC URL — prefer explicit property, fall back to seed
        String jdbcUrl = stringProp(props, "jdbcUrl", null);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = config.getSeed();
        }
        String username = stringProp(props, "username", null);
        String password = stringProp(props, "password", null);
        String driverClass = stringProp(props, "driver", null);
        String schemaName = stringProp(props, "schemaName", null);
        String catalogName = stringProp(props, "catalogName", null);
        int fetchSize = intProp(props, "fetchSize", 500);
        String rowFormat = stringProp(props, "rowFormat", "json");

        // Optional: explicit table list or custom query
        String tablesStr = stringProp(props, "tables", null);
        String customQuery = stringProp(props, "query", null);

        // Create temp directory for serialized rows
        Path outputDir = Files.createTempDirectory("kompile-sql-crawl-" + job.getJobId());

        log.info("[{}] Starting SQL crawl: jdbcUrl={}", job.getJobId(), redactJdbcUrl(jdbcUrl));

        // Load driver if specified
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw new IOException("JDBC driver not found: " + driverClass, e);
            }
        }

        Connection conn = null;
        try {
            // Connect
            if (username != null) {
                conn = DriverManager.getConnection(jdbcUrl, username, password);
            } else {
                conn = DriverManager.getConnection(jdbcUrl);
            }
            try {
                conn.setReadOnly(true);
            } catch (SQLException e) {
                log.debug("[{}] Could not set read-only mode: {}", job.getJobId(), e.getMessage());
            }

            String databaseProduct = "unknown";
            try {
                databaseProduct = conn.getMetaData().getDatabaseProductName();
            } catch (SQLException e) {
                log.warn("Could not determine database product name: {}", e.getMessage());
            }

            log.info("[{}] Connected to {} database", job.getJobId(), databaseProduct);

            if (customQuery != null && !customQuery.isBlank()) {
                // Custom query mode
                crawlQuery(sqlJob, conn, customQuery, "query", fetchSize, rowFormat,
                        outputDir, databaseProduct, jdbcUrl);
            } else {
                // Table scan mode
                List<String> tables;
                if (tablesStr != null && !tablesStr.isBlank()) {
                    tables = Arrays.stream(tablesStr.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                } else {
                    tables = discoverTables(conn, schemaName, catalogName);
                }

                log.info("[{}] Will crawl {} tables: {}", job.getJobId(), tables.size(), tables);

                for (String table : tables) {
                    if (sqlJob.shouldStop()) break;
                    if (!sqlJob.checkPauseAndContinue()) break;

                    String query = "SELECT * FROM " + quoteIdentifier(conn, table);
                    crawlQuery(sqlJob, conn, query, table, fetchSize, rowFormat,
                            outputDir, databaseProduct, jdbcUrl);
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.debug("[{}] Error closing JDBC connection: {}", job.getJobId(), e.getMessage());
                }
            }
        }

        log.info("[{}] SQL crawl complete: discovered={}", job.getJobId(), sqlJob.getDiscoveredCount());
    }

    /**
     * Executes a query and emits each row as a CrawlItem.
     */
    private void crawlQuery(SqlCrawlJob job, Connection conn, String query, String tableName,
                            int fetchSize, String rowFormat, Path outputDir,
                            String databaseProduct, String jdbcUrl) throws Exception {
        CrawlConfig config = job.getConfig();

        // Detect primary key columns for the table
        List<String> pkColumns = List.of();
        if (!"query".equals(tableName)) {
            pkColumns = getPrimaryKeyColumns(conn, tableName);
        }

        log.info("[{}] Crawling table/query '{}' (PK columns: {})", job.getJobId(), tableName, pkColumns);

        try (Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(fetchSize);
            try (ResultSet rs = stmt.executeQuery(query)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                String[] columnNames = new String[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    columnNames[i] = meta.getColumnLabel(i + 1);
                }

                long rowIndex = 0;
                int processed = 0;
                // Buffer a bounded number of rows so the table can also be emitted as a single
                // content_type=table document (→ one TABLE graph node), alongside per-row docs.
                List<List<String>> tableRowsBuffer = new ArrayList<>();
                final int maxTableGraphRows = 1000;

                while (rs.next()) {
                    if (job.shouldStop()) break;
                    if (!job.checkPauseAndContinue()) break;

                    // Extract row data
                    Map<String, Object> columns = new LinkedHashMap<>();
                    for (int i = 0; i < columnCount; i++) {
                        Object value = rs.getObject(i + 1);
                        columns.put(columnNames[i], value != null ? value : null);
                    }

                    // Capture row values (bounded) for the aggregated table document.
                    if (tableRowsBuffer.size() < maxTableGraphRows) {
                        List<String> rowVals = new ArrayList<>(columnCount);
                        for (String cn : columnNames) {
                            Object v = columns.get(cn);
                            rowVals.add(v != null ? String.valueOf(v) : "");
                        }
                        tableRowsBuffer.add(rowVals);
                    }

                    // Build row ID from primary key or row index
                    String rowId = buildRowId(columns, pkColumns, rowIndex);
                    String rowKey = tableName + ":" + rowId;

                    // Skip if already visited (incremental)
                    if (job.visitedRowKeys.contains(rowKey)) {
                        job.incrementSkipped();
                        job.getListener().onDocumentSkipped(rowKey, "already processed in previous crawl");
                        rowIndex++;
                        continue;
                    }

                    // Serialize row to temp file
                    String content = formatRow(columnNames, columns, rowFormat, tableName, rowId);
                    String safeFileName = tableName.replaceAll("[^a-zA-Z0-9._-]", "_")
                            + "_row_" + rowIndex + ("json".equals(rowFormat) ? ".json" : ".txt");
                    Path rowFile = outputDir.resolve(safeFileName);
                    Files.writeString(rowFile, content, StandardCharsets.UTF_8);

                    // Track
                    job.visitedRowKeys.add(rowKey);
                    job.tableHighWaterMarks.merge(tableName, rowIndex, Math::max);

                    // Build metadata
                    String localPath = rowFile.toAbsolutePath().toString();
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put(GraphConstants.META_SQL_TABLE_NAME, tableName);
                    metadata.put(GraphConstants.META_SQL_ROW_ID, rowId);
                    metadata.put(GraphConstants.META_SQL_ROW_INDEX, rowIndex);
                    metadata.put(GraphConstants.META_SQL_COLUMN_COUNT, columnCount);
                    metadata.put(GraphConstants.META_SQL_COLUMN_NAMES, String.join(",", columnNames));
                    metadata.put(GraphConstants.META_SQL_JDBC_URL, redactJdbcUrl(jdbcUrl));
                    metadata.put(GraphConstants.META_SQL_DATABASE_PRODUCT, databaseProduct);
                    metadata.put(GraphConstants.META_LOADER, "SQL Database Crawler");
                    metadata.put(GraphConstants.META_DOCUMENT_TYPE, "database-row");
                    metadata.put(GraphConstants.META_SOURCE_TYPE, SourceType.SQL.name());
                    metadata.put(GraphConstants.META_SOURCE_PATH, rowKey);
                    metadata.put("crawlJobId", job.getJobId());
                    if (!pkColumns.isEmpty()) {
                        metadata.put(GraphConstants.META_SQL_PRIMARY_KEY, String.join(",", pkColumns));
                    }
                    if (!"query".equals(tableName)) {
                        metadata.put(GraphConstants.META_SQL_QUERY, query);
                    }

                    CrawlItem item = CrawlItem.builder()
                            .url(localPath)
                            .parentUrl(tableName)
                            .depth(0)
                            .contentType("json".equals(rowFormat) ? "application/json" : "text/plain")
                            .contentLength((long) content.length())
                            .discoveredAt(Instant.now())
                            .sourceDescriptor(DocumentSourceDescriptor.builder()
                                    .type(SourceType.FILE)
                                    .pathOrUrl(localPath)
                                    .sourceId(rowKey)
                                    .originalFileName(safeFileName)
                                    .collectionName(config.getCollectionName())
                                    .build())
                            .metadata(metadata)
                            .build();

                    job.incrementDiscovered();
                    job.getListener().onDocumentDiscovered(item);
                    job.incrementProcessed();
                    job.getListener().onDocumentProcessed(item);

                    processed++;
                    rowIndex++;

                    if (processed % 100 == 0) {
                        job.getListener().onProgress(job.getProgress());
                        log.debug("[{}] Processed {} rows from '{}'", job.getJobId(), processed, tableName);
                    }
                }

                // Emit the table as a single content_type=table document so it surfaces as one TABLE
                // graph node (and renders) in the index browser, alongside the per-row documents.
                if (!tableRowsBuffer.isEmpty() && !job.shouldStop()) {
                    emitSqlTableSummary(job, tableName, columnNames, tableRowsBuffer, rowIndex,
                            rowIndex > tableRowsBuffer.size(), outputDir, jdbcUrl, databaseProduct);
                }

                log.info("[{}] Table '{}': processed {} rows", job.getJobId(), tableName, processed);
            }
        }
    }

    /**
     * Emits one aggregated {@code content_type=table} document per SQL table so the table appears as a
     * single TABLE graph node in the index browser (the per-row documents are still emitted for search).
     * For large tables only the first {@code maxTableGraphRows} rows are rendered; {@code table_truncated}
     * records that the markdown is a sample of {@code table_total_rows}.
     */
    private void emitSqlTableSummary(SqlCrawlJob job, String tableName, String[] columnNames,
                                     List<List<String>> rows, long totalRows, boolean truncated,
                                     Path outputDir, String jdbcUrl, String databaseProduct) throws IOException {
        CrawlConfig config = job.getConfig();

        List<List<String>> withHeader = new ArrayList<>();
        withHeader.add(new ArrayList<>(java.util.Arrays.asList(columnNames)));
        withHeader.addAll(rows);
        String markdown = TableCellGraphBuilder.toMarkdown(withHeader, true);

        Graph graph = new TableCellGraphBuilder()
                .namespace("sql:" + redactJdbcUrl(jdbcUrl) + "/table:" + tableName)
                .tableName(tableName)
                .rows(withHeader)
                .firstRowIsHeader(true)
                .build();

        String safeFileName = tableName.replaceAll("[^a-zA-Z0-9._-]", "_") + "_table.md";
        Path tableFile = outputDir.resolve(safeFileName);
        Files.writeString(tableFile, markdown, StandardCharsets.UTF_8);
        String localPath = tableFile.toAbsolutePath().toString();
        String sourceKey = "table:" + tableName;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SQL_TABLE_NAME, tableName);
        metadata.put(GraphConstants.META_SQL_COLUMN_NAMES, String.join(",", columnNames));
        metadata.put(GraphConstants.META_SQL_JDBC_URL, redactJdbcUrl(jdbcUrl));
        metadata.put(GraphConstants.META_SQL_DATABASE_PRODUCT, databaseProduct);
        metadata.put(GraphConstants.META_LOADER, "SQL Database Crawler");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "database-table");
        metadata.put(GraphConstants.META_CONTENT_TYPE, "table");
        metadata.put(GraphConstants.META_SOURCE_TYPE, SourceType.SQL.name());
        metadata.put(GraphConstants.META_SOURCE_PATH, sourceKey);
        metadata.put(GraphConstants.META_TABLE_ROW_COUNT, rows.size());
        metadata.put(GraphConstants.META_TABLE_COLUMN_COUNT, columnNames.length);
        metadata.put(GraphConstants.META_TABLE_HEADERS, String.join(",", columnNames));
        metadata.put("full_table_content", markdown);
        metadata.put("table_extraction_method", "sql");
        if (truncated) {
            metadata.put("table_truncated", true);
            metadata.put("table_total_rows", totalRows);
        }
        if (!graph.getEntities().isEmpty()) {
            metadata.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(graph));
        }
        metadata.put("crawlJobId", job.getJobId());

        CrawlItem item = CrawlItem.builder()
                .url(localPath)
                .parentUrl(tableName)
                .depth(0)
                .contentType("text/markdown")
                .contentLength((long) markdown.length())
                .discoveredAt(Instant.now())
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(SourceType.FILE)
                        .pathOrUrl(localPath)
                        .sourceId(sourceKey)
                        .originalFileName(safeFileName)
                        .collectionName(config.getCollectionName())
                        .build())
                .metadata(metadata)
                .build();

        job.incrementDiscovered();
        job.getListener().onDocumentDiscovered(item);
        job.incrementProcessed();
        job.getListener().onDocumentProcessed(item);
    }

    /**
     * Discovers all user tables in the database via JDBC metadata.
     */
    private List<String> discoverTables(Connection conn, String schemaName, String catalogName)
            throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getTables(catalogName, schemaName, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                // Skip common system/internal tables
                if (name != null && !name.startsWith("sqlite_") && !name.startsWith("pg_")
                        && !name.startsWith("information_schema")
                        && !name.startsWith("sys.") && !name.startsWith("SYSTEM_")) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    /**
     * Gets primary key column names for a table.
     */
    private List<String> getPrimaryKeyColumns(Connection conn, String tableName) {
        List<String> pkCols = new ArrayList<>();
        try {
            DatabaseMetaData dbMeta = conn.getMetaData();
            try (ResultSet rs = dbMeta.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    pkCols.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            log.debug("Could not determine primary keys for table '{}': {}", tableName, e.getMessage());
        }
        return pkCols;
    }

    /**
     * Builds a row identifier from primary key values or falls back to row index.
     */
    private String buildRowId(Map<String, Object> columns, List<String> pkColumns, long rowIndex) {
        if (pkColumns.isEmpty()) {
            return String.valueOf(rowIndex);
        }
        StringJoiner joiner = new StringJoiner("_");
        for (String pk : pkColumns) {
            Object val = columns.get(pk);
            joiner.add(val != null ? val.toString() : "null");
        }
        return joiner.toString();
    }

    /**
     * Formats a row as either JSON or plain text.
     */
    private String formatRow(String[] columnNames, Map<String, Object> columns,
                             String format, String tableName, String rowId) {
        if ("text".equals(format)) {
            return formatRowAsText(columnNames, columns, tableName, rowId);
        }
        return formatRowAsJson(columnNames, columns, tableName, rowId);
    }

    private String formatRowAsJson(String[] columnNames, Map<String, Object> columns,
                                   String tableName, String rowId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_table\": ").append(jsonString(tableName)).append(",\n");
        sb.append("  \"_rowId\": ").append(jsonString(rowId)).append(",\n");
        for (int i = 0; i < columnNames.length; i++) {
            String col = columnNames[i];
            Object val = columns.get(col);
            sb.append("  ").append(jsonString(col)).append(": ");
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Number) {
                sb.append(val);
            } else if (val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append(jsonString(val.toString()));
            }
            if (i < columnNames.length - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private String formatRowAsText(String[] columnNames, Map<String, Object> columns,
                                   String tableName, String rowId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(tableName).append(" | Row: ").append(rowId).append("\n");
        for (String col : columnNames) {
            Object val = columns.get(col);
            sb.append(col).append(": ").append(val != null ? val.toString() : "(null)").append("\n");
        }
        return sb.toString();
    }

    /**
     * Quotes a SQL identifier using the database's quoting character.
     */
    private String quoteIdentifier(Connection conn, String identifier) {
        try {
            String q = conn.getMetaData().getIdentifierQuoteString();
            if (q == null || q.isBlank() || " ".equals(q)) {
                return identifier;
            }
            return q + identifier + q;
        } catch (SQLException e) {
            return identifier;
        }
    }

    /**
     * Redacts credentials from a JDBC URL for logging.
     */
    static String redactJdbcUrl(String url) {
        if (url == null) return "null";
        // Redact password=..., user=..., etc. in query params
        return url.replaceAll("(?i)(password|passwd|pwd|secret)=[^&;]*", "$1=***");
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String stringProp(Map<String, Object> props, String key, String defaultValue) {
        Object v = props.get(key);
        if (v == null) return defaultValue;
        String s = v.toString();
        return s.isBlank() ? defaultValue : s;
    }

    private static int intProp(Map<String, Object> props, String key, int defaultValue) {
        Object v = props.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
