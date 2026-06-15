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

package ai.kompile.codeindexer.domain;

/**
 * Types of code entities that the indexer can extract.
 */
public enum CodeEntityType {
    FILE,
    PACKAGE,
    MODULE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    CONSTANT,
    FUNCTION,
    IMPORT,
    TYPE_ALIAS,
    VARIABLE,
    /**
     * A design rationale extracted from tagged comments such as
     * {@code // NOTE:}, {@code // HACK:}, {@code // WHY:}, {@code // IMPORTANT:},
     * {@code // TODO:}, or {@code // FIXME:}. These are surfaced as
     * "rationale_for" relationships in the knowledge graph.
     */
    RATIONALE
}
