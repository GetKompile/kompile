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
package ai.kompile.graph.reasoning.unified;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Whole-object binary serialization (JDK {@code ObjectStream}) used to bundle {@link Serializable}
 * model objects (PSL programs, MTheories, TypeRegistries, ...) inside a {@code .kgraph} as
 * {@code models/} artifacts. Pure JDK — no external dependency, keeps the library infraless.
 */
final class JavaSerde {

    private JavaSerde() { }

    static byte[] toBytes(Serializable value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to serialize model " + (value == null ? "null" : value.getClass().getName()), e);
        }
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static <T> T fromBytes(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (T) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize model artifact", e);
        }
    }
}
