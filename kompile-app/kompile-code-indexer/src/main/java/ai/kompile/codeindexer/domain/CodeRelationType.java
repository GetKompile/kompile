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
 * Types of relationships between code entities.
 */
public enum CodeRelationType {
    CONTAINS,       // file contains class, class contains method
    EXTENDS,        // class extends superclass
    IMPLEMENTS,     // class implements interface
    IMPORTS,        // file imports a type
    CALLS,          // method calls another method
    OVERRIDES,      // method overrides parent method
    RETURNS,        // method returns a type
    PARAMETER_TYPE, // method has parameter of type
    FIELD_TYPE,     // field is of type
    ANNOTATED_BY,   // entity annotated with annotation
    DEPENDS_ON      // general dependency
}
