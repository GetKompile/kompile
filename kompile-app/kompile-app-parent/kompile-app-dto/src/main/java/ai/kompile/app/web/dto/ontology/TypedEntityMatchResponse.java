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
package ai.kompile.app.web.dto.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Response for alias-aware direct/inherited type membership queries. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TypedEntityMatchResponse {
    long factSheetId;
    String queryType;
    String resolvedType;
    boolean includeInherited;
    int matchCount;
    List<Match> matches;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Match {
        String nodeId;
        String title;
        List<String> matchedTypes;
        List<String> directTypes;
        List<String> inheritedTypes;
        double confidence;
    }
}
