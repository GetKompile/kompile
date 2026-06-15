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
package ai.kompile.graph.neo4j;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring {@link Condition} that activates Neo4j beans when Neo4j is enabled in the
 * kompile JSON config ({@code ~/.kompile/config/kg-embedding-config.json}).
 *
 * <p>Reads the {@code neo4j.enabled} and {@code neo4j.uri} fields from the JSON config file
 * directly (without requiring a fully-initialized Spring context) so it can be used
 * in {@code @Conditional} annotations on {@code @Bean} methods.</p>
 */
public class Neo4jEnabledCondition implements Condition {

    private static final String CONFIG_FILENAME = "kg-embedding-config.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        try {
            Path configFilePath = KompileHome.dataDir().toPath()
                    .resolve("config")
                    .resolve(CONFIG_FILENAME);

            if (!Files.exists(configFilePath)) {
                return false;
            }

            JsonNode root = OBJECT_MAPPER.readTree(configFilePath.toFile());
            JsonNode neo4jNode = root.path("neo4j");

            boolean enabled = neo4jNode.path("enabled").asBoolean(false);
            String uri = neo4jNode.path("uri").asText("");

            return enabled && !uri.isBlank();

        } catch (Exception e) {
            // If we cannot read the config, do not activate Neo4j beans
            return false;
        }
    }
}
