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

package ai.kompile.core.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Fallback RagService that returns empty results when no real implementation is available.
 */
@Service
@ConditionalOnMissingBean(value = RagService.class, ignored = NoOpRagServiceImpl.class)
public class NoOpRagServiceImpl implements RagService {

    private static final Logger logger = LoggerFactory.getLogger(NoOpRagServiceImpl.class);

    public NoOpRagServiceImpl() {
        logger.warn("No RagService implementation found — using NoOp fallback. RAG queries will return empty results.");
    }

    @Override
    public RagResult answerQuery(RagQuery query) {
        return new RagResult("RAG service not configured", "", Collections.emptyList());
    }
}