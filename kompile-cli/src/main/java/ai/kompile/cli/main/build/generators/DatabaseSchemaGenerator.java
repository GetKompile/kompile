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

package ai.kompile.cli.main.build.generators;

import ai.kompile.cli.main.build.config.BuildConfiguration;

import java.io.*;

/**
 * Generates SQL schema files and Java configuration classes for pgvector-based builds.
 * PostgresML stubs have been removed (deprecated).
 */
public class DatabaseSchemaGenerator {

    private final BuildConfiguration config;

    public DatabaseSchemaGenerator(BuildConfiguration config) {
        this.config = config;
    }

    /**
     * Generate all database-related files if pgvector is selected.
     */
    public void generate(File projectDir) throws IOException {
        if (!config.getModules().has("vectorstore-pgvector")) {
            return;
        }

        if (config.isEnableSchemaInit()) {
            generateSqlSchemaFiles(projectDir);
        }
        generateDatabaseConfiguration(projectDir);
        generateGlobalExceptionHandler(projectDir);
    }

    private void generateSqlSchemaFiles(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        // schema.sql
        File schemaFile = new File(resourcesDir, "schema.sql");
        try (FileWriter writer = new FileWriter(schemaFile)) {
            writer.write("-- Schema initialization for Kompile RAG application\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n\n");

            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS vector;\n");
            writer.write("        RAISE NOTICE 'Vector extension created/verified successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector extension: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create uuid-ossp extension: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("END $;\n\n");

            writer.write("CREATE TABLE IF NOT EXISTS vector_store (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    content TEXT,\n");
            writer.write("    metadata JSONB,\n");
            writer.write("    embedding vector(1536),\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
            writer.write("    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("CREATE TABLE IF NOT EXISTS collections (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    name VARCHAR(255) NOT NULL UNIQUE,\n");
            writer.write("    description TEXT,\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    IF NOT EXISTS (\n");
            writer.write("        SELECT 1 FROM information_schema.columns \n");
            writer.write("        WHERE table_name = 'vector_store' AND column_name = 'collection_id'\n");
            writer.write("    ) THEN\n");
            writer.write("        ALTER TABLE vector_store ADD COLUMN collection_id UUID REFERENCES collections(id) ON DELETE SET NULL;\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n\n");

            writer.write("DO $\nBEGIN\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS vector_store_embedding_idx \n");
            writer.write("            ON vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector similarity index: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS idx_vector_store_metadata \n");
            writer.write("            ON vector_store USING GIN (metadata jsonb_ops);\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create JSONB metadata index: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_created_at ON vector_store (created_at);\n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_collection_id ON vector_store (collection_id);\n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_collections_name ON collections (name);\n");
            writer.write("END $;\n\n");

            writer.write("DO $\nBEGIN\n");
            writer.write("    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vector_store') THEN\n");
            writer.write("        RAISE NOTICE 'Schema initialization completed successfully';\n");
            writer.write("    ELSE\n");
            writer.write("        RAISE EXCEPTION 'Schema initialization failed - vector_store table not found';\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n");
        }

        // data.sql
        File dataFile = new File(resourcesDir, "data.sql");
        try (FileWriter writer = new FileWriter(dataFile)) {
            writer.write("-- Initial data for Kompile RAG application\n\n");
            writer.write("INSERT INTO collections (name, description) \n");
            writer.write("VALUES ('default', 'Default document collection') \n");
            writer.write("ON CONFLICT (name) DO NOTHING;\n");
        }

        System.out.println("Generated SQL schema files: " + schemaFile.getAbsolutePath());
    }

    private void generateDatabaseConfiguration(File projectDir) throws IOException {
        String groupId = config.getInstanceGroupId();
        File javaDir = new File(projectDir, "src/main/java/" + groupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File configFile = new File(javaDir, "DatabaseSetup.java");
        try (FileWriter w = new FileWriter(configFile)) {
            String pkg = groupId + ".config";
            w.write("package " + pkg + ";\n\n");
            w.write("import org.springframework.beans.factory.annotation.Value;\n");
            w.write("import org.springframework.context.annotation.Bean;\n");
            w.write("import org.springframework.context.annotation.Configuration;\n");
            w.write("import lombok.extern.slf4j.Slf4j;\n");
            w.write("import javax.sql.DataSource;\n");
            w.write("import com.zaxxer.hikari.HikariDataSource;\n");
            w.write("import java.sql.*;\n");
            w.write("import java.util.regex.*;\n\n");

            w.write("@Configuration(proxyBeanMethods = false)\n");
            w.write("@Slf4j\n");
            w.write("public class DatabaseSetup {\n\n");
            w.write("    @Value(\"${spring.datasource.url}\")\n");
            w.write("    private String databaseUrl;\n");
            w.write("    @Value(\"${spring.datasource.username}\")\n");
            w.write("    private String username;\n");
            w.write("    @Value(\"${spring.datasource.password}\")\n");
            w.write("    private String password;\n\n");

            w.write("    @Bean\n");
            w.write("    public DataSource dataSource() {\n");
            w.write("        log.info(\"Setting up database connection...\");\n");
            w.write("        try {\n");
            w.write("            ensureDatabaseExists();\n");
            w.write("            HikariDataSource ds = new HikariDataSource();\n");
            w.write("            ds.setJdbcUrl(databaseUrl);\n");
            w.write("            ds.setUsername(username);\n");
            w.write("            ds.setPassword(password);\n");
            w.write("            ds.setDriverClassName(\"org.postgresql.Driver\");\n");
            w.write("            ds.setMaximumPoolSize(10);\n");
            w.write("            ds.setMinimumIdle(2);\n");
            w.write("            log.info(\"DataSource configured successfully\");\n");
            w.write("            return ds;\n");
            w.write("        } catch (Exception e) {\n");
            w.write("            log.error(\"Database setup failed\", e);\n");
            w.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            w.write("        }\n");
            w.write("    }\n\n");

            w.write("    private void ensureDatabaseExists() {\n");
            w.write("        try {\n");
            w.write("            String dbName = extractDatabaseName(databaseUrl);\n");
            w.write("            String serverUrl = getServerUrl(databaseUrl);\n");
            w.write("            log.info(\"Checking if database '{}' exists...\", dbName);\n");
            w.write("            try (Connection conn = DriverManager.getConnection(serverUrl + \"/postgres\", username, password)) {\n");
            w.write("                try (Statement stmt = conn.createStatement()) {\n");
            w.write("                    var rs = stmt.executeQuery(\"SELECT 1 FROM pg_database WHERE datname = '\" + dbName + \"'\");\n");
            w.write("                    if (!rs.next()) {\n");
            w.write("                        log.info(\"Creating database '{}'...\", dbName);\n");
            w.write("                        stmt.executeUpdate(\"CREATE DATABASE \\\"\" + dbName + \"\\\"\");\n");
            w.write("                        log.info(\"Database '{}' created\", dbName);\n");
            w.write("                    } else {\n");
            w.write("                        log.info(\"Database '{}' already exists\", dbName);\n");
            w.write("                    }\n");
            w.write("                }\n");
            w.write("            }\n");
            w.write("        } catch (SQLException e) {\n");
            w.write("            log.error(\"Failed to ensure database exists: {}\", e.getMessage());\n");
            w.write("            throw new RuntimeException(\"Database setup failed\", e);\n");
            w.write("        }\n");
            w.write("    }\n\n");

            w.write("    private String extractDatabaseName(String url) {\n");
            w.write("        Matcher m = Pattern.compile(\".*/([^?]+)\").matcher(url);\n");
            w.write("        if (m.find()) return m.group(1);\n");
            w.write("        throw new IllegalArgumentException(\"Could not extract database name from URL: \" + url);\n");
            w.write("    }\n\n");

            w.write("    private String getServerUrl(String url) {\n");
            w.write("        int idx = url.lastIndexOf('/');\n");
            w.write("        if (idx > 0) return url.substring(0, idx);\n");
            w.write("        throw new IllegalArgumentException(\"Invalid database URL: \" + url);\n");
            w.write("    }\n");

            w.write("}\n");
        }
        System.out.println("Generated DatabaseSetup: " + configFile.getAbsolutePath());
    }

    private void generateGlobalExceptionHandler(File projectDir) throws IOException {
        String groupId = config.getInstanceGroupId();
        File javaDir = new File(projectDir, "src/main/java/" + groupId.replace('.', '/') + "/config");
        if (!javaDir.exists() && !javaDir.mkdirs()) {
            throw new IOException("Could not create config directory: " + javaDir.getAbsolutePath());
        }

        File handlerFile = new File(javaDir, "GlobalExceptionHandler.java");
        try (FileWriter w = new FileWriter(handlerFile)) {
            String pkg = groupId + ".config";
            w.write("package " + pkg + ";\n\n");
            w.write("import org.springframework.http.HttpStatus;\n");
            w.write("import org.springframework.http.ResponseEntity;\n");
            w.write("import org.springframework.web.bind.annotation.ExceptionHandler;\n");
            w.write("import org.springframework.web.bind.annotation.RestControllerAdvice;\n");
            w.write("import lombok.extern.slf4j.Slf4j;\n");
            w.write("import java.util.Map;\n\n");

            w.write("@RestControllerAdvice\n");
            w.write("@Slf4j\n");
            w.write("public class GlobalExceptionHandler {\n\n");
            w.write("    @ExceptionHandler(Exception.class)\n");
            w.write("    public ResponseEntity<Map<String, String>> handleException(Exception e) {\n");
            w.write("        log.error(\"Unhandled exception\", e);\n");
            w.write("        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)\n");
            w.write("                .body(Map.of(\"error\", e.getMessage() != null ? e.getMessage() : \"Internal server error\"));\n");
            w.write("    }\n");
            w.write("}\n");
        }
        System.out.println("Generated GlobalExceptionHandler: " + handlerFile.getAbsolutePath());
    }
}
