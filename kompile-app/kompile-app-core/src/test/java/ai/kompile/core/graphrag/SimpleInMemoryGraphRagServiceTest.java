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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.model.Graph;

/**
 * Concrete test class for SimpleInMemoryGraphRagService.
 * <p>
 * This class extends the contract tests to verify that the
 * SimpleInMemoryGraphRagService implementation meets all the
 * expected behaviors of a GraphRagService.
 * </p>
 */
public class SimpleInMemoryGraphRagServiceTest extends GraphRagServiceContractTest {

    @Override
    protected GraphRagService createService() {
        SimpleInMemoryGraphRagService service = new SimpleInMemoryGraphRagService();
        // Pre-populate with test data
        service.setStoredGraph(createSimpleTestGraph());
        return service;
    }

    @Override
    protected void setupWithGraph(GraphRagService service, Graph graph) {
        if (service instanceof SimpleInMemoryGraphRagService) {
            ((SimpleInMemoryGraphRagService) service).setStoredGraph(graph);
        }
    }
}
