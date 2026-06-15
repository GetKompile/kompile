/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.core.data;

import ai.kompile.pipelines.framework.api.data.ValueType;
import lombok.Getter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;

/**
 * Internal wrapper for values stored in JData, holding the typed value and its ValueType.
 * This helps in maintaining type information and consistent serialization.
 */
@Getter
class JDataValueWrapper implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ValueType type;
    private Object value; // Keep as Object to handle various types including null

    // Constructor for non-list types
    JDataValueWrapper(ValueType type, Object value) {
        if (type == ValueType.LIST) {
            throw new IllegalArgumentException("For LIST type, use the constructor with listElementType.");
        }
        this.type = Objects.requireNonNull(type, "ValueType cannot be null for non-null value unless value itself is null and type is also null (representing an untyped null).");
        this.value = value; // Allow null value
    }

    // Constructor for LIST type
    JDataValueWrapper(ValueType listContainerType, ValueType listElementType, Object listValue) {
        if (listContainerType != ValueType.LIST) {
            throw new IllegalArgumentException("This constructor is only for LIST type.");
        }
        // listElementType may be null for empty lists or lists with unknown element types
        this.type = ValueType.LIST; // The container type
        // Store the element type information separately if needed, or rely on JData to manage it.
        // For simplicity here, JData will store the list directly.
        // This wrapper could be enhanced to store listElementType if JData needs it per-value.
        this.value = listValue; // Allow null list
    }


    /**
     * Special constructor for an explicit null value where the type is not yet determined or is irrelevant.
     * This can be useful if JData wants to distinguish between a typed null and a generic null.
     */
    JDataValueWrapper() {
        this.type = null; // Representing an untyped or generic null
        this.value = null;
    }


    @SuppressWarnings("unchecked")
    public <T> T getValue() {
        return (T) value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JDataValueWrapper that = (JDataValueWrapper) o;
        return type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    // Custom serialization to handle specific types if needed, though default should work for most.
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
    }
}