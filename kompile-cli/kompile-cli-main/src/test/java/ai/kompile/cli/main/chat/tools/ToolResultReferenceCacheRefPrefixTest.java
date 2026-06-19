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
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the {@code ref:} prefix mismatch (Bug 1).
 *
 * <p>{@link ToolResultReferenceCache#storeAndSummarize} stores results under a
 * BARE handle but the summary it returns displays the handle as
 * {@code ref:<handle>}. The displayed string must therefore resolve through
 * {@link ToolResultReferenceCache#getSlice}, as must the bare handle.
 */
class ToolResultReferenceCacheRefPrefixTest {

    @Test
    void getSliceResolvesBothPrefixedAndBareHandles() {
        ToolResultReferenceCache cache = new ToolResultReferenceCache();

        String content = "line one\nline two\nline three";
        ToolResult stored = cache.storeAndSummarize(
                "demo_tool", "Demo Title", content, null);

        // The handle returned in metadata is the BARE handle (no ref: prefix).
        Object handleObj = stored.getMetadata().get("result_id");
        assertNotNull(handleObj, "storeAndSummarize must expose result_id in metadata");
        String handle = handleObj.toString();
        assertFalse(handle.startsWith("ref:"),
                "stored result_id should be the bare handle, got: " + handle);

        // Bug 1 regression: the DISPLAYED "ref:<handle>" form must resolve.
        ToolResult prefixed = cache.getSlice("ref:" + handle, 0, 200);
        assertFalse(prefixed.isError(),
                "getSlice must resolve the displayed ref: form; got error: " + prefixed.getOutput());
        assertTrue(prefixed.getOutput().contains("line one"),
                "sliced output should contain stored content (first line)");
        assertTrue(prefixed.getOutput().contains("line three"),
                "sliced output should contain stored content (last line)");

        // The bare handle must also continue to resolve.
        ToolResult bare = cache.getSlice(handle, 0, 200);
        assertFalse(bare.isError(),
                "getSlice must resolve the bare handle; got error: " + bare.getOutput());
        assertTrue(bare.getOutput().contains("line two"),
                "bare-handle sliced output should contain stored content");
    }
}
