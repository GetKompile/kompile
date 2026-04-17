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

package ai.kompile.app.tools;

import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes the {@link ResultReferenceCache} as an MCP tool so agents can pull
 * back the full payload of a truncated response using the {@code result_id}
 * handle they received. Also provides a targeted slice API for paginating over
 * large list-valued fields.
 */
@Component
public class ResultFetchTool {

    private static final Logger log = LoggerFactory.getLogger(ResultFetchTool.class);

    private final ResultReferenceCache cache;

    @Autowired
    public ResultFetchTool(ResultReferenceCache cache) {
        this.cache = cache;
    }

    public record FetchResultInput(String resultId, String key, Integer offset, Integer limit) {}

    @Tool(name = "fetch_result",
          description = "Retrieves a previously cached tool response by its result_id. " +
                        "Optionally pass 'key' to drill into a specific field of a map result, " +
                        "and 'offset' / 'limit' to paginate when that field is a list.")
    public Map<String, Object> fetchResult(FetchResultInput input) {
        if (input == null || input.resultId() == null || input.resultId().isBlank()) {
            return errorResponse("resultId is required");
        }

        String key = input.key();
        int offset = input.offset() != null ? input.offset() : 0;
        int limit = input.limit() != null ? input.limit() : 0;

        Optional<Object> value;
        if ((key != null && !key.isBlank()) || offset > 0 || limit > 0) {
            value = cache.getSlice(input.resultId(), key, offset, limit);
        } else {
            value = cache.get(input.resultId());
        }

        if (value.isEmpty()) {
            log.info("fetch_result: result_id '{}' not found or expired", input.resultId());
            return errorResponse("result_id not found or expired: " + input.resultId());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result_id", input.resultId());
        if (key != null && !key.isBlank()) {
            response.put("key", key);
        }
        response.put("payload", value.get());
        return response;
    }

    private static Map<String, Object> errorResponse(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message);
        return error;
    }
}
