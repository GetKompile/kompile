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
package ai.kompile.event.observation.domain;

/**
 * The kind of real-world event an {@link ObservedEvent} represents.
 */
public enum EventType {

    /** An entity (KG node) was observed — "entities are events in the number of times they occur". */
    ENTITY_OCCURRENCE,

    /** A connection (an edge of a given type between two nodes) was observed — "connections produce observed events". */
    CONNECTION_OCCURRENCE,

    /** A process step execution, keyed by (processDefinitionId, stepId). */
    PROCESS_STEP_OCCURRENCE,

    /** A caller-defined event type recorded via the manual observe API. */
    USER_DEFINED
}
