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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The type-hierarchy backbone: a computed-and-declared graph of {@link TypeNode} objects that
 * represents what entity types exist in a {@link ReasoningGraph}, how they are arranged in an
 * isA (single-inheritance) tree, and what attribute schemas and constraints they carry.
 *
 * <h2>Two data sources — computed membership + declared structure</h2>
 * <ol>
 *   <li><strong>Computed membership</strong> — {@link #fromGraph(ReasoningGraph)} iterates
 *       {@link ReasoningGraph#entities()} and groups them by
 *       {@link GraphEntity#type()} (case-insensitive), creating one {@link TypeNode} per
 *       distinct type string. This is the same logic as {@link ai.kompile.graph.reasoning.mebn.EntityType#fromGraph(ReasoningGraph, String)}
 *       but covers the full graph at once. Membership is computed eagerly at construction time
 *       and reflects graph state at that moment.</li>
 *   <li><strong>Declared structure</strong> — a {@link TypeRegistry} provides explicit isA links,
 *       attribute schemas, and constraints. {@link #fromGraph(ReasoningGraph, TypeRegistry)}
 *       merges declared structure onto computed membership.</li>
 * </ol>
 *
 * <h2>isA semantics</h2>
 * <p>{@link #isA(String, String)} is <strong>reflexive and transitive</strong>:
 * {@code isA("Person", "Person")} is {@code true};
 * if A isA B and B isA C then {@code isA("A", "C")} is {@code true}.
 * The method is <strong>cycle-safe</strong>: a mis-declared cycle in the registry
 * (A → B → A) does not cause infinite recursion — the walk terminates after visiting
 * each type name at most once.</p>
 *
 * <h2>Computed-view contract</h2>
 * <p>{@code TypeHierarchy} is a snapshot — it does not track subsequent mutations to the
 * source graph. Rebuild it via {@link #fromGraph} after the graph changes to obtain a
 * fresh membership view. {@link TypeRegistry} structure declarations survive across rebuilds.</p>
 *
 * <h2>No infra coupling</h2>
 * <p>This class has no Spring, JPA, or store dependencies. It is callable from any layer that
 * has a {@link ReasoningGraph}.</p>
 */
public final class TypeHierarchy {

    /** Primary index: typeName (canonical, lower-cased) → TypeNode. */
    private final Map<String, TypeNode> nodesByLowerName;
    /**
     * Preserves the original-cased type name for each lower-case key so that
     * {@link #allTypes()} and {@link #forType(String)} can return the casing
     * as it appears in the graph.
     */
    private final Map<String, String> canonicalName;

    private TypeHierarchy(Map<String, TypeNode> nodesByLowerName, Map<String, String> canonicalName) {
        this.nodesByLowerName = Collections.unmodifiableMap(nodesByLowerName);
        this.canonicalName    = Collections.unmodifiableMap(canonicalName);
    }

    // ─── Factory methods ─────────────────────────────────────────────────────────

    /**
     * Build a hierarchy from {@code graph} alone: membership is computed (entities grouped
     * by {@link GraphEntity#type()}, case-insensitive), but no isA links, attribute schemas,
     * or constraints are declared.
     *
     * @param graph the source graph (never {@code null})
     * @return a {@code TypeHierarchy} with one flat {@link TypeNode} per distinct type string
     *         found in the graph
     */
    public static TypeHierarchy fromGraph(ReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Map<String, TypeNode>  nodes     = new LinkedHashMap<>();
        Map<String, String>    canonical = new LinkedHashMap<>();
        for (GraphEntity e : graph.entities()) {
            String raw   = e.type();
            String lower = raw.toLowerCase();
            if (!nodes.containsKey(lower)) {
                nodes.put(lower, new TypeNode(raw));
                canonical.put(lower, raw);
            }
            nodes.get(lower).addEntityId(e.id());
        }
        return new TypeHierarchy(nodes, canonical);
    }

    /**
     * Build a hierarchy from {@code graph} and a {@link TypeRegistry}: membership is computed
     * from the graph; isA links, attribute schemas, and constraints come from the registry.
     *
     * <p>Types declared in the registry but not found in the graph are still included (they
     * have an empty membership set). Types found in the graph but not declared in the registry
     * appear as leaf nodes with no parent, schema, or constraints.</p>
     *
     * <p>Attribute schema merging is <strong>deep</strong>: each node's effective schema is the
     * union of its directly-declared attributes and all inherited attributes from its ancestor
     * chain, with child definitions taking precedence over parent definitions of the same name.</p>
     *
     * @param graph    the source graph (never {@code null})
     * @param registry the declared type structure (never {@code null})
     * @return a fully merged {@code TypeHierarchy}
     */
    public static TypeHierarchy fromGraph(ReasoningGraph graph, TypeRegistry registry) {
        Objects.requireNonNull(graph,    "graph");
        Objects.requireNonNull(registry, "registry");
        return registry.buildFor(graph);
    }

    // ─── Node lookup ─────────────────────────────────────────────────────────────

    /**
     * Look up the {@link TypeNode} for a given type name (case-insensitive).
     *
     * @param typeName the type name to look up
     * @return the node, or {@link Optional#empty()} if the type is not in this hierarchy
     */
    public Optional<TypeNode> forType(String typeName) {
        if (typeName == null) return Optional.empty();
        return Optional.ofNullable(nodesByLowerName.get(typeName.toLowerCase()));
    }

    /** All type nodes in this hierarchy (unmodifiable, insertion order). */
    public Collection<TypeNode> allTypes() {
        return Collections.unmodifiableCollection(nodesByLowerName.values());
    }

    /** All type name strings (original casing from the graph). */
    public Set<String> allTypeNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(canonicalName.values()));
    }

    // ─── isA queries ─────────────────────────────────────────────────────────────

    /**
     * Return {@code true} when {@code childType} is a subtype of (or the same as)
     * {@code ancestorType}, following the declared isA chain.
     *
     * <p><strong>Reflexive:</strong> {@code isA("Person", "Person")} is {@code true}.</p>
     *
     * <p><strong>Transitive:</strong> if Employee isA Person and Person isA Entity then
     * {@code isA("Employee", "Entity")} is {@code true}.</p>
     *
     * <p><strong>Cycle-safe:</strong> if a cycle was declared in the registry the walk
     * terminates after visiting each type at most once, so this method always terminates.</p>
     *
     * @param childType    the candidate subtype (case-insensitive)
     * @param ancestorType the candidate ancestor type (case-insensitive)
     * @return {@code true} if {@code childType} is the same as or a transitive subtype of
     *         {@code ancestorType}; {@code false} if either type is unknown
     */
    public boolean isA(String childType, String ancestorType) {
        if (childType == null || ancestorType == null) return false;
        String childKey    = childType.toLowerCase();
        String ancestorKey = ancestorType.toLowerCase();
        if (childKey.equals(ancestorKey)) return true; // reflexive
        TypeNode child = nodesByLowerName.get(childKey);
        if (child == null) return false;
        // Walk the ancestor chain — cycle-safe via visited set
        Set<String> visited = new HashSet<>();
        TypeNode current = child.getParent();
        while (current != null) {
            String key = current.getTypeName().toLowerCase();
            if (!visited.add(key)) break; // cycle detected
            if (key.equals(ancestorKey)) return true;
            current = current.getParent();
        }
        return false;
    }

    /**
     * Return the direct parent type name of {@code typeName}, if declared.
     *
     * @param typeName the type to query (case-insensitive)
     * @return the parent's type name, or {@link Optional#empty()} if the type is a root or unknown
     */
    public Optional<String> directParent(String typeName) {
        if (typeName == null) return Optional.empty();
        TypeNode node = nodesByLowerName.get(typeName.toLowerCase());
        if (node == null || node.getParent() == null) return Optional.empty();
        return Optional.of(node.getParent().getTypeName());
    }

    /**
     * Return the ordered ancestor chain of {@code typeName}, from its direct parent up to the
     * root, exclusive of {@code typeName} itself.
     *
     * <p>Cycle-safe: if a cycle was declared the walk stops on the first repeated type.</p>
     *
     * @param typeName the type whose ancestors to return (case-insensitive)
     * @return an immutable list of ancestor type names, direct parent first; empty if none or unknown
     */
    public List<String> ancestors(String typeName) {
        if (typeName == null) return List.of();
        TypeNode node = nodesByLowerName.get(typeName.toLowerCase());
        if (node == null) return List.of();
        List<String> result  = new ArrayList<>();
        Set<String>  visited = new HashSet<>();
        TypeNode current = node.getParent();
        while (current != null) {
            String key = current.getTypeName().toLowerCase();
            if (!visited.add(key)) break; // cycle
            result.add(current.getTypeName());
            current = current.getParent();
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Return all transitive descendants of {@code typeName} (all subtypes at any depth).
     *
     * <p>Cycle-safe: if a cycle was declared in the child links the traversal stops on
     * the first revisited type.</p>
     *
     * @param typeName the type whose descendants to return (case-insensitive)
     * @return an unmodifiable list of descendant type names; empty if the type is a leaf or unknown
     */
    public List<String> descendants(String typeName) {
        if (typeName == null) return List.of();
        TypeNode root = nodesByLowerName.get(typeName.toLowerCase());
        if (root == null) return List.of();
        List<String> result  = new ArrayList<>();
        Set<String>  visited = new HashSet<>();
        collectDescendants(root, result, visited);
        return Collections.unmodifiableList(result);
    }

    private void collectDescendants(TypeNode node, List<String> result, Set<String> visited) {
        for (TypeNode child : node.getChildren()) {
            String key = child.getTypeName().toLowerCase();
            if (!visited.add(key)) continue; // cycle guard
            result.add(child.getTypeName());
            collectDescendants(child, result, visited);
        }
    }

    // ─── Membership queries ──────────────────────────────────────────────────────

    /**
     * Return the IDs of all entities whose {@link GraphEntity#type()} matches {@code typeName}
     * (case-insensitive) in the source graph.
     *
     * <p>This is a direct-membership query (only the exact type, not subtypes).
     * To include entities of subtypes pass {@code includeSubtypes=true} to
     * {@link #entitiesOfType(String, boolean)}.</p>
     *
     * @param typeName the type name (case-insensitive)
     * @return an unmodifiable set of entity IDs; empty if the type is unknown or has no members
     */
    public Set<String> membersOf(String typeName) {
        if (typeName == null) return Set.of();
        TypeNode node = nodesByLowerName.get(typeName.toLowerCase());
        return node == null ? Set.of() : node.getEntityIds();
    }

    /**
     * Return entity IDs for the given type, optionally including members of all subtypes.
     *
     * @param typeName        the type name (case-insensitive)
     * @param includeSubtypes if {@code true}, include entities from all transitive subtypes
     * @return an unmodifiable set of entity IDs
     */
    public Set<String> entitiesOfType(String typeName, boolean includeSubtypes) {
        if (!includeSubtypes) return membersOf(typeName);
        if (typeName == null) return Set.of();
        TypeNode root = nodesByLowerName.get(typeName.toLowerCase());
        if (root == null) return Set.of();
        Set<String> result  = new LinkedHashSet<>(root.getEntityIds());
        Set<String> visited = new HashSet<>();
        collectMembersDown(root, result, visited);
        return Collections.unmodifiableSet(result);
    }

    private void collectMembersDown(TypeNode node, Set<String> acc, Set<String> visited) {
        for (TypeNode child : node.getChildren()) {
            String key = child.getTypeName().toLowerCase();
            if (!visited.add(key)) continue;
            acc.addAll(child.getEntityIds());
            collectMembersDown(child, acc, visited);
        }
    }

    // ─── Subtypes / supertypes ───────────────────────────────────────────────────

    /**
     * Return all transitive subtypes of {@code typeName} as a set of {@link TypeNode} objects.
     *
     * @param typeName the type to query (case-insensitive)
     * @return an unmodifiable set; empty if the type is a leaf or unknown
     */
    public Set<TypeNode> subtypesOf(String typeName) {
        if (typeName == null) return Set.of();
        TypeNode root = nodesByLowerName.get(typeName.toLowerCase());
        if (root == null) return Set.of();
        Set<TypeNode> result  = new LinkedHashSet<>();
        Set<String>   visited = new HashSet<>();
        collectSubtypeNodes(root, result, visited);
        return Collections.unmodifiableSet(result);
    }

    private void collectSubtypeNodes(TypeNode node, Set<TypeNode> acc, Set<String> visited) {
        for (TypeNode child : node.getChildren()) {
            String key = child.getTypeName().toLowerCase();
            if (!visited.add(key)) continue;
            acc.add(child);
            collectSubtypeNodes(child, acc, visited);
        }
    }

    /**
     * Return the chain of supertypes of {@code typeName} from direct parent to root,
     * as an ordered list of {@link TypeNode} objects.
     *
     * @param typeName the type to query (case-insensitive)
     * @return an unmodifiable list; empty if the type is a root or unknown
     */
    public List<TypeNode> supertypesOf(String typeName) {
        if (typeName == null) return List.of();
        TypeNode node = nodesByLowerName.get(typeName.toLowerCase());
        if (node == null) return List.of();
        List<TypeNode> result  = new ArrayList<>();
        Set<String>    visited = new HashSet<>();
        TypeNode current = node.getParent();
        while (current != null) {
            String key = current.getTypeName().toLowerCase();
            if (!visited.add(key)) break;
            result.add(current);
            current = current.getParent();
        }
        return Collections.unmodifiableList(result);
    }

    // ─── Package-private construction helpers ────────────────────────────────────

    /**
     * Package-private factory used by {@link TypeRegistry#buildFor(ReasoningGraph)} to
     * construct a hierarchy from pre-built node maps. Not part of the public API.
     */
    static TypeHierarchy of(Map<String, TypeNode> nodesByLowerName, Map<String, String> canonicalName) {
        return new TypeHierarchy(nodesByLowerName, canonicalName);
    }

    @Override
    public String toString() {
        return "TypeHierarchy{" + nodesByLowerName.size() + " types}";
    }
}
