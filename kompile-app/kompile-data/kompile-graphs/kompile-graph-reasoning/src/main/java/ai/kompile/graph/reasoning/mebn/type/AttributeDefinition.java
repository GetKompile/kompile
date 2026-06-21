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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A typed attribute descriptor for a single named property of a {@link TypeNode}.
 *
 * <p>An {@code AttributeDefinition} captures what the reasoning engines need to know about
 * an attribute: its name, the kind of value it holds ({@link AttributeValueType}), whether it
 * must be present on every entity of the owning type, and type-specific constraints (enum values
 * for {@link AttributeValueType#ENUM}, numeric bounds for {@link AttributeValueType#NUMBER},
 * and the target type name for {@link AttributeValueType#REFERENCE}).</p>
 *
 * <p>Instances are immutable once built. The {@link Builder} is the intended construction path.</p>
 *
 * <p>The {@code inherited} flag is set by {@link TypeHierarchy} during schema merging when a
 * definition was contributed by a supertype's schema rather than the owning type's own
 * declaration. Callers can use this flag to distinguish locally-declared from inherited
 * attributes.</p>
 */
public final class AttributeDefinition {

    private final String name;
    private final AttributeValueType valueType;
    private final boolean required;
    private final List<String> enumValues;
    private final Double min;
    private final Double max;
    private final String refersToType;
    private final boolean inherited;

    private AttributeDefinition(Builder b) {
        this.name         = Objects.requireNonNull(b.name, "name");
        this.valueType    = Objects.requireNonNull(b.valueType, "valueType");
        this.required     = b.required;
        this.enumValues   = b.enumValues == null ? List.of() : List.copyOf(b.enumValues);
        this.min          = b.min;
        this.max          = b.max;
        this.refersToType = b.refersToType;
        this.inherited    = b.inherited;
    }

    /** The attribute name (never {@code null}). */
    public String getName() { return name; }

    /** The value type (never {@code null}). */
    public AttributeValueType getValueType() { return valueType; }

    /**
     * Whether this attribute must be present on every entity of the owning type.
     * Required attributes trigger a validation violation when absent.
     */
    public boolean isRequired() { return required; }

    /**
     * The allowed literal values when {@link #getValueType()} is {@link AttributeValueType#ENUM}.
     * Empty for all other value types.
     */
    public List<String> getEnumValues() { return enumValues; }

    /**
     * Inclusive lower bound when {@link #getValueType()} is {@link AttributeValueType#NUMBER},
     * or {@code null} if no lower bound is declared.
     */
    public Double getMin() { return min; }

    /**
     * Inclusive upper bound when {@link #getValueType()} is {@link AttributeValueType#NUMBER},
     * or {@code null} if no upper bound is declared.
     */
    public Double getMax() { return max; }

    /**
     * The name of the target entity type when {@link #getValueType()} is
     * {@link AttributeValueType#REFERENCE}, or {@code null} for all other value types.
     */
    public String getRefersToType() { return refersToType; }

    /**
     * Whether this definition was contributed by a supertype's schema (set during
     * {@link TypeHierarchy} construction) rather than declared directly on the owning type.
     */
    public boolean isInherited() { return inherited; }

    /**
     * Return a copy of this definition with {@code inherited} set to {@code true}.
     * Used internally by {@link TypeHierarchy} during schema merging.
     */
    AttributeDefinition asInherited() {
        if (inherited) return this;
        Builder b = new Builder(name, valueType);
        b.required      = required;
        b.enumValues    = enumValues.isEmpty() ? null : new java.util.ArrayList<>(enumValues);
        b.min           = min;
        b.max           = max;
        b.refersToType  = refersToType;
        b.inherited     = true;
        return new AttributeDefinition(b);
    }

    // ─── Builder ────────────────────────────────────────────────────────────────

    /** Start a builder for an attribute with the given name and value type. */
    public static Builder of(String name, AttributeValueType valueType) {
        return new Builder(name, valueType);
    }

    /** Convenience: build a required STRING attribute in one call. */
    public static AttributeDefinition requiredString(String name) {
        return of(name, AttributeValueType.STRING).required(true).build();
    }

    /** Convenience: build an optional STRING attribute in one call. */
    public static AttributeDefinition optionalString(String name) {
        return of(name, AttributeValueType.STRING).required(false).build();
    }

    public static final class Builder {
        private final String name;
        private final AttributeValueType valueType;
        private boolean required = false;
        private List<String> enumValues;
        private Double min;
        private Double max;
        private String refersToType;
        private boolean inherited = false;

        private Builder(String name, AttributeValueType valueType) {
            this.name      = name;
            this.valueType = valueType;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder enumValues(List<String> values) {
            this.enumValues = values;
            return this;
        }

        public Builder min(Double min) {
            this.min = min;
            return this;
        }

        public Builder max(Double max) {
            this.max = max;
            return this;
        }

        public Builder refersToType(String typeName) {
            this.refersToType = typeName;
            return this;
        }

        public AttributeDefinition build() {
            return new AttributeDefinition(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttributeDefinition that)) return false;
        return name.equals(that.name) && valueType == that.valueType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, valueType);
    }

    @Override
    public String toString() {
        return "AttributeDefinition{" + name + ":" + valueType + (required ? ",required" : "") + "}";
    }
}
