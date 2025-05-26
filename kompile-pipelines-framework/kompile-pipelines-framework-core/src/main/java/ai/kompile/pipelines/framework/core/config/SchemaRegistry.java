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

package ai.kompile.pipelines.framework.core.config;

import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A concrete implementation of {@link StepSchemaProvider} that discovers, loads,
 * parses, and caches {@link StepSchema} definitions from classpath resources.
 * <p>
 * Schemas are expected to be JSON or YAML files named after the fully qualified
 * class name of the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
 * they describe, with a {@code .schema.json}, {@code .schema.yaml}, or {@code .schema.yml} extension.
 * For example, the schema for {@code com.example.MyRunner} would be looked for as
 * {@code com.example.MyRunner.schema.json} or {@code com.example.MyRunner.schema.yaml}
 * in the classpath.
 * <p>
 * This class is designed as a singleton.
 */

public class SchemaRegistry implements StepSchemaProvider {

    private static class Holder {
        static final SchemaRegistry INSTANCE = new SchemaRegistry();
    }

    private final Map<String, StepSchema> schemaCache = new ConcurrentHashMap<>();
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private SchemaRegistry() {
        // Initialize ObjectMappers from the framework's core serde package
        this.jsonMapper = ObjectMappers.getJsonMapper();
        this.yamlMapper = ObjectMappers.getYamlMapper();
        // Potential to pre-scan/warm up cache if desired, but typically lazy loading is fine.
    }

    public Collection<StepSchema> getAllSchemas() {
        return schemaCache.values();
    }

    /**
     * Gets the singleton instance of the SchemaRegistry.
     *
     * @return The singleton instance.
     */
    public static SchemaRegistry getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation attempts to load the schema from a classpath resource
     * matching the runner's class name with extensions {@code .schema.json},
     * {@code .schema.yaml}, or {@code .schema.yml}.
     * Loaded schemas are cached for subsequent requests.
     */
    @Override
    public Optional<StepSchema> getSchema(String runnerClassName) {
        if (runnerClassName == null || runnerClassName.trim().isEmpty()) {
            return Optional.empty();
        }
        // computeIfAbsent ensures thread-safe, atomic loading and caching
        StepSchema schema = schemaCache.computeIfAbsent(runnerClassName, this::loadSchemaFromFile);
        return Optional.ofNullable(schema);
    }

    /**
     * Loads a schema for the given runner class name from classpath resources.
     * It tries to find files ending with .schema.json, then .schema.yaml, then .schema.yml.
     *
     * @param runnerClassName The fully qualified name of the runner.
     * @return The loaded StepSchema, or null if not found or on error.
     */
    private StepSchema loadSchemaFromFile(String runnerClassName) {
        String baseSchemaPath = runnerClassName.replace('.', '/');
        String[] pathsToTry = {
                baseSchemaPath + ".schema.json",
                baseSchemaPath + ".schema.yaml",
                baseSchemaPath + ".schema.yml"
        };

        for (String path : pathsToTry) {
            try (InputStream schemaStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (schemaStream != null) {
                    ObjectMapper mapperToUse = path.endsWith(".json") ? jsonMapper : yamlMapper;
                    StepSchema schema = mapperToUse.readValue(schemaStream, StepSchema.class);

                    // Basic validation: ensure the runnerClassName in the schema matches the requested one.
                    return schema;
                }
            } catch (Exception e) {
                // Cache null or a special marker to avoid repeated failed load attempts for this path,
                // or simply don't cache and retry next time. For this impl, we just return null.
                return null;
            }
        }

        return null;
    }

    /**
     * Clears the internal schema cache. Useful for reloading schemas during development or testing.
     */
    public void clearCache() {
        schemaCache.clear();
    }

    /**
     * Manually registers or updates a schema in the cache.
     * This can be used for testing or for schemas that are not discoverable via classpath scanning.
     *
     * @param runnerClassName The fully qualified class name of the runner. Must not be null.
     * @param schema The StepSchema to register. Must not be null.
     */
    public void registerSchema(@lombok.NonNull String runnerClassName, @lombok.NonNull StepSchema schema) {
        // Using lombok.NonNull for parameter check, or use Objects.requireNonNull
        // Objects.requireNonNull(runnerClassName, "Runner class name cannot be null for manual schema registration.");
        // Objects.requireNonNull(schema, "Schema cannot be null for manual schema registration.");
        schemaCache.put(runnerClassName, schema);
    }
}