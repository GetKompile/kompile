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
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SqlCrawlerTest {

    private Path tempDbFile;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        // Create a temp SQLite database
        tempDbFile = Files.createTempFile("kompile-sql-test-", ".db");
        jdbcUrl = "jdbc:sqlite:" + tempDbFile.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT, department TEXT, salary REAL)");
            stmt.execute("INSERT INTO employees VALUES (1, 'Alice', 'Engineering', 95000.0)");
            stmt.execute("INSERT INTO employees VALUES (2, 'Bob', 'Marketing', 72000.0)");
            stmt.execute("INSERT INTO employees VALUES (3, 'Charlie', 'Engineering', 88000.0)");

            stmt.execute("CREATE TABLE projects (project_id INTEGER PRIMARY KEY, title TEXT, lead_id INTEGER)");
            stmt.execute("INSERT INTO projects VALUES (100, 'Alpha', 1)");
            stmt.execute("INSERT INTO projects VALUES (101, 'Beta', 3)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempDbFile);
    }

    @Test
    void testSupportedSourceTypes() {
        SqlCrawler crawler = new SqlCrawler();
        Set<SourceType> supported = crawler.getSupportedSourceTypes();
        assertTrue(supported.contains(SourceType.SQL));
        assertEquals(1, supported.size());
        assertFalse(supported.contains(SourceType.FILE));
    }

    @Test
    void testCrawlerMetadata() {
        SqlCrawler crawler = new SqlCrawler();
        assertEquals("sql", crawler.getId());
        assertEquals("SQL Database Crawler", crawler.getName());
        assertNotNull(crawler.getDescription());
        assertTrue(crawler.getDescription().contains("JDBC"));
    }

    @Test
    void testValidation_missingJdbcUrl() {
        SqlCrawler crawler = new SqlCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("not-a-jdbc-url")
                .sourceType(SourceType.SQL)
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("jdbcUrl")));
    }

    @Test
    void testValidation_jdbcUrlInProperties() {
        SqlCrawler crawler = new SqlCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("any-seed")
                .sourceType(SourceType.SQL)
                .properties(Map.of("jdbcUrl", "jdbc:sqlite::memory:"))
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void testValidation_jdbcUrlAsSeed() {
        SqlCrawler crawler = new SqlCrawler();
        CrawlConfig config = CrawlConfig.builder()
                .seed("jdbc:sqlite::memory:")
                .sourceType(SourceType.SQL)
                .properties(Map.of())
                .build();

        List<String> errors = crawler.validate(config);
        assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
    }

    @Test
    void testCrawlSingleTable() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("tables", "employees"))
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        CrawlSummary summary = job.getCompletionFuture().get();
        if (summary.status() == CrawlStatus.FAILED) {
            fail("Crawl failed with errors: " + summary.errors());
        }
        assertEquals(CrawlStatus.COMPLETED, job.getStatus());
        assertEquals(3, discovered.size());

        // Verify first row
        CrawlItem first = discovered.get(0);
        assertNotNull(first.getUrl());
        assertTrue(first.getUrl().endsWith(".json"));
        assertEquals("application/json", first.getContentType());
        assertNotNull(first.getSourceDescriptor());
        assertEquals(SourceType.FILE, first.getSourceDescriptor().getType());

        // Verify metadata
        Map<String, Object> meta = first.getMetadata();
        assertEquals("employees", meta.get(GraphConstants.META_SQL_TABLE_NAME));
        assertEquals("SQL Database Crawler", meta.get(GraphConstants.META_LOADER));
        assertEquals("database-row", meta.get(GraphConstants.META_DOCUMENT_TYPE));
        assertEquals("SQL", meta.get(GraphConstants.META_SOURCE_TYPE));

        // Verify serialized file content
        String content = Files.readString(Path.of(first.getUrl()));
        assertTrue(content.contains("\"_table\": \"employees\""));
        assertTrue(content.contains("Alice") || content.contains("Bob") || content.contains("Charlie"));
    }

    @Test
    void testCrawlMultipleTables() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("tables", "employees,projects"))
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        job.getCompletionFuture().get();
        assertEquals(CrawlStatus.COMPLETED, job.getStatus());
        assertEquals(5, discovered.size()); // 3 employees + 2 projects
    }

    @Test
    void testCrawlCustomQuery() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("query", "SELECT name, salary FROM employees WHERE department = 'Engineering'"))
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        job.getCompletionFuture().get();
        assertEquals(CrawlStatus.COMPLETED, job.getStatus());
        assertEquals(2, discovered.size()); // Alice and Charlie

        // Verify content of first result
        String content = Files.readString(Path.of(discovered.get(0).getUrl()));
        assertTrue(content.contains("name"));
        assertTrue(content.contains("salary"));
    }

    @Test
    void testCrawlAutoDiscoverTables() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of())
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        job.getCompletionFuture().get();
        assertEquals(CrawlStatus.COMPLETED, job.getStatus());
        // Should discover both tables: 3 employees + 2 projects
        assertEquals(5, discovered.size());
    }

    @Test
    void testTextFormat() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of(
                        "tables", "employees",
                        "rowFormat", "text"
                ))
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        job.getCompletionFuture().get();
        assertEquals(3, discovered.size());

        CrawlItem first = discovered.get(0);
        assertTrue(first.getUrl().endsWith(".txt"));
        assertEquals("text/plain", first.getContentType());

        String content = Files.readString(Path.of(first.getUrl()));
        assertTrue(content.contains("Table: employees"));
        assertTrue(content.contains("name:"));
    }

    @Test
    void testPrimaryKeyInMetadata() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered = new CopyOnWriteArrayList<>();

        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("tables", "employees"))
                .build();

        CrawlJob job = crawler.start(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered.add(item);
            }
        });

        job.getCompletionFuture().get();

        // Row ID should be the PK value (1, 2, 3)
        Set<String> rowIds = new HashSet<>();
        for (CrawlItem item : discovered) {
            rowIds.add((String) item.getMetadata().get(GraphConstants.META_SQL_ROW_ID));
        }
        assertTrue(rowIds.contains("1"));
        assertTrue(rowIds.contains("2"));
        assertTrue(rowIds.contains("3"));
    }

    @Test
    void testIncrementalCrawl() throws Exception {
        SqlCrawler crawler = new SqlCrawler();
        List<CrawlItem> discovered1 = new CopyOnWriteArrayList<>();

        CrawlConfig config1 = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("tables", "employees"))
                .build();

        CrawlJob job1 = crawler.start(config1, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered1.add(item);
            }
        });
        job1.getCompletionFuture().get();
        assertEquals(3, discovered1.size());

        // Second crawl with previous state should skip already-visited rows
        SqlCrawlJob sqlJob1 = (SqlCrawlJob) job1;
        CrawlState state = sqlJob1.checkpoint();

        List<CrawlItem> discovered2 = new CopyOnWriteArrayList<>();
        List<String> skipped = new CopyOnWriteArrayList<>();

        CrawlConfig config2 = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .properties(Map.of("tables", "employees"))
                .previousState(state)
                .build();

        CrawlJob job2 = crawler.start(config2, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                discovered2.add(item);
            }

            @Override
            public void onDocumentSkipped(String url, String reason) {
                skipped.add(url);
            }
        });
        job2.getCompletionFuture().get();

        // All rows should be skipped on second crawl
        assertEquals(0, discovered2.size());
        assertEquals(3, skipped.size());
    }

    @Test
    void testCheckpointState() {
        CrawlConfig config = CrawlConfig.builder()
                .seed(jdbcUrl)
                .sourceType(SourceType.SQL)
                .build();

        SqlCrawlJob job = new SqlCrawlJob("test-1", config, new CrawlEventListener() {});
        job.visitedRowKeys.add("employees:1");
        job.visitedRowKeys.add("employees:2");
        job.tableHighWaterMarks.put("employees", 1L);

        CrawlState checkpoint = job.checkpoint();
        assertNotNull(checkpoint);
        assertNotNull(checkpoint.getTimestamp());
        assertEquals(2, checkpoint.getVisitedUrls().size());
        assertTrue(checkpoint.getVisitedUrls().contains("employees:1"));
        assertEquals(1, checkpoint.getLastModifiedTimes().size());
        assertEquals(1L, checkpoint.getLastModifiedTimes().get("employees"));
    }

    @Test
    void testRedactJdbcUrl() {
        assertEquals("jdbc:mysql://host/db?user=admin&password=***",
                SqlCrawler.redactJdbcUrl("jdbc:mysql://host/db?user=admin&password=secret123"));
        assertEquals("jdbc:sqlite:/tmp/test.db",
                SqlCrawler.redactJdbcUrl("jdbc:sqlite:/tmp/test.db"));
        assertEquals("null", SqlCrawler.redactJdbcUrl(null));
    }

    @Test
    void testSqlRowEntry() {
        SqlRowEntry entry = new SqlRowEntry(
                "employees", "42", 0,
                new String[]{"id", "name"},
                Map.of("id", 42, "name", "Alice"));

        assertEquals("employees", entry.tableName());
        assertEquals("42", entry.rowId());
        assertEquals(0, entry.rowIndex());
        assertArrayEquals(new String[]{"id", "name"}, entry.columnNames());
        assertEquals(42, entry.columns().get("id"));
    }
}
