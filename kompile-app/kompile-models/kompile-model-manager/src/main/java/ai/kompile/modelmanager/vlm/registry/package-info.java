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
 * VLM pipeline registry for managing dynamic configurations.
 *
 * <p>This package provides the registry service for managing VLM pipelines,
 * stages, and model sets with JSON persistence.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry} - Main registry service</li>
 * </ul>
 *
 * <h2>Configuration Files</h2>
 * <p>Stored at {@code ~/.kompile/config/}:</p>
 * <ul>
 *   <li>{@code vlm-pipelines.json} - Custom pipeline definitions</li>
 *   <li>{@code vlm-stages.json} - Custom stage definitions</li>
 *   <li>{@code vlm-model-sets.json} - Custom model sets</li>
 * </ul>
 *
 * @see ai.kompile.modelmanager.vlm.dynamic
 */
package ai.kompile.modelmanager.vlm.registry;
