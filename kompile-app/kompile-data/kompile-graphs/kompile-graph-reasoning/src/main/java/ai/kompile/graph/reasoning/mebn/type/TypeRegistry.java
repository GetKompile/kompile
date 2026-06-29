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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The <em>declared</em> side of the MEBN type system: a registry of type names, their isA
 * (single-parent) links, attribute schemas, and type-level constraints.
 *
 * <p>{@code TypeRegistry} holds <strong>only</strong> structural declarations — it never stores
 * entity IDs. Membership is always derived from a {@link ReasoningGraph} at hierarchy-build time
 * (see {@link #buildFor(ReasoningGraph)}). This keeps the registry reusable across multiple
 * graphs and prevents stale membership accumulation.</p>
 *
 * <h2>Usage pattern</h2>
 * <pre>
 *   TypeRegistry registry = new TypeRegistry()
 *       .declare("Entity")
 *       .declare("Person", personSchema)
 *       .subtype("Person", "Entity")
 *       .declare("Employee", employeeSchema)
 *       .subtype("Employee", "Person")
 *       .constraint("Employee", new TypeConstraint.RelationConstraint("EMPLOYS", "Organization", "Employee"));
 *
 *   TypeHierarchy hierarchy = registry.buildFor(myGraph);
 *   boolean ok = hierarchy.isA("Employee", "Entity"); // true
 * </pre>
 *
 * <h2>Cycle safety</h2>
 * <p>Declaring a cycle in the subtype links (A → B → A) is not explicitly prevented at
 * declaration time — cycles are only guarded at query time in {@link TypeHierarchy#isA} and
 * the other traversal methods. If a cycle is declared the hierarchy is still usable; all
 * traversal methods terminate (cycle-safe visited-set guards).</p>
 *
 * <h2>Idempotent declarations</h2>
 * <p>Calling {@link #declare(String)} more than once for the same type name is idempotent.
 * A second {@link #declare(String, TypeAttributeSchema)} call with a schema <em>replaces</em>
 * the previous schema for that type.</p>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, JPA, or store dependencies. The caller (e.g. the ontology bridge in app-main)
 * populates the registry and calls {@link #buildFor}; the lib never reaches out to load
 * schemas on its own.</p>
 */
public final class TypeRegistry {

    /** Structural declarations keyed by lower-cased type name. */
    private final Map<String, Declaration> declarations = new LinkedHashMap<>();

    /** Internal holder for per-type declared state (all mutable during registry construction). */
    private static final class Declaration {
        final String typeName;           // original casing
        String parentName;               // lower-cased parent name, or null
        TypeAttributeSchema schema;
        final List<TypeConstraint> constraints = new ArrayList<>();

        Declaration(String typeName) {
            this.typeName = typeName;
            this.schema   = TypeAttributeSchema.EMPTY;
        }
    }

    // ─── Fluent declaration API ──────────────────────────────────────────────────

    /**
     * Declare a type by name (idempotent — a second call for the same name is a no-op).
     *
     * @param typeName the type name (never {@code null})
     * @return {@code this} for chaining
     */
    public TypeRegistry declare(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        declarations.computeIfAbsent(typeName.toLowerCase(), k -> new Declaration(typeName));
        return this;
    }

    /**
     * Declare a type with an explicit attribute schema (replaces any previously declared schema
     * for the same type).
     *
     * @param typeName the type name
     * @param schema   the attribute schema (may be {@link TypeAttributeSchema#EMPTY})
     * @return {@code this} for chaining
     */
    public TypeRegistry declare(String typeName, TypeAttributeSchema schema) {
        Objects.requireNonNull(typeName, "typeName");
        String key = typeName.toLowerCase();
        declarations.computeIfAbsent(key, k -> new Declaration(typeName));
        declarations.get(key).schema = schema != null ? schema : TypeAttributeSchema.EMPTY;
        return this;
    }

    /**
     * Declare a single-parent isA link: {@code child} is a subtype of {@code parent}.
     *
     * <p>Both types are implicitly declared if they have not been declared yet. If
     * {@code child} already has a parent declared, it is replaced (single-parent constraint).</p>
     *
     * @param child  the subtype name (never {@code null})
     * @param parent the supertype name (never {@code null})
     * @return {@code this} for chaining
     * @throws IllegalArgumentException if {@code child} and {@code parent} are the same name
     */
    public TypeRegistry subtype(String child, String parent) {
        Objects.requireNonNull(child,  "child");
        Objects.requireNonNull(parent, "parent");
        if (child.equalsIgnoreCase(parent)) {
            throw new IllegalArgumentException(
                    "A type cannot be its own parent: '" + child + "'");
        }
        declare(child);
        declare(parent);
        declarations.get(child.toLowerCase()).parentName = parent.toLowerCase();
        return this;
    }

    /**
     * Attach a {@link TypeConstraint} to a declared type. Multiple constraints may be attached
     * to the same type.
     *
     * @param typeName   the type to constrain (implicitly declared if unknown)
     * @param constraint the constraint to attach
     * @return {@code this} for chaining
     */
    public TypeRegistry constraint(String typeName, TypeConstraint constraint) {
        Objects.requireNonNull(typeName,   "typeName");
        Objects.requireNonNull(constraint, "constraint");
        declare(typeName);
        declarations.get(typeName.toLowerCase()).constraints.add(constraint);
        return this;
    }

    // ─── Hierarchy construction ──────────────────────────────────────────────────

    /**
     * Build a {@link TypeHierarchy} that merges the declared structure in this registry with
     * the computed entity membership derived from {@code graph}.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Create one {@link TypeNode} for every type name in this registry <em>and</em> for
     *       every distinct {@link GraphEntity#type()} string found in the graph that was not
     *       already declared.</li>
     *   <li>Populate entity IDs: for each entity in the graph, add its ID to the node whose
     *       type name matches (case-insensitive).</li>
     *   <li>Wire declared parent links: set {@link TypeNode#setParent} and
     *       {@link TypeNode#addChild} for each {@link #subtype} declaration.</li>
     *   <li>Attach attribute schemas and constraints to the corresponding nodes.</li>
     *   <li>Merge attribute schemas top-down: each node's effective schema is the deep merge of
     *       its own declared schema over its parent's effective schema (child overrides parent
     *       on same attribute name).</li>
     * </ol>
     *
     * @param graph the source graph (never {@code null})
     * @return a fully constructed {@link TypeHierarchy}
     */
    public TypeHierarchy buildFor(ReasoningGraph graph) {
        return buildFor(graph, Map.of());
    }

    /**
     * As {@link #buildFor(ReasoningGraph)}, but additionally treats each {@code (typeName →
     * entityIds)} entry of {@code extraMembersByType} as membership. Used to fold OWL-RL inferred
     * types (an entity classified into a type it is not explicitly typed as) into the hierarchy, so
     * subsumption grounding sees those entities under the inferred type and all its supertypes.
     *
     * @param graph              the source graph (never {@code null})
     * @param extraMembersByType extra {@code typeName → entityIds} memberships (e.g. OWL-inferred
     *                           types); may be empty, never {@code null}
     * @return a fully constructed {@link TypeHierarchy}
     */
    public TypeHierarchy buildFor(ReasoningGraph graph,
                                  Map<String, ? extends java.util.Collection<String>> extraMembersByType) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(extraMembersByType, "extraMembersByType");

        // Step 1: collect all type names
        Map<String, TypeNode>  nodes     = new LinkedHashMap<>();
        Map<String, String>    canonical = new LinkedHashMap<>(); // lower → original-cased

        // From registry declarations
        for (Map.Entry<String, Declaration> e : declarations.entrySet()) {
            String lower = e.getKey();
            String orig  = e.getValue().typeName;
            nodes.put(lower, new TypeNode(orig));
            canonical.put(lower, orig);
        }

        // From graph entities (add any type not yet in the registry)
        for (GraphEntity entity : graph.entities()) {
            String raw   = entity.type();
            String lower = raw.toLowerCase();
            if (!nodes.containsKey(lower)) {
                nodes.put(lower, new TypeNode(raw));
                canonical.put(lower, raw);
            }
        }

        // From extra (e.g. OWL-inferred) memberships — declare any not-yet-known type
        for (String typeName : extraMembersByType.keySet()) {
            if (typeName == null || typeName.isBlank()) continue;
            String lower = typeName.toLowerCase();
            if (!nodes.containsKey(lower)) {
                nodes.put(lower, new TypeNode(typeName));
                canonical.put(lower, typeName);
            }
        }

        // Step 2: populate entity IDs from graph
        for (GraphEntity entity : graph.entities()) {
            String lower = entity.type().toLowerCase();
            nodes.get(lower).addEntityId(entity.id());
        }

        // Step 2b: add extra (OWL-inferred) memberships
        for (Map.Entry<String, ? extends java.util.Collection<String>> e : extraMembersByType.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            TypeNode node = nodes.get(e.getKey().toLowerCase());
            if (node == null) continue;
            for (String entityId : e.getValue()) {
                if (entityId != null) node.addEntityId(entityId);
            }
        }

        // Step 3: wire parent/child links from registry declarations
        for (Map.Entry<String, Declaration> e : declarations.entrySet()) {
            String childKey  = e.getKey();
            String parentKey = e.getValue().parentName;
            if (parentKey == null) continue;
            TypeNode childNode  = nodes.get(childKey);
            TypeNode parentNode = nodes.get(parentKey);
            if (childNode == null || parentNode == null) continue;
            childNode.setParent(parentNode);
            parentNode.addChild(childNode);
        }

        // Step 4: attach constraints
        for (Map.Entry<String, Declaration> e : declarations.entrySet()) {
            TypeNode node = nodes.get(e.getKey());
            if (node == null) continue;
            for (TypeConstraint c : e.getValue().constraints) {
                node.addConstraint(c);
            }
        }

        // Step 5: deep-merge attribute schemas (top-down BFS from roots)
        // First pass: assign declared schemas directly
        for (Map.Entry<String, Declaration> e : declarations.entrySet()) {
            TypeNode node = nodes.get(e.getKey());
            if (node != null) node.setAttributeSchema(e.getValue().schema);
        }
        // Second pass: merge downward from roots
        for (TypeNode node : nodes.values()) {
            if (node.getParent() == null) {
                // Root node — push merged schema down to children
                mergeSchemaDown(node);
            }
        }

        return TypeHierarchy.of(nodes, canonical);
    }

    /**
     * Recursively merge the attribute schema downward from {@code node} to its children.
     * Child definitions override parent definitions of the same name.
     */
    private void mergeSchemaDown(TypeNode node) {
        for (TypeNode child : node.getChildren()) {
            TypeAttributeSchema parentSchema = node.getAttributeSchema();
            TypeAttributeSchema childDeclared = child.getAttributeSchema();
            // Merge: child's own attrs override parent's inherited attrs
            TypeAttributeSchema merged = childDeclared.mergedOver(parentSchema);
            child.setAttributeSchema(merged);
            mergeSchemaDown(child);
        }
    }

    @Override
    public String toString() {
        return "TypeRegistry{" + declarations.size() + " declared types}";
    }
}
