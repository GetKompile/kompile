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

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FetchResultBatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private ToolResultReferenceCache cache;
    private FetchResultBatchTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        cache = new ToolResultReferenceCache(10, 60_000);
        tool = new FetchResultBatchTool(cache);
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("fetch-batch-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(om));
    }

    private ObjectNode params(ObjectNode... requests) {
        ObjectNode params = om.createObjectNode();
        ArrayNode arr = params.putArray("requests");
        for (ObjectNode r : requests) arr.add(r);
        return params;
    }

    private ObjectNode request(String id) {
        ObjectNode r = om.createObjectNode();
        r.put("result_id", id);
        return r;
    }

    @Test
    void batchUsesOneSharedOutputBudgetAndSupportsLongLineContinuation() throws Exception {
        String id = cache.store("read", "x".repeat(40_000) + "TAIL", Map.of());
        ObjectNode[] requests = new ObjectNode[20];
        for (int i = 0; i < requests.length; i++) requests[i] = request(id);
        ToolResult result = tool.execute(params(requests), context);
        assertFalse(result.isError());
        assertTrue(result.getOutput().length() <= ToolResultReferenceCache.MAX_FETCH_CHARS);
        assertTrue(result.getOutput().contains("20/20 slices read"));
        assertTrue(result.getOutput().contains("character_offset="));
        ObjectNode tailRequest = request(id);
        tailRequest.put("character_offset", 39_995);
        ToolResult tail = tool.execute(params(tailRequest), context);
        assertTrue(tail.getOutput().contains("xxxxxTAIL"));
    }

    @Test
    void singleFetchAcceptsItsReturnedLongLineContinuation() throws Exception {
        String id = cache.store("read", "x".repeat(20_000) + "TAIL", Map.of());
        FetchResultTool single = new FetchResultTool(cache);
        ObjectNode first = request(id);
        ToolResult page = single.execute(first, context);
        assertTrue(page.getOutput().length() <= ToolResultReferenceCache.MAX_FETCH_CHARS);
        first.put("offset", (int) page.getMetadata().get("nextOffset"));
        first.put("character_offset", (int) page.getMetadata().get("nextCharacterOffset"));
        ToolResult tail = single.execute(first, context);
        assertTrue(tail.getOutput().contains("TAIL"));
        assertFalse((boolean) tail.getMetadata().get("truncated"));
        assertTrue(single.parameterSchema().path("properties").has("character_offset"));
    }

    @Test
    void readsMultipleSlicesInOneCall() throws Exception {
        String idA = cache.store("grep", "alpha-1\nalpha-2\nalpha-3\n", Map.of());
        String idB = cache.store("read", "beta-1\nbeta-2\n", Map.of());

        ToolResult result = tool.execute(params(request(idA), request(idB)), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("2/2 slices read"));
        assertTrue(result.getOutput().contains("alpha-1"));
        assertTrue(result.getOutput().contains("beta-2"));
    }

    @Test
    void offsetLimitAndPatternPassThrough() throws Exception {
        String id = cache.store("grep", "one\ntwo\nthree\nfour\n", Map.of());

        ObjectNode windowed = request(id);
        windowed.put("offset", 2);
        windowed.put("limit", 2);
        ObjectNode filtered = request(id);
        filtered.put("pattern", "our");

        ToolResult result = tool.execute(params(windowed, filtered), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("two"));
        assertTrue(result.getOutput().contains("three"));
        assertTrue(result.getOutput().contains("four"));
        assertFalse(result.getOutput().lines()
                        .filter(l -> l.startsWith("== [1]")).findFirst().orElse("")
                        .contains("one"),
                "windowed slice should start at line 2");
    }

    @Test
    void expiredHandleReportsWithoutFailingOthers() throws Exception {
        String good = cache.store("grep", "hello\n", Map.of());

        ToolResult result = tool.execute(params(
                request("ref:does-not-exist"),
                request(good)), context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("1/2 slices read"));
        assertTrue(result.getOutput().contains("not found or expired"));
        assertTrue(result.getOutput().contains("hello"));
    }

    @Test
    void allExpiredIsAnError() throws Exception {
        ToolResult result = tool.execute(params(request("ref:gone")), context);
        assertTrue(result.isError());
    }
}
