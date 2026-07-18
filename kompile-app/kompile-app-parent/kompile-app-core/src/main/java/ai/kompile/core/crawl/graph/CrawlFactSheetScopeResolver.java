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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.core.crawl.graph;

/**
 * Resolves a crawl request to a concrete fact-sheet scope before it is queued.
 *
 * <p>The crawl module deliberately owns only this SPI; the fact-sheet module supplies
 * the persistence-backed implementation when the full application is running.</p>
 */
@FunctionalInterface
public interface CrawlFactSheetScopeResolver {

    /**
     * Mutates {@code request} with a concrete fact-sheet ID and any scope-derived index settings.
     *
     * @throws IllegalArgumentException when an explicitly requested scope does not exist
     * @throws IllegalStateException when an implicit scope cannot be resolved safely
     */
    void resolveScope(UnifiedCrawlRequest request);
}
