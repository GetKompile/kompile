/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn;

import java.util.*;

/**
 * Represents an entity type in the MEBN framework.
 *
 * <p>Entity types define categories of domain objects (e.g., Person, Organization,
 * Event, Document) that random variables can be parameterized over. Each entity
 * type maps to a set of KG nodes sharing the same node type or entity classification.</p>
 *
 * <p>Entity types form a type hierarchy — a subtype inherits the random variables
 * of its supertypes (e.g., Employee is-a Person).</p>
 */
public class EntityType {

    private final String typeName;
    private final String description;
    private EntityType superType;
    private final Set<String> entityIds;

    public EntityType(String typeName) {
        this(typeName, null);
    }

    public EntityType(String typeName, String description) {
        this.typeName = typeName;
        this.description = description;
        this.entityIds = new LinkedHashSet<>();
    }

    public String getTypeName() {
        return typeName;
    }

    public String getDescription() {
        return description;
    }

    public EntityType getSuperType() {
        return superType;
    }

    public void setSuperType(EntityType superType) {
        this.superType = superType;
    }

    /**
     * Register a KG node ID as an instance of this entity type.
     */
    public void addEntity(String kgNodeId) {
        entityIds.add(kgNodeId);
    }

    /**
     * Get all registered entity instances.
     */
    public Set<String> getEntityIds() {
        return Collections.unmodifiableSet(entityIds);
    }

    /**
     * Check if a given entity ID is an instance of this type (including supertypes).
     */
    public boolean hasEntity(String kgNodeId) {
        return entityIds.contains(kgNodeId);
    }

    /**
     * Check if this type is a subtype of the given type (directly or transitively).
     */
    public boolean isSubtypeOf(EntityType other) {
        if (this.equals(other)) return true;
        EntityType current = this.superType;
        while (current != null) {
            if (current.equals(other)) return true;
            current = current.getSuperType();
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityType that)) return false;
        return typeName.equals(that.typeName);
    }

    @Override
    public int hashCode() {
        return typeName.hashCode();
    }

    @Override
    public String toString() {
        return "EntityType{" + typeName + ", instances=" + entityIds.size() + "}";
    }
}
