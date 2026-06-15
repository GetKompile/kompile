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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for Neo4j graph storage.
 *
 * <p>This configuration is only loaded when Neo4j driver classes are on the classpath.
 * It imports the actual bean definitions from Neo4jGraphBeans.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.neo4j.driver.Driver")
@Import(Neo4jGraphBeans.class)
public class Neo4jGraphConfiguration {
}
