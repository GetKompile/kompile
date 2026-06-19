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
 * Where an observation came from — recorded on each {@link EventObservationRecord} for audit
 * and windowed recompute.
 */
public enum EventSource {

    /** A full crawl / graph build completed (coarse, batch path). */
    CRAWL,

    /** A single node/edge graph mutation (fine-grained, online path). */
    MUTATION,

    /** A manual observation submitted via the REST API. */
    MANUAL,

    /** A process step / run execution. */
    PROCESS
}
