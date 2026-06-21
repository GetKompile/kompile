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
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled JSON serialization for {@link TypeRegistry} — the declared type-system structure.
 *
 * <h2>What is persisted</h2>
 * <p>Only the <em>declared</em> structural information is persisted:</p>
 * <ul>
 *   <li>Type names (in declaration order)</li>
 *   <li>isA parent links ({@code "parent"} field on each type entry)</li>
 *   <li>Attribute schemas (per attribute: {@code name}, {@code valueType}, {@code required},
 *       {@code enumValues}, {@code min}, {@code max}, {@code refersToType})</li>
 *   <li>Constraints (per constraint: {@code kind} + constraint-specific fields)</li>
 * </ul>
 * <p>Entity membership is NOT persisted — it is always computed from a {@link
 * ai.kompile.graph.reasoning.model.ReasoningGraph} at hierarchy-build time. Persisting
 * membership would cause stale entity lists when the graph changes.</p>
 *
 * <h2>JSON format</h2>
 * <pre>
 * {
 *   "types": [
 *     {
 *       "name": "Entity",
 *       "parent": null,
 *       "attributes": [],
 *       "constraints": []
 *     },
 *     {
 *       "name": "Person",
 *       "parent": "Entity",
 *       "attributes": [
 *         {"name":"email","valueType":"STRING","required":true}
 *       ],
 *       "constraints": [
 *         {"kind":"RELATION","relationLabel":"KNOWS","domainType":"Person","rangeType":"Person"}
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Constraint kinds persisted</h2>
 * <ul>
 *   <li>{@code RELATION} — {@link TypeConstraint.RelationConstraint}</li>
 *   <li>{@code CARDINALITY} — {@link TypeConstraint.CardinalityConstraint}</li>
 *   <li>{@code ATTR_REQUIRED} — {@link TypeConstraint.AttributeRequiredConstraint}</li>
 * </ul>
 *
 * <h2>Infra-free contract</h2>
 * <p>No jackson-databind, no Spring, no JPA. Pattern mirrors
 * {@link ai.kompile.graph.reasoning.learning.PslWeightLearningService#weightsToJson} and
 * {@link ai.kompile.graph.reasoning.learning.MebnWeightSerializer#strengthsToJson}.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Persist
 *   String json = TypeRegistryIO.toJson(registry);
 *
 *   // Restore
 *   TypeRegistry restored = TypeRegistryIO.fromJson(json);
 *   TypeHierarchy h = restored.buildFor(myGraph);
 * </pre>
 *
 * <p>The {@link TypeRegistry} does not expose its internal {@code declarations} map, so this
 * class works by re-building the registry via the public fluent API ({@link TypeRegistry#declare},
 * {@link TypeRegistry#subtype}, {@link TypeRegistry#constraint}) during deserialization.
 * This also means the round-trip behaves identically to freshly declaring the same structure.</p>
 */
public final class TypeRegistryIO {

    private TypeRegistryIO() { }

    // ═════════════════════════════════════════════════════════════════════════
    // Serialization
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Serialize the declared structure of {@code registry} to JSON.
     *
     * <p><strong>Caveat:</strong> {@link TypeRegistry} does not expose its internal declaration map
     * directly. This method therefore requires the caller to provide a {@code snapshot} of the
     * declared types in the order they should be serialized. Use
     * {@link #toJson(List)} to provide the ordered snapshot, or see
     * {@link #toJson(TypeRegistrySnapshot)} for the self-contained variant.</p>
     *
     * <p>The most ergonomic approach is to use {@link TypeRegistrySnapshot} to build the registry
     * and serialize it together — see {@link TypeRegistrySnapshot} for details.</p>
     *
     * @param snapshot ordered list of type declarations to serialize
     * @return a JSON string parseable by {@link #fromJson}
     */
    public static String toJson(List<TypeEntrySnapshot> snapshot) {
        StringBuilder sb = new StringBuilder("{\"types\":[");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) sb.append(',');
            appendTypeEntry(sb, snapshot.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Deserialization
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Deserialize a {@link TypeRegistry} from a JSON string produced by {@link #toJson}.
     *
     * <p>The restored registry is semantically equivalent to one built by calling {@link
     * TypeRegistry#declare}, {@link TypeRegistry#subtype}, and {@link TypeRegistry#constraint}
     * in the same order as the serialized entries.</p>
     *
     * @param json JSON string produced by {@link #toJson} (never {@code null})
     * @return a restored {@link TypeRegistry}
     */
    public static TypeRegistry fromJson(String json) {
        List<TypeEntrySnapshot> entries = parseEntries(json);
        TypeRegistry registry = new TypeRegistry();

        // First pass: declare all types (with schemas) so subtype() can reference them
        for (TypeEntrySnapshot e : entries) {
            if (e.attributes.isEmpty()) {
                registry.declare(e.name);
            } else {
                TypeAttributeSchema schema = buildSchema(e.attributes);
                registry.declare(e.name, schema);
            }
        }

        // Second pass: wire parent links
        for (TypeEntrySnapshot e : entries) {
            if (e.parent != null && !e.parent.isEmpty()) {
                registry.subtype(e.name, e.parent);
            }
        }

        // Third pass: attach constraints
        for (TypeEntrySnapshot e : entries) {
            for (ConstraintSnapshot cs : e.constraints) {
                TypeConstraint c = buildConstraint(cs);
                if (c != null) {
                    registry.constraint(e.name, c);
                }
            }
        }

        return registry;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Snapshot types (data holders for serialization round-trips)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * A snapshot of a single type declaration: its name, parent (nullable), attribute
     * definitions, and constraints. Instances of this class are the unit of serialization.
     *
     * <p>Build via the fluent builder: {@link TypeEntrySnapshot#of(String)}.</p>
     */
    public static final class TypeEntrySnapshot {
        final String                   name;
        final String                   parent;          // nullable
        final List<AttrSnapshot>       attributes;
        final List<ConstraintSnapshot> constraints;

        private TypeEntrySnapshot(String name, String parent,
                                  List<AttrSnapshot> attributes,
                                  List<ConstraintSnapshot> constraints) {
            this.name        = name;
            this.parent      = parent;
            this.attributes  = attributes;
            this.constraints = constraints;
        }

        /** Start a snapshot builder for the given type name. */
        public static Builder of(String name) { return new Builder(name); }

        public static final class Builder {
            private final String name;
            private String parent;
            private final List<AttrSnapshot>       attributes  = new ArrayList<>();
            private final List<ConstraintSnapshot> constraints = new ArrayList<>();

            private Builder(String name) { this.name = name; }

            /** Set the parent type name (for isA / subtype link). */
            public Builder parent(String parentName) {
                this.parent = parentName;
                return this;
            }

            /** Add an attribute definition snapshot. */
            public Builder attribute(AttrSnapshot attr) {
                attributes.add(attr);
                return this;
            }

            /** Convenience: add a required STRING attribute. */
            public Builder requiredString(String attrName) {
                return attribute(new AttrSnapshot(attrName, "STRING", true,
                        List.of(), null, null, null));
            }

            /** Convenience: add an optional STRING attribute. */
            public Builder optionalString(String attrName) {
                return attribute(new AttrSnapshot(attrName, "STRING", false,
                        List.of(), null, null, null));
            }

            /** Add a constraint snapshot. */
            public Builder constraint(ConstraintSnapshot c) {
                constraints.add(c);
                return this;
            }

            /** Add a RelationConstraint. */
            public Builder relationConstraint(String label, String domain, String range) {
                return constraint(ConstraintSnapshot.relation(label, domain, range));
            }

            /** Add a CardinalityConstraint. */
            public Builder cardinalityConstraint(String label, String cardinality) {
                return constraint(ConstraintSnapshot.cardinality(label, cardinality));
            }

            /** Add an AttributeRequiredConstraint. */
            public Builder attrRequired(String attrName) {
                return constraint(ConstraintSnapshot.attrRequired(attrName));
            }

            public TypeEntrySnapshot build() {
                return new TypeEntrySnapshot(name, parent, List.copyOf(attributes),
                        List.copyOf(constraints));
            }
        }
    }

    /**
     * Snapshot of a single {@link AttributeDefinition}: all serializable fields.
     * The {@code inherited} flag is NOT serialized (it is computed during hierarchy build).
     */
    public static final class AttrSnapshot {
        final String       name;
        final String       valueType;   // AttributeValueType.name()
        final boolean      required;
        final List<String> enumValues;
        final Double       min;
        final Double       max;
        final String       refersToType;

        public AttrSnapshot(String name, String valueType, boolean required,
                            List<String> enumValues, Double min, Double max, String refersToType) {
            this.name         = name;
            this.valueType    = valueType;
            this.required     = required;
            this.enumValues   = enumValues == null ? List.of() : List.copyOf(enumValues);
            this.min          = min;
            this.max          = max;
            this.refersToType = refersToType;
        }
    }

    /**
     * Snapshot of a single {@link TypeConstraint}: a {@code kind} discriminator plus
     * constraint-specific fields. Fields not relevant to a given kind are {@code null}.
     */
    public static final class ConstraintSnapshot {
        static final String KIND_RELATION      = "RELATION";
        static final String KIND_CARDINALITY   = "CARDINALITY";
        static final String KIND_ATTR_REQUIRED = "ATTR_REQUIRED";

        final String kind;
        // RELATION fields
        final String relationLabel;
        final String domainType;
        final String rangeType;
        // CARDINALITY fields
        final String cardinality;   // TypeConstraint.Cardinality.name()
        // ATTR_REQUIRED fields
        final String attributeName;

        private ConstraintSnapshot(String kind,
                                   String relationLabel, String domainType, String rangeType,
                                   String cardinality, String attributeName) {
            this.kind          = kind;
            this.relationLabel = relationLabel;
            this.domainType    = domainType;
            this.rangeType     = rangeType;
            this.cardinality   = cardinality;
            this.attributeName = attributeName;
        }

        static ConstraintSnapshot relation(String label, String domain, String range) {
            return new ConstraintSnapshot(KIND_RELATION, label, domain, range, null, null);
        }

        static ConstraintSnapshot cardinality(String label, String cardinality) {
            return new ConstraintSnapshot(KIND_CARDINALITY, label, null, null, cardinality, null);
        }

        static ConstraintSnapshot attrRequired(String attrName) {
            return new ConstraintSnapshot(KIND_ATTR_REQUIRED, null, null, null, null, attrName);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // JSON building (type entry)
    // ═════════════════════════════════════════════════════════════════════════

    private static void appendTypeEntry(StringBuilder sb, TypeEntrySnapshot e) {
        sb.append('{');
        sb.append("\"name\":").append('"').append(escape(e.name)).append('"').append(',');
        if (e.parent != null) {
            sb.append("\"parent\":").append('"').append(escape(e.parent)).append('"').append(',');
        } else {
            sb.append("\"parent\":null,");
        }
        // attributes
        sb.append("\"attributes\":[");
        for (int i = 0; i < e.attributes.size(); i++) {
            if (i > 0) sb.append(',');
            appendAttr(sb, e.attributes.get(i));
        }
        sb.append("],");
        // constraints
        sb.append("\"constraints\":[");
        for (int i = 0; i < e.constraints.size(); i++) {
            if (i > 0) sb.append(',');
            appendConstraint(sb, e.constraints.get(i));
        }
        sb.append("]}");
    }

    private static void appendAttr(StringBuilder sb, AttrSnapshot a) {
        sb.append('{');
        sb.append("\"name\":\"").append(escape(a.name)).append("\",");
        sb.append("\"valueType\":\"").append(escape(a.valueType)).append("\",");
        sb.append("\"required\":").append(a.required);
        if (!a.enumValues.isEmpty()) {
            sb.append(",\"enumValues\":[");
            for (int i = 0; i < a.enumValues.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(a.enumValues.get(i))).append('"');
            }
            sb.append(']');
        }
        if (a.min != null) {
            sb.append(",\"min\":").append(String.format(Locale.ROOT, "%.17g", a.min));
        }
        if (a.max != null) {
            sb.append(",\"max\":").append(String.format(Locale.ROOT, "%.17g", a.max));
        }
        if (a.refersToType != null) {
            sb.append(",\"refersToType\":\"").append(escape(a.refersToType)).append('"');
        }
        sb.append('}');
    }

    private static void appendConstraint(StringBuilder sb, ConstraintSnapshot c) {
        sb.append("{\"kind\":\"").append(escape(c.kind)).append('"');
        switch (c.kind) {
            case ConstraintSnapshot.KIND_RELATION:
                sb.append(",\"relationLabel\":\"").append(escape(c.relationLabel)).append('"');
                sb.append(",\"domainType\":\"").append(escape(c.domainType)).append('"');
                sb.append(",\"rangeType\":\"").append(escape(c.rangeType)).append('"');
                break;
            case ConstraintSnapshot.KIND_CARDINALITY:
                sb.append(",\"relationLabel\":\"").append(escape(c.relationLabel)).append('"');
                sb.append(",\"cardinality\":\"").append(escape(c.cardinality)).append('"');
                break;
            case ConstraintSnapshot.KIND_ATTR_REQUIRED:
                sb.append(",\"attributeName\":\"").append(escape(c.attributeName)).append('"');
                break;
            default:
                break;
        }
        sb.append('}');
    }

    // ═════════════════════════════════════════════════════════════════════════
    // JSON parsing (type entries)
    // ═════════════════════════════════════════════════════════════════════════

    private static List<TypeEntrySnapshot> parseEntries(String json) {
        List<TypeEntrySnapshot> out = new ArrayList<>();
        // Find the "types" array
        String s   = json.trim();
        int typesIdx = s.indexOf("\"types\"");
        if (typesIdx < 0) return out;
        int arrStart = s.indexOf('[', typesIdx);
        if (arrStart < 0) return out;

        // Walk the outer array, extracting individual object strings
        int i = arrStart + 1, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '{') {
                int objEnd = skipObject(s, i);
                String obj = s.substring(i, objEnd);
                TypeEntrySnapshot entry = parseTypeEntry(obj);
                if (entry != null) out.add(entry);
                i = objEnd;
            } else {
                i++;
            }
        }
        return out;
    }

    private static TypeEntrySnapshot parseTypeEntry(String obj) {
        // Strip outer braces
        String s = obj.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);

        String name   = null;
        String parent = null;
        List<AttrSnapshot>       attributes  = new ArrayList<>();
        List<ConstraintSnapshot> constraints = new ArrayList<>();

        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) != '"') break;

            int keyStart = i + 1;
            i = endQuote(s, keyStart);
            String key = unescape(s.substring(keyStart, i));
            i++; // skip '"'
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ':')) i++;

            switch (key) {
                case "name" -> {
                    if (i < n && s.charAt(i) == '"') {
                        int vs = i + 1;
                        i = endQuote(s, vs);
                        name = unescape(s.substring(vs, i));
                        i++;
                    }
                }
                case "parent" -> {
                    if (i < n && s.charAt(i) == '"') {
                        int vs = i + 1;
                        i = endQuote(s, vs);
                        String p = unescape(s.substring(vs, i));
                        parent = p.isEmpty() ? null : p;
                        i++;
                    } else {
                        // null literal
                        while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                    }
                }
                case "attributes" -> {
                    if (i < n && s.charAt(i) == '[') {
                        attributes = parseAttributesArray(s, i);
                        i = skipArray(s, i);
                    }
                }
                case "constraints" -> {
                    if (i < n && s.charAt(i) == '[') {
                        constraints = parseConstraintsArray(s, i);
                        i = skipArray(s, i);
                    }
                }
                default -> {
                    // skip unknown
                    if (i < n && s.charAt(i) == '[') i = skipArray(s, i);
                    else if (i < n && s.charAt(i) == '{') i = skipObject(s, i);
                    else while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                }
            }
        }

        if (name == null) return null;
        return new TypeEntrySnapshot(name, parent, attributes, constraints);
    }

    private static List<AttrSnapshot> parseAttributesArray(String s, int start) {
        List<AttrSnapshot> out = new ArrayList<>();
        int i = start + 1, n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '{') {
                int end = skipObject(s, i);
                AttrSnapshot a = parseAttr(s.substring(i, end));
                if (a != null) out.add(a);
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    private static AttrSnapshot parseAttr(String obj) {
        String s = obj.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);

        String       name        = null;
        String       valueType   = "STRING";
        boolean      required    = false;
        List<String> enumValues  = new ArrayList<>();
        Double       min         = null;
        Double       max         = null;
        String       refersToType = null;

        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) != '"') break;
            int ks = i + 1;
            i = endQuote(s, ks);
            String key = unescape(s.substring(ks, i));
            i++;
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ':')) i++;

            switch (key) {
                case "name" -> {
                    if (i < n && s.charAt(i) == '"') {
                        int vs = i + 1; i = endQuote(s, vs);
                        name = unescape(s.substring(vs, i)); i++;
                    }
                }
                case "valueType" -> {
                    if (i < n && s.charAt(i) == '"') {
                        int vs = i + 1; i = endQuote(s, vs);
                        valueType = unescape(s.substring(vs, i)); i++;
                    }
                }
                case "required" -> {
                    int vs = i;
                    while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                    String tok = s.substring(vs, i).trim();
                    required = "true".equalsIgnoreCase(tok);
                }
                case "enumValues" -> {
                    if (i < n && s.charAt(i) == '[') {
                        enumValues = parseStringArray(s, i);
                        i = skipArray(s, i);
                    }
                }
                case "min" -> {
                    int vs = i;
                    while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                    try { min = Double.parseDouble(s.substring(vs, i).trim()); }
                    catch (NumberFormatException ignore) { }
                }
                case "max" -> {
                    int vs = i;
                    while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                    try { max = Double.parseDouble(s.substring(vs, i).trim()); }
                    catch (NumberFormatException ignore) { }
                }
                case "refersToType" -> {
                    if (i < n && s.charAt(i) == '"') {
                        int vs = i + 1; i = endQuote(s, vs);
                        refersToType = unescape(s.substring(vs, i)); i++;
                    }
                }
                default -> {
                    if (i < n && s.charAt(i) == '[') i = skipArray(s, i);
                    else while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
                }
            }
        }
        if (name == null) return null;
        return new AttrSnapshot(name, valueType, required, enumValues, min, max, refersToType);
    }

    private static List<ConstraintSnapshot> parseConstraintsArray(String s, int start) {
        List<ConstraintSnapshot> out = new ArrayList<>();
        int i = start + 1, n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '{') {
                int end = skipObject(s, i);
                ConstraintSnapshot c = parseConstraint(s.substring(i, end));
                if (c != null) out.add(c);
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    private static ConstraintSnapshot parseConstraint(String obj) {
        String s = obj.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);

        String kind = null, relationLabel = null, domainType = null;
        String rangeType = null, cardinality = null, attributeName = null;

        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) != '"') break;
            int ks = i + 1; i = endQuote(s, ks);
            String key = unescape(s.substring(ks, i)); i++;
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ':')) i++;

            if (i < n && s.charAt(i) == '"') {
                int vs = i + 1; i = endQuote(s, vs);
                String val = unescape(s.substring(vs, i)); i++;
                switch (key) {
                    case "kind"          -> kind          = val;
                    case "relationLabel" -> relationLabel = val;
                    case "domainType"    -> domainType    = val;
                    case "rangeType"     -> rangeType     = val;
                    case "cardinality"   -> cardinality   = val;
                    case "attributeName" -> attributeName = val;
                    default              -> { }
                }
            } else {
                while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
            }
        }

        if (kind == null) return null;
        return switch (kind) {
            case ConstraintSnapshot.KIND_RELATION ->
                    (relationLabel != null && domainType != null && rangeType != null)
                    ? ConstraintSnapshot.relation(relationLabel, domainType, rangeType) : null;
            case ConstraintSnapshot.KIND_CARDINALITY ->
                    (relationLabel != null && cardinality != null)
                    ? ConstraintSnapshot.cardinality(relationLabel, cardinality) : null;
            case ConstraintSnapshot.KIND_ATTR_REQUIRED ->
                    attributeName != null
                    ? ConstraintSnapshot.attrRequired(attributeName) : null;
            default -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Domain-object construction helpers (snapshot → live type-system objects)
    // ═════════════════════════════════════════════════════════════════════════

    private static TypeAttributeSchema buildSchema(List<AttrSnapshot> attrs) {
        if (attrs.isEmpty()) return TypeAttributeSchema.EMPTY;
        TypeAttributeSchema.Builder sb = TypeAttributeSchema.builder();
        for (AttrSnapshot a : attrs) {
            AttributeValueType vt;
            try { vt = AttributeValueType.valueOf(a.valueType.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignore) { vt = AttributeValueType.STRING; }

            AttributeDefinition.Builder b = AttributeDefinition.of(a.name, vt).required(a.required);
            if (!a.enumValues.isEmpty()) b.enumValues(a.enumValues);
            if (a.min          != null) b.min(a.min);
            if (a.max          != null) b.max(a.max);
            if (a.refersToType != null) b.refersToType(a.refersToType);
            sb.add(b.build());
        }
        return sb.build();
    }

    private static TypeConstraint buildConstraint(ConstraintSnapshot cs) {
        return switch (cs.kind) {
            case ConstraintSnapshot.KIND_RELATION ->
                    new TypeConstraint.RelationConstraint(cs.relationLabel, cs.domainType, cs.rangeType);
            case ConstraintSnapshot.KIND_CARDINALITY -> {
                TypeConstraint.Cardinality card;
                try { card = TypeConstraint.Cardinality.valueOf(cs.cardinality); }
                catch (IllegalArgumentException ignore) { card = TypeConstraint.Cardinality.MANY_TO_MANY; }
                yield new TypeConstraint.CardinalityConstraint(cs.relationLabel, card);
            }
            case ConstraintSnapshot.KIND_ATTR_REQUIRED ->
                    new TypeConstraint.AttributeRequiredConstraint(cs.attributeName);
            default -> null;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // JSON parsing primitives (hand-rolled; no jackson-databind)
    // ═════════════════════════════════════════════════════════════════════════

    private static List<String> parseStringArray(String s, int start) {
        List<String> out = new ArrayList<>();
        int i = start + 1, n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '"') {
                int vs = i + 1; i = endQuote(s, vs);
                out.add(unescape(s.substring(vs, i))); i++;
            } else { break; }
        }
        return out;
    }

    /** Skip past the JSON array (or nested arrays) starting at {@code start} (pointing to '[').*/
    private static int skipArray(String s, int start) {
        int depth = 0, i = start, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) return i + 1; }
            else if (c == '"') { i = endQuote(s, i + 1) + 1; continue; }
            i++;
        }
        return i;
    }

    /** Skip past the JSON object starting at {@code start} (pointing to '{'). */
    private static int skipObject(String s, int start) {
        int depth = 0, i = start, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i + 1; }
            else if (c == '"') { i = endQuote(s, i + 1) + 1; continue; }
            i++;
        }
        return i;
    }

    private static int endQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"')  return i;
        }
        return s.length();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
