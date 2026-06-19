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
package ai.kompile.event.observation.service;

/**
 * Outcome of a {@link GraphEventScanner#scan} pass.
 */
public record ScanResult(int entitiesObserved, int connectionsObserved, boolean ran) {

    public static ScanResult empty() {
        return new ScanResult(0, 0, true);
    }

    public static ScanResult disabled() {
        return new ScanResult(0, 0, false);
    }

    public int total() {
        return entitiesObserved + connectionsObserved;
    }
}
