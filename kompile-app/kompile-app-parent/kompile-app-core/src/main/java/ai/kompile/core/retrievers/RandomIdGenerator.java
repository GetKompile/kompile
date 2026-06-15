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

import java.util.Map;
import java.util.UUID;

/**
 * Default implementation of IdGenerator that generates random UUID-based identifiers.
 * 
 * This generator creates universally unique identifiers using Java's UUID.randomUUID() method,
 * ensuring that each generated ID is unique across all instances and time.
 */
public class RandomIdGenerator implements IdGenerator {
    
    @Override
    public String generateId() {
        return UUID.randomUUID().toString();
    }
    
    @Override
    public String generateId(String content, Map<String, Object> metadata) {
        // For the random generator, we ignore content and metadata and always generate a random UUID
        return generateId();
    }
}
