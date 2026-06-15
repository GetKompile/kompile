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

package ai.kompile.cli.common.config;

/**
 * Controls how config files are merged during archive import.
 */
public enum ImportMode {

    /**
     * Append: merge imported configs with existing ones.
     * For JSON objects, imported keys are added/updated but existing keys
     * not present in the import are preserved.
     * For non-JSON files, imported files only overwrite if they don't already exist.
     */
    APPEND,

    /**
     * Override: imported configs completely replace existing ones.
     * Each file in the archive overwrites its counterpart on disk.
     */
    OVERRIDE
}
