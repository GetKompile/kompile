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

package ai.kompile.cli.main.build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Generates Java source files for a RAG application instance.
 * Extracted from RagPomGenerator to separate Java source generation concerns.
 */
public class ProjectSourceGenerator {

    private final String instanceArtifactId;
    private final String instanceGroupId;
    private final boolean includeEmbeddingPostgresml;
    private final boolean includePgmlIndexer;
    private final boolean includeVectorstorePgvector;

    public ProjectSourceGenerator(
            String instanceArtifactId,
            String instanceGroupId,
            boolean includeEmbeddingPostgresml,
            boolean includePgmlIndexer,
            boolean includeVectorstorePgvector) {
        this.instanceArtifactId = instanceArtifactId;
        this.instanceGroupId = instanceGroupId;
        this.includeEmbeddingPostgresml = includeEmbeddingPostgresml;
        this.includePgmlIndexer = includePgmlIndexer;
        this.includeVectorstorePgvector = includeVectorstorePgvector;
    }

    public void generateProviderConfigurationClass(File projectDir) throws IOException {
        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create java config directory: " + javaDir.getAbsolutePath());
        }

        File configFile = new File(javaDir, "ProviderConfiguration.java");

        try (FileWriter writer = new FileWriter(configFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.context.annotation.Configuration;\n");
            writer.write("import org.springframework.context.annotation.Bean;\n");
            writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.dao.DataAccessException;\n");
            writer.write("import org.springframework.jdbc.BadSqlGrammarException;\n");
            writer.write("import org.springframework.context.ApplicationListener;\n");
            writer.write("import org.springframework.boot.context.event.ApplicationReadyEvent;\n");
            writer.write("import org.springframework.stereotype.Component;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import javax.sql.DataSource;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n\n");

            writer.write("/**\n");
            writer.write(" * Generated provider configuration for " + instanceArtifactId + "\n");
            writer.write(" * Automatically debugs PostgresML errors without any manual intervention\n");
            writer.write(" */\n");
            writer.write("@Configuration(proxyBeanMethods = false)\n");
            writer.write("public class ProviderConfiguration {\n\n");

            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                writer.write("    /**\n");
                writer.write("     * Automatic PostgresML error detector - runs immediately on startup\n");
                writer.write("     * Tests the exact function that's failing and shows debug info\n");
                writer.write("     */\n");
                writer.write("    @Component\n");
                writer.write("    @Slf4j\n");
                writer.write(
                        "    public static class AutomaticPostgresMLDebugger implements ApplicationListener<ApplicationReadyEvent> {\n\n");

                writer.write("        private final DataSource dataSource;\n");
                writer.write("        private final JdbcTemplate jdbcTemplate;\n\n");

                writer.write(
                        "        public AutomaticPostgresMLDebugger(DataSource dataSource, JdbcTemplate jdbcTemplate) {\n");
                writer.write("            this.dataSource = dataSource;\n");
                writer.write("            this.jdbcTemplate = jdbcTemplate;\n");
                writer.write("        }\n\n");

                writer.write("        @Override\n");
                writer.write("        public void onApplicationEvent(ApplicationReadyEvent event) {\n");
                writer.write("            log.info(\"Application ready - testing PostgresML function...\");\n");
                writer.write("            \n");
                writer.write("            try {\n");
                writer.write("                jdbcTemplate.queryForObject(\n");
                writer.write(
                        "                    \"SELECT pgml.embed('startup-test'::character varying, 'test'::text, '{}'::jsonb)\", \n");
                writer.write("                    Object.class\n");
                writer.write("                );\n");
                writer.write(
                        "                log.info(\"✓ PostgresML function test PASSED - embeddings should work\");\n");
                writer.write("                \n");
                writer.write("            } catch (Exception e) {\n");
                writer.write(
                        "                log.error(\"✗ PostgresML function test FAILED - this will cause upload errors\", e);\n");
                writer.write("                \n");
                writer.write("                debugPostgresMLError(e);\n");
                writer.write("            }\n");
                writer.write("        }\n\n");

                writer.write("        private void debugPostgresMLError(Exception originalError) {\n");
                writer.write("            System.err.println(\"\\n\" + \"=\".repeat(100));\n");
                writer.write(
                        "            System.err.println(\"AUTOMATIC PostgresML DEBUG - Error detected on startup\");\n");
                writer.write("            System.err.println(\"Original error: \" + originalError.getMessage());\n");
                writer.write("            System.err.println(\"=\".repeat(100));\n");
                writer.write("            \n");
                writer.write("            try (Connection conn = dataSource.getConnection()) {\n");
                writer.write("                \n");
                writer.write("                System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
                writer.write("                try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                    ResultSet rs = stmt.executeQuery(\n");
                writer.write(
                        "                        \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
                writer.write("                    rs.next();\n");
                writer.write("                    \n");
                writer.write("                    if (rs.getInt(1) > 0) {\n");
                writer.write("                        System.err.println(\"   ✓ pgml schema EXISTS\");\n");
                writer.write("                    } else {\n");
                writer.write("                        System.err.println(\"   ✗ pgml schema MISSING!\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("                System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
                writer.write("                try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                    ResultSet rs = stmt.executeQuery(\n");
                writer.write("                        \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
                writer.write(
                        "                        \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
                writer.write("                    rs.next();\n");
                writer.write("                    \n");
                writer.write("                    int funcCount = rs.getInt(1);\n");
                writer.write("                    if (funcCount > 0) {\n");
                writer.write(
                        "                        System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
                writer.write("                        \n");
                writer.write("                        checkFunctionSignature(conn);\n");
                writer.write("                        \n");
                writer.write("                    } else {\n");
                writer.write("                        System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
                writer.write(
                        "                        System.err.println(\"   → This is why your uploads will fail\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (Exception debugError) {\n");
                writer.write(
                        "                System.err.println(\"Debug connection failed: \" + debugError.getMessage());\n");
                writer.write("            }\n");
                writer.write("            \n");
                writer.write("            System.err.println(\"\\n\" + \"-\".repeat(80));\n");
                writer.write("            System.err.println(\"IMMEDIATE FIX - Run this SQL in your database:\");\n");
                writer.write("            System.err.println(\"-\".repeat(80));\n");
                writer.write("            System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
                writer.write("            System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
                writer.write("            System.err.println(\"  model_name character varying,\");\n");
                writer.write("            System.err.println(\"  text_input text,\");\n");
                writer.write("            System.err.println(\"  kwargs jsonb DEFAULT '{}'\");\n");
                writer.write("            System.err.println(\") RETURNS FLOAT[] AS $$\");\n");
                writer.write("            System.err.println(\"BEGIN\");\n");
                writer.write("            System.err.println(\"  RAISE EXCEPTION 'PostgresML not installed';\");\n");
                writer.write("            System.err.println(\"END;\");\n");
                writer.write("            System.err.println(\"$$ LANGUAGE plpgsql;\");\n");
                writer.write("            System.err.println(\"-\".repeat(80));\n");
                writer.write("            System.err.println(\"=\".repeat(100));\n");
                writer.write("        }\n\n");

                writer.write("        private void checkFunctionSignature(Connection conn) {\n");
                writer.write("            try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                ResultSet rs = stmt.executeQuery(\n");
                writer.write(
                        "                    \"SELECT string_agg(p.data_type, ', ' ORDER BY p.ordinal_position) as params \" +\n");
                writer.write("                    \"FROM information_schema.routines r \" +\n");
                writer.write(
                        "                    \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
                writer.write(
                        "                    \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
                writer.write("                    \"GROUP BY r.specific_name\");\n");
                writer.write("                \n");
                writer.write("                boolean foundTarget = false;\n");
                writer.write("                System.err.println(\"   Available function signatures:\");\n");
                writer.write("                \n");
                writer.write("                while (rs.next()) {\n");
                writer.write("                    String params = rs.getString(\"params\");\n");
                writer.write("                    System.err.println(\"   - pgml.embed(\" + params + \")\");\n");
                writer.write("                    \n");
                writer.write("                    if (params != null && params.contains(\"character varying\") && \n");
                writer.write("                        params.contains(\"text\") && params.contains(\"jsonb\")) {\n");
                writer.write("                        foundTarget = true;\n");
                writer.write("                        System.err.println(\"     ✓ MATCHES Spring AI requirement\");\n");
                writer.write("                    }\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("                if (!foundTarget) {\n");
                writer.write(
                        "                    System.err.println(\"   ✗ MISSING required: (character varying, text, jsonb)\");\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (Exception e) {\n");
                writer.write(
                        "                System.err.println(\"   Function signature check failed: \" + e.getMessage());\n");
                writer.write("            }\n");
                writer.write("        }\n");
                writer.write("    }\n\n");
            }

            writer.write("}\n");
        }

        System.out.println("Generated ProviderConfiguration.java with automatic PostgresML debugging: "
                + configFile.getAbsolutePath());
    }

    public void writeProviderConfigurationClass(FileWriter writer) throws IOException {
        String packageName = instanceGroupId + ".config";

        writer.write("package " + packageName + ";\n\n");
        writer.write("import org.springframework.context.annotation.Configuration;\n");
        writer.write("import org.springframework.context.annotation.Bean;\n");
        writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;\n");
        writer.write("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n");
        writer.write("import org.springframework.beans.factory.annotation.Value;\n");
        writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
        writer.write("import org.springframework.ai.embedding.EmbeddingModel;\n");
        writer.write("import org.springframework.ai.vectorstore.pgvector.PgVectorStore;\n");

        writer.write("\n/**\n");
        writer.write(" * Generated provider configuration for " + instanceArtifactId + "\n");
        writer.write(" * \n");
        writer.write(" * This configuration only creates beans that are NOT created by the modules.\n");
        writer.write(" * Individual modules (kompile-embedding-*, kompile-vectorstore-*) create\n");
        writer.write(" * their own beans via their own configuration classes.\n");
        writer.write(" * \n");
        writer.write(" */\n");
        writer.write("@Configuration(proxyBeanMethods = false)\n");
        writer.write("public class ProviderConfiguration {\n\n");

        writer.write("    // No beans needed here - modules create their own beans\n");
        writer.write("    // This class exists in case you need custom cross-cutting configuration\n\n");

        writer.write("}\n");
    }

    public void generateDatabaseConfiguration(File projectDir) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File configFile = new File(javaDir, "DatabaseSetup.java");
        try (FileWriter writer = new FileWriter(configFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.beans.factory.annotation.Value;\n");
            writer.write("import org.springframework.context.annotation.Bean;\n");
            writer.write("import org.springframework.context.annotation.Configuration;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.boot.context.event.ApplicationReadyEvent;\n");
            writer.write("import org.springframework.context.event.EventListener;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import javax.sql.DataSource;\n");
            writer.write("import com.zaxxer.hikari.HikariDataSource;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.DriverManager;\n");
            writer.write("import java.sql.SQLException;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n");
            writer.write("import java.util.regex.Matcher;\n");
            writer.write("import java.util.regex.Pattern;\n\n");

            writer.write("@Configuration(proxyBeanMethods = false)\n");
            writer.write("@Slf4j\n");
            writer.write("public class DatabaseSetup {\n\n");

            writer.write("    @Value(\"${spring.datasource.url}\")\n");
            writer.write("    private String databaseUrl;\n");
            writer.write("    \n");
            writer.write("    @Value(\"${spring.datasource.username}\")\n");
            writer.write("    private String username;\n");
            writer.write("    \n");
            writer.write("    @Value(\"${spring.datasource.password}\")\n");
            writer.write("    private String password;\n\n");

            writer.write("    @Bean\n");
            writer.write("    public DataSource dataSource() {\n");
            writer.write("        log.info(\"Setting up database connection...\");\n");
            writer.write("        \n");
            writer.write("        try {\n");
            writer.write("            ensureDatabaseExists();\n");
            writer.write("            \n");
            writer.write("            HikariDataSource dataSource = new HikariDataSource();\n");
            writer.write("            dataSource.setJdbcUrl(databaseUrl);\n");
            writer.write("            dataSource.setUsername(username);\n");
            writer.write("            dataSource.setPassword(password);\n");
            writer.write("            dataSource.setDriverClassName(\"org.postgresql.Driver\");\n");
            writer.write("            dataSource.setMaximumPoolSize(10);\n");
            writer.write("            dataSource.setMinimumIdle(2);\n");
            writer.write("            \n");
            writer.write("            log.info(\"DataSource configured successfully\");\n");
            writer.write("            return dataSource;\n");
            writer.write("            \n");
            writer.write("        } catch (Exception e) {\n");
            writer.write("            log.error(\"Database setup failed\", e);\n");
            writer.write("            \n");
            writer.write("            if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("                debugPostgresMLIssue(e);\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void ensureDatabaseExists() {\n");
            writer.write("        try {\n");
            writer.write("            String dbName = extractDatabaseName(databaseUrl);\n");
            writer.write("            String serverUrl = getServerUrl(databaseUrl);\n");
            writer.write("            \n");
            writer.write("            log.info(\"Checking if database '{}' exists...\", dbName);\n");
            writer.write("            \n");
            writer.write(
                    "            try (Connection conn = DriverManager.getConnection(serverUrl + \"/postgres\", username, password)) {\n");
            writer.write("                try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                    var rs = stmt.executeQuery(\n");
            writer.write(
                    "                        \"SELECT 1 FROM pg_database WHERE datname = '\" + dbName + \"'\");\n");
            writer.write("                    \n");
            writer.write("                    if (!rs.next()) {\n");
            writer.write("                        log.info(\"Database '{}' does not exist. Creating...\", dbName);\n");
            writer.write("                        stmt.executeUpdate(\"CREATE DATABASE \\\"\" + dbName + \"\\\"\");\n");
            writer.write("                        log.info(\"Database '{}' created successfully\", dbName);\n");
            writer.write("                    } else {\n");
            writer.write("                        log.info(\"Database '{}' already exists\", dbName);\n");
            writer.write("                    }\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (SQLException e) {\n");
            writer.write("            log.error(\"Failed to ensure database exists: {}\", e.getMessage(), e);\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLIssue(e);\n");
            writer.write("            \n");
            writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void debugPostgresMLIssue(Exception originalError) {\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("        System.err.println(\"PostgresML ERROR DEBUG\");\n");
            writer.write("        System.err.println(\"Original Error: \" + originalError.getMessage());\n");
            writer.write("        System.err.println(\"=\".repeat(80));\n");
            writer.write("        \n");
            writer.write(
                    "        try (Connection conn = DriverManager.getConnection(databaseUrl, username, password)) {\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write(
                    "                    \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                int schemaCount = rs.getInt(1);\n");
            writer.write("                \n");
            writer.write("                if (schemaCount > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml schema EXISTS\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml schema MISSING!\");\n");
            writer.write(
                    "                    System.err.println(\"   → SOLUTION: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
            writer.write("                    \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                int funcCount = rs.getInt(1);\n");
            writer.write("                \n");
            writer.write("                if (funcCount > 0) {\n");
            writer.write(
                    "                    System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
            writer.write("                    System.err.println(\"   → This is likely the cause of your error\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n3. FUNCTION CALL TEST:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write(
                    "                System.err.println(\"   Testing: pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write(
                    "                    \"SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("                \n");
            writer.write("                if (rs.next()) {\n");
            writer.write(
                    "                    System.err.println(\"   ✓ Function call SUCCEEDED - PostgresML should work\");\n");
            writer.write("                }\n");
            writer.write("                \n");
            writer.write("            } catch (SQLException testError) {\n");
            writer.write(
                    "                System.err.println(\"   ✗ Function call FAILED: \" + testError.getMessage());\n");
            writer.write(
                    "                System.err.println(\"   → This is the EXACT error Spring AI encounters\");\n");
            writer.write("                \n");
            writer.write("                if (testError.getMessage().contains(\"does not exist\")) {\n");
            writer.write("                    System.err.println(\"\\n   IMMEDIATE FIX - Run this SQL:\");\n");
            writer.write("                    System.err.println(\"   CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("                    System.err.println(\"   CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
            writer.write("                    System.err.println(\"     model_name character varying,\");\n");
            writer.write("                    System.err.println(\"     text_input text,\");\n");
            writer.write("                    System.err.println(\"     kwargs jsonb DEFAULT '{}'\");\n");
            writer.write("                    System.err.println(\"   ) RETURNS FLOAT[] AS $$\");\n");
            writer.write("                    System.err.println(\"   BEGIN\");\n");
            writer.write(
                    "                    System.err.println(\"     RAISE EXCEPTION 'PostgresML not installed';\");\n");
            writer.write("                    System.err.println(\"   END;\");\n");
            writer.write("                    System.err.println(\"   $$ LANGUAGE plpgsql;\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (SQLException debugError) {\n");
            writer.write("            System.err.println(\"Debug failed: \" + debugError.getMessage());\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("    }\n\n");

            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                writer.write("    @EventListener(ApplicationReadyEvent.class)\n");
                writer.write("    public void testPostgresMLOnStartup() {\n");
                writer.write("        log.info(\"Testing PostgresML function on startup...\");\n");
                writer.write("        \n");
                writer.write(
                        "        try (Connection conn = DriverManager.getConnection(databaseUrl, username, password)) {\n");
                writer.write("            try (Statement stmt = conn.createStatement()) {\n");
                writer.write("                ResultSet rs = stmt.executeQuery(\n");
                writer.write(
                        "                    \"SELECT pgml.embed('startup-test'::character varying, 'test'::text, '{}'::jsonb)\");\n");
                writer.write("                \n");
                writer.write("                if (rs.next()) {\n");
                writer.write("                    log.info(\"✓ PostgresML function test PASSED on startup\");\n");
                writer.write("                }\n");
                writer.write("                \n");
                writer.write("            } catch (SQLException e) {\n");
                writer.write(
                        "                log.error(\"✗ PostgresML function test FAILED on startup: {}\", e.getMessage(), e);\n");
                writer.write("                \n");
                writer.write("                debugPostgresMLIssue(e);\n");
                writer.write("            }\n");
                writer.write("        } catch (SQLException e) {\n");
                writer.write("            log.error(\"Could not test PostgresML on startup\", e);\n");
                writer.write("        }\n");
                writer.write("    }\n\n");
            }

            writer.write("    private String extractDatabaseName(String url) {\n");
            writer.write("        Pattern pattern = Pattern.compile(\".*/([^?]+)\");\n");
            writer.write("        Matcher matcher = pattern.matcher(url);\n");
            writer.write("        if (matcher.find()) {\n");
            writer.write("            return matcher.group(1);\n");
            writer.write("        }\n");
            writer.write(
                    "        throw new IllegalArgumentException(\"Could not extract database name from URL: \" + url);\n");
            writer.write("    }\n\n");

            writer.write("    private String getServerUrl(String url) {\n");
            writer.write("        int lastSlash = url.lastIndexOf('/');\n");
            writer.write("        if (lastSlash > 0) {\n");
            writer.write("            return url.substring(0, lastSlash);\n");
            writer.write("        }\n");
            writer.write("        throw new IllegalArgumentException(\"Invalid database URL: \" + url);\n");
            writer.write("    }\n");

            writer.write("}\n");
        }

        System.out.println("Generated DatabaseSetup with inline PostgresML debugging: " + configFile.getAbsolutePath());
    }

    public void generateGlobalExceptionHandler(File projectDir) throws IOException {
        File javaDir = new File(projectDir, "src/main/java/" + instanceGroupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File handlerFile = new File(javaDir, "PostgresMLErrorCatcher.java");
        try (FileWriter writer = new FileWriter(handlerFile)) {
            String packageName = instanceGroupId + ".config";

            writer.write("package " + packageName + ";\n\n");
            writer.write("import org.springframework.beans.factory.annotation.Autowired;\n");
            writer.write("import org.springframework.web.bind.annotation.ControllerAdvice;\n");
            writer.write("import org.springframework.web.bind.annotation.ExceptionHandler;\n");
            writer.write("import org.springframework.dao.DataAccessException;\n");
            writer.write("import org.springframework.jdbc.BadSqlGrammarException;\n");
            writer.write("import org.springframework.jdbc.core.JdbcTemplate;\n");
            writer.write("import org.springframework.http.ResponseEntity;\n");
            writer.write("import lombok.extern.slf4j.Slf4j;\n");
            writer.write("import java.sql.SQLException;\n");
            writer.write("import java.sql.Connection;\n");
            writer.write("import java.sql.Statement;\n");
            writer.write("import java.sql.ResultSet;\n");
            writer.write("import javax.sql.DataSource;\n\n");

            writer.write("@ControllerAdvice\n");
            writer.write("@Slf4j\n");
            writer.write("public class PostgresMLErrorCatcher {\n\n");

            writer.write("    @Autowired(required = false)\n");
            writer.write("    private DataSource dataSource;\n\n");

            writer.write("    @Autowired(required = false)\n");
            writer.write("    private JdbcTemplate jdbcTemplate;\n\n");

            writer.write("    @ExceptionHandler(BadSqlGrammarException.class)\n");
            writer.write("    public ResponseEntity<String> handleBadSqlGrammar(BadSqlGrammarException e) {\n");
            writer.write("        log.error(\"BadSqlGrammarException caught\", e);\n");
            writer.write("        \n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml.embed\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"POSTGRESQL FUNCTION ERROR CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write(
                    "        return ResponseEntity.status(500).body(\"Database function error: \" + e.getMessage());\n");
            writer.write("    }\n\n");

            writer.write("    @ExceptionHandler(DataAccessException.class)\n");
            writer.write("    public ResponseEntity<String> handleDataAccess(DataAccessException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML DataAccessException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        return ResponseEntity.status(500).body(\"Database access error\");\n");
            writer.write("    }\n\n");

            writer.write("    @ExceptionHandler(SQLException.class)\n");
            writer.write("    public ResponseEntity<String> handleSQLException(SQLException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML SQLException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        return ResponseEntity.status(500).body(\"SQL error\");\n");
            writer.write("    }\n\n");

            writer.write("    @ExceptionHandler(RuntimeException.class)\n");
            writer.write("    public ResponseEntity<String> handleRuntimeException(RuntimeException e) {\n");
            writer.write("        if (e.getMessage() != null && e.getMessage().contains(\"pgml.embed\")) {\n");
            writer.write("            System.err.println(\"\\n\" + \"!\".repeat(100));\n");
            writer.write("            System.err.println(\"PostgresML RuntimeException CAUGHT!\");\n");
            writer.write("            System.err.println(\"Error: \" + e.getMessage());\n");
            writer.write("            System.err.println(\"!\".repeat(100));\n");
            writer.write("            \n");
            writer.write("            debugPostgresMLError();\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        throw e;\n");
            writer.write("    }\n\n");

            writer.write("    private void debugPostgresMLError() {\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("        System.err.println(\"IMMEDIATE PostgresML DEBUG\");\n");
            writer.write("        System.err.println(\"=\".repeat(80));\n");
            writer.write("        \n");
            writer.write("        if (dataSource == null) {\n");
            writer.write("            System.err.println(\"DataSource not available for debugging\");\n");
            writer.write("            printManualFix();\n");
            writer.write("            return;\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        try (Connection conn = dataSource.getConnection()) {\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n1. SCHEMA CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write(
                    "                    \"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                \n");
            writer.write("                if (rs.getInt(1) > 0) {\n");
            writer.write("                    System.err.println(\"   ✓ pgml schema EXISTS\");\n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml schema MISSING\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n2. FUNCTION CHECK:\");\n");
            writer.write("            try (Statement stmt = conn.createStatement()) {\n");
            writer.write("                ResultSet rs = stmt.executeQuery(\n");
            writer.write("                    \"SELECT COUNT(*) FROM information_schema.routines \" +\n");
            writer.write("                    \"WHERE routine_schema = 'pgml' AND routine_name = 'embed'\");\n");
            writer.write("                rs.next();\n");
            writer.write("                \n");
            writer.write("                int funcCount = rs.getInt(1);\n");
            writer.write("                if (funcCount > 0) {\n");
            writer.write(
                    "                    System.err.println(\"   ✓ pgml.embed function EXISTS (\" + funcCount + \" variants)\");\n");
            writer.write("                    \n");
            writer.write("                    checkFunctionSignatures(conn);\n");
            writer.write("                    \n");
            writer.write("                } else {\n");
            writer.write("                    System.err.println(\"   ✗ pgml.embed function MISSING!\");\n");
            writer.write("                    System.err.println(\"   → This is the ROOT CAUSE of your error\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"\\n3. EXACT FUNCTION TEST:\");\n");
            writer.write("            testExactFunctionCall(conn);\n");
            writer.write("            \n");
            writer.write("        } catch (Exception debugError) {\n");
            writer.write("            System.err.println(\"Debug connection failed: \" + debugError.getMessage());\n");
            writer.write("        }\n");
            writer.write("        \n");
            writer.write("        printManualFix();\n");
            writer.write("        System.err.println(\"\\n\" + \"=\".repeat(80));\n");
            writer.write("    }\n\n");

            writer.write("    private void checkFunctionSignatures(Connection conn) {\n");
            writer.write("        try (Statement stmt = conn.createStatement()) {\n");
            writer.write("            ResultSet rs = stmt.executeQuery(\n");
            writer.write("                \"SELECT string_agg( \" +\n");
            writer.write("                \"  p.data_type, ', ' ORDER BY p.ordinal_position \" +\n");
            writer.write("                \") as parameter_types \" +\n");
            writer.write("                \"FROM information_schema.routines r \" +\n");
            writer.write(
                    "                \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
            writer.write("                \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
            writer.write("                \"GROUP BY r.specific_name\");\n");
            writer.write("            \n");
            writer.write("            System.err.println(\"   Available function signatures:\");\n");
            writer.write("            boolean foundTargetSignature = false;\n");
            writer.write("            \n");
            writer.write("            while (rs.next()) {\n");
            writer.write("                String signature = rs.getString(\"parameter_types\");\n");
            writer.write("                System.err.println(\"   - pgml.embed(\" + signature + \")\");\n");
            writer.write("                \n");
            writer.write("                if (signature != null && \n");
            writer.write("                    signature.contains(\"character varying\") && \n");
            writer.write("                    signature.contains(\"text\") &&\n");
            writer.write("                    signature.contains(\"jsonb\")) {\n");
            writer.write("                    foundTargetSignature = true;\n");
            writer.write("                    System.err.println(\"     ✓ MATCHES Spring AI requirement!\");\n");
            writer.write("                }\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("            if (!foundTargetSignature) {\n");
            writer.write(
                    "                System.err.println(\"   ✗ MISSING required signature: (character varying, text, jsonb)\");\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (Exception e) {\n");
            writer.write(
                    "            System.err.println(\"   Function signature check failed: \" + e.getMessage());\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void testExactFunctionCall(Connection conn) {\n");
            writer.write("        try (Statement stmt = conn.createStatement()) {\n");
            writer.write(
                    "            System.err.println(\"   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("            \n");
            writer.write("            ResultSet rs = stmt.executeQuery(\n");
            writer.write(
                    "                \"SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
            writer.write("            \n");
            writer.write("            if (rs.next()) {\n");
            writer.write("                System.err.println(\"   ✓ Function call SUCCEEDED!\");\n");
            writer.write(
                    "                System.err.println(\"   → The function exists, but Spring AI might have a different issue\");\n");
            writer.write("            }\n");
            writer.write("            \n");
            writer.write("        } catch (Exception testError) {\n");
            writer.write("            System.err.println(\"   ✗ Function call FAILED: \" + testError.getMessage());\n");
            writer.write("            System.err.println(\"   → This confirms the function signature is missing\");\n");
            writer.write("        }\n");
            writer.write("    }\n\n");

            writer.write("    private void printManualFix() {\n");
            writer.write("        System.err.println(\"\\n\" + \"-\".repeat(80));\n");
            writer.write("        System.err.println(\"IMMEDIATE FIX - Run this SQL in your database:\");\n");
            writer.write("        System.err.println(\"-\".repeat(80));\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
            writer.write("        System.err.println(\"  model_name character varying,\");\n");
            writer.write("        System.err.println(\"  text_input text,\");\n");
            writer.write("        System.err.println(\"  kwargs jsonb DEFAULT '{}'\");\n");
            writer.write("        System.err.println(\") RETURNS FLOAT[] AS $$\");\n");
            writer.write("        System.err.println(\"BEGIN\");\n");
            writer.write(
                    "        System.err.println(\"  RAISE EXCEPTION 'PostgresML not installed. Visit https://postgresml.org';\");\n");
            writer.write("        System.err.println(\"END;\");\n");
            writer.write("        System.err.println(\"$$ LANGUAGE plpgsql;\");\n");
            writer.write("        System.err.println(\"\");\n");
            writer.write("        System.err.println(\"After running this SQL, restart your application.\");\n");
            writer.write("        System.err.println(\"-\".repeat(80));\n");
            writer.write("    }\n");

            writer.write("}\n");
        }

        System.out.println("Generated PostgresML error catcher: " + handlerFile.getAbsolutePath());
    }

    public void writeDataSourceMethod(FileWriter writer) throws IOException {
        writer.write("    @Bean\n");
        writer.write("    public DataSource dataSource() {\n");
        writer.write("        log.info(\"Setting up database connection with PostgresML error detection...\");\n");
        writer.write("        \n");
        writer.write("        try {\n");
        writer.write("            ensureDatabaseExists();\n");
        writer.write("            \n");
        writer.write("            HikariDataSource dataSource = new HikariDataSource();\n");
        writer.write("            dataSource.setJdbcUrl(databaseUrl);\n");
        writer.write("            dataSource.setUsername(username);\n");
        writer.write("            dataSource.setPassword(password);\n");
        writer.write("            dataSource.setDriverClassName(\"org.postgresql.Driver\");\n");
        writer.write("            dataSource.setMaximumPoolSize(10);\n");
        writer.write("            dataSource.setMinimumIdle(2);\n");
        writer.write("            \n");
        writer.write("            log.info(\"DataSource configured successfully\");\n");
        writer.write("            return dataSource;\n");
        writer.write("            \n");
        writer.write("        } catch (Exception e) {\n");
        writer.write("            log.error(\"Failed to create DataSource\", e);\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    public void writeDatabaseCreationMethods(FileWriter writer) throws IOException {
        writer.write("    private void ensureDatabaseExists() {\n");
        writer.write("        try {\n");
        writer.write("            String dbName = extractDatabaseName(databaseUrl);\n");
        writer.write("            String serverUrl = getServerUrl(databaseUrl);\n");
        writer.write("            \n");
        writer.write("            log.info(\"Checking if database '{}' exists...\", dbName);\n");
        writer.write("            \n");
        writer.write(
                "            try (Connection conn = DriverManager.getConnection(serverUrl + \"/postgres\", username, password)) {\n");
        writer.write("                try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                    var rs = stmt.executeQuery(\n");
        writer.write("                        \"SELECT 1 FROM pg_database WHERE datname = '\" + dbName + \"'\");\n");
        writer.write("                    \n");
        writer.write("                    if (!rs.next()) {\n");
        writer.write("                        log.info(\"Database '{}' does not exist. Creating...\", dbName);\n");
        writer.write("                        stmt.executeUpdate(\"CREATE DATABASE \\\"\" + dbName + \"\\\"\");\n");
        writer.write("                        log.info(\"Database '{}' created successfully ✓\", dbName);\n");
        writer.write("                    } else {\n");
        writer.write("                        log.info(\"Database '{}' already exists ✓\", dbName);\n");
        writer.write("                    }\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write("            log.error(\"Failed to ensure database exists: {}\", e.getMessage(), e);\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private String extractDatabaseName(String url) {\n");
        writer.write("        Pattern pattern = Pattern.compile(\".*/([^?]+)\");\n");
        writer.write("        Matcher matcher = pattern.matcher(url);\n");
        writer.write("        if (matcher.find()) {\n");
        writer.write("            return matcher.group(1);\n");
        writer.write("        }\n");
        writer.write(
                "        throw new IllegalArgumentException(\"Could not extract database name from URL: \" + url);\n");
        writer.write("    }\n\n");

        writer.write("    private String getServerUrl(String url) {\n");
        writer.write("        int lastSlash = url.lastIndexOf('/');\n");
        writer.write("        if (lastSlash > 0) {\n");
        writer.write("            return url.substring(0, lastSlash);\n");
        writer.write("        }\n");
        writer.write("        throw new IllegalArgumentException(\"Invalid database URL: \" + url);\n");
        writer.write("    }\n\n");
    }

    public void writeCompleteDebugMethods(FileWriter writer) throws IOException {
        writer.write("    public void debugPostgresMLIssues() {\n");
        writer.write("        System.out.println(\"\\n\" + \"=\".repeat(80));\n");
        writer.write("        System.out.println(\"PostgresML SCHEMA DIAGNOSTICS\");\n");
        writer.write("        System.out.println(\"=\".repeat(80));\n");
        writer.write("        \n");
        writer.write("        try (Connection conn = dataSource().getConnection()) {\n");
        writer.write("            \n");
        writer.write("            System.out.println(\"\\n1. SCHEMA CHECK:\");\n");
        writer.write("            try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                ResultSet rs = stmt.executeQuery(\n");
        writer.write(
                "                    \"SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'pgml'\"\n");
        writer.write("                );\n");
        writer.write("                \n");
        writer.write("                if (rs.next()) {\n");
        writer.write("                    System.out.println(\"   ✓ pgml schema EXISTS\");\n");
        writer.write("                } else {\n");
        writer.write(
                "                    System.out.println(\"   ✗ pgml schema MISSING - this could be the problem!\");\n");
        writer.write("                    System.out.println(\"   → Run: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            System.out.println(\"\\n2. EXTENSION CHECK:\");\n");
        writer.write("            try (Statement stmt = conn.createStatement()) {\n");
        writer.write("                ResultSet rs = stmt.executeQuery(\n");
        writer.write(
                "                    \"SELECT extname, extversion FROM pg_extension WHERE extname IN ('pgml', 'vector')\"\n");
        writer.write("                );\n");
        writer.write("                \n");
        writer.write("                boolean pgmlFound = false, vectorFound = false;\n");
        writer.write("                while (rs.next()) {\n");
        writer.write("                    String name = rs.getString(\"extname\");\n");
        writer.write("                    String version = rs.getString(\"extversion\");\n");
        writer.write(
                "                    System.out.println(\"   ✓ \" + name + \" extension v\" + version + \" installed\");\n");
        writer.write("                    \n");
        writer.write("                    if (\"pgml\".equals(name)) pgmlFound = true;\n");
        writer.write("                    if (\"vector\".equals(name)) vectorFound = true;\n");
        writer.write("                }\n");
        writer.write("                \n");
        writer.write("                if (!pgmlFound) {\n");
        writer.write("                    System.out.println(\"   ✗ pgml extension NOT installed\");\n");
        writer.write(
                "                    System.out.println(\"   → Install PostgresML: https://postgresml.org/docs/getting-started/installation\");\n");
        writer.write("                }\n");
        writer.write("                if (!vectorFound) {\n");
        writer.write("                    System.out.println(\"   ✗ vector extension NOT installed\");\n");
        writer.write(
                "                    System.out.println(\"   → Install pgvector: https://github.com/pgvector/pgvector\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            checkPgmlFunctions(conn);\n");
        writer.write("            \n");
        writer.write("            testPgmlFunctionCall(conn);\n");
        writer.write("            \n");
        writer.write("            provideQuickFix();\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write(
                "            System.err.println(\"✗ Database connection failed during diagnostics: \" + e.getMessage());\n");
        writer.write("        }\n");
        writer.write("        \n");
        writer.write("        System.out.println(\"\\n\" + \"=\".repeat(80));\n");
        writer.write("        System.out.println(\"PostgresML DIAGNOSTICS COMPLETE\");\n");
        writer.write("        System.out.println(\"=\".repeat(80));\n");
        writer.write("    }\n\n");

        writer.write("    private void checkPgmlFunctions(Connection conn) throws SQLException {\n");
        writer.write("        System.out.println(\"\\n3. FUNCTION CHECK (CRITICAL):\");\n");
        writer.write("        try (Statement stmt = conn.createStatement()) {\n");
        writer.write("            ResultSet rs = stmt.executeQuery(\n");
        writer.write("                \"SELECT \" +\n");
        writer.write("                \"  routine_name, \" +\n");
        writer.write("                \"  string_agg( \" +\n");
        writer.write("                \"    p.data_type || \" +\n");
        writer.write("                \"    CASE WHEN p.character_maximum_length IS NOT NULL \" +\n");
        writer.write("                \"      THEN '(' || p.character_maximum_length || ')' \" +\n");
        writer.write("                \"      ELSE '' \" +\n");
        writer.write("                \"    END, \" +\n");
        writer.write("                \"    ', ' ORDER BY p.ordinal_position \" +\n");
        writer.write("                \"  ) as parameter_types \" +\n");
        writer.write("                \"FROM information_schema.routines r \" +\n");
        writer.write(
                "                \"LEFT JOIN information_schema.parameters p ON r.specific_name = p.specific_name \" +\n");
        writer.write("                \"WHERE r.routine_schema = 'pgml' AND r.routine_name = 'embed' \" +\n");
        writer.write("                \"GROUP BY r.routine_name, r.specific_name\"\n");
        writer.write("            );\n");
        writer.write("            \n");
        writer.write("            boolean foundTargetSignature = false;\n");
        writer.write("            int embedFunctionCount = 0;\n");
        writer.write("            \n");
        writer.write("            System.out.println(\"   Found pgml.embed function signatures:\");\n");
        writer.write("            while (rs.next()) {\n");
        writer.write("                embedFunctionCount++;\n");
        writer.write("                String signature = rs.getString(\"parameter_types\");\n");
        writer.write(
                "                System.out.println(\"   - pgml.embed(\" + (signature != null ? signature : \"no params\") + \")\");\n");
        writer.write("                \n");
        writer.write("                if (signature != null && \n");
        writer.write("                    signature.contains(\"character varying\") && \n");
        writer.write("                    signature.contains(\"text\") && \n");
        writer.write("                    signature.contains(\"jsonb\")) {\n");
        writer.write("                    foundTargetSignature = true;\n");
        writer.write(
                "                    System.out.println(\"     ✓ THIS MATCHES Spring AI's expected signature!\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            if (embedFunctionCount == 0) {\n");
        writer.write("                System.out.println(\"   ✗ NO pgml.embed functions found!\");\n");
        writer.write(
                "                System.out.println(\"   → This is why you're getting 'function does not exist' error\");\n");
        writer.write(
                "                System.out.println(\"   → Solution: Run the pgml-schema.sql script to create stub functions\");\n");
        writer.write("            } else if (!foundTargetSignature) {\n");
        writer.write(
                "                System.out.println(\"   ✗ Missing required signature: pgml.embed(character varying, text, jsonb)\");\n");
        writer.write("                System.out.println(\"   → Spring AI needs this EXACT signature\");\n");
        writer.write(
                "                System.out.println(\"   → Solution: Create this specific function signature\");\n");
        writer.write("            } else {\n");
        writer.write(
                "                System.out.println(\"   ✓ Required function signature EXISTS - this should work!\");\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private void testPgmlFunctionCall(Connection conn) throws SQLException {\n");
        writer.write("        System.out.println(\"\\n4. FUNCTION CALL TEST:\");\n");
        writer.write("        try (Statement stmt = conn.createStatement()) {\n");
        writer.write(
                "            System.out.println(\"   Testing: SELECT pgml.embed('test'::character varying, 'text'::text, '{}'::jsonb)\");\n");
        writer.write("            \n");
        writer.write("            ResultSet rs = stmt.executeQuery(\n");
        writer.write(
                "                \"SELECT pgml.embed('test-model'::character varying, 'test text'::text, '{}'::jsonb)\"\n");
        writer.write("            );\n");
        writer.write("            \n");
        writer.write("            if (rs.next()) {\n");
        writer.write("                System.out.println(\"   ✓ Function call SUCCESS!\");\n");
        writer.write(
                "                System.out.println(\"   → pgml.embed function is working (even if it's a stub)\");\n");
        writer.write("                System.out.println(\"   → Spring AI should be able to call this function\");\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("        } catch (SQLException e) {\n");
        writer.write("            System.out.println(\"   ✗ Function call FAILED: \" + e.getMessage());\n");
        writer.write("            System.out.println(\"   → This is the EXACT error Spring AI encounters\");\n");
        writer.write("            \n");
        writer.write("            if (e.getMessage().contains(\"does not exist\")) {\n");
        writer.write("                System.out.println(\"   → SOLUTION: Create the missing function signature\");\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");

        writer.write("    private void provideQuickFix() {\n");
        writer.write("        System.out.println(\"\\n5. QUICK FIX:\");\n");
        writer.write(
                "        System.out.println(\"   If the function is missing, run this SQL to create a stub:\");\n");
        writer.write("        System.out.println(\"   \");\n");
        writer.write("        System.out.println(\"   CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("        System.out.println(\"   CREATE OR REPLACE FUNCTION pgml.embed(\");\n");
        writer.write("        System.out.println(\"     model_name character varying,\");\n");
        writer.write("        System.out.println(\"     text_input text,\");\n");
        writer.write("        System.out.println(\"     kwargs jsonb DEFAULT '{}'\");\n");
        writer.write("        System.out.println(\"   ) RETURNS FLOAT[] AS $$\");\n");
        writer.write("        System.out.println(\"   BEGIN\");\n");
        writer.write(
                "        System.out.println(\"     RAISE EXCEPTION 'PostgresML extension not available. Install from https://postgresml.org';\");\n");
        writer.write("        System.out.println(\"   END;\");\n");
        writer.write("        System.out.println(\"   $$ LANGUAGE plpgsql;\");\n");
        writer.write("    }\n\n");
    }

    public void writeEnhancedErrorHandling(FileWriter writer) throws IOException {
        writer.write("    public void handlePostgresMLError(Exception originalException) {\n");
        writer.write("        if (originalException.getMessage() != null && \n");
        writer.write("            originalException.getMessage().contains(\"pgml.embed\") && \n");
        writer.write("            originalException.getMessage().contains(\"does not exist\")) {\n");
        writer.write("            \n");
        writer.write("            log.error(\"PostgresML function signature error detected!\", originalException);\n");
        writer.write("            \n");
        writer.write("            System.err.println(\"\\n\" + \"!\".repeat(80));\n");
        writer.write("            System.err.println(\"PostgresML ERROR DETECTED!\");\n");
        writer.write("            System.err.println(\"Original error: \" + originalException.getMessage());\n");
        writer.write("            System.err.println(\"!\".repeat(80));\n");
        writer.write("            \n");
        writer.write("            try {\n");
        writer.write("                debugPostgresMLIssues();\n");
        writer.write("                \n");
        writer.write("                System.err.println(\"\\nSPECIFIC SOLUTION FOR YOUR ERROR:\");\n");
        writer.write(
                "                System.err.println(\"1. The pgml.embed function with the required signature is missing\");\n");
        writer.write(
                "                System.err.println(\"2. Spring AI needs: pgml.embed(character varying, text, jsonb)\");\n");
        writer.write(
                "                System.err.println(\"3. Run the SQL commands shown in the 'QUICK FIX' section above\");\n");
        writer.write("                System.err.println(\"4. Restart your application\");\n");
        writer.write("                \n");
        writer.write("            } catch (Exception debugException) {\n");
        writer.write("                log.error(\"Failed to run debug diagnostics\", debugException);\n");
        writer.write("                \n");
        writer.write("                System.err.println(\"\\nFALLBACK SOLUTION:\");\n");
        writer.write("                System.err.println(\"Run this SQL in your database:\");\n");
        writer.write("                System.err.println(\"CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write(
                "                System.err.println(\"CREATE OR REPLACE FUNCTION pgml.embed(model_name character varying, text_input text, kwargs jsonb DEFAULT '{}') RETURNS FLOAT[] AS $$ BEGIN RAISE EXCEPTION 'PostgresML not installed'; END; $$ LANGUAGE plpgsql;\");\n");
        writer.write("            }\n");
        writer.write("            \n");
        writer.write("            System.err.println(\"\\n\" + \"!\".repeat(80));\n");
        writer.write("            \n");
        writer.write("        } else {\n");
        writer.write("            log.error(\"Database error occurred\", originalException);\n");
        writer.write("            \n");
        writer.write("            if (originalException.getMessage() != null) {\n");
        writer.write("                String errorMsg = originalException.getMessage().toLowerCase();\n");
        writer.write("                \n");
        writer.write("                if (errorMsg.contains(\"schema\") && errorMsg.contains(\"pgml\")) {\n");
        writer.write("                    System.err.println(\"\\nSCHEMA ISSUE DETECTED:\");\n");
        writer.write(
                "                    System.err.println(\"1. Create the pgml schema: CREATE SCHEMA IF NOT EXISTS pgml;\");\n");
        writer.write("                    System.err.println(\"2. Then create the required functions\");\n");
        writer.write("                }\n");
        writer.write("                \n");
        writer.write("                if (errorMsg.contains(\"extension\") && errorMsg.contains(\"pgml\")) {\n");
        writer.write("                    System.err.println(\"\\nEXTENSION ISSUE DETECTED:\");\n");
        writer.write(
                "                    System.err.println(\"1. Install PostgresML: https://postgresml.org/docs/getting-started/installation\");\n");
        writer.write("                    System.err.println(\"2. Restart PostgreSQL server\");\n");
        writer.write("                    System.err.println(\"3. Restart your application\");\n");
        writer.write("                }\n");
        writer.write("            }\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    public void writeStartupDiagnostics(FileWriter writer, boolean includePostgresmlModules) throws IOException {
        writer.write("    @EventListener(ApplicationReadyEvent.class)\n");
        writer.write("    public void runPostgresMLDiagnosticsOnStartup() {\n");
        writer.write("        try {\n");
        if (includePostgresmlModules) {
            writer.write("            log.info(\"PostgresML modules detected - running schema diagnostics...\");\n");
            writer.write("            debugPostgresMLIssues();\n");
        } else {
            writer.write("            log.info(\"PostgresML modules not enabled - skipping diagnostics\");\n");
        }
        writer.write("        } catch (Exception e) {\n");
        writer.write("            log.error(\"Failed to run PostgresML diagnostics on startup\", e);\n");
        writer.write("            handlePostgresMLError(e);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }
}
