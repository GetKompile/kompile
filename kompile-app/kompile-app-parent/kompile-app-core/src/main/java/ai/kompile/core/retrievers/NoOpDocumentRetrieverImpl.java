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

package ai.kompile.core.retrievers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Fallback DocumentRetriever that returns empty results when no real implementation is available.
 */
@Service
@ConditionalOnMissingBean(value = DocumentRetriever.class, ignored = NoOpDocumentRetrieverImpl.class)
public class NoOpDocumentRetrieverImpl implements DocumentRetriever {

    private static final Logger logger = LoggerFactory.getLogger(NoOpDocumentRetrieverImpl.class);

    public NoOpDocumentRetrieverImpl() {
        logger.warn("No DocumentRetriever implementation found — using NoOp fallback. Retrieval will return empty results.");
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        return Collections.emptyList();
    }

    @Override
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
        return Collections.emptyList();
    }
}
