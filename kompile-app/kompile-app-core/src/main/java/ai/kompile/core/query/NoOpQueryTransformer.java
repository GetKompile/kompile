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

package ai.kompile.core.query;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default no-op query transformer that passes through queries unchanged.
 */
@Component
public class NoOpQueryTransformer implements QueryTransformer {

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        return List.of(query);
    }

    @Override
    public String getName() {
        return "passthrough";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.PASSTHROUGH;
    }
}
