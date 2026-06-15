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

package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.service.LanguageParser.ExtractionOutput;
import ai.kompile.codeindexer.service.parsers.ConfigLanguageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLanguageParserTest {

    private ConfigLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConfigLanguageParser();
    }

    @Test
    void supportedLanguages() {
        Set<String> langs = parser.supportedLanguages();
        assertTrue(langs.contains("sql"));
        assertTrue(langs.contains("bash"));
        assertTrue(langs.contains("terraform"));
        assertTrue(langs.contains("protobuf"));
        assertTrue(langs.contains("graphql"));
    }

    // ── SQL: Basic DDL ─────────────────────────────────────────────────

    @Test
    void parseSqlCreateTable() {
        String sql = """
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    email TEXT UNIQUE
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "schema.sql", "default", "sql");

        List<CodeEntity> tables = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertEquals(1, tables.size(), "Should find 1 table");
        assertEquals("users", tables.get(0).getName());

        // Column extraction
        List<CodeEntity> columns = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertTrue(columns.size() >= 3, "Should find at least 3 columns (id, name, email)");

        List<String> colNames = columns.stream().map(CodeEntity::getName).toList();
        assertTrue(colNames.contains("id"), "Should find id column");
        assertTrue(colNames.contains("name"), "Should find name column");
        assertTrue(colNames.contains("email"), "Should find email column");
    }

    @Test
    void parseSqlCreateTableColumnContainment() {
        String sql = """
                CREATE TABLE orders (
                    order_id BIGINT,
                    total DECIMAL(10,2),
                    created_at TIMESTAMP
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "orders.sql", "default", "sql");

        String tableFqn = "orders.sql#table:orders";
        List<RelationTriple> containsColumns = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS
                        && r.sourceFqn().equals(tableFqn)
                        && r.targetFqn().startsWith(tableFqn + "."))
                .toList();
        assertTrue(containsColumns.size() >= 3, "Table should CONTAIN its columns");
    }

    @Test
    void parseSqlCreateView() {
        String sql = """
                CREATE OR REPLACE VIEW active_users AS
                    SELECT * FROM users WHERE active = true;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "views.sql", "default", "sql");

        List<CodeEntity> views = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS
                        && e.getFullyQualifiedName().contains("view:"))
                .toList();
        assertEquals(1, views.size(), "Should find 1 view");
        assertEquals("active_users", views.get(0).getName());
    }

    @Test
    void parseSqlCreateIndex() {
        String sql = """
                CREATE UNIQUE INDEX idx_users_email ON users (email);
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "indexes.sql", "default", "sql");

        List<CodeEntity> indexes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTANT).toList();
        assertEquals(1, indexes.size());
        assertEquals("idx_users_email", indexes.get(0).getName());

        // Index should DEPEND ON table
        List<RelationTriple> deps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("index:idx_users_email"))
                .toList();
        assertTrue(deps.size() >= 1, "Index should DEPEND ON table");
    }

    @Test
    void parseSqlCreateFunctionAndProcedure() {
        String sql = """
                CREATE OR REPLACE FUNCTION get_user_count() RETURNS INTEGER AS $$
                BEGIN
                    RETURN (SELECT COUNT(*) FROM users);
                END;
                $$ LANGUAGE plpgsql;

                CREATE PROCEDURE update_status(IN user_id INT) AS $$
                BEGIN
                    UPDATE users SET status = 'active' WHERE id = user_id;
                END;
                $$;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "funcs.sql", "default", "sql");

        List<CodeEntity> funcs = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION).toList();
        assertTrue(funcs.size() >= 2, "Should find function + procedure");

        List<String> funcNames = funcs.stream().map(CodeEntity::getName).toList();
        assertTrue(funcNames.contains("get_user_count"));
        assertTrue(funcNames.contains("update_status"));
    }

    // ── SQL: New features ──────────────────────────────────────────────

    @Test
    void parseSqlForeignKey() {
        String sql = """
                CREATE TABLE orders (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "fk.sql", "default", "sql");

        List<RelationTriple> fkDeps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("table:orders")
                        && r.targetFqn().contains("table:users"))
                .toList();
        assertTrue(fkDeps.size() >= 1, "Foreign key should create DEPENDS_ON from orders to users");
    }

    @Test
    void parseSqlInlineReferences() {
        String sql = """
                CREATE TABLE order_items (
                    id SERIAL PRIMARY KEY,
                    order_id INTEGER REFERENCES orders(id),
                    product_id INTEGER REFERENCES products(id)
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "refs.sql", "default", "sql");

        List<RelationTriple> deps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("table:order_items"))
                .toList();
        assertTrue(deps.size() >= 2, "Inline REFERENCES should create DEPENDS_ON for orders and products");
    }

    @Test
    void parseSqlCreateTrigger() {
        String sql = """
                CREATE TRIGGER trg_audit_insert
                AFTER INSERT ON users
                FOR EACH ROW EXECUTE FUNCTION audit_log();
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "triggers.sql", "default", "sql");

        List<CodeEntity> triggers = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        && e.getFullyQualifiedName().contains("trigger:"))
                .toList();
        assertEquals(1, triggers.size(), "Should find 1 trigger");
        assertEquals("trg_audit_insert", triggers.get(0).getName());

        // Trigger DEPENDS_ON its target table
        List<RelationTriple> deps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("trigger:trg_audit_insert")
                        && r.targetFqn().contains("table:users"))
                .toList();
        assertTrue(deps.size() >= 1, "Trigger should DEPEND ON users table");
    }

    @Test
    void parseSqlInsertAndUpdate() {
        String sql = """
                INSERT INTO audit_log (action, timestamp) VALUES ('login', NOW());
                UPDATE users SET last_login = NOW() WHERE id = 1;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "dml.sql", "default", "sql");

        List<RelationTriple> deps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON).toList();

        List<String> targets = deps.stream().map(RelationTriple::targetFqn).toList();
        assertTrue(targets.contains("audit_log"), "INSERT INTO should reference audit_log");
        assertTrue(targets.contains("users"), "UPDATE should reference users");
    }

    @Test
    void parseSqlCte() {
        String sql = """
                WITH active_users AS (
                    SELECT * FROM users WHERE active = true
                ),
                user_orders AS (
                    SELECT * FROM orders WHERE user_id IN (SELECT id FROM active_users)
                )
                SELECT * FROM user_orders;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "cte.sql", "default", "sql");

        List<CodeEntity> ctes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        && e.getFullyQualifiedName().contains("cte:"))
                .toList();
        assertTrue(ctes.size() >= 1, "Should find at least 1 CTE");
        assertTrue(ctes.stream().anyMatch(c -> c.getName().equals("active_users")));
    }

    @Test
    void parseSqlCommentSkipping() {
        String sql = """
                -- This is a comment, should not create entities
                /* This is also a comment
                   CREATE TABLE fake_table (id INT);
                */
                CREATE TABLE real_table (
                    id INTEGER PRIMARY KEY,
                    name VARCHAR(100) -- inline comment
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "comments.sql", "default", "sql");

        List<CodeEntity> tables = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertEquals(1, tables.size(), "Should find only real_table, not fake_table");
        assertEquals("real_table", tables.get(0).getName());
    }

    @Test
    void parseSqlBlockCommentSpanning() {
        String sql = """
                CREATE TABLE t1 (
                    id INT
                );
                /* start of block comment
                CREATE TABLE should_be_hidden (
                    x INT
                );
                end of block comment */
                CREATE TABLE t2 (
                    id INT
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "block.sql", "default", "sql");

        List<CodeEntity> tables = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        List<String> names = tables.stream().map(CodeEntity::getName).toList();
        assertEquals(2, tables.size(), "Should find t1 and t2, not should_be_hidden");
        assertTrue(names.contains("t1"));
        assertTrue(names.contains("t2"));
        assertFalse(names.contains("should_be_hidden"));
    }

    @Test
    void parseSqlAlterTableForeignKey() {
        String sql = """
                ALTER TABLE orders ADD CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id);
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "alter.sql", "default", "sql");

        List<RelationTriple> fkDeps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("table:orders")
                        && r.targetFqn().contains("table:users"))
                .toList();
        assertTrue(fkDeps.size() >= 1, "ALTER TABLE FK should create DEPENDS_ON");
    }

    @Test
    void parseSqlFromAndJoin() {
        String sql = """
                SELECT u.name, o.total
                FROM users u
                JOIN orders o ON u.id = o.user_id
                LEFT JOIN payments p ON o.id = p.order_id;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "query.sql", "default", "sql");

        List<RelationTriple> deps = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON).toList();
        List<String> targets = deps.stream().map(RelationTriple::targetFqn).toList();
        assertTrue(targets.contains("users"), "FROM should reference users");
        assertTrue(targets.contains("orders"), "JOIN should reference orders");
        assertTrue(targets.contains("payments"), "LEFT JOIN should reference payments");
    }

    @Test
    void parseSqlColumnTypeCoverage() {
        String sql = """
                CREATE TABLE all_types (
                    a BIGINT,
                    b SMALLINT,
                    c BOOLEAN,
                    d VARCHAR(255),
                    e TEXT,
                    f TIMESTAMP,
                    g UUID,
                    h SERIAL,
                    i JSONB,
                    j BYTEA,
                    k INET,
                    l MONEY,
                    m REAL,
                    n FLOAT
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "types.sql", "default", "sql");

        List<CodeEntity> columns = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertTrue(columns.size() >= 14, "Should find all 14 columns, found: " + columns.size());
    }

    @Test
    void parseSqlSkipsConstraintKeywords() {
        String sql = """
                CREATE TABLE constrained (
                    id INTEGER,
                    PRIMARY KEY (id),
                    UNIQUE (id),
                    CHECK (id > 0),
                    CONSTRAINT ck_positive CHECK (id > 0)
                );
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "constraints.sql", "default", "sql");

        List<CodeEntity> columns = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertEquals(1, columns.size(), "Should only find id column, not PRIMARY/UNIQUE/CHECK/CONSTRAINT keywords");
        assertEquals("id", columns.get(0).getName());
    }

    @Test
    void parseSqlFullSchema() {
        String sql = """
                CREATE TABLE users (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    email TEXT UNIQUE
                );

                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    user_id INTEGER,
                    total DECIMAL(10,2),
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );

                CREATE INDEX idx_orders_user ON orders (user_id);

                CREATE OR REPLACE FUNCTION notify_new_order() RETURNS TRIGGER AS $$
                BEGIN
                    PERFORM pg_notify('new_order', NEW.id::text);
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;

                CREATE TRIGGER trg_new_order
                AFTER INSERT ON orders
                FOR EACH ROW EXECUTE FUNCTION notify_new_order();

                CREATE VIEW order_summary AS
                    SELECT u.name, COUNT(o.id) as order_count
                    FROM users u
                    JOIN orders o ON u.id = o.user_id
                    GROUP BY u.name;
                """;
        String[] lines = sql.split("\n");

        ExtractionOutput output = parser.parse(lines, "full.sql", "default", "sql");

        // Tables
        List<CodeEntity> tables = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS
                        && e.getFullyQualifiedName().contains("table:"))
                .toList();
        assertEquals(2, tables.size(), "Should find users and orders tables");

        // Columns
        List<CodeEntity> columns = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertTrue(columns.size() >= 6, "Should find at least 6 columns across both tables");

        // Foreign key relation
        List<RelationTriple> fk = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.DEPENDS_ON
                        && r.sourceFqn().contains("table:orders")
                        && r.targetFqn().contains("table:users"))
                .toList();
        assertTrue(fk.size() >= 1, "orders DEPENDS_ON users via FK");

        // Index
        List<CodeEntity> indexes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTANT).toList();
        assertEquals(1, indexes.size());

        // Function
        List<CodeEntity> functions = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        && e.getFullyQualifiedName().contains("func:"))
                .toList();
        assertEquals(1, functions.size());

        // Trigger
        List<CodeEntity> triggers = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FUNCTION
                        && e.getFullyQualifiedName().contains("trigger:"))
                .toList();
        assertEquals(1, triggers.size());

        // View
        List<CodeEntity> views = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS
                        && e.getFullyQualifiedName().contains("view:"))
                .toList();
        assertEquals(1, views.size());
    }
}
