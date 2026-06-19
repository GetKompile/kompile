/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskEngineTest {

    @Test
    void defaultsToReact() {
        assertEquals(TaskEngine.REACT, TaskEngine.fromString(null));
        assertEquals(TaskEngine.REACT, TaskEngine.fromString(""));
        assertEquals(TaskEngine.REACT, TaskEngine.fromString("  "));
        assertEquals(TaskEngine.REACT, TaskEngine.fromString("react"));
        assertEquals(TaskEngine.REACT, TaskEngine.fromString("something-else"));
    }

    @Test
    void recognizesKompileCliAliases() {
        assertEquals(TaskEngine.KOMPILE_CLI, TaskEngine.fromString("kompile-cli"));
        assertEquals(TaskEngine.KOMPILE_CLI, TaskEngine.fromString("kompile_cli"));
        assertEquals(TaskEngine.KOMPILE_CLI, TaskEngine.fromString("kompile"));
        assertEquals(TaskEngine.KOMPILE_CLI, TaskEngine.fromString("CLI"));
        assertEquals(TaskEngine.KOMPILE_CLI, TaskEngine.fromString("exec"));
    }
}
