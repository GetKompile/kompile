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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered set of {@link AttributeDefinition attribute descriptors} that declares what typed
 * properties instances of a {@link TypeNode} are expected to carry.
 *
 * <p>{@code TypeAttributeSchema} is intentionally simpler than
 * {@code OntologySchema.FieldDefinition}: it captures only the distinctions the reasoning engines
 * need — attribute name, value type, required flag, enum values, numeric bounds, and reference
 * target. The richer ontology constraints (primary-key, immutable, regex, FK-reference, SpEL
 * validation rules) remain in the ontology bridge (Phase 2) and are not pulled into this
 * infra-free lib.</p>
 *
 * <p>Insertion order is preserved. When two definitions share the same name, the later one wins
 * (child overrides parent — this is how {@link TypeHierarchy} implements schema inheritance
 * during its merge pass).</p>
 *
 * <p>Instances are immutable once built. Use the {@link Builder} to construct them.</p>
 */
public final class TypeAttributeSchema {

    /** An empty schema (no declared attributes). Returned when no schema has been declared. */
    public static final TypeAttributeSchema EMPTY = new TypeAttributeSchema(Map.of(), List.of());

    /** Ordered map: name → definition (insertion order preserved). */
    private final Map<String, AttributeDefinition> byName;
    /** Stable iteration order for the whole schema. */
    private final List<AttributeDefinition> ordered;

    private TypeAttributeSchema(Map<String, AttributeDefinition> byName, List<AttributeDefinition> ordered) {
        this.byName  = Collections.unmodifiableMap(byName);
        this.ordered = Collections.unmodifiableList(ordered);
    }

    /** All attribute definitions in declaration order (never {@code null}, may be empty). */
    public List<AttributeDefinition> getAttributes() {
        return ordered;
    }

    /** Look up an attribute definition by name (case-sensitive). */
    public Optional<AttributeDefinition> attribute(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All definitions whose {@link AttributeDefinition#isRequired()} flag is {@code true}. */
    public List<AttributeDefinition> requiredAttributes() {
        List<AttributeDefinition> out = new ArrayList<>();
        for (AttributeDefinition d : ordered) {
            if (d.isRequired()) out.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether this schema declares any attribute definitions. */
    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** Number of declared attribute definitions. */
    public int size() {
        return ordered.size();
    }

    /**
     * Merge {@code parent}'s definitions into this schema, producing a new schema where this
     * schema's definitions take precedence (same-name overrides parent's definition).
     * Definitions from {@code parent} that have no override here are marked
     * {@link AttributeDefinition#isInherited() inherited} in the result.
     *
     * <p>This is used by {@link TypeHierarchy} to compute the effective (deep-merged) attribute
     * schema for a type node that has a declared parent.</p>
     *
     * @param parent the parent type's schema to inherit from
     * @return a merged schema: parent definitions first (marked inherited), overridden by ours
     */
    TypeAttributeSchema mergedOver(TypeAttributeSchema parent) {
        if (parent == null || parent.isEmpty()) return this;
        // Start with all inherited parent attrs, then override with ours
        LinkedHashMap<String, AttributeDefinition> merged = new LinkedHashMap<>();
        for (AttributeDefinition pd : parent.ordered) {
            merged.put(pd.getName(), pd.asInherited());
        }
        // Our own definitions override same-name parent entries
        for (AttributeDefinition d : ordered) {
            merged.put(d.getName(), d);
        }
        List<AttributeDefinition> orderedList = new ArrayList<>(merged.values());
        return new TypeAttributeSchema(merged, orderedList);
    }

    // ─── Builder ────────────────────────────────────────────────────────────────

    /** Start a builder for a new schema. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        /** Ordered insertion map so duplicates replace the earlier entry in place. */
        private final LinkedHashMap<String, AttributeDefinition> defs = new LinkedHashMap<>();

        private Builder() {}

        /** Add (or replace) an attribute definition. */
        public Builder add(AttributeDefinition def) {
            Objects.requireNonNull(def, "def");
            defs.put(def.getName(), def);
            return this;
        }

        /** Convenience: add a definition from its builder. */
        public Builder add(AttributeDefinition.Builder defBuilder) {
            return add(defBuilder.build());
        }

        public TypeAttributeSchema build() {
            if (defs.isEmpty()) return EMPTY;
            LinkedHashMap<String, AttributeDefinition> byName = new LinkedHashMap<>(defs);
            List<AttributeDefinition> ordered = new ArrayList<>(byName.values());
            return new TypeAttributeSchema(byName, ordered);
        }
    }

    @Override
    public String toString() {
        return "TypeAttributeSchema{" + ordered.size() + " attrs}";
    }
}
