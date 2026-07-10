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
package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.FactSheet;

/**
 * SPI for applying a fact sheet's index configuration (encoder / reranker / vector-store and
 * keyword-index paths) to the running application.
 *
 * <p>Declaring this interface here — in the facts module — inverts what used to be a direct
 * {@code facts → kompile-app-main.services.AppIndexConfigService} dependency. {@link FactSheetService}
 * now depends only on this interface (optionally injected), while the concrete implementation
 * ({@code ai.kompile.app.services.AppIndexConfigService}) lives in kompile-app-main and is wired in
 * by Spring at runtime. This keeps the facts/eval/sync/prompts cluster free of any compile-time
 * coupling back into app-main. When no implementation is present (e.g. the module is used outside
 * the full app), the optional injection is {@code null} and callers skip index configuration.</p>
 */
public interface FactSheetIndexConfigurer {

    /**
     * Apply the given fact sheet's encoder/reranker/storage configuration to the running app.
     *
     * @return {@code true} if configuration changed and was applied; {@code false} if unchanged.
     */
    boolean applyFactSheetConfiguration(FactSheet factSheet);

    /** Default vector-store path derived from a fact sheet name. */
    String generateDefaultVectorStorePath(String factSheetName);

    /** Default keyword-index path derived from a fact sheet name. */
    String generateDefaultKeywordIndexPath(String factSheetName);
}
