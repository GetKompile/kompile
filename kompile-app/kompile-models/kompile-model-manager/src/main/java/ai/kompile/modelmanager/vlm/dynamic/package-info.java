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

/**
 * Dynamic VLM pipeline configuration models.
 *
 * <p>This package contains POJOs for defining VLM pipelines at runtime,
 * extending the static enum-based approach with user-configurable pipelines.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link ai.kompile.modelmanager.vlm.dynamic.VlmPipelineDefinition} - Complete pipeline configuration</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.dynamic.VlmStageDefinition} - Dynamic stage definition</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.dynamic.VlmPipelineStageConfig} - Stage instance in a pipeline</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.dynamic.VlmGraphNodeConfig} - Graph node for DAG pipelines</li>
 *   <li>{@link ai.kompile.modelmanager.vlm.dynamic.VlmCustomModelSet} - Runtime-registrable model sets</li>
 * </ul>
 *
 * <h2>Pipeline Types</h2>
 * <ul>
 *   <li><b>SEQUENCE</b> - Linear stage execution (most common)</li>
 *   <li><b>GRAPH</b> - DAG-based execution with parallel paths</li>
 * </ul>
 *
 * @see ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry
 */
package ai.kompile.modelmanager.vlm.dynamic;
