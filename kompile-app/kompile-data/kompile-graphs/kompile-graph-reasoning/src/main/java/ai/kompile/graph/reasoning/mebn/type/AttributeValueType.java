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
package ai.kompile.graph.reasoning.mebn.type;

/**
 * The set of value types that an {@link AttributeDefinition} can carry.
 *
 * <p>Deliberately simpler than the full {@code OntologySchema.FieldDefinition} value type set:
 * only the distinctions the reasoning engines need are represented here. The ontology bridge
 * (Phase 2) maps the richer ontology field types onto these five buckets when constructing a
 * {@link TypeAttributeSchema} for a {@link TypeNode}.</p>
 *
 * <ul>
 *   <li>{@link #STRING} — any free-form text value</li>
 *   <li>{@link #NUMBER} — any numeric value (integer or floating-point)</li>
 *   <li>{@link #BOOLEAN} — true/false flag</li>
 *   <li>{@link #ENUM} — a value restricted to an explicit {@link AttributeDefinition#getEnumValues()} list</li>
 *   <li>{@link #REFERENCE} — a foreign-key reference to another entity type,
 *       named by {@link AttributeDefinition#getRefersToType()}</li>
 * </ul>
 */
public enum AttributeValueType {

    /** A free-form text value. */
    STRING,

    /** A numeric value (integer or floating-point). */
    NUMBER,

    /** A boolean (true/false) value. */
    BOOLEAN,

    /**
     * A value constrained to an explicit enumeration.
     * The allowed values are declared on {@link AttributeDefinition#getEnumValues()}.
     */
    ENUM,

    /**
     * A reference to an entity of another type.
     * The target type name is declared on {@link AttributeDefinition#getRefersToType()}.
     */
    REFERENCE
}
