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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A single node in the {@link TypeHierarchy}: represents one named entity type together with
 * its structural metadata, its declared supertype, its children, its membership set, and its
 * type-level constraints.
 *
 * <h2>Design choices</h2>
 * <ul>
 *   <li><strong>Single-parent isA.</strong> Each node has at most one {@link #getParent()} link,
 *       matching the {@code EntityType.superType} pointer already present in the MEBN layer.
 *       This keeps MFrag dispatch ({@code MTheory.getMostSpecificMFrag}) and PSL subsumption
 *       rule derivation simple (no diamond resolution). Facets / role tags are a separate concern
 *       (handled via {@link ai.kompile.graph.reasoning.model.GraphEntity#tags()}).</li>
 *   <li><strong>Computed membership.</strong> {@link #getEntityIds()} is computed from the graph
 *       by {@link TypeHierarchy#fromGraph(ai.kompile.graph.reasoning.model.ReasoningGraph)} and
 *       never maintained independently. Rebuilding the hierarchy after a graph mutation produces a
 *       fresh, consistent membership set.</li>
 *   <li><strong>Mutable during construction.</strong> {@code TypeNode} is built incrementally by
 *       {@link TypeHierarchy} (add children, set entity IDs, set parent). After the hierarchy is
 *       fully constructed the node is treated as read-only by callers.</li>
 * </ul>
 */
public final class TypeNode {

    private final String typeName;
    private TypeNode parent;
    private final Set<TypeNode> children;
    private final Set<String> entityIds;
    private TypeAttributeSchema attributeSchema;
    private final List<TypeConstraint> constraints;

    /** Package-private — created only by {@link TypeHierarchy} and {@link TypeRegistry}. */
    TypeNode(String typeName) {
        this.typeName        = Objects.requireNonNull(typeName, "typeName");
        this.children        = new LinkedHashSet<>();
        this.entityIds       = new LinkedHashSet<>();
        this.attributeSchema = TypeAttributeSchema.EMPTY;
        this.constraints     = new ArrayList<>();
    }

    // ─── Identity ───────────────────────────────────────────────────────────────

    /** The type name (never {@code null}). Immutable after construction. */
    public String getTypeName() {
        return typeName;
    }

    // ─── Hierarchy ──────────────────────────────────────────────────────────────

    /**
     * The declared supertype of this type, or {@code null} when this type is a root
     * (i.e., has no declared parent). Single-parent isA — no multiple inheritance.
     */
    public TypeNode getParent() {
        return parent;
    }

    /** Package-private setter used by {@link TypeHierarchy} during construction. */
    void setParent(TypeNode parent) {
        this.parent = parent;
    }

    /** An unmodifiable view of the direct child type nodes (subtypes of this type). */
    public Set<TypeNode> getChildren() {
        return Collections.unmodifiableSet(children);
    }

    /** Package-private mutator: register a direct child. */
    void addChild(TypeNode child) {
        children.add(child);
    }

    /**
     * Walk the declared supertype chain upward to check whether this node is a (transitive)
     * subtype of {@code other}.
     *
     * <p><strong>Reflexive:</strong> a type is a subtype of itself ({@code isSubtypeOf(this)}
     * returns {@code true}). This matches the standard OWL/RDFS {@code rdfs:subClassOf}
     * semantics.</p>
     *
     * <p><strong>Cycle-safe:</strong> if a cycle was accidentally declared in the registry
     * the walk terminates after visiting each node at most once (tracked via a local visited
     * set), so this method always terminates.</p>
     *
     * @param other the candidate supertype to test against
     * @return {@code true} if this node is the same as, or a transitive subtype of, {@code other}
     */
    public boolean isSubtypeOf(TypeNode other) {
        if (this.equals(other)) return true;
        Set<String> visited = new HashSet<>();
        TypeNode current = this.parent;
        while (current != null) {
            if (!visited.add(current.typeName)) {
                // Cycle detected — stop
                break;
            }
            if (current.equals(other)) return true;
            current = current.parent;
        }
        return false;
    }

    // ─── Membership ─────────────────────────────────────────────────────────────

    /**
     * The set of entity IDs (from the source {@link ai.kompile.graph.reasoning.model.ReasoningGraph})
     * whose {@link ai.kompile.graph.reasoning.model.GraphEntity#type()} matched this type name
     * (case-insensitively) at the time the {@link TypeHierarchy} was built.
     *
     * <p>This is a <em>computed view</em> of the graph, not a separately maintained registry —
     * it reflects graph state at construction time.</p>
     */
    public Set<String> getEntityIds() {
        return Collections.unmodifiableSet(entityIds);
    }

    /** Package-private: add an entity ID during graph-membership computation. */
    void addEntityId(String id) {
        entityIds.add(id);
    }

    // ─── Attribute schema ────────────────────────────────────────────────────────

    /**
     * The declared attribute schema for this type, or {@link TypeAttributeSchema#EMPTY} when
     * no schema has been declared.
     *
     * <p>When a {@link TypeRegistry} has declared a schema for this type <em>and</em> the
     * {@link TypeHierarchy} has performed a deep schema merge, this schema includes both the
     * directly-declared attributes and any inherited ones (marked
     * {@link AttributeDefinition#isInherited()}) from supertype nodes.</p>
     */
    public TypeAttributeSchema getAttributeSchema() {
        return attributeSchema;
    }

    /** Package-private: set or replace the attribute schema (called during hierarchy construction). */
    void setAttributeSchema(TypeAttributeSchema schema) {
        this.attributeSchema = schema != null ? schema : TypeAttributeSchema.EMPTY;
    }

    // ─── Constraints ────────────────────────────────────────────────────────────

    /** All type-level constraints declared for this type (unmodifiable view). */
    public List<TypeConstraint> getConstraints() {
        return Collections.unmodifiableList(constraints);
    }

    /** Package-private: add a constraint during registry-driven hierarchy construction. */
    void addConstraint(TypeConstraint constraint) {
        constraints.add(Objects.requireNonNull(constraint, "constraint"));
    }

    // ─── Object identity ────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypeNode that)) return false;
        return typeName.equals(that.typeName);
    }

    @Override
    public int hashCode() {
        return typeName.hashCode();
    }

    @Override
    public String toString() {
        return "TypeNode{" + typeName
                + ", entities=" + entityIds.size()
                + ", children=" + children.size()
                + "}";
    }
}
