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
package ai.kompile.knowledgegraph.confidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultsEnableBoundedGraphLearningWithoutEnablingMebnForEveryCrawl() {
        KbConfig config = KbConfig.defaults();

        assertFalse(config.isMebnTheoryRegistrationOnCrawlEnabled());
        assertTrue(config.isMebnAutoEnableWhenOntologyBound());
        assertTrue(config.isGnnScoringOnCrawlEnabled());
        assertTrue(config.getGnnMaxNodes() > 0);
        assertTrue(config.getGnnMaxEdges() > config.getGnnMaxNodes());
        assertTrue(config.getGnnScoreBatchSize() > 0);
        assertTrue(config.getSimMaxNodesPerRun() > 0);
        assertTrue(config.getSimMaxEdgesPerRun() > config.getSimMaxNodesPerRun());
    }

    @Test
    void parsesClampsAndSerializesManagedGraphSettings() {
        KbConfig config = KbConfig.from(objectMapper.valueToTree(Map.of(
                "kbMebnAutoEnableWhenOntologyBound", false,
                "kbGnnScoringOnCrawlEnabled", true,
                "kbGnnMaxNodes", 123,
                "kbGnnMaxEdges", 456,
                "kbGnnScoreBatchSize", 0,
                "kbGnnSelfWeight", 2.0,
                "kbGnnNeighborWeight", -1.0,
                "kbSimMaxNodesPerRun", 789,
                "kbSimMaxEdgesPerRun", 987)));

        assertFalse(config.isMebnAutoEnableWhenOntologyBound());
        assertTrue(config.isGnnScoringOnCrawlEnabled());
        assertEquals(123, config.getGnnMaxNodes());
        assertEquals(456, config.getGnnMaxEdges());
        assertEquals(1, config.getGnnScoreBatchSize());
        assertEquals(1.0, config.getGnnSelfWeight());
        assertEquals(0.0, config.getGnnNeighborWeight());
        assertEquals(789, config.getSimMaxNodesPerRun());
        assertEquals(987, config.getSimMaxEdgesPerRun());

        Map<String, Object> serialized = config.toMap();
        assertEquals(123, serialized.get("kbGnnMaxNodes"));
        assertEquals(false, serialized.get("kbMebnAutoEnableWhenOntologyBound"));
        assertEquals(987, serialized.get("kbSimMaxEdgesPerRun"));
        assertTrue(KbConfig.keys().containsAll(serialized.keySet()));
    }
}
