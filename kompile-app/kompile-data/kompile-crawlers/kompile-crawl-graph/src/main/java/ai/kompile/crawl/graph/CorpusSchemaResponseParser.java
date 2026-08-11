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

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.format.LlmJsonExtractor;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

final class CorpusSchemaResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CorpusSchemaResponseParser() {
    }

    record ParseResult(
            GraphSchema schema,
            List<String> errors) {

        ParseResult {
            errors = errors == null
                    ? List.of()
                    : List.copyOf(errors);
        }

        boolean valid() {
            return schema != null && errors.isEmpty();
        }
    }

    static ParseResult parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new ParseResult(null, List.of("[SCHEMA_RESPONSE] Model response was blank"));
        }

        String extractedJson = LlmJsonExtractor.extractJsonObject(rawResponse);
        if (extractedJson == null || extractedJson.isBlank()) {
            return new ParseResult(null, List.of("[SCHEMA_JSON] Model response did not contain extractable JSON"));
        }

        try {
            GraphSchema schema = OBJECT_MAPPER.readValue(extractedJson, GraphSchema.class);
            return new ParseResult(schema, List.of());
        } catch (JsonProcessingException exception) {
            String message = conciseErrorMessage(exception);
            return new ParseResult(null, List.of("[SCHEMA_JSON] " + message));
        }
    }

    private static String conciseErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "Unable to parse schema JSON response";
        }
        String trimmed = message.replace('\n', ' ').trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
