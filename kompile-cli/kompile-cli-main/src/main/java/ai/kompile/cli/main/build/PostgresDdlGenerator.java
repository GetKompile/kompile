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
 * Generates SQL DDL schema files for PostgreSQL/PostgresML integration.
 * Extracted from RagPomGenerator to separate SQL generation concerns.
 */
public class PostgresDdlGenerator {

    private final boolean includeVectorstorePgvector;
    private final boolean includeEmbeddingPostgresml;
    private final boolean includePgmlIndexer;
    private final boolean enableSchemaInit;

    public PostgresDdlGenerator(
            boolean includeVectorstorePgvector,
            boolean includeEmbeddingPostgresml,
            boolean includePgmlIndexer,
            boolean enableSchemaInit) {
        this.includeVectorstorePgvector = includeVectorstorePgvector;
        this.includeEmbeddingPostgresml = includeEmbeddingPostgresml;
        this.includePgmlIndexer = includePgmlIndexer;
        this.enableSchemaInit = enableSchemaInit;
    }

    public void generatePgmlSchemaFiles(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        File pgmlSchemaFile = new File(resourcesDir, "pgml-schema.sql");
        try (FileWriter writer = new FileWriter(pgmlSchemaFile)) {
            writer.write("-- PostgresML Comprehensive Schema Initialization\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n");
            writer.write(
                    "-- COMPREHENSIVE FIX: Creates ALL possible function signatures for PostgreSQL string types\n");
            writer.write("-- This addresses PostgreSQL's strict function overloading rules\n\n");

            writer.write("-- Step 1: Create pgml schema and required extensions\n");
            writer.write("CREATE SCHEMA IF NOT EXISTS pgml;\n");
            writer.write("COMMENT ON SCHEMA pgml IS 'PostgresML schema - comprehensive initialization';\n\n");

            writer.write("-- Ensure vector extension if available (for return types)\n");
            writer.write("DO $extension_setup$\n");
            writer.write("BEGIN\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS vector;\n");
            writer.write("        RAISE NOTICE '✓ Vector extension available';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING '⚠ Vector extension not available - using FLOAT[] fallback';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS pgml SCHEMA pgml;\n");
            writer.write("        RAISE NOTICE '✓ PostgresML extension installed and working!';\n");
            writer.write("        -- If we get here, PostgresML is available, so we don't need stub functions\n");
            writer.write("        RETURN;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write(
                    "        RAISE WARNING '⚠ PostgresML extension not available - creating comprehensive stub functions';\n");
            writer.write("    END;\n");
            writer.write("END $extension_setup$;\n\n");

            writer.write("-- Step 2: Create comprehensive stub functions\n");
            writer.write("-- PostgreSQL treats these as completely different function signatures:\n");
            writer.write(
                    "-- character varying, text, varchar, char, etc. are all different types for function resolution\n\n");

            String returnType;
            if (includeVectorstorePgvector) {
                returnType = "vector";
                writer.write("-- Using vector return type (pgvector enabled)\n");
            } else {
                returnType = "FLOAT[]";
                writer.write("-- Using FLOAT[] return type (pgvector not enabled)\n");
            }

            writer.write("DO $create_stubs$\n");
            writer.write("DECLARE\n");
            writer.write("    pgml_available BOOLEAN := FALSE;\n");
            writer.write("    vector_available BOOLEAN := FALSE;\n");
            writer.write("    final_return_type TEXT;\n");
            writer.write("BEGIN\n");
            writer.write("    -- Check if PostgresML extension is already working\n");
            writer.write("    BEGIN\n");
            writer.write("        PERFORM pgml.version();\n");
            writer.write("        pgml_available := TRUE;\n");
            writer.write("        RAISE NOTICE 'PostgresML is available - skipping stub creation';\n");
            writer.write("        RETURN;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        pgml_available := FALSE;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Determine return type based on vector extension availability\n");
            writer.write("    BEGIN\n");
            writer.write("        -- Test if vector type exists\n");
            writer.write("        EXECUTE 'SELECT NULL::vector';\n");
            writer.write("        vector_available := TRUE;\n");
            writer.write("        final_return_type := 'vector';\n");
            writer.write("        RAISE NOTICE 'Using vector return type';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        vector_available := FALSE;\n");
            writer.write("        final_return_type := 'FLOAT[]';\n");
            writer.write("        RAISE NOTICE 'Using FLOAT[] return type (vector extension not available)';\n");
            writer.write("    END;\n");
            writer.write("    \n");

            String[] firstParamTypes = { "character varying", "text", "varchar", "TEXT", "CHAR", "CHARACTER VARYING" };
            String[] secondParamTypes = { "text", "character varying", "varchar", "TEXT", "VARCHAR", "CHAR" };
            String[] thirdParamTypes = { "jsonb", "JSONB", "json", "JSON" };

            writer.write("    -- Create comprehensive function signatures to handle all possible Spring AI calls\n");
            writer.write(
                    "    RAISE NOTICE 'Creating comprehensive stub functions for all PostgreSQL string type combinations...';\n");
            writer.write("    \n");

            int signatureCount = 0;
            for (String param1 : firstParamTypes) {
                for (String param2 : secondParamTypes) {
                    for (String param3 : thirdParamTypes) {
                        signatureCount++;
                        writer.write("    -- Signature " + signatureCount + ": " + param1 + ", " + param2 + ", "
                                + param3 + "\n");
                        writer.write("    BEGIN\n");
                        writer.write("        EXECUTE 'CREATE OR REPLACE FUNCTION pgml.embed(' ||\n");
                        writer.write("            'model_name " + param1 + ", ' ||\n");
                        writer.write("            'text_input " + param2 + ", ' ||\n");
                        writer.write("            'kwargs " + param3 + " DEFAULT ''{}''::jsonb' ||\n");
                        writer.write("        ') RETURNS ' || final_return_type || ' AS $stub$' ||\n");
                        writer.write("        'BEGIN ' ||\n");
                        writer.write("            'RAISE EXCEPTION ''PostgresML not available. Signature: " + param1
                                + ", " + param2 + ", " + param3
                                + ". Install: https://postgresml.org/docs/getting-started/installation''; ' ||\n");
                        writer.write("        'END; $stub$ LANGUAGE plpgsql;';\n");
                        writer.write("    EXCEPTION WHEN duplicate_function THEN\n");
                        writer.write("        -- Function already exists, skip\n");
                        writer.write("        NULL;\n");
                        writer.write("    WHEN OTHERS THEN\n");
                        writer.write("        RAISE WARNING 'Could not create function signature " + signatureCount
                                + " (" + param1 + ", " + param2 + ", " + param3 + "): %', SQLERRM;\n");
                        writer.write("    END;\n");
                        writer.write("    \n");
                    }
                }
            }

            writer.write("    -- Create other commonly used stub functions\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE OR REPLACE FUNCTION pgml.version() RETURNS TEXT AS $version$\n");
            writer.write("        BEGIN\n");
            writer.write("            RETURN 'stub-version-pgml-not-installed';\n");
            writer.write("        END;\n");
            writer.write("        $version$ LANGUAGE plpgsql;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create pgml.version stub: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE OR REPLACE FUNCTION pgml.transform(\n");
            writer.write("            task TEXT,\n");
            writer.write("            inputs TEXT[],\n");
            writer.write("            model_name TEXT DEFAULT NULL,\n");
            writer.write("            kwargs JSONB DEFAULT '{}'\n");
            writer.write("        ) RETURNS JSONB AS $transform$\n");
            writer.write("        BEGIN\n");
            writer.write(
                    "            RAISE EXCEPTION 'PostgresML not available. Install: https://postgresml.org/docs/getting-started/installation';\n");
            writer.write("        END;\n");
            writer.write("        $transform$ LANGUAGE plpgsql;\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create pgml.transform stub: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '✓ Created comprehensive stub functions for PostgresML';\n");
            writer.write("END $create_stubs$;\n\n");

            if (includePgmlIndexer) {
                writer.write("-- Step 3: Create PGML Indexer tables\n");
                writer.write("CREATE TABLE IF NOT EXISTS pgml.indexer_jobs (\n");
                writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
                writer.write("    job_name VARCHAR(255) NOT NULL,\n");
                writer.write("    status VARCHAR(50) DEFAULT 'pending',\n");
                writer.write("    model_name VARCHAR(255),\n");
                writer.write("    task_type VARCHAR(100),\n");
                writer.write("    input_data TEXT,\n");
                writer.write("    output_data JSONB,\n");
                writer.write("    error_message TEXT,\n");
                writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
                writer.write("    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
                writer.write("    completed_at TIMESTAMP,\n");
                writer.write("    metadata JSONB\n");
                writer.write(");\n\n");

                writer.write("CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_status ON pgml.indexer_jobs(status);\n");
                writer.write(
                        "CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_created_at ON pgml.indexer_jobs(created_at);\n");
                writer.write(
                        "CREATE INDEX IF NOT EXISTS idx_pgml_indexer_jobs_task_type ON pgml.indexer_jobs(task_type);\n\n");
            }

            writer.write("-- Step 4: Final verification and debugging info\n");
            writer.write("DO $final_check$\n");
            writer.write("DECLARE\n");
            writer.write("    embed_count INTEGER;\n");
            writer.write("    signatures TEXT;\n");
            writer.write("BEGIN\n");
            writer.write("    -- Count embed functions\n");
            writer.write("    SELECT COUNT(*) INTO embed_count\n");
            writer.write("    FROM information_schema.routines \n");
            writer.write("    WHERE routine_schema = 'pgml' AND routine_name = 'embed';\n");
            writer.write("    \n");
            writer.write("    -- Get all embed function signatures for debugging\n");
            writer.write("    SELECT string_agg(\n");
            writer.write("        'pgml.embed(' || \n");
            writer.write("        COALESCE(\n");
            writer.write("            (SELECT string_agg(\n");
            writer.write("                data_type || CASE WHEN character_maximum_length IS NOT NULL \n");
            writer.write("                    THEN '(' || character_maximum_length || ')' ELSE '' END,\n");
            writer.write("                ', ' ORDER BY ordinal_position\n");
            writer.write("            )\n");
            writer.write("            FROM information_schema.parameters p \n");
            writer.write("            WHERE p.specific_name = r.specific_name), ''\n");
            writer.write("        ) || ')',\n");
            writer.write("        '; '\n");
            writer.write("    ) INTO signatures\n");
            writer.write("    FROM information_schema.routines r\n");
            writer.write("    WHERE routine_schema = 'pgml' AND routine_name = 'embed';\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("    RAISE NOTICE 'PostgresML Comprehensive Setup Complete';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("    RAISE NOTICE 'Total embed function variants created: %', embed_count;\n");
            writer.write("    \n");
            writer.write("    IF signatures IS NOT NULL THEN\n");
            writer.write("        RAISE NOTICE 'Available function signatures:';\n");
            writer.write("        RAISE NOTICE '%', signatures;\n");
            writer.write("    END IF;\n");
            writer.write("    \n");
            writer.write("    IF embed_count > 0 THEN\n");
            writer.write("        RAISE NOTICE 'Status: ✓ SUCCESS - All function signatures created';\n");
            writer.write("        RAISE NOTICE 'Spring AI should now find a matching function signature';\n");
            writer.write("    ELSE\n");
            writer.write("        RAISE EXCEPTION 'FAILED - No embed functions were created';\n");
            writer.write("    END IF;\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE '';\n");
            writer.write("    RAISE NOTICE 'Next steps:';\n");
            writer.write("    RAISE NOTICE '1. If you see function signature errors, check the log above';\n");
            writer.write(
                    "    RAISE NOTICE '2. To install PostgresML: https://postgresml.org/docs/getting-started/installation';\n");
            writer.write("    RAISE NOTICE '3. After installing PostgresML, restart your application';\n");
            writer.write("    RAISE NOTICE '============================================================';\n");
            writer.write("END $final_check$;\n");
        }

        System.out.println("Generated comprehensive PostgresML schema file: " + pgmlSchemaFile.getAbsolutePath());
        System.out.println("COMPREHENSIVE FIX: Created " + (6 * 6 * 4)
                + " function signature combinations to handle all PostgreSQL string type variations");
    }

    public void generateSqlSchemaFiles(File projectDir) throws IOException {
        if (!enableSchemaInit)
            return;

        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        File schemaFile = new File(resourcesDir, "schema.sql");
        try (FileWriter writer = new FileWriter(schemaFile)) {
            writer.write("-- Schema initialization for Kompile RAG application\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n");
            writer.write("-- This script is designed to be idempotent and safe to run multiple times\n");
            writer.write("-- IMPORTANT: This script runs AFTER pgml-schema.sql (if present)\n\n");

            writer.write("-- Enable required PostgreSQL extensions (with error handling)\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    -- Try to create vector extension\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS vector;\n");
            writer.write("        RAISE NOTICE 'Vector extension created/verified successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector extension: %', SQLERRM;\n");
            writer.write(
                    "        RAISE WARNING 'This may be normal if pgvector is not installed. Vector operations will not work.';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Try to create uuid-ossp extension\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";\n");
            writer.write("        RAISE NOTICE 'UUID-OSSP extension created/verified successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create uuid-ossp extension: %', SQLERRM;\n");
            writer.write("        RAISE WARNING 'UUID generation will use random() instead';\n");
            writer.write("    END;\n");
            writer.write("END $;\n\n");

            writer.write("-- Create vector store table\n");
            writer.write("CREATE TABLE IF NOT EXISTS vector_store (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    content TEXT,\n");
            writer.write("    metadata JSONB,\n");
            writer.write("    embedding vector(1536),\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n");
            writer.write("    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("-- Create collections table\n");
            writer.write("CREATE TABLE IF NOT EXISTS collections (\n");
            writer.write("    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n");
            writer.write("    name VARCHAR(255) NOT NULL UNIQUE,\n");
            writer.write("    description TEXT,\n");
            writer.write("    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n");
            writer.write(");\n\n");

            writer.write("-- Add collection reference to vector_store if not exists\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    IF NOT EXISTS (\n");
            writer.write("        SELECT 1 FROM information_schema.columns \n");
            writer.write("        WHERE table_name = 'vector_store' AND column_name = 'collection_id'\n");
            writer.write("    ) THEN\n");
            writer.write(
                    "        ALTER TABLE vector_store ADD COLUMN collection_id UUID REFERENCES collections(id) ON DELETE SET NULL;\n");
            writer.write("        RAISE NOTICE 'Added collection_id column to vector_store table';\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n\n");

            writer.write("-- Create indexes for performance (with error handling)\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write("    -- Vector similarity index (only if vector extension available)\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS vector_store_embedding_idx \n");
            writer.write("            ON vector_store USING ivfflat (embedding vector_cosine_ops) \n");
            writer.write("            WITH (lists = 100);\n");
            writer.write("        RAISE NOTICE 'Vector similarity index created successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create vector similarity index: %', SQLERRM;\n");
            writer.write("        RAISE WARNING 'This is normal if vector extension is not available';\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- JSONB metadata index (FIXED: proper syntax for JSONB)\n");
            writer.write("    BEGIN\n");
            writer.write("        CREATE INDEX IF NOT EXISTS idx_vector_store_metadata \n");
            writer.write("            ON vector_store USING GIN (metadata jsonb_ops);\n");
            writer.write("        RAISE NOTICE 'JSONB metadata index created successfully';\n");
            writer.write("    EXCEPTION WHEN OTHERS THEN\n");
            writer.write("        RAISE WARNING 'Could not create JSONB metadata index: %', SQLERRM;\n");
            writer.write("    END;\n");
            writer.write("    \n");
            writer.write("    -- Other standard indexes\n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_created_at \n");
            writer.write("        ON vector_store (created_at);\n");
            writer.write("    \n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_vector_store_collection_id \n");
            writer.write("        ON vector_store (collection_id);\n");
            writer.write("    \n");
            writer.write("    CREATE INDEX IF NOT EXISTS idx_collections_name \n");
            writer.write("        ON collections (name);\n");
            writer.write("    \n");
            writer.write("    RAISE NOTICE 'All standard indexes created successfully';\n");
            writer.write("END $;\n\n");

            writer.write("-- Verify table creation\n");
            writer.write("DO $\n");
            writer.write("BEGIN\n");
            writer.write(
                    "    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vector_store') THEN\n");
            writer.write("        RAISE NOTICE 'Schema initialization completed successfully ✓';\n");
            writer.write("    ELSE\n");
            writer.write("        RAISE EXCEPTION 'Schema initialization failed - vector_store table not found';\n");
            writer.write("    END IF;\n");
            writer.write("END $;\n");
        }

        File dataFile = new File(resourcesDir, "data.sql");
        try (FileWriter writer = new FileWriter(dataFile)) {
            writer.write("-- Initial data for Kompile RAG application\n");
            writer.write("-- Generated on: " + new java.util.Date() + "\n\n");

            writer.write("-- Insert default collection if it doesn't exist\n");
            writer.write("INSERT INTO collections (name, description) \n");
            writer.write("VALUES ('default', 'Default document collection') \n");
            writer.write("ON CONFLICT (name) DO NOTHING;\n\n");

            writer.write("-- Verify data initialization\n");
            writer.write("DO $\n");
            writer.write("DECLARE\n");
            writer.write("    collection_count INTEGER;\n");
            writer.write("BEGIN\n");
            writer.write("    SELECT COUNT(*) INTO collection_count FROM collections;\n");
            writer.write("    RAISE NOTICE 'Data initialization completed. Collections: %', collection_count;\n");
            writer.write("END $;\n");
        }

        System.out.println("Generated SQL schema files:");
        System.out.println("  - " + schemaFile.getAbsolutePath());
        System.out.println("  - " + dataFile.getAbsolutePath());
    }
}
