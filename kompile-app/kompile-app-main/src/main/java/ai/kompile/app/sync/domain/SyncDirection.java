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

package ai.kompile.app.sync.domain;

/**
 * Direction of bilateral sync between Kompile notes and an external provider.
 */
public enum SyncDirection {
    /** Sync in both directions. */
    BIDIRECTIONAL,
    /** Only push Kompile notes to the external provider. */
    KOMPILE_TO_EXTERNAL,
    /** Only pull from the external provider into Kompile. */
    EXTERNAL_TO_KOMPILE
}
