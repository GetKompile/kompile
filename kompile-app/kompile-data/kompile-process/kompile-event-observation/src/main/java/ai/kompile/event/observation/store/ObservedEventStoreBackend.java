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
package ai.kompile.event.observation.store;

/**
 * A concrete storage backend for {@link ObservedEventStore}. The marker sub-interface lets the
 * {@link CompositeObservedEventStore} inject all backends as a {@code List} without including
 * itself (it implements {@link ObservedEventStore} directly, not this interface), avoiding a
 * self-referential injection cycle.
 */
public interface ObservedEventStoreBackend extends ObservedEventStore {

    /** Config key identifying this backend, e.g. {@code "jpa"} or {@code "vector"}. */
    String backendName();

    /** Whether this backend can serve reads. The composite reads from the first read-capable backend. */
    default boolean supportsReads() {
        return true;
    }
}
