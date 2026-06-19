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

package ai.kompile.app.web.controllers;

/**
 * This class has been split into focused controllers:
 *
 * <ul>
 *   <li>{@link ModelStatusController} — GET /api/models/status, /init-status,
 *       /subprocess/logs, DELETE /subprocess/logs</li>
 *   <li>{@link ModelDiscoveryController} — GET /api/models/list,
 *       /samediff-embeddings/list, /samediff-embeddings/{idx}/summary,
 *       /samediff-embeddings/{idx}/summary/text, /samediff/{beanName}/summary,
 *       /samediff/{beanName}/summary/text, /embeddings/info,
 *       POST /embeddings/test</li>
 *   <li>{@link Nd4jProfilingController} — GET /api/models/nd4j/profiling-metrics,
 *       /nd4j/thread-dump, POST /nd4j/profiling-preset/{preset}</li>
 *   <li>{@link Nd4jEnvironmentController} — GET/POST /api/nd4j/environment/**
 *       (ND4J environment configuration via Nd4jEnvironmentConfigService)</li>
 * </ul>
 *
 * This stub is retained to avoid breaking any compile-time references that
 * may reference the class name directly. It contains no endpoints.
 */
public class ModelDebugController {
    // Intentionally empty — all endpoints have been extracted to focused controllers.
}
