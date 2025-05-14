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

package ai.kompile.core.retrievers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;

@Service("noOpDocumentRetriever") // Explicit bean name for clarity
public class NoOpDocumentRetrieverImpl implements DocumentRetriever {
    private static final Logger logger = LoggerFactory.getLogger(NoOpDocumentRetrieverImpl.class);

    public NoOpDocumentRetrieverImpl() {
        logger.warn("No specific DocumentRetriever implementation found. Initializing NoOpDocumentRetrieverImpl. Document retrieval will return no results.");
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        logger.warn("NoOpDocumentRetriever called for query: {}. Returning empty list.", query);
        return Collections.emptyList();
    }
}