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

package ai.kompile.app.web.dto.evaldebugger;

import ai.kompile.core.citation.CitationDto;

/**
 * A retrieved document entry surfaced by the eval-debugger pipeline.
 *
 * <p>Carries both the raw chunk text (for display / LLM-judge context) and a
 * structured {@link CitationDto} so the eval-debugger UI can render inline
 * source citations via {@code <app-source-citation>}.</p>
 *
 * @param text     chunk content as returned by the retriever
 * @param citation structured source citation built from the retriever's metadata
 *                 and score; may be sparse (all fields optional)
 */
public record EvalRetrievedDoc(String text, CitationDto citation) {}
