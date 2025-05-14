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

package ai.kompile.core.rag; // Or a sub-package like ai.kompile.core.rag.defaults

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

// This service will be created ONLY if no other bean implementing RagService is found.
// Alternatively, you could use @Order(Ordered.LOWEST_PRECEDENCE)
@Service("noOpRagService")
public class NoOpRagServiceImpl implements RagService {

    private static final Logger logger = LoggerFactory.getLogger(NoOpRagServiceImpl.class);

    public NoOpRagServiceImpl() {
        logger.warn("No specific RagService implementation found. Initializing NoOpRagServiceImpl. RAG functionality will be disabled or limited.");
    }

    @Override
    public String answerQuery(RagQuery query) {
        String message = "RAG Service is not fully configured. Cannot process query: " + query.getQuery();
        logger.warn(message);
        // You could also return a more structured error or a specific DTO.
        return "Error: " + message;
    }
}