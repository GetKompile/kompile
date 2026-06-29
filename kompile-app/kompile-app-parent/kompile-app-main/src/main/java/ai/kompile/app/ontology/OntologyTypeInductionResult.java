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
package ai.kompile.app.ontology;

/**
 * Summary for the post-OWL type-system enrichment pass.
 *
 * @param changed      true when the bound ontology was versioned with aliases or new types
 * @param aliasesAdded aliases/localized labels merged into existing or new types
 * @param typesAdded   new entity types added to the schema
 * @param version      new ontology version, or the existing version when unchanged
 */
public record OntologyTypeInductionResult(boolean changed, int aliasesAdded, int typesAdded, int version) {

    public static OntologyTypeInductionResult unchanged(int version) {
        return new OntologyTypeInductionResult(false, 0, 0, version);
    }
}
